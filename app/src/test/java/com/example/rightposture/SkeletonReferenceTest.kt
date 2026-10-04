package com.example.rightposture

import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.BodyFrameResult
import com.example.rightposture.core.pose.BodyFrameStatus
import com.example.rightposture.core.pose.BodyFrameTracker
import com.example.rightposture.core.pose.GHOST_LANDMARKS
import com.example.rightposture.core.pose.GHOST_LANDMARK_SLOT
import com.example.rightposture.core.pose.NormalisedPose
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.TimedLandmarkFrame
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.syntheticTwoStepRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.SegmentationResult
import com.example.rightposture.core.reference.SkeletonReference
import com.example.rightposture.core.reference.buildSkeletonReference
import com.example.rightposture.core.reference.diagnostic
import com.example.rightposture.core.reference.runAuthoringPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos

/**
 * 10.2. The physio's body on the §6.8.4 phase grid - the same grid, by the same rule, as the angle
 * reference the z-scores come from.
 */
class SkeletonReferenceTest {

    // -------------------------------------------------------------------------------------
    // Through the authoring pipeline
    // -------------------------------------------------------------------------------------

    @Test
    fun `authoring a movement produces a skeleton on the phase grid`() {
        val skeleton = authored()

        assertEquals(Params.PHASE_BINS, skeleton.binCount)
        assertEquals(GHOST_LANDMARKS, skeleton.landmarks)
        assertTrue(skeleton.repCount >= Params.MIN_SURVIVING_REPS)
        assertTrue("every coordinate must be finite", skeleton.positions.all(Float::isFinite))
        assertTrue(skeleton.spread.all { it.isFinite() && it >= 0f })
        assertTrue(skeleton.visibility.all { it in 0f..1f })
    }

    @Test
    fun `the skeleton at bin p is the pose the angle reference has at bin p`() {
        // The trap 10.2 exists to avoid. Phase comes from the knee's value and the generator locks
        // the elbow to it, so on the outbound half both angles must fall on a straight line from
        // rest to peak as a function of p. A skeleton indexed by time, or resampled by any rule but
        // the features', bends that line - and would still look like a plausible movement.
        val skeleton = authored()
        val knee = (0..50).map { jointAngle(skeleton.poseAt(it), MOVING_KNEE) }
        val elbow = (0..50).map { jointAngle(skeleton.poseAt(it), MOVING_ELBOW) }

        assertEquals("the knee sweep the physio recorded", 90.0, abs(knee[50] - knee[0]), 3.0)
        assertEquals("the elbow sweep the physio recorded", 70.0, abs(elbow[50] - elbow[0]), 3.0)
        (0..50).forEach { p ->
            val expectedKnee = knee[0] + (knee[50] - knee[0]) * p / 50.0
            val expectedElbow = elbow[0] + (elbow[50] - elbow[0]) * p / 50.0
            assertEquals("knee at p=$p", expectedKnee, knee[p], 3.0)
            assertEquals("elbow at p=$p", expectedElbow, elbow[p], 3.0)
        }
    }

    @Test
    fun `the return half comes back to the start pose`() {
        val skeleton = authored()
        val start = skeleton.poseAt(0)
        val end = skeleton.poseAt(Params.PHASE_BINS - 1)

        start.points.zip(end.points).forEachIndexed { slot, (a, b) ->
            assertTrue("landmark ${GHOST_LANDMARKS[slot]} does not return", (a - b).length() < 0.05f)
        }
    }

    @Test
    fun `the diagnostic names every landmark`() {
        val line = authored().diagnostic()

        assertTrue(line, line.startsWith("skeleton bins=${Params.PHASE_BINS} "))
        GHOST_LANDMARKS.forEach { assertTrue("$it missing from: $line", line.contains(" $it:")) }
    }

    // -------------------------------------------------------------------------------------
    // Dropping reps rather than repairing them
    // -------------------------------------------------------------------------------------

    @Test
    fun `a rep with an unusable body frame anywhere is dropped, not interpolated across`() {
        val inputs = inputs()
        val full = inputs.build()!!
        val holed = inputs.copy(bodyFrames = inputs.withInvalidFrameIn(repIndex = 2)).build()!!

        assertEquals("one rep lost, not repaired", full.repCount - 1, holed.repCount)
    }

