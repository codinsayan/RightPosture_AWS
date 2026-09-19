package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.live.HoldAuthoringFailure
import com.example.rightposture.core.live.HoldState
import com.example.rightposture.core.live.HoldTracker
import com.example.rightposture.core.live.HoldUpdate
import com.example.rightposture.core.live.buildHoldReference
import com.example.rightposture.core.live.leastSquaresSlopeOverTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.19. A wall sit is not a rep, and the plan is explicit that treating it like one produces "a
 * meaningless watch list": everything the cyclic path derives from range of motion is undefined
 * when the range of motion is zero by construction.
 *
 * So these tests are mostly about the substitutions - offset from neutral in place of ROM,
 * tightness in place of ROM/sigma - and about the two ways a hold ends.
 */
class HoldModeTest {

    private val fps = 30.0
    private val step = (1_000_000_000.0 / fps).toLong()

    /** A wall sit: knee bent hard, trunk near neutral, everything steady. */
    private fun wallSitFrame(
        knee: Double = 90.0,
        trunk: Double = 5.0,
        hip: Double = 95.0,
    ): Map<FeatureId, Float> = mapOf(
        FeatureId.KNEE_FLEX_ACTIVE to knee.toFloat(),
        FeatureId.TRUNK_FLEXION to trunk.toFloat(),
        FeatureId.HIP_FLEX_ACTIVE to hip.toFloat(),
    )

    /** Standing neutral, which the physio captures for one second before the hold. */
    private val neutral = mapOf(
        FeatureId.KNEE_FLEX_ACTIVE to 175f,
        FeatureId.TRUNK_FLEXION to 2f,
        FeatureId.HIP_FLEX_ACTIVE to 175f,
    )

    private fun steadyRecording(
        seconds: Double = 20.0,
        jitterDegrees: Double = 0.4,
    ): List<Map<FeatureId, Float>> {
        val frames = (seconds * fps).toInt()
        return (0 until frames).map { i ->
            // A deterministic wobble, so the reference has a band rather than a zero-width one.
            val wobble = jitterDegrees * kotlin.math.sin(i * 0.7)
            wallSitFrame(knee = 90.0 + wobble, trunk = 5.0 + wobble * 0.5, hip = 95.0 + wobble)
        }
    }

    // -------------------------------------------------------------------------------------
    // Reference construction
    // -------------------------------------------------------------------------------------

    @Test
    fun `a steady wall sit authors a reference`() {
        val result = buildHoldReference(steadyRecording(), neutral)

        assertNull(result.failure)
        val reference = result.reference!!
        assertEquals(90.0, reference.features.getValue(FeatureId.KNEE_FLEX_ACTIVE).mean, 1.0)
        assertTrue("the stable window excludes the transitions", reference.stableWindowSeconds < 20.0)
    }

    @Test
    fun `the dominant joint is the one furthest from neutral, not the most variable`() {
        // §6.19: "Use the largest ABSOLUTE angle, not the largest variance." The knee is 85 degrees
        // off neutral; the trunk barely moves from it. A variance-based rule would be picking
        // between two nearly-still signals, which is picking noise.
        val reference = buildHoldReference(steadyRecording(), neutral).reference!!

        assertEquals(FeatureId.KNEE_FLEX_ACTIVE, reference.dominant)
    }

    @Test
    fun `salience is tightness, so the best-controlled joint ranks highest`() {
        // §6.19 replaces ROM/sigma with 1/sigma. The trunk is held to half the wobble of the others
        // here, so it must outrank them however small its range is.
        val reference = buildHoldReference(steadyRecording(), neutral).reference!!
        val trunk = reference.features.getValue(FeatureId.TRUNK_FLEXION)
        val knee = reference.features.getValue(FeatureId.KNEE_FLEX_ACTIVE)

        assertTrue("tighter joint, higher salience", trunk.salience >= knee.salience)
    }

    @Test
    fun `the dominant joint is always watched`() {
        val reference = buildHoldReference(steadyRecording(), neutral).reference!!

        assertTrue(reference.watchList.contains(reference.dominant))
    }

