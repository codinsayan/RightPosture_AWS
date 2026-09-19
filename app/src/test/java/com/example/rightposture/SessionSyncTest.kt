package com.example.rightposture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rightposture.data.FeatureSampleEntity
import com.example.rightposture.data.RepEntity
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.sync.SessionSync
import com.example.rightposture.sync.SupabaseClient
import com.example.rightposture.sync.SupabaseConfig
import com.example.rightposture.sync.SyncOutcome
import com.example.rightposture.sync.SyncWorkResult
import com.example.rightposture.sync.syncWorkResultFor
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 7.5's session mirror.
 *
 * The three things the iteration plan asks to prove - an offline session queues and later uploads,
 * a duplicated retry is a no-op, and the session stays bound to the exercise version it was scored
 * against - are all properties of what is written where, so Room is real and only the server is
 * mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionSyncTest {

    private lateinit var database: RightPostureDatabase
    private val config = SupabaseConfig("https://example.supabase.co", "anon-key")
    private var clock = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            RightPostureDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    private fun sync(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>): SessionSync {
        val engine = MockEngine { request ->
            val (status, body) = handler(request)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return SessionSync(
            SupabaseClient(config, engine),
            database.sessions(),
            database.events(),
            now = { clock },
        )
    }

    private fun ok() = sync { HttpStatusCode.Created to "" }

    private suspend fun recordSession(
        id: String = "s-1",
        exerciseVersion: Int = 1,
        endedAt: Long? = 1_699_000_100_000,
        startedAt: Long = 1_699_000_000_000,
        reps: Int = 2,
    ): SessionEntity {
        database.exercises().upsert(
            com.example.rightposture.data.ExerciseEntity(
                id = "ex-1", version = exerciseVersion, physioUid = "physio-1",
                name = "Heel slide", rposePath = "/tmp/x.rpose", demoPath = null,
                createdAt = 1_699_000_000_000,
            ),
        )
        val session = SessionEntity(
            id = id,
            assignmentId = null,
            patientUid = null,
            exerciseId = "ex-1",
            exerciseVersion = exerciseVersion,
            startedAt = startedAt,
            endedAt = endedAt,
            prescribedReps = 10,
            setScore = 82.0,
            completionRate = 0.8,
            degradationOnsetRep = 6,
            deviceFps = 29.5,
            delegate = "NPU",
        )
        database.sessions().upsert(session)
        for (index in 1..reps) {
            database.sessions().recordRep(
                RepEntity(
                    sessionId = id, repIndex = index,
                    // §1.6: rep 2 is deliberately unscored, so the null has to survive the wire.
                    quality = if (index == 1) 90.0 else null,
                    romDegrees = 88.0, durationMs = 2100, valid = true, rejection = null,
                    smoothnessSparc = -1.7, recordedAt = startedAt + index * 2000L,
                ),
                listOf(
                    FeatureSampleEntity(
                        sessionId = id, repIndex = index, feature = "knee.flexion",
                        meanZ = 0.4, maxZ = 1.1, meanSignedZ = -0.4, frames = 60,
                    ),
                ),
            )
        }
        return session
    }

    // -------------------------------------------------------------------------------------
    // The queue
    // -------------------------------------------------------------------------------------

    @Test
    fun `a session performed offline queues and uploads when the network returns`() = runTest {
        recordSession()

        // Airplane mode. §13.3: unreachable is ordinary, and nothing is lost by it.
        val offline = SessionSync(
            SupabaseClient(config, MockEngine { throw java.io.IOException("airplane mode") }),
            database.sessions(), database.events(), now = { clock },
        )
        assertEquals(SyncOutcome.Offline, offline.pushPending("patient-1"))
        assertNull(
            "an unreachable server must not mark anything as mirrored",
            database.sessions().session("s-1")!!.syncedAt,
        )

        // Network back.
        assertEquals(SyncOutcome.Done(1), ok().pushPending("patient-1"))
        assertEquals(clock, database.sessions().session("s-1")!!.syncedAt)
    }

    @Test
    fun `a session already mirrored is not sent again`() = runTest {
        recordSession()
        ok().pushPending("patient-1")

        var calls = 0
        val second = sync { calls++; HttpStatusCode.Created to "" }
        assertEquals(SyncOutcome.Done(0), second.pushPending("patient-1"))
        assertEquals(0, calls)
    }

    @Test
    fun `a set still in progress is not uploaded out from under the patient`() = runTest {
        // Started a minute ago, not finished. That is someone resting between sets.
        recordSession(endedAt = null, startedAt = clock - 60_000)
        var calls = 0
        assertEquals(SyncOutcome.Done(0), sync { calls++; HttpStatusCode.Created to "" }
            .pushPending("patient-1"))
        assertEquals(0, calls)
    }

    @Test
    fun `a set the OS killed still reaches the physio once it is stale`() = runTest {
        // §13.1 kept those reps on disk precisely so they would not be lost. "The patient started
        // and something went wrong" is a clinically useful thing for a physio to see.
        recordSession(endedAt = null, startedAt = clock - 2 * 60 * 60 * 1000)
        assertEquals(SyncOutcome.Done(1), ok().pushPending("patient-1"))
        assertNotNull(database.sessions().session("s-1")!!.syncedAt)
    }

    @Test
    fun `sessions are marked one at a time, so a drop mid-queue keeps the rest queued`() = runTest {
        recordSession(id = "s-1", reps = 1)
        recordSession(id = "s-2", reps = 1)
        recordSession(id = "s-3", reps = 1)

        var sessionPosts = 0
        val flaky = sync { request ->
            if (request.url.encodedPath.endsWith("/sessions")) {
                sessionPosts++
                // The third session's head request never lands.
                if (sessionPosts >= 3) throw java.io.IOException("network dropped")
            }
            HttpStatusCode.Created to ""
        }
        assertEquals(SyncOutcome.Done(2), flaky.pushPending("patient-1"))

        assertNotNull(database.sessions().session("s-1")!!.syncedAt)
        assertNotNull(database.sessions().session("s-2")!!.syncedAt)
        // Marking the batch at the end would have marked this one too, and it never left.
        assertNull(database.sessions().session("s-3")!!.syncedAt)
    }

    // -------------------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------------------

    @Test
    fun `a duplicated retry writes the same rows, so it is a no-op`() = runTest {
        val bodies = mutableListOf<Pair<String, String>>()
        val engine = MockEngine { request ->
            bodies += request.url.encodedPath to String(request.body.toByteArray())
            respond("", HttpStatusCode.Created)
        }
        val client = SupabaseClient(config, engine)
        val sync = SessionSync(client, database.sessions(), database.events(), now = { clock })
        val session = recordSession()

        sync.pushSession(session, "patient-1")
        val first = bodies.toList()
        bodies.clear()
        sync.pushSession(session, "patient-1")

        // Byte-identical payloads against natural keys. There is no dedup pass because the key
        // shape makes one unnecessary - the replay overwrites itself.
        assertEquals(first, bodies.toList())
    }

    @Test
    fun `every write asks the server to merge duplicates rather than insert`() = runTest {
        val prefers = mutableListOf<String>()
        val engine = MockEngine { request ->
            prefers += request.headers["Prefer"] ?: ""
            respond("", HttpStatusCode.Created)
        }
        SessionSync(
            SupabaseClient(config, engine), database.sessions(), database.events(), now = { clock },
        ).pushSession(recordSession(), "patient-1")

        assertTrue(prefers.isNotEmpty())
        // Without this a retry is a primary-key violation instead of a no-op, and the whole
        // idempotency argument collapses into an error the patient never sees but the physio does.
        assertTrue(prefers.toString(), prefers.all { it.contains("resolution=merge-duplicates") })
    }

    @Test
    fun `children follow parents, so a partial push is a prefix and never an orphan`() = runTest {
        val order = mutableListOf<String>()
        val sync = sync { request ->
            order += request.url.encodedPath.substringAfterLast('/')
            HttpStatusCode.Created to ""
        }
        val session = recordSession()
        database.events().upsertFault(
            com.example.rightposture.data.FaultEventEntity(
                id = "f-1", sessionId = "s-1", repIndex = 1, faultId = "knee.valgus",
                zAtFire = 2.4, tFireMs = 1200,
            ),
        )
        database.events().upsertCue(
            com.example.rightposture.data.CueEventEntity(
                id = "c-1", faultEventId = "f-1", cueVariant = "knees out",
                zAtFire = 2.4, zAt2s = 1.1, zNextRep = null, resolved = true,
            ),
        )
        sync.pushSession(session, "patient-1")

        // The server declares these foreign keys, so the order is not a preference.
        assertEquals(
            listOf("sessions", "reps", "feature_samples", "fault_events", "cue_events"),
            order,
        )
    }

    // -------------------------------------------------------------------------------------
    // §13.4's version binding
    // -------------------------------------------------------------------------------------

    @Test
    fun `the session stays bound to the version it was scored against`() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/sessions")) {
                body = String(request.body.toByteArray())
            }
            respond("", HttpStatusCode.Created)
        }
        val session = recordSession(exerciseVersion = 1)

        // The physio publishes v2 while the set is queued. §9.6 makes (id, version) immutable, so
        // this is a new row rather than an edit - and the queued session must not be retagged to it.
        database.exercises().upsert(
            com.example.rightposture.data.ExerciseEntity(
                id = "ex-1", version = 2, physioUid = "physio-1", name = "Heel slide",
                rposePath = "/tmp/x2.rpose", demoPath = null, createdAt = clock,
            ),
        )

        SessionSync(
            SupabaseClient(config, engine), database.sessions(), database.events(), now = { clock },
        ).pushSession(session, "patient-1")

        assertTrue(body, body.contains("\"exercise_version\":1"))
        assertFalse(body, body.contains("\"exercise_version\":2"))
    }

    @Test
    fun `the signed-in patient owns the row even when the set was recorded anonymously`() = runTest {
        // 7.1's airplane-mode session has a null patientUid - nobody was signed in. RLS needs an
        // owner, so the id comes from whoever is signed in when it is finally pushed.
        var body = ""
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/sessions")) body = String(request.body.toByteArray())
            respond("", HttpStatusCode.Created)
        }
        val session = recordSession()
        assertNull(session.patientUid)

        SessionSync(
            SupabaseClient(config, engine), database.sessions(), database.events(), now = { clock },
        ).pushSession(session, "patient-77")
        assertTrue(body, body.contains("\"patient_id\":\"patient-77\""))
    }

    @Test
    fun `an unscored rep travels as null, never as zero`() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/reps")) body = String(request.body.toByteArray())
            respond("", HttpStatusCode.Created)
        }
        SessionSync(
            SupabaseClient(config, engine), database.sessions(), database.events(), now = { clock },
        ).pushSession(recordSession(), "patient-1")

        // §1.6. "Not scored" and "scored 0" are different clinical claims, and the mirror is where
        // a careless default would quietly turn one into the other.
        assertTrue(body, body.contains("\"quality\":null"))
        assertFalse(body, body.contains("\"quality\":0"))
    }

    // -------------------------------------------------------------------------------------
    // Failure handling and the worker contract
    // -------------------------------------------------------------------------------------

    @Test
    fun `a rejection stops the run and leaves the session queued`() = runTest {
        recordSession()
        val outcome = sync { HttpStatusCode.Forbidden to """{"message":"denied"}""" }
            .pushPending("patient-1")

        assertTrue(outcome is SyncOutcome.Failed)
        // Nothing is lost by stopping: Room is the source of truth and still holds the set.
        assertNull(database.sessions().session("s-1")!!.syncedAt)
    }

    @Test
    fun `offline retries, a rejection does not, and an empty queue succeeds`() = runTest {
        assertEquals(SyncWorkResult.RETRY, syncWorkResultFor(SyncOutcome.Offline))
        assertEquals(SyncWorkResult.FAILURE, syncWorkResultFor(SyncOutcome.Failed("denied")))
        // Zero pushed is success. An empty queue is not a problem to back off from.
        assertEquals(SyncWorkResult.SUCCESS, syncWorkResultFor(SyncOutcome.Done(0)))
        assertEquals(SyncWorkResult.SUCCESS, syncWorkResultFor(SyncOutcome.Done(3)))
    }

    // -------------------------------------------------------------------------------------
    // §14.1's report
    // -------------------------------------------------------------------------------------

    @Test
    fun `the report counts valid reps and keeps an unscored set unscored`() = runTest {
        val reply = """
            [{"id":"s-1","patient_id":"p-1","exercise_id":"ex-1","exercise_version":1,
              "started_at":"2026-09-05T07:21:17.291458+00:00","ended_at":null,
              "prescribed_reps":10,"set_score":null,"completion_rate":0.6,
              "degradation_onset_rep":6,
              "reps":[{"valid":true},{"valid":true},{"valid":false}]}]
        """.trimIndent()
        val report = sync { HttpStatusCode.OK to reply }.pullReport("physio-1")

        assertEquals(1, report.size)
        val row = report.single()
        assertEquals(2, row.countedReps)
        // §1.6 again, at the point the physio reads it.
        assertNull(row.setScore)
        assertEquals(6, row.degradationOnsetRep)
        // §13.1's killed-mid-set case has to be distinguishable from a finished one.
        assertTrue(row.unfinished)
    }

    @Test
    fun `an unreachable server yields an empty report rather than an exception`() = runTest {
        val offline = SessionSync(
            SupabaseClient(config, MockEngine { throw java.io.IOException("no network") }),
            database.sessions(), database.events(), now = { clock },
        )
        assertTrue(offline.pullReport("physio-1").isEmpty())
    }
}