    @Test
    fun `too few usable reps returns null rather than a thin skeleton`() {
        val inputs = inputs()
        val usable = inputs.segmentation.usable.size
        var frames = inputs.bodyFrames
        // Break every rep but MIN_SURVIVING_REPS - 1 of them.
        (0..usable - Params.MIN_SURVIVING_REPS).forEach { rep ->
            frames = inputs.copy(bodyFrames = frames).withInvalidFrameIn(rep)
        }

        assertNull(inputs.copy(bodyFrames = frames).build())
    }

    @Test
    fun `a body frame reused rather than rebuilt is recorded, not rejected`() {
        // §6.4: a sagittal recording is mostly REUSED by design. That is provenance for the reader,
        // never a reason to drop the skeleton.
        val inputs = inputs()
        val reused = inputs.bodyFrames.map { BodyFrameResult(it.frame, BodyFrameStatus.REUSED) }
        val skeleton = inputs.copy(bodyFrames = reused).build()

        assertNotNull(skeleton)
        assertTrue("no bin had a fresh frame", skeleton!!.fresh.none { it })
        assertTrue("and the fresh recording says so", inputs.build()!!.fresh.all { it })
    }

    // -------------------------------------------------------------------------------------
    // Median, minimum, distance
    // -------------------------------------------------------------------------------------

    @Test
    fun `one atypical rep does not move the median pose`() {
        // With five to seven reps a single outlier still moves a mean noticeably (§6.9.1).
        val inputs = inputs()
        val clean = inputs.build()!!
        val oddRep = inputs.withLandmarkMovedIn(repIndex = 1, landmark = PoseLandmark.LEFT_WRIST, by = Vec3(0.4f, 0f, 0f))
        val skeleton = oddRep.build()!!
        val slot = GHOST_LANDMARK_SLOT.getValue(PoseLandmark.LEFT_WRIST)

        (0 until Params.PHASE_BINS).forEach { bin ->
            val shift = (skeleton.poseAt(bin).points[slot] - clean.poseAt(bin).points[slot]).length()
            assertTrue("bin $bin moved by $shift", shift < 1e-3f)
        }
    }

    @Test
    fun `spread is where the physio was inconsistent and nowhere else`() {
        // Half the reps put the left wrist somewhere else, in a different direction each time. Its
        // spread must rise; a landmark nobody moved must stay at zero.
        var inputs = inputs()
        val directions = listOf(Vec3(0.1f, 0f, 0f), Vec3(0f, 0.1f, 0f), Vec3(0f, 0f, 0.1f), Vec3(-0.1f, 0f, 0f))
        directions.forEachIndexed { rep, offset ->
            inputs = inputs.withLandmarkMovedIn(repIndex = rep, landmark = PoseLandmark.LEFT_WRIST, by = offset)
        }
        val skeleton = inputs.build()!!
        val wrist = GHOST_LANDMARK_SLOT.getValue(PoseLandmark.LEFT_WRIST)
        val hip = GHOST_LANDMARK_SLOT.getValue(PoseLandmark.LEFT_HIP)

        (0 until Params.PHASE_BINS).forEach { bin ->
            assertTrue("wrist at bin $bin", skeleton.spreadAt(bin, wrist) > 0.05f)
            assertTrue("hip at bin $bin", skeleton.spreadAt(bin, hip) < 1e-3f)
        }
    }

    @Test
    fun `visibility is the lowest across reps, not the mean`() {
        // One rep where the joint was hidden is a reason to doubt the skeleton there.
        val skeleton = inputs().withVisibilityIn(repIndex = 0, landmark = PoseLandmark.LEFT_WRIST, visibility = 0.2f).build()!!
        val wrist = GHOST_LANDMARK_SLOT.getValue(PoseLandmark.LEFT_WRIST)
        val knee = GHOST_LANDMARK_SLOT.getValue(PoseLandmark.LEFT_KNEE)

        (0 until Params.PHASE_BINS).forEach { bin ->
            assertEquals(0.2f, skeleton.visibilityAt(bin, wrist), 1e-4f)
            assertTrue(skeleton.visibilityAt(bin, knee) > 0.9f)
        }
    }

    @Test
    fun `mismatched inputs return null rather than a misaligned skeleton`() {
        val inputs = inputs()

        assertNull(inputs.copy(bodyFrames = inputs.bodyFrames.drop(1)).build())
        assertNull(inputs.copy(trace = inputs.trace.drop(1)).build())
    }

