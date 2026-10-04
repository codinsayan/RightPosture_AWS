package com.example.rightposture.core

object Params {
    const val NOMINAL_FPS = 30
    const val MAX_FRAME_GAP_SECONDS = 0.25
    const val MIN_VECTOR_LENGTH = 1e-6f
    const val SIGNED_ANGLE_MAX_DEGREES = 90f
    const val BODY_FRAME_MIN_TORSO_METRES = 0.10f
    const val BODY_FRAME_MIN_HIP_WIDTH_METRES = 0.05f
    const val BODY_FRAME_MIN_VISIBILITY = 0.5f
    const val BODY_FRAME_REUSE_LIMIT_SECONDS = 0.5

    // 6.6 feature validity. VIS_MIN above is the shared 0.6 confidence bar.
    const val PRESENCE_MIN = 0.6f
    const val FEATURE_MIN_VECTOR_METRES = 1e-3f
    const val VALIDITY_RECOVERY_SECONDS = 0.300
    const val OCCLUSION_WARN_SECONDS = 0.5
    const val OCCLUSION_IDLE_SECONDS = 1.5
    const val REP_DOMINANT_INVALID_MAX_FRACTION = 0.30

    const val FC_MIN_HZ = 1.0
    const val BETA = 0.02
    const val D_CUTOFF_HZ = 1.0
    const val BUTTERWORTH_CUTOFF_HZ = 6.0
    const val BUTTERWORTH_ORDER = 4
    const val OFFLINE_OUTLIER_RADIUS_FRAMES = 3
    const val OFFLINE_OUTLIER_MAD_MULTIPLIER = 3.0
    const val OFFLINE_OUTLIER_MIN_JUMP = 8.0
    const val VIS_MIN = 0.6f
    const val AUTOCORRELATION_MIN_LAG_SECONDS = 0.5
    const val AUTOCORRELATION_MAX_LAG_SECONDS = 8.0
    const val R_MIN = 0.40
    const val ACTIVE_SIDE_RANGE_RATIO = 1.5
    const val PEAK_MIN_SPACING_PERIOD_FRACTION = 0.6
    const val PEAK_MIN_PROMINENCE_SWING_FRACTION = 0.25
    const val DURATION_OUTLIER_MEDIAN_FRACTION = 0.35
    const val MIN_SURVIVING_REPS = 4
    const val PHASE_BINS = 101
    const val SIGMA_MEDIAN_WINDOW = 9
    const val SIGMA_MEAN_WINDOW = 9
    const val SIGMA_FLOOR_DEGREES = 2.5
    const val SIGMA_FLOOR_ROM_FRACTION = 0.05
    const val SIGMA_CAP_DEGREES = 15.0
    const val K_INFLATE = 1.15
    const val K_INFLATE_NON_UPRIGHT = 1.30
    const val LUMBAR_SIGMA_FLOOR_DEGREES = 6.0
    const val MAD_TO_SIGMA = 1.4826
    const val SHAPE_OUTLIER_MAD_MULTIPLIER = 2.5

    // 11.3. Was 5. frameScore and pose conformance only see watch-listed features, and the dominant
    // one's z is ~0 by construction because phase is derived from it, so five left a compound
    // exercise scored on four joints at most. Seven is the dominant joint plus one per remaining
    // major segment a whole-body movement recruits - hip, knee, ankle, trunk, shoulder, elbow.
    //
    // This only caps how many survivors of §6.10's four PASS filters are kept; it admits nothing
    // the filters reject. A single-joint exercise has nowhere near this many survivors and is
    // unaffected. The features it admits are by construction the least salient, so they carry the
    // least weight - which is the argument for the frame-score scale holding. 11.3's device gate
    // checks that rather than trusting it.
    const val WATCH_LIST_SIZE = 7
    const val WATCH_LIST_MIN_ROM_DEGREES = 8.0
    const val WATCH_LIST_MIN_VISIBILITY = 0.7

