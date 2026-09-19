package com.example.rightposture.data

import com.example.rightposture.core.Params
import com.example.rightposture.core.live.FatigueResult
import com.example.rightposture.core.live.RepRecord
import com.example.rightposture.core.live.SessionRecord
import com.example.rightposture.core.live.spectralArcLength
import com.example.rightposture.core.rpose.RposeManifest
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The write path §13.1 specifies: **a `Rep` row as each rep completes, not at set end.**
 *
 * That sentence is the whole design. Everything here exists to make the moment of the write early
 * enough that a kill mid-set loses at most the rep in progress, and to make each write independent
 * so that losing one does not invalidate the ones before it.
 *
 * The repository is deliberately dumb about *when* to call [recordRep] - that is the caller's edge
 * detection, because only the caller knows a rep finished. What it owns is that a call is durable
 * before it returns.
 */
class SessionRepository(
    private val sessions: SessionDao,
    private val exercises: ExerciseDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Opens a session row before the first rep exists.
     *
     * Written up front rather than at the first rep so that a set abandoned after zero reps is still
     * a fact on disk. "The patient started and did nothing" and "the patient never started" are
     * different clinical observations, and only the first one is evidence.
     */
    suspend fun startSession(
        manifest: RposeManifest?,
        prescribedReps: Int,
        assignmentId: String? = null,
        patientUid: String? = null,
        deviceFps: Double? = null,
        delegate: String? = null,
    ): String {
        val id = newId()
        sessions.upsert(
            SessionEntity(
                id = id,
                assignmentId = assignmentId,
                patientUid = patientUid,
                exerciseId = manifest?.id,
                // §13.4: the session is bound to the version it was scored against, so a physio
                // publishing an edit mid-set cannot retroactively change what this was measured
                // against.
                exerciseVersion = manifest?.version,
                startedAt = now(),
                endedAt = null,
                prescribedReps = prescribedReps,
                setScore = null,
                completionRate = null,
                degradationOnsetRep = null,
                deviceFps = deviceFps,
                delegate = delegate,
            ),
        )
        return id
    }

    /**
     * Persists one finished rep and its per-feature samples, in a single transaction.
     *
     * @param sampleRateHz the frame rate the velocity profile was captured at, needed because
     *   §6.18.5's SPARC is a frequency-domain measure and is meaningless without it.
     */
    suspend fun recordRep(
        sessionId: String,
        record: RepRecord,
        sampleRateHz: Double = Params.NOMINAL_FPS.toDouble(),
    ) {
        val rep = RepEntity(
            sessionId = sessionId,
            repIndex = record.index,
            quality = record.quality,
            romDegrees = record.romDegrees,
            durationMs = (record.durationSeconds * 1000).roundToInt(),
            valid = record.valid,
            rejection = record.rejection?.name,
            // Reduced here rather than stored raw: the profile is ~90 doubles per rep and §13.1
            // asks for one number. Null when the rep was too short to transform, which is an
            // abstention and not a zero.
            smoothnessSparc = spectralArcLength(record.velocityProfile, sampleRateHz),
            recordedAt = now(),
        )
        val samples = record.features.values.map {
            FeatureSampleEntity(
                sessionId = sessionId,
                repIndex = record.index,
                feature = it.feature.value,
                meanZ = it.meanZ,
                maxZ = it.maxZ,
                meanSignedZ = it.meanSignedZ,
                frames = it.frames,
            )
        }
        sessions.recordRep(rep, samples)
    }

    /**
     * Closes the session. Everything written here is a **summary** of rows that are already durable,
     * so losing this write costs the set score and nothing else - the reps survive it.
     */
    suspend fun finishSession(
        sessionId: String,
        record: SessionRecord,
        fatigue: FatigueResult? = null,
    ) {
        sessions.finish(
            id = sessionId,
            endedAt = now(),
            setScore = record.setQuality,
            completionRate = record.completionRate,
            degradationOnsetRep = fatigue?.changePointRep,
        )
    }

    /** §13.1's recovery question: which sessions were started and never closed? */
    suspend fun unfinishedSessions(): List<SessionEntity> = sessions.unfinishedSessions()

    /** The reps that survived, for a session that was killed or one that finished. */
    suspend fun repsOf(sessionId: String): List<RepEntity> = sessions.reps(sessionId)

    /**
     * The newest version stored under [id], or null if this is the first.
     *
     * Section 9.6 again: saving under a name that already exists publishes the next version, and
     * the caller needs to know what that is before it assembles the bundle.
     */
    suspend fun latestVersionOf(id: String): ExerciseEntity? = exercises.latestVersionOf(id)

    /** Every reference stored on this device, newest first. */
    suspend fun allExercises(): List<ExerciseEntity> = exercises.all()

    /** Records the exercise a session will be scored against, so the binding in §13.4 resolves. */
    suspend fun saveExercise(
        manifest: RposeManifest,
        rposePath: String,
        demoPath: String? = null,
        physioUid: String? = null,
    ) {
        exercises.upsert(
            ExerciseEntity(
                id = manifest.id,
                version = manifest.version,
                physioUid = physioUid,
                name = manifest.name,
                mode = manifest.mode,
                holdSeconds = manifest.prescription.holdSeconds,
                rposePath = rposePath,
                demoPath = demoPath,
                createdAt = now(),
            ),
        )
    }
}

/**
 * Persists every rep the engine has finished that is not on disk yet, and reports the new
 * watermark.
 *
 * Extracted from the UI sampler because the ordering here is the whole durability guarantee and it
 * is the one part of the write path that cannot be checked by reading it. The rule: the watermark
 * only advances past a rep that is **actually stored**. Advancing it first would mark a rep durable
 * that a failed write never wrote, and nothing would ever retry it - losing exactly the rep this
 * path exists to keep.
 *
 * Stops at the first failure rather than skipping past it, so reps stay in order and the next call
 * retries from the same place. The `(sessionId, repIndex)` upsert makes that retry free.
 *
 * @return the index of the last rep known to be on disk, to be passed back as [alreadyPersisted].
 */
suspend fun SessionRepository.persistPending(
    sessionId: String,
    reps: List<RepRecord>,
    alreadyPersisted: Int,
    onFailure: (Throwable) -> Unit = {},
): Int {
    var watermark = alreadyPersisted
    for (rep in reps.drop(alreadyPersisted)) {
        val written = runCatching { recordRep(sessionId, rep) }
        val failure = written.exceptionOrNull()
        if (failure != null) {
            onFailure(failure)
            return watermark
        }
        watermark = rep.index
    }
    return watermark
}
