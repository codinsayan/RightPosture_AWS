package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.pose.BodyFrameResult
import com.example.rightposture.core.pose.BodyFrameStatus
import com.example.rightposture.core.pose.FeatureContext
import com.example.rightposture.core.pose.FeatureKind
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.pose.ValidityTracker
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.build
import com.example.rightposture.core.pose.contributingLandmarks
import com.example.rightposture.core.pose.kind
import com.example.rightposture.core.pose.renormaliseWeights
import com.example.rightposture.core.pose.repIsScorable
import com.example.rightposture.core.pose.validFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureValidityTest {
    private val context = FeatureContext(Side.RIGHT, Vec3(0f, 1f, 0f))

    // --- Rule 1: confidence ---

    @Test
    fun lowVisibilityOnOneLandmarkInvalidatesOnlyItsFeatures() {
        val features = validFeatures(
            standing(lowVisibility = setOf(PoseLandmark.RIGHT_FOOT_INDEX)),
            freshFrame(),
            context,
        )

        assertFalse(features.containsKey(FeatureId.ANKLE_DORSIFLEX_ACTIVE))
        assertTrue(features.containsKey(FeatureId.KNEE_FLEX_ACTIVE))
        assertTrue(features.containsKey(FeatureId.TRUNK_FLEXION))
    }

    @Test
    fun lowPresenceInvalidatesJustLikeLowVisibility() {
        val byVisibility = validFeatures(
            standing(lowVisibility = setOf(PoseLandmark.RIGHT_WRIST)),
            freshFrame(),
            context,
        )
        val byPresence = validFeatures(
            standing(lowPresence = setOf(PoseLandmark.RIGHT_WRIST)),
            freshFrame(),
            context,
        )

        assertFalse(byVisibility.containsKey(FeatureId.ELBOW_FLEX_ACTIVE))
        assertEquals(byVisibility.keys, byPresence.keys)
    }

    @Test
    fun confidenceExactlyAtTheThresholdIsAccepted() {
        val features = validFeatures(standing(confidence = 0.6f), freshFrame(), context)

        assertEquals(FeatureId.entries.toSet(), features.keys)
    }

    @Test
    fun losingTheHipsInvalidatesEveryFeatureThatUsesThem() {
        val features = validFeatures(
            standing(lowVisibility = setOf(PoseLandmark.LEFT_HIP)),
            freshFrame(),
            context,
        )

        assertFalse(features.containsKey(FeatureId.PELVIC_OBLIQUITY))
        assertFalse(features.containsKey(FeatureId.TRUNK_ROTATION))
        assertTrue(features.containsKey(FeatureId.KNEE_FLEX_ACTIVE))
    }

    // --- Rule 2: vector magnitude ---

    @Test
    fun limbShorterThanOneMillimetreIsInvalid() {
        val collapsed = standing().mutate(PoseLandmark.RIGHT_ANKLE to Vec3(0.15f, -0.4005f, 0f))

        val features = validFeatures(collapsed, freshFrame(), context)

        assertFalse(features.containsKey(FeatureId.KNEE_FLEX_ACTIVE))
        assertTrue(features.containsKey(FeatureId.HIP_FLEX_ACTIVE))
    }

    @Test
    fun oneMillimetreLimbIsStillTooShortForTheOldNumericalFloor() {
        // 1e-3 m clears the 1e-6 guard inside the angle primitives, so section 6.6 has to catch it.
        val shank = standing().mutate(PoseLandmark.RIGHT_ANKLE to Vec3(0.15f, -0.40005f, 0f))

        assertFalse(
            validFeatures(shank, freshFrame(), context).containsKey(FeatureId.KNEE_FLEX_ACTIVE),
        )
    }

    // --- Rule 3: declared range ---

    @Test
    fun everyFeatureHasADeclaredKind() {
        val unsigned = FeatureId.entries.filter { it.kind == FeatureKind.UNSIGNED_ANGLE }
        val signed = FeatureId.entries.filter { it.kind == FeatureKind.SIGNED_PLANAR }

        assertEquals(8, unsigned.size)
        assertEquals(6, signed.size)
        assertEquals(FeatureKind.RATIO, FeatureId.SHOULDER_ELEVATION.kind)
        assertEquals(15, unsigned.size + signed.size + 1)
    }

    @Test
    fun everyProducedValueSitsInsideItsDeclaredRange() {
        validFeatures(standing(), freshFrame(), context).forEach { (id, value) ->
            val kind = id.kind
            assertTrue("$id = $value below range", kind.min == null || value >= kind.min!!)
            assertTrue("$id = $value above range", kind.max == null || value <= kind.max!!)
        }
    }

    // --- Rule 4: body frame ---

    @Test
    fun invalidBodyFrameInvalidatesEverything() {
        val features = validFeatures(
            standing(),
            BodyFrameResult(null, BodyFrameStatus.INVALID),
            context,
        )

        assertTrue(features.isEmpty())
    }

    @Test
    fun reusedBodyFrameStillProducesFeatures() {
        val reused = BodyFrameResult(build(standing()), BodyFrameStatus.REUSED)

        assertTrue(validFeatures(standing(), reused, context).isNotEmpty())
    }

    // --- Recovery hold ---

    @Test
    fun featureIsWithheldUntilContinuouslyValidForThreeHundredMillis() {
        val tracker = ValidityTracker()
        val good = mapOf(FeatureId.KNEE_FLEX_ACTIVE to 90f)

        assertFalse(tracker.update(good, ms(0)).containsKey(FeatureId.KNEE_FLEX_ACTIVE))
        assertFalse(tracker.update(good, ms(299)).containsKey(FeatureId.KNEE_FLEX_ACTIVE))
        assertTrue(tracker.update(good, ms(300)).containsKey(FeatureId.KNEE_FLEX_ACTIVE))
    }

    @Test
    fun flickeringLandmarkNeverEscapesTheRecoveryHold() {
        val tracker = ValidityTracker()
        val good = mapOf(FeatureId.KNEE_FLEX_ACTIVE to 90f)
        var emitted = 0

        // Valid for 200 ms, one bad frame, repeat. Never 300 ms of continuous validity.
        for (cycle in 0 until 10) {
            val base = cycle * 250L
            if (tracker.update(good, ms(base)).isNotEmpty()) emitted++
            if (tracker.update(good, ms(base + 200)).isNotEmpty()) emitted++
            tracker.update(emptyMap(), ms(base + 220))
        }

        assertEquals(0, emitted)
    }

    @Test
    fun recoveryClockRestartsAfterEachDropout() {
        val tracker = ValidityTracker()
        val good = mapOf(FeatureId.KNEE_FLEX_ACTIVE to 90f)

        tracker.update(good, ms(0))
        assertTrue(tracker.update(good, ms(400)).isNotEmpty())
        tracker.update(emptyMap(), ms(500))
        assertTrue(tracker.update(good, ms(600)).isEmpty())
        assertTrue(tracker.update(good, ms(900)).isNotEmpty())
    }

    @Test
    fun warnsOnceAWatchedFeatureHasBeenOutForHalfASecond() {
        val tracker = ValidityTracker()
        val watched = listOf(FeatureId.KNEE_FLEX_ACTIVE)
        tracker.update(mapOf(FeatureId.KNEE_FLEX_ACTIVE to 90f), ms(0))

        tracker.update(emptyMap(), ms(1000))

        assertFalse(tracker.shouldWarn(watched, ms(1400)))
        assertTrue(tracker.shouldWarn(watched, ms(1600)))
        assertFalse(tracker.shouldGoIdle(FeatureId.KNEE_FLEX_ACTIVE, ms(1600)))
        assertTrue(tracker.shouldGoIdle(FeatureId.KNEE_FLEX_ACTIVE, ms(2600)))
    }

    @Test
    fun aValidFeatureIsNeverReportedAsInvalid() {
        val tracker = ValidityTracker()

        tracker.update(mapOf(FeatureId.KNEE_FLEX_ACTIVE to 90f), ms(0))

        assertEquals(0.0, tracker.invalidForSeconds(FeatureId.KNEE_FLEX_ACTIVE, ms(5000)), 0.0)
    }

    // --- Downstream contracts ---

    @Test
    fun weightsRenormaliseToOneWhenAFeatureDropsOut() {
        val weights = mapOf(
            FeatureId.KNEE_FLEX_ACTIVE to 0.5f,
            FeatureId.HIP_FLEX_ACTIVE to 0.3f,
            FeatureId.TRUNK_FLEXION to 0.2f,
        )

        val kept = renormaliseWeights(weights, setOf(FeatureId.KNEE_FLEX_ACTIVE, FeatureId.HIP_FLEX_ACTIVE))

        assertEquals(1f, kept.values.sum(), 0.0001f)
        assertEquals(0.625f, kept.getValue(FeatureId.KNEE_FLEX_ACTIVE), 0.0001f)
        assertEquals(0.375f, kept.getValue(FeatureId.HIP_FLEX_ACTIVE), 0.0001f)
        assertFalse(kept.containsKey(FeatureId.TRUNK_FLEXION))
    }

    @Test
    fun renormalisingWithNothingValidYieldsNoScoreRatherThanZero() {
        val weights = mapOf(FeatureId.KNEE_FLEX_ACTIVE to 1f)

        assertTrue(renormaliseWeights(weights, emptySet()).isEmpty())
    }

    @Test
    fun repIsUnscoredOnceTheDominantFeatureMissesMoreThanThirtyPercent() {
        assertTrue(repIsScorable(dominantValidFrames = 70, totalFrames = 100))
        assertFalse(repIsScorable(dominantValidFrames = 69, totalFrames = 100))
        assertTrue(repIsScorable(dominantValidFrames = 100, totalFrames = 100))
        assertFalse(repIsScorable(dominantValidFrames = 0, totalFrames = 100))
        assertFalse(repIsScorable(dominantValidFrames = 0, totalFrames = 0))
    }

    // --- Contributing landmark table ---

    @Test
    fun contributingLandmarksFollowTheActiveSide() {
        val right = contributingLandmarks(FeatureId.KNEE_FLEX_ACTIVE, Side.RIGHT)
        val left = contributingLandmarks(FeatureId.KNEE_FLEX_ACTIVE, Side.LEFT)

        assertEquals(listOf(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE), right)
        assertEquals(listOf(PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE), left)
        assertEquals(left, contributingLandmarks(FeatureId.KNEE_FLEX_PASSIVE, Side.RIGHT))
    }

    @Test
    fun everyFeatureNamesAtLeastTwoLandmarks() {
        FeatureId.entries.forEach { id ->
            val landmarks = contributingLandmarks(id, Side.RIGHT)
            assertTrue("$id names ${landmarks.size}", landmarks.size >= 2)
            assertEquals("$id repeats a landmark", landmarks.size, landmarks.toSet().size)
        }
    }

    // --- Helpers ---

    private fun ms(value: Long) = value * 1_000_000L

    private fun freshFrame() = BodyFrameResult(build(standing()), BodyFrameStatus.FRESH)

    private fun LandmarkFrame.mutate(vararg edits: Pair<Int, Vec3>): LandmarkFrame {
        val updated = worldLandmarks.toMutableList()
        edits.forEach { (index, value) -> updated[index] = value }
        return copy(worldLandmarks = updated)
    }

    private fun standing(
        confidence: Float = 0.9f,
        lowVisibility: Set<Int> = emptySet(),
        lowPresence: Set<Int> = emptySet(),
    ): LandmarkFrame {
        val points = mapOf(
            PoseLandmark.LEFT_SHOULDER to Vec3(-0.2f, 0.5f, 0f),
            PoseLandmark.RIGHT_SHOULDER to Vec3(0.2f, 0.5f, 0f),
            PoseLandmark.LEFT_ELBOW to Vec3(-0.2f, 0.2f, 0f),
            PoseLandmark.RIGHT_ELBOW to Vec3(0.2f, 0.2f, 0f),
            PoseLandmark.LEFT_WRIST to Vec3(-0.2f, -0.05f, 0f),
            PoseLandmark.RIGHT_WRIST to Vec3(0.2f, -0.05f, 0f),
            PoseLandmark.LEFT_HIP to Vec3(-0.15f, 0f, 0f),
            PoseLandmark.RIGHT_HIP to Vec3(0.15f, 0f, 0f),
            PoseLandmark.LEFT_KNEE to Vec3(-0.15f, -0.4f, 0f),
            PoseLandmark.RIGHT_KNEE to Vec3(0.15f, -0.4f, 0f),
            PoseLandmark.LEFT_ANKLE to Vec3(-0.15f, -0.8f, 0f),
            PoseLandmark.RIGHT_ANKLE to Vec3(0.15f, -0.8f, 0f),
            PoseLandmark.LEFT_FOOT_INDEX to Vec3(-0.15f, -0.85f, 0.15f),
            PoseLandmark.RIGHT_FOOT_INDEX to Vec3(0.15f, -0.85f, 0.15f),
        )
        return LandmarkFrame(
            worldLandmarks = List(POSE_LANDMARK_COUNT) { points[it] ?: Vec3(0f, 0f, 0f) },
            imageLandmarks = List(POSE_LANDMARK_COUNT) { Vec3(0f, 0f, 0f) },
            visibility = List(POSE_LANDMARK_COUNT) { if (it in lowVisibility) 0.59f else confidence },
            presence = List(POSE_LANDMARK_COUNT) { if (it in lowPresence) 0.59f else confidence },
        )
    }
}
