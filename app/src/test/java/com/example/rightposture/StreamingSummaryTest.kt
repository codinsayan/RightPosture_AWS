package com.example.rightposture

import com.example.rightposture.core.summary.ProgressFacts
import com.example.rightposture.core.summary.QualityTrend
import com.example.rightposture.core.summary.StreamingTextGenerator
import com.example.rightposture.core.summary.SummaryAudience
import com.example.rightposture.core.summary.SummarySource
import com.example.rightposture.core.summary.generateProgressSummary
import com.example.rightposture.core.summary.safeVisiblePrefix
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live guard on a streaming summary.
 *
 * Streaming reopens the hole the validator exists to close: the full check needs a finished
 * paragraph, and by then a fabricated measurement has already been read. These tests are about the
 * one property that matters - that no reader ever sees a number we did not compute, or a clinical
 * claim, even for the second it takes the stream to finish and be rejected.
 */
class StreamingSummaryTest {

    private val facts = ProgressFacts(
        subject = "Seated knee extension",
        audience = SummaryAudience.PATIENT,
        sets = 8,
        scoredSets = 6,
        medianQuality = 84,
        bestQuality = 88,
        latestQuality = 84,
        setsAtOrAboveBar = 5,
        qualityBar = 80,
        repsCompleted = 84,
        repsPrescribed = 96,
        streakDays = 3,
        trend = QualityTrend.IMPROVING,
    )

    /** Emits the text one character at a time, the way a decoder hands back tokens. */
    private fun streamOf(full: String) = object : StreamingTextGenerator {
        override suspend fun generate(prompt: String) = full
        override suspend fun generateStreaming(
            prompt: String,
            onPartial: (String) -> Unit,
        ): String {
            full.indices.forEach { onPartial(full.take(it + 1)) }
            return full
        }
    }

    @Test
    fun `an invented number never becomes visible`() = runBlocking {
        val seen = mutableListOf<String>()
        generateProgressSummary(
            facts,
            streamOf("You improved by 15 percent this week."),
            onPartial = { seen += it },
        )

        assertTrue("15 was shown: $seen", seen.none { "15" in it })
    }

    @Test
    fun `a number still being typed is held back until it is complete`() {
        // "8" is allowed and "84" is allowed, but "88" arriving one digit at a time must not flash
        // an "8" that means something different. Nothing partial is ever revealed.
        assertEquals("Your best was ", safeVisiblePrefix("Your best was 8", facts))
        assertEquals("Your best was ", safeVisiblePrefix("Your best was 88", facts))
        assertEquals("Your best was 88.", safeVisiblePrefix("Your best was 88.", facts))
    }

    @Test
    fun `a disallowed number stops the reveal at that point`() {
        val visible = safeVisiblePrefix("You did well. Range rose 15 degrees overall.", facts)

        assertEquals("You did well. Range rose ", visible)
    }

    @Test
    fun `a clinical claim stops the reveal at that point`() {
        val visible = safeVisiblePrefix("Good work, but this looks like tendinitis.", facts)

        assertTrue(visible, "tendinitis" !in visible)
        assertTrue(visible, visible.startsWith("Good work"))
    }

    @Test
    fun `allowed numbers do stream through`() {
        assertEquals(
            "You finished 8 sets and 5 passed.",
            safeVisiblePrefix("You finished 8 sets and 5 passed.", facts),
        )
    }

    @Test
    fun `a clean stream still ends as a model summary`() = runBlocking {
        val text = "You have finished 8 sets and 5 reached the 80 pass mark. " +
            "Your middle score is 84 out of 100."
        val seen = mutableListOf<String>()

        val summary = generateProgressSummary(facts, streamOf(text), onPartial = { seen += it })

        assertEquals(SummarySource.MODEL, summary.source)
        assertEquals(text, summary.text)
        // The reveal grew rather than jumping straight to the finished paragraph.
        assertTrue(seen.size > 10)
        assertTrue(seen.last().isNotBlank())
    }

    @Test
    fun `one attempt means one attempt`() = runBlocking {
        // What the screens ask for. A streamed retry makes the reader watch one paragraph appear,
        // vanish and be replaced - so a refused draft goes straight to the template instead.
        var calls = 0
        val summary = generateProgressSummary(
            facts,
            { calls++; "You improved by 15 percent." },
            retryOnRejection = false,
        )

        assertEquals(1, calls)
        assertEquals(SummarySource.TEMPLATE, summary.source)
        assertTrue(summary.rejections.any { "15" in it })
    }

    @Test
    fun `a generator with no streaming support still works`() = runBlocking {
        val seen = mutableListOf<String>()
        val summary = generateProgressSummary(
            facts,
            { "You have finished 8 sets and 5 reached the 80 pass mark." },
            onPartial = { seen += it },
        )

        assertEquals(SummarySource.MODEL, summary.source)
        assertTrue("a non-streaming generator reported partials", seen.isEmpty())
    }
}
