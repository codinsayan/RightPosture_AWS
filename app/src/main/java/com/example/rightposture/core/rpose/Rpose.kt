package com.example.rightposture.core.rpose

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.reference.BodyPosition
import com.example.rightposture.core.reference.CheckOutcome
import com.example.rightposture.core.live.HoldFeatureReference
import com.example.rightposture.core.live.HoldReference
import com.example.rightposture.core.pose.GHOST_LANDMARKS
import com.example.rightposture.core.reference.FeatureReference
import com.example.rightposture.core.reference.PhaseTiming
import com.example.rightposture.core.reference.SkeletonReference
import com.example.rightposture.core.reference.Reference
import com.example.rightposture.core.reference.ValidationResult
import com.example.rightposture.core.reference.ValidatorCheck
import com.example.rightposture.core.reference.WatchList
import com.example.rightposture.core.reference.WatchedFeature
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * §9. The `.rpose` bundle: a zip, not a flat file. It is a portable clinical prescription.
 *
 * Five JSON entries are required. `demo.mp4`, `hold.json` and `skeleton.json` are optional; the first
 * is opaque bytes, the second is present exactly when the manifest says the bundle is a §6.19 hold,
 * and the third carries the ghost's reference when the recording could support one (10.4).
 */

/**
 * Bumped from 1.0 for `hold.json`.
 *
 * A hold bundle read by a 1.0 build would parse field by field - the manifest is unchanged in
 * shape - and produce an exercise with an empty reference and no hold data, which fails silently in
 * front of a patient. The version is what turns that into a refusal.
 */
const val RPOSE_FORMAT_VERSION = "1.1"

/**
 * Versions this build reads. Writing is always [RPOSE_FORMAT_VERSION].
 *
 * 1.0 stays readable because every bundle already on a phone is one: `hold.json` is additive, and a
 * bundle without it is a cyclic bundle, which is exactly what 1.0 could only ever have been.
 */
private val READABLE_FORMAT_VERSIONS = setOf("1.0", RPOSE_FORMAT_VERSION)

const val RPOSE_MANIFEST = "manifest.json"
const val RPOSE_REFERENCE = "reference.json"
const val RPOSE_FAULTS = "faults.json"
const val RPOSE_GOALS = "goals.json"
const val RPOSE_VALIDATION = "validation.json"
const val RPOSE_DEMO = "demo.mp4"

/** §6.19's reference. Present only when `manifest.mode` is `hold`, and required when it is. */
const val RPOSE_HOLD = "hold.json"

/**
 * 10.4. The physio's body and timing over one rep, for a ghost to draw. Optional in both
 * directions: absent from every bundle written before it existed, and from one whose recording
 * could not support it. Absence means the exercise is scored but not demonstrated.
 *
 * Added **without** a format bump, unlike [RPOSE_HOLD]. That bump was needed because a 1.0 reader
 * would have read a hold bundle as an empty cyclic one and failed in front of a patient. A reader
 * that predates this entry ignores it - [parseRpose] reads named entries only - and scores the
 * exercise exactly as before. Bumping would instead make every build without this code refuse
 * every newly authored exercise, including the other track's builds pulling from Supabase.
 */
const val RPOSE_SKELETON = "skeleton.json"

private val REQUIRED_ENTRIES =
    listOf(RPOSE_MANIFEST, RPOSE_REFERENCE, RPOSE_FAULTS, RPOSE_GOALS, RPOSE_VALIDATION)

// ---------------------------------------------------------------------------------------------
// manifest.json (§9.1)
// ---------------------------------------------------------------------------------------------

data class RposeCapture(
    val fpsNominal: Int,
    val frames: Int,
    val durationSeconds: Double,
    val device: String,
)

data class RposeSegmentation(
    val repsDetected: Int,
    val repsUsed: Int,
    val periodSeconds: Double,
    val dominantFeature: FeatureId,
)

data class RposePhase(
    val bins: Int,
    val thetaStart: Double,
    val thetaPeak: Double,
    /** §6.13.3 critical phase window, inclusive, in phase units. */
    val criticalWindow: Pair<Int, Int>,
    val vRef: Double,
)

data class RposePrescription(
    val reps: Int,
    val sets: Int,
    val restSeconds: Int,
    /** Null for cyclic exercises; §6.3 hold mode sets it. */
    val holdSeconds: Int?,
)

