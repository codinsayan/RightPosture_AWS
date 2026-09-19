package com.example.rightposture.core.reference

import com.example.rightposture.core.Params
import com.example.rightposture.core.live.HoldReference
import com.example.rightposture.core.live.criticalWindow
import com.example.rightposture.core.rpose.FaultSet
import com.example.rightposture.core.rpose.HOLD_MODE
import com.example.rightposture.core.rpose.GoalMetrics
import com.example.rightposture.core.rpose.GoalWeights
import com.example.rightposture.core.rpose.Goals
import com.example.rightposture.core.rpose.RposeBundle
import com.example.rightposture.core.rpose.RposeCapture
import com.example.rightposture.core.rpose.RposeManifest
import com.example.rightposture.core.rpose.RposePhase
import com.example.rightposture.core.rpose.RposePrescription
import com.example.rightposture.core.rpose.RposeSegmentation
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.Side
import kotlin.math.abs

/**
 * Turns an accepted 3.8 authoring result into the §9 bundle the live pipeline consumes.
 *
 * This is the join between the two halves of the app. Until now authoring produced a `Reference` and
 * the live side consumed an `RposeBundle`, and nothing connected them.
 *
 * The fault set is deliberately **empty**: fault definitions and their wording are 8.1 and 8.2. An
 * exercise with no faults still scores, counts reps and drives the quality bar; it simply has
 * nothing to say. Inventing placeholder faults here would put unvalidated clinical claims in front
 * of a patient, which §6.15.1 is emphatic about.
 */
fun bundleFromAuthoring(
    result: AuthoringResult,
    name: String,
    id: String,
    /**
     * Section 9.6 makes a bundle immutable, so an edit is a new version rather than a rewrite.
     * Defaults to 1 for the first recording of an id; the authoring screen passes the next version
     * when the physio saves under a name they have already used.
     */
    version: Int = 1,
    createdAt: String,
    createdBy: String = "local",
    device: String = "unknown",
    prescribedReps: Int = 12,
    sets: Int = 3,
    restSeconds: Int = 60,
    frames: Int = 0,
    durationSeconds: Double = 0.0,
    mirrored: Boolean = false,
    cameraFacing: String = "rear",
    bodyPositionLabel: String = "seated",
    /**
     * Defaults to the view the recording was made in. Passing an explicit value overrides that, but
     * nothing does: the physio records from where the exercise is visible, and that is the answer.
     */
    requiredView: String? = null,
): RposeBundle? {
    val reference = result.reference ?: return null
    val watchList = result.watchList ?: return null
    val validation = result.validation ?: return null
    val dominant = result.dominant ?: return null
    val dominantReference = reference.features[dominant] ?: return null
    val period = result.periodSeconds ?: return null

    val thetaStart = dominantReference.mean.first().toDouble()
    // The reference's own extremum, which is what §6.12.2 indexes phase against. Taken as the bin
    // furthest from the start value rather than the largest value, because a movement that decreases
    // (knee flexing rather than extending) has its extremum at the minimum.
    val peakBin = dominantReference.mean.indices.maxByOrNull {
        abs(dominantReference.mean[it] - dominantReference.mean.first())
    } ?: 0
    val thetaPeak = dominantReference.mean[peakBin].toDouble()

    return RposeBundle(
        manifest = RposeManifest(
            id = id,
            version = version,
            name = name,
            createdBy = createdBy,
            createdAt = createdAt,
            mode = "cyclic",
            referenceSource = "self",
            bodyPosition = bodyPositionLabel,
            laterality = "unilateral",
            // 6.2 resolved this from the recording. Dropping it here was letting the live engine
            // score the opposite limb against this reference.
            activeSide = if (result.activeSide == Side.LEFT) "left" else "right",
            // Measured off this recording, so it is correct for this camera, this view and this
            // patient's framing - none of which a constant table could have known.
            arrowSigns = result.arrowSigns,
            requiredView = requiredView
                ?: when (result.recordedView) {
                    CameraView.FRONTAL -> "frontal"
                    else -> "sagittal"
                },
            cameraFacing = cameraFacing,
            mirrored = mirrored,
            capture = RposeCapture(Params.NOMINAL_FPS, frames, durationSeconds, device),
            segmentation = RposeSegmentation(
                repsDetected = result.segmentation?.detected?.size ?: reference.repCount,
                repsUsed = reference.repCount,
                periodSeconds = period,
                dominantFeature = dominant,
            ),
            phase = RposePhase(
                bins = Params.PHASE_BINS,
                thetaStart = thetaStart,
                thetaPeak = thetaPeak,
                criticalWindow = criticalWindow(dominantReference).let { it.first to it.last },
                vRef = referenceVelocity(dominantReference, thetaPeak - thetaStart, period),
            ),
            prescription = RposePrescription(prescribedReps, sets, restSeconds, null),
        ),
        reference = reference,
        watchList = watchList,
        meanVisibility = result.meanVisibility,
        faults = FaultSet(emptyList()),
        goals = Goals(
            baseline = GoalMetrics.EMPTY,
            goal = GoalMetrics.EMPTY,
            weights = GoalWeights(0.5, 0.3, 0.2),
            baselineCaptured = false,
        ),
        validation = validation,
    )
}

/**
 * §6.13.2 `phase.v_ref`: the reference movement's own median `|dφ/dt|`, in φ per second.
 *
 * Phase advances 100 units over one reference period, and `derivative` is `dμ` per phase bin, so
 * `dφ/dt = (dμ/dp) · (100 / T) / span`. Deriving it here rather than storing a guess is what lets
 * 4.2 widen the tolerance when the patient moves slower than the physio did.
 */
