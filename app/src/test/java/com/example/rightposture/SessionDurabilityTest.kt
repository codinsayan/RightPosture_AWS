package com.example.rightposture

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rightposture.core.live.RepRecord
import com.example.rightposture.data.FeatureSampleEntity
import com.example.rightposture.data.RepEntity
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.data.SessionDao
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.data.SessionRepository
import com.example.rightposture.data.persistPending
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
 * 7.1's durability contract, at the two points that are not visible by reading the code.
 *
 * `SessionPersistenceTest` proves the happy path stores what it should. This proves the two things
 * that only show up when something goes wrong: a write that fails must not be mistaken for one that
 * succeeded, and the foreign keys must actually cascade rather than merely be declared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionDurabilityTest {

    private lateinit var db: RightPostureDatabase
    private lateinit var dao: FailableSessionDao
    private lateinit var repository: SessionRepository

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RightPostureDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = FailableSessionDao(db.sessions())
        repository = SessionRepository(dao, db.exercises(), now = { 1_000L }, newId = { "session-1" })
    }

    @After
    fun close() = db.close()

    // -------------------------------------------------------------------------------------
    // A failed write must stay retryable
    // -------------------------------------------------------------------------------------

    @Test
    fun `the watermark does not advance past a rep that failed to write`() = runTest {
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        dao.failOnRepIndex = 3

        val watermark = repository.persistPending(session, reps(1..5), alreadyPersisted = 0)

        // Reps 1 and 2 are on disk, so the watermark sits at 2. Had it advanced to 5, reps 3-5
        // would be marked durable while absent, and nothing would ever look at them again - the
        // exact silent loss this whole write path exists to prevent.
        assertEquals(2, watermark)
        assertEquals(listOf(1, 2), repository.repsOf(session).map { it.repIndex })
    }

    @Test
    fun `the next call retries the rep that failed and catches up`() = runTest {
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        dao.failOnRepIndex = 3
        val afterFailure = repository.persistPending(session, reps(1..5), alreadyPersisted = 0)

        // The transient condition clears - a full disk that was emptied, in practice.
        dao.failOnRepIndex = null
        val afterRetry = repository.persistPending(session, reps(1..5), alreadyPersisted = afterFailure)

        assertEquals(5, afterRetry)
        assertEquals(listOf(1, 2, 3, 4, 5), repository.repsOf(session).map { it.repIndex })
    }

    @Test
    fun `the failure is reported rather than swallowed`() = runTest {
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        dao.failOnRepIndex = 2
        var reported: Throwable? = null

        repository.persistPending(session, reps(1..3), alreadyPersisted = 0) { reported = it }

        // Silently dropping a rep and silently dropping a rep *while logging it* are very
        // different positions to debug from on a device.
        assertNotNull(reported)
    }

    @Test
    fun `nothing is rewritten when every rep is already durable`() = runTest {
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        repository.persistPending(session, reps(1..3), alreadyPersisted = 0)
        dao.writes = 0

        val watermark = repository.persistPending(session, reps(1..3), alreadyPersisted = 3)

        assertEquals(3, watermark)
        assertEquals("an up-to-date watermark should write nothing", 0, dao.writes)
    }

    // -------------------------------------------------------------------------------------
    // The foreign keys actually cascade
    // -------------------------------------------------------------------------------------

    @Test
    fun `deleting a session row cascades to its reps and feature samples`() = runTest {
        // The existing suite proved this with clearAllTables(), which is Room's own bulk wipe and
        // never touches SQLite's foreign-key machinery. This deletes one row with real SQL, so it
        // is the first test that proves the PRAGMA foreign_keys=ON callback takes effect at all.
        val session = repository.startSession(manifest = null, prescribedReps = 12)
        repository.recordRep(session, sampledRep(index = 1))
        assertEquals(1, repository.repsOf(session).size)
        assertEquals(1, db.sessions().featureSamples(session, 1).size)

        db.openHelper.writableDatabase.execSQL("DELETE FROM sessions WHERE id = ?", arrayOf(session))

        assertTrue("reps should have gone with the session", repository.repsOf(session).isEmpty())
        assertTrue(
            "feature samples should have gone with the rep",
            db.sessions().featureSamples(session, 1).isEmpty(),
        )
        assertNull(db.sessions().session(session))
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private fun reps(range: IntRange) = range.map { plainRep(it) }

    private fun plainRep(index: Int) = RepRecord(
        index = index,
        quality = 90.0 - index,
        romDegrees = 80.0,
        durationSeconds = 2.2,
        phiMax = 1.01,
        valid = true,
        rejection = null,
        features = emptyMap(),
        velocityProfile = emptyList(),
    )

    private fun sampledRep(index: Int) = plainRep(index).copy(
        features = mapOf(
            com.example.rightposture.core.FeatureId.TRUNK_FLEXION to
                com.example.rightposture.core.live.FeatureSample(
                    feature = com.example.rightposture.core.FeatureId.TRUNK_FLEXION,
                    meanZ = 1.2,
                    maxZ = 2.0,
                    meanSignedZ = -1.2,
                    frames = 40,
                ),
        ),
    )

    /**
     * A real DAO with a fault injected at one rep index.
     *
     * Delegating rather than faking, so every write that is *not* the injected failure still goes
     * through SQLite. A pure fake would let the test pass while the schema rejected the row.
     */
    private class FailableSessionDao(private val real: SessionDao) : SessionDao() {
        var failOnRepIndex: Int? = null
        var writes = 0

        override suspend fun upsert(session: SessionEntity) = real.upsert(session)

        override suspend fun upsertRep(rep: RepEntity) {
            if (rep.repIndex == failOnRepIndex) error("injected write failure at rep ${rep.repIndex}")
            writes++
            real.upsertRep(rep)
        }

        override suspend fun upsertFeatureSamples(samples: List<FeatureSampleEntity>) =
            real.upsertFeatureSamples(samples)

        override suspend fun session(id: String) = real.session(id)

        override suspend fun reps(sessionId: String) = real.reps(sessionId)

        override suspend fun featureSamples(sessionId: String, repIndex: Int) =
            real.featureSamples(sessionId, repIndex)

        override suspend fun unfinishedSessions() = real.unfinishedSessions()

        override suspend fun recentSessions(limit: Int) = real.recentSessions(limit)

        override suspend fun sessionsForExercise(exerciseId: String) =
            real.sessionsForExercise(exerciseId)

        override suspend fun repsForSessions(sessionIds: List<String>) =
            real.repsForSessions(sessionIds)

        override suspend fun unsyncedSessions(staleBefore: Long) =
            real.unsyncedSessions(staleBefore)

        override suspend fun markSynced(id: String, at: Long) = real.markSynced(id, at)

        override suspend fun allFeatureSamples(sessionId: String) =
            real.allFeatureSamples(sessionId)

        override suspend fun finish(
            id: String,
            endedAt: Long,
            setScore: Double?,
            completionRate: Double?,
            degradationOnsetRep: Int?,
        ) = real.finish(id, endedAt, setScore, completionRate, degradationOnsetRep)
    }
}