data class RposeManifest(
    val id: String,
    val version: Int,
    val name: String,
    val createdBy: String,
    /** ISO-8601 UTC, as written by the caller. Kept verbatim so a bundle byte-compares. */
    val createdAt: String,
    val mode: String,
    val referenceSource: String,
    val bodyPosition: String,
    val laterality: String,
    /**
     * Which limb the `*_ACTIVE` features were measured on, "left" or "right". The feature ids do not
     * carry it, so a bundle that omits it cannot be scored against without guessing.
     */
    val activeSide: String,
    /**
     * §6.16's rotation sign per feature, measured off the reference recording. Empty for an older
     * bundle, which draws no arrow rather than guessing.
     */
    val arrowSigns: Map<FeatureId, Int> = emptyMap(),
    val requiredView: String,
    val cameraFacing: String,
    val mirrored: Boolean,
    val capture: RposeCapture,
    val segmentation: RposeSegmentation,
    val phase: RposePhase,
    val prescription: RposePrescription,
    val formatVersion: String = RPOSE_FORMAT_VERSION,
)

/**
 * §9.6. Bundles are immutable: an edit produces a new file at `version + 1` under the same `id`, and
 * the old one stays. A patient mid-programme is never disrupted by an edit, and a session record can
 * always point at exactly the reference it was scored against.
 */
fun RposeManifest.nextVersion(): RposeManifest = copy(version = version + 1)

// ---------------------------------------------------------------------------------------------
// faults.json (§9.3) and goals.json (§9.4)
// ---------------------------------------------------------------------------------------------

data class FaultCondition(val feature: FeatureId, val sign: String, val zOn: Double)

data class FaultDefinition(
    val id: String,
    val conditions: List<FaultCondition>,
    val phaseRange: Pair<Int, Int>,
    val priority: Double,
    val maskedBy: List<String>,
    val cueShort: String,
    val cueFull: String,
    val cueAlt: String,
    val arrowJoint: String,
    val arrowAxis: String,
    val generatedBy: String,
    val validated: Boolean,
)

data class FaultSet(
    val faults: List<FaultDefinition>,
    val maskRulesVersion: String = "builtin_v1",
)

data class GoalMetrics(val romPeak: Double?, val qMean: Double?, val repsAtQuality: Int?) {
    companion object {
        /** §9.4: `baseline` stays null until the patient's session-1 measurement (§6.20.3). */
        val EMPTY = GoalMetrics(null, null, null)
    }
}

data class GoalWeights(val romPeak: Double, val qMean: Double, val repsAtQuality: Double)

data class Goals(
    val baseline: GoalMetrics,
    val goal: GoalMetrics,
    val weights: GoalWeights,
    val baselineCaptured: Boolean,
)

// ---------------------------------------------------------------------------------------------
// The bundle
// ---------------------------------------------------------------------------------------------

data class RposeBundle(
    val manifest: RposeManifest,
    val reference: Reference,
    val watchList: WatchList,
    /** §9.2 `mean_visibility`, per feature over the authoring recording. */
    val meanVisibility: Map<FeatureId, Float>,
    val faults: FaultSet,
    val goals: Goals,
    val validation: ValidationResult,
    /**
     * §6.19's hold reference, or null for a cyclic bundle.
     *
     * The live type rather than a parallel wire type, for the same reason [reference] and
     * [watchList] are: one definition of what a hold reference is, so a field cannot be added to
     * the maths and quietly left out of the file.
     *
     * A hold carries no phase trajectory, so [reference] is empty for these - all of a hold's
     * per-joint numbers live here.
     */
    val hold: HoldReference? = null,
    /**
     * 10.4. The ghost's reference, or null when this exercise is scored but not demonstrated.
     *
     * Last, because [parseRpose] constructs this positionally. Deliberately unchecked in `init`:
     * shape validation lives in the parse helper, where it can name the file, and a `require` here
     * would fire for every in-memory fixture.
     */
    val skeleton: SkeletonReference? = null,
) {
    init {
        // §9's bundle is a prescription, and a prescription that disagrees with itself about what
        // kind of exercise it is cannot be safely acted on. Checked in the constructor so a bundle
        // assembled in memory fails at the same place one read off disk does.
        require((manifest.mode == HOLD_MODE) == (hold != null)) {
            if (hold == null) {
                "manifest.mode is \"" + HOLD_MODE + "\" but the bundle carries no hold reference"
            } else {
                "the bundle carries a hold reference but manifest.mode is \"" + manifest.mode + "\""
            }
        }
    }
}

