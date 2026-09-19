package com.example.rightposture

import com.example.rightposture.core.summary.CueTally
import com.example.rightposture.core.summary.FeatureChange
import com.example.rightposture.core.summary.SummaryCheck
import com.example.rightposture.core.summary.SmoothnessDirection
import com.example.rightposture.core.summary.SummaryFacts
import com.example.rightposture.core.summary.SummarySource
import com.example.rightposture.core.summary.TextGenerator
import com.example.rightposture.core.summary.checkSummary
import com.example.rightposture.core.summary.generateSummary
import com.example.rightposture.core.summary.numeralRanges
import com.example.rightposture.core.summary.summaryPrompt
import com.example.rightposture.core.summary.summaryTemplate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 8.3's gate: **no new medical advice and no fabricated measurement can enter a summary.**
 *
 * Every case here is a way a small model actually misbehaves. None of them can be produced on
 * demand from a real model, which is the whole reason the generator is an interface - pointing at
 * Gemma and hoping it hallucinates on cue is not a test.
 */
class SummarySafetyTest {

    private val facts = SummaryFacts(
        exerciseName = "Heel slide",
        repsCompleted = 8,
        repsPrescribed = 10,
        medianQuality = 71,
        degradationOnsetRep = 5,
        changes = listOf(
            FeatureChange(
                displayName = "Knee extension",
                description = "less range after rep 5",
                numbers = setOf("5"),
            ),
        ),
        cues = listOf(CueTally("Straighten all the way", times = 3, resolved = 1)),
    )

    private fun generatorOf(vararg replies: String?) = object : TextGenerator {
        private var call = 0
        override suspend fun generate(prompt: String): String? =
            replies.getOrNull(call++) ?: replies.lastOrNull()
    }

    // -------------------------------------------------------------------------------------
    // Fabricated measurement
    // -------------------------------------------------------------------------------------

    @Test
    fun `a number nobody measured is rejected`() {
        // The failure this whole gate exists for. It does not look like a bug, it looks like
        // encouragement, and the patient repeats it to their physiotherapist as fact.
        val result = checkSummary(
            "Good work today. Your knee extension improved by 15 percent. Keep going.",
            facts,
        )
        assertTrue(result is SummaryCheck.Rejected)
        assertTrue(
            (result as SummaryCheck.Rejected).reasons.toString(),
            result.reasons.any { "15" in it },
        )
    }

    @Test
    fun `the numbers we computed are allowed through`() {
        val result = checkSummary(
            "You finished 8 of 10 reps at a median quality of 71. Your knee extension lost " +
                "range after rep 5. Next time, slow down before that point.",
            facts,
        )
        assertTrue(result.toString(), result is SummaryCheck.Accepted)
    }

    @Test
    fun `a magnitude spelled out in words cannot dodge the digit check`() {
        // "fifteen percent" contains no digits at all, so the numeric rule alone would pass it.
        val result = checkSummary(
            "Great effort. Your range improved by a few percent this time. Keep it up.",
            facts,
        )
        assertTrue(result is SummaryCheck.Rejected)
    }

    // -------------------------------------------------------------------------------------
    // Medical advice
    // -------------------------------------------------------------------------------------

    @Test
    fun `diagnosis is rejected`() {
        val result = checkSummary(
            "You finished 8 of 10 reps. This pattern suggests tendinitis. Rest it.",
            facts,
        )
        assertTrue(result is SummaryCheck.Rejected)
    }

    @Test
    fun `treatment suggestions are rejected`() {
        val result = checkSummary(
            "Nice work on 8 of 10. Take some ibuprofen before your next session.",
            facts,
        )
        assertTrue(result is SummaryCheck.Rejected)
    }

    @Test
    fun `telling a patient to push through pain is rejected`() {
        // The most dangerous sentence a fitness-flavoured model can produce, and it reads as
        // motivation rather than as advice.
        val result = checkSummary(
            "You managed 8 of 10 reps. Push through the pain next time and you will get all 10.",
            facts,
        )
        assertTrue(result is SummaryCheck.Rejected)
    }

    @Test
    fun `unilaterally advancing the programme is rejected`() {
        // §6.21.5 reserves advancement for a clinician. A summary that promotes the patient by
        // itself has overruled the one rule the progression logic exists to protect.
        val result = checkSummary(
            "Strong set of 8 of 10. Add weight next session to keep progressing.",
            facts,
        )
        assertTrue(result is SummaryCheck.Rejected)
    }

    @Test
    fun `naming a joint the set never measured is rejected`() {
        // No number attached, so the numeric rule passes it. It is still a claim about a
        // measurement that does not exist.
        val result = checkSummary(
            "You finished 8 of 10 reps. Your shoulder position looked much steadier today.",
            facts,
        )
        assertTrue(result is SummaryCheck.Rejected)
    }

