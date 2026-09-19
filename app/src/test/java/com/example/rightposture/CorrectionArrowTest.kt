package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.live.ArrowSigns
import com.example.rightposture.core.live.Point2
import com.example.rightposture.core.live.arrowLandmarks
import com.example.rightposture.core.live.correctionArrow
import com.example.rightposture.core.live.lengthFractionForZ
import com.example.rightposture.core.live.rotate2D
import com.example.rightposture.core.pose.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.3's direction tests. §6.16 is blunt about the failure mode - "an arrow that confidently points
 * the wrong way" - so these pin the rotation against hand-computed vectors rather than against
 * whatever the implementation happens to produce.
 */
class CorrectionArrowTest {

    private fun assertPoint(expected: Point2, actual: Point2, tolerance: Float = 1e-3f) {
        assertEquals("x", expected.x.toDouble(), actual.x.toDouble(), tolerance.toDouble())
        assertEquals("y", expected.y.toDouble(), actual.y.toDouble(), tolerance.toDouble())
    }

    // -------------------------------------------------------------------------------------
    // rotate2D against known values
    // -------------------------------------------------------------------------------------

    @Test
    fun `ninety degrees takes plus x to plus y`() {
        assertPoint(Point2(0f, 1f), rotate2D(Point2(1f, 0f), 90.0))
    }

    @Test
    fun `minus ninety degrees takes plus x to minus y`() {
        assertPoint(Point2(0f, -1f), rotate2D(Point2(1f, 0f), -90.0))
    }

    @Test
    fun `one hundred and eighty degrees negates the vector`() {
        assertPoint(Point2(-3f, -4f), rotate2D(Point2(3f, 4f), 180.0))
    }

    @Test
    fun `zero degrees is the identity`() {
        assertPoint(Point2(3f, 4f), rotate2D(Point2(3f, 4f), 0.0))
    }

    @Test
    fun `rotation preserves length`() {
        val rotated = rotate2D(Point2(3f, 4f), 37.0)
        assertEquals(5.0, rotated.length(), 1e-3)
    }

    // -------------------------------------------------------------------------------------
    // The arrow itself
    // -------------------------------------------------------------------------------------

    /** A segment pointing along +x from the origin, 100 px long. */
    private val joint = Point2(0f, 0f)
    private val distal = Point2(100f, 0f)

    @Test
    fun `the sign flips the direction the arrow points`() {
        val positive = correctionArrow(joint, distal, deltaThetaDegrees = 20.0, lengthFraction = lengthFractionForZ(6.0), sign = 1)
        val negative = correctionArrow(joint, distal, deltaThetaDegrees = 20.0, lengthFraction = lengthFractionForZ(6.0), sign = -1)
        assertNotNull(positive)
        assertNotNull(negative)

        // Rotating +20 degrees about the origin lifts the distal end toward +y; -20 drops it.
        assertTrue("positive sign should push toward +y", positive!!.to.y > positive.from.y)
        assertTrue("negative sign should push toward -y", negative!!.to.y < negative.from.y)
    }

    @Test
    fun `a negative angular error reverses the arrow just as the sign does`() {
        val positiveError = correctionArrow(joint, distal, 20.0, lengthFractionForZ(6.0), sign = 1)!!
        val negativeError = correctionArrow(joint, distal, -20.0, lengthFractionForZ(6.0), sign = 1)!!
        assertTrue(positiveError.to.y > 0f)
        assertTrue(negativeError.to.y < 0f)
    }

    @Test
    fun `the arrow starts at the distal landmark`() {
        val arrow = correctionArrow(joint, distal, 20.0, lengthFractionForZ(6.0), sign = 1)!!
        assertPoint(distal, arrow.from)
    }

    @Test
    fun `length saturates at the maximum once z reaches full scale`() {
        val arrow = correctionArrow(joint, distal, 20.0, lengthFraction = lengthFractionForZ(Params.ARROW_Z_FULL_SCALE), sign = 1)!!
        assertEquals(Params.ARROW_MAX_PX, arrow.length, 1e-3)
    }

    @Test
    fun `length is clamped at the maximum for a z beyond full scale`() {
        val arrow = correctionArrow(joint, distal, 20.0, lengthFraction = lengthFractionForZ(50.0), sign = 1)!!
        assertEquals(Params.ARROW_MAX_PX, arrow.length, 1e-3)
    }