    @Test
    fun `a physio who was not holding still is refused`() {
        // §6.19's stability precondition. Without it the reference is a band wide enough to contain
        // any performance, and the hold would score as perfect no matter what the patient did.
        val frames = (20.0 * fps).toInt()
        val wandering = (0 until frames).map { i ->
            wallSitFrame(knee = 90.0 + 25.0 * kotlin.math.sin(i * 0.02))
        }
        val result = buildHoldReference(wandering, neutral)

        assertEquals(HoldAuthoringFailure.NOT_HELD_STILL, result.failure)
        assertNull(result.reference)
        assertTrue(
            "the review screen has to be able to say by how much: ${result.dominantMadDegrees}",
            result.dominantMadDegrees!! > Params.HOLD_MAX_DOMINANT_MAD_DEGREES,
        )
    }

    @Test
    fun `a recording shorter than the trimmed transitions is refused`() {
        assertEquals(
            HoldAuthoringFailure.TOO_SHORT,
            buildHoldReference(steadyRecording(seconds = 3.0), neutral).failure,
        )
    }

    @Test
    fun `a feature valid for only a handful of frames is not a measurement of the hold`() {
        val frames = (20.0 * fps).toInt()
        val mostlyMissing = (0 until frames).map { i ->
            if (i % 20 == 0) wallSitFrame() else wallSitFrame().filterKeys { it != FeatureId.HIP_FLEX_ACTIVE }
        }
        val reference = buildHoldReference(mostlyMissing, neutral).reference!!

        assertFalse(reference.features.containsKey(FeatureId.HIP_FLEX_ACTIVE))
    }

    // -------------------------------------------------------------------------------------
    // Live scoring
    // -------------------------------------------------------------------------------------

    private fun drive(
        tracker: HoldTracker,
        frames: List<Map<FeatureId, Float>>,
    ): List<HoldUpdate> {
        var t = 0L
        return frames.map { tracker.update(it, t).also { _ -> t += step } }
    }

    private fun reference() = buildHoldReference(steadyRecording(), neutral).reference!!

    // -------------------------------------------------------------------------------------
    // Settling: the hold begins when the position is reached, not when the button is pressed
    // -------------------------------------------------------------------------------------

    /** The patient presses start, then takes two seconds to lower into the wall sit. */
    private fun gettingIntoPosition(seconds: Double = 2.0): List<Map<FeatureId, Float>> {
        val frames = (seconds * fps).toInt()
        return (0 until frames).map { i ->
            wallSitFrame(knee = 175.0 - (175.0 - 90.0) * (i.toDouble() / frames))
        }
    }

    @Test
    fun `getting into position does not break the hold`() {
        // Reported from a device and reproduced at 1.03 s before this existed: the patient presses
        // start while standing, and their knee is tens of sigma from a reference that describes a
        // wall sit, so the break condition is met within a second and the hold ends before it began.
        val tracker = HoldTracker(reference())
        val updates = drive(tracker, gettingIntoPosition() + steadyRecording(seconds = 20.0))

        assertTrue("nothing may break on the way in", updates.none { it.brokeThisFrame })
        assertEquals(HoldState.HOLDING, updates.last().state)
    }

    @Test
    fun `the clock starts when the position is reached, not when tracking starts`() {
        val tracker = HoldTracker(reference())
        val updates = drive(tracker, gettingIntoPosition(seconds = 3.0) + steadyRecording(seconds = 10.0))

        // Roughly 10 s of hold, not the 13 s of elapsed tracking. The settle confirmation costs a
        // little on top, so this is a band rather than a point.
        assertTrue(
            "the three seconds spent arriving are not hold time: ${updates.last().durationSeconds}",
            updates.last().durationSeconds in 8.0..11.0,
        )
    }

    @Test
    fun `while settling there is no timer and no drift`() {
        val tracker = HoldTracker(reference())
        val updates = drive(tracker, gettingIntoPosition(seconds = 3.0))

        assertEquals(HoldState.SETTLING, updates.last().state)
        assertEquals(0.0, updates.last().durationSeconds, 1e-9)
        assertFalse(updates.last().driftWarning)
        assertNull(updates.last().worstDriftPerSecond)
    }

    @Test
    fun `passing through the position does not start the clock`() {
        // Momentarily crossing the pose on the way somewhere else is not reaching it.
        val tracker = HoldTracker(reference())
        val passing = gettingIntoPosition(seconds = 2.0) +
            List(4) { wallSitFrame() } +
            (0 until 60).map { wallSitFrame(knee = 90.0 + it * 1.5) }
        val updates = drive(tracker, passing)

        assertEquals(HoldState.SETTLING, updates.last().state)
    }

