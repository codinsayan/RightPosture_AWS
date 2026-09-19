package com.example.rightposture.sync

import com.example.rightposture.data.AssignmentDao
import com.example.rightposture.data.AssignmentEntity
import com.example.rightposture.data.ExerciseDao
import com.example.rightposture.data.ExerciseEntity
import java.io.File
import java.util.UUID

/** What one prescription looks like when the physio writes it. §13.1's `Assignment`, as an input. */
data class Prescription(
    val exerciseId: String,
    val exerciseVersion: Int,
    val patientId: String,
    val reps: Int,
    val sets: Int,
    val restSeconds: Int,
    val sequenceIndex: Int,
    /**
     * Seconds per hold, for a §6.19 exercise. Null for a cyclic one, and null for a hold the physio
     * prescribes exactly as recorded - the exercise's own duration is the fallback, so the two
     * cannot disagree unless somebody deliberately made them.
     *
     * Last, and defaulted, so the positional callers that predate hold mode still read correctly.
     */
    val holdSeconds: Int? = null,
)

sealed interface SyncOutcome {
    /** [count] is how many rows moved - zero is a success, not a failure. */
    data class Done(val count: Int) : SyncOutcome

    /**
     * The server was unreachable. §13.3 treats this as ordinary: whatever was to be synced is still
     * on disk, and the next attempt will carry it.
     */
    data object Offline : SyncOutcome

    data class Failed(val message: String) : SyncOutcome
}

/**
 * 7.4's data path: a physio's reference travels to the patient it was prescribed to.
 *
 * ## Direction
 *
 * §13.3 makes Room the source of truth and the server a mirror, and that stays true here. The push
 * side writes what is already local; the pull side writes into Room only what the patient did not
 * have. Nothing on the server can delete or contradict a local row - the worst a broken sync can do
 * is leave the patient with the exercises they already had.
 *
 * ## Why the bundle bytes travel, not just the row
 *
 * §7.4 says "exercise metadata sync", but a metadata row whose `rpose_path` points into a bucket the
 * patient never fetched from is an exercise that appears on Today and cannot be started. The bundle
 * is what §9.6 makes immutable and what §4 scores against, so it goes with the row.
 */
