package com.example.rightposture.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * §13.1's local store, and per §13.3 the **authoritative** one. Sync is a mirror of this, never the
 * other way round: the entire patient session works with no network, and a rehab app that needs a
 * connection to score a set is not a rehab app.
 *
 * `exportSchema = false` while the schema is still moving. Turn it on with a migration directory the
 * first time a build ships that someone else's device will upgrade from - which is not yet, and the
 * fallback below says what happens until then.
 */
@Database(
    entities = [
        ExerciseEntity::class,
        AssignmentEntity::class,
        SessionEntity::class,
        RepEntity::class,
        FeatureSampleEntity::class,
        FaultEventEntity::class,
        CueEventEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class RightPostureDatabase : RoomDatabase() {
    abstract fun exercises(): ExerciseDao
    abstract fun assignments(): AssignmentDao
    abstract fun sessions(): SessionDao
    abstract fun events(): EventDao

    companion object {
        @Volatile
        private var instance: RightPostureDatabase? = null

        fun get(context: Context): RightPostureDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        private fun build(context: Context) = Room
            .databaseBuilder(context, RightPostureDatabase::class.java, "rightposture.db")
            // Foreign keys are declared on the entities and SQLite ignores them unless asked.
            // Without this the cascade deletes are decoration.
            .addCallback(ForeignKeysOn)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            // Still here for any *other* version jump during development, but 1 -> 2 is now a real
            // migration. The destructive fallback was fine while nothing had been recorded; it stops
            // being fine the moment a session exists that a patient performed.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

        /**
         * Adds 7.5's `syncedAt`.
         *
         * Written rather than left to the destructive fallback because the fallback drops every
         * table, and §13.1 makes those tables the source of truth for work a patient actually did.
         * Losing a set to an app update is not an upgrade. Existing rows get null, which is exactly
         * right - nothing recorded before this column existed has been mirrored.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN syncedAt INTEGER")
            }
        }

        /**
         * Adds `exercises.mode` and `exercises.holdSeconds`, so a hold can be told from a cyclic
         * exercise, and described, without opening the bundle it came from.
         *
         * Existing rows default to `cyclic` with no duration, which is not a guess: hold references
         * had no way to be saved before this version, so every row already in the table came from
         * the cyclic path and none of them has a hold duration to lose.
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN mode TEXT NOT NULL DEFAULT 'cyclic'")
                db.execSQL("ALTER TABLE exercises ADD COLUMN holdSeconds INTEGER")
                db.execSQL("ALTER TABLE assignments ADD COLUMN holdSeconds INTEGER")
            }
        }

        private val ForeignKeysOn = object : RoomDatabase.Callback() {
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }
    }
}
