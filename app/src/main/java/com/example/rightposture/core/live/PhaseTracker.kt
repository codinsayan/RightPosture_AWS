package com.example.rightposture.core.live

import com.example.rightposture.core.Params
import kotlin.math.abs

/**
 * §6.12. Live phase estimation.
 *
 * **No lookahead.** Every decision is made from the frames seen so far, which is what separates this
 * from the authoring pipeline: 3.4 could look at a whole recording and pick rep boundaries with
 * hindsight, and this cannot.
 *
 * "Descend" and "ascend" are names of convenience. They mean "moving toward the extremum" and
 * "returning", whichever direction the joint actually travels.
 */
enum class PhaseState { IDLE, DESCEND, BOTTOM_HOLD, ASCEND }

/**
 * The reference values the tracker indexes against. §6.12.2 is explicit that `thetaStart` and
 * `thetaPeak` come from the **reference**, not from the current rep: the current rep's extremum is
 * not knowable in advance, which is the whole difficulty of doing this live.
 */
data class PhaseConfig(
    val thetaStart: Double,
    val thetaPeak: Double,
    /** `T_ref`, the reference rep period, for the §6.12.4 duration bounds. */
    val referencePeriodSeconds: Double,
)

/**
 * §6.12.4. Why a finished rep did not count.
 *
 * A code rather than a sentence: the wording is a presentation concern, and the same rejection has
 * to be readable on screen and speakable in whichever language the patient chose.
 */
enum class RepRejection {
    /** `φ_max` never reached [Params.REP_ACCEPTANCE_PHASE_MAX]. */
    RANGE,
    TOO_FAST,
    TOO_SLOW,

    /** The dominant feature was invalid for too much of the rep. */
    UNSEEN,

    /** The tracker accepted it but no frame in it could be scored. */
    UNSCORED,

    /**
     * The end pose was reached without the movement in between.
     *
     * §6.12.4's acceptance is a start point, an extremum and a duration, and a patient who jumps
     * straight to the end pose and holds there satisfies all three. That is not the exercise the
     * physio recorded, and counting it teaches the patient that the shortcut is the movement.
     *
     * Also covers the step that was simply left out. All of §6.12.4 is a function of phi, and phi is
     * one joint; an exercise made of several steps needs the rest of the body to have been in the
     * right place too, so a checkpoint is only credited when it was.
     */
    SKIPPED,
}

/** §6.12.4. A rep that finished, and whether it counts. */
data class CompletedRep(
    val valid: Boolean,
    val phiMax: Double,
    val durationSeconds: Double,
    /** Fraction of the rep's frames where the dominant feature was valid. */
    val validFraction: Double,
    /** The fastest `|dphi/dt|` seen inside the rep, for the §6.12.4 trajectory bound. */
    val maxPhiRatePerSecond: Double,
    /** Checkpoints the rep was observed passing through on **both** legs of the movement. */
    val checkpointsVisited: Int,
    /**
     * Frames inside the rep where a watch-listed joint was away from the reference pose.
     *
     * Non-zero on a [RepRejection.SKIPPED] rep means the patient moved the dominant joint through
     * the range but did not perform the movement, which is a different sentence to say to them than
     * "you jumped to the end".
     */
    val nonConformantFrames: Int,
    /**
     * Checkpoints this rep needed, derived from how far it actually went.
     *
     * Scaled to [phiMax] rather than fixed, because a rep that stops short is short - which is
     * [RepRejection.RANGE]'s business - not one that skipped the middle.
     */
    val checkpointsRequired: Int,
    /** Null when valid. Otherwise why it was refused, per §6.12.4. */
    val rejection: RepRejection?,
    /**
     * 11.2. Share of the rep's judged frames in which the whole pose was right.
     *
     * Judged means valid and not past phi = 1 - the same frames the checkpoints are decided on. A
     * rep in which nothing could be judged reports 1.0: no frame contradicted the reference, and an
     * abstention must not read as a fault (§6.6).
     *
     * Defaulted, and last, so a [CompletedRep] built anywhere before this existed still compiles.
     */
    val conformantFraction: Double = 1.0,
)

