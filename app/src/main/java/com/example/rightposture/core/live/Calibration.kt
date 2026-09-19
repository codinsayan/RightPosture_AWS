package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.BodyFrameStatus
import com.example.rightposture.core.pose.BodyFrameTracker
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.FeatureContext
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.classifyView
import com.example.rightposture.core.pose.contributingLandmarks
import com.example.rightposture.core.pose.viewRatio
import com.example.rightposture.core.pose.validFeatures
import kotlin.math.abs

/**
 * §6.20. "A gate, not a formality. Refuse to start until it passes."
 *
 * The seven checks, in the plan's own numbering. A code rather than free text for the same reason
 * [RepRejection] is: the same check has to be readable on a checklist and speakable, in whichever
 * language the patient chose.
 */
enum class CalibrationCheckId {
    VISIBILITY, FRAME_FILL, CENTERED, VIEW, UPRIGHT, LIGHTING, STATIONARY
}

data class CalibrationCheck(val id: CalibrationCheckId, val passed: Boolean)

/**
 * One frame's calibration read.
 *
 * The measured values are carried alongside the verdicts, not just the verdicts. A checklist that
 * says "the camera is angled wrong" and nothing else is undiagnosable on a phone: the tester cannot
 * tell a genuinely bad angle from a reference authored against the wrong required view.
 */
data class CalibrationState(
    val checks: List<CalibrationCheck>,
    val view: CameraView?,
    val requiredView: CameraView,
    /** §6.20.2's `shoulderWidth / torsoHeight`, null when it could not be measured. */
    val viewRatio: Double? = null,
    /** `|v_dom|` in phi per second, the same units [Params.V_STALL_PER_SECOND] is written in. */
    val dominantVelocity: Double? = null,
) {
    val ready: Boolean get() = checks.all { it.passed }
    fun passed(id: CalibrationCheckId) = checks.first { it.id == id }.passed

    /** One line for logcat, so a failing gate can be diagnosed from a device rather than guessed at. */
    fun diagnostic(): String {
        val failing = checks.filterNot { it.passed }.joinToString(",") { it.id.name }
        return "view=%s/%s ratio=%s vDom=%s failing=[%s]".format(
            view?.name ?: "-",
            requiredView.name,
            viewRatio?.let { "%.3f".format(it) } ?: "-",
            dominantVelocity?.let { "%.4f".format(it) } ?: "-",
            failing,
        )
    }
}

/** `RposeManifest.requiredView` is stored as a string; an unrecognised value defaults sagittal. */
fun requiredViewFromLabel(label: String): CameraView =
    if (label.equals("frontal", ignoreCase = true)) CameraView.FRONTAL else CameraView.SAGITTAL

/**
 * Drives all seven checks from the live frame stream. Not thread-safe, one instance per
 * calibration attempt, driven from the same analysis thread as [ExerciseEngine].
 *
 * **No lookahead**, same discipline as [PhaseTracker]: a check that was true a moment ago and is
 * not true now is not true. The two checks with a hold duration (1 and 7) track *since when* their
 * condition has been continuously true and reset that clock the instant it breaks, rather than
 * accumulating true frames - a patient who is visible for 1.9 s, blinks out for one frame, and is
 * visible for 1.9 s more has not been visible for 2.0 s.
 */