    // 6.11 reference validator
    const val VALIDATOR_MIN_DOMINANT_ROM_DEGREES = 15.0
    const val VALIDATOR_MAX_DURATION_DISPERSION = 0.25
    const val VALIDATOR_TIGHT_SIGMA_DEGREES = 12.0
    const val VALIDATOR_MIN_TIGHT_FEATURES = 2
    const val VALIDATOR_MIN_WATCH_LIST_SIZE = 3
    const val VALIDATOR_MIN_VIEW_STABILITY = 0.90
    const val VALIDATOR_MIN_DURATION_SECONDS = 15.0
    const val VALIDATOR_MAX_DURATION_SECONDS = 180.0
    const val AUTHORING_ACCEPTANCE_TIMEOUT_SECONDS = 60.0
    const val MIN_SALIENCE = 2.0
    const val V_TH_PER_SECOND = 0.15
    const val V_STALL_PER_SECOND = 0.05
    const val STATE_MIN_DWELL_SECONDS = 0.150
    const val REP_ACCEPTANCE_PHASE_MAX = 0.75

    // 6.12.4 accepts a rep on how far it went and how long it took, which says nothing about the
    // path taken to get there: jumping straight to the end pose and holding satisfies both. The rep
    // is therefore also required to be *seen* in each fifth of the phase index.
    //
    // Four is chosen against the fastest rep the duration bound still accepts. That rep lasts
    // REP_DURATION_MIN_REF_FRACTION of the reference period, so on a 3 s reference it runs 1.2 s -
    // roughly 9 frames per leg at the device's ~15 fps. phi therefore advances about 0.11 per frame
    // on average and, at the peak of a sinusoidal velocity profile, about 0.17. Quarter-width
    // buckets are wider than that largest legitimate step, so a patient who is genuinely moving
    // through the range cannot skip one by being fast, while a patient who jumps from one end to the
    // other misses every bucket in between.
    const val TRAJECTORY_CHECKPOINTS = 4

    // Checkpoints are quarter-width, so a patient can still jump *within* one and be missed. This
    // catches a jump at any scale: phi may not advance faster than this multiple of the reference
    // movement's own tempo at any single frame.
    //
    // The reference covers phi 0 to 1 in half a period, so its mean rate is 2/T. The fastest rep the
    // duration bound accepts runs at 1/REP_DURATION_MIN_REF_FRACTION = 2.5x that, and a sinusoidal
    // velocity profile peaks about 1.57x above its own mean, so a legitimate sprint reaches roughly
    // 3.9x. Five leaves margin over that while a jump from one end to the other - a whole unit of phi
    // inside one frame - lands above twenty.
    const val TRAJECTORY_MAX_RATE_MULTIPLE = 5.0

    // Both bounds above are functions of phi, and phi is one joint. An exercise built from a
    // combination of steps is not: the patient who raises the arm but never bends the elbow moves
    // the dominant joint through every checkpoint at a legal speed, and until this was counted.
    //
    // A checkpoint is therefore only credited when the *pose* was right there, not just the
    // dominant angle - every watch-listed joint within this many sigma of what the physio did at
    // that phase. Three sigma of the physio's own frame-to-frame variation is wide enough that a
    // patient performing the step clears it comfortably, and far too tight for a step left out
    // altogether: an omitted joint sits at its rest angle while the reference has moved on, which
    // is tens of degrees against a sigma the reference builder floors at 2.5.
    //
    // The dominant feature is excluded, and not as an optimisation: phase is derived from it, so it
    // is compared against itself and its z is ~0 whatever the patient does.
    const val TRAJECTORY_CONFORMANCE_Z = 3.0

    // 11.1. How much of a checkpoint the pose has to be right in for it to count.
    //
    // A checkpoint used to be credited by a *single* conformant frame anywhere inside it, which
    // answers "was the patient ever in the right position in this quarter of the range". A patient
    // who is out of position for three quarters of a bucket and passes through the right pose once
    // on the way answers yes - and that is the compound-exercise hole: the dominant joint travels
    // legally, the omitted joint happens to sweep through the right angle somewhere in each bucket,
    // and the rep counts.
    //
    // A majority makes the checkpoint a statement about the movement rather than about its luckiest
    // instant. Sized against the jitter TRAJECTORY_CONFORMANCE_Z already tolerates rather than
    // picked: PoseConformanceTest's one-bad-frame-in-seventeen case sits at 0.94, so 0.6 leaves
    // ample room for landmark noise while refusing a joint left out through most of a bucket.
    //
    // Uncalibrated against real bodies, like most of this file - see C3.
    const val TRAJECTORY_MIN_BUCKET_CONFORMANT_FRACTION = 0.6

