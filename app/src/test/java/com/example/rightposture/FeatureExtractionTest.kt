package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.pose.BodyFrame
import com.example.rightposture.core.pose.FeatureContext
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.build
import com.example.rightposture.core.pose.extractFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic stationary poses. Every landmark is placed by hand so the expected angle is known from
 * the geometry, not from running the code and recording what it printed.
 *
 * World layout: +x is the subject's right, +y is up, +z is toward the camera.
 */
class FeatureExtractionTest {
    private val vertical = Vec3(0f, 1f, 0f)

    @Test
    fun standingPoseProducesEveryFeature() {
        val features = extract(standing())

        assertEquals(FeatureId.entries.toSet(), features.keys)
    }

    @Test
    fun straightLegReadsAsFullKneeExtension() {
        val features = extract(standing())

        assertEquals(180f, features.getValue(FeatureId.KNEE_FLEX_ACTIVE), 0.01f)
        assertEquals(180f, features.getValue(FeatureId.KNEE_FLEX_PASSIVE), 0.01f)
    }

    @Test
    fun bentKneeReadsAsNinetyDegrees() {
        // Shank swung forward so thigh and shank meet at a right angle.
        val pose = standing().mutate(
            PoseLandmark.RIGHT_ANKLE to Vec3(0.15f, -0.4f, 0.4f),
        )

        assertEquals(90f, extract(pose).getValue(FeatureId.KNEE_FLEX_ACTIVE), 0.01f)
    }

    @Test
    fun kneeFlexionIsUnsignedSoItDoesNotDependOnBendDirection() {
        val forward = standing().mutate(PoseLandmark.RIGHT_ANKLE to Vec3(0.15f, -0.4f, 0.4f))
        val backward = standing().mutate(PoseLandmark.RIGHT_ANKLE to Vec3(0.15f, -0.4f, -0.4f))

        assertEquals(
            extract(forward).getValue(FeatureId.KNEE_FLEX_ACTIVE),
            extract(backward).getValue(FeatureId.KNEE_FLEX_ACTIVE),
            0.01f,
        )
    }

    @Test
    fun raisingTheArmSidewaysOpensShoulderFlexion() {
        val down = extract(standing()).getValue(FeatureId.SHOULDER_FLEX_ACTIVE)
        val out = extract(
            standing().mutate(
                PoseLandmark.RIGHT_ELBOW to Vec3(0.5f, 0.4f, 0f),
                PoseLandmark.RIGHT_WRIST to Vec3(0.75f, 0.4f, 0f),
            ),
        ).getValue(FeatureId.SHOULDER_FLEX_ACTIVE)

        assertTrue("arm out should open the hip-shoulder-elbow angle, was $down then $out", out > down)
    }

    @Test
    fun bentElbowReadsAsNinetyDegrees() {
        val pose = standing().mutate(
            PoseLandmark.RIGHT_ELBOW to Vec3(0.2f, 0.1f, 0f),
            PoseLandmark.RIGHT_WRIST to Vec3(0.2f, 0.1f, 0.3f),
        )

        assertEquals(90f, extract(pose).getValue(FeatureId.ELBOW_FLEX_ACTIVE), 0.01f)
    }

    @Test
    fun abductingTheLegIncreasesHipAbduction() {
        val neutral = extract(standing()).getValue(FeatureId.HIP_ABDUCTION_ACTIVE)
        val out = extract(
            standing().mutate(
                PoseLandmark.RIGHT_KNEE to Vec3(0.45f, -0.4f, 0f),
                PoseLandmark.RIGHT_ANKLE to Vec3(0.6f, -0.8f, 0f),
            ),
        ).getValue(FeatureId.HIP_ABDUCTION_ACTIVE)

        assertEquals(0f, neutral, 0.01f)
        assertTrue("leg out to the side should read further from zero, was $out", out > 10f)
    }

    @Test
    fun droppingOneHipShowsAsPelvicObliquityWithOppositeSigns() {
        val leftLow = standing().mutate(PoseLandmark.LEFT_HIP to Vec3(-0.15f, -0.05f, 0f))
        val rightLow = standing().mutate(PoseLandmark.RIGHT_HIP to Vec3(0.15f, -0.05f, 0f))

        val a = extract(leftLow).getValue(FeatureId.PELVIC_OBLIQUITY)
        val b = extract(rightLow).getValue(FeatureId.PELVIC_OBLIQUITY)

        assertEquals(0f, extract(standing()).getValue(FeatureId.PELVIC_OBLIQUITY), 0.01f)
        assertTrue("hip drop must be signed, got $a and $b", a * b < 0f)
        assertEquals(a, -b, 0.01f)
    }

