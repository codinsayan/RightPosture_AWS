package com.example.rightposture.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * §13.1's local schema. Room is the **source of truth**, not a cache: §13.3 requires the entire
 * patient session to work with no network, so nothing here is nullable merely because a server has
 * not answered yet.
 *
 * Two deliberate departures from §13.1's field sketch, both documented where they occur: there is
 * no `User` table (7.3 owns identity, and 7.1 must work with nobody signed in), and `Rep` stores
 * the rejection reason in place of a separate `partial` flag.
 */

/**
 * §13.1's `Exercise`, keyed by `(id, version)` because §9.6 makes bundles immutable - an edit is a
 * new row, never an update. A session recorded against version 1 can still name exactly what it was
 * scored against after the physio has published version 2.
 */
@Entity(tableName = "exercises", primaryKeys = ["id", "version"], indices = [Index("name")])
data class ExerciseEntity(
    val id: String,
    val version: Int,
    /** Null until 7.3 has an authenticated physio. */
    val physioUid: String?,
    val name: String,
    /**
     * `cyclic` or `hold`, copied from the bundle's manifest at save time.
     *
     * Denormalised so a list of exercises can be tagged and filtered without opening every zip on
     * disk, which is what Today would otherwise have to do to draw a row. It cannot drift from the
     * file it describes: §9.6 makes bundles immutable, so the manifest this was copied from can
     * never be edited, only superseded by a new version that copies its own.
     */
    val mode: String = "cyclic",
    /**
     * §9.1's `prescription.hold_s` for a hold, null for a cyclic exercise.
     *
     * Denormalised for the same reason [mode] is: Today draws "3 x 30s hold" from a list query, and
     * opening every bundle on disk to find one integer would make the screen wait on the filesystem.
     */
    val holdSeconds: Int? = null,
    /** Path to the `.rpose` bundle on local storage. */
    val rposePath: String,
    /** §9's optional `demo.mp4`, once one is recorded. */
    val demoPath: String?,
    val createdAt: Long,
)

