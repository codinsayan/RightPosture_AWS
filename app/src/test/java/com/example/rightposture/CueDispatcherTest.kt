package com.example.rightposture

import com.example.rightposture.core.live.Cue
import com.example.rightposture.core.live.CueDispatcher
import com.example.rightposture.core.live.CueLevel
import com.example.rightposture.core.live.FrameResult
import com.example.rightposture.core.live.CompletedRep
import com.example.rightposture.core.live.ExerciseStatus
import com.example.rightposture.core.live.Haptics
import com.example.rightposture.core.live.HindiPhrases
import com.example.rightposture.core.live.RepRejection
import com.example.rightposture.core.live.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.2's dispatcher test. The fake speaker is the point: §12.4's contract is *what* is spoken and
 * *when*, and that is decidable without an Android engine.
 */
class CueDispatcherTest {

    /** Records utterances in order. Queue-flush semantics live in the platform speaker, so what is
     *  asserted here is that a newer cue is handed over at all rather than suppressed. */
    private class FakeSpeaker(override var isReady: Boolean = true) : Speaker {
        val spoken = mutableListOf<Pair<String, String>>()
        override fun speak(utteranceId: String, text: String) {
            spoken += utteranceId to text
        }
    }

    private class FakeHaptics : Haptics {
        var onsets = 0
        var resolutions = 0
        var repsCounted = 0
        var repsRejected = 0
        var setsComplete = 0
        override fun onFaultOnset() { onsets++ }
        override fun onFaultResolved() { resolutions++ }
        override fun onRepCounted() { repsCounted++ }
        override fun onRepRejected() { repsRejected++ }
        override fun onSetComplete() { setsComplete++ }
    }

    private fun cue(
        faultId: String = "knee_valgus",
        occurrence: Int = 1,
        level: CueLevel = CueLevel.SHORT,
        text: String = "Knee out.",
    ) = Cue(
        faultId = faultId,
        level = level,
        text = text,
        occurrence = occurrence,
        usedAlternative = false,
        arrowJoint = "knee",
        arrowAxis = "x",
    )

    private fun frame(
        cue: Cue? = null,
        onsets: List<String> = emptyList(),
        clears: List<String> = emptyList(),
    ) = FrameResult.EMPTY.copy(cue = cue, faultOnsetIds = onsets, faultClearIds = clears)

    private fun rep(valid: Boolean, rejection: RepRejection? = null) = CompletedRep(
        valid = valid,
        phiMax = if (valid) 1.0 else 0.4,
        durationSeconds = 2.0,
        validFraction = 1.0,
        maxPhiRatePerSecond = 0.0,
        checkpointsVisited = com.example.rightposture.core.Params.TRAJECTORY_CHECKPOINTS,
        checkpointsRequired = com.example.rightposture.core.Params.TRAJECTORY_CHECKPOINTS,
        nonConformantFrames = 0,
        rejection = rejection,
    )

    // -------------------------------------------------------------------------------------
    // The app's own voice - what it says when the bundle has no faults to cue on
    // -------------------------------------------------------------------------------------

    @Test
    fun `a counted rep is announced and ticks, without any fault being present`() {
        val speaker = FakeSpeaker()
        val haptics = FakeHaptics()
        val dispatcher = CueDispatcher(speaker, haptics)

        dispatcher.onFrame(FrameResult.EMPTY.copy(completedRep = rep(valid = true), validReps = 1))

        assertEquals(1, haptics.repsCounted)
        assertEquals(listOf("1"), speaker.spoken.map { it.second })
    }

    @Test
    fun `a refused rep says why it did not count`() {
        val speaker = FakeSpeaker()
        val haptics = FakeHaptics()
        val dispatcher = CueDispatcher(speaker, haptics)

        dispatcher.onFrame(
            FrameResult.EMPTY.copy(
                completedRep = rep(valid = false, rejection = RepRejection.RANGE),
                partialReps = 1,
            ),
        )

        assertEquals(1, haptics.repsRejected)
        assertEquals(0, haptics.repsCounted)
        assertTrue(speaker.spoken.single().second.contains("all the way down"))
    }

    @Test
    fun `the end of the set is announced exactly once`() {
        val speaker = FakeSpeaker()
        val haptics = FakeHaptics()
        val dispatcher = CueDispatcher(speaker, haptics)

        repeat(10) { dispatcher.onFrame(FrameResult.EMPTY.copy(status = ExerciseStatus.SET_COMPLETE)) }

        assertEquals(1, haptics.setsComplete)
        assertEquals(1, speaker.spoken.size)
        assertTrue(speaker.spoken.single().second.contains("Set complete"))
    }

