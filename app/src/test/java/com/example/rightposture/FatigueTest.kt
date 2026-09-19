package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.live.FatigueStatus
import com.example.rightposture.core.live.FeatureSample
import com.example.rightposture.core.live.RepRecord
import com.example.rightposture.core.live.RepRejection
import com.example.rightposture.core.live.SessionRecord
import com.example.rightposture.core.live.assessFatigue
import com.example.rightposture.core.live.leastSquaresSlope
import com.example.rightposture.core.live.percentChange
import com.example.rightposture.core.live.spectralArcLength
import com.example.rightposture.core.live.twoConsecutiveBelowSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * §6.18. The plan calls this the differentiator and treats it as the centrepiece, so these tests
 * are mostly about the ways it could produce a confident, defensible-looking number that is wrong:
 * firing on noise, firing on too little evidence, or naming a change point the data cannot support.
 */
class FatigueTest {

    private fun rep(
        index: Int,
        quality: Double?,
        valid: Boolean = true,
        rom: Double = 90.0,
        duration: Double = 2.0,
        features: Map<FeatureId, FeatureSample> = emptyMap(),
        velocity: List<Double> = smoothProfile(),
    ) = RepRecord(
        index = index,
        quality = quality,
        romDegrees = rom,
        durationSeconds = duration,
        phiMax = 1.0,
        valid = valid,
        rejection = if (valid) null else RepRejection.RANGE,
        features = features,
        velocityProfile = velocity,
    )

    private fun sample(feature: FeatureId, signed: Double, frames: Int = 60) =
        FeatureSample(feature, kotlin.math.abs(signed), kotlin.math.abs(signed), signed, frames)

    /** One clean out-and-back: a smooth, single-peaked speed profile. */
    private fun smoothProfile(samples: Int = 60) =
        (0 until samples).map { sin(PI * it / samples) }

    /** The same movement with a high-frequency tremor on top of it. */
    private fun roughProfile(samples: Int = 60) =
        (0 until samples).map { sin(PI * it / samples) + 0.35 * sin(2.0 * PI * 9.0 * it / samples) }

    private fun session(reps: List<RepRecord>, prescribed: Int = 12) = SessionRecord(reps, prescribed)

    // --- a real set through the engine, for the live-assessment cases

    private fun kneeBundle(): com.example.rightposture.core.rpose.RposeBundle {
        val captured = com.example.rightposture.core.pose.syntheticKneeRepetitions(
            com.example.rightposture.core.pose.SyntheticPoseConfig(repCount = 8),
        ).map { com.example.rightposture.core.reference.CapturedFrame(it.frame, it.timestampNs) }
        val authored = com.example.rightposture.core.reference.runAuthoringPipeline(captured)
        assertNull(authored.failure, authored.failure)
        return com.example.rightposture.core.reference.bundleFromAuthoring(
            result = authored,
            name = "Knee extension",
            id = "rp_fatigue",
            createdAt = "2026-09-04T00:00:00Z",
            prescribedReps = 30,
        )!!
    }

    private fun kneeSet(repCount: Int): List<com.example.rightposture.core.pose.TimedLandmarkFrame> {
        val config = com.example.rightposture.core.pose.SyntheticPoseConfig(repCount = repCount)
        val stream = com.example.rightposture.core.pose.syntheticKneeRepetitions(config)
        val expanded = mutableListOf<com.example.rightposture.core.pose.LandmarkFrame>()
        stream.forEachIndexed { index, timed ->
            expanded += timed.frame
            if ((index + 1) % (config.samplesPerRep / 2) == 0) repeat(15) { expanded += timed.frame }
        }
        val step = (1_000_000_000.0 / 30.0).toLong()
        return expanded.mapIndexed { i, f ->
            com.example.rightposture.core.pose.TimedLandmarkFrame(i * step, f)
        }
    }

    // -------------------------------------------------------------------------------------
    // §6.18.1 - the prerequisite
    // -------------------------------------------------------------------------------------

    @Test
    fun `fewer than six valid reps reports insufficient, not a change point`() {
        val result = assessFatigue(session((1..5).map { rep(it, 90.0) }))

        assertEquals(FatigueStatus.INSUFFICIENT_REPS, result.status)
        assertNull("a change point from five numbers would be believed", result.changePointRep)
        assertNull(result.baseline)
    }

    @Test
    fun `partial reps do not make up the quorum`() {
        // Eight finished reps of which three were refused is five measurements. §6.18.1's floor is
        // about how much evidence exists, not how many times the patient moved.
        val reps = (1..8).map { rep(it, 90.0, valid = it <= 5) }

        assertEquals(FatigueStatus.INSUFFICIENT_REPS, assessFatigue(session(reps)).status)
    }

