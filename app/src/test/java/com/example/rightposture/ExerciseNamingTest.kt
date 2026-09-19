package com.example.rightposture

import com.example.rightposture.authoring.exerciseIdFromName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id a physio's chosen name turns into.
 *
 * This matters more than it looks. The id is §9.6's identity: two saves that produce the same id
 * are two versions of one exercise, and two saves that produce different ids are two unrelated
 * exercises. Getting it wrong does not throw - it silently forks a version history, or silently
 * merges two exercises that were never meant to be the same one.
 */
class ExerciseNamingTest {

    @Test
    fun `the same name typed differently is the same exercise`() {
        // "Heel Slide" and "heel slide" are the same exercise to the person who named it. Treating
        // them as two would fork the version history §9.6 depends on, and the physio would have no
        // way to tell from the library that it had happened.
        val canonical = exerciseIdFromName("Heel Slide")
        assertEquals(canonical, exerciseIdFromName("heel slide"))
        assertEquals(canonical, exerciseIdFromName("  Heel   Slide  "))
        assertEquals(canonical, exerciseIdFromName("Heel-Slide"))
    }

    @Test
    fun `different names are different exercises`() {
        assertNotEquals(exerciseIdFromName("Heel slide"), exerciseIdFromName("Knee extension"))
    }

    @Test
    fun `the id is safe as a filename and as a storage path`() {
        // It becomes both: `<id>_v<n>.rpose` on disk, and `<physio>/<id>/v<n>.rpose` in the bucket.
        // A slash or a space in either place is a broken path rather than an error message.
        val id = exerciseIdFromName("Straight-leg raise (left) 30°/45°")
        assertTrue(id, id.all { it.isLetterOrDigit() || it == '_' })
        assertTrue(id, '/' !in id && ' ' !in id)
    }

    @Test
    fun `punctuation collapses rather than piling up`() {
        // Without the collapse, "a - b" and "a---b" would be different ids for what a physio would
        // read as the same name.
        assertEquals(exerciseIdFromName("a - b"), exerciseIdFromName("a---b"))
        assertTrue("__" !in exerciseIdFromName("a !!! b"))
    }

    @Test
    fun `a name made only of punctuation still yields a usable id`() {
        // Nothing should be able to produce an empty id: it would become the file `_v1.rpose` and
        // the storage path `<physio>//v1.rpose`, neither of which is what anybody meant.
        val id = exerciseIdFromName("???")
        assertTrue(id.isNotEmpty())
        assertTrue(id, id.all { it.isLetterOrDigit() || it == '_' })
    }

    @Test
    fun `a very long name is truncated but stays distinct from a short one`() {
        val long = exerciseIdFromName("x".repeat(200))
        assertTrue(long.length <= 52)
        assertNotEquals(long, exerciseIdFromName("x"))
    }
}