    @Test
    fun `switching to Hindi changes what is spoken, not when`() {
        val speaker = FakeSpeaker()
        val dispatcher = CueDispatcher(speaker, FakeHaptics())
        dispatcher.setLanguage(HindiPhrases)

        dispatcher.onFrame(FrameResult.EMPTY.copy(completedRep = rep(valid = true), validReps = 3))
        dispatcher.onFrame(
            FrameResult.EMPTY.copy(
                completedRep = rep(valid = false, rejection = RepRejection.TOO_FAST),
                validReps = 3,
                partialReps = 1,
            ),
        )

        assertEquals(listOf("तीन", "यह गिनती में नहीं आया — बहुत तेज़ था।"), speaker.spoken.map { it.second })
    }

    @Test
    fun `rep announcements are dropped, not queued, while speech is unavailable`() {
        val speaker = FakeSpeaker(isReady = false)
        val haptics = FakeHaptics()
        val dispatcher = CueDispatcher(speaker, haptics)

        dispatcher.onFrame(FrameResult.EMPTY.copy(completedRep = rep(valid = true), validReps = 1))

        // The buzz still happens: §12.5 is the channel that survives no speech at all.
        assertEquals(1, haptics.repsCounted)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun `a cue is spoken once, however many frames carry it`() {
        val speaker = FakeSpeaker()
        val dispatcher = CueDispatcher(speaker, FakeHaptics())

        repeat(5) { dispatcher.onFrame(frame(cue())) }

        assertEquals(1, speaker.spoken.size)
        assertEquals("Knee out.", speaker.spoken.single().second)
    }

    @Test
    fun `a newer cue is spoken over a stale one`() {
        val speaker = FakeSpeaker()
        val dispatcher = CueDispatcher(speaker, FakeHaptics())

        dispatcher.onFrame(frame(cue(occurrence = 1, text = "Knee out.")))
        dispatcher.onFrame(frame(cue(occurrence = 2, level = CueLevel.FULL, text = "Push the knee out over your toes.")))

        assertEquals(2, speaker.spoken.size)
        assertEquals("Push the knee out over your toes.", speaker.spoken.last().second)
        // The utterance id has to differ, or QUEUE_FLUSH cannot tell the two apart downstream.
        assertTrue(speaker.spoken[0].first != speaker.spoken[1].first)
    }

    @Test
    fun `a different fault is spoken even at the same escalation rung`() {
        val speaker = FakeSpeaker()
        val dispatcher = CueDispatcher(speaker, FakeHaptics())

        dispatcher.onFrame(frame(cue(faultId = "knee_valgus")))
        dispatcher.onFrame(frame(cue(faultId = "trunk_lean", text = "Stand tall.")))

        assertEquals(2, speaker.spoken.size)
    }

    @Test
    fun `nothing is spoken until the engine is ready, and the missed cue is not queued`() {
        val speaker = FakeSpeaker(isReady = false)
        val dispatcher = CueDispatcher(speaker, FakeHaptics())

        dispatcher.onFrame(frame(cue(text = "Knee out.")))
        assertTrue(speaker.spoken.isEmpty())

        // Speech comes up. §6.15's cooldowns are wall-clock, so the stale cue is dropped rather
        // than replayed over a movement it no longer describes.
        speaker.isReady = true
        dispatcher.onFrame(frame())
        assertTrue(speaker.spoken.isEmpty())

        dispatcher.onFrame(frame(cue(occurrence = 2, text = "Push the knee out.")))
        assertEquals(listOf("Push the knee out."), speaker.spoken.map { it.second })
    }

    @Test
    fun `fault onset and resolution each buzz once, and do so without speech`() {
        val speaker = FakeSpeaker(isReady = false)
        val haptics = FakeHaptics()
        val dispatcher = CueDispatcher(speaker, haptics)

        dispatcher.onFrame(frame(onsets = listOf("knee_valgus")))
        dispatcher.onFrame(frame())
        dispatcher.onFrame(frame(clears = listOf("knee_valgus")))

        assertEquals(1, haptics.onsets)
        assertEquals(1, haptics.resolutions)
        assertTrue("haptics must not depend on TTS", speaker.spoken.isEmpty())
    }

    @Test
    fun `a new set may repeat what the last one said`() {
        val speaker = FakeSpeaker()
        val dispatcher = CueDispatcher(speaker, FakeHaptics())

        dispatcher.onFrame(frame(cue()))
        dispatcher.resetSet()
        dispatcher.onFrame(frame(cue()))

        assertEquals(2, speaker.spoken.size)
    }
}