/** The one value of `manifest.mode` that means §6.19, spelled once. */
const val HOLD_MODE = "hold"

/** The other one. Cyclic is the default a bundle without a mode could only have been. */
const val CYCLIC_MODE = "cyclic"

// ---------------------------------------------------------------------------------------------
// Write
// ---------------------------------------------------------------------------------------------

/** The five required entries as JSON text, keyed by their name inside the zip. */
fun RposeBundle.entries(): Map<String, String> = linkedMapOf<String, String>(
    RPOSE_MANIFEST to jsonWrite(manifestJson()),
    RPOSE_REFERENCE to jsonWrite(referenceJson()),
    RPOSE_FAULTS to jsonWrite(faultsJson()),
    RPOSE_GOALS to jsonWrite(goalsJson()),
    RPOSE_VALIDATION to jsonWrite(validationJson()),
).apply {
    hold?.let { put(RPOSE_HOLD, jsonWrite(holdJson(it))) }
    skeleton?.let { put(RPOSE_SKELETON, jsonWrite(skeletonJson(it))) }
}

/**
 * 10.4's `skeleton.json`.
 *
 * Flat arrays with a documented stride, every number rounded to four decimals. `Json.kt` has no
 * nested-array reader and writes each number through `Double.toString()`, so a widened Float costs
 * about seventeen characters; nested and unrounded this entry would run to a few hundred kilobytes
 * parsed on every exercise open. In torso lengths the fourth decimal is about a twentieth of a
 * millimetre on an adult, far below anything MediaPipe resolves.
 *
 * Layout, with `L = landmarks.size` and `B = bins`:
 * - `positions_torso`: `B * L * 3`, index `(bin * L + slot) * 3 + axis`, axes x y z of §6.4's body
 *   frame, in torso lengths.
 * - `spread_torso`, `min_visibility`: `B * L`, index `bin * L + slot`.
 * - `fresh`: `B`.
 * - `phase_by_time`: phase at evenly spaced instants over `rep_duration_s`.
 */
private fun skeletonJson(skeleton: SkeletonReference): Map<String, Any?> = linkedMapOf(
    "landmarks" to skeleton.landmarks,
    "bins" to skeleton.binCount,
    "rep_count" to skeleton.repCount,
    "positions_torso" to skeleton.positions.map(::quantised),
    "spread_torso" to skeleton.spread.map(::quantised),
    "min_visibility" to skeleton.visibility.map(::quantised),
    "fresh" to skeleton.fresh,
    "phase_by_time" to skeleton.timing.phaseByTime.map(::quantised),
    "rep_duration_s" to quantised(skeleton.timing.repDurationSeconds),
)

/**
 * Four decimals. Rounding is monotonic, so a non-decreasing phase profile stays non-decreasing
 * through it - the load check below depends on that.
 */
private fun quantised(value: Float): Double = quantised(value.toDouble())

private fun quantised(value: Double): Double = Math.round(value * 10_000.0) / 10_000.0

/**
 * §6.19's reference, as JSON.
 *
 * Angles carry their unit in the key. Everything here is degrees except [HoldFeatureReference.salience],
 * which is `1/degrees`, and a reader that guessed radians would be wrong by a factor of 57 without
 * anything looking malformed.
 */
private fun holdJson(hold: HoldReference): Map<String, Any?> = linkedMapOf(
    "dominant" to hold.dominant.value,
    "stable_window_s" to hold.stableWindowSeconds,
    "watch_list" to hold.watchList.map { it.value },
    "features" to hold.features.entries.associate { (id, feature) ->
        id.value to linkedMapOf<String, Any?>(
            "mean_deg" to feature.mean,
            "sigma_deg" to feature.sigma,
            "offset_from_neutral_deg" to feature.offsetFromNeutral,
            "salience" to feature.salience,
        )
    },
)

