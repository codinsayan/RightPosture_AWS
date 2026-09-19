package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.CalibrationCheckId
import com.example.rightposture.core.live.CalibrationGate
import com.example.rightposture.core.live.ViewRecheck
import com.example.rightposture.core.live.requiredViewFromLabel
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.pose.SyntheticPoseConfig
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.syntheticKneeRepetitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.20. "A gate, not a formality." Each of the seven checks is exercised on its own - a check
 * that only ever passes alongside the other six proves nothing about which one is doing the work -
 * and then all seven together, because the gate's real job is refusing to start until they agree.
 */
class CalibrationTest {

    private val fps = 30.0
    private val step = (1_000_000_000.0 / fps).toLong()

    /** A standing rest frame from the same rig every other engine test is built on. */
    private fun restFrame(kneeAngleDegrees: Float = 170f): LandmarkFrame =
        syntheticKneeRepetitions(
            SyntheticPoseConfig(repCount = 1, minimumKneeAngleDegrees = kneeAngleDegrees, maximumKneeAngleDegrees = kneeAngleDegrees + 1f),
        ).first().frame

    /**
     * Re-projects the rig's own world coordinates into image space at a chosen scale, so the same
     * pose can be presented as a sagittal or a frontal view without disturbing the world landmarks
     * the stationary check reads. Derived once in the scratch work behind this file: with the rig's
     * shoulder half-width 0.2 m and shoulder-to-hip height 0.5 m, `ratio = 0.8 * (scaleX / scaleY)`.
     */
    private fun sagittal(frame: LandmarkFrame) = frame.copy(imageLandmarks = remap(frame, scaleX = 0.10f, scaleY = 0.40f))
    private fun frontal(frame: LandmarkFrame) = frame.copy(imageLandmarks = remap(frame, scaleX = 0.50f, scaleY = 0.40f))

    private fun remap(frame: LandmarkFrame, scaleX: Float, scaleY: Float): List<Vec3> =
        frame.worldLandmarks.map { Vec3(0.5f + it.x * scaleX, 0.5f - it.y * scaleY, it.z) }

    /** The rig's knee sweeps 80 to 170 degrees, so this is its reference span. */
    private val spanDegrees = 90.0

    private fun gate(span: Double = spanDegrees) = CalibrationGate(
        requiredView = CameraView.SAGITTAL,
        dominant = FeatureId.KNEE_FLEX_ACTIVE,
        watchedFeatures = listOf(FeatureId.KNEE_FLEX_ACTIVE),
        activeSide = Side.RIGHT,
        spanDegrees = span,
    )

    // -------------------------------------------------------------------------------------
    // View label parsing
    // -------------------------------------------------------------------------------------

    @Test
    fun `the manifest's view label parses, and defaults sagittal`() {
        assertEquals(CameraView.SAGITTAL, requiredViewFromLabel("sagittal"))
        assertEquals(CameraView.FRONTAL, requiredViewFromLabel("frontal"))
        assertEquals(CameraView.FRONTAL, requiredViewFromLabel("Frontal"))
        assertEquals(CameraView.SAGITTAL, requiredViewFromLabel("whatever this is"))
    }

    // -------------------------------------------------------------------------------------
    // Check 1 - visibility, held 2.0 s
    // -------------------------------------------------------------------------------------

    @Test
    fun `an occluded watched joint fails visibility immediately`() {
        val frame = sagittal(restFrame()).let {
            it.copy(visibility = it.visibility.toMutableList().also { v -> v[PoseLandmark.RIGHT_KNEE] = 0.2f })
        }
        val state = gate().update(frame, upright(), 0L)

        assertFalse(state.passed(CalibrationCheckId.VISIBILITY))
    }

    @Test
    fun `visibility must be held for 2 seconds, not glimpsed once`() {
        val g = gate()
        val frame = sagittal(restFrame())
        var state = g.update(frame, upright(), 0L)
        assertFalse("the first frame alone cannot satisfy a 2 s hold", state.passed(CalibrationCheckId.VISIBILITY))

        var t = 0L
        repeat((2.5 * fps).toInt()) {
            t += step
            state = g.update(frame, upright(), t)
        }
        assertTrue("held continuously for 2.5 s, it must now pass", state.passed(CalibrationCheckId.VISIBILITY))
    }