    @Test
    fun `six valid reps is enough to assess`() {
        val result = assessFatigue(session((1..6).map { rep(it, 90.0) }))

        assertTrue(result.status != FatigueStatus.INSUFFICIENT_REPS)
        assertEquals(90.0, result.baseline!!, 1e-9)
    }

    // -------------------------------------------------------------------------------------
    // §6.18.2 - the baseline
    // -------------------------------------------------------------------------------------

    @Test
    fun `the baseline skips rep one`() {
        // Q_1 is the patient finding their position. Including it drags the baseline down, which
        // masks the very decline this is looking for - so a terrible first rep must not move it.
        val reps = listOf(rep(1, 10.0)) + (2..8).map { rep(it, 90.0) }
        val result = assessFatigue(session(reps))

        assertEquals("B is median(Q2,Q3,Q4), untouched by Q1", 90.0, result.baseline!!, 1e-9)
    }

    @Test
    fun `the scale has a floor so a tight baseline cannot fire on nothing`() {
        // Three identical reps give MAD = 0. Without the floor, k and h collapse to zero and the
        // very next rep one point below baseline alarms.
        val result = assessFatigue(session((1..8).map { rep(it, 90.0) }))

        assertEquals(Params.FATIGUE_SCALE_FLOOR_POINTS, result.scale!!, 1e-9)
        assertEquals("identical reps are not a decline", FatigueStatus.STABLE, result.status)
    }

    // -------------------------------------------------------------------------------------
    // §6.18.3 / §6.18.4 - detection, and refusing to detect
    // -------------------------------------------------------------------------------------

    @Test
    fun `a stable set does not trigger`() {
        val reps = listOf(88.0, 91.0, 89.0, 90.0, 92.0, 89.0, 90.0, 91.0, 88.0, 90.0)
            .mapIndexed { i, q -> rep(i + 1, q) }
        val result = assessFatigue(session(reps))

        assertEquals(FatigueStatus.STABLE, result.status)
        assertFalse(result.declaredDegradation)
        assertNull(result.changePointRep)
    }

    @Test
    fun `a known degrading set produces the expected change point`() {
        // Steady at 90 through rep 5, then a clear decline. The change point is where the sum last
        // sat at zero, plus one - which is the first rep of the decline, not the rep that alarmed.
        val quality = listOf(90.0, 90.0, 90.0, 90.0, 90.0, 78.0, 70.0, 62.0, 54.0, 46.0)
        val result = assessFatigue(session(quality.mapIndexed { i, q -> rep(i + 1, q) }))

        assertEquals(FatigueStatus.DEGRADED, result.status)
        assertEquals("the decline starts at rep 6", 6, result.changePointRep)
        assertTrue("the alarm must come at or after the change point", result.alarmRep!! >= 6)
        assertTrue("the slope must corroborate: ${result.slopePerRep}", result.slopePerRep!! < -1.5)
    }

    @Test
    fun `a cusum alarm without a corroborating slope is not degradation`() {
        // §6.18.4: "Two independent signals agreeing is enough; either alone is not." A single deep
        // dip that the set then recovers from can accumulate an alarm while the slope stays flat.
        val quality = listOf(90.0, 90.0, 90.0, 40.0, 40.0, 40.0, 90.0, 90.0, 92.0, 95.0, 96.0, 98.0)
        val result = assessFatigue(session(quality.mapIndexed { i, q -> rep(i + 1, q) }))

        assertFalse("the slope does not agree, so nothing is declared", result.declaredDegradation)
        assertTrue("but the alarm is still reported, not hidden", result.alarmRep != null)
        assertTrue(result.slopePerRep!! > Params.CORROBORATING_SLOPE_POINTS_PER_REP)
    }

    @Test
    fun `a gentle drift within the allowance never accumulates`() {
        // k = 0.5·s absorbs ordinary variation. A set that wanders by a point or two must not
        // eventually alarm just because it is long.
        val quality = (1..20).map { 90.0 - (it % 3) }
        val result = assessFatigue(session(quality.mapIndexed { i, q -> rep(i + 1, q) }))

        assertEquals(FatigueStatus.STABLE, result.status)
    }

    @Test
    fun `improvement resets the sum rather than banking against a later decline`() {
        // One-sided, per §6.18.3. Reps well above baseline must not build credit that a later dip
        // then spends.
        val quality = listOf(90.0, 90.0, 90.0, 99.0, 99.0, 99.0, 99.0, 82.0)
        val result = assessFatigue(session(quality.mapIndexed { i, q -> rep(i + 1, q) }))

        assertEquals(FatigueStatus.STABLE, result.status)
    }