    @Test
    fun rotatingTheShouldersShowsAsTrunkRotationWithOppositeSigns() {
        val oneWay = standing().mutate(
            PoseLandmark.LEFT_SHOULDER to Vec3(-0.2f, 0.5f, 0.1f),
            PoseLandmark.RIGHT_SHOULDER to Vec3(0.2f, 0.5f, -0.1f),
        )
        val other = standing().mutate(
            PoseLandmark.LEFT_SHOULDER to Vec3(-0.2f, 0.5f, -0.1f),
            PoseLandmark.RIGHT_SHOULDER to Vec3(0.2f, 0.5f, 0.1f),
        )

        val a = extract(oneWay).getValue(FeatureId.TRUNK_ROTATION)
        val b = extract(other).getValue(FeatureId.TRUNK_ROTATION)

        assertEquals(0f, extract(standing()).getValue(FeatureId.TRUNK_ROTATION), 0.01f)
        assertTrue("rotation must be signed, got $a and $b", a * b < 0f)
    }

    @Test
    fun shruggingRaisesShoulderElevation() {
        val neutral = extract(standing()).getValue(FeatureId.SHOULDER_ELEVATION)
        val shrugged = extract(
            standing().mutate(PoseLandmark.RIGHT_SHOULDER to Vec3(0.2f, 0.6f, 0f)),
        ).getValue(FeatureId.SHOULDER_ELEVATION)

        assertTrue("shrug should raise the ratio, was $neutral then $shrugged", shrugged > neutral)
    }

    @Test
    fun shoulderElevationIsBodySizeInvariant() {
        val small = extract(standing()).getValue(FeatureId.SHOULDER_ELEVATION)
        val large = extract(standing(scale = 1.8f)).getValue(FeatureId.SHOULDER_ELEVATION)

        assertEquals(small, large, 0.01f)
    }

    @Test
    fun leaningForwardIncreasesTrunkFlexion() {
        val upright = extract(standing()).getValue(FeatureId.TRUNK_FLEXION)
        val leaning = extract(
            standing().mutate(
                PoseLandmark.LEFT_SHOULDER to Vec3(-0.2f, 0.45f, 0.2f),
                PoseLandmark.RIGHT_SHOULDER to Vec3(0.2f, 0.45f, 0.2f),
            ),
        ).getValue(FeatureId.TRUNK_FLEXION)

        assertEquals(0f, upright, 0.01f)
        assertTrue("forward lean should raise trunk flexion, was $leaning", leaning > 10f)
    }

    @Test
    fun trunkFlexionIsUnsignedSoLeaningBackReadsTheSame() {
        val forward = extract(
            standing().mutate(
                PoseLandmark.LEFT_SHOULDER to Vec3(-0.2f, 0.45f, 0.2f),
                PoseLandmark.RIGHT_SHOULDER to Vec3(0.2f, 0.45f, 0.2f),
            ),
        ).getValue(FeatureId.TRUNK_FLEXION)
        val back = extract(
            standing().mutate(
                PoseLandmark.LEFT_SHOULDER to Vec3(-0.2f, 0.45f, -0.2f),
                PoseLandmark.RIGHT_SHOULDER to Vec3(0.2f, 0.45f, -0.2f),
            ),
        ).getValue(FeatureId.TRUNK_FLEXION)

        assertEquals(forward, back, 0.01f)
    }

    @Test
    fun gravityReferencedFeaturesAbstainWithoutAVertical() {
        val features = extract(standing(), vertical = null)

        assertFalse(features.containsKey(FeatureId.TRUNK_FLEXION))
        assertFalse(features.containsKey(FeatureId.TRUNK_LATERAL))
        // Everything body-intrinsic still reports, including 10, per section 6.5.4.
        assertTrue(features.containsKey(FeatureId.PELVIC_OBLIQUITY))
        assertTrue(features.containsKey(FeatureId.TRUNK_ROTATION))
        assertTrue(features.containsKey(FeatureId.KNEE_FLEX_ACTIVE))
    }

