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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A hold travelling from the physio's phone to the patient's.
 *
 * The kind of exercise has to survive the trip. A hold that arrives labelled cyclic is not a
 * cosmetic problem: Today would offer it as ten repetitions, and the engine that opens it expects a
 * phase axis a hold does not have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HoldSyncTest {

    private lateinit var database: RightPostureDatabase
    private lateinit var bundleDirectory: File
    private val config = SupabaseConfig("https://example.supabase.co", "anon-key")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RightPostureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bundleDirectory = File(context.cacheDir, "hold-sync-" + System.nanoTime()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        bundleDirectory.deleteRecursively()
    }

    private fun sync(handler: suspend (HttpRequestData) -> Pair<HttpStatusCode, String>): ExerciseSync {
        val engine = MockEngine { request ->
            val (status, body) = handler(request)
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

    private fun localHold(): ExerciseEntity {
        val file = File(bundleDirectory, "rp_wall_sit.rpose").apply { writeBytes(byteArrayOf(9, 9)) }
        return ExerciseEntity(
            id = "rp_wall_sit",
            version = 1,
            physioUid = null,
            name = "Wall sit",
            mode = "hold",
            holdSeconds = 30,
            rposePath = file.absolutePath,
            demoPath = null,
            createdAt = 1_699_000_000_000,
        )
    }

    @Test
    fun `pushing a hold registers it as a hold with its duration`() = runTest {
        val bodies = mutableListOf<String>()
        val sync = sync { request ->
            if (request.url.encodedPath.endsWith("/rest/v1/exercises")) {
                bodies += String(request.body.toByteArray())
            }
            HttpStatusCode.Created to ""
        }
        val hold = localHold()
        database.exercises().upsert(hold)

        assertEquals(SyncOutcome.Done(1), sync.pushExercise(hold, "physio-1"))
        assertEquals(1, bodies.size)
        assertTrue(bodies[0], bodies[0].contains("\"mode\":\"hold\""))
        assertTrue(bodies[0], bodies[0].contains("\"hold_seconds\":30"))
    }

    @Test
    fun `a prescribed hold duration travels with the assignment`() = runTest {
        val bodies = mutableListOf<String>()
        val sync = sync { request ->
            bodies += String(request.body.toByteArray())
            HttpStatusCode.Created to ""
        }
        // Prescribed shorter than recorded: a thirty-second reference at fifteen for a patient
        // starting out is a normal thing for a physio to do, and the two must not be conflated.
        val prescription = Prescription("rp_wall_sit", 1, "patient-1", 1, 3, 60, 0, holdSeconds = 15)

        assertEquals(SyncOutcome.Done(1), sync.assign(prescription))
        assertTrue(bodies[0], bodies[0].contains("\"hold_seconds\":15"))
    }

    @Test
    fun `a pulled hold arrives as a hold, not as a cyclic exercise`() = runTest {
        val sync = sync { request ->
            when {
                request.url.encodedPath.contains("/storage/") -> HttpStatusCode.OK to "bundle-bytes"
                else -> HttpStatusCode.OK to ASSIGNMENT_ROW
            }
        }

        assertEquals(SyncOutcome.Done(1), sync.pullAssignments("patient-1"))

        val stored = database.exercises().find("rp_wall_sit", 1)
        assertEquals("hold", stored?.mode)
        assertEquals(30, stored?.holdSeconds)
        // The prescription's own duration wins over the reference's.
        assertEquals(15, database.assignments().all().first().holdSeconds)
    }

    @Test
    fun `a row written before hold mode existed still reads as cyclic`() = runTest {
        val sync = sync { request ->
            when {
                request.url.encodedPath.contains("/storage/") -> HttpStatusCode.OK to "bundle-bytes"
                else -> HttpStatusCode.OK to LEGACY_ASSIGNMENT_ROW
            }
        }

        assertEquals(SyncOutcome.Done(1), sync.pullAssignments("patient-1"))

        val stored = database.exercises().find("ex-legacy", 1)
        assertEquals("cyclic", stored?.mode)
        assertEquals(null, stored?.holdSeconds)
    }

    private companion object {
        const val ASSIGNMENT_ROW = """
            [{"id":"assignment-1","exercise_id":"rp_wall_sit","exercise_version":1,
              "reps":1,"sets":3,"rest_seconds":60,"hold_seconds":15,"sequence_index":0,
              "exercises":{"name":"Wall sit","rpose_path":"physio-1/rp_wall_sit/v1.rpose",
                           "demo_path":null,"physio_id":"physio-1",
                           "created_at":"2026-09-06T10:00:00Z","mode":"hold","hold_seconds":30}}]
        """

        /** No `mode`, no `hold_seconds`: exactly the shape a project created before 7.6 returns. */
        const val LEGACY_ASSIGNMENT_ROW = """
            [{"id":"assignment-2","exercise_id":"ex-legacy","exercise_version":1,
              "reps":10,"sets":3,"rest_seconds":60,"sequence_index":0,
              "exercises":{"name":"Knee extension","rpose_path":"physio-1/ex-legacy/v1.rpose",
                           "demo_path":null,"physio_id":"physio-1",
                           "created_at":"2026-09-06T10:00:00Z"}}]
        """
    }
}