    // 11.2. How much of the whole rep the pose has to be right in, as a backstop beneath the
    // per-bucket rule above.
    //
    // Checkpoints are only required as far as the rep fully crossed, so the top bucket of a rep that
    // peaks short of phi = 1 is never examined - and that is where an end-range hold sits. This sees
    // every judged frame instead.
    //
    // Deliberately looser than the bucket bar, never tighter: a rep whose every bucket cleared 0.6
    // has at least 0.6 overall, so anything at or under that cannot reject a rep that was right
    // everywhere the checkpoints looked. 0.5 is the plain-language line - the patient spent more of
    // the rep out of position than in it. Pinned by TrajectoryConformanceTest.
    //
    // Uncalibrated against real bodies - see C3.
    const val TRAJECTORY_MIN_REP_CONFORMANT_FRACTION = 0.5

    // 10.5. How clearly a body must show which way it faces, in torso lengths, before a ghost is
    // placed over it. Frontal: the shoulder line across the torso axis. Sagittal: the nose ahead of
    // the hips. Below this the horizontal axis could go either way, and a leading ghost drawn the
    // wrong way round is copied - so nothing is drawn. Deliberately small: it only has to refuse the
    // undecidable, not judge the view, which classifyView and requiredView already do.
    // Uncalibrated; 10.6 owns tuning it against real recordings.
    const val GHOST_FACING_MARGIN = 0.05f

    // 10.7. The furthest a patient's limb may differ from the physio's, as a ratio of lengths in
    // each body's own torso lengths, before the measurement is distrusted and the ghost keeps the
    // physio's length. Adult limb-to-torso proportions vary by well under this; a ratio past it is
    // a landmark that slid (a wrist found on the hip), not a differently built person. Uncalibrated.
    const val GHOST_RETARGET_MAX_RATIO = 1.6f

    // 10.8. The ghost clock. All uncalibrated.
    // Count-in before the first demonstrated rep: long enough to read "get ready", short enough
    // that a patient already in position is not kept waiting.
    const val GHOST_COUNT_IN_SECONDS = 3.0
    // The beat at the start pose between demonstrated reps. The physio's own dwell at rest is
    // already inside 10.3's profile; this is only the seam between two cycles.
    const val GHOST_REST_SECONDS = 0.5
    // Whole demonstrated cycles with the patient never leaving IDLE before they are prompted to
    // move with the ghost. The ghost itself never stops for them (device feedback, 2026-10-03: a
    // patient who has paused needs the demonstration most, to know how to carry on).
    const val GHOST_IDLE_CYCLES = 1
    // A gap between frames longer than this (a stall, the app backgrounded) does not advance the
    // clock. Otherwise the ghost jumps whole reps ahead of a patient who never saw it move.
    const val GHOST_MAX_FRAME_GAP_SECONDS = 0.5
    const val PHASE_OVERSHOOT_MAX = 1.15
    const val PHASE_ENTRY = 0.10
    const val PHASE_ASCEND_MIN = 0.60
    const val PHASE_BOTTOM_HOLD_ENTRY = 0.70
    const val PHASE_REP_COMPLETE = 0.15
    const val BOTTOM_HOLD_STALL_SECONDS = 0.400
    const val BOTTOM_HOLD_ABANDON_SECONDS = 20.0
    const val VELOCITY_EMA_TAU_SECONDS = 0.100
    const val REP_DURATION_MIN_REF_FRACTION = 0.4
    const val REP_DURATION_MAX_REF_FRACTION = 3.0
    const val PHASE_UNCERTAINTY_UNITS = 3.0
    const val PHASE_UNCERTAINTY_MIN_VELOCITY = 0.02
    const val PHASE_UNCERTAINTY_MAX_SCALE = 3.0
    const val SIGMA_EFF_MIN_DEGREES = 0.5
    const val Z_ON = 3.0
    const val Z_OFF = 1.5
    const val FAULT_ENTRY_DWELL_SECONDS = 0.300
    const val FAULT_EXIT_DWELL_SECONDS = 0.500
    const val PER_CUE_COOLDOWN_SECONDS = 5.0
    const val GLOBAL_CUE_COOLDOWN_SECONDS = 3.0
    const val FRAME_SCORE_DECAY = 0.15
    const val Z_CLAMP = 6.0
    const val CRITICAL_PHASE_WEIGHT = 2.0