class CalibrationGate(
    private val requiredView: CameraView,
    private val dominant: FeatureId,
    watchedFeatures: List<FeatureId>,
    private val activeSide: Side,
    /**
     * `thetaPeak - thetaStart` from the manifest, in degrees.
     *
     * Check 7 is written as `|v_dom| < V_STALL`, and [Params.V_STALL_PER_SECOND] is a **phi** rate:
     * §6.12.3 compares it against `dphi/dt`, where phi is the movement normalised to its own range.
     * Comparing a raw degrees-per-second rate against it instead makes the bound tighter by exactly
     * the span - on a 90 degree exercise, 0.05 phi/s is 4.5 deg/s, and demanding 0.05 deg/s is a
     * stillness no living person produces, because landmark jitter alone exceeds it.
     */
    private val spanDegrees: Double,
) {
    /**
     * Check 1 is about raw landmark visibility, not the derived feature value, so it needs no body
     * frame - just which landmark indices the watch-listed features are built from.
     */
    private val watchedLandmarks =
        watchedFeatures.flatMap { contributingLandmarks(it, activeSide) }.distinct()

    private val bodyFrames = BodyFrameTracker()
    private var lastAngle: Float? = null
    private var lastTimestampNs: Long? = null
    private var velocity = 0.0
    private var velocityInitialised = false

    private var visibleSinceNs: Long? = null
    private var stationarySinceNs: Long? = null

    fun reset() {
        bodyFrames.reset()
        lastAngle = null
        lastTimestampNs = null
        velocity = 0.0
        velocityInitialised = false
        visibleSinceNs = null
        stationarySinceNs = null
    }

    /**
     * @param gravity the low-pass-filtered accelerometer reading in device coordinates, or null
     *   before the first sensor event arrives. Null is not "assume upright" - an unread sensor
     *   fails the check exactly like an unset value fails everything else in this pipeline (§6.6):
     *   abstention is not agreement.
     */
    fun update(frame: LandmarkFrame?, gravity: Vec3?, timestampNs: Long): CalibrationState {
        val uprightOk = uprightCheck(gravity)

        if (frame == null) {
            visibleSinceNs = null
            stationarySinceNs = null
            velocityInitialised = false
            lastAngle = null
            lastDominantVelocity = null
            lastTimestampNs = timestampNs
            return checklist(
                visibility = false, frameFill = false, centered = false,
                view = false, upright = uprightOk, lighting = false, stationary = false,
                observedView = null, ratio = null,
            )
        }

        val visibilityOk = watchedLandmarks.isNotEmpty() &&
            watchedLandmarks.all { frame.visibility[it] >= Params.CALIBRATION_VISIBILITY_MIN }
        visibleSinceNs = if (visibilityOk) (visibleSinceNs ?: timestampNs) else null
        val visibilityHeld = visibilityOk && heldFor(visibleSinceNs, timestampNs) >=
            Params.CALIBRATION_VISIBILITY_HOLD_SECONDS

        val box = boundingBox(frame)
        val frameFillOk = box != null &&
            box.height in Params.CALIBRATION_BBOX_HEIGHT_MIN..Params.CALIBRATION_BBOX_HEIGHT_MAX
        val band = (1.0 - Params.CALIBRATION_CENTER_BAND_FRACTION) / 2.0
        val centeredOk = box != null && box.centerX in band..(1.0 - band)

        val ratio = viewRatio(frame)
        val observedView = classifyView(frame)
        val viewOk = observedView == requiredView

        val lightingOk = frame.presence.map { it.toDouble() }.average() >= Params.CALIBRATION_PRESENCE_MIN

        val stationaryOk = updateStationary(frame, timestampNs)
        stationarySinceNs = if (stationaryOk) (stationarySinceNs ?: timestampNs) else null
        val stationaryHeld = stationaryOk &&
            heldFor(stationarySinceNs, timestampNs) >= Params.CALIBRATION_STATIONARY_SECONDS

        return checklist(
            visibility = visibilityHeld,
            frameFill = frameFillOk,
            centered = centeredOk,
            view = viewOk,
            upright = uprightOk,
            lighting = lightingOk,
            stationary = stationaryHeld,
            observedView = observedView,
            ratio = ratio,
        )
    }

    /** Most recent `|v_dom|` in phi per second, for the diagnostic line. */
    private var lastDominantVelocity: Double? = null

    /** Check 7: `|v_dom| < V_STALL`, the same bound, units and EMA shape as [PhaseTracker]. */
    private fun updateStationary(frame: LandmarkFrame, timestampNs: Long): Boolean {
        val body = bodyFrames.update(frame, timestampNs)
        val angle = if (body.status != BodyFrameStatus.INVALID) {
            validFeatures(frame, body, FeatureContext(activeSide, Vec3(0f, 1f, 0f)))[dominant]
        } else {
            null
        }
        val previousAngle = lastAngle
        val previousTimestampNs = lastTimestampNs
        lastAngle = angle
        lastTimestampNs = timestampNs

        if (angle == null || previousAngle == null || previousTimestampNs == null) {
            velocityInitialised = false
            lastDominantVelocity = null
            return false
        }
        val dt = (timestampNs - previousTimestampNs) / 1_000_000_000.0
        if (dt <= 0.0 || dt > Params.MAX_FRAME_GAP_SECONDS) {
            velocityInitialised = false
            lastDominantVelocity = null
            return false
        }
        // Normalised to the reference's own range before comparison, so this is dphi/dt exactly as
        // §6.12.3 means it. A reference whose dominant joint does not move cannot define phi at all;
        // 3.8 check 2 rejects such a reference, so reaching here with a zero span means one got
        // through, and abstaining beats dividing by zero.
        val span = kotlin.math.abs(spanDegrees)
        if (span < 1e-6) {
            velocityInitialised = false
            lastDominantVelocity = null
            return false
        }
        val instant = ((angle - previousAngle) / dt) / span
        velocity = if (!velocityInitialised) {
            velocityInitialised = true
            instant.toDouble()
        } else {
            val alpha = dt / (Params.VELOCITY_EMA_TAU_SECONDS + dt)
            velocity + alpha * (instant - velocity)
        }
        lastDominantVelocity = abs(velocity)
        return abs(velocity) < Params.V_STALL_PER_SECOND
    }

    private fun uprightCheck(gravity: Vec3?): Boolean {
        if (gravity == null) return false
        val magnitude = gravity.length()
        if (magnitude < 1e-3f) return false
        return abs(gravity.z / magnitude) < Params.CALIBRATION_UPRIGHT_MAX_RATIO
    }

    private fun heldFor(sinceNs: Long?, timestampNs: Long): Double =
        if (sinceNs == null) 0.0 else (timestampNs - sinceNs) / 1_000_000_000.0

    private fun checklist(
        visibility: Boolean,
        frameFill: Boolean,
        centered: Boolean,
        view: Boolean,
        upright: Boolean,
        lighting: Boolean,
        stationary: Boolean,
        observedView: CameraView?,
        ratio: Double?,
    ) = CalibrationState(
        checks = listOf(
            CalibrationCheck(CalibrationCheckId.VISIBILITY, visibility),
            CalibrationCheck(CalibrationCheckId.FRAME_FILL, frameFill),
            CalibrationCheck(CalibrationCheckId.CENTERED, centered),
            CalibrationCheck(CalibrationCheckId.VIEW, view),
            CalibrationCheck(CalibrationCheckId.UPRIGHT, upright),
            CalibrationCheck(CalibrationCheckId.LIGHTING, lighting),
            CalibrationCheck(CalibrationCheckId.STATIONARY, stationary),
        ),
        view = observedView,
        requiredView = requiredView,
        viewRatio = ratio,
        dominantVelocity = lastDominantVelocity,
    )
}

