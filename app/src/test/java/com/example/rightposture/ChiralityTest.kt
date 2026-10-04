package com.example.rightposture

import com.example.rightposture.core.live.PlacedGhost
import com.example.rightposture.core.live.forCamera
import com.example.rightposture.core.live.placeGhost
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.NormalisedPose
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.build
import com.example.rightposture.core.pose.normalisedPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 10.6. A ghost recorded on one camera and shown on the other must move the patient's *same*
 * limb - and that can only come from the stored camera bits, because no frame's geometry can tell a
 * body from its mirror image.
 *
 * The body used throughout has its **right arm raised**: asymmetric in the frontal projection, so a
 * mirrored ghost is visibly wrong. A symmetric movement - or one made entirely towards the camera -
 * passes every test here while mirrored, which is how a mirror test certifies the bug it was written
 * to catch.
 */
class ChiralityTest {

    // -------------------------------------------------------------------------------------
    // Across cameras
    // -------------------------------------------------------------------------------------

    @Test
    fun `recorded on the front camera, shown on the rear, the raised arm is the patient's raised arm`() {
        val ghost = ghostOf(reference = frontCamera(body()), recordedMirrored = true, live = body(), liveMirrored = false)

        assertLandsOn(ghost, liveFrame(body()))
    }

    @Test
    fun `recorded on the rear camera, shown on the front, the raised arm is the patient's raised arm`() {
        val ghost = ghostOf(reference = body(), recordedMirrored = false, live = frontCamera(body()), liveMirrored = true)

        assertLandsOn(ghost, liveFrame(frontCamera(body())))
    }

    @Test
    fun `the same camera both times changes nothing`() {
        assertLandsOn(ghostOf(body(), false, body(), false), liveFrame(body()))
        assertLandsOn(ghostOf(frontCamera(body()), true, frontCamera(body()), true), liveFrame(frontCamera(body())))
    }

    @Test
    fun `side-on across cameras still lands`() {
        val sideOn = body().map { rotateY(it, 90.0) }
        val ghost = placeGhost(
            poseOf(frontCamera(sideOn)).forCamera(recordedMirrored = true, liveMirrored = false),
            liveFrame(sideOn),
            ASPECT,
            CameraView.SAGITTAL,
            CameraView.SAGITTAL,
        )

        assertLandsOn(ghost, liveFrame(sideOn))
    }

    // -------------------------------------------------------------------------------------
    // Sensitivity - the tests that would have caught §6.16's arrow
    // -------------------------------------------------------------------------------------

    @Test
    fun `getting the camera bit wrong puts the raised arm on the wrong side`() {
        // The pipeline must *disagree* with itself when the bit is wrong. A ghost that lands on the
        // raised arm either way is not using the bit, and would be mirrored on half of all phones.
        val live = liveFrame(body())
        val right = ghostOf(frontCamera(body()), recordedMirrored = true, live = body(), liveMirrored = false)!!
        val wrong = ghostOf(frontCamera(body()), recordedMirrored = false, live = body(), liveMirrored = false)!!

        val raised = live.imageLandmarks[PoseLandmark.RIGHT_WRIST]
        assertTrue("with the right bit the ghost's raised wrist is on the patient's", distance(right[PoseLandmark.RIGHT_WRIST]!!, raised) < 1e-3)
        assertTrue("with the wrong bit it must not be", distance(wrong[PoseLandmark.RIGHT_WRIST]!!, raised) > 0.1)
        // And the ghost raises its other arm instead - the extended limb became the other limb: as
        // high as the patient's raised wrist, on the opposite side of their body.
        val centre = (live.imageLandmarks[PoseLandmark.LEFT_HIP].x + live.imageLandmarks[PoseLandmark.RIGHT_HIP].x) / 2f
        val ghostRaised = wrong[PoseLandmark.LEFT_WRIST]!!
        assertEquals("the ghost's raised wrist is as high", raised.y, ghostRaised.y, 1e-3f)
        assertTrue("but on the other side", (ghostRaised.x - centre) * (raised.x - centre) < 0f)
        assertEquals("mirrored about the body's centre", centre - raised.x, ghostRaised.x - centre, 1e-3f)
    }

    @Test
    fun `nothing in the geometry refuses a wrong bit, which is why the bit is load-bearing`() {
        // Pinned rather than discovered. The mirror body is a valid body: it is placed, facing and
        // all, just the wrong way round. If a later change finds a real geometric check, this test
        // is where it should start failing - deliberately.
        val wrong = ghostOf(frontCamera(body()), recordedMirrored = false, live = body(), liveMirrored = false)

        assertNotNull(wrong)
    }

