package com.example.rightposture

import com.example.rightposture.core.live.RepRejection
import com.example.rightposture.core.summary.SummaryCheck
import com.example.rightposture.core.summary.SummaryFacts
import com.example.rightposture.core.summary.checkSummary
import com.example.rightposture.core.summary.numeralRanges
import com.example.rightposture.data.RepEntity
import com.example.rightposture.report.rejectionReason
import com.example.rightposture.report.repReadoutsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a rep is described once the set is over.
 *
 * The case that matters is the one that looked like a bug: a rep can score 94 and still not count,
 * because `ExerciseEngine` scores the frames and *then* asks the phase tracker whether the movement
 * was the prescribed one. Both answers are true, and a report that prints them side by side with no
 * explanation sends people hunting for a defect that is not there.
 */
class RepReadoutTest {

    private fun rep(
        index: Int,
        quality: Double?,
        valid: Boolean,
        rejection: String? = null,
    ) = RepEntity(
        sessionId = "s1",
        repIndex = index,
        quality = quality,
        romDegrees = 50.0,
        durationMs = 1000,
        valid = valid,
        rejection = rejection,
        smoothnessSparc = null,
        recordedAt = 0,
    )

    @Test
    fun `a rep that scored well and still did not count says both things`() {
        val readout = repReadoutsOf(
            listOf(rep(3, 94.0, valid = false, rejection = "SKIPPED")),
        ).single()

        assertEquals(94, readout.quality)
        assertFalse(readout.counted)
        assertTrue("The score must survive into the headline", readout.headline.contains("94"))
        assertTrue(readout.headline.contains("not counted"))
        // The raw enum name is vocabulary only the code uses.
        assertFalse(readout.headline.contains("SKIPPED"))
        assertNotNull("The two facts need reconciling in words", readout.detail)
        assertTrue(readout.detail!!.contains("jumped to the end"))
        // §1.6: nothing here may read as a score of zero.
        assertFalse(readout.detail!!.contains("0"))
    }

    @Test
    fun `an unscored rep is never rendered as zero`() {
        val readout = repReadoutsOf(
            listOf(rep(1, null, valid = false, rejection = "UNSEEN")),
        ).single()

        assertNull(readout.quality)
        assertTrue(readout.headline.contains("not scored"))
        // "not scored" and "scored 0" are different clinical claims.
        assertFalse(readout.headline.contains("0"))
    }

    @Test
    fun `a counted rep says only its score`() {
        val readout = repReadoutsOf(listOf(rep(2, 88.0, valid = true))).single()

        assertTrue(readout.counted)
        assertEquals("Rep 2 · 88", readout.headline)
        assertNull("Nothing left to explain", readout.detail)
    }

    @Test
    fun `every rejection has report wording`() {
        RepRejection.entries.forEach { entry ->
            val phrase = rejectionReason(entry.name)

            assertNotNull("$entry has no wording", phrase)
            assertTrue("$entry is blank", phrase!!.isNotBlank())
            // A raw enum name leaking through is the bug this replaces.
            assertFalse("$entry reads as code", phrase.contains("_"))
            assertFalse("$entry reads as code", phrase == entry.name)
        }
    }

    @Test
    fun `no rejection phrase trips the summary validator`() {
        // These phrases reach the prompt, the model echoes them, and the validator then sees them.
        // UNSAFE_ADVICE_WORDS contains "skip", so SKIPPED described with that word would reject
        // every summary mentioning it - silently, by falling back to the template.
        val facts = SummaryFacts(
            exerciseName = "Heel slide",
            repsCompleted = 8,
            repsPrescribed = 10,
            medianQuality = 80,
            degradationOnsetRep = null,
        )

        RepRejection.entries.forEach { entry ->
            val phrase = rejectionReason(entry.name)!!
            val check = checkSummary("Two reps did not count because $phrase.", facts)

            assertTrue(
                "$entry produces a summary the validator refuses: $check",
                check is SummaryCheck.Accepted,
            )
        }
    }

    @Test
    fun `an unrecognised rejection string degrades to something readable`() {
        // Real rows carry free text: SessionFactsTest stores "too fast", which is not an enum name.
        // valueOf would throw here and take the report down with it.
        assertEquals("too fast", rejectionReason("too fast"))
        assertNull(rejectionReason(null))
        assertNull(rejectionReason(""))
    }

    @Test
    fun `readouts keep rep order even when the rows arrive shuffled`() {
        val readouts = repReadoutsOf(
            listOf(rep(3, 70.0, valid = true), rep(1, 90.0, valid = true), rep(2, 80.0, valid = true)),
        )

        assertEquals(listOf(1, 2, 3), readouts.map { it.repIndex })
    }

    @Test
    fun `highlighting finds exactly the numbers the validator checks`() {
        // The highlight claims "this is a measurement we took". It can only claim that because the
        // validator refuses any digit run outside allowedNumbers, and this shares its regex.
        val text = "You finished 8 of 10 reps, with a median quality of 71 out of 100."
        val found = numeralRanges(text).map { text.substring(it.first, it.last + 1) }

        assertEquals(listOf("8", "10", "71", "100"), found)
    }

    @Test
    fun `a decimal is one numeral, not two`() {
        val text = "Range fell by 1.8 today."
        val found = numeralRanges(text).map { text.substring(it.first, it.last + 1) }

        // Whitelisting "1" and "8" would not admit "1.8", which is why every emitted fact is
        // formatted as an integer.
        assertEquals(listOf("1.8"), found)
    }

    @Test
    fun `text with no numbers is left whole`() {
        assertTrue(numeralRanges("Your form held all the way through.").isEmpty())
    }
}
