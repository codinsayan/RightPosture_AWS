package com.example.rightposture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.rightposture.data.AssignmentEntity
import com.example.rightposture.data.ExerciseEntity
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.data.deleteExercise
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Deleting an exercise from the physio's Library, against a real in-memory Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExerciseDeletionTest {

    private lateinit var database: RightPostureDatabase
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, RightPostureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        directory = File(context.cacheDir, "deletion-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun `every version goes, rows and files, and nothing else does`() = runTest {
        val v1 = exercise("knee", 1, demo = true)
        val v2 = exercise("knee", 2)
        val other = exercise("hip", 1)

        assertEquals(2, deleteExercise(database.exercises(), "knee"))

        assertTrue(database.exercises().versionsOf("knee").isEmpty())
        assertFalse(File(v1.rposePath).exists())
        assertFalse(File(v1.demoPath!!).exists())
        assertFalse(File(v2.rposePath).exists())
        assertNotNull(database.exercises().find("hip", 1))
        assertTrue(File(other.rposePath).exists())
    }

    @Test
    fun `its prescriptions go with it, its performed sets stay`() = runTest {
        exercise("knee", 1)
        database.assignments().upsert(
            AssignmentEntity("a1", "knee", 1, null, reps = 10, sets = 3, restSeconds = 60, sequenceIndex = 0, activeFrom = 0L, activeTo = null),
        )
        database.sessions().upsert(
            SessionEntity("s1", "a1", null, "knee", 1, startedAt = 1L, endedAt = 2L, prescribedReps = 10, setScore = 80.0, completionRate = 1.0, degradationOnsetRep = null, deviceFps = null, delegate = null),
        )

        deleteExercise(database.exercises(), "knee")

        assertTrue("a prescription of nothing would sit on Today and do nothing", database.assignments().all().isEmpty())
        assertNotNull("a patient's history outlives the reference", database.sessions().session("s1"))
    }

    @Test
    fun `a missing file does not stop the row going`() = runTest {
        val gone = exercise("knee", 1)
        File(gone.rposePath).delete()

        assertEquals(1, deleteExercise(database.exercises(), "knee"))
        assertTrue(database.exercises().versionsOf("knee").isEmpty())
    }

    private suspend fun exercise(id: String, version: Int, demo: Boolean = false): ExerciseEntity {
        val bundle = File(directory, "${id}_v$version.rpose").apply { writeText("bundle") }
        val video = if (demo) File(directory, "${id}_v$version.mp4").apply { writeText("video") } else null
        val entity = ExerciseEntity(
            id = id,
            version = version,
            physioUid = null,
            name = id,
            rposePath = bundle.absolutePath,
            demoPath = video?.absolutePath,
            createdAt = version.toLong(),
        )
        database.exercises().upsert(entity)
        return entity
    }
}