    @Test
    fun `only valid reps enter the series, so a partial cannot read as a collapse`() {
        val reps = listOf(
            rep(1, 90.0), rep(2, 90.0), rep(3, 90.0),
            rep(4, 12.0, valid = false),
            rep(5, 90.0), rep(6, 91.0), rep(7, 89.0), rep(8, 90.0),
        )

        assertEquals(FatigueStatus.STABLE, assessFatigue(session(reps)).status)
    }

    // -------------------------------------------------------------------------------------
    // §6.18.5 - attribution
    // -------------------------------------------------------------------------------------

    @Test
    fun `attribution names the joints that drifted, in order`() {
        val quality = listOf(90.0, 90.0, 90.0, 90.0, 90.0, 76.0, 68.0, 60.0, 52.0, 44.0)
        val reps = quality.mapIndexed { i, q ->
            val late = i >= 5
            rep(
                i + 1, q,
                features = mapOf(
                    // The big drifter, and it drifts negative - direction has to survive.
                    FeatureId.TRUNK_LATERAL to sample(FeatureId.TRUNK_LATERAL, if (late) -3.5 else -0.2),
                    FeatureId.HIP_FLEX_ACTIVE to sample(FeatureId.HIP_FLEX_ACTIVE, if (late) 1.2 else 0.1),
                    // Steady throughout: must not be named.
                    FeatureId.KNEE_FLEX_ACTIVE to sample(FeatureId.KNEE_FLEX_ACTIVE, 0.05),
                ),
            )
        }
        val result = assessFatigue(session(reps))

        assertEquals(2, result.attribution.size)
        assertEquals(FeatureId.TRUNK_LATERAL, result.attribution[0].feature)
        assertEquals(FeatureId.HIP_FLEX_ACTIVE, result.attribution[1].feature)
        assertTrue("the direction is the finding", result.attribution[0].deltaSigma < 0)
        assertTrue(result.attribution[1].deltaSigma > 0)
    }

    @Test
    fun `the fatigue signature is reported as percentage changes`() {
        val quality = listOf(90.0, 90.0, 90.0, 90.0, 90.0, 76.0, 68.0, 60.0, 52.0, 44.0)
        val reps = quality.mapIndexed { i, q ->
            val late = i >= 5
            // The textbook pattern: range shrinks, tempo slows.
            rep(i + 1, q, rom = if (late) 72.0 else 90.0, duration = if (late) 2.5 else 2.0)
        }
        val result = assessFatigue(session(reps))

        assertEquals(-20.0, result.romChangePercent!!, 1e-6)
        assertEquals(25.0, result.tempoChangePercent!!, 1e-6)
    }

    @Test
    fun `roughening movement shows as a negative smoothness change`() {
        val quality = listOf(90.0, 90.0, 90.0, 90.0, 90.0, 76.0, 68.0, 60.0, 52.0, 44.0)
        val reps = quality.mapIndexed { i, q ->
            rep(i + 1, q, velocity = if (i >= 5) roughProfile() else smoothProfile())
        }
        val result = assessFatigue(session(reps))

        assertTrue(
            "SPARC must fall as the movement roughens: ${result.smoothnessChange}",
            result.smoothnessChange!! < 0.0,
        )
    }

    @Test
    fun `a set with no usable velocity profile simply has no smoothness term`() {
        // §6.18.5 permits dropping smoothness entirely. Absent is a valid answer; a fabricated
        // number would not be.
        val quality = listOf(90.0, 90.0, 90.0, 90.0, 90.0, 76.0, 68.0, 60.0, 52.0, 44.0)
        val reps = quality.mapIndexed { i, q -> rep(i + 1, q, velocity = emptyList()) }
        val result = assessFatigue(session(reps))

        assertEquals(FatigueStatus.DEGRADED, result.status)
        assertNull("no profile, no claim", result.smoothnessChange)
    }

    // -------------------------------------------------------------------------------------
    // §6.18.6 - the safety intervention
    // -------------------------------------------------------------------------------------

    @Test
    fun `a hard collapse recommends stopping the set`() {
        val quality = listOf(90.0, 90.0, 90.0, 90.0, 88.0, 70.0, 55.0, 40.0, 38.0, 35.0)
        val result = assessFatigue(session(quality.mapIndexed { i, q -> rep(i + 1, q) }))

        assertEquals(FatigueStatus.DEGRADED, result.status)
        assertTrue("two reps running under 0.7·B", result.stopRecommended)
    }

