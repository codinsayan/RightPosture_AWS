package com.example.rightposture

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.pose.GHOST_LANDMARKS
import com.example.rightposture.core.pose.GHOST_MIRROR_SLOT
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.NormalisedPose
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.build
import com.example.rightposture.core.pose.contributingLandmarks
import com.example.rightposture.core.pose.mirrored
import com.example.rightposture.core.pose.normalisedPose
import com.example.rightposture.pose.POSE_SKELETON_CONNECTIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 10.1. A pose must depend on the body's shape and nothing else - not where it stood, which way it
 * faced, or how big it was - and a mirror must be a relabel as well as a negation.
 */
class NormalisedPoseTest {

    // -------------------------------------------------------------------------------------
    // Invariance
    // -------------------------------------------------------------------------------------

    @Test
    fun `a pose is unchanged by where the body stands`() {
        val moved = asymmetricBody().map { it.plus(Vec3(1.7f, -0.4f, 2.3f)) }

        assertSamePose(poseOf(asymmetricBody()), poseOf(moved))
    }

    @Test
    fun `a pose is unchanged by which way the body faces`() {
        // Turned 70 degrees about the vertical and leant 15 degrees - a proper rotation, which is
        // all a different camera position or facing direction can do.
        val turned = asymmetricBody().map { rotateX(rotateY(it, 70.0), 15.0) }

        assertSamePose(poseOf(asymmetricBody()), poseOf(turned))
    }

    @Test
    fun `a pose is unchanged by how big the body is`() {
        val bigger = asymmetricBody().map { it * 1.35f }

        assertSamePose(poseOf(asymmetricBody()), poseOf(bigger))
    }

    @Test
    fun `the torso is one unit long and the hips are the origin`() {
        val pose = poseOf(asymmetricBody())
        val midHip = midpoint(pose[PoseLandmark.LEFT_HIP]!!, pose[PoseLandmark.RIGHT_HIP]!!)
        val midShoulder = midpoint(pose[PoseLandmark.LEFT_SHOULDER]!!, pose[PoseLandmark.RIGHT_SHOULDER]!!)

        assertEquals(0f, midHip.length(), 1e-5f)
        assertEquals(1f, (midShoulder - midHip).length(), 1e-5f)
        // The torso runs up the body frame's y axis, by §6.4's construction.
        assertEquals(1f, midShoulder.y, 1e-5f)
    }

    @Test
    fun `image landmarks play no part`() {
        // They are perspective-divided and their z is in another unit. Scrambling them must not move
        // a single point.
        val world = asymmetricBody()
        val scrambled = frame(world).copy(imageLandmarks = List(POSE_LANDMARK_COUNT) { Vec3(9f, -3f, 40f) })
        val body = build(frame(world))!!

        assertSamePose(poseOf(world), normalisedPose(scrambled, body)!!)
    }

    // -------------------------------------------------------------------------------------
    // Mirroring
    // -------------------------------------------------------------------------------------

    @Test
    fun `mirroring agrees with actually reflecting the body`() {
        // The definition, not a restatement of the implementation: reflect the world through a
        // plane and relabel left and right, the way a front camera's flipped image does, and the
        // pose that comes out must be mirrored() of the original.
        val reflected = relabel(asymmetricBody().map { Vec3(-it.x, it.y, it.z) })

        assertSamePose(poseOf(asymmetricBody()).mirrored(), poseOf(reflected))
    }

    @Test
    fun `mirroring swaps the labels and negates x`() {
        // The right arm is raised in the fixture. In the mirror image that is the left arm, on the
        // other side of the body.
        val original = poseOf(asymmetricBody())
        val mirror = original.mirrored()
        val rightWrist = original[PoseLandmark.RIGHT_WRIST]!!
        val mirroredLeft = mirror[PoseLandmark.LEFT_WRIST]!!

        assertEquals(-rightWrist.x, mirroredLeft.x, 1e-6f)
        assertEquals(rightWrist.y, mirroredLeft.y, 1e-6f)
        assertEquals(rightWrist.z, mirroredLeft.z, 1e-6f)
        assertTrue("the raised arm must stay raised, on the other side", mirroredLeft.y > 0.5f)
    }

    @Test
    fun `mirroring is not a bare negation`() {
        // The trap the relabel exists for. Negating x alone would put the raised wrist on the left
        // side of the body while still calling it the right wrist.
        val original = poseOf(asymmetricBody())
        val negatedOnly = NormalisedPose(original.points.map { Vec3(-it.x, it.y, it.z) })

        assertFalse(samePose(original.mirrored(), negatedOnly))
    }

    @Test
    fun `mirroring twice is the identity`() {
        val pose = poseOf(asymmetricBody())

        assertSamePose(pose, pose.mirrored().mirrored())
    }

