package com.example.rightposture

import com.example.rightposture.camera.GhostHint
import com.example.rightposture.camera.LiveGhost
import com.example.rightposture.camera.LiveGhostFrame
import com.example.rightposture.camera.drawableBones
import com.example.rightposture.core.Params
import com.example.rightposture.core.live.EnglishPhrases
import com.example.rightposture.core.live.FrameResult
import com.example.rightposture.core.live.GhostState
import com.example.rightposture.core.live.HindiPhrases
import com.example.rightposture.core.live.PhaseState
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.GHOST_LANDMARK_SLOT
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.syntheticTwoStepRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.SkeletonReference
import com.example.rightposture.core.reference.runAuthoringPipeline
import com.example.rightposture.pose.POSE_SKELETON_CONNECTIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 10.9. The live guide's decisions, without a camera: what it says, when it stops, what it draws. */
class LiveGhostTest {

    // -------------------------------------------------------------------------------------
    // What the panel says
    // -------------------------------------------------------------------------------------

    @Test
    fun `the panel shows the count-in while the clock is counting`() {
        val run = Run()
        val first = run.frame()
        assertEquals(GhostHint.CountIn(Params.GHOST_COUNT_IN_SECONDS.toInt()), first.hint)

        val later = run.advance(Params.GHOST_COUNT_IN_SECONDS + 0.1, moving = true)
        assertEquals(GhostState.MOVING, later.tick.state)
        assertFalse("no count-in once the guide moves", later.hint is GhostHint.CountIn)
    }

    @Test
    fun `behind appears only past the threshold, and does not flicker at the edge`() {
        val run = Run()
        run.advance(Params.GHOST_COUNT_IN_SECONDS + 0.1, moving = true)
        // Into the rep: the physio's recording opens with a held beat at phase 0, where nobody can
        // be a quarter of a rep behind.
        while ((run.frame(moving = true).tick.phase ?: 0.0) < 40.0) run.advance(1.0 / 30, moving = true)

        // Keeping up: the patient's phase tracks the guide's.
        val keepingUp = run.follow(seconds = 0.1) { guide -> guide - 5.0 }
        assertNull(keepingUp.hint)

        // Falling well behind switches it on.
        val behind = run.follow(seconds = 0.1) { guide -> guide - (LiveGhost.BEHIND_ON_PHASE + 5.0) }
        assertEquals(GhostHint.Behind, behind.hint)

        // Between the two thresholds it stays on rather than flickering off.
        val between = run.follow(seconds = 0.1) { guide -> guide - (LiveGhost.BEHIND_OFF_PHASE + LiveGhost.BEHIND_ON_PHASE) / 2 }
        assertEquals(GhostHint.Behind, between.hint)

        // Catching up switches it off.
        val caughtUp = run.follow(seconds = 0.1) { guide -> guide - 2.0 }
        assertNull(caughtUp.hint)
    }

    @Test
    fun `a still patient keeps being shown the movement and is prompted to join in`() {
        val run = Run()
        run.advance(Params.GHOST_COUNT_IN_SECONDS + skeleton.timing.repDurationSeconds + 1.0)
        assertEquals(GhostHint.Prompt, run.frame().hint)

        // Several reps later the guide is still demonstrating, not parked.
        val phases = (0 until 90).map { run.advance(1.0 / 30).tick.phase!! }
        assertTrue("the guide still moves", phases.max() - phases.min() > 30.0)
        assertTrue(phases.indices.all { run.frame().hint == GhostHint.Prompt })
    }

    @Test
    fun `the prompt goes the moment the patient moves`() {
        val run = Run()
        run.advance(Params.GHOST_COUNT_IN_SECONDS + skeleton.timing.repDurationSeconds + 1.0)
        assertEquals(GhostHint.Prompt, run.frame().hint)
        assertNotEquals(GhostHint.Prompt, run.frame(moving = true).hint)
    }

    @Test
    fun `every guide line and the toggle exist in English and Hindi`() {
        listOf(EnglishPhrases, HindiPhrases).forEach { phrases ->
            listOf(
                phrases.ghostCountIn(3),
                phrases.ghostPrompt(),
                phrases.ghostBehind(),
                phrases.ghostToggle(true),
                phrases.ghostToggle(false),
            ).forEach { assertTrue("${phrases.language}: blank line", it.isNotBlank()) }
            assertTrue("the count-in says the number", phrases.ghostCountIn(3).contains("3"))
            assertNotEquals(phrases.ghostToggle(true), phrases.ghostToggle(false))
        }
        assertNotEquals(EnglishPhrases.ghostBehind(), HindiPhrases.ghostBehind())
    }

    // -------------------------------------------------------------------------------------
    // The clock, as the screen drives it
    // -------------------------------------------------------------------------------------

