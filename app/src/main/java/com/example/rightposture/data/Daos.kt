package com.example.rightposture.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * §13.1's access layer.
 *
 * Every insert is `REPLACE` on the natural key rather than `ABORT`. That is what makes the write
 * path idempotent: 7.5 retries a failed sync by replaying the same rows, and a replay must be a
 * no-op rather than a constraint violation. It also means a rep re-recorded after a crash overwrites
 * itself cleanly instead of needing a delete first.
 */

@Dao
interface ExerciseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exercise: ExerciseEntity)

    @Query("SELECT * FROM exercises WHERE id = :id ORDER BY version DESC LIMIT 1")
    suspend fun latestVersionOf(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE id = :id AND version = :version")
    suspend fun find(id: String, version: Int): ExerciseEntity?

    @Query("SELECT * FROM exercises ORDER BY createdAt DESC")
    suspend fun all(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE id = :id ORDER BY version ASC")
    suspend fun versionsOf(id: String): List<ExerciseEntity>

    /**
     * Every version of one exercise. Its assignments go with it (`ON DELETE CASCADE`); its sessions
     * do not, because they carry no foreign key - a patient's history outlives the reference.
     */
    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun deleteAllVersions(id: String): Int
}

@Dao
interface AssignmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: AssignmentEntity)

    @Query(
        "SELECT * FROM assignments WHERE patientUid = :patientUid AND activeTo IS NULL " +
            "ORDER BY sequenceIndex ASC",
    )
    suspend fun activeFor(patientUid: String): List<AssignmentEntity>

    @Query("SELECT * FROM assignments ORDER BY sequenceIndex ASC")
    suspend fun all(): List<AssignmentEntity>

    /**
     * 7.4's Today list: what was prescribed, joined to the reference it will be scored against.
     *
     * The join is an inner join on purpose. An assignment whose exercise has not downloaded yet is
     * not something the patient can start, and listing it would put a row on Today that does
     * nothing when tapped. It reappears by itself once the bundle arrives.
     *
     * [patientUid] null means "everything on this device", which is what an unsigned-in build needs
     * - §13.3 will not let a missing account empty the screen.
     */
    @Query(
        "SELECT a.id AS assignmentId, a.exerciseId AS exerciseId, " +
            "a.exerciseVersion AS exerciseVersion, e.name AS name, e.rposePath AS rposePath, " +
            "e.mode AS mode, " +
            // The prescription wins when the physio set one; the reference's own duration is the
            // fallback. Resolved here so no screen has to know there are two places to look.
            "COALESCE(a.holdSeconds, e.holdSeconds) AS holdSeconds, " +
            "a.reps AS reps, a.sets AS sets, a.restSeconds AS restSeconds, " +
            "a.sequenceIndex AS sequenceIndex " +
            "FROM assignments a " +
            "JOIN exercises e ON e.id = a.exerciseId AND e.version = a.exerciseVersion " +
            "WHERE a.activeTo IS NULL AND (:patientUid IS NULL OR a.patientUid = :patientUid) " +
            "ORDER BY a.sequenceIndex ASC",
    )
    suspend fun prescribedFor(patientUid: String?): List<PrescribedExercise>
}

/** One row of §14.2's "Today": the prescription and the reference behind it, in one object. */
data class PrescribedExercise(
    val assignmentId: String,
    val exerciseId: String,
    val exerciseVersion: Int,
    val name: String,
    /** `cyclic` or `hold`, so Today can label the row without opening the bundle. */
    val mode: String,
    /** The reference's own hold duration, null for a cyclic exercise. */
    val holdSeconds: Int?,
    val rposePath: String,
    val reps: Int,
    val sets: Int,
    val restSeconds: Int,
    val sequenceIndex: Int,
)

@Dao
abstract class SessionDao {
    // An abstract class rather than an interface: Room needs a real method body for the
    // @Transaction below, and a suspend default method on an interface is what KSP chokes on.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRep(rep: RepEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertFeatureSamples(samples: List<FeatureSampleEntity>)