    @Test
    fun `reading outside the grid is an error, not a clamp`() {
        val skeleton = authored()
        val failed = runCatching { skeleton.poseAt(Params.PHASE_BINS) }

        assertTrue(failed.isFailure)
        assertFalse(runCatching { skeleton.poseAt(Params.PHASE_BINS - 1) }.isFailure)
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    private data class Inputs(
        val captured: List<CapturedFrame>,
        val bodyFrames: List<BodyFrameResult>,
        val segmentation: SegmentationResult,
        val trace: List<Float>,
        val sampleRateHz: Double,
    ) {
        fun build(): SkeletonReference? = buildSkeletonReference(
            captured, bodyFrames, segmentation, trace, sampleRateHz,
            minOf(Params.BUTTERWORTH_CUTOFF_HZ, sampleRateHz / 2.0 * 0.8),
        )

        private fun framesOf(repIndex: Int): IntRange {
            val segment = segmentation.usable[repIndex]
            return segment.startIndex..segment.endIndex
        }

        fun withInvalidFrameIn(repIndex: Int): List<BodyFrameResult> {
            val middle = framesOf(repIndex).let { (it.first + it.last) / 2 }
            return bodyFrames.mapIndexed { index, frame ->
                if (index == middle) BodyFrameResult(null, BodyFrameStatus.INVALID) else frame
            }
        }

        fun withLandmarkMovedIn(repIndex: Int, landmark: Int, by: Vec3): Inputs = copy(
            captured = captured.mapIndexed { index, captured ->
                if (index !in framesOf(repIndex)) return@mapIndexed captured
                val world = captured.frame.worldLandmarks.toMutableList()
                world[landmark] = Vec3(world[landmark].x + by.x, world[landmark].y + by.y, world[landmark].z + by.z)
                captured.copy(frame = captured.frame.copy(worldLandmarks = world))
            },
        )

        fun withVisibilityIn(repIndex: Int, landmark: Int, visibility: Float): Inputs = copy(
            captured = captured.mapIndexed { index, captured ->
                if (index !in framesOf(repIndex)) return@mapIndexed captured
                val seen = captured.frame.visibility.toMutableList()
                seen[landmark] = visibility
                captured.copy(frame = captured.frame.copy(visibility = seen))
            },
        )
    }

    // Nine: with boundaries at rest the generator's edge rests are undetectable, which leaves five
    // usable reps - an odd count, so a median can outvote one atypical rep, and one more than
    // MIN_SURVIVING_REPS, so one can be dropped.
    private fun stream(): List<TimedLandmarkFrame> = syntheticTwoStepRepetitions(repCount = 9)

    /** The pipeline's own intermediates, so a test can break exactly one thing in them. */
    private fun inputs(): Inputs {
        val captured = stream().map { CapturedFrame(it.frame, it.timestampNs) }
        val result = runAuthoringPipeline(captured)
        assertNull(result.failure, result.failure)
        val tracker = BodyFrameTracker()
        val seconds = (captured.last().timestampNs - captured.first().timestampNs) / 1_000_000_000.0
        return Inputs(
            captured = captured,
            bodyFrames = captured.map { tracker.update(it.frame, it.timestampNs) },
            segmentation = result.segmentation!!,
            trace = result.dominantTrace,
            sampleRateHz = (captured.size - 1) / seconds,
        )
    }

    private fun authored(): SkeletonReference {
        val result = runAuthoringPipeline(stream().map { CapturedFrame(it.frame, it.timestampNs) })
        assertNull(result.failure, result.failure)
        return assertNotNullAnd(result.skeleton)
    }

    private fun assertNotNullAnd(skeleton: SkeletonReference?): SkeletonReference {
        assertNotNull("authoring must produce a skeleton", skeleton)
        return skeleton!!
    }

    /** The interior angle at the middle landmark, degrees. Rotation-invariant, so frame-free. */
    private fun jointAngle(pose: NormalisedPose, joint: Triple<Int, Int, Int>): Double {
        val a = pose[joint.first]!! - pose[joint.second]!!
        val b = pose[joint.third]!! - pose[joint.second]!!
        val cosine = (a dot b) / (a.length() * b.length())
        return Math.toDegrees(acos(cosine.toDouble().coerceIn(-1.0, 1.0)))
    }

    private companion object {
        // The generator moves the right-hand limbs (as MediaPipe labels them).
        val MOVING_KNEE = Triple(PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
        val MOVING_ELBOW = Triple(PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
    }
}