data class PhaseUpdate(
    val state: PhaseState,
    /** `p`, the 0-100 phase index. Frozen while in [PhaseState.BOTTOM_HOLD] per §6.12.5. */
    val phase: Double,
    /** `φ`, clamped to [0, 1.15]. Overshoot above 1 is deliberately observable. */
    val phi: Double,
    /** `dφ/dt`, EMA-smoothed with τ = 100 ms. */
    val velocity: Double,
    /** Non-null on exactly the frame a rep finished. */
    val completedRep: CompletedRep?,
)

/**
 * Feed every frame in order. Pass a null `dominantValue` when the dominant feature abstained (1.6);
 * that is different from passing a stale one, and §6.12.3's "person left frame" rule depends on the
 * difference.
 */
class PhaseTracker(private val config: PhaseConfig) {

    private var state = PhaseState.IDLE
    private var stateEnteredNs = 0L
    private var lastTimestampNs: Long? = null

    private var phi = 0.0
    private var lastPhi = 0.0
    private var velocity = 0.0
    private var velocityInitialised = false

    /** Start of the current stall inside DESCEND, for the 400 ms BOTTOM_HOLD entry condition. */
    private var stallSinceNs: Long? = null
    private var invalidSinceNs: Long? = null
    private var frozenPhase = 0.0

    private var repStartNs: Long? = null
    private var repPhiMax = 0.0
    private var repFrames = 0
    private var repValidFrames = 0

    /**
     * Frames seen in each checkpoint and how many of them had the whole pose right, one pair of
     * rows per leg.
     *
     * Deliberately over phi and not over the phase index `p`. `p` runs 0 to 50 outward and 50 to 100
     * back, so a rep peaking below phi = 1 never produces values near 50 at all - the index has a
     * gap at the turnaround whose width is exactly how far short the rep stopped. Bucketing `p`
     * would score that gap as a skipped checkpoint and reject every rep that did not reach the full
     * reference range, which is not what a skipped rep is.
     *
     * The two legs are tracked separately so that moving out properly and then dropping straight
     * back is still caught; a single combined tally would be filled by the good leg.
     *
     * Counts rather than the bitmasks this kept before 11.1. A single bit could only record that
     * *some* frame in the bucket was conformant, which credits a bucket the patient was out of
     * position in for three quarters of - the exact case a multi-joint exercise fails on.
     */
    private val repOutwardFrames = IntArray(Params.TRAJECTORY_CHECKPOINTS)
    private val repOutwardConformant = IntArray(Params.TRAJECTORY_CHECKPOINTS)
    private val repReturnFrames = IntArray(Params.TRAJECTORY_CHECKPOINTS)
    private val repReturnConformant = IntArray(Params.TRAJECTORY_CHECKPOINTS)

    /** Fastest `|dphi/dt|` inside the current rep, and the last one seen at all. */
    private var repMaxPhiRate = 0.0
    private var lastPhiRate = 0.0

    /** Frames in this rep where the rest of the body was not where the reference had it. */
    private var repNonConformantFrames = 0

    /**
     * Frames in this rep whose conformance was actually judged: valid, and not past phi = 1.
     *
     * The denominator for 11.2's whole-rep fraction. Not [repValidFrames], because overshoot frames
     * are valid but deliberately unjudged, and counting them would dilute a wrong end-range hold
     * with frames nobody scored.
     */
    private var repJudgedFrames = 0

    /**
     * False whenever the previous frame did not yield a usable phi, so the next difference is not a
     * derivative.
     *
     * Without this, the first frame back after a dropout compares a fresh phi against a stale one
     * across a gap the patient moved through unobserved, and reads as a jump. §6.6 already says the
     * unseen part is the part we cannot judge - it must not be judged as a skip either.
     */
    private var phiRateValid = false

    val currentState get() = state

    fun reset() {
        state = PhaseState.IDLE
        lastTimestampNs = null
        lastPhiRate = 0.0
        phiRateValid = false
        phi = 0.0
        lastPhi = 0.0
        velocity = 0.0
        velocityInitialised = false
        stallSinceNs = null
        invalidSinceNs = null
        clearRep()
    }