    @Test
    fun `a rep counted by the engine resynchronises the guide`() {
        val run = Run()
        run.advance(Params.GHOST_COUNT_IN_SECONDS + 0.1, moving = true)
        val midway = run.advance(skeleton.timing.repDurationSeconds * 0.6, moving = true)
        assertTrue(midway.tick.phase!! > 20.0)

        // The engine's counter moves; the screen never saw `completedRep` itself.
        run.validReps = 1
        val resynced = run.frame(moving = false)
        assertEquals(0.0, resynced.tick.phase!!, 1e-9)
    }

    @Test
    fun `a rejected rep resynchronises it too`() {
        val run = Run()
        run.advance(Params.GHOST_COUNT_IN_SECONDS + 0.1, moving = true)
        run.advance(skeleton.timing.repDurationSeconds * 0.6, moving = true)

        run.partialReps = 1
        assertEquals(0.0, run.frame(moving = false).tick.phase!!, 1e-9)
    }

    @Test
    fun `the guide stops when the prescription is met`() {
        val run = Run(prescribed = 2)
        run.advance(Params.GHOST_COUNT_IN_SECONDS + 0.1, moving = true)

        run.validReps = 2
        val done = run.frame(moving = true)
        assertEquals(GhostState.DONE, done.tick.state)
        assertNull("nothing drawn past the prescription", done.ghost)
        assertTrue(done.bones.isEmpty())
        assertNull(done.hint)
    }

    // -------------------------------------------------------------------------------------
    // Per-bone abstention
    // -------------------------------------------------------------------------------------

    @Test
    fun `a well recorded skeleton draws every bone it carries`() {
        val drawn = drawableBones(skeleton, 50.0)
        val carried = POSE_SKELETON_CONNECTIONS.filter { (a, b) -> a in GHOST_LANDMARK_SLOT && b in GHOST_LANDMARK_SLOT }
        assertEquals(carried.toSet(), drawn.toSet())
    }

    @Test
    fun `a poorly seen ankle hides its own bones and nothing else`() {
        val slot = GHOST_LANDMARK_SLOT.getValue(PoseLandmark.LEFT_ANKLE)
        val hidden = skeleton.copy(
            visibility = skeleton.visibility.mapIndexed { index, value ->
                if (index % skeleton.landmarks.size == slot) 0.2f else value
            },
        )
        val drawn = drawableBones(hidden, 50.0)

        assertTrue(drawn.none { PoseLandmark.LEFT_ANKLE in it.toList() })
        assertTrue("the trunk is still drawn", (PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER) in drawn ||
            (PoseLandmark.RIGHT_SHOULDER to PoseLandmark.LEFT_SHOULDER) in drawn)
        assertEquals(drawableBones(skeleton, 50.0).size - drawn.size, drawableBones(skeleton, 50.0).count { PoseLandmark.LEFT_ANKLE in it.toList() })
    }

    @Test
    fun `a landmark the physio placed inconsistently is not drawn`() {
        val slot = GHOST_LANDMARK_SLOT.getValue(PoseLandmark.RIGHT_WRIST)
        val scattered = skeleton.copy(
            spread = skeleton.spread.mapIndexed { index, value ->
                if (index % skeleton.landmarks.size == slot) LiveGhost.MAX_BONE_SPREAD + 0.1f else value
            },
        )
        assertTrue(drawableBones(scattered, 50.0).none { PoseLandmark.RIGHT_WRIST in it.toList() })
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private val skeleton: SkeletonReference by lazy {
        val captured = syntheticTwoStepRepetitions(repCount = 9).map { CapturedFrame(it.frame, it.timestampNs) }
        runAuthoringPipeline(captured).skeleton!!
    }

    /** The screen's sampler, minus the camera: an engine state it controls and a 30 Hz clock. */
    private inner class Run(private val prescribed: Int = 12) {
        private val ghost = LiveGhost(skeleton, recordedMirrored = false, requiredView = CameraView.SAGITTAL)
        private var timestampNs = 1_000_000_000L
        var validReps = 0
        var partialReps = 0
        private var last: LiveGhostFrame? = null

        fun frame(moving: Boolean = false, patientPhase: Double = 0.0): LiveGhostFrame {
            val state = FrameResult.EMPTY.copy(
                phaseState = if (moving) PhaseState.DESCEND else PhaseState.IDLE,
                phase = patientPhase,
                validReps = validReps,
                partialReps = partialReps,
            )
            return ghost.update(state, frame = null, timestampNs, imageAspect = 0.5625f, liveMirrored = true, prescribed)
                .also { last = it }
        }

        fun advance(seconds: Double, moving: Boolean = false): LiveGhostFrame {
            repeat((seconds * 30).toInt().coerceAtLeast(1)) {
                timestampNs += 33_333_333L
                frame(moving)
            }
            return last!!
        }

        /** Frames for [seconds] with the patient at a phase derived from the guide's previous one. */
        fun follow(seconds: Double, patient: (Double) -> Double): LiveGhostFrame {
            repeat((seconds * 30).toInt().coerceAtLeast(1)) {
                timestampNs += 33_333_333L
                val guide = last?.tick?.phase ?: 0.0
                frame(moving = true, patientPhase = patient(guide).coerceAtLeast(0.0))
            }
            return last!!
        }
    }
}
