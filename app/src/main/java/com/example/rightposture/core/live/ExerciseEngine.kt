package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.BodyFrameStatus
import com.example.rightposture.core.pose.BodyFrameTracker
import com.example.rightposture.core.pose.FeatureContext
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.OneEuroLandmarkFilter
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.pose.ValidityTracker
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.validFeatures
import com.example.rightposture.core.rpose.RposeBundle
import com.example.rightposture.core.rpose.RposeManifest

/** The manifest stores the side as a string; an unrecognised value is treated as the old default. */
fun RposeManifest.activeSideOrDefault(): Side =
    if (activeSide.equals("left", ignoreCase = true)) Side.LEFT else Side.RIGHT

/**
 * §12.2. One frame in, one immutable [FrameResult] out.
 *
 * This is the first place the live half of the app exists as one thing rather than as seven tested
 * pieces. Everything below is pure and synchronous - §12.2 is explicit that there are no coroutines
 * in the hot path, because the camera thread is the only clock the pipeline has.
 */

/** What the UI is allowed to be in. §8.2 and §6.6 decide, not the screen. */
enum class ExerciseStatus {
    /** No person, or the body frame is unusable. Nothing may be claimed. */
    NO_PERSON,

    /** A watched joint has been out of sight long enough to matter. Cues are suppressed. */
    OCCLUDED,

    /** Between reps. */
    READY,

    /** Mid-rep and scoring. */
    TRACKING,

    /** The prescribed rep count has been reached. Nothing further is counted or cued. */
    SET_COMPLETE,
}

/**
 * The whole visible state of one frame. Immutable: §12.2 hands this to Compose, and a mutable frame
 * result read from a recomposition is a race by construction.
 */
data class FrameResult(
    val status: ExerciseStatus,
    val phaseState: PhaseState,
    /** 0-100 phase index, for the phase ring. */
    val phase: Double,
    /** `φ`, the raw 0-1.15 progress toward the reference extremum. Diagnostic readout only. */
    val phi: Double,
    /** `dφ/dt`. Diagnostic readout only. */
    val velocity: Double,
    /** 0-100 frame quality, or null when nothing valid could be scored. Never zero for "unknown". */
    val quality: Double?,
    val validReps: Int,
    val partialReps: Int,
    /** Set quality so far, median over valid reps. Null until a valid rep exists. */
    val setQuality: Double?,
    /** The rep that just finished, on the one frame it finished. */
    val completedRep: CompletedRep?,
    /**
     * Why the most recent rep did not count, held until another rep finishes.
     *
     * [completedRep] is live for exactly one frame and the UI samples at 33 ms, so it reads this
     * one in practice. Without it the counter simply stops moving and the patient is told nothing.
     */
    val lastRejection: RepRejection?,
    /** The single cue to show or speak, on the frame it was chosen. */
    val cue: Cue?,
    val activeFaultIds: List<String>,
    /** Faults that fired on this frame, for §12.5 onset haptics. Single-frame, like [completedRep]. */
    val faultOnsetIds: List<String>,
    /** Faults that cleared on this frame, for §12.5 resolution haptics. */
    val faultClearIds: List<String>,
    /** Landmarks after filtering and canonicalisation, for the skeleton overlay. */
    val frame: LandmarkFrame?,
    /** Features that scored this frame, so the overlay can colour the faulted segments. */
    val zScores: Map<FeatureId, FeatureZ>,
    /** Which limb the `*_ACTIVE` features were measured on, so §6.16 can find the right landmarks. */
    val activeSide: Side,
    /**
     * What was worth saying about the rep that just finished, held until the next one finishes.
     *
     * Separate from [lastRejection]: a rep can satisfy §6.12.4 and count, and still be worth a word.
     * Counting it silently teaches the patient the movement was fine when it was not.
     */
    val lastRepNote: RepNote?,
    /**
     * On a [RepRejection.SKIPPED] rep, the watch-listed joint that was furthest from the reference
     * pose - the step the patient left out. Null when the rep was refused for jumping the range
     * rather than for omitting a joint, so the message can say which of the two happened.
     */
    val skippedFeature: FeatureId? = null,
    /**
     * §6.18.6 fired on the rep that just finished, on the one frame it finished.
     *
     * Single-frame like [completedRep], because it is an event rather than a state: the set being
     * over is carried by [ExerciseStatus.SET_COMPLETE], and the *reason* is carried by
     * [ExerciseEngine.fatigue], which persists.
     */
    val safetyStop: Boolean = false,
) {
    companion object {
        val EMPTY = FrameResult(
            status = ExerciseStatus.NO_PERSON,
            phaseState = PhaseState.IDLE,
            phase = 0.0,
            phi = 0.0,
            velocity = 0.0,
            quality = null,
            validReps = 0,
            partialReps = 0,
            setQuality = null,
            completedRep = null,
            lastRejection = null,
            cue = null,
            activeFaultIds = emptyList(),
            faultOnsetIds = emptyList(),
            faultClearIds = emptyList(),
            frame = null,
            zScores = emptyMap(),
            activeSide = Side.RIGHT,
            lastRepNote = null,
            skippedFeature = null,
            safetyStop = false,
        )
    }
}