    /**
     * @param conformant whether every other watch-listed joint was within
     *   [Params.TRAJECTORY_CONFORMANCE_Z] of the reference pose on this frame. Checkpoints are only
     *   credited on conformant frames, which is what makes the trajectory a path through **poses**
     *   rather than through one joint's angle. Defaults to true so a single-joint exercise - where
     *   there is no other joint to disagree with - behaves exactly as it did before.
     */
    fun update(
        dominantValue: Float?,
        timestampNs: Long,
        conformant: Boolean = true,
    ): PhaseUpdate {
        val previous = lastTimestampNs
        // Out-of-order or duplicated timestamps would produce a divide-by-zero or a negative dt, and
        // a velocity derived from either is worse than no velocity. Same rule as 2.3.
        if (previous != null && timestampNs <= previous) return snapshot(null)
        val dt = if (previous == null) 0.0 else (timestampNs - previous) / 1_000_000_000.0
        lastTimestampNs = timestampNs
        if (previous == null) stateEnteredNs = timestampNs

        // A gap means the frames either side are not adjacent, so the difference between them is not
        // a derivative. Drop the velocity estimate rather than let a discontinuity through it.
        if (dt > Params.MAX_FRAME_GAP_SECONDS) velocityInitialised = false

        // A gap longer than the occlusion timeout is the same event as the person having left, and
        // §6.12.3 already discards the rep in progress for that. Reaching here means frames stopped
        // arriving at all while the patient stayed visible - which is what §6.20.2's pause does when
        // the phone is bumped mid-rep. Without this the rep completes carrying the whole pause in
        // its duration and is refused as TOO_SLOW, so a bumped phone costs the patient the rep and
        // then tells them they were slow, which is both a lie and the opposite of actionable.
        if (dt > Params.OCCLUSION_IDLE_SECONDS && state != PhaseState.IDLE) {
            clearRep()
            velocityInitialised = false
            velocity = 0.0
            phiRateValid = false
            enter(PhaseState.IDLE, timestampNs)
        }

        if (dominantValue == null || !dominantValue.isFinite()) return updateInvalid(timestampNs)
        invalidSinceNs = null

        val span = config.thetaPeak - config.thetaStart
        // A reference whose dominant joint does not move cannot define phase at all. 3.8 check 2
        // rejects such a reference, so reaching here means one got through; abstain rather than
        // divide by zero and coach off a meaningless number.
        if (abs(span) < 1e-6) return snapshot(null)

        lastPhi = phi
        phi = ((dominantValue - config.thetaStart) / span)
            .coerceIn(0.0, Params.PHASE_OVERSHOOT_MAX)

        if (dt > 0.0) {
            val instant = (phi - lastPhi) / dt
            // A gap means these frames are not adjacent, so the difference across them is not a
            // rate, and treating it as one would read every dropped frame as a jump.
            if (phiRateValid && dt <= Params.MAX_FRAME_GAP_SECONDS) {
                lastPhiRate = abs(instant)
                if (repStartNs != null && lastPhiRate > repMaxPhiRate) repMaxPhiRate = lastPhiRate
            } else {
                lastPhiRate = 0.0
            }
            velocity = if (!velocityInitialised) {
                velocityInitialised = true
                instant
            } else {
                // EMA over irregular sampling: α from dt and τ, not a fixed weight.
                val alpha = dt / (Params.VELOCITY_EMA_TAU_SECONDS + dt)
                velocity + alpha * (instant - velocity)
            }
        }

        phiRateValid = true
        countFrame(valid = true, conformant = conformant)
        return advance(timestampNs)
    }

    // -----------------------------------------------------------------------------------------
    // Transitions (§6.12.3)
    // -----------------------------------------------------------------------------------------