internal fun referenceVelocity(
    dominant: FeatureReference,
    span: Double,
    periodSeconds: Double,
): Double {
    if (periodSeconds <= 0.0 || abs(span) < 1e-6) return 0.0
    val perSecond = dominant.derivative
        .map { abs(it.toDouble()) * (Params.PHASE_BINS - 1) / periodSeconds / abs(span) }
        .sorted()
    if (perSecond.isEmpty()) return 0.0
    val middle = perSecond.size / 2
    return if (perSecond.size % 2 == 1) {
        perSecond[middle]
    } else {
        (perSecond[middle - 1] + perSecond[middle]) / 2.0
    }
}

/**
 * Packages a §6.19 hold reference as a bundle, so a hold can be saved, versioned and prescribed the
 * way a cyclic recording already can.
 *
 * ## Why the fields a hold has no answer to are zero
 *
 * A hold has no reps, no period and no phase axis - §6.19 is explicit that the absence "is the whole
 * design". So [RposeSegmentation] and [RposePhase] are written as zeroes: not placeholders standing
 * in for numbers nobody measured, but the true count of reps in a recording that has none. The one
 * field that carries meaning is `dominantFeature`, which a hold does have and which §6.20's gate
 * reads.
 *
 * [RposeBundle.reference] is likewise empty. Every per-joint number a hold has - mean, band, offset
 * from neutral, salience - lives in the hold reference, and duplicating a subset of them into a
 * one-bin phase trajectory would create two places to disagree about the same angle.
 *
 * The manifest watch list is populated, because it is the one part of the cyclic shape a hold fills
 * honestly, and §6.20's calibration gate reads it without caring which mode produced it.
 */
fun bundleFromHold(
    hold: HoldReference,
    name: String,
    id: String,
    version: Int = 1,
    createdAt: String,
    /** Seconds the physio actually held the recorded pose. §9.1's `prescription.hold_s`. */
    holdSeconds: Int,
    createdBy: String = "local",
    device: String = "unknown",
    sets: Int = 3,
    restSeconds: Int = 60,
    frames: Int = 0,
    durationSeconds: Double = 0.0,
    mirrored: Boolean = false,
    cameraFacing: String = "rear",
    bodyPositionLabel: String = "standing",
    activeSide: Side = Side.RIGHT,
    requiredView: String = "sagittal",
    nonUpright: Boolean = false,
): RposeBundle {
    // §6.10's weight is salience normalised across the watch list, and §6.19 substitutes tightness
    // for range in the salience itself. The normalisation is the same either way, including the
    // degenerate branch: equal shares beat NaN weights that would poison every frame score.
    val watched = hold.watchList.mapNotNull { feature -> hold.features[feature]?.let { feature to it.salience } }
    val total = watched.sumOf { it.second }
    val features = if (total > 0.0 && total.isFinite()) {
        watched.map { (feature, salience) -> WatchedFeature(feature, salience, salience / total) }
    } else {
        watched.map { (feature, salience) -> WatchedFeature(feature, salience, 1.0 / watched.size) }
    }

    return RposeBundle(
        manifest = RposeManifest(
            id = id,
            version = version,
            name = name,
            createdBy = createdBy,
            createdAt = createdAt,
            mode = HOLD_MODE,
            referenceSource = "self",
            bodyPosition = bodyPositionLabel,
            laterality = "unilateral",
            activeSide = if (activeSide == Side.LEFT) "left" else "right",
            // §6.16's arrow is drawn along a reference trajectory. A hold has none, so no arrow is
            // drawn - which is the same thing an older bundle's empty map already means.
            arrowSigns = emptyMap(),
            requiredView = requiredView,
            cameraFacing = cameraFacing,
            mirrored = mirrored,
            capture = RposeCapture(Params.NOMINAL_FPS, frames, durationSeconds, device),
            segmentation = RposeSegmentation(
                repsDetected = 0,
                repsUsed = 0,
                periodSeconds = 0.0,
                dominantFeature = hold.dominant,
            ),
            phase = RposePhase(
                bins = 0,
                thetaStart = 0.0,
                thetaPeak = 0.0,
                criticalWindow = 0 to 0,
                vRef = 0.0,
            ),
            // One hold is one rep of it. The repetitions a physio prescribes are the sets, so
            // "three thirty-second wall sits" is sets = 3, holdSeconds = 30 - and `reps` stays the
            // 1 that keeps every count downstream honest rather than a 0 that reads as "none".
            prescription = RposePrescription(1, sets, restSeconds, holdSeconds),
        ),
        reference = Reference(
            features = emptyMap(),
            repCount = 0,
            bodyPosition = if (nonUpright) BodyPosition.NON_UPRIGHT else BodyPosition.UPRIGHT,
        ),
        watchList = WatchList(features, rejected = emptyMap()),
        meanVisibility = emptyMap(),
        // Empty for the same reason bundleFromAuthoring leaves it empty: fault wording is 8.1 and
        // 8.2, and inventing placeholders would put unvalidated clinical claims in front of a
        // patient.
        faults = FaultSet(emptyList()),
        goals = Goals(
            baseline = GoalMetrics.EMPTY,
            goal = GoalMetrics.EMPTY,
            weights = GoalWeights(0.5, 0.3, 0.2),
            baselineCaptured = false,
        ),
        // §6.11's checks are all statements about a phase-segmented recording; none of them can be
        // evaluated here. The gate a hold actually passes is §6.19's own stability precondition,
        // which `runHoldAuthoring` already applied before this function was reachable.
        validation = ValidationResult(emptyList()),
        hold = hold,
    )
}