fun writeRpose(bundle: RposeBundle, out: OutputStream, demoVideo: ByteArray? = null) {
    // Not closed here: the caller owns the stream it handed us.
    val zip = ZipOutputStream(out)
    bundle.entries().forEach { (name, text) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    if (demoVideo != null) {
        zip.putNextEntry(ZipEntry(RPOSE_DEMO))
        zip.write(demoVideo)
        zip.closeEntry()
    }
    zip.finish()
    zip.flush()
}

/**
 * §9.6 immutability, enforced rather than documented. Overwriting a bundle in place would silently
 * change what an already-recorded session was scored against; a new version gets a new file.
 */
fun writeRposeFile(bundle: RposeBundle, file: File, demoVideo: ByteArray? = null) {
    require(!file.exists()) {
        ".rpose bundles are immutable; " + file.name + " already exists. Write a new version instead."
    }
    file.outputStream().use { writeRpose(bundle, it, demoVideo) }
}

private fun RposeBundle.manifestJson(): Map<String, Any?> = linkedMapOf(
    "format_version" to manifest.formatVersion,
    "id" to manifest.id,
    "version" to manifest.version,
    "name" to manifest.name,
    "created_by" to manifest.createdBy,
    "created_at" to manifest.createdAt,
    "mode" to manifest.mode,
    "reference_source" to manifest.referenceSource,
    "body_position" to manifest.bodyPosition,
    "laterality" to manifest.laterality,
    "active_side" to manifest.activeSide,
    "arrow_signs" to manifest.arrowSigns.entries.associate { it.key.value to it.value },
    "required_view" to manifest.requiredView,
    "camera_facing" to manifest.cameraFacing,
    "mirrored" to manifest.mirrored,
    "capture" to linkedMapOf(
        "fps_nominal" to manifest.capture.fpsNominal,
        "frames" to manifest.capture.frames,
        "duration_s" to manifest.capture.durationSeconds,
        "device" to manifest.capture.device,
    ),
    "segmentation" to linkedMapOf(
        "reps_detected" to manifest.segmentation.repsDetected,
        "reps_used" to manifest.segmentation.repsUsed,
        "period_s" to manifest.segmentation.periodSeconds,
        "dominant_feature" to manifest.segmentation.dominantFeature.value,
    ),
    "phase" to linkedMapOf(
        "bins" to manifest.phase.bins,
        "theta_start" to manifest.phase.thetaStart,
        "theta_peak" to manifest.phase.thetaPeak,
        "critical_window" to listOf(manifest.phase.criticalWindow.first, manifest.phase.criticalWindow.second),
        "v_ref" to manifest.phase.vRef,
    ),
    "watch_list" to watchList.features.map {
        linkedMapOf<String, Any?>(
            "feature" to it.id.value,
            "weight" to it.weight,
            "salience" to it.salience,
        )
    },
    // Not in the §9.1 example. The 3.8 review screen shows a physio *why* a joint was dropped, and
    // that explanation is worth as much when the bundle is reopened a week later as when it was
    // recorded. A silently shorter watch list tells them nothing.
    "watch_list_rejected" to watchList.rejected.entries.associate { it.key.value to it.value },
    "prescription" to linkedMapOf(
        "reps" to manifest.prescription.reps,
        "sets" to manifest.prescription.sets,
        "rest_s" to manifest.prescription.restSeconds,
        "hold_s" to manifest.prescription.holdSeconds,
    ),
)

private fun RposeBundle.referenceJson(): Map<String, Any?> = linkedMapOf(
    // §9.2 lists only "features". `rep_count` and `body_position` are added because a reference
    // cannot be reconstructed without them, and re-deriving them from the manifest would let the
    // two disagree.
    "rep_count" to reference.repCount,
    "body_position" to reference.bodyPosition.name.lowercase(),
    "features" to reference.features.entries.associate { (id, feature) ->
        id.value to linkedMapOf<String, Any?>(
            "mu" to feature.mean.map { it.toDouble() },
            "sigma" to feature.sigma.map { it.toDouble() },
            "dmu" to feature.derivative.map { it.toDouble() },
            "rom" to feature.rom,
            "sigma_floor" to sigmaFloorOf(id, feature),
            // §6.10 filter 2 and §6.11 check 4 both read the pre-clamp median. Recomputing it from
            // the stored band is impossible - the clamp destroys it - so it is persisted.
            "median_sigma_raw" to feature.medianSigmaRaw,
            "mean_visibility" to (meanVisibility[id]?.toDouble() ?: 0.0),
        )
    },
)

/** §6.9.4 floor actually applied to this feature. Informational; the band already has it baked in. */
private fun sigmaFloorOf(id: FeatureId, feature: FeatureReference): Double = maxOf(
    if (id == FeatureId.LUMBAR_FLEXION) Params.LUMBAR_SIGMA_FLOOR_DEGREES else Params.SIGMA_FLOOR_DEGREES,
    Params.SIGMA_FLOOR_ROM_FRACTION * feature.rom,
)

private fun RposeBundle.faultsJson(): Map<String, Any?> = linkedMapOf(
    "faults" to faults.faults.map { fault ->
        linkedMapOf<String, Any?>(
            "id" to fault.id,
            "conditions" to fault.conditions.map {
                linkedMapOf<String, Any?>(
                    "feature" to it.feature.value,
                    "sign" to it.sign,
                    "z_on" to it.zOn,
                )
            },
            "phase_range" to listOf(fault.phaseRange.first, fault.phaseRange.second),
            "priority" to fault.priority,
            "masked_by" to fault.maskedBy,
            "cue_short" to fault.cueShort,
            "cue_full" to fault.cueFull,
            "cue_alt" to fault.cueAlt,
            "arrow_joint" to fault.arrowJoint,
            "arrow_axis" to fault.arrowAxis,
            "generated_by" to fault.generatedBy,
            "validated" to fault.validated,
        )
    },
    "mask_rules_version" to faults.maskRulesVersion,
)

private fun RposeBundle.goalsJson(): Map<String, Any?> = linkedMapOf(
    "baseline" to metricsJson(goals.baseline),
    "goal" to metricsJson(goals.goal),
    "weights" to linkedMapOf<String, Any?>(
        "rom_peak" to goals.weights.romPeak,
        "q_mean" to goals.weights.qMean,
        "reps_at_quality" to goals.weights.repsAtQuality,
    ),
    "baseline_captured" to goals.baselineCaptured,
)

private fun metricsJson(metrics: GoalMetrics): Map<String, Any?> = linkedMapOf(
    "rom_peak" to metrics.romPeak,
    "q_mean" to metrics.qMean,
    "reps_at_quality" to metrics.repsAtQuality,
)

private fun RposeBundle.validationJson(): Map<String, Any?> = linkedMapOf(
    // §9.5's example row is `{ id, pass, value, threshold }`. A boolean `pass` cannot express
    // §6.11's third outcome, NOT_EVALUATED, and dropping the physio-facing message would make the
    // stored record less useful than the screen that produced it - which defeats the stated purpose
    // of persisting it at all. The check's own fields are stored instead.
    "checks" to validation.checks.map {
        linkedMapOf<String, Any?>(
            "id" to it.number,
            "name" to it.name,
            "outcome" to it.outcome.name.lowercase(),
            "pass" to (it.outcome == CheckOutcome.PASS),
            "message" to it.message,
            "detail" to it.detail,
        )
    },
    "accepted" to validation.accepted,
)

// ---------------------------------------------------------------------------------------------
// Read
// ---------------------------------------------------------------------------------------------

fun readRpose(input: InputStream): RposeBundle = parseRpose(readEntries(input).first)

/** The optional `demo.mp4`, or null when the bundle does not carry one yet. */
fun readRposeDemoVideo(input: InputStream): ByteArray? = readEntries(input).second

private fun readEntries(input: InputStream): Pair<Map<String, String>, ByteArray?> {
    val texts = mutableMapOf<String, String>()
    var demo: ByteArray? = null
    val zip = ZipInputStream(input)
    while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.isDirectory) continue
        val bytes = zip.readAllBytesCompat()
        if (entry.name == RPOSE_DEMO) demo = bytes else texts[entry.name] = String(bytes, Charsets.UTF_8)
    }
    return texts to demo
}

