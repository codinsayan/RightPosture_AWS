package com.example.rightposture

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.FatigueResult
import com.example.rightposture.core.live.FatigueStatus
import com.example.rightposture.core.live.FeatureSample
import com.example.rightposture.core.live.RepRecord
import com.example.rightposture.core.live.RepRejection
import com.example.rightposture.core.live.SessionRecord
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.data.SessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 7.1. §13.1's local store, exercised against a real Room database rather than a fake DAO.
 *
 * A fake would prove the repository calls the methods it says it calls, which is not the thing that
 * goes wrong. What goes wrong is the schema: a foreign key that rejects a legitimate insert, a
 * composite key that does not compose, a nullable column that is not, an upsert that duplicates
 * instead of replacing. None of those are visible without SQLite actually running, which is what
 * Robolectric is here for.
 *
 * Pinned to SDK 34: Robolectric ships android-all jars per API level and the project compiles
 * against 36, which it has no jar for. The schema does not vary by API level, so the pin costs
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionPersistenceTest {

    private lateinit var db: RightPostureDatabase
    private lateinit var repository: SessionRepository
    private var clock = 1_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RightPostureDatabase::class.java,
        ).allowMainThreadQueries().build()
        var counter = 0
        repository = SessionRepository(
            sessions = db.sessions(),
            exercises = db.exercises(),
            now = { clock },
            newId = { "id-${counter++}" },
        )
    }

    @After
    fun close() = db.close()

    // -------------------------------------------------------------------------------------
    // The requirement the iteration gates on
    // -------------------------------------------------------------------------------------

    @Test
    fun `a rep is durable the moment it completes, not at set end`() = runTest {
        val session = repository.startSession(manifest = null, prescribedReps = 12)

        repository.recordRep(session, rep(index = 1, quality = 88.0))
        repository.recordRep(session, rep(index = 2, quality = 84.0))

        // No finishSession call anywhere above. If the write were deferred to set end, this is
        // where it would come back empty.
        assertEquals(listOf(1, 2), repository.repsOf(session).map { it.repIndex })
        assertEquals(88.0, repository.repsOf(session).first().quality!!, 1e-9)
    }

    @Test
    fun `a session killed mid-set keeps every completed rep and stays recoverable`() = runTest {
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        (1..5).forEach { repository.recordRep(session, rep(index = it, quality = 90.0 - it)) }

        // The kill: nothing calls finishSession, and the process goes away. Reopening the DAO is
        // the closest a JVM test gets to the app being restarted against the same file.
        val recovered = repository.unfinishedSessions()

        assertEquals(1, recovered.size)
        assertEquals(session, recovered.first().id)
        assertNull("a killed session has no end timestamp", recovered.first().endedAt)
        assertEquals(5, repository.repsOf(session).size)
    }

    @Test
    fun `a finished session is no longer offered for recovery`() = runTest {
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        repository.recordRep(session, rep(index = 1, quality = 91.0))

        repository.finishSession(
            session,
            SessionRecord(listOf(record(1, 91.0)), prescribedReps = 12),
            FatigueResult(FatigueStatus.DEGRADED, changePointRep = 4),
        )

        assertTrue(repository.unfinishedSessions().isEmpty())
        val stored = db.sessions().session(session)!!
        assertNotNull(stored.endedAt)
        assertEquals(4, stored.degradationOnsetRep)
        assertNotNull("the set score is written at finish", stored.setScore)
    }

    // -------------------------------------------------------------------------------------
    // Schema behaviour a fake DAO could not have caught
    // -------------------------------------------------------------------------------------

    @Test
    fun `re-recording the same rep replaces it rather than duplicating`() = runTest {
        // §7.5 retries a failed sync by replaying rows. On the natural key that has to be a no-op.
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        repository.recordRep(session, rep(index = 1, quality = 70.0))
        repository.recordRep(session, rep(index = 1, quality = 95.0))

        val reps = repository.repsOf(session)
        assertEquals(1, reps.size)
        assertEquals(95.0, reps.single().quality!!, 1e-9)
    }

    @Test
    fun `an unscored rep stores null quality rather than zero`() = runTest {
        // §1.6. A rep that could not be scored is not a rep that scored badly, and a zero here
        // would feed §6.18.2's baseline a collapse that never happened.
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        repository.recordRep(
            session,
            rep(index = 1, quality = null, valid = false, rejection = RepRejection.UNSCORED),
        )

        val stored = repository.repsOf(session).single()
        assertNull(stored.quality)
        assertEquals("UNSCORED", stored.rejection)
        assertEquals(false, stored.valid)
    }

    @Test
    fun `feature samples land beside their rep and keep the sign`() = runTest {
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        repository.recordRep(
            session,
            rep(index = 1, quality = 80.0).copy(
                features = mapOf(
                    FeatureId.TRUNK_FLEXION to FeatureSample(
                        feature = FeatureId.TRUNK_FLEXION,
                        meanZ = 2.4,
                        maxZ = 3.1,
                        // §6.18.5 attributes on the signed mean: a joint drifting one way and the
                        // other way are opposite findings sharing a magnitude.
                        meanSignedZ = -2.4,
                        frames = 61,
                    ),
                ),
            ),
        )

        val samples = db.sessions().featureSamples(session, 1)
        assertEquals(1, samples.size)
        assertEquals("trunk_flexion", samples.single().feature)
        assertEquals(-2.4, samples.single().meanSignedZ, 1e-9)
        assertEquals(61, samples.single().frames)
    }

    @Test
    fun `deleting a session takes its reps and samples with it`() = runTest {
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        repository.recordRep(session, rep(index = 1, quality = 80.0))

        db.clearAllTables()

        assertTrue(repository.repsOf(session).isEmpty())
        assertTrue(db.sessions().featureSamples(session, 1).isEmpty())
    }

    @Test
    fun `a session records the exercise version it was scored against`() = runTest {
        // §13.4: a physio publishing an edit mid-set must not retroactively change what this
        // session was measured against.
        val session = repository.startSession(
            manifest = manifest(id = "rp_knee_extension", version = 2),
            prescribedReps = 12,
        )

        val stored = db.sessions().session(session)!!
        assertEquals("rp_knee_extension", stored.exerciseId)
        assertEquals(2, stored.exerciseVersion)
    }

    @Test
    fun `two versions of one exercise coexist`() = runTest {
        // §9.6 immutability, as a primary key. Version 2 must not overwrite version 1, because a
        // session already points at version 1.
        repository.saveExercise(manifest("rp_knee_extension", 1), rposePath = "/v1.rpose")
        repository.saveExercise(manifest("rp_knee_extension", 2), rposePath = "/v2.rpose")

        assertEquals(2, db.exercises().all().size)
        assertEquals(2, db.exercises().latestVersionOf("rp_knee_extension")!!.version)
        assertEquals("/v1.rpose", db.exercises().find("rp_knee_extension", 1)!!.rposePath)
    }

    @Test
    fun `a session with no assignment and nobody signed in is still a valid row`() = runTest {
        // The 7.1 flow exactly: physio records a reference, performs a set against it immediately.
        // No assignment exists and 7.3 has not happened, so a schema that demanded either would
        // fail the airplane-mode gate on its first row.
        val session = repository.startSession(manifest = null, prescribedReps = 8)

        val stored = db.sessions().session(session)!!
        assertNull(stored.assignmentId)
        assertNull(stored.patientUid)
        assertEquals(8, stored.prescribedReps)
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private fun rep(
        index: Int,
        quality: Double?,
        valid: Boolean = true,
        rejection: RepRejection? = null,
    ) = RepRecord(
        index = index,
        quality = quality,
        romDegrees = 82.0,
        durationSeconds = 2.4,
        phiMax = 1.02,
        valid = valid,
        rejection = rejection,
        features = emptyMap(),
        velocityProfile = emptyList(),
    )

    private fun record(index: Int, quality: Double) = rep(index, quality)

    private fun manifest(id: String, version: Int) =
        com.example.rightposture.core.rpose.RposeManifest(
            id = id,
            version = version,
            name = "Seated knee extension",
            createdBy = "local",
            createdAt = "2026-09-05T00:00:00Z",
            mode = "cyclic",
            referenceSource = "self",
            bodyPosition = "seated",
            laterality = "unilateral",
            activeSide = "right",
            requiredView = "sagittal",
            cameraFacing = "rear",
            mirrored = false,
            capture = com.example.rightposture.core.rpose.RposeCapture(30, 300, 10.0, "test"),
            segmentation = com.example.rightposture.core.rpose.RposeSegmentation(
                repsDetected = 7,
                repsUsed = 5,
                periodSeconds = 2.0,
                dominantFeature = FeatureId.KNEE_FLEX_ACTIVE,
            ),
            phase = com.example.rightposture.core.rpose.RposePhase(
                bins = 101,
                thetaStart = 170.0,
                thetaPeak = 80.0,
                criticalWindow = 40 to 60,
                vRef = 0.5,
            ),
            prescription = com.example.rightposture.core.rpose.RposePrescription(12, 3, 60, null),
        )
}
