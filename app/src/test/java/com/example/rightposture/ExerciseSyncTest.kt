package com.example.rightposture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rightposture.data.ExerciseEntity
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.sync.ExerciseSync
import com.example.rightposture.sync.Prescription
import com.example.rightposture.sync.SupabaseClient
import com.example.rightposture.sync.SupabaseConfig
import com.example.rightposture.sync.SyncOutcome
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
import java.io.File

/**
 * 7.4's data path, against a real in-memory Room and a mocked server.
 *
 * Room is real because the assertions that matter are about what ends up on disk, and §13.3 makes
 * disk the source of truth. The server is mocked because the failures worth testing - an upload
 * that succeeds while the row that follows it fails, a bundle that will not download - are
 * precisely the ones a healthy project will not produce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExerciseSyncTest {

    private lateinit var database: RightPostureDatabase
    private lateinit var bundleDirectory: File
    private val config = SupabaseConfig("https://example.supabase.co", "anon-key")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RightPostureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bundleDirectory = File(context.cacheDir, "bundles-${System.nanoTime()}")
        bundleDirectory.mkdirs()
    }

    @After
    fun tearDown() {
        database.close()
        bundleDirectory.deleteRecursively()
    }

    private fun sync(handler: (HttpRequestData, Int) -> Pair<HttpStatusCode, String>): ExerciseSync {
        var call = 0
        val engine = MockEngine { request ->
            call++
            val (status, body) = handler(request, call)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return ExerciseSync(
            client = SupabaseClient(config, engine),
            exercises = database.exercises(),
            assignments = database.assignments(),
            bundleDirectory = bundleDirectory,
            newId = { "assignment-1" },
            now = { 1_700_000_000_000 },
        )
    }

    private fun localExercise(id: String = "ex-1", version: Int = 1): ExerciseEntity {
        val file = File(bundleDirectory, "$id.rpose").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        return ExerciseEntity(
            id = id,
            version = version,
            physioUid = null,
            name = "Knee extension",
            rposePath = file.absolutePath,
            demoPath = null,
            createdAt = 1_699_000_000_000,
        )
    }

    // -------------------------------------------------------------------------------------
    // Push
    // -------------------------------------------------------------------------------------

    @Test
    fun `pushing an exercise uploads the bundle before it registers the row`() = runTest {
        val order = mutableListOf<String>()
        val sync = sync { request, _ ->
            order += request.url.encodedPath
            HttpStatusCode.Created to ""
        }
        val exercise = localExercise()
        database.exercises().upsert(exercise)

        assertEquals(SyncOutcome.Done(1), sync.pushExercise(exercise, "physio-1"))
        assertEquals(2, order.size)
        // A row with no upload behind it is an exercise the patient can see and cannot start, and
        // no retry fixes it. The bundle goes first.
        assertTrue(order[0], order[0].startsWith("/storage/v1/object/"))
        assertTrue(order[1], order[1].endsWith("/rest/v1/exercises"))
    }

    @Test
    fun `a failed upload never registers the row`() = runTest {
        var registered = false
        val sync = sync { request, _ ->
            if (request.url.encodedPath.contains("/rest/v1/exercises")) registered = true
            HttpStatusCode.Forbidden to """{"message":"denied"}"""
        }
        val outcome = sync.pushExercise(localExercise(), "physio-1")
        assertTrue(outcome is SyncOutcome.Failed)
        assertFalse(registered)
    }

    @Test
    fun `pushing records the physio locally so the row knows it has been claimed`() = runTest {
        val exercise = localExercise()
        database.exercises().upsert(exercise)
        assertNull(database.exercises().find("ex-1", 1)?.physioUid)

        sync { _, _ -> HttpStatusCode.Created to "" }.pushExercise(exercise, "physio-1")
        assertEquals("physio-1", database.exercises().find("ex-1", 1)?.physioUid)
    }

    @Test
    fun `an exercise whose bundle is gone fails without touching the network`() = runTest {
        // The row can outlive the file - a reinstall, or cleared storage. Uploading nothing and
        // registering a row for it would push the breakage onto the patient.
        var calls = 0
        val exercise = localExercise().copy(rposePath = File(bundleDirectory, "gone.rpose").path)
        val outcome = sync { _, _ -> calls++; HttpStatusCode.Created to "" }
            .pushExercise(exercise, "physio-1")
        assertTrue(outcome is SyncOutcome.Failed)
        assertEquals(0, calls)
    }

    @Test
    fun `assigning generates the id on the device so a replay is the same row`() = runTest {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { request ->
            bodies += String(request.body.toByteArray())
            respond("", HttpStatusCode.Created)
        }
        val sync = ExerciseSync(
            client = SupabaseClient(config, engine),
            exercises = database.exercises(),
            assignments = database.assignments(),
            bundleDirectory = bundleDirectory,
            newId = { "fixed-id" },
        )
        val prescription = Prescription("ex-1", 1, "patient-1", 10, 3, 60, 0)

        assertEquals(SyncOutcome.Done(1), sync.assign(prescription))
        assertEquals(SyncOutcome.Done(1), sync.assign(prescription))
        // A request that timed out after the server committed it would otherwise be replayed as a
        // second prescription, and the patient would be told to do the exercise twice.
        assertEquals(bodies[0], bodies[1])
        assertTrue(bodies[0], bodies[0].contains("\"id\":\"fixed-id\""))
    }

    // -------------------------------------------------------------------------------------
    // Pull
    // -------------------------------------------------------------------------------------

    private val oneAssignment = """
        [{"id":"a-1","exercise_id":"ex-9","exercise_version":2,"reps":12,"sets":3,
          "rest_seconds":45,"sequence_index":0,
          "exercises":{"name":"Heel slide","rpose_path":"physio-1/ex-9/v2.rpose",
                       "demo_path":null,
                       "physio_id":"physio-1","created_at":"2026-01-01T00:00:00Z"}}]
    """.trimIndent()

    /** Shaped exactly as the live project replies - microseconds and a `+00:00` offset. */
    private val withDemo = """
        [{"id":"a-1","exercise_id":"ex-9","exercise_version":2,"reps":12,"sets":3,
          "rest_seconds":45,"sequence_index":0,
          "exercises":{"name":"Heel slide","rpose_path":"physio-1/ex-9/v2.rpose",
                       "demo_path":"physio-1/ex-9/v2/demo.mp4",
                       "physio_id":"physio-1",
                       "created_at":"2026-09-05T07:21:17.291458+00:00"}}]
    """.trimIndent()

    @Test
    fun `pulling stores the assignment and downloads the bundle it points at`() = runTest {
        val sync = sync { request, call ->
            if (call == 1) HttpStatusCode.OK to oneAssignment
            else HttpStatusCode.OK to "bundle-bytes"
        }
        assertEquals(SyncOutcome.Done(1), sync.pullAssignments("patient-1"))

        val stored = database.exercises().find("ex-9", 2)
        assertNotNull(stored)
        assertEquals("Heel slide", stored!!.name)
        assertTrue(File(stored.rposePath).isFile)
        // Nothing may be left behind by a completed download: a `.part` file that survived would be
        // mistaken for a bundle on the next scan of the directory.
        assertFalse(File("${stored.rposePath}.part").exists())

        val prescribed = database.assignments().prescribedFor("patient-1")
        assertEquals(1, prescribed.size)
        assertEquals(12, prescribed[0].reps)
        assertEquals(3, prescribed[0].sets)
    }

    @Test
    fun `a bundle already on disk is not downloaded again`() = runTest {
        // §9.6 makes `(id, version)` immutable, so a local copy can never be stale - only absent.
        // Re-downloading it would spend the patient's data to obtain bytes they already have.
        database.exercises().upsert(localExercise("ex-9", 2))
        var downloads = 0
        val sync = sync { request, call ->
            if (request.url.encodedPath.startsWith("/storage")) downloads++
            if (call == 1) HttpStatusCode.OK to oneAssignment else HttpStatusCode.OK to "bytes"
        }
        assertEquals(SyncOutcome.Done(1), sync.pullAssignments("patient-1"))
        assertEquals(0, downloads)
    }

    @Test
    fun `an exercise whose bundle will not download is skipped, not faked`() = runTest {
        val sync = sync { request, call ->
            when {
                call == 1 -> HttpStatusCode.OK to oneAssignment
                else -> HttpStatusCode.NotFound to """{"message":"not found"}"""
            }
        }
        // Zero stored is still a success: the call did what it could. What must not happen is an
        // assignment row pointing at a bundle that is not there, which would put a button on Today
        // that fails when the patient presses it.
        assertEquals(SyncOutcome.Done(0), sync.pullAssignments("patient-1"))
        assertNull(database.exercises().find("ex-9", 2))
        assertTrue(database.assignments().all().isEmpty())
    }

    @Test
    fun `an unreachable server leaves what is already on disk alone`() = runTest {
        database.exercises().upsert(localExercise("ex-9", 2))
        val engine = MockEngine { throw java.io.IOException("airplane mode") }
        val sync = ExerciseSync(
            client = SupabaseClient(config, engine),
            exercises = database.exercises(),
            assignments = database.assignments(),
            bundleDirectory = bundleDirectory,
        )
        // §13.3: the server is a mirror. It cannot delete or contradict a local row, so the worst a
        // failed pull does is leave the patient with exactly the exercises they had.
        assertEquals(SyncOutcome.Offline, sync.pullAssignments("patient-1"))
        assertNotNull(database.exercises().find("ex-9", 2))
    }

    @Test
    fun `pulling twice stores one assignment, not two`() = runTest {
        val sync = sync { _, call ->
            if (call % 2 == 1) HttpStatusCode.OK to oneAssignment else HttpStatusCode.OK to "bytes"
        }
        sync.pullAssignments("patient-1")
        sync.pullAssignments("patient-1")
        assertEquals(1, database.assignments().all().size)
    }

    // -------------------------------------------------------------------------------------
    // Today
    // -------------------------------------------------------------------------------------

    @Test
    fun `today lists a prescription and hides an assignment whose exercise is absent`() = runTest {
        database.exercises().upsert(localExercise("ex-1", 1))
        val sync = sync { _, call ->
            if (call == 1) HttpStatusCode.OK to oneAssignment else HttpStatusCode.OK to "bytes"
        }
        sync.pullAssignments("patient-1")

        // The inner join is the point: a row whose reference has not arrived is not startable, and
        // listing it would put a button on Today that does nothing.
        val listed = database.assignments().prescribedFor("patient-1")
        assertEquals(1, listed.size)
        assertEquals("ex-9", listed[0].exerciseId)
    }

    // -------------------------------------------------------------------------------------
    // Bugs found by pointing the sync at the live project rather than at the mock
    // -------------------------------------------------------------------------------------

    @Test
    fun `the demo travels with the exercise`() = runTest {
        // Found live: `pullAssignments` never asked for `demo_path` and hard-coded the local demo
        // to null, so a demo the physio uploaded could never reach the patient. The mock replied
        // with whatever it was asked for, so only a real reply exposed the missing column.
        val fetched = mutableListOf<String>()
        val sync = sync { request, call ->
            if (request.url.encodedPath.startsWith("/storage")) fetched += request.url.encodedPath
            if (call == 1) HttpStatusCode.OK to withDemo else HttpStatusCode.OK to "bytes"
        }
        assertEquals(SyncOutcome.Done(1), sync.pullAssignments("patient-1"))

        val stored = database.exercises().find("ex-9", 2)!!
        assertNotNull("the demo should have been stored", stored.demoPath)
        assertTrue(File(stored.demoPath!!).isFile)
        assertEquals(2, fetched.size)
        assertFalse(File("${stored.demoPath}.part").exists())
    }

    @Test
    fun `a demo that will not download does not cost the patient the exercise`() = runTest {
        // §9 makes the demo optional. Losing a whole prescribed exercise over a video that would
        // not fetch is the wrong trade - the patient can work from the cues.
        val sync = sync { request, call ->
            when {
                call == 1 -> HttpStatusCode.OK to withDemo
                request.url.encodedPath.endsWith("demo.mp4") ->
                    HttpStatusCode.NotFound to """{"message":"gone"}"""
                else -> HttpStatusCode.OK to "bundle"
            }
        }
        assertEquals(SyncOutcome.Done(1), sync.pullAssignments("patient-1"))
        val stored = database.exercises().find("ex-9", 2)!!
        assertTrue(File(stored.rposePath).isFile)
        assertNull(stored.demoPath)
    }

    @Test
    fun `the exercise keeps the date the physio recorded it, not the date it downloaded`() = runTest {
        // Found live: `created_at` was requested and then thrown away in favour of `now()`, so the
        // patient's copy claimed to have been recorded on the day it synced. "Recorded 3 March" has
        // to mean the day it was recorded.
        val sync = sync { _, call ->
            if (call == 1) HttpStatusCode.OK to withDemo else HttpStatusCode.OK to "bytes"
        }
        sync.pullAssignments("patient-1")

        val stored = database.exercises().find("ex-9", 2)!!
        // 2026-09-05T07:21:17.291458+00:00, and emphatically not the injected now() of
        // 1_700_000_000_000 that the download would otherwise have stamped on it.
        assertEquals(1_788_592_877_291L, stored.createdAt)
    }

    @Test
    fun `a timestamp that cannot be read falls back rather than losing the row`() = runTest {
        val sync = sync { _, call ->
            if (call == 1) {
                HttpStatusCode.OK to oneAssignment.replace("2026-01-01T00:00:00Z", "not a date")
            } else {
                HttpStatusCode.OK to "bytes"
            }
        }
        assertEquals(SyncOutcome.Done(1), sync.pullAssignments("patient-1"))
        assertEquals(1_700_000_000_000L, database.exercises().find("ex-9", 2)!!.createdAt)
    }

    @Test
    fun `a null patient sees everything on the device`() = runTest {
        // 7.1's airplane-mode gate: on a phone where the physio recorded the reference there is no
        // account and no assignment, and the screen must still not be empty.
        database.exercises().upsert(localExercise("ex-1", 1))
        assertTrue(database.assignments().prescribedFor(null).isEmpty())
        assertEquals(1, database.exercises().all().size)
    }
}