    @Test
    fun `a front-camera recording is the mirror image of a rear-camera one`() {
        // The premise of forCamera, checked against the definition rather than the implementation:
        // relabelling a front-camera pose recovers the rear-camera pose of the same body.
        val rear = poseOf(body())
        val front = poseOf(frontCamera(body()))

        rear.points.zip(front.forCamera(recordedMirrored = true, liveMirrored = false).points).forEach { (a, b) ->
            assertTrue("$a vs $b", (a - b).length() < 1e-4f)
        }
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private companion object {
        const val ASPECT = 0.75f
    }

    private fun ghostOf(
        reference: List<Vec3>,
        recordedMirrored: Boolean,
        live: List<Vec3>,
        liveMirrored: Boolean,
    ): PlacedGhost? = placeGhost(
        poseOf(reference).forCamera(recordedMirrored, liveMirrored),
        liveFrame(live),
        ASPECT,
        CameraView.FRONTAL,
        CameraView.FRONTAL,
    )

    /**
     * What the front camera hands MediaPipe: the flipped image of this body. Its world pose is the
     * body reflected, and every left/right label names the other side.
     */
    private fun frontCamera(world: List<Vec3>): List<Vec3> {
        val reflected = world.map { Vec3(-it.x, it.y, it.z) }.toMutableList()
        PoseLandmark.LATERAL_PAIRS.forEach { (left, right) ->
            val l = reflected[left]
            reflected[left] = reflected[right]
            reflected[right] = l
        }
        return reflected
    }

    /** Isotropic camera, 3:4 portrait. See GhostPlacementTest for why not the generator's mapping. */
    private fun liveFrame(world: List<Vec3>) = LandmarkFrame(
        worldLandmarks = world,
        imageLandmarks = world.map { Vec3(0.5f + 0.6f * it.x / ASPECT, 0.5f + 0.6f * it.y, it.z) },
        visibility = List(POSE_LANDMARK_COUNT) { 1f },
        presence = List(POSE_LANDMARK_COUNT) { 1f },
    )

    private fun poseOf(world: List<Vec3>): NormalisedPose {
        val frame = liveFrame(world)
        return normalisedPose(frame, build(frame)!!)!!
    }

    private fun assertLandsOn(ghost: PlacedGhost?, frame: LandmarkFrame) {
        assertNotNull("drew nothing", ghost)
        listOf(PoseLandmark.RIGHT_WRIST, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_ELBOW, PoseLandmark.NOSE).forEach { id ->
            val expected = frame.imageLandmarks[id]
            val actual = ghost!![id]!!
            assertEquals("landmark $id x", expected.x, actual.x, 1e-3f)
            assertEquals("landmark $id y", expected.y, actual.y, 1e-3f)
        }
    }

    private fun distance(p: com.example.rightposture.core.live.GhostPoint, q: Vec3) =
        hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble())

    /** Standing, MediaPipe world metres (+y down), **right arm raised**. */
    private fun body(): List<Vec3> {
        val points = MutableList(POSE_LANDMARK_COUNT) { Vec3(0f, -0.55f, 0.02f) }
        fun set(id: Int, x: Float, y: Float, z: Float = 0f) { points[id] = Vec3(x, y, z) }
        set(PoseLandmark.NOSE, 0f, -0.62f, -0.12f)
        set(PoseLandmark.LEFT_SHOULDER, 0.18f, -0.45f)
        set(PoseLandmark.RIGHT_SHOULDER, -0.18f, -0.45f)
        set(PoseLandmark.LEFT_ELBOW, 0.22f, -0.18f, 0.02f)
        set(PoseLandmark.LEFT_WRIST, 0.24f, 0.06f, -0.03f)
        set(PoseLandmark.RIGHT_ELBOW, -0.30f, -0.66f, -0.05f)
        set(PoseLandmark.RIGHT_WRIST, -0.36f, -0.90f, -0.10f)
        set(PoseLandmark.LEFT_HIP, 0.11f, 0f)
        set(PoseLandmark.RIGHT_HIP, -0.11f, 0f)
        set(PoseLandmark.LEFT_KNEE, 0.12f, 0.42f, -0.04f)
        set(PoseLandmark.RIGHT_KNEE, -0.10f, 0.40f, -0.12f)
        set(PoseLandmark.LEFT_ANKLE, 0.12f, 0.82f, 0.02f)
        set(PoseLandmark.RIGHT_ANKLE, -0.10f, 0.78f, 0.05f)
        set(PoseLandmark.LEFT_HEEL, 0.12f, 0.86f, 0.06f)
        set(PoseLandmark.RIGHT_HEEL, -0.10f, 0.82f, 0.09f)
        set(PoseLandmark.LEFT_FOOT_INDEX, 0.13f, 0.88f, -0.10f)
        set(PoseLandmark.RIGHT_FOOT_INDEX, -0.11f, 0.84f, -0.07f)
        return points
    }

    private fun rotateY(v: Vec3, degrees: Double): Vec3 {
        val r = Math.toRadians(degrees)
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return Vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z)
    }
}