    private fun advance(timestampNs: Long): PhaseUpdate {
        val dwellMet = (timestampNs - stateEnteredNs) / 1_000_000_000.0 >= Params.STATE_MIN_DWELL_SECONDS
        val stalled = abs(velocity) < Params.V_STALL_PER_SECOND

        // The deadband - V_TH for transitions, V_STALL for stalls, with a gap between them - plus
        // the minimum dwell are what stop the state machine chattering at the turnaround. §6.12.3:
        // without them you get three phantom reps per real rep.
        when (state) {
            PhaseState.IDLE -> {
                if (phi > Params.PHASE_ENTRY && velocity > Params.V_TH_PER_SECOND && dwellMet) {
                    startRep(timestampNs)
                    enter(PhaseState.DESCEND, timestampNs)
                }
            }

            PhaseState.DESCEND -> {
                if (phi > Params.PHASE_BOTTOM_HOLD_ENTRY && stalled) {
                    val since = stallSinceNs ?: timestampNs.also { stallSinceNs = it }
                    if ((timestampNs - since) / 1_000_000_000.0 >= Params.BOTTOM_HOLD_STALL_SECONDS) {
                        frozenPhase = phaseIndex()
                        enter(PhaseState.BOTTOM_HOLD, timestampNs)
                        return snapshot(null)
                    }
                } else {
                    stallSinceNs = null
                }
                if (velocity < -Params.V_TH_PER_SECOND && phi > Params.PHASE_ASCEND_MIN && dwellMet) {
                    enter(PhaseState.ASCEND, timestampNs)
                }
            }

            PhaseState.BOTTOM_HOLD -> {
                if (velocity < -Params.V_TH_PER_SECOND) {
                    enter(PhaseState.ASCEND, timestampNs)
                } else if ((timestampNs - stateEnteredNs) / 1_000_000_000.0 > Params.BOTTOM_HOLD_ABANDON_SECONDS) {
                    // Abandoned rep. It is dropped rather than completed: nobody held the bottom for
                    // twenty seconds and then finished the rep they started.
                    clearRep()
                    enter(PhaseState.IDLE, timestampNs)
                }
            }

            PhaseState.ASCEND -> {
                if (phi < Params.PHASE_REP_COMPLETE && stalled && dwellMet) {
                    return snapshot(completeRep(timestampNs))
                }
            }
        }
        return snapshot(null)
    }

    private fun updateInvalid(timestampNs: Long): PhaseUpdate {
        phiRateValid = false
        lastPhiRate = 0.0
        // Conformance is not judged on a frame whose landmarks were unusable: §6.6 says the unseen
        // part is the part we cannot judge, and an abstention must not read as a fault.
        countFrame(valid = false, conformant = true)
        val since = invalidSinceNs ?: timestampNs.also { invalidSinceNs = it }
        // §6.12.3, "any → IDLE": the person left the frame. The rep in progress is discarded rather
        // than completed, because the part we could not see is exactly the part we cannot score.
        if ((timestampNs - since) / 1_000_000_000.0 > Params.OCCLUSION_IDLE_SECONDS && state != PhaseState.IDLE) {
            clearRep()
            velocityInitialised = false
            velocity = 0.0
            enter(PhaseState.IDLE, timestampNs)
        }
        return snapshot(null)
    }

    private fun enter(next: PhaseState, timestampNs: Long) {
        state = next
        stateEnteredNs = timestampNs
        stallSinceNs = null
    }

    // -----------------------------------------------------------------------------------------
    // Rep bookkeeping (§6.12.4)
    // -----------------------------------------------------------------------------------------

    private fun startRep(timestampNs: Long) {
        repStartNs = timestampNs
        repPhiMax = phi
        repFrames = 0
        repValidFrames = 0
        clearCheckpoints()
        repNonConformantFrames = 0
        repJudgedFrames = 0
        // Seeded with the rate on the frame that opened the rep. That frame is the jump, when there
        // is one: a patient who snaps from rest to the end pose enters DESCEND on the very step that
        // skipped the movement, and measuring only from the next frame would miss it.
        repMaxPhiRate = lastPhiRate
    }

    private fun clearRep() {
        repStartNs = null
        repPhiMax = 0.0
        repFrames = 0
        repValidFrames = 0
        clearCheckpoints()
        repNonConformantFrames = 0
        repJudgedFrames = 0
        repMaxPhiRate = 0.0
    }

    private fun clearCheckpoints() {
        repOutwardFrames.fill(0)
        repOutwardConformant.fill(0)
        repReturnFrames.fill(0)
        repReturnConformant.fill(0)
    }