/** `InputStream.readBytes` allocates per call but the entries are kilobytes; minSdk 24 has no readAllBytes. */
private fun ZipInputStream.readAllBytesCompat(): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private val FEATURE_BY_VALUE = FeatureId.entries.associateBy { it.value }

private fun featureId(value: String): FeatureId =
    FEATURE_BY_VALUE[value] ?: throw IllegalArgumentException("Unknown feature id: " + value)

internal fun parseRpose(texts: Map<String, String>): RposeBundle {
    REQUIRED_ENTRIES.forEach {
        require(texts.containsKey(it)) { "Bundle is missing " + it }
    }

    val manifestJson = jsonParse(texts.getValue(RPOSE_MANIFEST)).asObject(RPOSE_MANIFEST)

    // Version is checked before anything else is read. A bundle written by a future build may look
    // parseable field by field and still mean something different; guessing at it is worse than
    // refusing it.
    val formatVersion = manifestJson["format_version"] as? String
        ?: throw IllegalArgumentException("manifest.json has no format_version")
    require(formatVersion in READABLE_FORMAT_VERSIONS) {
        "Unsupported .rpose format_version " + formatVersion + "; this build reads " +
            READABLE_FORMAT_VERSIONS.joinToString(", ")
    }

    val capture = manifestJson.obj("capture")
    val segmentation = manifestJson.obj("segmentation")
    val phase = manifestJson.obj("phase")
    val prescription = manifestJson.obj("prescription")
    val criticalWindow = phase.list("critical_window")
    require(criticalWindow.size == 2) { "phase.critical_window must have two entries" }

    val manifest = RposeManifest(
        id = manifestJson.str("id"),
        version = manifestJson.int("version"),
        name = manifestJson.str("name"),
        createdBy = manifestJson.str("created_by"),
        createdAt = manifestJson.str("created_at"),
        mode = manifestJson.str("mode"),
        referenceSource = manifestJson.str("reference_source"),
        bodyPosition = manifestJson.str("body_position"),
        laterality = manifestJson.str("laterality"),
        // Bundles written before the field existed measured whatever Side.RIGHT resolved to, which
        // is the same default the live engine used, so reading them back keeps their old behaviour.
        activeSide = manifestJson.strOrNull("active_side") ?: "right",
        arrowSigns = (manifestJson["arrow_signs"] as? Map<*, *>).orEmpty().mapNotNull { (key, value) ->
            val id = FeatureId.entries.firstOrNull { it.value == key } ?: return@mapNotNull null
            val sign = (value as? Double)?.toInt() ?: return@mapNotNull null
            if (sign == 1 || sign == -1) id to sign else null
        }.toMap(),
        requiredView = manifestJson.str("required_view"),
        cameraFacing = manifestJson.str("camera_facing"),
        mirrored = manifestJson.bool("mirrored"),
        capture = RposeCapture(
            fpsNominal = capture.int("fps_nominal"),
            frames = capture.int("frames"),
            durationSeconds = capture.num("duration_s"),
            device = capture.str("device"),
        ),
        segmentation = RposeSegmentation(
            repsDetected = segmentation.int("reps_detected"),
            repsUsed = segmentation.int("reps_used"),
            periodSeconds = segmentation.num("period_s"),
            dominantFeature = featureId(segmentation.str("dominant_feature")),
        ),
        phase = RposePhase(
            bins = phase.int("bins"),
            thetaStart = phase.num("theta_start"),
            thetaPeak = phase.num("theta_peak"),
            criticalWindow = (criticalWindow[0] as Double).toInt() to (criticalWindow[1] as Double).toInt(),
            vRef = phase.num("v_ref"),
        ),
        prescription = RposePrescription(
            reps = prescription.int("reps"),
            sets = prescription.int("sets"),
            restSeconds = prescription.int("rest_s"),
            holdSeconds = prescription.intOrNull("hold_s"),
        ),
        formatVersion = formatVersion,
    )

    val watchList = WatchList(
        features = manifestJson.list("watch_list").map {
            val entry = it.asObject("watch_list entry")
            WatchedFeature(
                id = featureId(entry.str("feature")),
                salience = entry.num("salience"),
                weight = entry.num("weight"),
            )
        },
        rejected = manifestJson["watch_list_rejected"]
            .let { if (it == null) emptyMap() else it.asObject("watch_list_rejected") }
            .entries.associate { featureId(it.key) to it.value.toString() },
    )

    val referenceJson = jsonParse(texts.getValue(RPOSE_REFERENCE)).asObject(RPOSE_REFERENCE)
    val featuresJson = referenceJson.obj("features")
    val meanVisibility = mutableMapOf<FeatureId, Float>()
    val features = featuresJson.entries.associate { (key, value) ->
        val id = featureId(key)
        val feature = value.asObject(key)
        val mean = feature.floats("mu")
        val sigma = feature.floats("sigma")
        val derivative = feature.floats("dmu")
        // §6.8.4 fixes the grid at 101 bins. A reference of any other length would index silently
        // wrong at runtime rather than fail, so it is rejected on load.
        listOf("mu" to mean, "sigma" to sigma, "dmu" to derivative).forEach { (name, values) ->
            require(values.size == Params.PHASE_BINS) {
                key + "." + name + " has " + values.size + " values; " + Params.PHASE_BINS + " required"
            }
        }
        meanVisibility[id] = feature.num("mean_visibility").toFloat()
        id to FeatureReference(
            mean = mean,
            sigma = sigma,
            derivative = derivative,
            rom = feature.num("rom"),
            medianSigmaRaw = feature.num("median_sigma_raw"),
        )
    }

    val reference = Reference(
        features = features,
        repCount = referenceJson.int("rep_count"),
        bodyPosition = BodyPosition.valueOf(referenceJson.str("body_position").uppercase()),
    )

    val faultsJson = jsonParse(texts.getValue(RPOSE_FAULTS)).asObject(RPOSE_FAULTS)
    val faults = FaultSet(
        faults = faultsJson.list("faults").map {
            val fault = it.asObject("fault")
            val range = fault.list("phase_range")
            require(range.size == 2) { "fault.phase_range must have two entries" }
            FaultDefinition(
                id = fault.str("id"),
                conditions = fault.list("conditions").map { raw ->
                    val condition = raw.asObject("condition")
                    FaultCondition(
                        feature = featureId(condition.str("feature")),
                        sign = condition.str("sign"),
                        zOn = condition.num("z_on"),
                    )
                },
                phaseRange = (range[0] as Double).toInt() to (range[1] as Double).toInt(),
                priority = fault.num("priority"),
                maskedBy = fault.strings("masked_by"),
                cueShort = fault.str("cue_short"),
                cueFull = fault.str("cue_full"),
                cueAlt = fault.str("cue_alt"),
                arrowJoint = fault.str("arrow_joint"),
                arrowAxis = fault.str("arrow_axis"),
                generatedBy = fault.str("generated_by"),
                validated = fault.bool("validated"),
            )
        },
        maskRulesVersion = faultsJson.str("mask_rules_version"),
    )

    val goalsJson = jsonParse(texts.getValue(RPOSE_GOALS)).asObject(RPOSE_GOALS)
    val weights = goalsJson.obj("weights")
    val goals = Goals(
        baseline = parseMetrics(goalsJson.obj("baseline")),
        goal = parseMetrics(goalsJson.obj("goal")),
        weights = GoalWeights(
            romPeak = weights.num("rom_peak"),
            qMean = weights.num("q_mean"),
            repsAtQuality = weights.num("reps_at_quality"),
        ),
        baselineCaptured = goalsJson.bool("baseline_captured"),
    )

    val validationJson = jsonParse(texts.getValue(RPOSE_VALIDATION)).asObject(RPOSE_VALIDATION)
    val validation = ValidationResult(
        validationJson.list("checks").map {
            val check = it.asObject("check")
            ValidatorCheck(
                number = check.int("id"),
                name = check.str("name"),
                outcome = CheckOutcome.valueOf(check.str("outcome").uppercase()),
                message = check.str("message"),
                detail = check.str("detail"),
            )
        },
    )

    // The invariant is RposeBundle's to enforce, and it does. Reaching it needs the entry parsed
    // first, and a mode of "hold" with no entry has to fail here rather than as a null dereference
    // three screens later.
    val holdText = texts[RPOSE_HOLD]
    require(manifest.mode != HOLD_MODE || holdText != null) {
        "manifest.mode is \"" + HOLD_MODE + "\" but the bundle has no " + RPOSE_HOLD
    }
    val hold = holdText?.let { parseHold(jsonParse(it).asObject(RPOSE_HOLD)) }
    val skeleton = texts[RPOSE_SKELETON]?.let { parseSkeleton(jsonParse(it).asObject(RPOSE_SKELETON)) }

    return RposeBundle(manifest, reference, watchList, meanVisibility, faults, goals, validation, hold, skeleton)
}

