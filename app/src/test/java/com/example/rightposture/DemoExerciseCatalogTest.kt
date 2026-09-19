package com.example.rightposture

import com.example.rightposture.catalog.CatalogWarningId
import com.example.rightposture.catalog.DEMO_EXERCISES
import com.example.rightposture.catalog.ExerciseMode
import com.example.rightposture.catalog.checkAgainstCatalog
import com.example.rightposture.catalog.demoExerciseById
import com.example.rightposture.catalog.manifestLabel
import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.buildHoldReference
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.SyntheticPoseConfig
import com.example.rightposture.core.pose.syntheticKneeRepetitions
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.runAuthoringPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 6.4. §3.3's four demo exercises, and the check that a recording is of the one that was chosen.
 *
 * The catalogue supplies no thresholds, so there is nothing here about tolerances - the tests are
 * about the two things it can get wrong: describing an exercise inconsistently with the scoring path
 * it routes to, and failing to notice that the physio recorded something else.
 */
class DemoExerciseCatalogTest {

    private val kneeExtension = demoExerciseById("rp_knee_extension")!!
    private val hipAbduction = demoExerciseById("rp_hip_abduction")!!
    private val wallSit = demoExerciseById("rp_wall_sit")!!
    private val sitToStand = demoExerciseById("rp_sit_to_stand")!!

    // -------------------------------------------------------------------------------------
    // The set itself
    // -------------------------------------------------------------------------------------

    @Test
    fun `the catalogue is the four exercises section 3_3 names`() {
        assertEquals(4, DEMO_EXERCISES.size)
        assertEquals(4, DEMO_EXERCISES.map { it.id }.toSet().size)
        assertEquals(
            listOf("Seated knee extension", "Standing hip abduction", "Wall sit", "Sit to stand"),
            DEMO_EXERCISES.map { it.name },
        )
    }

    @Test
    fun `only the wall sit is a hold, and only it carries a hold duration`() {
        // §6.19 is the second scoring path and the wall sit is what proves it exists. The
        // prescription has to agree with the mode: a counted exercise with hold_seconds set, or a
        // hold without one, would produce a manifest that contradicts the path that scores it.
        DEMO_EXERCISES.forEach { exercise ->
            val isHold = exercise.mode == ExerciseMode.HOLD
            assertEquals(exercise.name, isHold, exercise.id == wallSit.id)
            assertEquals(exercise.name, isHold, exercise.prescription.holdSeconds != null)
        }
    }

    @Test
    fun `abduction is the only frontal exercise`() {
        // Abduction is a frontal-plane movement, so side-on it is almost pure depth - the axis a
        // single camera measures worst. §17.1 makes required_view the answer to that objection, and
        // this exercise is the one that exercises it.
        assertEquals(CameraView.FRONTAL, hipAbduction.requiredView)
        assertEquals(
            listOf(hipAbduction.id),
            DEMO_EXERCISES.filter { it.requiredView == CameraView.FRONTAL }.map { it.id },
        )
        assertEquals("frontal", hipAbduction.requiredView.manifestLabel())
        assertEquals("sagittal", kneeExtension.requiredView.manifestLabel())
    }

    @Test
    fun `every exercise says where the phone goes and when to stop`() {
        // Read on the screen while the stand is being set up, so an empty one is a broken setup
        // instruction rather than a cosmetic gap.
        DEMO_EXERCISES.forEach {
            assertTrue(it.name, it.setup.isNotBlank())
            assertTrue(it.name, it.safety.isNotBlank())
            assertTrue(it.name, it.expectedDominant.isNotEmpty())
            assertTrue(it.name, it.compensationWatch.isNotEmpty())
        }
    }

    // -------------------------------------------------------------------------------------
    // Conformance, cyclic
    // -------------------------------------------------------------------------------------

    @Test
    fun `a knee recording matches seated knee extension`() {
        val result = authoredKneeExtension().copy(recordedView = CameraView.SAGITTAL)

        val warnings = checkAgainstCatalog(kneeExtension, result)

        assertNull(
            warnings.toString(),
            warnings.firstOrNull { it.id == CatalogWarningId.VIEW || it.id == CatalogWarningId.DOMINANT },
        )
    }