    private fun countFrame(valid: Boolean, conformant: Boolean) {
        if (repStartNs == null) return
        repFrames++
        if (!valid) return
        repValidFrames++
        if (phi > repPhiMax) repPhiMax = phi

        // Past the reference's own extremum there is no reference pose left to disagree with.
        // phaseIndex() saturates at 50 once phi passes 1, so a patient swinging beyond what the
        // physio recorded is scored against the turnaround while being well past it, and every
        // joint that moves with the dominant one reads as a deviation it never made.
        //
        // That is an artefact of the overshoot rather than a second fault, and the overshoot
        // already has something more specific and more useful to say about itself
        // (RepNote.Overshot). Judging these frames would report one problem twice and pick the less
        // actionable description - the same reasoning that puts RANGE ahead of everything in
        // completeRep. The line is phi = 1 exactly, because that is where the saturation starts;
        // REP_OVERSHOOT_TOLERANCE answers a different question, which is how far past the reference
        // is worth mentioning to the patient.
        if (phi > 1.0) return

        markCheckpoint(conformant)
        repJudgedFrames++
        if (!conformant) repNonConformantFrames++
    }

    /**
     * Records this frame against the checkpoint it falls in, on whichever leg the rep is on.
     *
     * Every judged frame is counted, conformant or not: the credit decision is a ratio taken at
     * completion, and a denominator built only from the frames that agreed would make every bucket
     * unanimous by construction.
     */
    private fun markCheckpoint(conformant: Boolean) {
        val bucket = checkpointIndex(phi)
        fun record(frames: IntArray, agreed: IntArray) {
            frames[bucket]++
            if (conformant) agreed[bucket]++
        }
        when (state) {
            PhaseState.IDLE, PhaseState.DESCEND -> record(repOutwardFrames, repOutwardConformant)
            PhaseState.ASCEND -> record(repReturnFrames, repReturnConformant)
            // The turnaround belongs to both legs; the patient is at the top of each of them.
            PhaseState.BOTTOM_HOLD -> {
                record(repOutwardFrames, repOutwardConformant)
                record(repReturnFrames, repReturnConformant)
            }
        }
    }

    /**
     * Buckets this leg was not merely seen in but *performed* in, as a bitmask.
     *
     * A bucket is credited when a majority of its frames had the whole pose right. One jittering
     * landmark therefore cannot lose a rep - it is one frame against the bucket's several - while a
     * joint left out through most of the bucket no longer earns it on the stray frame the patient
     * happened to pass through the right position on.
     */
    private fun creditedMask(frames: IntArray, agreed: IntArray): Int {
        var mask = 0
        for (bucket in frames.indices) {
            if (frames[bucket] == 0) continue
            val share = agreed[bucket].toDouble() / frames[bucket]
            if (share >= Params.TRAJECTORY_MIN_BUCKET_CONFORMANT_FRACTION) mask = mask or (1 shl bucket)
        }
        return mask
    }

    /** Which quarter of the movement's range this value of phi falls in. */
    private fun checkpointIndex(value: Double): Int =
        (value * Params.TRAJECTORY_CHECKPOINTS).toInt()
            .coerceIn(0, Params.TRAJECTORY_CHECKPOINTS - 1)

