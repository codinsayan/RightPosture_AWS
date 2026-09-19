package com.example.rightposture.sync

import com.example.rightposture.data.EventDao
import com.example.rightposture.data.SessionDao
import com.example.rightposture.data.SessionEntity

/**
 * §14.1's report row, as the physio's phone receives it.
 *
 * A separate type from [SessionEntity] on purpose: this is somebody else's session, read from the
 * mirror, and it is not written to Room. Reusing the local entity would invite exactly the confusion
 * §13.3 forbids - that a row pulled from the server is as authoritative as one the device recorded.
 */
data class RemoteSession(
    val id: String,
    val patientId: String?,
    val exerciseId: String?,
    val exerciseVersion: Int?,
    val startedAt: String?,
    val endedAt: String?,
    val prescribedReps: Int,
    val setScore: Double?,
    val completionRate: Double?,
    val degradationOnsetRep: Int?,
    val countedReps: Int,
) {
    /** §13.1's killed-mid-set case, which the physio has to be able to tell from a finished one. */
    val unfinished: Boolean get() = endedAt == null
}

/**
 * §13.3's mirror for performed work: Room to Postgres, one direction, never back.
 *
 * ## Idempotency
 *
 * Every table is keyed so a replay is a no-op: `sessions` by the id the device generated,
 * `reps` by `(session_id, rep_index)`, `feature_samples` by `(session_id, rep_index, feature)`,
 * and the event tables by ids that were minted when the events happened. That is the whole
 * mechanism - there is no dedup pass, because the key shape makes one unnecessary. A retry that
 * duplicates a request writes the same rows over themselves.
 *
 * ## Ordering
 *
 * Children follow parents, because the server declares the foreign keys: session, then reps, then
 * feature samples; fault events, then cue events. A partial push is therefore always a *prefix* of
 * a complete one - never a set of orphans - and the retry that follows it simply replays from the
 * top.
 */