/** §13.1's `Assignment`. Not written by 7.1 - the table exists so 7.4 has somewhere to sync into. */
@Entity(
    tableName = "assignments",
    foreignKeys = [
        ForeignKey(
            entity = ExerciseEntity::class,
            parentColumns = ["id", "version"],
            childColumns = ["exerciseId", "exerciseVersion"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("exerciseId", "exerciseVersion"), Index("patientUid")],
)
data class AssignmentEntity(
    @PrimaryKey val id: String,
    val exerciseId: String,
    val exerciseVersion: Int,
    val patientUid: String?,
    val reps: Int,
    val sets: Int,
    val restSeconds: Int,
    /**
     * Seconds per hold as prescribed, which need not be what was recorded: a thirty-second
     * reference can be prescribed at fifteen for a patient starting out. Null means "as recorded",
     * and the exercise's own duration is used.
     */
    val holdSeconds: Int? = null,
    val sequenceIndex: Int,
    val activeFrom: Long,
    val activeTo: Long?,
)

/**
 * §13.1's `Session`, one per set.
 *
 * [assignmentId] and [patientUid] are nullable on purpose. In 7.1 the physio records a reference and
 * immediately performs a set against it: there is no assignment, and there is nobody signed in. A
 * schema that could not represent that would fail the airplane-mode gate on its first row.
 *
 * [endedAt] null is what makes a session *recoverable* rather than merely incomplete - see
 * [SessionDao.unfinishedSessions].
 */
@Entity(tableName = "sessions", indices = [Index("assignmentId"), Index("startedAt")])
data class SessionEntity(
    @PrimaryKey val id: String,
    val assignmentId: String?,
    val patientUid: String?,
    val exerciseId: String?,
    val exerciseVersion: Int?,
    val startedAt: Long,
    /** Null while the set is in progress, and after a kill. */
    val endedAt: Long?,
    val prescribedReps: Int,
    /** §6.17.4, written at finish. */
    val setScore: Double?,
    val completionRate: Double?,
    /** §6.18.3's change point, null when fatigue never fired or there were too few reps. */
    val degradationOnsetRep: Int?,
    val deviceFps: Double?,
    /** NPU / GPU / CPU, per §12.2's logging requirement. */
    val delegate: String?,
    /**
     * When this session was mirrored to the server, or null if it has not been (7.5).
     *
     * A column rather than a separate outbox table. The queue is "sessions where this is null", and
     * one column cannot disagree with the row it describes - an outbox can, if a write lands in one
     * and not the other. §13.3 makes Room authoritative, so the record of what has been *mirrored*
     * belongs on the record itself.
     *
     * It is deliberately not cleared when a session changes. Nothing edits a finished session, so
     * "synced once" and "up to date" are the same statement here.
     */
    val syncedAt: Long? = null,
)

/**
 * §13.1's `Rep`, keyed by `(sessionId, index)` rather than a surrogate id.
 *
 * The natural key is what makes §7.5's retry idempotent: an upsert of rep 4 of a session is the
 * same row every time, so a duplicated sync is a no-op instead of a duplicated rep.
 *
 * §13.1 lists both `valid` and `partial`. Only [rejection] is stored: `partial` is derivable from
 * it and two columns that can disagree about the same fact are a bug waiting to happen. [quality]
 * stays nullable for §1.6's reason - absent means "not scored", never zero.
 */
@Entity(
    tableName = "reps",
    primaryKeys = ["sessionId", "repIndex"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RepEntity(
    val sessionId: String,
    /** 1-based, counting every finished rep - §6.18.3's change point indexes this. */
    val repIndex: Int,
    val quality: Double?,
    val romDegrees: Double,
    val durationMs: Int,
    val valid: Boolean,
    /** Null when [valid]; §6.12.4's reason otherwise. `partial` in §13.1's sketch. */
    val rejection: String?,
    /**
     * §6.18.5's smoothness term, as spectral arc length.
     *
     * §13.1 calls this column `jerk`. Named for what it actually holds instead: SPARC is a
     * frequency-domain measure and is not jerk, and a report that says "jerk" while showing SPARC
     * would mislead the one person qualified to interpret it. Null when the rep was too short to
     * transform.
     */
    val smoothnessSparc: Double?,
    val recordedAt: Long,
)

/**
 * §13.1's `FeatureSample`, one row per watched feature per rep.
 *
 * [meanSignedZ] is the reason this table exists: §6.18.5 attributes degradation on the **signed**
 * mean, because a joint drifting into valgus and one drifting out of it are opposite findings that
 * share a magnitude.
 */
@Entity(
    tableName = "feature_samples",
    primaryKeys = ["sessionId", "repIndex", "feature"],
    foreignKeys = [
        ForeignKey(
            entity = RepEntity::class,
            parentColumns = ["sessionId", "repIndex"],
            childColumns = ["sessionId", "repIndex"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FeatureSampleEntity(
    val sessionId: String,
    val repIndex: Int,
    /** [com.example.rightposture.core.FeatureId.value] - the stable wire identifier, not the enum name. */
    val feature: String,
    val meanZ: Double,
    val maxZ: Double,
    val meanSignedZ: Double,
    /** Frames this feature was scored on. A mean over 3 frames is not a mean over 90. */
    val frames: Int,
)

/** §13.1's `FaultEvent`. §6.14 fires these; 8.1 gives them wording. */
@Entity(
    tableName = "fault_events",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class FaultEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val repIndex: Int,
    val faultId: String,
    val zAtFire: Double,
    val tFireMs: Int,
)

/**
 * §13.1's `CueEvent`, and §17's "cue effectiveness" line.
 *
 * [zAt2s] and [zNextRep] are nullable because they are measured *after* the cue and the set may end
 * first. A cue whose effect was never observed is not a cue that failed.
 */
@Entity(
    tableName = "cue_events",
    foreignKeys = [
        ForeignKey(
            entity = FaultEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["faultEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("faultEventId")],
)
data class CueEventEntity(
    @PrimaryKey val id: String,
    val faultEventId: String,
    val cueVariant: String,
    val zAtFire: Double,
    val zAt2s: Double?,
    val zNextRep: Double?,
    val resolved: Boolean,
)