    // 6.16 correction arrow. The length maps |z| onto a fraction of ARROW_MAX_PX, floored so a real
    // but small error is still visible and capped so a landmark glitch cannot throw an arrow across
    // the screen. ARROW_MIN_Z is not from 6.16: it stops the overlay drawing a confident arrow for
    // a patient who is already inside the reference band.
    const val ARROW_MAX_PX = 160.0
    // 6.16's floor. Reachable, and load-bearing: while a corrected patient is inside ArrowSelector's
    // exit dwell their |z| is falling below 1.2, and this keeps the shrinking arrow visible instead
    // of letting it collapse to a dot before it disappears.
    const val ARROW_MIN_LENGTH_FRACTION = 0.2
    const val ARROW_Z_FULL_SCALE = 6.0
    // How much better a challenger must score before the arrow moves to it, and for how long. Two
    // features of similar score otherwise trade the arrow back and forth every few frames.
    // Range guidance. The dominant feature defines phase, so it is compared against itself and its
    // z is ~0 by construction: it can never earn a deviation arrow. What *is* meaningful for it is
    // how far the patient is from the range target for the part of the rep they are in.
    const val ARROW_RANGE_MIN_FRACTION = 0.15
    const val ARROW_RANGE_CLEAR_FRACTION = 0.08
    const val ARROW_RANGE_FULL_FRACTION = 0.5

    // Rep notes. A rep can satisfy 6.12.4's acceptance and still be worth commenting on.
    const val REP_OVERSHOOT_TOLERANCE = 0.10
    const val REP_FULL_RANGE_FRACTION = 0.95

    const val ARROW_SWITCH_MARGIN = 1.5
    const val ARROW_SWITCH_DWELL_SECONDS = 0.500
    const val CRITICAL_WINDOW_DEFAULT_START = 40
    const val CRITICAL_WINDOW_DEFAULT_END = 60
    const val CRITICAL_WINDOW_MAX_GRID_FRACTION = 0.5
    const val ROM_FULL_CREDIT_FRACTION = 0.95
    const val TEMPO_FAST_RATIO = 0.7
    const val TEMPO_SLOW_RATIO = 1.6
    const val TEMPO_FAST_PENALTY_FLOOR = 0.6
    const val TEMPO_SLOW_PENALTY_FLOOR = 0.8
    const val CUSUM_ALLOWANCE_SIGMA = 0.5
    const val CUSUM_THRESHOLD_SIGMA = 5.0

    // 6.18.2. The floor stops a pathologically tight baseline - three near-identical reps - from
    // scaling the detector down until it fires on nothing.
    const val FATIGUE_SCALE_FLOOR_POINTS = 3.0

    // 6.18.5 reports the top 2 drifting joints. More would be a list nobody reads; fewer would hide
    // a compensation pattern, which is usually a pair.
    const val FATIGUE_ATTRIBUTION_COUNT = 2

    // 6.18.6's safety intervention: quality under this fraction of baseline, twice running.
    const val FATIGUE_SAFETY_QUALITY_FRACTION = 0.7

