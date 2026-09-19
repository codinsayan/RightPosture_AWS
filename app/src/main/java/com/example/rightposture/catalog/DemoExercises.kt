package com.example.rightposture.catalog

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.live.HoldReference
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.reference.AuthoringResult
import com.example.rightposture.core.rpose.RposePrescription

/**
 * §3.3's demo set: four exercises that work, rather than forty that might.
 *
 * The catalogue is descriptive, not prescriptive. Nothing here feeds a threshold or a tolerance -
 * every number the live pipeline scores against still comes from the physio's own recording, which
 * is the whole argument of §6.11. What this file supplies is the metadata a recording cannot
 * contain: what the exercise is called, which of the two scoring paths it belongs to, where the
 * phone goes, and what it is unsafe to keep doing.
 *
 * The one place it makes a claim is [checkAgainstCatalog], and even there the claim is only that a
 * recording disagrees with the exercise the physio said they were recording. That is a warning to a
 * human, never a rejection - §3.8's validator owns rejection.
 */

enum class ExerciseMode {
    /** §6.12's phase axis: reps, phase index, rep scores. */
    CYCLIC,

    /** §6.19: no phase axis, a timer instead of a counter. */
    HOLD,
}

data class DemoExercise(
    val id: String,
    val name: String,
    val mode: ExerciseMode,
    /**
     * §6.20.1 check 4. Sagittal for anything that moves in the plane of the body's side; frontal for
     * abduction, which is invisible from the side. Single-camera depth is unreliable, so the plane
     * of motion has to face the lens - §17.1 makes that the honest answer to the judges' question.
     */
    val requiredView: CameraView,
    /** `manifest.body_position`, §9.1. */
    val bodyPosition: String,
    val laterality: String,
    /**
     * What §6.11.1's largest-range rule should land on for a correct recording.
     *
     * A set rather than one id: sit-to-stand is genuinely a hip, knee and trunk movement, and which
     * of the three has the largest normalised range depends on the performer's proportions. An
     * unexpected dominant is a sign the recording is of something else - or of the right movement
     * from the wrong angle - and is worth saying out loud before it becomes a bundle.
     */
    val expectedDominant: Set<FeatureId>,
    /**
     * The joint the classic compensation for this exercise shows up in.
     *
     * Advisory only. §3.7 derives the watch list from what actually varied, so its absence means the
     * physio's own recording did not vary there - which is either a very clean demonstration or a
     * camera that cannot see the joint. The physio is the one who can tell those apart.
     */
    val compensationWatch: Set<FeatureId>,
    val prescription: RposePrescription,
    /** Where the phone goes, in the physio's words, because this is read while setting up. */
    val setup: String,
    /** §17's rehearsal rule, and the standing instruction: stop if it hurts. */
    val safety: String,
)

/** §3.3, in order. */
val DEMO_EXERCISES: List<DemoExercise> = listOf(
    DemoExercise(
        id = "rp_knee_extension",
        name = "Seated knee extension",
        mode = ExerciseMode.CYCLIC,
        requiredView = CameraView.SAGITTAL,
        bodyPosition = "seated",
        laterality = "unilateral",
        expectedDominant = setOf(FeatureId.KNEE_FLEX_ACTIVE),
        // Leaning back to swing the leg up is the compensation, and it is a trunk movement.
        compensationWatch = setOf(FeatureId.TRUNK_FLEXION),
        prescription = RposePrescription(reps = 12, sets = 3, restSeconds = 60, holdSeconds = null),
        setup = "Sit side-on to the phone, working leg nearest the lens. Phone at knee height, " +
            "about 2 m away, whole body from head to foot in frame.",
        safety = "Stop if the knee hurts rather than works. Do not lock the knee at the top.",
    ),
    DemoExercise(
        id = "rp_hip_abduction",
        name = "Standing hip abduction",
        mode = ExerciseMode.CYCLIC,
        // Abduction happens in the frontal plane, so from the side it is almost pure depth - the one
        // axis a single camera measures worst. This is the exercise that proves view handling works.
        requiredView = CameraView.FRONTAL,
        bodyPosition = "standing",
        laterality = "unilateral",
        expectedDominant = setOf(FeatureId.HIP_ABDUCTION_ACTIVE),
        // Hiking the hip and leaning away both raise the leg without abducting it.
        compensationWatch = setOf(FeatureId.PELVIC_OBLIQUITY, FeatureId.TRUNK_LATERAL),
        prescription = RposePrescription(reps = 12, sets = 3, restSeconds = 60, holdSeconds = null),
        setup = "Stand facing the phone, one hand lightly on a chair. Phone at hip height, about " +
            "2.5 m away, both feet in frame.",
        safety = "Stop if the standing hip aches. Keep the raised foot pointing forwards.",
    ),
    DemoExercise(
        id = "rp_wall_sit",
        name = "Wall sit",
        mode = ExerciseMode.HOLD,
        requiredView = CameraView.SAGITTAL,
        bodyPosition = "standing",
        laterality = "bilateral",
        expectedDominant = setOf(FeatureId.KNEE_FLEX_ACTIVE, FeatureId.HIP_FLEX_ACTIVE),
        compensationWatch = setOf(FeatureId.TRUNK_FLEXION),
        // Reps and sets carry no meaning on the hold path; hold_seconds is the prescription.
        prescription = RposePrescription(reps = 1, sets = 3, restSeconds = 60, holdSeconds = 30),
        setup = "Stand side-on to the phone with your back against the wall. Phone at hip height, " +
            "about 2.5 m away, wall behind you and both feet in frame.",
        safety = "Stop at the first shake, not the last. Come up if the knees burn sharply.",
    ),
    DemoExercise(
        id = "rp_sit_to_stand",
        name = "Sit to stand",
        mode = ExerciseMode.CYCLIC,
        requiredView = CameraView.SAGITTAL,
        bodyPosition = "seated",
        laterality = "bilateral",
        // Genuinely three-jointed: which one dominates depends on limb proportions.
        expectedDominant = setOf(
            FeatureId.HIP_FLEX_ACTIVE,
            FeatureId.KNEE_FLEX_ACTIVE,
            FeatureId.TRUNK_FLEXION,
        ),
        compensationWatch = setOf(FeatureId.TRUNK_FLEXION, FeatureId.ANKLE_DORSIFLEX_ACTIVE),
        prescription = RposePrescription(reps = 10, sets = 3, restSeconds = 90, holdSeconds = null),
        setup = "Sit side-on to the phone on a firm chair, arms folded. Phone at hip height, about " +
            "2.5 m away, chair and both feet in frame.",
        safety = "Keep the chair against a wall. Stop and use your hands rather than fall back.",
    ),
)