/**
 * Reads 10.4's entry, refusing anything that would index wrong or run the movement backwards.
 *
 * Every failure names the file. A truncated array would otherwise read as a skeleton that is fine
 * until the ghost reaches the missing bin, and a phase profile that goes backwards would play the
 * movement in reverse mid-rep - the file is the last place either can be caught.
 */
private fun parseSkeleton(json: Map<String, Any?>): SkeletonReference {
    fun check(condition: Boolean, message: () -> String) =
        require(condition) { RPOSE_SKELETON + ": " + message() }

    val landmarks = json.list("landmarks").map { (it as? Double)?.toInt() ?: -1 }
    // Poses are read as NormalisedPose, which is fixed to this build's landmark set. A different
    // set is a different build's skeleton, and silently misreading its slots would draw the wrong
    // joints.
    check(landmarks == GHOST_LANDMARKS) {
        "landmarks " + landmarks + " are not this build's set " + GHOST_LANDMARKS
    }
    val bins = json.int("bins")
    check(bins == Params.PHASE_BINS) { "has " + bins + " bins; " + Params.PHASE_BINS + " required" }

    val slots = landmarks.size
    val positions = json.floats("positions_torso")
    val spread = json.floats("spread_torso")
    val visibility = json.floats("min_visibility")
    val freshRaw = json.list("fresh").map { it as? Boolean }
    check(freshRaw.none { it == null }) { "fresh must hold only true or false" }
    val fresh = freshRaw.map { it!! }
    val phaseByTime = json.floats("phase_by_time")

    check(positions.size == bins * slots * 3) {
        "positions_torso has " + positions.size + " values; " + (bins * slots * 3) + " required"
    }
    check(spread.size == bins * slots) { "spread_torso has " + spread.size + " values; " + (bins * slots) + " required" }
    check(visibility.size == bins * slots) {
        "min_visibility has " + visibility.size + " values; " + (bins * slots) + " required"
    }
    check(fresh.size == bins) { "fresh has " + fresh.size + " values; " + bins + " required" }
    check((positions + spread + visibility + phaseByTime).all(Float::isFinite)) { "contains a non-finite number" }

    // PhaseTiming refuses a backwards profile or a non-positive duration itself; this only puts the
    // file's name on the refusal.
    val timing = try {
        PhaseTiming(phaseByTime, json.num("rep_duration_s"))
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException(RPOSE_SKELETON + ": " + e.message, e)
    }

    return SkeletonReference(
        landmarks = landmarks,
        positions = positions,
        spread = spread,
        visibility = visibility,
        fresh = fresh,
        repCount = json.int("rep_count"),
        timing = timing,
    )
}