    @Test
    fun `a single dropped frame resets the visibility hold`() {
        val g = gate()
        val good = sagittal(restFrame())
        val bad = good.copy(visibility = good.visibility.toMutableList().also { it[PoseLandmark.RIGHT_KNEE] = 0.2f })

        var t = 0L
        // Nearly 2 s of good frames, then one bad one, then 1 s more of good frames.
        repeat((1.9 * fps).toInt()) { t += step; g.update(good, upright(), t) }
        t += step
        g.update(bad, upright(), t)
        var state = good.let { g.update(it, upright(), t) }
        repeat((1.0 * fps).toInt()) { t += step; state = g.update(good, upright(), t) }

        assertFalse("the interruption must restart the 2 s clock, which 1 s cannot satisfy", state.passed(CalibrationCheckId.VISIBILITY))
    }

    // -------------------------------------------------------------------------------------
    // Checks 2 and 3 - frame fill and centring, both instantaneous
    // -------------------------------------------------------------------------------------

    @Test
    fun `too small or too large in frame fails the fill check`() {
        val g = gate()
        val tooFar = restFrame().let { it.copy(imageLandmarks = remap(it, scaleX = 0.05f, scaleY = 0.20f)) }
        val tooClose = restFrame().let { it.copy(imageLandmarks = remap(it, scaleX = 0.30f, scaleY = 1.20f)) }

        assertFalse(g.update(tooFar, upright(), 0L).passed(CalibrationCheckId.FRAME_FILL))
        assertFalse(g.update(tooClose, upright(), step).passed(CalibrationCheckId.FRAME_FILL))
    }

    @Test
    fun `the right distance passes the fill check`() {
        val state = gate().update(sagittal(restFrame()), upright(), 0L)

        assertTrue(state.passed(CalibrationCheckId.FRAME_FILL))
    }

    @Test
    fun `off to one side of the frame fails centring`() {
        val frame = sagittal(restFrame()).let {
            it.copy(imageLandmarks = it.imageLandmarks.map { p -> Vec3(p.x + 0.35f, p.y, p.z) })
        }
        val state = gate().update(frame, upright(), 0L)

        assertFalse(state.passed(CalibrationCheckId.CENTERED))
    }

    // -------------------------------------------------------------------------------------
    // Check 4 - view classification against the manifest's required view
    // -------------------------------------------------------------------------------------

    @Test
    fun `a frontal presentation fails a sagittal requirement, and passes its own`() {
        val sagittalGate = gate()
        val frontalGate = CalibrationGate(
            CameraView.FRONTAL,
            FeatureId.KNEE_FLEX_ACTIVE,
            listOf(FeatureId.KNEE_FLEX_ACTIVE),
            Side.RIGHT,
            spanDegrees,
        )
        val frontalFrame = frontal(restFrame())

        assertFalse(sagittalGate.update(frontalFrame, upright(), 0L).passed(CalibrationCheckId.VIEW))
        assertTrue(frontalGate.update(frontalFrame, upright(), 0L).passed(CalibrationCheckId.VIEW))
    }

    // -------------------------------------------------------------------------------------
    // Check 5 - phone upright, from the gravity vector
    // -------------------------------------------------------------------------------------

    private fun upright() = Vec3(0f, 9.8f, 0f)
    private fun flatOnATable() = Vec3(0f, 0f, 9.8f)

    @Test
    fun `a flat phone fails the upright check`() {
        val state = gate().update(sagittal(restFrame()), flatOnATable(), 0L)

        assertFalse(state.passed(CalibrationCheckId.UPRIGHT))
    }

    @Test
    fun `no sensor reading yet fails upright rather than assuming it`() {
        val state = gate().update(sagittal(restFrame()), null, 0L)

        assertFalse("an unread sensor is not evidence of anything", state.passed(CalibrationCheckId.UPRIGHT))
    }

    @Test
    fun `the plan's own 10 degree boundary is exact`() {
        val g1 = gate()
        val g2 = gate()
        val justInside = Vec3(0f, 9.8f, 9.8f * 0.16f)
        val justOutside = Vec3(0f, 9.8f, 9.8f * 0.18f)

        assertTrue(g1.update(sagittal(restFrame()), justInside, 0L).passed(CalibrationCheckId.UPRIGHT))
        assertFalse(g2.update(sagittal(restFrame()), justOutside, 0L).passed(CalibrationCheckId.UPRIGHT))
    }

    // -------------------------------------------------------------------------------------
    // Check 6 - lighting, from mean presence
    // -------------------------------------------------------------------------------------

    @Test
    fun `dim lighting fails on mean presence`() {
        val frame = sagittal(restFrame()).let { it.copy(presence = List(it.presence.size) { 0.5f }) }

        assertFalse(gate().update(frame, upright(), 0L).passed(CalibrationCheckId.LIGHTING))
    }