fun demoExerciseById(id: String): DemoExercise? = DEMO_EXERCISES.firstOrNull { it.id == id }

// ---------------------------------------------------------------------------------------------
// Conformance
// ---------------------------------------------------------------------------------------------

/**
 * A disagreement between the recording and the exercise the physio chose.
 *
 * Deliberately not a validator check: §3.8 rejects recordings that cannot produce a usable
 * reference, which is a property of the recording alone. These are recordings that are fine but
 * appear to be of something else, and only the person who pressed record can settle that.
 */
data class CatalogWarning(val id: CatalogWarningId, val message: String)

enum class CatalogWarningId { VIEW, DOMINANT, COMPENSATION, MODE }

/** §3.3 conformance for the cyclic path. Empty means the recording matches the chosen exercise. */
fun checkAgainstCatalog(exercise: DemoExercise, result: AuthoringResult): List<CatalogWarning> {
    if (exercise.mode != ExerciseMode.CYCLIC) {
        return listOf(
            CatalogWarning(
                CatalogWarningId.MODE,
                "${exercise.name} is a hold. Record it with the hold flow, not eight reps.",
            ),
        )
    }

    val warnings = mutableListOf<CatalogWarning>()

    val recorded = result.recordedView
    // Null means the recording never settled on one view, which §3.8 check 6 already rejects; there
    // is nothing this function can add to that.
    if (recorded != null && recorded != exercise.requiredView) {
        warnings += CatalogWarning(
            CatalogWarningId.VIEW,
            "${exercise.name} needs a ${exercise.requiredView.label()} view and this was recorded " +
                "${recorded.label()}. The patient will be calibrated against the way you recorded, " +
                "not the way this exercise should be seen.",
        )
    }

    val dominant = result.dominant
    if (dominant != null && dominant !in exercise.expectedDominant) {
        warnings += CatalogWarning(
            CatalogWarningId.DOMINANT,
            "The biggest movement here was ${dominant.value}, not " +
                "${exercise.expectedDominant.joinToString(" or ") { it.value }}. Check the phone is " +
                "seeing the working limb.",
        )
    }

    val watched = result.watchList?.features?.map { it.id }?.toSet().orEmpty()
    val missing = exercise.compensationWatch - watched
    if (watched.isNotEmpty() && missing.isNotEmpty()) {
        warnings += CatalogWarning(
            CatalogWarningId.COMPENSATION,
            "Nothing will be said about ${missing.joinToString(", ") { it.value }} - it did not " +
                "vary in your recording, so there is no band to score it against.",
        )
    }

    return warnings
}

/** §3.3 conformance for the hold path, which produces a [HoldReference] and no manifest. */
fun checkAgainstCatalog(exercise: DemoExercise, reference: HoldReference): List<CatalogWarning> {
    if (exercise.mode != ExerciseMode.HOLD) {
        return listOf(
            CatalogWarning(
                CatalogWarningId.MODE,
                "${exercise.name} is a counted exercise. Record it with reps, not a hold.",
            ),
        )
    }

    val warnings = mutableListOf<CatalogWarning>()

    if (reference.dominant !in exercise.expectedDominant) {
        warnings += CatalogWarning(
            CatalogWarningId.DOMINANT,
            "The joint furthest from neutral was ${reference.dominant.value}, not " +
                "${exercise.expectedDominant.joinToString(" or ") { it.value }}.",
        )
    }

    val missing = exercise.compensationWatch - reference.watchList.toSet()
    if (missing.isNotEmpty()) {
        warnings += CatalogWarning(
            CatalogWarningId.COMPENSATION,
            "Nothing will be said about ${missing.joinToString(", ") { it.value }} during the hold.",
        )
    }

    return warnings
}

private fun CameraView.label(): String = when (this) {
    CameraView.SAGITTAL -> "side-on"
    CameraView.FRONTAL -> "front-on"
    CameraView.OBLIQUE -> "at an angle"
}

/** The manifest's `required_view` spelling, which is a string in §9.1. */
fun CameraView.manifestLabel(): String = when (this) {
    CameraView.FRONTAL -> "frontal"
    else -> "sagittal"
}
