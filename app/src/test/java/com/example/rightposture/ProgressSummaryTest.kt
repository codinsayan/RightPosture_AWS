package com.example.rightposture

import com.example.rightposture.core.summary.ProgressFacts
import com.example.rightposture.core.summary.QualityTrend
import com.example.rightposture.core.summary.SummaryAudience
import com.example.rightposture.core.summary.SummaryCheck
import com.example.rightposture.core.summary.SummarySource
import com.example.rightposture.core.summary.checkSummary
import com.example.rightposture.core.summary.generateProgressSummary
import com.example.rightposture.core.summary.progressPrompt
import com.example.rightposture.core.summary.progressTemplate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10.7's run-of-sessions summary, held to the same rules as the per-set one.
 *
 * The rules are what is being tested, not the prose. A model that invents "your range improved 15
 * percent" produces a sentence a patient repeats to their physiotherapist and acts on, and nothing
 * about it looks like a bug - so the guard has to be exercised against every way a model misbehaves,
 * which is only possible with an injected generator.
 */
class ProgressSummaryTest {

    private fun facts(
        scoredSets: Int = 6,
        medianQuality: Int? = 84,
        trend: QualityTrend = QualityTrend.IMPROVING,
        audience: SummaryAudience = SummaryAudience.PATIENT,
        bestRangeDegrees: Int? = null,
    ) = ProgressFacts(
        subject = "Seated knee extension",
        audience = audience,
        sets = 8,
        scoredSets = scoredSets,
        medianQuality = medianQuality,
        bestQuality = medianQuality?.plus(4),
        latestQuality = medianQuality,
        setsAtOrAboveBar = 5,
        qualityBar = 80,
        repsCompleted = 84,
        repsPrescribed = 96,
        streakDays = 3,
        trend = trend,
        bestRangeDegrees = bestRangeDegrees,
    )

    @Test
    fun `the fallback template passes the validator it falls back to`() {
        // The one property the template must have. A fallback that fails validation leaves the
        // screen with no paragraph at all, which reads as a bug rather than as an absent model.
        listOf(
            facts(),
            facts(medianQuality = null, scoredSets = 0, trend = QualityTrend.UNKNOWN),
            facts(audience = SummaryAudience.CLINICIAN),
            facts(trend = QualityTrend.DECLINING),
            facts(bestRangeDegrees = 108),
        ).forEach { sheet ->
            val checked = checkSummary(progressTemplate(sheet), sheet)
            assertTrue("$checked for $sheet", checked is SummaryCheck.Accepted)
        }
    }

    @Test
    fun `a number nobody measured is refused`() = runBlocking {
        val summary = generateProgressSummary(facts(), { "Your range improved by 15 degrees." })

        // Rejected twice, so the template stands in rather than the invention reaching the screen.
        assertEquals(SummarySource.TEMPLATE, summary.source)
        assertTrue(summary.rejections.any { "15" in it })
    }

    @Test
    fun `a percentage is refused even when the number happens to be allowed`() = runBlocking {
        // 84 is a real measurement here, but "84 percent" is a rate we never computed.
        val summary = generateProgressSummary(facts(), { "You are working at 84 percent." })

        assertEquals(SummarySource.TEMPLATE, summary.source)
        assertTrue(summary.rejections.any { "percent" in it })
    }

    @Test
    fun `a clean summary is accepted on the first pass`() = runBlocking {
        val summary = generateProgressSummary(facts(), {
            "You have finished 8 sets and 5 of them reached the 80 pass mark. " +
                "Your middle score is 84 out of 100, and it has been rising."
        })

        assertEquals(SummarySource.MODEL, summary.source)
        assertTrue(summary.rejections.isEmpty())
    }

    @Test
    fun `the exercise name may be repeated even when it names a joint`() = runBlocking {
        // Without the subject being whitelisted, the body-part check refuses every summary of an
        // exercise named after the joint it works - which is most of them.
        val summary = generateProgressSummary(facts(), {
            "Your knee extension is holding steady across 8 sets."
        })

        assertEquals(SummarySource.MODEL, summary.source)
    }

    @Test
    fun `nothing scored yet is stated rather than scored as zero`() {
        val sheet = facts(medianQuality = null, scoredSets = 0, trend = QualityTrend.UNKNOWN)
        val text = progressTemplate(sheet)

        assertTrue(text, "no score yet" in text)
        assertTrue("a median of zero was invented: $text", "0 out of 100" !in text)
        assertTrue("not measured" in progressPrompt(sheet))
    }

    @Test
    fun `the two audiences are addressed differently`() {
        val patient = progressTemplate(facts(audience = SummaryAudience.PATIENT))
        val clinician = progressTemplate(facts(audience = SummaryAudience.CLINICIAN))

        assertTrue(patient, patient.startsWith("You have"))
        assertTrue(clinician, clinician.startsWith("This patient has"))
        assertTrue("never to \"you\"" in progressPrompt(facts(audience = SummaryAudience.CLINICIAN)))
    }

    @Test
    fun `with no model at all the template is used rather than nothing`() = runBlocking {
        val summary = generateProgressSummary(facts(), null)

        assertEquals(SummarySource.TEMPLATE, summary.source)
        assertTrue(summary.text.isNotBlank())
    }

    @Test
    fun `degrees are admitted only when a range was measured`() {
        val withRange = facts(bestRangeDegrees = 108)
        val withoutRange = facts()

        assertTrue("degrees" in withRange.allowedMagnitudeWords())
        assertTrue(withoutRange.allowedMagnitudeWords().isEmpty())
        assertTrue("108" in withRange.allowedNumbers())
    }
}