    // -------------------------------------------------------------------------------------
    // Check 7 - stationary start, held 1.0 s
    // -------------------------------------------------------------------------------------

    @Test
    fun `a moving knee fails the stationary check`() {
        val g = gate()
        var t = 0L
        var state = g.update(sagittal(restFrame(170f)), upright(), t)
        // A fast, real sweep, not a jump - large enough to clear V_STALL comfortably.
        repeat(10) {
            t += step
            state = g.update(sagittal(restFrame(170f - it * 4f)), upright(), t)
        }
        assertFalse("a moving joint is not a stationary start", state.passed(CalibrationCheckId.STATIONARY))
    }

    /** Drives the gate at a constant angular rate and returns the final state. */
    private fun driveAtRate(
        g: CalibrationGate,
        degreesPerSecond: Double,
        seconds: Double = 2.0,
    ): com.example.rightposture.core.live.CalibrationState {
        var t = 0L
        var angle = 130.0
        var state = g.update(sagittal(restFrame(angle.toFloat())), upright(), t)
        repeat((seconds * fps).toInt()) {
            t += step
            angle += degreesPerSecond / fps
            state = g.update(sagittal(restFrame(angle.toFloat())), upright(), t)
        }
        return state
    }

    @Test
    fun `the stationary bound is a phi rate, not a degrees rate`() {
        // The bug this pins. V_STALL is 0.05 and §6.12.3 compares it against dphi/dt, where phi is
        // the movement normalised to its own range. Comparing raw degrees per second against it
        // makes the bound tighter by the whole span: on this 90 degree exercise it demands
        // 0.05 deg/s, a stillness no living person produces, and check 7 never passed on a device.
        //
        // 2 deg/s is 0.022 phi/s here, comfortably inside the bound, and 40x outside it in degrees.
        val state = driveAtRate(gate(), degreesPerSecond = 2.0)

        assertTrue(
            "a slow drift within V_STALL must read as stationary: ${state.dominantVelocity}",
            state.passed(CalibrationCheckId.STATIONARY),
        )
    }

    @Test
    fun `the same absolute rate fails on an exercise with a smaller range`() {
        // Which is the point of normalising: 2 deg/s is nothing across 90 degrees and is most of
        // the movement across 10. If the span were ignored, both would give the same verdict.
        val state = driveAtRate(gate(span = 10.0), degreesPerSecond = 2.0)

        assertFalse(
            "0.2 phi/s is outside V_STALL: ${state.dominantVelocity}",
            state.passed(CalibrationCheckId.STATIONARY),
        )
    }

    @Test
    fun `the measured velocity is reported in phi per second for diagnosis`() {
        val state = driveAtRate(gate(), degreesPerSecond = 2.0)

        // 2 deg/s over a 90 degree span.
        assertEquals(2.0 / 90.0, state.dominantVelocity!!, 5e-3)
    }

    @Test
    fun `holding still for 1 second passes the stationary check`() {
        val g = gate()
        val frame = sagittal(restFrame())
        var t = 0L
        var state = g.update(frame, upright(), t)
        repeat((1.5 * fps).toInt()) {
            t += step
            state = g.update(frame, upright(), t)
        }
        assertTrue(state.passed(CalibrationCheckId.STATIONARY))
    }

    // -------------------------------------------------------------------------------------
    // The gate as a whole
    // -------------------------------------------------------------------------------------

    @Test
    fun `every check can pass together, and the gate reports ready`() {
        val g = gate()
        val frame = sagittal(restFrame())
        var t = 0L
        var state = g.update(frame, upright(), t)
        repeat((2.5 * fps).toInt()) {
            t += step
            state = g.update(frame, upright(), t)
        }

        assertTrue("expected every check to pass: ${state.checks}", state.ready)
    }

    @Test
    fun `one failing check is enough to withhold readiness`() {
        // A second watched feature whose landmarks do not overlap the dominant knee chain, so
        // occluding it fails VISIBILITY without also invalidating the angle STATIONARY reads.
        val g = CalibrationGate(
            requiredView = CameraView.SAGITTAL,
            dominant = FeatureId.KNEE_FLEX_ACTIVE,
            watchedFeatures = listOf(FeatureId.KNEE_FLEX_ACTIVE, FeatureId.ELBOW_FLEX_ACTIVE),
            activeSide = Side.RIGHT,
            spanDegrees = spanDegrees,
        )
        val frame = sagittal(restFrame()).let {
            it.copy(visibility = it.visibility.toMutableList().also { v -> v[PoseLandmark.RIGHT_ELBOW] = 0.2f })
        }
        var t = 0L
        var state = g.update(frame, upright(), t)
        repeat((2.5 * fps).toInt()) {
            t += step
            state = g.update(frame, upright(), t)
        }

        assertFalse(state.ready)
        assertFalse(state.passed(CalibrationCheckId.VISIBILITY))
        assertTrue("only the occluded joint's check should fail", state.passed(CalibrationCheckId.STATIONARY))
    }