    // -------------------------------------------------------------------------------------
    // Shape
    // -------------------------------------------------------------------------------------

    @Test
    fun `a model that narrates instead of summarising is rejected`() {
        val rambling = (1..12).joinToString(" ") { "This is sentence number." }
        assertTrue(checkSummary(rambling, facts) is SummaryCheck.Rejected)
    }

    @Test
    fun `scaffolding leaking out of the model is rejected`() {
        assertTrue(checkSummary("""{"summary": "8 of 10"}""", facts) is SummaryCheck.Rejected)
        assertTrue(
            checkSummary("As an AI I cannot assess this.", facts) is SummaryCheck.Rejected,
        )
    }

    @Test
    fun `an empty reply is rejected rather than shown`() {
        assertTrue(checkSummary("   ", facts) is SummaryCheck.Rejected)
    }

    // -------------------------------------------------------------------------------------
    // The generate / validate / retry / fall back loop
    // -------------------------------------------------------------------------------------

    @Test
    fun `a clean first answer is used as is`() = runTest {
        val good = "You finished 8 of 10 reps at quality 71. Range dropped after rep 5. " +
            "Next session, slow down earlier."
        val result = generateSummary(facts, generatorOf(good))
        assertEquals(SummarySource.MODEL, result.source)
        assertEquals(good, result.text)
    }

    @Test
    fun `a rejected answer is retried once with the reasons attached`() = runTest {
        var retryPrompt: String? = null
        val generator = object : TextGenerator {
            private var call = 0
            override suspend fun generate(prompt: String): String {
                call++
                if (call == 1) return "Your knee improved by 15 percent. Well done."
                retryPrompt = prompt
                return "You finished 8 of 10 reps. Range dropped after rep 5. Slow down next time."
            }
        }
        val result = generateSummary(facts, generator)
        assertEquals(SummarySource.MODEL_RETRY, result.source)
        // §10.4: the retry names what went wrong. A blind second attempt usually reproduces the
        // first one, and costs the patient another wait to do it.
        assertTrue(retryPrompt!!, "rejected" in retryPrompt!!.lowercase())
        assertTrue(retryPrompt!!, "15" in retryPrompt!!)
    }

    @Test
    fun `two bad answers fall back to the template rather than shipping either`() = runTest {
        val result = generateSummary(
            facts,
            generatorOf(
                "Your knee improved 15 percent.",
                "You likely have tendinitis, take ibuprofen.",
            ),
        )
        assertEquals(SummarySource.TEMPLATE, result.source)
        assertEquals(summaryTemplate(facts), result.text)
        assertTrue(result.rejections.isNotEmpty())
    }

    @Test
    fun `a model that will not load still produces a summary`() = runTest {
        // §10.5's whole purpose: the app is never silent. A patient who finished their set gets
        // told how it went whether or not a 1.5 GB model loaded.
        val absent = generateSummary(facts, null)
        assertEquals(SummarySource.TEMPLATE, absent.source)
        assertTrue(absent.text.isNotBlank())

        val nullReply = generateSummary(facts, generatorOf(null))
        assertEquals(SummarySource.TEMPLATE, nullReply.source)
        assertTrue(nullReply.text.isNotBlank())
    }

    @Test
    fun `the template passes its own validator`() {
        // The fallback is the thing that ships when everything else fails, so it has to satisfy
        // the same rules. A template that would be rejected as a model answer is a bug that only
        // ever appears on the worst day.
        assertTrue(checkSummary(summaryTemplate(facts), facts).toString(),
            checkSummary(summaryTemplate(facts), facts) is SummaryCheck.Accepted)
    }

    @Test
    fun `the template handles an unscored set without inventing a zero`() {
        // §1.6. "Median quality 0" is a different clinical claim from "not scored", and the one
        // the patient would take personally is the one that is not true.
        val unscored = facts.copy(medianQuality = null, degradationOnsetRep = null, changes = emptyList())
        val text = summaryTemplate(unscored)
        assertFalse(text, "0 out of 100" in text)
        assertFalse(text, "quality of 0" in text)
        assertTrue(checkSummary(text, unscored) is SummaryCheck.Accepted)
    }

    // -------------------------------------------------------------------------------------
    // The prompt
    // -------------------------------------------------------------------------------------

    @Test
    fun `the prompt carries the computed facts and nothing else`() {
        val prompt = summaryPrompt(facts)
        assertTrue(prompt, "8 of 10" in prompt)
        assertTrue(prompt, "71 / 100" in prompt)
        assertTrue(prompt, "rep 5" in prompt)
        assertTrue(prompt, "Knee extension" in prompt)
        assertTrue(prompt, "Straighten all the way" in prompt)
    }