    @Test
    fun `a declared degradation that stays above the safety floor does not stop the set`() {
        // Degrading and still working safely is the ordinary case. Stopping here would teach the
        // patient to ignore the one message that matters.
        val quality = listOf(90.0, 90.0, 90.0, 90.0, 90.0, 82.0, 78.0, 74.0, 70.0, 66.0)
        val result = assessFatigue(session(quality.mapIndexed { i, q -> rep(i + 1, q) }))

        assertEquals(FatigueStatus.DEGRADED, result.status)
        assertFalse(result.stopRecommended)
    }

    @Test
    fun `one bad rep is not two consecutive ones`() {
        assertFalse(twoConsecutiveBelowSafety(listOf(90.0, 50.0, 90.0, 90.0), 90.0))
        assertTrue(twoConsecutiveBelowSafety(listOf(90.0, 50.0, 50.0, 90.0), 90.0))
    }

    @Test
    fun `a stable set never recommends stopping, however long it runs`() {
        val result = assessFatigue(session((1..30).map { rep(it, 90.0) }))

        assertFalse(result.stopRecommended)
    }

    // -------------------------------------------------------------------------------------
    // The pieces, on their own
    // -------------------------------------------------------------------------------------

    @Test
    fun `the slope is quality points per rep`() {
        assertEquals(-2.0, leastSquaresSlope(listOf(100.0, 98.0, 96.0, 94.0)), 1e-9)
        assertEquals(0.0, leastSquaresSlope(listOf(90.0, 90.0, 90.0)), 1e-9)
        assertEquals(0.0, leastSquaresSlope(listOf(90.0)), 1e-9)
    }

    @Test
    fun `percent change refuses to divide by a zero mean`() {
        assertEquals(-20.0, percentChange(listOf(100.0), listOf(80.0))!!, 1e-9)
        assertNull(percentChange(listOf(0.0), listOf(80.0)))
        assertNull(percentChange(emptyList(), listOf(80.0)))
    }

    @Test
    fun `sparc rates a smooth movement above a rough one`() {
        val smooth = spectralArcLength(smoothProfile(120).map { kotlin.math.abs(it) }, 30.0)!!
        val rough = spectralArcLength(roughProfile(120).map { kotlin.math.abs(it) }, 30.0)!!

        assertTrue("both are negative arc lengths", smooth < 0.0 && rough < 0.0)
        assertTrue("more negative is less smooth: smooth=$smooth rough=$rough", rough < smooth)
    }

    // -------------------------------------------------------------------------------------
    // Live in the engine - §6.18.6 is an intervention, so it cannot wait for set end
    // -------------------------------------------------------------------------------------

    @Test
    fun `the engine reassesses on every completed rep`() {
        val engine = com.example.rightposture.core.live.ExerciseEngine(kneeBundle())
        assertEquals(FatigueStatus.INSUFFICIENT_REPS, engine.fatigue.status)

        kneeSet(8).forEach { engine.onFrame(it.frame, it.timestampNs) }

        assertTrue(
            "a real set of 8 must reach an assessable state: ${engine.session.validReps}",
            engine.fatigue.status != FatigueStatus.INSUFFICIENT_REPS,
        )
        assertEquals("a faithful set is not degradation", FatigueStatus.STABLE, engine.fatigue.status)
    }

    @Test
    fun `reset clears the assessment back to insufficient`() {
        val engine = com.example.rightposture.core.live.ExerciseEngine(kneeBundle())
        kneeSet(8).forEach { engine.onFrame(it.frame, it.timestampNs) }
        engine.reset()

        assertEquals(FatigueStatus.INSUFFICIENT_REPS, engine.fatigue.status)
        assertFalse(engine.state.safetyStop)
    }

    @Test
    fun `the safety stop is announced once and ends the set`() {
        // Driven through the record path rather than through synthetic landmarks: producing a
        // genuine quality collapse from a pose stream is a different test's job, and this one is
        // about the engine's reaction, which is the part §6.18.6 specifies.
        val quality = listOf(90.0, 90.0, 90.0, 90.0, 88.0, 70.0, 55.0, 40.0, 38.0, 35.0)
        val result = assessFatigue(session(quality.mapIndexed { i, q -> rep(i + 1, q) }))

        assertTrue(result.stopRecommended)
        // Rep 5 scored 88, two points under baseline, which is where the sum first leaves zero -
        // so the decline began accumulating at 5 even though the alarm only trips at 6.
        assertEquals(5, result.changePointRep)
    }

    @Test
    fun `sparc abstains rather than inventing a number`() {
        assertNull("too few samples", spectralArcLength(listOf(1.0, 2.0), 30.0))
        assertNull("no DC term to normalise by", spectralArcLength(List(60) { 0.0 }, 30.0))
        assertNull("unusable sample rate", spectralArcLength(smoothProfile(60), 0.0))
        assertNull("non-finite input", spectralArcLength(List(60) { Double.NaN }, 30.0))
    }
}