class SessionSync(
    private val client: SupabaseClient,
    private val sessions: SessionDao,
    private val events: EventDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Pushes everything queued for [patientId], marking each session as it lands.
     *
     * Sessions are marked one at a time rather than in a batch at the end. If the network drops
     * after three of five, those three are done and the next run starts at the fourth - marking the
     * batch at the end would either re-send all five or, worse, mark two that never left.
     */
    suspend fun pushPending(patientId: String): SyncOutcome {
        val queue = sessions.unsyncedSessions(now() - STALE_AFTER_MS)
        var pushed = 0
        for (session in queue) {
            when (val outcome = pushSession(session, patientId)) {
                is SyncOutcome.Done -> {
                    sessions.markSynced(session.id, now())
                    pushed++
                }
                // Offline stops the run. The rest of the queue is still queued, and hammering a
                // server that is not there wastes the battery of someone who has just exercised.
                SyncOutcome.Offline -> return if (pushed > 0) SyncOutcome.Done(pushed) else outcome
                is SyncOutcome.Failed -> return outcome
            }
        }
        return SyncOutcome.Done(pushed)
    }

    /** One session and everything hanging off it. */
    suspend fun pushSession(session: SessionEntity, patientId: String): SyncOutcome {
        val reps = sessions.reps(session.id)

        val head = retryingSupabase {
            client.insert(
                "sessions",
                listOf(
                    mapOf(
                        "id" to session.id,
                        "assignment_id" to session.assignmentId,
                        // The signed-in patient, not the column - a session recorded before anyone
                        // signed in has a null `patientUid`, and RLS needs an owner to accept it.
                        "patient_id" to patientId,
                        // §13.4's binding, copied at set start and never recomputed. A physio who
                        // publishes v2 while this is in flight does not retag it: §9.6 makes
                        // (id, version) immutable, so v1 still names exactly what was scored.
                        "exercise_id" to session.exerciseId,
                        "exercise_version" to session.exerciseVersion,
                        "started_at" to iso8601(session.startedAt),
                        "ended_at" to session.endedAt?.let { iso8601(it) },
                        "prescribed_reps" to session.prescribedReps,
                        "set_score" to session.setScore,
                        "completion_rate" to session.completionRate,
                        "degradation_onset_rep" to session.degradationOnsetRep,
                        "device_fps" to session.deviceFps,
                        "delegate" to session.delegate,
                    ),
                ),
                upsert = true,
            )
        }
        head.failure()?.let { return it }

        if (reps.isNotEmpty()) {
            val rows = reps.map { rep ->
                mapOf(
                    "session_id" to rep.sessionId,
                    "rep_index" to rep.repIndex,
                    // §1.6: null means "not scored". It must survive the wire as null and never be
                    // coerced to zero, because "not scored" and "scored 0" are different claims.
                    "quality" to rep.quality,
                    "rom_degrees" to rep.romDegrees,
                    "duration_ms" to rep.durationMs,
                    "valid" to rep.valid,
                    "rejection" to rep.rejection,
                    "smoothness_sparc" to rep.smoothnessSparc,
                )
            }
            retryingSupabase { client.insert("reps", rows, upsert = true) }.failure()
                ?.let { return it }

            val samples = sessions.allFeatureSamples(session.id).map { sample ->
                mapOf(
                    "session_id" to sample.sessionId,
                    "rep_index" to sample.repIndex,
                    "feature" to sample.feature,
                    "mean_z" to sample.meanZ,
                    "max_z" to sample.maxZ,
                    "mean_signed_z" to sample.meanSignedZ,
                    "frames" to sample.frames,
                )
            }
            if (samples.isNotEmpty()) {
                retryingSupabase { client.insert("feature_samples", samples, upsert = true) }
                    .failure()?.let { return it }
            }
        }

        val faults = events.faults(session.id)
        if (faults.isNotEmpty()) {
            val rows = faults.map {
                mapOf(
                    "id" to it.id,
                    "session_id" to it.sessionId,
                    "rep_index" to it.repIndex,
                    "fault_id" to it.faultId,
                    "z_at_fire" to it.zAtFire,
                    "t_fire_ms" to it.tFireMs,
                )
            }
            retryingSupabase { client.insert("fault_events", rows, upsert = true) }.failure()
                ?.let { return it }

            val cues = events.cues(session.id)
            if (cues.isNotEmpty()) {
                val cueRows = cues.map {
                    mapOf(
                        "id" to it.id,
                        "fault_event_id" to it.faultEventId,
                        "cue_variant" to it.cueVariant,
                        "z_at_fire" to it.zAtFire,
                        // §17's cue effectiveness. Null because the set ended before the effect
                        // could be observed, which is not the same as a cue that failed.
                        "z_at_2s" to it.zAt2s,
                        "z_next_rep" to it.zNextRep,
                        "resolved" to it.resolved,
                    )
                }
                retryingSupabase { client.insert("cue_events", cueRows, upsert = true) }.failure()
                    ?.let { return it }
            }
        }

        return SyncOutcome.Done(1)
    }

    /**
     * §14.1's report: the sets performed against exercises [physioId] owns.
     *
     * No physio filter is sent. The `physio reads sessions on own exercises` policy already decides
     * this, and a client-side filter on top would either duplicate that rule or quietly contradict
     * it. [physioId] is taken so the call reads as what it is at every call site.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun pullReport(physioId: String, limit: Int = 50): List<RemoteSession> {
        val response = retryingSupabase {
            client.select(
                "sessions",
                "?order=started_at.desc&limit=$limit" +
                    "&select=id,patient_id,exercise_id,exercise_version,started_at,ended_at," +
                    "prescribed_reps,set_score,completion_rate,degradation_onset_rep,reps(valid)",
            )
        }
        if (!response.ok) return emptyList()

        return response.asArray().mapNotNull { raw ->
            val row = raw as? Map<*, *> ?: return@mapNotNull null
            RemoteSession(
                id = row["id"] as? String ?: return@mapNotNull null,
                patientId = row["patient_id"] as? String,
                exerciseId = row["exercise_id"] as? String,
                exerciseVersion = (row["exercise_version"] as? Number)?.toInt(),
                startedAt = row["started_at"] as? String,
                endedAt = row["ended_at"] as? String,
                prescribedReps = (row["prescribed_reps"] as? Number)?.toInt() ?: 0,
                setScore = (row["set_score"] as? Number)?.toDouble(),
                completionRate = (row["completion_rate"] as? Number)?.toDouble(),
                degradationOnsetRep = (row["degradation_onset_rep"] as? Number)?.toInt(),
                countedReps = (row["reps"] as? List<*>)
                    ?.count { (it as? Map<*, *>)?.get("valid") == true } ?: 0,
            )
        }
    }

    /** Maps a response onto the outcome to abandon the push with, or null to carry on. */
    private fun SupabaseResponse.failure(): SyncOutcome? = when {
        ok -> null
        offline -> SyncOutcome.Offline
        else -> SyncOutcome.Failed(error ?: "Could not upload the session")
    }

    private companion object {
        /**
         * How long an unfinished session waits before it is treated as abandoned rather than live.
         *
         * Longer than any plausible set, so a patient resting between sets never has their
         * in-progress session uploaded out from under them.
         */
        const val STALE_AFTER_MS = 30L * 60 * 1000
    }
}