    @Test
    fun `settling into the position slightly off does not trip drift`() {
        // The drift fit needs a window before it means anything: measured across two seconds it is
        // dominated by the patient arriving, which reads as a steep trend that is really a transient.
        val tracker = HoldTracker(reference())
        val updates = drive(tracker, gettingIntoPosition() + steadyRecording(seconds = 6.0))

        assertEquals(HoldState.HOLDING, updates.last().state)
    }

    @Test
    fun `a stable hold stays held and scores well`() {
        val tracker = HoldTracker(reference())
        val updates = drive(tracker, steadyRecording(seconds = 15.0))
        val last = updates.last()

        assertEquals(HoldState.HOLDING, last.state)
        assertTrue("HoldQuality is the fraction inside 2 sigma: ${last.holdQuality}", last.holdQuality!! > 0.9)
        assertTrue(updates.none { it.brokeThisFrame })
    }

    @Test
    fun `a watched joint far outside the band for long enough breaks the hold`() {
        val reference = reference()
        val sigma = reference.features.getValue(FeatureId.KNEE_FLEX_ACTIVE).sigma
        val tracker = HoldTracker(reference)

        // Steady, then a large sustained breach.
        val frames = steadyRecording(seconds = 5.0) +
            List((2.0 * fps).toInt()) { wallSitFrame(knee = 90.0 + 6.0 * sigma) }
        val updates = drive(tracker, frames)

        assertEquals(HoldState.BROKEN, updates.last().state)
        assertEquals("exactly one break event", 1, updates.count { it.brokeThisFrame })
    }

    @Test
    fun `a brief wobble outside the band does not break the hold`() {
        // §6.19 says *continuously*. A hold that ended on a single flinch would be unusable, so the
        // breach here is deep but shorter than HOLD_BREAK_SECONDS.
        val reference = reference()
        val sigma = reference.features.getValue(FeatureId.KNEE_FLEX_ACTIVE).sigma
        val tracker = HoldTracker(reference)

        val brief = (Params.HOLD_BREAK_SECONDS * 0.5 * fps).toInt()
        val frames = steadyRecording(seconds = 5.0) +
            List(brief) { wallSitFrame(knee = 90.0 + 6.0 * sigma) } +
            steadyRecording(seconds = 5.0)
        val updates = drive(tracker, frames)

        assertEquals(HoldState.HOLDING, updates.last().state)
    }

    // C3 gate: each threshold below has a test that fails at the value it replaced.

    @Test
    fun `a breach between three and four sigma breaks the hold`() {
        // Fails at the old HOLD_BREAK_Z of 4.0, where a 3.5 sigma breach was inside the band and a
        // visibly collapsed wall sit went on being scored as a hold.
        assertTrue(
            "this test only means something while the threshold is under 3.5 sigma",
            Params.HOLD_BREAK_Z < 3.5,
        )
        val reference = reference()
        val sigma = reference.features.getValue(FeatureId.KNEE_FLEX_ACTIVE).sigma
        val tracker = HoldTracker(reference)

        val frames = steadyRecording(seconds = 5.0) +
            List((2.0 * fps).toInt()) { wallSitFrame(knee = 90.0 + 3.5 * sigma) }
        val updates = drive(tracker, frames)

        assertEquals(HoldState.BROKEN, updates.last().state)
    }

    @Test
    fun `a breach shorter than a second still breaks the hold`() {
        // Fails at the old HOLD_BREAK_SECONDS of 1.0, where 0.8 s of a deep breach was tolerated.
        assertTrue(
            "this test only means something while the window is under 0.8 s",
            Params.HOLD_BREAK_SECONDS < 0.8,
        )
        val reference = reference()
        val sigma = reference.features.getValue(FeatureId.KNEE_FLEX_ACTIVE).sigma
        val tracker = HoldTracker(reference)

        val frames = steadyRecording(seconds = 5.0) +
            List((0.8 * fps).toInt()) { wallSitFrame(knee = 90.0 + 6.0 * sigma) }
        val updates = drive(tracker, frames)

        assertEquals(HoldState.BROKEN, updates.last().state)
    }

    @Test
    fun `the break threshold keeps a margin over the re-entry band`() {
        // Not a preference. Re-entry needs every watched joint inside HOLD_QUALITY_Z for
        // HOLD_SETTLE_CONFIRM_SECONDS, and the live hold re-arms on break, so if breaking became as
        // easy as settling the patient would watch it loop between the two.
        assertTrue(
            "HOLD_BREAK_Z ${Params.HOLD_BREAK_Z} must stay clear of HOLD_QUALITY_Z ${Params.HOLD_QUALITY_Z}",
            Params.HOLD_BREAK_Z >= Params.HOLD_QUALITY_Z + 0.5,
        )
    }

