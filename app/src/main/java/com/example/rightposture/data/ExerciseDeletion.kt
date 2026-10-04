package com.example.rightposture.data

import java.io.File

/**
 * Removes an exercise from this phone: every version's row, then every version's files.
 *
 * Rows before files. Interrupted between the two, an orphaned bundle on disk is invisible and
 * harmless; a row whose bundle is gone is a Library card that cannot be opened.
 *
 * Local only. A copy already pushed to the server (7.4) stays there, so a patient it was prescribed
 * to on another phone keeps it - this phone has no business deleting their exercise.
 *
 * @return how many versions were removed.
 */
suspend fun deleteExercise(exercises: ExerciseDao, id: String): Int {
    val versions = exercises.versionsOf(id)
    exercises.deleteAllVersions(id)
    versions.forEach { version ->
        File(version.rposePath).delete()
        version.demoPath?.let { File(it).delete() }
    }
    return versions.size
}