private fun parseHold(json: Map<String, Any?>): HoldReference {
    val features = json.obj("features").entries.associate { (key, value) ->
        val id = featureId(key)
        val feature = value.asObject(key)
        id to HoldFeatureReference(
            feature = id,
            mean = feature.num("mean_deg"),
            sigma = feature.num("sigma_deg"),
            offsetFromNeutral = feature.num("offset_from_neutral_deg"),
            salience = feature.num("salience"),
        )
    }
    val watchList = json.strings("watch_list").map { featureId(it) }
    // §6.19 scores against the watch list, so a watched joint with no band behind it would divide
    // by a sigma that is not there. Caught on load, where the file can still be named.
    watchList.forEach {
        require(features.containsKey(it)) { "hold watch list names " + it.value + ", which has no reference" }
    }
    val dominant = featureId(json.str("dominant"))
    require(features.containsKey(dominant)) { "hold dominant joint " + dominant.value + " has no reference" }
    return HoldReference(
        features = features,
        dominant = dominant,
        watchList = watchList,
        stableWindowSeconds = json.num("stable_window_s"),
    )
}

private fun parseMetrics(json: Map<String, Any?>) = GoalMetrics(
    romPeak = json.numOrNull("rom_peak"),
    qMean = json.numOrNull("q_mean"),
    repsAtQuality = json.intOrNull("reps_at_quality"),
)