class ExerciseSync(
    private val client: SupabaseClient,
    private val exercises: ExerciseDao,
    private val assignments: AssignmentDao,
    /** Where downloaded bundles land. The same directory authoring writes into. */
    private val bundleDirectory: File,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
) {

    // ---------------------------------------------------------------------------------------
    // Physio side
    // ---------------------------------------------------------------------------------------

    /**
     * Uploads one exercise's bundle and registers its row.
     *
     * The bundle goes up **before** the row. If it is done the other way round and the upload then
     * fails, the patient sees an exercise whose reference does not exist - which reads to them as a
     * broken app rather than as a sync that has not finished. A row with no upload behind it is the
     * one ordering that cannot be recovered from by retrying.
     */
    suspend fun pushExercise(exercise: ExerciseEntity, physioId: String): SyncOutcome {
        val local = File(exercise.rposePath)
        if (!local.isFile) {
            return SyncOutcome.Failed("The reference for ${exercise.name} is missing on this device")
        }

        val path = storagePath(physioId, exercise.id, exercise.version)
        val uploaded = retryingSupabase {
            client.upload(BUCKET, path, local.readBytes(), "application/zip")
        }
        if (uploaded.offline) return SyncOutcome.Offline
        if (!uploaded.ok) return SyncOutcome.Failed(uploaded.error ?: "Could not upload the reference")

        var demoPath: String? = null
        exercise.demoPath?.let { localDemo ->
            val demo = File(localDemo)
            if (demo.isFile) {
                val target = "$physioId/${exercise.id}/v${exercise.version}/demo.mp4"
                val sent = retryingSupabase { client.upload(BUCKET, target, demo.readBytes(), "video/mp4") }
                // §9 makes the demo optional, so a failed demo upload must not fail the exercise.
                // The patient can perform the movement from the cues; they cannot perform it from
                // nothing.
                if (sent.ok) demoPath = target
            }
        }

        val row = retryingSupabase {
            client.insert(
                "exercises",
                listOf(
                    mapOf(
                        "id" to exercise.id,
                        "version" to exercise.version,
                        "physio_id" to physioId,
                        "name" to exercise.name,
                        "mode" to exercise.mode,
                        "hold_seconds" to exercise.holdSeconds,
                        "rpose_path" to path,
                        "demo_path" to demoPath,
                        "created_at" to iso8601(exercise.createdAt),
                    ),
                ),
                // §9.6: `(id, version)` is immutable, so re-pushing the same version is a no-op
                // rather than an edit. An edit is a new version, and therefore a new row.
                upsert = true,
            )
        }
        if (row.offline) return SyncOutcome.Offline
        if (!row.ok) return SyncOutcome.Failed(row.error ?: "Could not register the exercise")

        // Now that it is on the server it belongs to a physio locally too, which is what lets a
        // later push know it has already been claimed.
        if (exercise.physioUid != physioId) exercises.upsert(exercise.copy(physioUid = physioId))
        return SyncOutcome.Done(1)
    }

    /**
     * Prescribes an exercise to a linked patient.
     *
     * The id is generated here rather than by the database. That is what makes a retry idempotent:
     * a request that timed out after the server committed it would otherwise be replayed as a second
     * prescription, and the patient would be told to do the same exercise twice.
     */
    suspend fun assign(prescription: Prescription): SyncOutcome {
        val id = newId()
        val response = retryingSupabase {
            client.insert(
                "assignments",
                listOf(
                    mapOf(
                        "id" to id,
                        "exercise_id" to prescription.exerciseId,
                        "exercise_version" to prescription.exerciseVersion,
                        "patient_id" to prescription.patientId,
                        "reps" to prescription.reps,
                        "sets" to prescription.sets,
                        "rest_seconds" to prescription.restSeconds,
                        "hold_seconds" to prescription.holdSeconds,
                        "sequence_index" to prescription.sequenceIndex,
                    ),
                ),
                upsert = true,
            )
        }
        return when {
            response.offline -> SyncOutcome.Offline
            !response.ok -> SyncOutcome.Failed(response.error ?: "Could not assign that exercise")
            else -> SyncOutcome.Done(1)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Patient side
    // ---------------------------------------------------------------------------------------

    /**
     * Pulls everything prescribed to [patientId] into Room, fetching any bundle not already here.
     *
     * Each assignment is handled independently. One exercise whose bundle will not download must not
     * stop the other four from appearing - a patient with four of their five exercises can still
     * train today.
     */
    suspend fun pullAssignments(patientId: String): SyncOutcome {
        val response = retryingSupabase {
            client.select(
                "assignments",
                "?patient_id=eq.$patientId&active_to=is.null&order=sequence_index.asc" +
                    "&select=id,exercise_id,exercise_version,reps,sets,rest_seconds,hold_seconds," +
                    "sequence_index," +
                    "exercises(name,rpose_path,demo_path,physio_id,created_at,mode,hold_seconds)",
            )
        }
        if (response.offline) return SyncOutcome.Offline
        if (!response.ok) return SyncOutcome.Failed(response.error ?: "Could not fetch your exercises")

        var stored = 0
        for (raw in response.asArray()) {
            val row = raw as? Map<*, *> ?: continue
            val exerciseId = row["exercise_id"] as? String ?: continue
            val version = intOf(row["exercise_version"]) ?: continue
            val assignmentId = row["id"] as? String ?: continue
            val embedded = row["exercises"] as? Map<*, *>

            // The bundle is fetched only when it is not already on disk. §9.6's immutability is what
            // makes that safe: `(id, version)` never changes content, so a local copy can never be
            // stale, only absent.
            if (exercises.find(exerciseId, version) == null) {
                val stashed = fetchBundle(exerciseId, version, embedded) ?: continue
                exercises.upsert(stashed)
            }

            assignments.upsert(
                AssignmentEntity(
                    id = assignmentId,
                    exerciseId = exerciseId,
                    exerciseVersion = version,
                    patientUid = patientId,
                    reps = intOf(row["reps"]) ?: 0,
                    sets = intOf(row["sets"]) ?: 1,
                    restSeconds = intOf(row["rest_seconds"]) ?: 0,
                    // The prescription's own duration when the physio set one, otherwise the
                    // reference's. A hold with neither is still startable; it just has no target.
                    holdSeconds = intOf(row["hold_seconds"]) ?: intOf(embedded?.get("hold_seconds")),
                    sequenceIndex = intOf(row["sequence_index"]) ?: 0,
                    activeFrom = now(),
                    activeTo = null,
                ),
            )
            stored++
        }
        return SyncOutcome.Done(stored)
    }

    private suspend fun fetchBundle(
        exerciseId: String,
        version: Int,
        embedded: Map<*, *>?,
    ): ExerciseEntity? {
        val remotePath = embedded?.get("rpose_path") as? String ?: return null
        val bytes = client.download(BUCKET, remotePath) ?: return null

        bundleDirectory.mkdirs()
        val stored = writeWhole("${exerciseId}_v$version.rpose", bytes) ?: return null

        // §9's demo travels too. It is fetched after the bundle and its failure is swallowed,
        // because §9 makes it optional: a patient can perform the movement from the cues, and
        // losing the whole exercise over a video that would not download would be the wrong trade.
        val demo = (embedded["demo_path"] as? String)
            ?.let { client.download(BUCKET, it) }
            ?.let { writeWhole("${exerciseId}_v$version.mp4", it) }

        return ExerciseEntity(
            id = exerciseId,
            version = version,
            physioUid = embedded["physio_id"] as? String,
            name = embedded["name"] as? String ?: exerciseId,
            // A row with no mode was written before hold mode existed, and could only have been
            // cyclic. Defaulting here is reading the old data correctly, not guessing at it.
            mode = embedded["mode"] as? String ?: "cyclic",
            holdSeconds = intOf(embedded["hold_seconds"]),
            rposePath = stored.absolutePath,
            demoPath = demo?.absolutePath,
            // The physio's timestamp, not this device's. "Recorded 3 March" has to mean the day it
            // was recorded, not the day the patient's phone happened to download it.
            createdAt = parseIso8601(embedded["created_at"] as? String) ?: now(),
        )
    }

    /**
     * Writes bytes to [name], via a `.part` file that is renamed once whole.
     *
     * A half-written file is worse than an absent one: the row would point at something that fails
     * to open in front of the patient, which reads as a broken app rather than a sync still in
     * progress.
     */
    private fun writeWhole(name: String, bytes: ByteArray): File? {
        val file = File(bundleDirectory, name)
        val staging = File(bundleDirectory, "$name.part")
        return runCatching {
            staging.writeBytes(bytes)
            if (file.exists()) file.delete()
            check(staging.renameTo(file)) { "could not move $name into place" }
            file
        }.onFailure { staging.delete() }.getOrNull()
    }

    private fun storagePath(physioId: String, exerciseId: String, version: Int) =
        "$physioId/$exerciseId/v$version.rpose"

    /** Every JSON number arrives as a Double from the project's own parser. */
    private fun intOf(value: Any?): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        else -> null
    }

    private companion object {
        const val BUCKET = "exercises"
    }
}