/**
 * Wires 1.2 through 4.5 against one `.rpose` bundle.
 *
 * Not thread-safe, and deliberately so: one engine per capture stream, driven from the analysis
 * thread. [onFrame] is the only entry point.
 */
class ExerciseEngine(
    private val bundle: RposeBundle,
    /**
     * Defaults to the side the bundle was authored on. The `*_ACTIVE` feature ids are side-agnostic,
     * so measuring the other limb against this reference yields a confident, wrong score - the
     * patient's moving limb sits at reference phase 0 forever and no rep ever completes.
     */
    private val activeSide: Side = bundle.manifest.activeSideOrDefault(),
    private val verticalWorld: Vec3? = Vec3(0f, 1f, 0f),
) {
    private val landmarkFilter = OneEuroLandmarkFilter()
    private val bodyFrames = BodyFrameTracker()
    private val validity = ValidityTracker()
    private val phaseTracker = PhaseTracker(
        PhaseConfig(
            thetaStart = bundle.manifest.phase.thetaStart,
            thetaPeak = bundle.manifest.phase.thetaPeak,
            referencePeriodSeconds = bundle.manifest.segmentation.periodSeconds,
        ),
    )
    private val faultDetector = FaultDetector(bundle.faults.faults)
    private val cueArbiter = CueArbiter()

    private val dominant = bundle.manifest.segmentation.dominantFeature
    private val critical = bundle.manifest.phase.criticalWindow.let { it.first..it.second }
    private val referenceRom = bundle.reference.features[dominant]?.rom ?: 0.0

    private val repFrames = mutableListOf<ScoredFrame>()

    // 6.1. §13.1's Rep rows, in memory. The engine used to keep only (quality, valid) per rep,
    // which is enough to draw a counter and nothing like enough for §6.18: CUSUM needs the series
    // in order, attribution needs signed z per feature per rep, and the fatigue signature needs
    // ROM and duration. Those were computed every rep already and thrown away every rep.
    private val repRecords = mutableListOf<RepRecord>()
    private val repFeatures = FeatureAccumulator()
    private val repVelocities = mutableListOf<Double>()
    private var lastRejection: RepRejection? = null
    private var lastRepNote: RepNote? = null

    /** Set once §6.18.6 has fired, so the patient is told to stop exactly once. */
    private var safetyStopAnnounced = false
    private var lastSkippedFeature: FeatureId? = null

    /**
     * Whether the previous frame's pose matched the reference on every watch-listed joint.
     *
     * Deliberately the previous frame. Z-scores are computed *from* the phase this same call is
     * about to produce, so the current frame's verdict does not exist yet; one frame of lag is 33 ms
     * against checkpoint buckets a quarter of the range wide, which is nothing. Starts true so the
     * first frame of a set is not judged against a pose nobody has measured yet.
     */
    private var lastConformant = true
    /** Worst watch-listed compensation seen during the rep in progress, for the end-of-rep note. */
    private var repWorstCompensation: Pair<FeatureId, Double>? = null

    /** §6.12 prescribes a rep count; a set that ignores it is not the set that was prescribed. */
    private val prescribedReps = bundle.manifest.prescription.reps
    private var setComplete = false

    /** True once the prescribed reps are done. The UI stops the set on this. */
    val isSetComplete get() = setComplete

    var state: FrameResult = FrameResult.EMPTY
        private set

    /**
     * §13.1's `Session` for the set in progress, rebuilt whenever a rep finishes.
     *
     * Immutable and reassigned rather than mutated, exactly like [state]: it is read from the UI
     * thread while the analysis thread appends to the set, and handing out a live list would be a
     * race. §13.1 persists a row per rep as it completes rather than at set end, and this is the
     * in-memory half of that - a set interrupted mid-way is still readable up to its last rep.
     */
    var session: SessionRecord = SessionRecord.EMPTY
        private set

    /**
     * §6.18, re-assessed each time a rep completes.
     *
     * Live rather than at set end because §6.18.6 is an *intervention*: "stop the set, say so
     * aloud, log it" is not something that can be said after the set has already finished. Below
     * six valid reps this stays [FatigueResult.INSUFFICIENT], which is the honest answer and not a
     * quiet zero.
     */
    var fatigue: FatigueResult = FatigueResult.INSUFFICIENT
        private set

    /** Starts a new set. Everything the patient has already earned is discarded. */
    fun reset() {
        clearPerFrameState()
        cueArbiter.resetSet()
        repRecords.clear()
        session = SessionRecord.EMPTY
        fatigue = FatigueResult.INSUFFICIENT
        safetyStopAnnounced = false
        lastRejection = null
        lastRepNote = null
        lastSkippedFeature = null
        repWorstCompensation = null
        setComplete = false
        state = FrameResult.EMPTY
    }

    /**
     * The detector stopped returning a person. The rep in progress is abandoned - the part we could
     * not see is exactly the part we cannot score - but the reps already completed are **kept**: a
     * patient who steps out of frame between reps has not undone the set behind them, and zeroing
     * the counter there makes it look permanently stuck.
     */
    fun onPersonLost() {
        clearPerFrameState()
        state = state.copy(
            status = ExerciseStatus.NO_PERSON,
            phaseState = PhaseState.IDLE,
            phase = 0.0,
            phi = 0.0,
            velocity = 0.0,
            quality = null,
            completedRep = null,
            cue = null,
            activeFaultIds = emptyList(),
            faultOnsetIds = emptyList(),
            faultClearIds = emptyList(),
            frame = null,
            zScores = emptyMap(),
        )
    }

    private fun clearPerFrameState() {
        landmarkFilter.reset()
        bodyFrames.reset()
        validity.reset()
        phaseTracker.reset()
        faultDetector.reset()
        repFrames.clear()
        repFeatures.clear()
        repVelocities.clear()
        repWorstCompensation = null
        lastConformant = true
    }

    fun onFrame(
        raw: LandmarkFrame,
        timestampNs: Long,
        personRedetected: Boolean = false,
    ): FrameResult {
        val filtered = landmarkFilter.filter(raw, timestampNs, personRedetected)
        val body = bodyFrames.update(filtered, timestampNs)

        // §6.6 rule 4. An unusable body frame means no feature can be trusted, so the pipeline is
        // not run at all - the phase tracker still gets a null so its own idle timeout can fire.
        if (body.status == BodyFrameStatus.INVALID) {
            lastConformant = true
            val update = phaseTracker.update(null, timestampNs)
            validity.update(emptyMap(), timestampNs)
            return publish(
                status = ExerciseStatus.NO_PERSON,
                phaseUpdate = update,
                quality = null,
                completed = null,
                cue = null,
                activeFaults = emptyList(),
                faultOnsets = emptyList(),
                faultClears = emptyList(),
                frame = filtered,
                zScores = emptyMap(),
            )
        }

        val features = validity.update(
            validFeatures(filtered, body, FeatureContext(activeSide, verticalWorld)),
            timestampNs,
        )

        val phaseUpdate = phaseTracker.update(features[dominant], timestampNs, lastConformant)

        val z = zScores(
            measured = features,
            reference = bundle.reference,
            watchList = bundle.watchList,
            phase = phaseUpdate.phase,
            velocity = phaseUpdate.velocity,
            vRef = bundle.manifest.phase.vRef,
        )
        val quality = frameScore(z, bundle.watchList)
        lastConformant = poseConformant(z)

        val faults = faultDetector.update(z, phaseUpdate.phase, timestampNs)

        // §6.6: once a watched joint has been out of sight long enough, name the problem and stop
        // coaching. A cue chosen off a half-seen body is worse than silence.
        val watched = bundle.watchList.features.map { it.id }
        val occluded = validity.shouldWarn(watched, timestampNs)
        // Once the set is done there is nothing left to coach toward: cueing a 13th rep asks the
        // patient to keep working past what was prescribed.
        val cue = if (occluded || setComplete) {
            null
        } else {
            cueArbiter.select(faults.active, repRecords.size + 1, timestampNs)
        }

        if (!setComplete && phaseUpdate.state != PhaseState.IDLE && quality != null) {
            repFrames += ScoredFrame(phaseUpdate.phase, quality)
            repFeatures.add(z)
            repVelocities += phaseUpdate.velocity
            trackWorstCompensation(z)
        }

        // A rep that finishes after the set is complete is discarded rather than counted. The
        // prescription is the set; reps past it are the patient not knowing they can stop.
        val completed = phaseUpdate.completedRep?.takeUnless { setComplete }
        val safetyStop = if (completed != null) {
            closeRep(completed)
            // Announced once. A stop that repeats every frame is noise, and noise is how a patient
            // learns to ignore the one message that matters.
            if (fatigue.stopRecommended && !safetyStopAnnounced) {
                safetyStopAnnounced = true
                true
            } else {
                false
            }
        } else {
            false
        }

        val status = when {
            setComplete -> ExerciseStatus.SET_COMPLETE
            occluded -> ExerciseStatus.OCCLUDED
            phaseUpdate.state == PhaseState.IDLE -> ExerciseStatus.READY
            else -> ExerciseStatus.TRACKING
        }

        return publish(
            status = status,
            phaseUpdate = phaseUpdate,
            quality = quality,
            completed = completed,
            cue = cue,
            activeFaults = faults.active.map { it.definition.id },
            // Suppressed once the set is done, so a completed set cannot buzz at the patient.
            faultOnsets = if (setComplete) emptyList() else faults.onsets.map { it.id },
            faultClears = if (setComplete) emptyList() else faults.clears.map { it.id },
            frame = filtered,
            zScores = z,
            safetyStop = safetyStop,
        )
    }

    /**
     * §6.17.3 plus §6.12.4. A partial rep is recorded so the counter and the summary can show it,
     * and excluded from the set statistic by the `valid` flag rather than by being thrown away here.
     */
    private fun closeRep(completed: CompletedRep) {
        val rom = completed.phiMax * (bundle.manifest.phase.thetaPeak - bundle.manifest.phase.thetaStart)
        val quality = repScore(
            frames = repFrames.toList(),
            critical = critical,
            romDegrees = kotlin.math.abs(rom),
            referenceRom = referenceRom,
            durationSeconds = completed.durationSeconds,
            referencePeriodSeconds = bundle.manifest.segmentation.periodSeconds,
        )
        repFrames.clear()
        val valid = completed.valid && quality != null
        val worstJoint = repWorstCompensation?.first
        repRecords += RepRecord(
            index = repRecords.size + 1,
            // Nullable all the way through. A rep the tracker accepted and could not score is not
            // a rep scored zero, and §6.18.2's baseline is a median that a zero would drag down.
            quality = quality,
            romDegrees = kotlin.math.abs(rom),
            durationSeconds = completed.durationSeconds,
            phiMax = completed.phiMax,
            valid = valid,
            rejection = if (valid) null else (completed.rejection ?: RepRejection.UNSCORED),
            features = repFeatures.samples(),
            velocityProfile = repVelocities.toList(),
        )
        session = SessionRecord(repRecords.toList(), prescribedReps)
        fatigue = assessFatigue(session)
        repFeatures.clear()
        repVelocities.clear()
        lastRejection = if (valid) {
            null
        } else {
            // A rep the phase tracker accepted but that scored nothing has no reason of its own.
            completed.rejection ?: RepRejection.UNSCORED
        }
        // "You skipped the middle" and "you left the elbow out" are both SKIPPED, and they are not
        // the same instruction. The joint is only named when a joint is actually what went wrong.
        lastSkippedFeature = worstJoint.takeIf {
            !valid &&
                completed.rejection == RepRejection.SKIPPED &&
                completed.nonConformantFrames > 0
        }
        lastRepNote = if (valid) noteFor(completed, quality) else null
        repWorstCompensation = null
        // Only valid reps count toward the prescription, so a set of partials never ends the set.
        if (prescribedReps > 0 && session.validReps >= prescribedReps) setComplete = true
        // §6.18.6. The plan is unambiguous that this ends the set: continuing to coach a patient
        // whose quality has collapsed is the opposite of the intervention. It closes the set the
        // same way the prescription does, so nothing further is counted or cued.
        if (fatigue.stopRecommended) setComplete = true
    }

    /**
     * What to say about a rep that **counted**.
     *
     * §6.12.4 decides whether a rep is a rep. It says nothing about a rep that clears that bar and
     * is still not the movement that was prescribed - going well past the reference, stopping just
     * short of it, or dragging another joint along. Those were previously counted in silence, which
     * tells the patient the rep was fine.
     *
     * Ordered by what a patient can act on first: how far they went, then how cleanly.
     */
    private fun noteFor(completed: CompletedRep, quality: Double?): RepNote? {
        // phi is normalised so 1.0 is the reference extremum, whatever the exercise or the joint.
        // Past it means past what the physio recorded, for every exercise, with nothing to author.
        if (completed.phiMax > 1.0 + Params.REP_OVERSHOOT_TOLERANCE) return RepNote.Overshot

        if (completed.phiMax < Params.REP_FULL_RANGE_FRACTION) return RepNote.ShortOfRange

        val worst = repWorstCompensation
        if (quality != null && quality < Params.PROGRESSION_MIN_QUALITY && worst != null) {
            return RepNote.Compensation(worst.first)
        }
        return null
    }

    /**
     * Was the body in the pose the reference has at this phase, on every joint but the one phase is
     * measured from?
     *
     * Abstention is not disagreement (§6.6): a joint the landmarks could not support is absent from
     * [zScores] and has no opinion here. A frame where **nothing** could be scored is conformant by
     * the same rule - refusing a rep for what was never seen is the error the abstention discipline
     * exists to prevent.
     */
    private fun poseConformant(zScores: Map<FeatureId, FeatureZ>): Boolean =
        zScores.none { (id, featureZ) ->
            id != dominant &&
                featureZ.z.isFinite() &&
                kotlin.math.abs(featureZ.z) > Params.TRAJECTORY_CONFORMANCE_Z
        }

    /**
     * The joint that drifted furthest during this rep, so the end-of-rep note can name it.
     *
     * The dominant feature is excluded: it defines phase and so cannot register a deviation, and
     * naming it would be reporting an artefact of the measurement as if it were the patient's doing.
     */
    private fun trackWorstCompensation(zScores: Map<FeatureId, FeatureZ>) {
        val watched = bundle.watchList.features.associate { it.id to it.weight }
        zScores.forEach { (id, featureZ) ->
            if (id == dominant || !featureZ.z.isFinite()) return@forEach
            val weight = watched[id] ?: return@forEach
            val score = kotlin.math.abs(featureZ.z) * weight
            if (score > (repWorstCompensation?.second ?: 0.0)) {
                repWorstCompensation = id to score
            }
        }
    }

    /**
     * §6.16's arrow answers "where should this joint be *right now*". For the dominant feature that
     * question has no content - phase is derived from it, so it is compared against itself and its z
     * is ~0 however the patient moves. The question that does have content is where the movement is
     * meant to *end*: the peak on the way out, the start on the way back.
     *
     * This is what makes the arrow stay up while the patient is still short of the target and then
     * turn around once they reach it, instead of appearing only when some other joint drifts.
     */
    private fun publish(
        status: ExerciseStatus,
        phaseUpdate: PhaseUpdate,
        quality: Double?,
        completed: CompletedRep?,
        cue: Cue?,
        activeFaults: List<String>,
        faultOnsets: List<String>,
        faultClears: List<String>,
        frame: LandmarkFrame?,
        zScores: Map<FeatureId, FeatureZ>,
        safetyStop: Boolean = false,
    ): FrameResult {
        // Unscored reps are dropped from the scoring input rather than coerced to zero; they are
        // still counted as partials below, from the records themselves.
        val set = setScore(
            repRecords.mapNotNull { record -> record.quality?.let { ScoredRep(it, record.valid) } },
            bundle.manifest.prescription.reps,
        )
        state = FrameResult(
            status = status,
            phaseState = phaseUpdate.state,
            phase = phaseUpdate.phase,
            phi = phaseUpdate.phi,
            velocity = phaseUpdate.velocity,
            quality = quality,
            validReps = set.validReps,
            partialReps = repRecords.count { !it.valid },
            setQuality = set.score,
            completedRep = completed,
            lastRejection = lastRejection,
            cue = cue,
            activeFaultIds = activeFaults,
            faultOnsetIds = faultOnsets,
            faultClearIds = faultClears,
            frame = frame,
            zScores = zScores,
            activeSide = activeSide,
            lastRepNote = lastRepNote,
            skippedFeature = lastSkippedFeature,
            safetyStop = safetyStop,
        )
        return state
    }
}

/**
 * What the §12.3 quality bar should look like. Kept out of Compose so it can be tested at all - the
 * plan calls the bar "the feedback loop the patient can actually see working", which makes its
 * thresholds behaviour, not styling.
 */
enum class QualityBand { UNKNOWN, DANGER, WARNING, SUCCESS }

fun qualityBand(quality: Double?): QualityBand = when {
    quality == null -> QualityBand.UNKNOWN
    quality >= Params.PROGRESSION_MIN_QUALITY -> QualityBand.SUCCESS
    quality >= 55.0 -> QualityBand.WARNING
    else -> QualityBand.DANGER
}
