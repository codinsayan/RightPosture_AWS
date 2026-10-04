package com.example.rightposture

import com.example.rightposture.core.live.Bone
import com.example.rightposture.core.live.GhostPoint
import com.example.rightposture.core.live.PlacedGhost
import com.example.rightposture.core.live.RETARGETED_BONES
import com.example.rightposture.core.live.boneLengths
import com.example.rightposture.core.live.medianBoneLengths
import com.example.rightposture.core.live.placeGhost
import com.example.rightposture.core.live.retargeted
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.GHOST_LANDMARKS
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.NormalisedPose
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.build
import com.example.rightposture.core.pose.normalisedPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.hypot

/**
 * 10.7. The ghost with the patient's own limbs: reachable, at the physio's joint angles.
 */
class GhostRetargetTest {

    private val aspect = 0.75f

    // -------------------------------------------------------------------------------------
    // The patient's limbs, the physio's angles
    // -------------------------------------------------------------------------------------

    @Test
    fun `a patient with shins half again as long gets a ghost with their shins`() {
        val patient = longShins(physio(), 1.5f)
        val ghost = retargetedOver(patient)
        val live = frameOf(patient)

        PoseLandmark.run { listOf(LEFT_ANKLE, RIGHT_ANKLE, LEFT_FOOT_INDEX, RIGHT_FOOT_INDEX) }.forEach { id ->
            assertLands("landmark $id", ghost[id]!!, live.imageLandmarks[id])
        }
    }

    @Test
    fun `without retargeting the ghost's ankles are where the physio's reached`() {
        // The problem this solves, stated so the test above cannot pass vacuously.
        val patient = longShins(physio(), 1.5f)
        val fitted = fittedOver(patient)
        val ankle = frameOf(patient).imageLandmarks[PoseLandmark.LEFT_ANKLE]

        assertTrue(distance(fitted[PoseLandmark.LEFT_ANKLE]!!, ankle) > 0.05)
    }

    @Test
    fun `joint angles are unchanged by the retarget`() {
        val patient = longShins(physio(), 1.5f)
        val before = fittedOver(patient)
        val after = before.retargeted(reference(), boneLengths(frameOf(patient)))

        PoseLandmark.run {
            listOf(
                Triple(LEFT_HIP, LEFT_KNEE, LEFT_ANKLE),
                Triple(RIGHT_KNEE, RIGHT_ANKLE, RIGHT_FOOT_INDEX),
                Triple(RIGHT_SHOULDER, RIGHT_ELBOW, RIGHT_WRIST),
            )
        }.forEach { joint ->
            assertEquals("angle at ${joint.second}", angle(before, joint), angle(after, joint), 1e-3)
        }
    }

    @Test
    fun `the torso is where the fit put it`() {
        // Its corners are the fit's anchors; re-deriving them would fight the fit.
        val patient = longShins(physio(), 1.5f)
        val before = fittedOver(patient)
        val after = before.retargeted(reference(), boneLengths(frameOf(patient)))

        PoseLandmark.run { listOf(LEFT_HIP, RIGHT_HIP, LEFT_SHOULDER, RIGHT_SHOULDER, NOSE) }.forEach {
            assertEquals(before[it], after[it])
        }
    }

    // -------------------------------------------------------------------------------------
    // When the patient cannot be measured
    // -------------------------------------------------------------------------------------

    @Test
    fun `a bone whose length cannot be measured keeps its fitted length`() {
        val patient = longShins(physio(), 1.5f)
        val live = frameOf(patient).let { frame ->
            frame.copy(visibility = frame.visibility.toMutableList().apply { this[PoseLandmark.LEFT_ANKLE] = 0.1f })
        }
        val fitted = fittedOver(patient)
        val ghost = fitted.retargeted(reference(), boneLengths(live))

        assertEquals("the unseen shin keeps the physio's length", fitted[PoseLandmark.LEFT_ANKLE], ghost[PoseLandmark.LEFT_ANKLE])
        assertLands("the seen one is still the patient's", ghost[PoseLandmark.RIGHT_ANKLE]!!, live.imageLandmarks[PoseLandmark.RIGHT_ANKLE])
    }

    @Test
    fun `a child is carried along when its parent moves, even unmeasured`() {
        // A missing forearm confuses a patient; a detached one is worse.
        val patient = longShins(physio(), 1.5f)
        val live = frameOf(patient).let { frame ->
            frame.copy(visibility = frame.visibility.toMutableList().apply { this[PoseLandmark.LEFT_FOOT_INDEX] = 0.1f })
        }
        val fitted = fittedOver(patient)
        val ghost = fitted.retargeted(reference(), boneLengths(live))

        // The shin was retargeted, so the ankle moved; the unmeasured foot keeps its fitted offset
        // from the ankle rather than its fitted absolute position.
        val fittedOffset = fitted[PoseLandmark.LEFT_FOOT_INDEX]!!.minus(fitted[PoseLandmark.LEFT_ANKLE]!!)
        val offset = ghost[PoseLandmark.LEFT_FOOT_INDEX]!!.minus(ghost[PoseLandmark.LEFT_ANKLE]!!)
        assertEquals(fittedOffset.x, offset.x, 1e-6f)
        assertEquals(fittedOffset.y, offset.y, 1e-6f)
        assertTrue(distance(ghost[PoseLandmark.LEFT_FOOT_INDEX]!!, fitted[PoseLandmark.LEFT_FOOT_INDEX]!!.let { Vec3(it.x, it.y, 0f) }) > 0.01)
    }