private data class Box(val height: Double, val centerX: Double)

/** Over landmarks confident enough to trust their position, per §6.20.1 check 2/3. */
private fun boundingBox(frame: LandmarkFrame): Box? {
    val points = frame.imageLandmarks.indices
        .filter { frame.presence[it] >= Params.CALIBRATION_BOX_PRESENCE_MIN }
        .map { frame.imageLandmarks[it] }
    if (points.size < 4) return null
    val minX = points.minOf { it.x }.toDouble()
    val maxX = points.maxOf { it.x }.toDouble()
    val minY = points.minOf { it.y }.toDouble()
    val maxY = points.maxOf { it.y }.toDouble()
    val height = maxY - minY
    if (!height.isFinite() || height <= 0.0) return null
    return Box(height = height, centerX = (minX + maxX) / 2.0)
}

/**
 * §6.20.2's last line and §8.2's "phone bumped": both are the same event from the patient's side -
 * the framing that calibration approved no longer holds - so they are one state machine.
 *
 * Deliberately sparse: the view is sampled once every [Params.CALIBRATION_RECHECK_SECONDS] rather
 * than every frame, so one frame of pose noise cannot pause a set on its own; a real bump is a
 * change in what the camera is looking at, which is still there five seconds later.
 */
class ViewRecheck(private val requiredView: CameraView) {
    private var lastSampleNs: Long? = null
    private var recalibrating = false
    private var matchSinceNs: Long? = null

    val isRecalibrating get() = recalibrating

    fun reset() {
        lastSampleNs = null
        recalibrating = false
        matchSinceNs = null
    }

    /**
     * Once per frame. Returns whether the set should be paused for recalibration - which, once
     * true, stays true until the patient has held the required view again for
     * [Params.CALIBRATION_RECALIBRATE_HOLD_SECONDS].
     */
    fun update(frame: LandmarkFrame?, timestampNs: Long): Boolean {
        if (recalibrating) {
            val view = frame?.let(::classifyView)
            matchSinceNs = if (view == requiredView) (matchSinceNs ?: timestampNs) else null
            val held = matchSinceNs != null &&
                (timestampNs - matchSinceNs!!) / 1_000_000_000.0 >= Params.CALIBRATION_RECALIBRATE_HOLD_SECONDS
            if (held) {
                recalibrating = false
                matchSinceNs = null
                lastSampleNs = timestampNs
            }
            return recalibrating
        }

        val last = lastSampleNs
        if (last != null && (timestampNs - last) / 1_000_000_000.0 < Params.CALIBRATION_RECHECK_SECONDS) {
            return false
        }
        lastSampleNs = timestampNs
        // Only a confident, wrong classification counts as drift. A frame we cannot classify at
        // all - the patient mid-stride, a momentary occlusion - is not evidence the phone moved.
        val view = frame?.let(::classifyView)
        if (view != null && view != requiredView) recalibrating = true
        return recalibrating
    }
}
