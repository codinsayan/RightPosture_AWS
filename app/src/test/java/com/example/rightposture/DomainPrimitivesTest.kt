package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.FrameTiming
import com.example.rightposture.core.pose.frameTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainPrimitivesTest {
    @Test
    fun rejectsNonPositiveAndLongTimestampDeltasAsGaps() {
        assertSame(FrameTiming.Gap, frameTiming(1_000_000_000L, 1_000_000_000L))
        assertSame(FrameTiming.Gap, frameTiming(2_000_000_000L, 1_000_000_000L))
        assertSame(FrameTiming.Gap, frameTiming(1_000_000_000L, 1_250_000_001L))
    }

    @Test
    fun acceptsTimestampDeltaAtGapBoundary() {
        val result = frameTiming(1_000_000_000L, 1_250_000_000L)

        assertTrue(result is FrameTiming.Continuous)
        assertEquals(Params.MAX_FRAME_GAP_SECONDS, (result as FrameTiming.Continuous).dtSeconds, 0.0)
    }

    @Test
    fun featureIdentifiersAreExactAndUnique() {
        val identifiers = FeatureId.entries.map(FeatureId::value)
        val expected = listOf(
            "knee_flex_active",
            "knee_flex_passive",
            "knee_frontal_active",
            "hip_flex_active",
            "hip_flex_passive",
            "hip_abduction_active",
            "trunk_flexion",
            "trunk_lateral",
            "trunk_rotation",
            "pelvic_obliquity",
            "shoulder_flex_active",
            "shoulder_elevation",
            "elbow_flex_active",
            "ankle_dorsiflex_active",
            "lumbar_flexion",
        )

        assertEquals(expected, identifiers)
        assertEquals(identifiers.size, identifiers.toSet().size)
    }
}
