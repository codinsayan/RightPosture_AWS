package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.pose.SyntheticPoseConfig
import com.example.rightposture.core.pose.syntheticKneeRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.bundleFromAuthoring
import com.example.rightposture.core.reference.deriveArrowSigns
import com.example.rightposture.core.reference.runAuthoringPipeline
import com.example.rightposture.core.pose.BodyFrameStatus
import com.example.rightposture.core.pose.BodyFrameTracker
import com.example.rightposture.core.pose.FeatureContext
import com.example.rightposture.core.pose.validFeatures
import com.example.rightposture.core.pose.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.16's rotation sign, measured instead of guessed.
 *
 * The device capture that prompted this recorded `shoulder_flex_active` on the front camera with
 * 593 of 595 usable frame pairs agreeing on -1, while the app was drawing +1 - every arrow exactly
 * backwards. These tests pin the property that made that measurable: the relationship between a
 * feature's angle and its segment's on-screen orientation is in the recording already.
 */
class ArrowSignDerivationTest {

    private fun captured(config: SyntheticPoseConfig = SyntheticPoseConfig(repCount = 8)) =
        syntheticKneeRepetitions(config).map { CapturedFrame(it.frame, it.timestampNs) }

    private fun featuresFor(frames: List<CapturedFrame>, side: Side): List<Map<FeatureId, Float>> {
        val tracker = BodyFrameTracker()
        return frames.map { (frame, timestampNs) ->
            val body = tracker.update(frame, timestampNs)
            if (body.status == BodyFrameStatus.INVALID) {
                emptyMap()
            } else {
                validFeatures(frame, body, FeatureContext(side, Vec3(0f, 1f, 0f)))
            }
        }
    }

    @Test
    fun `a moving joint yields a definite sign`() {
        val frames = captured()
        val signs = deriveArrowSigns(frames, featuresFor(frames, Side.RIGHT), Side.RIGHT)

        val knee = signs[FeatureId.KNEE_FLEX_ACTIVE]
        assertTrue("the exercised knee should produce a sign, got $signs", knee != null)
        assertTrue("a sign is +1 or -1, got $knee", knee == 1 || knee == -1)
    }

    @Test
    fun `the derived sign is stable across two recordings of the same movement`() {
        // If the derivation were noise-driven it would disagree with itself, and an arrow that
        // reverses between sessions is worse than no arrow.
        val a = captured(SyntheticPoseConfig(repCount = 6))
        val b = captured(SyntheticPoseConfig(repCount = 10))
        val signsA = deriveArrowSigns(a, featuresFor(a, Side.RIGHT), Side.RIGHT)
        val signsB = deriveArrowSigns(b, featuresFor(b, Side.RIGHT), Side.RIGHT)

        val shared = signsA.keys intersect signsB.keys
        assertTrue("expected overlap", shared.isNotEmpty())
        shared.forEach { assertEquals(it.value, signsA[it], signsB[it]) }
    }

    @Test
    fun `a joint that did not move produces no sign at all`() {
        // Abstention, as everywhere else: §6.16 draws nothing rather than committing to a direction
        // the recording never demonstrated.
        val frames = captured()
        val signs = deriveArrowSigns(frames, featuresFor(frames, Side.RIGHT), Side.RIGHT)
        // The generator moves one knee. The opposite leg is static, so it must abstain.
        assertNull(signs[FeatureId.KNEE_FLEX_PASSIVE])
    }

    @Test
    fun `out-of-plane features never get a sign`() {
        val frames = captured()
        val signs = deriveArrowSigns(frames, featuresFor(frames, Side.RIGHT), Side.RIGHT)
        listOf(
            FeatureId.TRUNK_ROTATION,
            FeatureId.PELVIC_OBLIQUITY,
            FeatureId.LUMBAR_FLEXION,
            FeatureId.SHOULDER_ELEVATION,
        ).forEach { assertNull(it.value, signs[it]) }
    }

    @Test
    fun `the sign reaches the bundle so the overlay can use it`() {
        val frames = captured()
        val result = runAuthoringPipeline(frames)
        assertNull(result.failure, result.failure)
        assertTrue("authoring must derive signs", result.arrowSigns.isNotEmpty())

        val bundle = bundleFromAuthoring(
            result = result,
            name = "Knee extension",
            id = "rp_test",
            createdAt = "2026-09-04T00:00:00Z",
        )!!
        assertEquals(result.arrowSigns, bundle.manifest.arrowSigns)
    }

    @Test
    fun `the dominant feature has a sign, or its range arrow could never draw`() {
        val frames = captured()
        val result = runAuthoringPipeline(frames)
        assertNull(result.failure, result.failure)
        assertTrue(
            "the exercised joint must be drawable: ${result.dominant} in ${result.arrowSigns.keys}",
            result.arrowSigns.containsKey(result.dominant),
        )
    }
}