    // 6.18.5's smoothness term. The spectrum is truncated where it first falls below the cutoff, or
    // at the frequency cap, whichever is lower.
    const val SPARC_MAGNITUDE_CUTOFF = 0.05
    const val SPARC_MAX_FREQUENCY_HZ = 10.0
    const val CORROBORATING_SLOPE_POINTS_PER_REP = -1.5
    // 6.19's break condition: a watched joint outside this many sigma, continuously, for this long.
    //
    // Tightened from 4.0 sigma / 1.0 s after device testing, where holds that had visibly collapsed
    // stayed HOLDING. Both numbers are in units of sigma derived from the physio's own recording,
    // so neither is an angle: raising HOLD_RECORD_DEFAULT_SECONDS from 5 s to 10 s widens sigma
    // (six seconds of measured window captures natural sway that one second missed), which offsets
    // part of this tightening. The two changes were made together and their net effect is an
    // empirical question, not an arithmetic one.
    //
    // The floor is 2.5. Re-entry needs every watched joint inside HOLD_QUALITY_Z = 2.0 for
    // HOLD_SETTLE_CONFIRM_SECONDS, and the gap between that and this is the hysteresis that stops a
    // break -> settle -> break oscillation. The live hold re-arms automatically on break, so a
    // margin lost here shows up as a visible loop rather than a subtle mis-score.
    const val HOLD_BREAK_Z = 3.0
    const val HOLD_BREAK_SECONDS = 0.6
    const val HOLD_DRIFT_SIGMA_PER_SECOND = 0.15

    // 6.19. Above this the patient is losing the position progressively rather than wobbling, and
    // the hold is ended rather than merely corrected - the hold-mode analogue of 6.18.6.
    const val HOLD_DRIFT_END_SIGMA_PER_SECOND = 0.30

    // 6.19 says the slope must be **sustained**, and the word carries weight. A least-squares fit
    // over the whole hold spikes the moment a transient lands - a flinch reads as a steep trend
    // until enough later samples arrive to flatten it again - so a hold would end on a wobble it
    // had already recovered from. Drift is a trend, and a trend is something that is still true two
    // seconds later.
    const val HOLD_DRIFT_SUSTAINED_SECONDS = 2.0

    // The hold starts when the patient reaches the position, not when they press the button. Held
    // briefly first, so that passing through the pose on the way somewhere else does not start the
    // clock.
    const val HOLD_SETTLE_CONFIRM_SECONDS = 0.5

    // A drift rate fitted across two seconds is dominated by the patient settling into the
    // position. Below this much hold there is not enough of a trend to end anything on.
    const val HOLD_DRIFT_MIN_WINDOW_SECONDS = 8.0

    // 6.19: "discard the first and last 2 seconds (getting into and out of position)".
    const val HOLD_TRIM_SECONDS = 2.0

    // 6.19's stability precondition: past this the physio was not actually holding still.
    const val HOLD_MAX_DOMINANT_MAD_DEGREES = 8.0

    // 6.19: HoldQuality is the fraction of hold time with every watched |z| under this.
    const val HOLD_QUALITY_Z = 2.0

    // 6.19 takes the top K by the substituted salience. 6.10's K for the cyclic path is the same.
    const val HOLD_WATCHLIST_SIZE = 5
    const val VIEW_RATIO_MIN = 0.25
    const val VIEW_RATIO_MAX = 0.35
    const val PROGRESSION_THRESHOLD = 0.90
    const val PROGRESSION_MIN_QUALITY = 80
    const val PROGRESSION_REQUIRED_SESSIONS = 2
    const val PROGRESSION_WINDOW_SESSIONS = 3

    // 6.20 calibration. A stricter visibility bar than the general 0.6 feature-validity floor
    // (VIS_MIN): calibration is asking "can we start", not "can we still abstain gracefully", so it
    // holds the patient to a cleaner frame before committing them to a set.
    const val CALIBRATION_VISIBILITY_MIN = 0.7f
    const val CALIBRATION_VISIBILITY_HOLD_SECONDS = 2.0
    const val CALIBRATION_BBOX_HEIGHT_MIN = 0.5
    const val CALIBRATION_BBOX_HEIGHT_MAX = 0.92
    const val CALIBRATION_CENTER_BAND_FRACTION = 0.6
    const val CALIBRATION_BOX_PRESENCE_MIN = 0.6f
    /** `|gravity_z| / |gravity| < this`, sin(10°) rounded, the plan's own "within 10° of vertical". */
    const val CALIBRATION_UPRIGHT_MAX_RATIO = 0.17
    const val CALIBRATION_PRESENCE_MIN = 0.75
    const val CALIBRATION_STATIONARY_SECONDS = 1.0
    const val CALIBRATION_RECHECK_SECONDS = 5.0
    const val CALIBRATION_RECALIBRATE_HOLD_SECONDS = 1.0
}