    private fun completeRep(timestampNs: Long): CompletedRep {
        val start = repStartNs
        val duration = if (start == null) 0.0 else (timestampNs - start) / 1_000_000_000.0
        val validFraction = if (repFrames == 0) 0.0 else repValidFrames.toDouble() / repFrames
        val minDuration = Params.REP_DURATION_MIN_REF_FRACTION * config.referencePeriodSeconds
        val maxDuration = Params.REP_DURATION_MAX_REF_FRACTION * config.referencePeriodSeconds
        val minValidFraction = 1.0 - Params.REP_DOMINANT_INVALID_MAX_FRACTION

        // §6.12.4 spells out why this distinction matters "enormously": without it a fatiguing
        // patient's shrinking reps score as good reps, because they never leave the band near their
        // own shortened turnaround, and the fatigue detector sees nothing.
        // Only the checkpoints this rep's own range **fully crosses** are required, and both legs
        // must have been seen in each of them.
        //
        // Fully crossed, not merely entered: a rep peaking at phi = 0.8 only grazes the top quarter,
        // touching it for a frame or two at the turnaround, and demanding it on both legs would
        // reject a rep whose only fault is stopping a little short - which is RANGE's business.
        val requiredCount = (repPhiMax * Params.TRAJECTORY_CHECKPOINTS).toInt()
            .coerceIn(1, Params.TRAJECTORY_CHECKPOINTS)
        val required = (1 shl requiredCount) - 1

        // The reference covers phi 0 to 1 in half a period, so 2/T is its mean rate.
        val maxPhiRate = if (config.referencePeriodSeconds <= 0.0) {
            Double.MAX_VALUE
        } else {
            Params.TRAJECTORY_MAX_RATE_MULTIPLE * 2.0 / config.referencePeriodSeconds
        }
        val bothLegs = creditedMask(repOutwardFrames, repOutwardConformant) and
            creditedMask(repReturnFrames, repReturnConformant) and
            required
        val visited = Integer.bitCount(bothLegs)

        // 11.2. The checkpoints only ask about the buckets this rep fully crossed, so a rep peaking
        // at phi = 0.95 never has its top bucket examined - and a patient who travels correctly and
        // then holds the end range out of position clears every checkpoint asked of them. §6.17.2
        // puts the therapeutic work at end range, so that is close to the worst rep the per-bucket
        // rule alone would count. This is the backstop that sees the whole rep.
        val conformantFraction = if (repJudgedFrames == 0) {
            1.0
        } else {
            1.0 - repNonConformantFrames.toDouble() / repJudgedFrames
        }

        // Range first: a rep that never reached the end has a simpler thing wrong with it than one
        // that reached it the wrong way, and telling a patient they skipped the middle of a movement
        // they never finished would be the less useful of the two true statements.
        val reason = when {
            repPhiMax < Params.REP_ACCEPTANCE_PHASE_MAX -> RepRejection.RANGE
            // Duration before checkpoints, deliberately. TRAJECTORY_CHECKPOINTS is sized against the
            // fastest rep the duration bound still accepts; past that the sampling genuinely cannot
            // tell a continuous sprint from a jump, so "too fast" is the verdict that is certainly
            // true rather than the one that merely might be.
            duration < minDuration -> RepRejection.TOO_FAST
            duration > maxDuration -> RepRejection.TOO_SLOW
            visited < requiredCount -> RepRejection.SKIPPED
            conformantFraction < Params.TRAJECTORY_MIN_REP_CONFORMANT_FRACTION -> RepRejection.SKIPPED
            repMaxPhiRate > maxPhiRate -> RepRejection.SKIPPED
            validFraction < minValidFraction -> RepRejection.UNSEEN
            else -> null
        }

        // Read before clearRep(), which zeroes them. maxPhiRatePerSecond was previously read after
        // it and so was reported as 0.0 on every rep ever completed - including the tests that
        // asserted it stayed under the bound, which passed against zero.
        val phiMax = repPhiMax
        val maxRate = repMaxPhiRate
        val nonConformant = repNonConformantFrames
        clearRep()
        // §6.12's diagram loops rep-complete straight back to DESCEND. It goes to IDLE here instead.
        // The next rep still starts on the same condition, so the count is identical, but the rep
        // clock now starts when the patient moves rather than when the last rep ended - otherwise
        // every rep taken after a breather would be rejected as "too slow" by the duration bound
        // above, and a resting patient would sit in DESCEND with a rep timer that never stops.
        enter(PhaseState.IDLE, timestampNs)
        return CompletedRep(
            valid = reason == null,
            phiMax = phiMax,
            durationSeconds = duration,
            validFraction = validFraction,
            maxPhiRatePerSecond = maxRate,
            checkpointsVisited = visited,
            checkpointsRequired = requiredCount,
            nonConformantFrames = nonConformant,
            rejection = reason,
            conformantFraction = conformantFraction,
        )
    }

    // -----------------------------------------------------------------------------------------

    /** §6.12.2. `p` is 0-50 on the way out and 50-100 on the way back. */
    private fun phaseIndex(): Double {
        val bounded = minOf(phi, 1.0)
        return if (state == PhaseState.DESCEND || state == PhaseState.IDLE) {
            50.0 * bounded
        } else {
            100.0 - 50.0 * bounded
        }
    }

    private fun snapshot(completed: CompletedRep?) = PhaseUpdate(
        state = state,
        // §6.12.5: while holding, phase is frozen at its entry value. A frozen phase with a stale
        // reference is correct here - the reference at that phase *is* the end-range target.
        phase = if (state == PhaseState.BOTTOM_HOLD) frozenPhase else phaseIndex(),
        phi = phi,
        velocity = velocity,
        completedRep = completed,
    )
}