    @Test
    fun `length is floored so a shrinking error stays visible`() {
        // A patient who has just corrected is inside ArrowSelector's exit dwell with a small |z|.
        // §6.16's floor is what stops the arrow collapsing to a dot before it disappears.
        val arrow = correctionArrow(joint, distal, 2.0, lengthFraction = lengthFractionForZ(0.3), sign = 1)!!
        val expected = Params.ARROW_MIN_LENGTH_FRACTION * Params.ARROW_MAX_PX
        assertEquals(expected, arrow.length, 1e-3)
    }

    @Test
    fun `length does not depend on the size of the angular error`() {
        val small = correctionArrow(joint, distal, 5.0, lengthFraction = lengthFractionForZ(6.0), sign = 1)!!
        val large = correctionArrow(joint, distal, 60.0, lengthFraction = lengthFractionForZ(6.0), sign = 1)!!
        // §6.16 scales length by |z| alone. The angle sets direction only.
        assertEquals(small.length, large.length, 1e-3)
    }

    // -------------------------------------------------------------------------------------
    // Abstention
    // -------------------------------------------------------------------------------------

    @Test
    fun `no verified sign means no arrow`() {
        assertNull(correctionArrow(joint, distal, 20.0, lengthFractionForZ(6.0), sign = null))
    }

    @Test
    fun `a small z shortens the arrow rather than removing it`() {
        // Whether to show an arrow at all is ArrowSelector's call. A z-gate here would cancel its
        // exit dwell and bring back the flicker the dwell exists to stop.
        assertNotNull(correctionArrow(joint, distal, 1.0, lengthFraction = lengthFractionForZ(0.4), sign = 1))
    }

    @Test
    fun `a collapsed segment produces no arrow`() {
        assertNull(correctionArrow(joint, joint, 20.0, lengthFractionForZ(6.0), sign = 1))
    }

    @Test
    fun `a non-finite input produces no arrow`() {
        assertNull(correctionArrow(joint, distal, Double.NaN, lengthFractionForZ(6.0), sign = 1))
        assertNull(correctionArrow(joint, distal, 20.0, Double.NaN, sign = 1))
    }

    @Test
    fun `a zero angular error produces no arrow even with a large z`() {
        // The rotation is the identity, so the ghost sits on the patient and there is no direction
        // to point in. Drawing a zero-length arrow would be a dot the patient cannot read.
        assertNull(correctionArrow(joint, distal, 0.0, lengthFractionForZ(6.0), sign = 1))
    }

    // -------------------------------------------------------------------------------------
    // The landmark table and the sign table
    // -------------------------------------------------------------------------------------

    @Test
    fun `out-of-plane features have no arrow geometry`() {
        // §6.16 is planar. A rotation about the longitudinal axis cannot be drawn in the image
        // plane, and pretending otherwise would coach off a picture that does not mean anything.
        listOf(
            FeatureId.TRUNK_ROTATION,
            FeatureId.PELVIC_OBLIQUITY,
            FeatureId.LUMBAR_FLEXION,
            FeatureId.SHOULDER_ELEVATION,
        ).forEach {
            assertNull(it.value, arrowLandmarks(it, Side.RIGHT))
        }
    }

    @Test
    fun `active-side features follow the side they are measured on`() {
        val right = arrowLandmarks(FeatureId.KNEE_FLEX_ACTIVE, Side.RIGHT)!!
        val left = arrowLandmarks(FeatureId.KNEE_FLEX_ACTIVE, Side.LEFT)!!
        assertTrue("the two sides must use different landmarks", right != left)
    }

    @Test
    fun `the passive knee uses the opposite leg from the active one`() {
        assertEquals(
            arrowLandmarks(FeatureId.KNEE_FLEX_ACTIVE, Side.LEFT),
            arrowLandmarks(FeatureId.KNEE_FLEX_PASSIVE, Side.RIGHT),
        )
    }

    @Test
    fun `no rotation sign is hardcoded`() {
        // §6.16 and the 5.3 plan both forbid guessed signs. The constant table stays empty on
        // purpose: signs are now measured from the reference recording in `deriveArrowSigns`, which
        // is correct for the camera and view that recording was made with. Filling this in would put
        // a guess back in front of a measurement.
        assertFalse(ArrowSigns.VERIFIED)
        FeatureId.entries.forEach { assertNull(it.value, ArrowSigns.of(it)) }
    }
}