    @Test
    fun `an unscored set says so in the prompt rather than sending a zero`() {
        val prompt = summaryPrompt(facts.copy(medianQuality = null))
        assertTrue(prompt, "not scored" in prompt)
        assertFalse(prompt, "0 / 100" in prompt)
    }

    // -------------------------------------------------------------------------------------
    // 8.7: §6.18.5's deltas
    // -------------------------------------------------------------------------------------

    /** Every field populated, which is the worst case for the sentence and word bounds. */
    private val maximalFacts = facts.copy(
        repsNotCounted = 2,
        topRejection = "it did not reach the full range",
        romChangePercent = -12,
        tempoChangePercent = 8,
        smoothness = SmoothnessDirection.LESS_SMOOTH,
    )

    @Test
    fun `a delta we computed is allowed and one we did not is not`() {
        val real = checkSummary("Your range was 12 percent smaller after that point.", maximalFacts)
        val invented = checkSummary("Your range was 13 percent smaller after that point.", maximalFacts)

        assertTrue(real.toString(), real is SummaryCheck.Accepted)
        assertTrue(invented is SummaryCheck.Rejected)
    }

    @Test
    fun `the word percent is refused when no delta was computed`() {
        // The trap the whole sixth edit exists for. Whitelisting the number is not enough: the unit
        // is checked separately, and without allowedMagnitudeWords every delta-bearing summary
        // would be rejected and fall silently back to the template.
        val noDeltas = facts.copy(romChangePercent = null, tempoChangePercent = null)
        val result = checkSummary("Your range was 12 percent smaller after that point.", noDeltas)

        assertTrue(result is SummaryCheck.Rejected)
    }

    @Test
    fun `the sign is dropped before whitelisting, because a minus is never captured`() {
        // ΔROM of -12 must admit the string "12": the regex matches digit runs only, so a
        // whitelisted "-12" would admit nothing at all.
        assertTrue("12" in maximalFacts.allowedNumbers())
        assertFalse("-12" in maximalFacts.allowedNumbers())
    }

    @Test
    fun `the template stays inside its bounds with every fact present`() {
        val text = summaryTemplate(maximalFacts)
        val sentences = text.split(Regex("""[.!?]+""")).count { it.isNotBlank() }
        val words = text.split(Regex("""\s+""")).count { it.isNotBlank() }

        // The bounds checkSummary enforces. Asserted here so a future sixth sentence fails loudly
        // rather than only on the day the model is unavailable.
        assertTrue("$sentences sentences: $text", sentences <= 5)
        assertTrue("$words words: $text", words <= 90)
    }

    @Test
    fun `the template passes its own validator with every fact present`() {
        val result = checkSummary(summaryTemplate(maximalFacts), maximalFacts)
        assertTrue(result.toString(), result is SummaryCheck.Accepted)
    }

    @Test
    fun `every numeral the template emits is one the validator allows`() {
        // This is what makes the on-screen highlighting honest: an emphasised number is one we
        // computed, not one that merely survived.
        val text = summaryTemplate(maximalFacts)
        val allowed = maximalFacts.allowedNumbers()

        numeralRanges(text).forEach { range ->
            val numeral = text.substring(range.first, range.last + 1)
            assertTrue("$numeral is not a measurement we took", numeral in allowed)
        }
    }

    @Test
    fun `every delta token is an integer`() {
        // A decimal would defeat the whitelist, which matches "1.8" whole, and would also read as
        // a sentence boundary to the validator's sentence count.
        listOf("12", "8", "2").forEach { assertTrue(it in maximalFacts.allowedNumbers()) }
        assertTrue(maximalFacts.allowedNumbers().none { "." in it })
    }

    @Test
    fun `the prompt carries the deltas`() {
        val prompt = summaryPrompt(maximalFacts)

        assertTrue(prompt, "12 percent smaller" in prompt)
        assertTrue(prompt, "8 percent slower" in prompt)
        assertTrue(prompt, "less smooth" in prompt)
        assertTrue(prompt, "did not count: 2" in prompt)
        // Spelled, never "%": the model writes the word, so it has to have seen the word.
        assertFalse(prompt, "%" in prompt)
    }

    @Test
    fun `a set with no change point carries no delta into the prompt`() {
        val prompt = summaryPrompt(facts.copy(degradationOnsetRep = null))

        // setDeltasOf returns nulls without an onset, so "after that point" can never be orphaned.
        assertFalse(prompt, "after that point" in prompt)
    }
}
