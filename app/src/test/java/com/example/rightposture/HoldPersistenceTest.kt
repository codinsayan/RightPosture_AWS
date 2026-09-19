package com.example.rightposture

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.HoldFeatureReference
import com.example.rightposture.core.live.HoldReference
import com.example.rightposture.core.reference.bundleFromHold
import com.example.rightposture.core.rpose.CYCLIC_MODE
import com.example.rightposture.core.rpose.HOLD_MODE
import com.example.rightposture.core.rpose.readRpose
import com.example.rightposture.core.rpose.writeRposeFile
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.data.SessionRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A hold that survives the screen it was recorded on.
 *
 * Before this, `HoldReference` lived in a Compose `var` and was gone the moment the physio left. The
 * things worth pinning are the two that would fail silently: the row has to remember which kind of
 * exercise it is without reopening the zip, and the bundle on disk has to still be a hold when it is
 * read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HoldPersistenceTest {

    private lateinit var db: RightPostureDatabase
    private lateinit var repository: SessionRepository
    private lateinit var directory: File

    private fun holdReference() = HoldReference(
        features = mapOf(
            FeatureId.KNEE_FLEX_ACTIVE to HoldFeatureReference(
                FeatureId.KNEE_FLEX_ACTIVE, 87.4, 2.1, 74.9, 0.476,
            ),
        ),
        dominant = FeatureId.KNEE_FLEX_ACTIVE,
        watchList = listOf(FeatureId.KNEE_FLEX_ACTIVE),
        stableWindowSeconds = 6.2,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, RightPostureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SessionRepository(db.sessions(), db.exercises())
        directory = File(context.cacheDir, "hold-bundles-" + System.nanoTime()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        directory.deleteRecursively()
    }

    @Test
    fun `a saved hold is on disk and readable back as a hold`() = runTest {
        val bundle = bundleFromHold(
            hold = holdReference(),
            name = "Wall sit",
            id = "rp_wall_sit",
            createdAt = "2026-09-06T10:00:00Z",
            holdSeconds = 30,
        )
        val file = File(directory, "rp_wall_sit_v1.rpose")
        writeRposeFile(bundle, file)
        repository.saveExercise(bundle.manifest, file.absolutePath)

        val reloaded = file.inputStream().use { readRpose(it) }
        assertEquals(HOLD_MODE, reloaded.manifest.mode)
        assertEquals(holdReference(), reloaded.hold)
        assertEquals(30, reloaded.manifest.prescription.holdSeconds)
    }

    @Test
    fun `the row remembers the mode without reopening the bundle`() = runTest {
        val bundle = bundleFromHold(
            hold = holdReference(),
            name = "Wall sit",
            id = "rp_wall_sit",
            createdAt = "2026-09-06T10:00:00Z",
            holdSeconds = 30,
        )
        repository.saveExercise(bundle.manifest, File(directory, "x.rpose").absolutePath)

        val stored = db.exercises().latestVersionOf("rp_wall_sit")
        assertEquals(HOLD_MODE, stored?.mode)
    }

    @Test
    fun `an exercise row defaults to cyclic`() = runTest {
        // Every row written before the column existed came from the cyclic path, because a hold had
        // no way to be saved at all. The default is a fact about the old data, not a guess.
        val entity = com.example.rightposture.data.ExerciseEntity(
            id = "rp_heel_slide",
            version = 1,
            physioUid = null,
            name = "Heel slide",
            rposePath = "/tmp/x.rpose",
            demoPath = null,
            createdAt = 0L,
        )
        db.exercises().upsert(entity)
        assertEquals(CYCLIC_MODE, db.exercises().latestVersionOf("rp_heel_slide")?.mode)
    }

    @Test
    fun `saving the same name again publishes the next version`() = runTest {
        repeat(2) { attempt ->
            val version = (repository.latestVersionOf("rp_wall_sit")?.version ?: 0) + 1
            val bundle = bundleFromHold(
                hold = holdReference(),
                name = "Wall sit",
                id = "rp_wall_sit",
                version = version,
                createdAt = "2026-09-06T10:0" + attempt + ":00Z",
                holdSeconds = 30,
            )
            val file = File(directory, "rp_wall_sit_v" + version + ".rpose")
            writeRposeFile(bundle, file)
            repository.saveExercise(bundle.manifest, file.absolutePath)
        }
        // §9.6: version 1 is still there, so a session already scored against it still can be.
        assertEquals(2, repository.latestVersionOf("rp_wall_sit")?.version)
        assertEquals(1, db.exercises().find("rp_wall_sit", 1)?.version)
    }
}

/**
 * The 2 -> 3 migration, run against a real SQLite database.
 *
 * `fallbackToDestructiveMigration(dropAllTables = true)` is still configured, so a missing or broken
 * migration does not fail loudly - it silently drops every session a patient recorded. This is the
 * test that tells the difference.
 *
 * Room's `MigrationTestHelper` is not used because it validates against exported schemas, and
 * `exportSchema` is deliberately off while the schema is still moving. The migration is a statement
 * about SQL, so it is exercised as SQL: build the v2 table, migrate, read the column back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HoldMigrationTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // in-memory
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build(),
        )
        db = helper.writableDatabase
        // The v2 shape of the two tables the migration touches.
        db.execSQL(
            "CREATE TABLE exercises (" +
                "id TEXT NOT NULL, version INTEGER NOT NULL, physioUid TEXT, name TEXT NOT NULL, " +
                "rposePath TEXT NOT NULL, demoPath TEXT, createdAt INTEGER NOT NULL, " +
                "PRIMARY KEY(id, version))",
        )
        db.execSQL(
            "CREATE TABLE assignments (" +
                "id TEXT NOT NULL PRIMARY KEY, exerciseId TEXT NOT NULL, " +
                "exerciseVersion INTEGER NOT NULL, patientUid TEXT, reps INTEGER NOT NULL, " +
                "sets INTEGER NOT NULL, restSeconds INTEGER NOT NULL, " +
                "sequenceIndex INTEGER NOT NULL, activeFrom INTEGER NOT NULL, activeTo INTEGER)",
        )
    }

    @After
    fun tearDown() = helper.close()

    @Test
    fun `an exercise recorded before the mode column survives the upgrade as cyclic`() {
        db.execSQL(
            "INSERT INTO exercises (id, version, physioUid, name, rposePath, demoPath, createdAt) " +
                "VALUES ('rp_heel_slide', 1, NULL, 'Heel slide', '/tmp/a.rpose', NULL, 1000)",
        )

        RightPostureDatabase.MIGRATION_2_3.migrate(db)

        db.query("SELECT name, mode FROM exercises WHERE id = 'rp_heel_slide'").use {
            assertEquals(true, it.moveToFirst())
            // The row is still there. A destructive fallback would have taken it.
            assertEquals("Heel slide", it.getString(0))
            assertEquals(CYCLIC_MODE, it.getString(1))
        }
    }

    @Test
    fun `the migration adds a hold duration to both tables`() {
        RightPostureDatabase.MIGRATION_2_3.migrate(db)
        // A half-applied migration is the failure mode worth catching: the app would start, the
        // list would render, and the first push of a hold would fail on a missing column.
        db.query("SELECT holdSeconds FROM exercises").use { assertEquals(0, it.count) }
        db.query("SELECT holdSeconds FROM assignments").use { assertEquals(0, it.count) }
    }
}
