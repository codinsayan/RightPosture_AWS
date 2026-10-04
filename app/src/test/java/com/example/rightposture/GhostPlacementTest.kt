package com.example.rightposture

import com.example.rightposture.core.Params
import com.example.rightposture.core.live.PlacedGhost
import com.example.rightposture.core.live.placeGhost
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.GHOST_LANDMARKS
import com.example.rightposture.core.pose.GHOST_LANDMARK_SLOT
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.NormalisedPose
import com.example.rightposture.core.pose.POSE_LANDMARK_COUNT
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.build
import com.example.rightposture.core.pose.normalisedPose
import com.example.rightposture.core.pose.syntheticTwoStepRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.replayPhases
import com.example.rightposture.core.reference.runAuthoringPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 10.5. The ghost placed over the body it was built from must land on that body - through every
 * camera arrangement a real phone can produce - and refuse whenever it cannot be sure.
 *
 * The fixtures build image landmarks from world landmarks through an **isotropic** camera: one scale
 * for both axes, then divided into MediaPipe's width and height fractions. The synthetic generator's
 * own mapping scales x and y differently, a shear no similarity can reproduce and no camera has;
 * testing placement against it would measure the generator.
 */
class GhostPlacementTest {

    // -------------------------------------------------------------------------------------
    // Landing on the body it came from
    // -------------------------------------------------------------------------------------

    @Test
    fun `a frontal ghost lands on the physio's own landmarks`() {
        assertLandsOnItself(Camera(), CameraView.FRONTAL)
    }

    @Test
    fun `it lands whichever way round the camera sees the body`() {
        // The similarity has no reflection. Which way the horizontal axis runs is measured from both
        // bodies, and this is the case that fails if it is assumed instead.
        assertLandsOnItself(Camera(mirrored = true), CameraView.FRONTAL)
    }

    @Test
    fun `it lands on a portrait image and a landscape one`() {
        // MediaPipe's x and y are fractions of different lengths. Fitted in them directly, the
        // ghost is right on a square image and stretched on every phone.
        assertLandsOnItself(Camera(aspect = 9f / 16f), CameraView.FRONTAL)
        assertLandsOnItself(Camera(aspect = 16f / 9f), CameraView.FRONTAL)
    }

    @Test
    fun `it lands on a tilted camera`() {
        assertLandsOnItself(Camera(rollDegrees = 20.0), CameraView.FRONTAL)
    }

    @Test
    fun `a sagittal ghost lands on the physio's own landmarks, facing either way`() {
        val sideOn = asymmetricBody().map { rotateY(it, 90.0) }
        assertLandsOnItself(Camera(), CameraView.SAGITTAL, sideOn)
        assertLandsOnItself(Camera(mirrored = true), CameraView.SAGITTAL, sideOn)
        assertLandsOnItself(Camera(), CameraView.SAGITTAL, asymmetricBody().map { rotateY(it, -90.0) })
    }

    @Test
    fun `the ghost is the right way up`() {
        // Body y runs up, image y runs down. Unnegated, the fit turns the ghost 180 degrees - which
        // also mirrors it - and it still looks almost plausible.
        val ghost = placed(Camera(), CameraView.FRONTAL)!!

        assertTrue(ghost[PoseLandmark.NOSE]!!.y < ghost[PoseLandmark.LEFT_HIP]!!.y)
        assertTrue(ghost[PoseLandmark.LEFT_ANKLE]!!.y > ghost[PoseLandmark.LEFT_HIP]!!.y)
    }

    @Test
    fun `the ghost follows the body across the frame and in size`() {
        val near = placed(Camera(scale = 0.9f, centreX = 0.3f), CameraView.FRONTAL)!!
        val far = placed(Camera(scale = 0.4f, centreX = 0.7f), CameraView.FRONTAL)!!

        assertTrue(near[PoseLandmark.NOSE]!!.x < far[PoseLandmark.NOSE]!!.x)
        val nearHeight = near[PoseLandmark.LEFT_ANKLE]!!.y - near[PoseLandmark.NOSE]!!.y
        val farHeight = far[PoseLandmark.LEFT_ANKLE]!!.y - far[PoseLandmark.NOSE]!!.y
        assertEquals(0.9 / 0.4, (nearHeight / farHeight).toDouble(), 1e-3)
    }