    @Test
    fun `the duration stops at the break`() {
        val reference = reference()
        val sigma = reference.features.getValue(FeatureId.KNEE_FLEX_ACTIVE).sigma
        val tracker = HoldTracker(reference)

        val frames = steadyRecording(seconds = 5.0) +
            List((2.0 * fps).toInt()) { wallSitFrame(knee = 90.0 + 6.0 * sigma) } +
            steadyRecording(seconds = 10.0)
        val updates = drive(tracker, frames)

        assertTrue(
            "the timer must stop, not keep running past the break: ${updates.last().durationSeconds}",
            updates.last().durationSeconds < 8.0,
        )
    }

    // -------------------------------------------------------------------------------------
    // Drift - hold mode's fatigue signal
    // -------------------------------------------------------------------------------------

    /** A hold sagging steadily at a chosen rate in sigma per second. */
    private fun sagging(sigmaPerSecond: Double, seconds: Double, sigma: Double): List<Map<FeatureId, Float>> {
        val frames = (seconds * fps).toInt()
        return (0 until frames).map { i ->
            val drifted = 90.0 + sigmaPerSecond * sigma * (i / fps)
            wallSitFrame(knee = drifted)
        }
    }

    @Test
    fun `a slow sag raises a drift warning`() {
        val reference = reference()
        val sigma = reference.features.getValue(FeatureId.KNEE_FLEX_ACTIVE).sigma
        val tracker = HoldTracker(reference)

        val updates = drive(tracker, steadyRecording(seconds = 2.0) + sagging(0.2, seconds = 12.0, sigma = sigma))
        val warned = updates.last { it.worstDriftPerSecond != null }

        assertTrue("0.2 sigma/s is past the 0.15 announce threshold", warned.driftWarning)
        assertEquals(FeatureId.KNEE_FLEX_ACTIVE, warned.driftFeature)
    }

    @Test
    fun `a hold that is not drifting raises no warning`() {
        val tracker = HoldTracker(reference())
        val updates = drive(tracker, steadyRecording(seconds = 10.0))

        assertFalse(updates.last().driftWarning)
    }

    @Test
    fun `drift past the end threshold ends the hold`() {
        // §6.19: "above 0.3 sigma/s, end the hold". This is hold mode's §6.18.6.
        val reference = reference()
        val sigma = reference.features.getValue(FeatureId.KNEE_FLEX_ACTIVE).sigma
        val tracker = HoldTracker(reference)

        val updates = drive(tracker, steadyRecording(seconds = 2.0) + sagging(0.5, seconds = 16.0, sigma = sigma))

        assertEquals(HoldState.BROKEN, updates.last().state)
    }

    @Test
    fun `the frame score is sampled at 1 Hz for the change-point machinery`() {
        // §6.19 runs §6.18's CUSUM over FrameScore at 1 Hz "giving your form started slipping at
        // 0:38" - so the series has to be in seconds, not frames.
        val tracker = HoldTracker(reference())
        val updates = drive(tracker, steadyRecording(seconds = 10.0))
        val samples = updates.last().qualitySamples

        assertTrue("about one sample a second, not thirty: ${samples.size}", samples.size in 8..12)
        assertTrue(samples.all { it in 0.0..100.0 })
    }

    @Test
    fun `an abstaining joint neither scores nor breaks the hold`() {
        // §6.6 again: a feature the landmarks could not support has no opinion, and a hold must not
        // end because the camera briefly lost a knee.
        val tracker = HoldTracker(reference())
        val updates = drive(tracker, List((5.0 * fps).toInt()) { emptyMap<FeatureId, Float>() })

        // Still waiting to see the position, which is the honest state when nothing is measurable -
        // and emphatically not BROKEN, which is what a hold must never become for want of a camera.
        assertEquals(HoldState.SETTLING, updates.last().state)
        assertTrue(updates.none { it.brokeThisFrame })
        assertNull("nothing was scored, so there is no quality", updates.last().holdQuality)
    }

    @Test
    fun `the drift fit is sigma per second, not per frame`() {
        val points = (0..60).map { (it / 30.0) to (it / 30.0) * 0.25 }

        assertEquals(0.25, leastSquaresSlopeOverTime(points)!!, 1e-9)
        assertNull("a fit needs the times to vary", leastSquaresSlopeOverTime(listOf(1.0 to 2.0)))
    }
}