    @Test
    fun activeAndPassiveSwapWhenTheActiveSideSwaps() {
        // Only the right knee is bent, so the two sides must not read the same.
        val pose = standing().mutate(PoseLandmark.RIGHT_ANKLE to Vec3(0.15f, -0.4f, 0.4f))

        val rightActive = extract(pose, side = Side.RIGHT)
        val leftActive = extract(pose, side = Side.LEFT)

        assertEquals(
            rightActive.getValue(FeatureId.KNEE_FLEX_ACTIVE),
            leftActive.getValue(FeatureId.KNEE_FLEX_PASSIVE),
            0.01f,
        )
        assertEquals(
            rightActive.getValue(FeatureId.KNEE_FLEX_PASSIVE),
            leftActive.getValue(FeatureId.KNEE_FLEX_ACTIVE),
            0.01f,
        )
        assertEquals(90f, rightActive.getValue(FeatureId.KNEE_FLEX_ACTIVE), 0.01f)
    }

    @Test
    fun signedFeaturesFlipWhenTheActiveSideSwaps() {
        val pose = standing().mutate(PoseLandmark.LEFT_HIP to Vec3(-0.15f, -0.05f, 0f))

        val right = extract(pose, side = Side.RIGHT).getValue(FeatureId.PELVIC_OBLIQUITY)
        val left = extract(pose, side = Side.LEFT).getValue(FeatureId.PELVIC_OBLIQUITY)

        assertEquals(right, -left, 0.01f)
    }

    @Test
    fun featuresAreInvariantToWhereThePatientStandsAndWhichWayTheyFace() {
        val here = extract(standing().mutate(PoseLandmark.RIGHT_ANKLE to Vec3(0.15f, -0.4f, 0.4f)))
        val moved = extract(
            standing(offset = Vec3(3f, 0f, -2f))
                .mutate(PoseLandmark.RIGHT_ANKLE to Vec3(3.15f, -0.4f, -1.6f)),
        )

        assertEquals(
            here.getValue(FeatureId.KNEE_FLEX_ACTIVE),
            moved.getValue(FeatureId.KNEE_FLEX_ACTIVE),
            0.01f,
        )
    }

    @Test
    fun nonFiniteLandmarkDropsOnlyTheFeaturesThatUseIt() {
        val pose = standing().mutate(PoseLandmark.RIGHT_FOOT_INDEX to Vec3(Float.NaN, 0f, 0f))

        val features = extract(pose)

        assertFalse(features.containsKey(FeatureId.ANKLE_DORSIFLEX_ACTIVE))
        assertTrue(features.containsKey(FeatureId.KNEE_FLEX_ACTIVE))
    }

    @Test
    fun collapsedLimbDropsOnlyItsOwnFeature() {
        // Ankle sitting exactly on the knee gives a zero-length shank.
        val pose = standing().mutate(PoseLandmark.RIGHT_ANKLE to Vec3(0.15f, -0.4f, 0f))

        val features = extract(pose)

        assertFalse(features.containsKey(FeatureId.KNEE_FLEX_ACTIVE))
        assertTrue(features.containsKey(FeatureId.HIP_FLEX_ACTIVE))
    }

    private fun extract(
        pose: LandmarkFrame,
        side: Side = Side.RIGHT,
        vertical: Vec3? = this.vertical,
    ): Map<FeatureId, Float> {
        val basis: BodyFrame = build(pose)!!
        return extractFeatures(pose, basis, FeatureContext(side, vertical))
    }

    private fun LandmarkFrame.mutate(vararg edits: Pair<Int, Vec3>): LandmarkFrame {
        val updated = worldLandmarks.toMutableList()
        edits.forEach { (index, value) -> updated[index] = value }
        return copy(worldLandmarks = updated)
    }

    /** Symmetric upright subject, arms at the sides, feet flat. */
    private fun standing(scale: Float = 1f, offset: Vec3 = Vec3(0f, 0f, 0f)): LandmarkFrame {
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
            worldLandmarks = List(POSE_LANDMARK_COUNT) { index ->
                val p = points[index] ?: Vec3(0f, 0f, 0f)
                Vec3(p.x * scale + offset.x, p.y * scale + offset.y, p.z * scale + offset.z)
            },
            imageLandmarks = List(POSE_LANDMARK_COUNT) { Vec3(0f, 0f, 0f) },
            visibility = List(POSE_LANDMARK_COUNT) { 0.9f },
            presence = List(POSE_LANDMARK_COUNT) { 0.9f },
        )
    }
}