    // -------------------------------------------------------------------------------------
    // Drawing nothing
    // -------------------------------------------------------------------------------------

    @Test
    fun `an oblique view draws nothing`() {
        assertNull(placed(Camera(), liveView = CameraView.OBLIQUE, requiredView = CameraView.OBLIQUE))
    }

    @Test
    fun `an unclassified view draws nothing`() {
        assertNull(placed(Camera(), liveView = null, requiredView = CameraView.FRONTAL))
    }

    @Test
    fun `a body in another view than the exercise needs draws nothing`() {
        assertNull(placed(Camera(), liveView = CameraView.FRONTAL, requiredView = CameraView.SAGITTAL))
    }

    @Test
    fun `a missing torso landmark draws nothing`() {
        PoseLandmark.run { listOf(LEFT_HIP, RIGHT_HIP, LEFT_SHOULDER, RIGHT_SHOULDER) }.forEach { hidden ->
            val world = asymmetricBody()
            val frame = frameOf(world, Camera()).let { frame ->
                frame.copy(visibility = frame.visibility.toMutableList().apply { this[hidden] = 0.1f })
            }
            assertNull("landmark $hidden hidden", placeGhost(referenceOf(world), frame, 0.75f, CameraView.FRONTAL, CameraView.FRONTAL))
        }
    }

    @Test
    fun `a side-on body with no nose draws nothing`() {
        // Sagittal facing is read off the nose; without it the horizontal axis is a guess.
        val world = asymmetricBody().map { rotateY(it, 90.0) }
        val frame = frameOf(world, Camera()).let { frame ->
            frame.copy(visibility = frame.visibility.toMutableList().apply { this[PoseLandmark.NOSE] = 0.1f })
        }

        assertNull(placeGhost(referenceOf(world), frame, 0.75f, CameraView.SAGITTAL, CameraView.SAGITTAL))
    }

    @Test
    fun `a body whose facing cannot be told draws nothing`() {
        // Shoulders collapsed onto the torso axis: nothing says which way round it is, and a leading
        // ghost drawn the wrong way round is copied.
        val world = asymmetricBody()
        val frame = frameOf(world, Camera()).let { frame ->
            val image = frame.imageLandmarks.toMutableList()
            val mid = Vec3(
                (image[PoseLandmark.LEFT_SHOULDER].x + image[PoseLandmark.RIGHT_SHOULDER].x) / 2f,
                image[PoseLandmark.LEFT_SHOULDER].y,
                0f,
            )
            image[PoseLandmark.LEFT_SHOULDER] = mid
            image[PoseLandmark.RIGHT_SHOULDER] = mid
            frame.copy(imageLandmarks = image)
        }

        assertNull(placeGhost(referenceOf(world), frame, 0.75f, CameraView.FRONTAL, CameraView.FRONTAL))
    }

    // -------------------------------------------------------------------------------------
    // Projection
    // -------------------------------------------------------------------------------------

    @Test
    fun `a frontal ghost cannot show a movement made towards the camera`() {
        // Correct, and why requiredView gates the ghost at all. Pinned so that a later change has to
        // be deliberate about it rather than discover it: body z never drives a frontal coordinate.
        val world = asymmetricBody()
        val frame = frameOf(world, Camera())
        val reference = referenceOf(world)
        val kneeSlot = GHOST_LANDMARK_SLOT.getValue(PoseLandmark.RIGHT_KNEE)
        val kneeForward = NormalisedPose(
            reference.points.mapIndexed { slot, p -> if (slot == kneeSlot) Vec3(p.x, p.y, p.z + 0.8f) else p },
        )

        val plain = placeGhost(reference, frame, 0.75f, CameraView.FRONTAL, CameraView.FRONTAL)!!
        val forward = placeGhost(kneeForward, frame, 0.75f, CameraView.FRONTAL, CameraView.FRONTAL)!!

        assertEquals(plain, forward)
    }

    // -------------------------------------------------------------------------------------
    // Replay on the physio's own recording
    // -------------------------------------------------------------------------------------