    @Test
    fun `every carried landmark has its opposite carried too`() {
        GHOST_LANDMARKS.forEachIndexed { slot, id ->
            val opposite = GHOST_LANDMARKS[GHOST_MIRROR_SLOT[slot]]
            if (id == PoseLandmark.NOSE) {
                assertEquals(id, opposite)
            } else {
                assertTrue("$id must map to its lateral pair, got $opposite", id != opposite)
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Degenerate input
    // -------------------------------------------------------------------------------------

    @Test
    fun `a collapsed torso returns null rather than a pose`() {
        // Shoulders on the hips: there is no length to scale by, and dividing by it would send every
        // point to infinity.
        val good = asymmetricBody()
        val collapsed = good.toMutableList().apply {
            this[PoseLandmark.LEFT_SHOULDER] = good[PoseLandmark.LEFT_HIP]
            this[PoseLandmark.RIGHT_SHOULDER] = good[PoseLandmark.RIGHT_HIP]
        }

        assertNull(normalisedPose(frame(collapsed), build(frame(good))!!))
    }

    @Test
    fun `a non-finite landmark returns null rather than a pose with a hole in it`() {
        val good = asymmetricBody()
        val broken = good.toMutableList().apply { this[PoseLandmark.LEFT_KNEE] = Vec3(Float.NaN, 0f, 0f) }

        assertNull(normalisedPose(frame(broken), build(frame(good))!!))
    }

    // -------------------------------------------------------------------------------------
    // The subset
    // -------------------------------------------------------------------------------------

    @Test
    fun `every drawn bone has both ends carried`() {
        POSE_SKELETON_CONNECTIONS.forEach { (a, b) ->
            assertTrue("$a is drawn but not carried", a in GHOST_LANDMARKS)
            assertTrue("$b is drawn but not carried", b in GHOST_LANDMARKS)
        }
    }

    @Test
    fun `every landmark a feature is computed from is carried`() {
        FeatureId.entries.forEach { id ->
            Side.entries.forEach { side ->
                contributingLandmarks(id, side).forEach {
                    assertTrue("$id on $side needs landmark $it", it in GHOST_LANDMARKS)
                }
            }
        }
    }

    @Test
    fun `the face past the nose is not carried`() {
        val face = listOf(
            PoseLandmark.LEFT_EYE_INNER, PoseLandmark.LEFT_EYE, PoseLandmark.LEFT_EYE_OUTER,
            PoseLandmark.RIGHT_EYE_INNER, PoseLandmark.RIGHT_EYE, PoseLandmark.RIGHT_EYE_OUTER,
            PoseLandmark.LEFT_EAR, PoseLandmark.RIGHT_EAR,
            PoseLandmark.MOUTH_LEFT, PoseLandmark.MOUTH_RIGHT,
        )

        assertTrue(PoseLandmark.NOSE in GHOST_LANDMARKS)
        face.forEach { assertFalse("face landmark $it is noise at this distance", it in GHOST_LANDMARKS) }
        assertTrue(GHOST_LANDMARKS.size < POSE_LANDMARK_COUNT)
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    /**
     * A standing body in MediaPipe world metres (+y down, +x toward the subject's left as the camera
     * sees it), with the **right arm raised** so left and right are distinguishable. A symmetric
     * body would pass every mirror test with the relabel missing.
     */
    private fun asymmetricBody(): List<Vec3> {
        val points = MutableList(POSE_LANDMARK_COUNT) { Vec3(0f, -0.55f, 0.02f) }
        fun set(id: Int, x: Float, y: Float, z: Float = 0f) { points[id] = Vec3(x, y, z) }
        set(PoseLandmark.NOSE, 0f, -0.62f, -0.08f)
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

    private fun frame(world: List<Vec3>) = LandmarkFrame(
        worldLandmarks = world,
        imageLandmarks = world,
        visibility = List(POSE_LANDMARK_COUNT) { 1f },
        presence = List(POSE_LANDMARK_COUNT) { 1f },
    )

    private fun poseOf(world: List<Vec3>): NormalisedPose {
        val frame = frame(world)
        val body = build(frame)
        assertNotNull("the fixture must support a body frame", body)
        return normalisedPose(frame, body!!)!!
    }

    private fun relabel(world: List<Vec3>): List<Vec3> {
        val out = world.toMutableList()
        PoseLandmark.LATERAL_PAIRS.forEach { (left, right) ->
            out[left] = world[right]
            out[right] = world[left]
        }
        return out
    }

    private fun rotateY(v: Vec3, degrees: Double): Vec3 {
        val r = Math.toRadians(degrees)
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return Vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z)
    }

    private fun rotateX(v: Vec3, degrees: Double): Vec3 {
        val r = Math.toRadians(degrees)
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return Vec3(v.x, c * v.y - s * v.z, s * v.y + c * v.z)
    }

    private fun Vec3.plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    private fun midpoint(a: Vec3, b: Vec3) = Vec3((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f)

    private fun samePose(a: NormalisedPose, b: NormalisedPose, tolerance: Float = 1e-4f) =
        a.points.zip(b.points).all { (p, q) -> (p - q).length() <= tolerance }

    private fun assertSamePose(expected: NormalisedPose, actual: NormalisedPose) {
        expected.points.zip(actual.points).forEachIndexed { slot, (p, q) ->
            assertTrue("landmark ${GHOST_LANDMARKS[slot]}: expected $p, got $q", (p - q).length() <= 1e-4f)
        }
    }
}