    @Test
    fun `the same recording labelled hip abduction is flagged on both counts`() {
        // The mistake this exists for: the physio picks the wrong entry, records a perfectly good
        // set, and the bundle goes out under a name that does not describe it. The recording is
        // valid, so §3.8 has nothing to say - only the catalogue can notice.
        val result = authoredKneeExtension().copy(recordedView = CameraView.SAGITTAL)

        val warnings = checkAgainstCatalog(hipAbduction, result).map { it.id }

        assertTrue(warnings.toString(), CatalogWarningId.VIEW in warnings)
        assertTrue(warnings.toString(), CatalogWarningId.DOMINANT in warnings)
    }

    @Test
    fun `a view the recording never settled on is left to the validator`() {
        // §3.8 check 6 already rejects a recording with no consistent view. Repeating that here
        // would be a second, weaker voice on a question that is already answered.
        val result = authoredKneeExtension().copy(recordedView = null)

        assertTrue(checkAgainstCatalog(hipAbduction, result).none { it.id == CatalogWarningId.VIEW })
    }

    @Test
    fun `an unwatched compensation is reported rather than silently dropped`() {
        // Sit-to-stand's compensations are the trunk and the ankle. In the knee recording the ankle
        // does move with the sweep and is watched; the trunk never moves, so nothing would ever be
        // said about it - which is worth knowing before the patient starts, not after a set in
        // which the app stayed quiet through a visible compensation.
        val result = authoredKneeExtension().copy(recordedView = CameraView.SAGITTAL)

        val compensation = checkAgainstCatalog(sitToStand, result)
            .firstOrNull { it.id == CatalogWarningId.COMPENSATION }

        assertTrue(
            compensation?.message ?: "no compensation warning",
            compensation != null && FeatureId.TRUNK_FLEXION.value in compensation.message,
        )
    }

    @Test
    fun `a hold recorded as reps is refused before anything else is said`() {
        val warnings = checkAgainstCatalog(wallSit, authoredKneeExtension())

        // One warning, and it is the one that matters: everything downstream of the mode is
        // meaningless, so listing a view mismatch underneath it would be noise.
        assertEquals(1, warnings.size)
        assertEquals(CatalogWarningId.MODE, warnings.first().id)
    }

    // -------------------------------------------------------------------------------------
    // Conformance, hold
    // -------------------------------------------------------------------------------------

    @Test
    fun `a wall sit hold reference matches the wall sit`() {
        val reference = buildHoldReference(steadyWallSit(), neutralStanding).reference!!

        val warnings = checkAgainstCatalog(wallSit, reference)

        assertTrue(warnings.toString(), warnings.none { it.id == CatalogWarningId.DOMINANT })
    }

    @Test
    fun `a counted exercise recorded as a hold is refused`() {
        val reference = buildHoldReference(steadyWallSit(), neutralStanding).reference!!

        val warnings = checkAgainstCatalog(kneeExtension, reference)

        assertEquals(1, warnings.size)
        assertEquals(CatalogWarningId.MODE, warnings.first().id)
    }

    // -------------------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------------------

    /** The real authoring path, not a hand-built result: the dominant has to be derived. */
    private fun authoredKneeExtension() = runAuthoringPipeline(
        syntheticKneeRepetitions(SyntheticPoseConfig(repCount = 8))
            .map { CapturedFrame(it.frame, it.timestampNs) },
    ).also { assertEquals(FeatureId.KNEE_FLEX_ACTIVE, it.dominant) }

    private val neutralStanding = mapOf(
        FeatureId.KNEE_FLEX_ACTIVE to 175f,
        FeatureId.TRUNK_FLEXION to 2f,
        FeatureId.HIP_FLEX_ACTIVE to 175f,
    )

    private fun steadyWallSit(seconds: Double = 20.0): List<Map<FeatureId, Float>> =
        (0 until (seconds * 30).toInt()).map { i ->
            val wobble = 0.4 * kotlin.math.sin(i * 0.7)
            mapOf(
                FeatureId.KNEE_FLEX_ACTIVE to (90.0 + wobble).toFloat(),
                FeatureId.TRUNK_FLEXION to (5.0 + wobble * 0.5).toFloat(),
                FeatureId.HIP_FLEX_ACTIVE to (95.0 + wobble).toFloat(),
            )
        }
}