    @Test
    fun `every frame inside a rep gets the phase the reference was built on`() {
        val captured = syntheticTwoStepRepetitions(repCount = 8).map { CapturedFrame(it.frame, it.timestampNs) }
        val result = runAuthoringPipeline(captured)
        val phases = replayPhases(result.segmentation!!, result.dominantTrace)

        assertEquals(captured.size, phases.size)
        result.segmentation!!.usable.forEach { segment ->
            // Up to, not including, the end frame: a rep's last frame is the next rep's first, and
            // takes that rep's phase.
            val inRep = (segment.startIndex until segment.endIndex).map { phases[it] }
            assertTrue("a frame inside a usable rep has no phase", inRep.all { it != null })
            inRep.filterNotNull().zipWithNext().forEach { (a, b) -> assertTrue("phase went backwards", b >= a) }
            assertTrue(inRep.filterNotNull().max() >= 50.0)
        }
    }

    @Test
    fun `a pose between bins is interpolated between them`() {
        val skeleton = runAuthoringPipeline(
            syntheticTwoStepRepetitions(repCount = 8).map { CapturedFrame(it.frame, it.timestampNs) },
        ).skeleton!!
        val halfway = skeleton.poseAtPhase(20.5)
        val a = skeleton.poseAt(20)
        val b = skeleton.poseAt(21)

        assertEquals(skeleton.poseAt(20), skeleton.poseAtPhase(20.0))
        halfway.points.indices.forEach { slot ->
            assertEquals((a.points[slot].y + b.points[slot].y) / 2f, halfway.points[slot].y, 1e-5f)
        }
        assertEquals(skeleton.poseAt(Params.PHASE_BINS - 1), skeleton.poseAtPhase(250.0))
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    /**
     * An isotropic camera. World metres to pixels with one [scale], optionally mirrored and rolled in
     * the image plane, then expressed as MediaPipe's fractions of width and height for [aspect].
     */
    private data class Camera(
        val aspect: Float = 0.75f,
        val mirrored: Boolean = false,
        val rollDegrees: Double = 0.0,
        val scale: Float = 0.6f,
        val centreX: Float = 0.5f,
    )

    private fun frameOf(world: List<Vec3>, camera: Camera): LandmarkFrame {
        val r = Math.toRadians(camera.rollDegrees)
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val image = world.map { p ->
            val x = if (camera.mirrored) -p.x else p.x
            // MediaPipe world y is down, as image y is.
            val u = camera.scale * (c * x - s * p.y)
            val v = camera.scale * (s * x + c * p.y)
            Vec3(camera.centreX + u / camera.aspect, 0.5f + v, p.z)
        }
        return LandmarkFrame(
            worldLandmarks = world,
            imageLandmarks = image,
            visibility = List(POSE_LANDMARK_COUNT) { 1f },
            presence = List(POSE_LANDMARK_COUNT) { 1f },
        )
    }

    private fun referenceOf(world: List<Vec3>): NormalisedPose {
        val frame = frameOf(world, Camera())
        return normalisedPose(frame, build(frame)!!)!!
    }

    private fun placed(
        camera: Camera,
        liveView: CameraView?,
        requiredView: CameraView = liveView ?: CameraView.FRONTAL,
        world: List<Vec3> = asymmetricBody(),
    ): PlacedGhost? = placeGhost(referenceOf(world), frameOf(world, camera), camera.aspect, liveView, requiredView)

    private fun assertLandsOnItself(camera: Camera, view: CameraView, world: List<Vec3> = asymmetricBody()) {
        val frame = frameOf(world, camera)
        val ghost = placeGhost(referenceOf(world), frame, camera.aspect, view, view)
        assertNotNull("$camera $view drew nothing", ghost)
        GHOST_LANDMARKS.forEachIndexed { slot, id ->
            val expected = frame.imageLandmarks[id]
            val actual = ghost!!.points[slot]
            assertEquals("$camera $view landmark $id x", expected.x, actual.x, 1e-3f)
            assertEquals("$camera $view landmark $id y", expected.y, actual.y, 1e-3f)
        }
    }

    /** Standing, MediaPipe world metres (+y down), right arm raised so left and right differ. */
    private fun asymmetricBody(): List<Vec3> {
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