    @Test
    fun `losing the person resets every hold`() {
        val g = gate()
        val frame = sagittal(restFrame())
        var t = 0L
        var state = g.update(frame, upright(), t)
        repeat((2.5 * fps).toInt()) { t += step; state = g.update(frame, upright(), t) }
        assertTrue(state.ready)

        t += step
        state = g.update(null, upright(), t)
        assertFalse("no person means nothing can be confirmed", state.ready)

        t += step
        state = g.update(frame, upright(), t)
        assertFalse("one frame after the person returns is not a fresh 2 s hold", state.passed(CalibrationCheckId.VISIBILITY))
    }

    @Test
    fun `reset clears the holds, exactly like starting over`() {
        val g = gate()
        val frame = sagittal(restFrame())
        var t = 0L
        var state = g.update(frame, upright(), t)
        repeat((2.5 * fps).toInt()) { t += step; state = g.update(frame, upright(), t) }
        assertTrue(state.ready)

        g.reset()
        state = g.update(frame, upright(), t + step)
        assertFalse(state.ready)
    }

    // -------------------------------------------------------------------------------------
    // The mid-set recheck and phone-bump pause path (§6.20.2, §8.2)
    // -------------------------------------------------------------------------------------

    @Test
    fun `the diagnostic names the required view and what is failing`() {
        // §6.20 refusing to start is only actionable if it says why. The verdicts alone cannot
        // separate "stand differently" from "this reference was authored against the wrong view".
        val state = gate().update(frontal(restFrame()), upright(), 0L)
        val line = state.diagnostic()

        assertTrue(line, line.contains("FRONTAL/SAGITTAL"))
        assertTrue(line, line.contains("VIEW"))
        assertTrue("the ratio behind the verdict: $line", state.viewRatio != null)
        assertEquals(CameraView.SAGITTAL, state.requiredView)
    }

    @Test
    fun `a stable view never enters recalibration`() {
        val recheck = ViewRecheck(CameraView.SAGITTAL)
        val frame = sagittal(restFrame())
        var t = 0L
        repeat(4) {
            recheck.update(frame, t)
            t += (5.5 * 1_000_000_000.0).toLong()
        }
        assertFalse(recheck.isRecalibrating)
    }

    @Test
    fun `a view that drifts away is caught on the next 5 second sample`() {
        val recheck = ViewRecheck(CameraView.SAGITTAL)
        val sagittalFrame = sagittal(restFrame())
        val frontalFrame = frontal(restFrame())

        assertFalse(recheck.update(sagittalFrame, 0L))
        // Under 5 s: not yet sampled again, so a bump here must not be caught early.
        assertFalse(recheck.update(frontalFrame, (2 * 1_000_000_000.0).toLong()))
        assertTrue("the sample at 5 s must catch the drift", recheck.update(frontalFrame, (5 * 1_000_000_000.0).toLong()))
    }

    @Test
    fun `an unclassifiable frame is not treated as a bump`() {
        val recheck = ViewRecheck(CameraView.SAGITTAL)
        recheck.update(sagittal(restFrame()), 0L)
        val ambiguous = restFrame().copy(visibility = List(33) { 0f })

        assertFalse(recheck.update(ambiguous, (5 * 1_000_000_000.0).toLong()))
    }

    @Test
    fun `recalibration resumes once the required view is held again`() {
        val recheck = ViewRecheck(CameraView.SAGITTAL)
        val sagittalFrame = sagittal(restFrame())
        val frontalFrame = frontal(restFrame())
        recheck.update(sagittalFrame, 0L)
        assertTrue(recheck.update(frontalFrame, (5 * 1_000_000_000.0).toLong()))

        var t = (5 * 1_000_000_000.0).toLong()
        var recalibrating = true
        repeat((1.5 * fps).toInt()) {
            t += step
            recalibrating = recheck.update(sagittalFrame, t)
        }
        assertFalse("held the required view for 1 s, the set should resume", recalibrating)
    }
}