    @Test
    fun `an implausible length is distrusted rather than drawn`() {
        // A shin three times the physio's is a landmark that slid, not a patient.
        val patient = longShins(physio(), 3f)
        val fitted = fittedOver(patient)
        val ghost = fitted.retargeted(reference(), boneLengths(frameOf(patient)))

        assertEquals(fitted[PoseLandmark.LEFT_ANKLE], ghost[PoseLandmark.LEFT_ANKLE])
    }

    @Test
    fun `a limb pointing at the camera is not mistaken for a short one`() {
        // Measured in the image, a forearm reaching towards the lens reads as short, and the ghost's
        // forearm would shrink on every such frame. Measured in 3D it is its own length.
        val body = physio()
        val reaching = body.toMutableList().apply {
            val elbow = body[PoseLandmark.LEFT_ELBOW]
            val forearm = (body[PoseLandmark.LEFT_WRIST] - elbow).length()
            this[PoseLandmark.LEFT_WRIST] = Vec3(elbow.x, elbow.y, elbow.z - forearm)
        }
        val fitted = fittedOver(reaching)
        val ghost = fitted.retargeted(reference(), boneLengths(frameOf(reaching)))

        assertLands("same 3D length, so no change", ghost[PoseLandmark.LEFT_WRIST]!!, fitted[PoseLandmark.LEFT_WRIST]!!.let { Vec3(it.x, it.y, 0f) })
    }

    // -------------------------------------------------------------------------------------
    // Measuring
    // -------------------------------------------------------------------------------------

    @Test
    fun `lengths are in the patient's own torso lengths, whatever their size`() {
        val small = boneLengths(frameOf(physio()))
        val big = boneLengths(frameOf(physio().map { it * 1.3f }))

        RETARGETED_BONES.forEach { assertEquals("$it", small.getValue(it), big.getValue(it), 1e-5f) }
    }

    @Test
    fun `the median across frames ignores one bad frame`() {
        val frames = List(6) { frameOf(physio()) } + frameOf(longShins(physio(), 2f))
        val shin = Bone(PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE)

        assertEquals(boneLengths(frameOf(physio())).getValue(shin), medianBoneLengths(frames).getValue(shin), 1e-6f)
    }

    @Test
    fun `every bone joins landmarks the ghost carries`() {
        RETARGETED_BONES.forEach { bone ->
            assertTrue("$bone parent", bone.parent in GHOST_LANDMARKS)
            assertTrue("$bone child", bone.child in GHOST_LANDMARKS)
        }
    }

    @Test
    fun `every bone's parent is placed before it`() {
        val torso = PoseLandmark.run { setOf(LEFT_HIP, RIGHT_HIP, LEFT_SHOULDER, RIGHT_SHOULDER) }
        val placed = torso.toMutableSet()
        RETARGETED_BONES.forEach { bone ->
            assertTrue("$bone's parent is not placed yet", bone.parent in placed)
            placed += bone.child
        }
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private fun reference(): NormalisedPose = frameOf(physio()).let { normalisedPose(it, build(it)!!)!! }

    private fun fittedOver(patient: List<Vec3>): PlacedGhost =
        placeGhost(reference(), frameOf(patient), aspect, CameraView.FRONTAL, CameraView.FRONTAL)!!

    private fun retargetedOver(patient: List<Vec3>): PlacedGhost =
        fittedOver(patient).retargeted(reference(), boneLengths(frameOf(patient)))

    /** Isotropic camera; see GhostPlacementTest. */
    private fun frameOf(world: List<Vec3>) = LandmarkFrame(
        worldLandmarks = world,
        imageLandmarks = world.map { Vec3(0.5f + 0.6f * it.x / aspect, 0.5f + 0.6f * it.y, it.z) },
        visibility = List(POSE_LANDMARK_COUNT) { 1f },
        presence = List(POSE_LANDMARK_COUNT) { 1f },
    )

    /** The same body with each shin scaled by [factor], and the foot carried with the ankle. */
    private fun longShins(body: List<Vec3>, factor: Float): List<Vec3> {
        val out = body.toMutableList()
        PoseLandmark.run { listOf(LEFT_KNEE to listOf(LEFT_ANKLE, LEFT_HEEL, LEFT_FOOT_INDEX), RIGHT_KNEE to listOf(RIGHT_ANKLE, RIGHT_HEEL, RIGHT_FOOT_INDEX)) }
            .forEach { (knee, foot) ->
                val ankle = foot.first()
                val shift = (body[ankle] - body[knee]) * (factor - 1f)
                foot.forEach { out[it] = Vec3(body[it].x + shift.x, body[it].y + shift.y, body[it].z + shift.z) }
            }
        return out
    }

    /** Standing, MediaPipe world metres (+y down), right arm raised. */
    private fun physio(): List<Vec3> {
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

    private fun angle(ghost: PlacedGhost, joint: Triple<Int, Int, Int>): Double {
        val a = ghost[joint.first]!!.minus(ghost[joint.second]!!)
        val b = ghost[joint.third]!!.minus(ghost[joint.second]!!)
        val cosine = (a.x * b.x + a.y * b.y) / (hypot(a.x, a.y) * hypot(b.x, b.y))
        return Math.toDegrees(acos(cosine.toDouble().coerceIn(-1.0, 1.0)))
    }

    private fun GhostPoint.minus(o: GhostPoint) = GhostPoint(x - o.x, y - o.y)

    private fun distance(p: GhostPoint, q: Vec3) = hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble())

    private fun assertLands(what: String, actual: GhostPoint, expected: Vec3) {
        assertEquals("$what x", expected.x, actual.x, 1e-3f)
        assertEquals("$what y", expected.y, actual.y, 1e-3f)
    }
}