    /**
     * §13.1: "persist a `Rep` row **as each rep completes**, not at set end."
     *
     * The rep and its feature samples go down together or not at all. A rep row whose samples were
     * lost to a kill between the two writes would read downstream as a rep that scored nothing,
     * which §1.6 is emphatic is a different claim from a rep that scored badly.
     */
    @Transaction
    open suspend fun recordRep(rep: RepEntity, samples: List<FeatureSampleEntity>) {
        upsertRep(rep)
        if (samples.isNotEmpty()) upsertFeatureSamples(samples)
    }

    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract suspend fun session(id: String): SessionEntity?

    @Query("SELECT * FROM reps WHERE sessionId = :sessionId ORDER BY repIndex ASC")
    abstract suspend fun reps(sessionId: String): List<RepEntity>

    @Query("SELECT * FROM feature_samples WHERE sessionId = :sessionId AND repIndex = :repIndex")
    abstract suspend fun featureSamples(sessionId: String, repIndex: Int): List<FeatureSampleEntity>

    /**
     * Sessions that were started and never finished - §13.1's "killed mid-set is still partially
     * recoverable", asked as a query.
     *
     * `endedAt IS NULL` is the whole test. A session the app closed properly has a timestamp; one
     * the OS killed does not, and its reps are still on disk because each was written as it
     * completed.
     */
    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC")
    abstract suspend fun unfinishedSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    abstract suspend fun recentSessions(limit: Int): List<SessionEntity>

    /**
     * Every session recorded against one exercise, oldest first.
     *
     * `ASC` is not a preference. §6.21.2's window is the *last* three and §6.21.6 counts
     * *consecutive* sessions, so the order this list arrives in changes the verdict - which is why
     * the caller using [recentSessions] has to re-sort before it can evaluate anything.
     */
    @Query("SELECT * FROM sessions WHERE exerciseId = :exerciseId ORDER BY startedAt ASC")
    abstract suspend fun sessionsForExercise(exerciseId: String): List<SessionEntity>

    /**
     * The reps of many sessions in one query.
     *
     * Progression and analytics both need every rep of every session they consider, and asking per
     * session costs a round trip each - sixty of them to draw one screen. Bounded by SQLite's
     * 999-host-parameter limit, which is far above the sixty sessions the callers ask for; chunk the
     * caller if that ever grows into the hundreds.
     */
    @Query("SELECT * FROM reps WHERE sessionId IN (:sessionIds) ORDER BY sessionId ASC, repIndex ASC")
    abstract suspend fun repsForSessions(sessionIds: List<String>): List<RepEntity>

    /**
     * 7.5's queue: what the server has not been told about yet.
     *
     * A finished session qualifies immediately. An **unfinished** one qualifies once it is older
     * than [staleBefore], which is how a set the OS killed still reaches the physio - "the patient
     * started and something went wrong" is a clinically useful thing to see, and §13.1 kept those
     * reps on disk precisely so they would not be lost. Without the age test a live, in-progress
     * set would be uploaded mid-rep.
     */
    @Query(
        "SELECT * FROM sessions WHERE syncedAt IS NULL " +
            "AND (endedAt IS NOT NULL OR startedAt < :staleBefore) " +
            "ORDER BY startedAt ASC",
    )
    abstract suspend fun unsyncedSessions(staleBefore: Long): List<SessionEntity>

    @Query("UPDATE sessions SET syncedAt = :at WHERE id = :id")
    abstract suspend fun markSynced(id: String, at: Long)

    @Query("SELECT * FROM feature_samples WHERE sessionId = :sessionId")
    abstract suspend fun allFeatureSamples(sessionId: String): List<FeatureSampleEntity>

    @Query(
        "UPDATE sessions SET endedAt = :endedAt, setScore = :setScore, " +
            "completionRate = :completionRate, degradationOnsetRep = :degradationOnsetRep " +
            "WHERE id = :id",
    )
    abstract suspend fun finish(
        id: String,
        endedAt: Long,
        setScore: Double?,
        completionRate: Double?,
        degradationOnsetRep: Int?,
    )
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFault(fault: FaultEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCue(cue: CueEventEntity)

    @Query("SELECT * FROM fault_events WHERE sessionId = :sessionId ORDER BY tFireMs ASC")
    suspend fun faults(sessionId: String): List<FaultEventEntity>

    @Query(
        "SELECT c.* FROM cue_events c JOIN fault_events f ON c.faultEventId = f.id " +
            "WHERE f.sessionId = :sessionId",
    )
    suspend fun cues(sessionId: String): List<CueEventEntity>
}
