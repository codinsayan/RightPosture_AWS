# RightPosture — Implementation Plan

**iQOO Hackathon 2026 · Pune City Battle · 5–6 September 2026**
**Track:** HealthTech
**Format:** 30 hours · 55% Red Light (phone only) / 45% Green Light

---

## Table of contents

0. [MVP scope](#0-mvp-scope)
1. [Problem context](#1-problem-context)
2. [What we are actually building](#2-what-we-are-actually-building)
3. [Scope decisions and cuts](#3-scope-decisions-and-cuts)
4. [Tech stack](#4-tech-stack)
5. [System architecture](#5-system-architecture)
6. [The mathematical core](#6-the-mathematical-core)
   - 6.1 [Conventions, units, and timing](#61-conventions-units-and-timing)
   - 6.2 [Landmark ingest and canonicalisation](#62-landmark-ingest-and-canonicalisation)
   - 6.3 [Filtering](#63-filtering)
   - 6.4 [The body reference frame](#64-the-body-reference-frame)
   - 6.5 [Feature extraction](#65-feature-extraction)
   - 6.6 [Validity and occlusion handling](#66-validity-and-occlusion-handling)
   - 6.7 [Offline: rep segmentation](#67-offline-rep-segmentation)
   - 6.8 [Offline: phase definition and resampling](#68-offline-phase-definition-and-resampling)
   - 6.9 [Offline: building the reference](#69-offline-building-the-reference)
   - 6.10 [Offline: watch-list selection](#610-offline-watch-list-selection)
   - 6.11 [Offline: reference validator](#611-offline-reference-validator)
   - 6.12 [Live: phase estimation state machine](#612-live-phase-estimation-state-machine)
   - 6.13 [Live: deviation scoring](#613-live-deviation-scoring)
   - 6.14 [Live: fault detection](#614-live-fault-detection)
   - 6.15 [Live: cue arbitration and escalation](#615-live-cue-arbitration-and-escalation)
   - 6.16 [Live: correction vector](#616-live-correction-vector)
   - 6.17 [Scoring: frame, rep, set](#617-scoring-frame-rep-set)
   - 6.18 [Fatigue and degradation detection](#618-fatigue-and-degradation-detection)
   - 6.19 [Hold mode](#619-hold-mode)
   - 6.20 [Calibration](#620-calibration)
   - 6.21 [Progression and goal state](#621-progression-and-goal-state)
   - 6.22 [Numerical guards](#622-numerical-guards)
7. [Parameter table](#7-parameter-table)
8. [Edge case register](#8-edge-case-register)
9. [The .rpose format](#9-the-rpose-format)
10. [LLM cue generation](#10-llm-cue-generation)
11. [On-device model deployment](#11-on-device-model-deployment)
12. [Android implementation](#12-android-implementation)
13. [Data model and sync](#13-data-model-and-sync)
14. [UI specification](#14-ui-specification)
15. [Hackathon rubric mapping](#15-hackathon-rubric-mapping)
16. [Build schedule](#16-build-schedule)
17. [Demo script](#17-demo-script)
18. [Risk register](#18-risk-register)
19. [Pre-event checklist](#19-pre-event-checklist)

---

## 0. MVP scope

**This document specifies a 30-hour hackathon prototype, not a shipping product.** Read every section with that in mind.

**In scope:** the four demo exercises, the full mathematical pipeline in §6, two roles, doctor↔patient sync, on-device pose and LLM inference, and a rehearsed demo.

**Explicitly out of scope for the city battle.** Not oversights — deliberate deferrals:

| Deferred | Why it's safe to defer |
|---|---|
| Consent flows, privacy policy, data-deletion, medical-device disclaimers | No real patients, no production deployment, no data leaves the team's own test accounts. Required before any real use; irrelevant to a 30-hour prototype. |
| Clinical validation of the tolerance bands | We claim the bands reflect the prescribing clinician's demonstrated tolerance, which is a defensible proxy. We do not claim clinical validity, and should not. |
| Compound multi-segment exercises | `compound` mode is reserved in the manifest schema so the format doesn't need a breaking change later. Not implemented. |
| Fine motor (hand, wrist, cervical) | Landmark noise exceeds the signal. Refused at authoring, by design. |
| Multi-set sessions with rest timers | The prescription schema carries `sets` and `rest_s`, but the live flow implements one set. If time permits, add rest timers and **reset the CUSUM baseline per set** — set 3 legitimately begins more fatigued than set 1, and carrying a baseline across sets would fire on every patient. |
| Voice command control | Stretch goal only. Build at hour 26 if there is slack, never before. |
| Accessibility, localisation, tablet layouts | Single phone form factor, en-IN only. |

**One thing that is in scope and easy to miss:** patient↔physio linking. The physio generates a six-character invite code; the patient enters it once. Fifteen minutes of work, and without it the demo flow has no way to connect the two roles. Build it in the hours 22–25 block.

**If the physio can't perform the exercise.** A judge with a clinical background will ask this — a 55-year-old physiotherapist prescribing a deep squat cannot demonstrate it. The manifest carries a `reference_source` field (`self` | `proxy` | `patient_best`) allowing the reference to be recorded by an assistant or taken from the patient's own best prior session. Having the answer ready matters more than having the feature; implement the field, defer the UI.

---

## 1. Problem context

### 1.1 The clinical problem

Home physiotherapy fails in three predictable ways:

1. **Non-adherence.** The patient skips sessions. Nobody knows.
2. **Incorrect execution.** The patient performs the movement wrong from rep one, reinforcing a compensation pattern that may worsen the original injury.
3. **Progressive form degradation.** The patient starts correctly and deteriorates as the set proceeds. Reps 1–4 are clean; by rep 8 they are recruiting compensatory muscles and the exercise has stopped delivering its therapeutic effect — and may have become actively harmful.

The third failure is the one nobody addresses. It is also the most clinically significant, because it is the mechanism by which a well-prescribed exercise produces a bad outcome. A physiotherapist watching in clinic sees it immediately and says "that's enough for today." At home, there is no such observer.

The physiotherapist, meanwhile, has no ground truth. At the follow-up appointment they ask "how did the exercises go?" and receive an answer that is optimistic, vague, and unverifiable.

### 1.2 What existing solutions miss

| Category | What it does | What it misses |
|---|---|---|
| Reminder apps | Push notifications, streak tracking | No verification that anything happened, let alone happened correctly |
| Rep counters | Accelerometer or vision-based counting | Counts a bad rep the same as a good one |
| Single-frame form checkers | "Is this pose correct, yes/no" | An exercise is a trajectory, not a pose. A snapshot at the wrong moment is meaningless |
| Generic fitness AI coaches | Population-average form models for squats, push-ups | Not the movement a physio prescribed, not the range this specific patient should be working in, no clinician in the loop |

None of them model **within-set quality decay**, and none of them are configured by the prescribing clinician.

### 1.3 Our thesis

> Verify both **compliance** (did the session happen) and **movement quality** (was it performed correctly), with specific emphasis on detecting the point within a set at which the patient's form begins to deteriorate — and report that finding back to the prescribing clinician.

### 1.4 Design constraints derived from the domain

These are not optional; they shape the architecture.

- **Wrong advice is worse than no advice.** Coaching a rehab patient into a bad movement pattern is an active harm. The system must be able to abstain.
- **The clinician stays in the loop.** The app never advances a patient's programme autonomously. It flags; the physio approves.
- **Explainability is mandatory.** A physiotherapist must be able to ask "why did you flag rep 6?" and receive a numerical answer. This rules out an opaque learned scorer as the primary mechanism.
- **Zero training data per exercise.** A physio must be able to invent an exercise on a Tuesday and have the system score it on the same Tuesday. Anything requiring a labelled dataset per exercise cannot ship.
- **Everything must run on the phone.** Both because it is the hackathon constraint and because home patients have unreliable connectivity and clinical data should not require a round trip.

---

## 2. What we are actually building

### 2.1 One sentence

A two-role Android application in which a physiotherapist records a reference performance of an exercise on their phone, from which the system automatically derives per-joint tolerance envelopes, and against which a patient's home performance is scored in real time with spoken corrective cues, on-screen ghost overlay, and within-set fatigue detection.

### 2.2 The central technical idea

**An exercise is not a pose. It is a phase-indexed trajectory through joint-angle space, with a tolerance envelope derived from the clinician's own rep-to-rep variance.**

Every downstream capability falls out of this single data structure:

| Capability | How it derives from the reference |
|---|---|
| Rep counting | Zero-crossings of the phase variable |
| "Is the form wrong right now" | Z-score against μ(p) ± σ(p) at current phase p |
| "What should they fix" | Sign of the z-score selects a pre-generated cue |
| "How near are they to correct" | Exponential of the weighted sum of squared z-scores |
| "When did they start degrading" | CUSUM change-point detection on per-rep quality |
| "Are they close to their goal" | Ratio of achieved range of motion to prescribed target |

### 2.3 Why the tolerance envelope is the key insight

Naïve form-checking apps hardcode thresholds: "knee angle must be between 80° and 95°." This fails because (a) somebody has to pick those numbers for every exercise, and (b) the numbers differ per patient and per exercise variant.

Instead: the clinician performs 6–8 reps. For each joint, at each point in the movement, we compute the mean **and the standard deviation across reps**. That standard deviation *is* the tolerance:

- Joints the clinician controlled tightly → small σ → tight band → we alert on small deviations.
- Joints that moved freely and inconsistently → large σ → wide band → we never alert, because that joint doesn't matter for this exercise.

**We never pick a threshold. The clinician's own movement picks it, implicitly, by demonstrating what they care about.**

### 2.4 Roles

**Physiotherapist**
- Record an exercise reference (the only manual step: press record, perform, press stop)
- Review auto-derived tolerance bands and cues; optionally adjust
- Assign exercises to patients, with target reps / sets / duration and a goal
- View per-patient dashboard: adherence, per-session quality, progression curves, fatigue findings
- Approve progression when the system flags a patient as goal-ready

**Patient**
- See today's prescribed exercises in order
- Watch the clinician's demo
- Calibrate camera position
- Perform, with live scoring, ghost overlay, correction arrow, and spoken cues
- Receive a post-set summary generated by the on-device LLM
- Data syncs to the clinician

---

## 3. Scope decisions and cuts

### 3.1 Explicitly cut, with reasons

| Originally planned | Decision | Reason |
|---|---|---|
| Custom ML model for rep detection | **Cut** | Zero-crossings on the phase variable are more reliable, need no training data, and generalise to any exercise the clinician records. A model trained in 30 hours would be worse. |
| ML model to generate intermediate goal poses | **Cut** | The intermediate target is `lerp(baseline_angles, goal_angles, progress)` — linear interpolation in angle space. It is arithmetic, not machine learning. |
| Real-time LLM cue generation | **Cut** | Latency and non-determinism in the safety-critical path. Cues are LLM-generated **once at authoring time** and cached. |
| Hand / wrist / finger rehabilitation | **Out of scope** | Body-pose landmark noise is on the order of centimetres, which swamps a 15° wrist deviation. Declaring this out of scope is a credibility gain, not a loss. |
| Compound multi-segment exercises | **Deferred** | Architecture supports it (`compound` mode in the manifest) but we do not build it for the city battle. |
| Voice command control | **Stretch goal** | Nice for the "creative phone use" score, but not load-bearing. Build only if hour 26 arrives with slack. |

### 3.2 The three scoring modes

Not every exercise is a rep-based cycle. The manifest declares one of:

| Mode | Covers | Phase axis? | Coverage of ortho rehab |
|---|---|---|---|
| `cyclic` | Knee extension, squat, shoulder flexion, hip abduction, heel raise, sit-to-stand, bicep curl | Yes, 0–100 per rep | ~70% |
| `hold` | Plank, wall sit, bridge hold, single-leg balance, postural correction | No — reference is a single (μ, σ) per joint | ~20% |
| `compound` | Lunge-to-press, get-up sequences, gait drills | Chained per-segment phase | ~10% (deferred) |

`hold` is **simpler** than `cyclic`, not harder — drop phase estimation entirely, and the fatigue signal becomes drift slope over hold duration.

### 3.3 Demo exercise set (build and validate these four)

| Exercise | Mode | Required view | Why it's in the set |
|---|---|---|---|
| Seated knee extension | `cyclic` | Sagittal | Textbook case, clean signal, easy to demo |
| Standing hip abduction | `cyclic` | Frontal | Proves the view-handling and unilateral logic work |
| Wall sit | `hold` | Sagittal | Proves the second scoring path exists |
| Sit-to-stand | `cyclic` | Sagittal | Functional, largest ROM, best fatigue-degradation story |

Four exercises that work flawlessly, plus a validator that visibly rejects a bad recording on stage, beats a claim of forty exercises. The brief states this explicitly: *a well-built simple product beats a broken complex one.*

---

## 4. Tech stack

### 4.1 Decisions

| Layer | Choice | Rationale |
|---|---|---|
| Platform | **Native Android, Kotlin** | The entire value chain is camera → NPU → geometry → overlay. Every cross-language bridge sits directly on that path. |
| UI | **Jetpack Compose** | Fast to write; Canvas overlay composes cleanly over the camera preview. |
| Camera | **CameraX** `ImageAnalysis` | Backpressure handling, lifecycle-aware, standard MediaPipe integration path. |
| Pose estimation | **MediaPipe Pose Landmarker** (Tasks Vision) | 33 landmarks, world (metric) output, mature Android API. |
| NPU acceleration | **Qualcomm AI Hub → MediaPipe Pose** | Pre-compiled for Hexagon. Detector ~1 ms, landmark model ~1 ms on NPU. |
| On-device LLM | **MediaPipe LLM Inference API**, Gemma-class LiteRT model | Authoring-time cue generation + post-set summaries. Off the real-time path. |
| Local persistence | **Room** | Sessions, reps, faults, cue logs. |
| Sync | **Firebase Firestore + Storage** | Fastest path to doctor↔patient sync in a hackathon. Storage for `.rpose` bundles and demo video. |
| Auth | **Firebase Auth**, email/password, role claim | Two roles: `physio`, `patient`. |
| TTS | **Android `TextToSpeech`** | `QUEUE_FLUSH`, en-IN locale. |
| Charts | **Vico** or hand-drawn Compose Canvas | Reference band chart, progression curves. |

### 4.2 Why not Flutter or React Native

The pose pipeline runs at 30 fps. In Flutter you would either copy image frames across the platform channel (unacceptable) or write the entire pipeline in Kotlin anyway and expose only results — at which point the Dart layer buys you nothing but an extra failure mode. Additionally the QNN delegate integration for NPU acceleration is Android-native; wrapping it in a plugin during a 30-hour event with Red Light laptop restrictions is a bad trade.

**Fallback position:** if the team hits a wall on native Android UI velocity, the compromise is pipeline-in-Kotlin, UI-in-Flutter, passing an immutable `FrameResult` struct (≈200 bytes) per frame. Never pixels. This is a contingency, not the plan.

### 4.3 Module structure

```
app/
  ui/                       Compose screens, navigation
  ui/overlay/               SkeletonOverlay, GhostOverlay, CorrectionArrow
core/
  pose/                     PoseLandmarkerSource, LandmarkFrame
  filter/                   OneEuroFilter, ButterworthFiltFilt
  geom/                     BodyFrame, AngleMath, FeatureExtractor
  reference/                RepSegmenter, PhaseResampler, ReferenceBuilder,
                            WatchListSelector, ReferenceValidator
  live/                     PhaseTracker, DeviationScorer, FaultDetector,
                            CueArbiter, RepScorer
  analysis/                 CusumDetector, Attribution, ProgressionEngine
  rpose/                    RposeWriter, RposeReader, Manifest
  llm/                      CueGenerator, SummaryGenerator, OutputValidator
data/
  local/                    Room entities, DAOs
  remote/                   Firestore repositories
```

---

## 5. System architecture

### 5.1 Two pipelines

**Authoring pipeline (offline, physio's phone).** Batch. Has the whole recording available before deciding anything. Can iterate, revise, reject.

```
Record video + landmark stream
  → Butterworth zero-phase filter (offline, no lag penalty)
  → Feature extraction (joint angles)
  → Dominant joint selection (coarse)
  → Autocorrelation → rep period estimate
  → Peak/valley detection with period constraint
  → Rep segmentation
  → Refine dominant joint using clean segments (2nd pass)
  → Phase-based resampling to 101 bins
  → Reference statistics: μ[101], σ[101] per feature
  → σ smoothing, flooring, capping
  → Watch-list selection by salience
  → VALIDATOR → accept or reject with reason
  → LLM cue generation (batched, once)
  → Write .rpose bundle
```

**Live pipeline (streaming, patient's phone).** No lookahead. Must decide per frame.

```
CameraX frame
  → Pose Landmarker (NPU)
  → One Euro filter (low-latency)
  → Canonicalisation (mirror, active-side)
  → Body frame construction
  → Feature extraction
  → Validity gating
  → Phase tracker state machine
  → μ/σ lookup + interpolation at phase p
  → Z-scores with phase-uncertainty inflation
  → Fault detection (hysteresis + dwell)
  → Cue arbitration (masking, priority, cooldown, escalation)
  ├→ TTS out
  ├→ Overlay state (skeleton colour, ghost, arrow)
  └→ Rep accumulator → rep score → CUSUM → session record
```

### 5.2 Threading

- **Camera thread** — CameraX `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`.
- **Inference executor** — single-threaded. Pose inference plus the entire scoring chain runs here; the scoring maths is microseconds and does not warrant a second hop.
- **Main thread** — collects an immutable `FrameResult` from a `StateFlow`, renders Compose Canvas, drives TTS.
- **LLM executor** — separate single thread, never touched during a live set.

`FrameResult` is a data class holding: landmark array, feature array, validity mask, phase, z-vector, active fault, overlay directives, rep counter. It is immutable and allocated per frame from a small object pool to avoid GC pressure at 30 fps.

---

## 6. The mathematical core

This is the section that matters. Everything here should be implemented with the stated guards, in this order.

### 6.1 Conventions, units, and timing

**Angles** in degrees throughout the pipeline (easier to reason about clinically, easier to debug). Convert to radians only inside trigonometric calls.

**Timestamps.** Use camera frame timestamps in nanoseconds, never frame indices. Frames *will* be dropped under thermal load, and any derivative computed against a frame count will be silently wrong.

```
dt = (t_n - t_{n-1}) / 1e9      // seconds
```

Guard: if `dt <= 0` or `dt > 0.25`, treat as a discontinuity — reset filter velocity states and mark the frame as a gap. Do not compute derivatives across a gap.

**Nominal rate** 30 fps. All time constants below are expressed in seconds, not frames.

**Coordinate system.** MediaPipe world landmarks are metres, origin at the hip midpoint. The axis orientation follows the image convention (x right, y **down**, z toward/away from camera depending on version).

> **Do not trust the documentation on axis signs.** In hour 1 on the actual loaner device, log the raw landmark values for a person standing upright with the right arm raised, and empirically determine the sign of each axis. Write the discovered convention into a single `AxisConvention` constant and derive everything from it. Getting this wrong late costs hours and produces cues that instruct the patient to move in exactly the wrong direction.

### 6.2 Landmark ingest and canonicalisation

Per frame we receive:
- `worldLandmarks[33]` — (x, y, z) in metres, hip-centred
- `landmarks[33]` — normalised image coordinates (for overlay drawing only)
- `visibility[33]`, `presence[33]` — in [0, 1]

**Use world landmarks for all geometry. Use image landmarks only for drawing.** Image landmarks are perspective-distorted; angles computed from them depend on where in the frame the person stands.

**Mirroring.** The front camera produces a mirrored image.

> **`camera_facing` and `mirrored` in the manifest describe the physio's recording only.** The patient's session has its own, independent facing, read from CameraX at runtime. The manifest field is *not* an instruction to the patient's session about which camera to use. Canonicalisation is applied separately on both sides — once when building the reference, once per live frame — so a rear-camera reference and a front-camera session interoperate correctly. Reading the manifest value as a runtime setting would mirror the patient's feed to match the physio's, which is exactly backwards.

Canonicalise at ingest so that everything downstream is agnostic:

```
if (mirrored) {
    x = -x                              // for all landmarks
    swap(LEFT_*, RIGHT_*)               // relabel the landmark indices
}
```

Both operations are required. Negating x without relabelling leaves you with a left knee at the right knee's position.

**Recommendation:** use the **front camera** for the patient session. Being able to see yourself is a large usability win for rehab, and it matches the mirror a physio uses in clinic. Use the rear camera for the physio's recording only if the physio needs someone else to hold the phone. Canonicalisation makes the two interoperable.

**Active-side resolution (unilateral exercises).** For exercises performed one side at a time (hip abduction, single-leg balance), determine the active side per rep:

```
rangeL = max(θ_dom_L) - min(θ_dom_L)  over the first 25% of the rep
rangeR = max(θ_dom_R) - min(θ_dom_R)  over the same window
active = (rangeL > rangeR) ? LEFT : RIGHT
```

Then remap features into canonical `(active, passive)` slots. This means **one reference serves both sides** — critical, because otherwise the physio has to record twice and the patient's left/right asymmetry pollutes the comparison.

Guard: require `max(rangeL, rangeR) > 1.5 × min(rangeL, rangeR)` to declare a side. If both sides move similarly, the exercise is bilateral — fall back to the manifest's declared laterality.

### 6.3 Filtering

Two different filters for two different jobs. Do not use one for both.

#### 6.3.1 Live: One Euro filter

The problem with a moving average is that it forces a trade between jitter suppression and lag. You need both: rock-steady angles at the top of a rep (where the patient pauses) and zero lag through the fast portion. The One Euro filter adapts its cutoff to the signal velocity, giving you both.

Applied **per landmark coordinate** (99 scalar channels), before any geometry.

```
// Low-pass with adaptive cutoff
alpha(cutoff, dt):
    tau = 1 / (2 * PI * cutoff)
    return 1 / (1 + tau / dt)

// State per channel: xPrev, dxPrev, initialised = false
filter(x, dt):
    if !initialised:
        xPrev = x; dxPrev = 0; initialised = true; return x

    dx        = (x - xPrev) / dt
    dxHat     = alpha(D_CUTOFF, dt) * dx + (1 - alpha(D_CUTOFF, dt)) * dxPrev
    cutoff    = FC_MIN + BETA * abs(dxHat)
    xHat      = alpha(cutoff, dt) * x + (1 - alpha(cutoff, dt)) * xPrev

    xPrev = xHat; dxPrev = dxHat
    return xHat
```

Parameters: `FC_MIN = 1.0 Hz`, `BETA = 0.02`, `D_CUTOFF = 1.0 Hz`.

**Tuning procedure** (do this in hour 3, not hour 20): set `BETA = 0` and lower `FC_MIN` until a *stationary* pose stops jittering. Then raise `BETA` until a *fast* movement stops lagging. Two knobs, ten minutes, done.

Reset the filter state on a gap (`dt > 0.25 s`) or on person re-detection.

#### 6.3.2 Offline: zero-phase Butterworth

For the authoring pipeline, use the biomechanics standard: **4th-order low-pass Butterworth at 6 Hz, applied forward and backward** (`filtfilt`). Forward-backward application cancels the phase distortion, which matters because we are locating peaks and a phase-shifted peak is a mislocated rep boundary.

6 Hz is the conventional cutoff for human whole-body movement; essentially all voluntary limb motion is below it, and everything above is noise.

Implementation note: Kotlin has no `scipy.signal`. Implement a 4th-order Butterworth as two cascaded biquad sections with coefficients computed from the bilinear transform, then run the sequence forward, reverse it, run it again, reverse it back. Handle edge transients by padding the signal with a reflected copy of the first and last 3×order samples before filtering and trimming after.

Do not substitute a moving average here — it will shift your peaks.

### 6.4 The body reference frame

The purpose: make every angle invariant to which way the patient is facing and where they stand.

Given `LHip, RHip, LShoulder, RShoulder` in world coordinates:

```
midHip      = (LHip + RHip) / 2
midShoulder = (LShoulder + RShoulder) / 2

// Longitudinal axis (torso up-vector)
yRaw = midShoulder - midHip
ŷ    = yRaw / |yRaw|

// Medio-lateral axis, orthogonalised against ŷ (Gram–Schmidt)
xRaw = RHip - LHip
xOrth = xRaw - (xRaw · ŷ) ŷ
x̂    = xOrth / |xOrth|

// Antero-posterior axis
ẑ = x̂ × ŷ
```

**The Gram–Schmidt step is not optional.** The hip-to-hip vector is not exactly perpendicular to the torso axis (pelvis tilt, landmark noise), and skipping orthogonalisation gives you a non-orthonormal basis whose inverse is not its transpose — every projected angle then carries a shear error that varies with posture.

Rotation matrix (world → body): `R = [x̂ᵀ; ŷᵀ; ẑᵀ]` (rows are the basis vectors).

```
v_body = R · v_world
```

**Degeneracy guards:**

| Condition | Meaning | Action |
|---|---|---|
| `\|yRaw\| < 0.10 m` | Torso collapsed — bad detection or extreme foreshortening | Reuse previous frame's `R`; if >500 ms, mark frame invalid |
| `\|xOrth\| < 0.05 m` | Person is nearly edge-on; hips project onto each other | Reuse previous `R`; this is expected in sagittal-view exercises and is fine as long as it's smooth |
| Any of the four landmarks `visibility < 0.5` | Frame unusable for frame construction | Reuse previous `R`, mark frame degraded |

Smooth `R` across frames by applying the One Euro filter to the basis vectors and re-orthonormalising, or by slerping the corresponding quaternion. Raw per-frame `R` is noisy enough to inject a degree or two into every projected angle.

### 6.5 Feature extraction

We compute **14 scalar features** per frame. Not all are used for every exercise — the watch-list selector picks per exercise.

#### 6.5.1 Three-point joint angle (unsigned)

For a joint at `B` with neighbours `A` and `C`:

```
v1 = A - B
v2 = C - B
θ  = atan2( |v1 × v2| , v1 · v2 ) × 180/π
```

**Use the `atan2` form, not `acos(v1·v2 / (|v1||v2|))`.** The `acos` form loses precision catastrophically as the argument approaches ±1 — exactly at full extension, which is precisely where knee-extension exercises live. At θ = 178°, `acos` on float32 has an error of several degrees. `atan2` is well-conditioned across the whole range.

Range: [0°, 180°]. No wraparound possible, so no unwrapping needed.

#### 6.5.2 Signed planar angle

For deviations that have a meaningful direction (knee valgus/varus, hip drop), project into a plane of the body frame and compute a signed angle:

```
signedAngle(v1, v2, n̂):
    v1p = v1 - (v1 · n̂) n̂          // project out of plane
    v2p = v2 - (v2 · n̂) n̂
    cross = v1p × v2p
    return atan2( cross · n̂ , v1p · v2p ) × 180/π
```

Range: (−180°, 180°]. For anatomical deviations we expect small values; **if |θ| > 90°, mark the feature invalid** rather than trusting a wrapped value.

#### 6.5.3 The feature set

| # | Feature | Definition | Type |
|---|---|---|---|
| 1 | `knee_flex_active` | Angle(hip, knee, ankle), active side | Unsigned 3-point |
| 2 | `knee_flex_passive` | Same, passive side | Unsigned 3-point |
| 3 | `knee_frontal_active` | Signed angle of shank vs thigh projected onto frontal plane (normal = `ẑ`). Positive = valgus (inward) | Signed planar |
| 4 | `hip_flex_active` | Angle(shoulder, hip, knee), active side | Unsigned 3-point |
| 5 | `hip_flex_passive` | Same, passive side | Unsigned 3-point |
| 6 | `hip_abduction_active` | Signed angle of thigh vs torso axis in frontal plane | Signed planar |
| 7 | `trunk_flexion` | Angle between `ŷ` and the vertical reference (see 6.5.4) | Unsigned |
| 8 | `trunk_lateral` | Signed lean in the frontal plane | Signed planar |
| 9 | `trunk_rotation` | Signed angle between shoulder line and hip line about `ŷ` | Signed planar |
| 10 | `pelvic_obliquity` | Signed angle of the hip line vs horizontal in the frontal plane. Detects hip drop / hike | Signed planar |
| 11 | `shoulder_flex_active` | Angle(hip, shoulder, elbow) | Unsigned 3-point |
| 12 | `shoulder_elevation` | Vertical displacement of acromion relative to torso length (dimensionless ratio × 100) | Ratio |
| 13 | `elbow_flex_active` | Angle(shoulder, elbow, wrist) | Unsigned 3-point |
| 14 | `ankle_dorsiflex_active` | Angle(knee, ankle, foot_index) | Unsigned 3-point |
| 15 | `lumbar_flexion` | Signed angle between the shoulder-line normal and the hip-line normal in the sagittal plane. Positive = lumbar flexion (rounding); negative = lumbar extension (arching) | Signed planar |

Feature 12 is a ratio rather than an angle because shoulder shrug has no clean three-point definition. Normalising by torso length makes it body-size invariant.

**Feature 15 carries a reliability caveat.** MediaPipe provides no spine landmarks between the shoulders and the hips, so `lumbar_flexion` is inferred from the relative orientation of the two available lines. It is a coarse proxy: it will detect gross rounding or arching but not segmental spinal motion. Apply `σ_floor = 6°` to this feature specifically (rather than the general 2.5° floor) so its wider intrinsic noise does not generate spurious faults. It exists so that the `lumbar_flexion masks shoulder_flexion` rule has something real to bind to; **none of the four demo exercises exercises this rule**, so if it proves noisy on device, drop feature 15 and the corresponding mask rule together rather than debugging it during the event.

#### 6.5.4 The vertical reference problem

`trunk_flexion` needs a "vertical." Three options, in descending order of preference:

**(a) Gravity from the IMU.** Read Android's `TYPE_GRAVITY` sensor, transform from device frame into camera frame using the camera sensor orientation and current display rotation, and use `−ĝ` as vertical. Correct, but fiddly, and the device→camera transform is a real source of sign errors.

**(b) Calibration-locked vertical.** During calibration, verify the phone is within ±10° of upright using `TYPE_GRAVITY` (a single scalar check, no frame transform needed), then assume image-vertical is world-vertical for the whole session. Re-check every 5 seconds; if the phone is bumped, pause and ask the patient to reposition.

**(c) Body-intrinsic only.** Avoid gravity entirely by using hip flexion (trunk relative to thigh) instead of trunk flexion (trunk relative to world). Fully robust, but loses the ability to distinguish "leaning forward" from "sitting back," which matters clinically.

**Recommendation: (b) as the primary, with (c) as the always-available fallback.** Option (b) gets you the clinically meaningful measurement with one scalar check, and the calibration gate you need anyway makes the assumption safe. Attempt (a) only if the schedule permits.

**Critical edge case: non-upright exercises.** For supine, prone, or side-lying exercises, the pose model's landmark quality degrades (training data is predominantly upright people), and any feature measured against world-vertical becomes meaningless. The manifest declares `body_position`; for non-upright exercises:

- **Disable feature 7 (`trunk_flexion`) only.** It is the sole gravity-referenced feature. Features 8 (`trunk_lateral`) and 10 (`pelvic_obliquity`) are computed entirely within the body frame and remain valid in any body position — an earlier draft of this plan incorrectly grouped them with feature 7.
- Inflate all σ by `k_inflate = 1.3` instead of 1.15, to account for degraded landmark quality
- Warn the physio at authoring time that accuracy is reduced

### 6.6 Validity and occlusion handling

Every feature carries a validity flag. A feature is **valid** for a frame iff:

1. All contributing landmarks have `visibility ≥ 0.6` **and** `presence ≥ 0.6`
2. All contributing vectors have magnitude `≥ 1e-3` m
3. The computed value is finite and within its declared range
4. The body frame was constructible this frame (or reused within 500 ms)

**Downstream consequences of an invalid feature:**

| Consumer | Behaviour on invalid |
|---|---|
| Z-score | Not computed. No fault can fire on an invalid feature. |
| Frame score | Feature excluded; remaining weights **renormalised** so the score stays on a 0–100 scale |
| Phase tracker | If the *dominant* feature is invalid, phase is frozen at its last value and marked stale |
| Rep scoring | If >30% of a rep's frames had the dominant feature invalid, the rep is **counted but unscored** — excluded from Q statistics and from CUSUM |
| UI | If any watched feature is invalid for >500 ms, display "I can't see your left leg" and suppress all cues |

**Abstention is a feature.** Silence beats a wrong correction. Never guess through an occlusion.

**Occlusion recovery.** When a feature returns to validity, do not immediately resume scoring: require 300 ms of continuous validity first. Otherwise a flickering landmark produces a stream of spurious faults at the recovery boundary.

### 6.7 Offline: rep segmentation

The authoring pipeline has the whole recording. Use that.

#### 6.7.1 Breaking the circularity

There is an apparent chicken-and-egg problem: you need rep boundaries to compute range of motion cleanly, and you need the dominant joint (chosen by range of motion) to find rep boundaries.

It breaks because **the coarse choice is robust.** Across the whole recording, take the raw peak-to-peak swing of each feature:

```
swing_j = percentile(θ_j, 95) - percentile(θ_j, 5)
```

Use 5th/95th percentiles rather than min/max so a single spurious frame cannot win. The exercise's principal joint will have a swing an order of magnitude above the noise joints, so the coarse ranking is correct even with contaminated boundaries.

Then run the full pipeline and, once you have clean segments, **recompute swing using only the clean segments and confirm or switch the dominant feature.** Two passes; it converges immediately.

#### 6.7.2 Period estimation by autocorrelation

Let `s[n]` be the dominant feature over the recording, mean-removed and filtered. Compute normalised autocorrelation:

```
r[k] = Σ_{n=0}^{N-k-1} s[n]·s[n+k]  /  sqrt( Σ s[n]² · Σ s[n+k]² )
```

for `k` in `[k_min, k_max]` where `k_min = round(0.5 s × fps)` and `k_max = round(8.0 s × fps)`.

The per-lag normalisation (rather than dividing by `r[0]`) removes the triangular bias that would otherwise make short lags spuriously dominant.

**Period `T` = the lag of the first local maximum with `r[k] ≥ 0.4`.**

Take the *first* qualifying local maximum, not the global one — the global maximum can land on 2T for a highly periodic signal, halving your rep count.

**Failure handling:** if no lag reaches 0.4, the recording is not periodic. Fall back to peak detection without a period constraint and let the validator catch the mess, or offer `hold` mode.

#### 6.7.3 Peak and valley detection

Find local extrema of `s[n]`, ranked by **prominence** (the vertical drop required to reach a higher peak in either direction), then greedily accept:

```
candidates = localMaxima(s) sorted by prominence descending
accepted = []
for c in candidates:
    if all(|c.index - a.index| >= 0.6 * T for a in accepted):
        accepted.add(c)
```

The `0.6 × T` minimum spacing constraint is the single highest-value rule in this section — it eliminates almost all false splits from mid-rep wobble.

Also require `prominence ≥ 0.25 × swing_dominant` to reject noise peaks in a low-amplitude region.

Repeat for local minima.

#### 6.7.4 Enforcing alternation

Merge peaks and valleys into a single time-ordered list. The sequence must alternate. Where it doesn't:

- **Two peaks in a row** → a valley was missed. Insert the global minimum of `s` between them.
- **Two valleys in a row** → symmetric fix.
- **Still degenerate after repair** → drop the offending pair and log it.

This self-corrects most residual errors.

#### 6.7.5 Rep boundaries

A rep runs **valley → valley** (for exercises starting at the extended position, like a squat) or **peak → peak**, determined by which produces more complete segments. Convention: define rep boundaries at the **start position**, i.e. the extremum matching the pose at t=0.

#### 6.7.6 Outlier rejection

You now have N candidate reps.

1. **Drop the first and last rep unconditionally.** The first is the physio settling into position; the last is them reaching for the stop button. This is not a heuristic to be clever about — it is reliably true and costs you nothing when you asked for 8 reps.
2. **Duration outliers:** compute `medianT` over remaining reps. Drop any rep with `|T_i − medianT| > 0.35 × medianT`.
3. **Shape outliers:** after resampling (6.8), compute each rep's RMS deviation from the provisional mean trajectory of the dominant feature. Drop reps beyond `median + 2.5 × MAD`.
4. Require at least **4 surviving reps**. Below that, reject the recording.

Use MAD (median absolute deviation, scaled by 1.4826) rather than standard deviation for outlier thresholds. With 6–8 samples, a single bad rep inflates the standard deviation enough to hide itself.

### 6.8 Offline: phase definition and resampling

#### 6.8.1 The consistency requirement

**This is the subtlest correctness issue in the whole system.**

If the authoring pipeline indexes the reference by *elapsed time within the rep*, but the live pipeline estimates phase from the *dominant joint's value*, the two are inconsistent. A patient who moves at a different tempo — which is the entire point — would then be compared against the wrong part of the reference, and every z-score would be wrong in a tempo-dependent way.

**Therefore: define phase from the dominant feature's value, in both pipelines, identically.**

#### 6.8.2 Phase definition

Within a rep, let `θ_start` be the dominant feature's value at the rep boundary and `θ_peak` its value at the mid-rep extremum. Define:

```
φ(t) = clamp( (θ_dom(t) − θ_start) / (θ_peak − θ_start), 0, 1 )
```

`φ` runs 0 → 1 → 0 across the rep. Split the rep at the extremum into an outbound and a return half, and map:

```
p(t) = 50 · φ(t)          on the outbound half
p(t) = 100 − 50 · φ(t)    on the return half
```

`p ∈ [0, 100]`.

#### 6.8.3 Enforcing monotonicity

`φ` derived from a noisy signal is not monotonic — it wobbles, which makes `p` non-monotonic, which makes resampling ill-defined. Fix by enforcing monotonicity per half-rep:

```
outbound: φ[n] = max(φ[n], φ[n-1])      // cumulative maximum
return:   φ[n] = min(φ[n], φ[n-1])      // cumulative minimum
```

This is legitimate: within a half-rep the movement genuinely is monotonic in the dominant joint, and any reversal is either noise or a tremor we do not want to model as phase.

#### 6.8.4 Resampling

For each surviving rep, resample every feature onto `P = 101` phase bins (`p = 0, 1, …, 100`) by linear interpolation:

For bin `p`, find the bracketing frames `n, n+1` with `p(t_n) ≤ p ≤ p(t_{n+1})`, and interpolate:

```
w      = (p − p(t_n)) / (p(t_{n+1}) − p(t_n))
θ_j[p] = (1 − w)·θ_j(t_n) + w·θ_j(t_{n+1})
```

Guard: if `p(t_{n+1}) − p(t_n) < 1e-6` (the movement stalled), take `θ_j(t_n)`.

**Edge case — the pause at end range.** If the physio holds at the extremum, many frames map to phase bins 49–51 and the surrounding bins are sparse. This is handled correctly by interpolation, but note that the reference will then have very small σ near the extremum (the physio was stationary and consistent there), which is *correct* — the end-range position genuinely is the tightly-controlled part.

**Edge case — undershoot.** If a rep does not reach the same peak as the others, `φ` still reaches 1 for that rep (it's normalised per-rep). This would hide range-of-motion differences. **Therefore: normalise `φ` against the rep's own extremum for phase purposes, but record the absolute `ROM_i = |θ_peak,i − θ_start,i|` separately.** Phase alignment and range-of-motion measurement are different jobs; do not conflate them.

### 6.9 Offline: building the reference

For each feature `j` and each phase bin `p`, over the `N` surviving reps:

#### 6.9.1 Central tendency

```
μ_j[p] = median over reps of θ_j,i[p]
```

Use the median, not the mean. With N = 5–7, one atypical rep that survived outlier rejection still shifts a mean noticeably.

#### 6.9.2 Dispersion

```
residual_j,i[p] = θ_j,i[p] − μ_j[p]
σ_raw_j[p]      = 1.4826 × median_i( |residual_j,i[p]| )
```

MAD-based scale, which is robust and consistent with the median centre.

**Small-sample correction.** With N < 8, MAD is a noisy estimator. Apply a finite-sample correction factor:

| N | Correction |
|---|---|
| 4 | 1.36 |
| 5 | 1.21 |
| 6 | 1.14 |
| 7 | 1.10 |
| 8+ | 1.06 |

Multiply `σ_raw` by the appropriate factor.

#### 6.9.3 Smoothing σ across phase

`σ_j[p]` computed independently per bin is jagged — a bin where the reps happened to agree gets an artificially tight band, producing a spurious fault for the patient at exactly that phase.

Apply a **moving median of window 9 phase bins, then a moving average of window 9**, with reflective padding at the ends:

```
σ_smooth_j[p] = movingAverage(movingMedian(σ_raw_j, 9), 9)[p]
```

Median first to kill single-bin spikes, mean second to make the curve continuous (important, because we differentiate μ in 6.13 and need σ to behave smoothly alongside it).

#### 6.9.4 Floor, cap, and inflation

```
ROM_j     = max_p(μ_j[p]) − min_p(μ_j[p])
σ_floor_j = max(2.5°, 0.05 × ROM_j)
σ_j[p]    = clamp(σ_smooth_j[p], σ_floor_j, 15.0°) × k_inflate
```

- **Floor** prevents divide-by-near-zero and absurd sensitivity where the physio happened to be perfectly consistent. The `0.05 × ROM` term scales the floor with the exercise's amplitude.
- **Cap at 15°** — beyond this the joint is effectively unconstrained. Such features are dropped from the watch list entirely rather than carried with a meaningless band.
- **Inflation `k_inflate = 1.15`** accounts for the fact that the reference comes from the physio's body, not the patient's. Joint angles are largely anthropometry-invariant (that is why we use them instead of positions), but limb-length ratio differences leave a small residual. 15% is the pragmatic allowance.
  - For non-upright exercises, use `k_inflate = 1.3` (see 6.5.4).
- **`lumbar_flexion` overrides the floor** to 6.0° (see §6.5.3).

> **Order of operations is deliberate: inflation is applied AFTER the clamp, not inside it.**
> The effective ceiling on `σ_j[p]` is therefore `15.0 × 1.15 = 17.25°` for upright exercises and `15.0 × 1.30 = 19.50°` for non-upright ones — not 15°.
>
> This is intentional. The cap at 15° expresses "beyond this the physio was not controlling the joint," which is a statement about the *recording*. The inflation expresses "the patient is not the physio," which is a statement about *transfer*. They are independent corrections and both should apply. A feature that hits the cap has already been excluded from the watch list by §6.10 filter 2, so the inflated ceiling only ever affects features that are carried but not scored.
>
> Do not "fix" this by moving `k_inflate` inside the `clamp()` during a refactor. If you change it, the watch-list filter threshold in §6.10 must change with it or the two will disagree.

#### 6.9.5 The anthropometry tension — do not auto-calibrate it away

It is tempting to measure the patient's constant offset from the reference in session 1 and subtract it. **Do not do this on watched joints.** A persistent constant offset is exactly what a genuine movement limitation looks like; normalising it away means the app cheerfully reports perfect form for a patient who cannot reach the prescribed range.

The correct handling: leave the offset in place, and surface it to the physio as a finding — *"patient's trunk flexion runs consistently 11° above reference across all sessions"* — which is clinically actionable information, not noise to be removed.

#### 6.9.6 Reference derivative

Precompute and store `dμ_j/dp` by central difference, since we need it for phase-uncertainty inflation at runtime:

```
dμ_j[p] = (μ_j[p+1] − μ_j[p−1]) / 2        // interior
```

with one-sided differences at `p = 0` and `p = 100`.

### 6.10 Offline: watch-list selection

The physio does not tell us which joints matter. We infer it: **joints they controlled tightly relative to how much they moved are the joints they cared about.**

```
salience_j = ROM_j / (median_p σ_j[p])
```

Rank all features by salience, then filter:

**Every row below is a PASS condition.** A feature is kept only if all four evaluate true. The original draft mixed pass and fail phrasing across rows, which is exactly the kind of ambiguity that produces an inverted filter and corrupts every downstream score.

| # | Keep the feature if… | Rejects features that… |
|---|---|---|
| 1 | `ROM_j ≥ 8°` | barely moved — nothing to score |
| 2 | `median_p σ_j[p] < 15°` | are unconstrained — the band is so wide it would never fire |
| 3 | `mean visibility_j ≥ 0.7` | were poorly observed in the recording, and will be unreliable live too |
| 4 | `salience_j ≥ 2.0` | move no more than their own rep-to-rep variance — pure noise |

Filter 2 is the one most likely to be inverted during implementation. Read it as: **small σ is good.** A feature with `median σ = 4°` is tightly controlled and belongs in the watch list; a feature with `median σ = 18°` wandered freely and must be excluded. This is consistent with §6.9.4, where the σ cap of 15° marks the point beyond which a feature is considered unconstrained — filter 2 is the watch-list expression of that same cap.

Reference implementation, so there is no room for interpretation:

```kotlin
val watchList = features
    .filter { it.rom >= 8.0 }
    .filter { it.medianSigma < 15.0 }          // SMALL sigma passes
    .filter { it.meanVisibility >= 0.7 }
    .filter { it.salience >= 2.0 }
    .sortedByDescending { it.salience }
    .take(K)
    .plusIfAbsent(dominantFeature)
```

Take the **top K = 5** surviving features. The dominant feature is always included regardless of rank.

**Required unit test (P3-15).** Construct a synthetic reference in which one feature has a large ROM and a deliberately huge σ (say `ROM = 60°`, `σ = 22°`). Assert it does **not** appear in the watch list. Then construct one with `ROM = 40°, σ = 3°` and assert it does. If the filter is inverted, the first test fails immediately rather than three hours later as inexplicable scoring behaviour.

**Weights** for the frame score, normalised over the watch list:

```
w_j = salience_j / Σ salience
```

So the joints the physio controlled most tightly carry the most weight — which is exactly the desired clinical behaviour, obtained for free.

### 6.11 Offline: reference validator

**Do not accept a recording just because the pipeline ran.** Run these checks and reject with a specific, actionable message.

| # | Check | Threshold | Message on failure |
|---|---|---|---|
| 1 | Surviving reps after outlier rejection | ≥ 4 | "I could only identify 3 clean reps. Record 8 reps at a steady pace." |
| 2 | Dominant feature ROM | ≥ 15° | "This doesn't look like a repeated movement. Switch to Hold mode?" |
| 3 | Rep duration consistency | `MAD(T_i) / median(T_i) ≤ 0.25` | "Your reps varied a lot in speed. Try to keep a steady rhythm." |
| 4 | Band tightness | ≥ 2 features with `median σ < 12°` | "Your reps varied too much for me to learn a reliable target. Record again with more consistent form." |
| 5 | Landmark visibility | `mean visibility ≥ 0.7` for all watch-list features | "I couldn't see your left leg clearly. Move the camera back and check the framing." |
| 6 | Camera view consistency | View classifier (6.20.2) stable across ≥90% of frames | "You changed orientation mid-recording. Stay in one position." |
| 7 | Watch list size | ≥ 3 features | "I couldn't identify enough joints to score reliably." |
| 8 | Recording duration | 15 s ≤ duration ≤ 180 s | "Recording too short / too long." |

**Then show the physio the segmentation visually** — the dominant feature plotted with rep boundaries marked, and the derived μ ± 3σ band drawn on top. Two seconds of visual confirmation is worth more than any amount of algorithmic cleverness; a physio will spot a bad split instantly.

Provide a "looks wrong — re-record" button. Always.

> **This validator is a demo asset.** Deliberately record a bad reference on stage, show the app rejecting it with a specific reason, then record a good one. *A system that knows when it cannot score is a system you can trust when it does.*

### 6.12 Live: phase estimation state machine

No lookahead. Must decide per frame.

#### 6.12.1 States

```
IDLE → DESCEND → [BOTTOM_HOLD] → ASCEND → (rep complete) → DESCEND
                                              ↓
                                            IDLE (on timeout)
```

("Descend" and "ascend" are names of convenience; they mean "moving toward the extremum" and "returning.")

#### 6.12.2 Phase computation

Using `θ_start` and `θ_peak` **from the reference**, not from the current rep (we cannot know the current rep's extremum in advance):

```
φ_raw = (θ_dom − θ_start) / (θ_peak − θ_start)
φ     = clamp(φ_raw, 0, 1.15)          // allow 15% overshoot
```

We deliberately allow `φ > 1` so that overshoot is observable (see edge cases). For phase indexing, clamp to 1.

```
p = (state == DESCEND) ? 50·min(φ,1) : 100 − 50·min(φ,1)
```

Velocity, from the filtered signal:

```
v = dφ/dt          smoothed with an EMA, τ = 100 ms
```

#### 6.12.3 Transitions

| From | To | Condition | Guard |
|---|---|---|---|
| IDLE | DESCEND | `φ > 0.10` and `v > +V_TH` | Min 150 ms in state |
| DESCEND | BOTTOM_HOLD | `φ > 0.70` and `\|v\| < V_STALL` for 400 ms | — |
| DESCEND | ASCEND | `v < −V_TH` and `φ > 0.60` | Min 150 ms in state |
| BOTTOM_HOLD | ASCEND | `v < −V_TH` | — |
| BOTTOM_HOLD | IDLE | Held > 20 s | Abandoned rep |
| ASCEND | rep complete | `φ < 0.15` and `\|v\| < V_STALL` | Min 150 ms in state |
| any | IDLE | Dominant feature invalid > 1.5 s | Person left frame |

`V_TH = 0.15 /s`, `V_STALL = 0.05 /s`.

The **deadband** (`V_TH` for transitions, `V_STALL` for stalls, with a gap between them) plus the **minimum dwell time** are what prevent state chatter at the turnaround. Without them you get 3 phantom reps per real rep.

#### 6.12.4 Rep acceptance

On reaching "rep complete," the rep counts as a **valid rep** only if:

```
φ_max ≥ 0.75                             // reached at least 75% of reference ROM
AND rep duration ∈ [0.4·T_ref, 3.0·T_ref]
AND fraction of frames with dominant feature valid ≥ 0.70
```

Otherwise it is a **partial rep** — displayed to the patient ("that one didn't count — go all the way down"), logged, but excluded from Q statistics and CUSUM.

**This distinction matters enormously.** Without it, a fatiguing patient's shrinking reps get scored as good reps because they never leave the band near their (shortened) turnaround, and the fatigue detector sees nothing.

#### 6.12.5 BOTTOM_HOLD scoring

While in `BOTTOM_HOLD`, freeze `p` at its entry value and score in hold mode against `μ[p_frozen]`, `σ[p_frozen]`. Do not let phase drift while the patient is stationary; a frozen phase with a stale reference is the correct behaviour here because the reference at that phase *is* the end-range target.

### 6.13 Live: deviation scoring

#### 6.13.1 Reference lookup with interpolation

`p` is continuous; the reference is binned at integers. Interpolate:

```
p0 = floor(p); p1 = min(p0+1, 100); w = p − p0
μ_j(p) = (1−w)·μ_j[p0] + w·μ_j[p1]
σ_j(p) = (1−w)·σ_j[p0] + w·σ_j[p1]
dμ_j(p) similarly
```

#### 6.13.2 Phase-uncertainty inflation

The phase estimate itself is uncertain — roughly ±3 phase units, more where the dominant joint moves slowly. That uncertainty propagates into the reference value:

```
σ_eff,j(p) = sqrt( σ_j(p)²  +  ( |dμ_j(p)| · δp )² )
```

with `δp = 3.0` phase units, and optionally scaled up when `|v|` is small (a slow-moving dominant joint gives a poorly-determined phase):

```
δp = 3.0 · clamp(V_REF / max(|v|, 0.02), 1.0, 3.0)

// V_REF = the reference movement's own median |dφ/dt| over the rep,
// computed at authoring time and stored in the manifest as
// phase.v_ref. Units: φ per second. Typical value 0.4–0.8 /s.
// If phase.v_ref is absent (older .rpose), fall back to δp = 3.0 flat.
```

**This is standard error propagation and it matters a lot.** It automatically widens the tolerance exactly where the reference is changing fastest — the mid-descent portion, where a small phase error corresponds to a large expected-angle difference. Without it you get a burst of spurious faults every rep at the steepest part of the movement.

#### 6.13.3 The z-score

```
z_j(t) = ( θ_j(t) − μ_j(p) ) / σ_eff,j(p)
```

Computed only for valid, watch-listed features. The **sign** is as important as the magnitude — it selects the cue.

Sign convention: **positive z means the measured angle exceeds the reference.** For `knee_flex` (an extension angle where larger = straighter), positive means "not bending enough." Document the physical meaning of positive-z for every feature in a table; this is the single most common source of backwards cues.

### 6.14 Live: fault detection

A **fault** is a named pattern, not a bare z-score. Faults are defined at authoring time from the watch list; the LLM writes only their *wording* (section 10).

```
Fault {
  id, feature, sign, z_on, phase_range, priority, masked_by[],
  cue_short, cue_full, cue_alt, arrow_joint, arrow_axis
}
```

A fault may require multiple simultaneous conditions. Example: lumbar flexion at the bottom of a squat ("butt wink") requires `z(lumbar_flexion) > 2.5 AND p ∈ [45,55]` — neither condition alone is meaningful. Note the feature identifier is `lumbar_flexion` exactly as listed in §6.5.3; fault condition names are bound and asserted at startup alongside the mask rules.

#### 6.14.1 Hysteresis and dwell

```
enter:  |z| ≥ Z_ON  (3.0)  continuously for ≥ 300 ms, and p ∈ phase_range
exit:   |z| ≤ Z_OFF (1.5)  continuously for ≥ 500 ms
```

The asymmetric thresholds prevent flicker at the boundary; the dwell requirements prevent single-frame noise from triggering.

**The dwell requirement is the difference between a demo that feels magical and one that feels broken.** Without it the app fires on every frame of jitter.

#### 6.14.2 Phase-range gating

A fault only fires within its declared phase window. This is essential: 40° of trunk lean at `p = 50` in a squat is correct; the same 40° at `p = 5` is a fault. Same joint, same value, opposite verdicts.

Default phase range for an auto-generated fault: the contiguous window where `σ_j[p]` is in its lowest tercile — i.e. where the physio was most controlled, which is where deviation matters most.

#### 6.14.3 Validity gate

No fault may fire on an invalid feature. Ever. If the contributing landmarks are occluded, we do not know, and we say nothing.

### 6.15 Live: cue arbitration and escalation

Multiple faults will be active simultaneously. **Speak exactly one.**

#### 6.15.1 Masking (compensation chains)

Deviations propagate along kinematic chains. The visibly-wrong joint is frequently not the joint to cue:

| Observed | Usual cause | Correct cue target |
|---|---|---|
| Knee caves inward | Weak hip abductors | Hip |
| Excessive forward trunk lean in squat | Limited ankle dorsiflexion | Ankle / weight distribution |
| Hip hikes in single-leg stance | Weak gluteus medius, stance side | Pelvis |
| Shoulder shrugs during arm raise | Overactive upper trapezius | Scapula |
| Lumbar arches in overhead reach | Limited shoulder flexion | Rib cage |

**Hardcode these masking rules in Kotlin. Do not ask the LLM to reason about compensation chains** — that is clinical logic, and a 1B model reasoning about it is where you would actually cause harm.

```
Feature names below are the **exact identifiers from §6.5.3**. An earlier draft used shortened names (`knee_frontal`, `ankle_dorsiflex`, `elbow_deviation`) that match no feature, which would have made every rule silently inert — the lookup would return null, no mask would apply, and you would never see an error.

```
MASK_RULES = [
  ("pelvic_obliquity",       masks, "knee_frontal_active"),
  ("ankle_dorsiflex_active", masks, "trunk_flexion"),
  ("lumbar_flexion",         masks, "shoulder_flex_active"),
  ("shoulder_elevation",     masks, "elbow_flex_active"),
  ("trunk_lateral",          masks, "hip_abduction_active"),
]
```

The fifth rule is added because it covers the classic cheat in **standing hip abduction** — leaning the trunk away to create the appearance of range — and that is one of the four demo exercises. The original four covered the other three demo exercises but left this one exposed.

**Mandatory startup assertion.** Bind these names at app start and fail loudly if any does not resolve:

```kotlin
init {
    MASK_RULES.forEach { (masker, _, masked) ->
        require(FEATURES.containsKey(masker))  { "Unknown mask source: $masker" }
        require(FEATURES.containsKey(masked))  { "Unknown mask target: $masked" }
    }
}
```

A mask rule that references a non-existent feature fails open — the compensation chain simply never masks, and you get contradictory cues with no indication anything is wrong. Assert at startup, not at first use.
```

If a masking fault is active, suppress the masked fault even if its `|z|` is larger.

#### 6.15.2 Ranking

Among unmasked active faults:

```
score_f = |z_f| × priority_f
```

Speak the highest. Priority defaults to `w_j` from the watch list, adjustable by the physio.

#### 6.15.3 Cooldown

Per fault ID: 5 s minimum between utterances. Global: 3 s minimum between any two utterances. TTS uses `QUEUE_FLUSH` so a new cue interrupts a stale one rather than queueing behind it.

#### 6.15.4 Escalation ladder

| Occurrence in set | Response |
|---|---|
| 1st | `cue_short` (≤5 words) |
| 2nd | `cue_full` (≤12 words), arrow persists longer |
| 3rd | Pause the set: "Let's reset. Stand up and set your feet." |
| 4th (post-reset) | End the set, log for the physio, suggest a regression |

**Never nag.** A patient cued on every rep stops listening by rep 4. Clinically, a fault surviving three cues means the exercise is too hard for them today — that is information for the physio, not a cue problem.

#### 6.15.5 Cue effect logging

Every utterance logs:

```
CueEvent { cue_id, rep, t_fire, z_at_fire, z_at_+2s, z_next_rep, resolved }
```

Three uses:

1. **Immediate.** If `z` hasn't improved 2 s after speaking, that phrasing didn't land — use `cue_alt` next time before escalating.
2. **Per-patient learning.** Over sessions, reorder the variants by which resolve fastest for this individual. Genuine personalisation from ~40 lines of bookkeeping, no model required.
3. **Clinical finding.** *"Knee valgus fired on 8 of 12 reps, resolved after cue on only 2."* That is a strength deficit, not a technique problem — and it is a finding the app produced, which is a much stronger claim than a rep count.

### 6.16 Live: correction vector

The words require authoring. The **direction does not** — it is pure geometry, and therefore works for any exercise the physio ever records.

> **Correction to an earlier draft.** A previous version of this section said to compute the target position in world space and then "project to image space using the same transform used for skeleton drawing." **That transform does not exist.** MediaPipe gives you world landmarks (metres, hip-centred) and image landmarks (normalised 2D) as two independent outputs, with no camera intrinsics and no projection matrix relating them. Implementing the section as originally written is impossible, and discovering that at hour 14 would cost you Saturday night.

**Work entirely in image space instead.** We are only ever displaying a rotation of one limb segment about a joint, and `required_view` already guarantees the plane of motion is roughly parallel to the image plane. So the rotation can be applied directly to the 2D limb vector, with no 3D round trip.

For a fault on feature `j` with target joint `J`, proximal landmark `A`, joint `B`, distal landmark `C` — all in **image** coordinates:

```
Δθ    = μ_j(p) − θ_j(t)          // signed angular error, degrees
v     = C_img − B_img            // live distal segment, image space
v_rot = rotate2D(v, s · Δθ)      // rotate about the joint
C_ghost = B_img + v_rot
```

`s ∈ {+1, −1}` is a per-feature sign that maps "increase the measured angle" to "rotate clockwise or anticlockwise in image space." It depends on the feature's definition and on `required_view`. **Determine `s` empirically for each feature and store it in a constant table** — the same discipline as Appendix A, and the same failure mode if you guess: an arrow that confidently points the wrong way.

Then:

```
d_img  = C_ghost − C_img
length = clamp(|z|/6, 0.2, 1.0) × ARROW_MAX_PX
```

Draw the arrow from `C_img` along `d_img`, normalised to `length`.

**Ghost overlay** applies the same rotation to every watch-listed joint simultaneously, walking the kinematic chain outward from the hip so that a correction at the hip carries the whole leg with it:

```
for joint in chainOrder(hip → distal):
    apply rotation for that joint's feature
    propagate the resulting offset to all downstream landmarks
```

Because everything is derived from the patient's own image-space landmarks, the ghost is automatically anchored on their body and scaled to their limb lengths. No hip-anchoring or scaling step is needed — that was an artefact of the world-space approach and should not be implemented.

**Known limitation, worth stating in the pitch if asked.** This is a planar approximation. If the patient drifts off the required view, the ghost degrades gracefully (it stays attached to their body, but the rotation magnitude is foreshortened). The 5-second view re-check in §6.20.2 is what keeps it honest, and the quality bar remains driven by the true 3D z-scores regardless — only the *visual* is planar.

### 6.17 Scoring: frame, rep, set

#### 6.17.1 Frame score

```
FrameScore(t) = 100 · exp( −λ · Σ_j w_j · z̃_j²  /  Σ_j w_j )
```

where `z̃_j = clamp(|z_j|, 0, 6)` and the sums run over **valid, watch-listed** features only (weights renormalised over valid features).

**Choosing λ.** We want "every joint at 2σ" to score 55:

```
exp(−λ · 4) = 0.55  ⟹  λ = −ln(0.55)/4 = 0.1494
```

Use `λ = 0.15`.

Why exponential rather than linear: it is forgiving of small deviations (a patient 1σ off everywhere still scores 86), drops sharply on real faults, is bounded in [0, 100] without clamping, and cannot be dragged negative by one bad joint.

The `z̃` clamp at 6 prevents a single catastrophic landmark glitch from zeroing an otherwise good rep.

#### 6.17.2 Phase weighting

Not all parts of a rep matter equally. The end-range portion is where the therapeutic effect lives.

```
w_phase(p) = 2.0  if p ∈ critical_window
             1.0  otherwise
```

Auto-derive `critical_window` as the contiguous phase range where `σ_dominant[p]` is in its lowest tercile — where the physio was most controlled. Default `[40, 60]` if derivation fails. Physio may override.

#### 6.17.3 Rep score

```
Q_i = [ Σ_p w_phase(p)·FrameScore(p) / Σ_p w_phase(p) ]  ×  romPenalty_i  ×  tempoPenalty_i
```

**ROM penalty:**

```
romRatio_i    = ROM_i / (0.95 × ROM_ref)
romPenalty_i  = clamp(romRatio_i, 0, 1)
```

Dividing by `0.95 × ROM_ref` means 95% of reference range counts as full credit; below that, penalty scales linearly.

> **This term is the single most important line in the scoring function.** Without it, a patient who barely moves scores beautifully — they never leave the reference band near their own shortened turnaround. It is the most common failure mode in form-scoring apps and it is a one-line fix.

**Tempo penalty:**

```
ratio = T_i / T_ref
tempoPenalty = 1.0                       if 0.7 ≤ ratio ≤ 1.6
             = clamp(ratio/0.7, 0.6, 1)  if ratio < 0.7      (rushed / momentum)
             = clamp(1.6/ratio, 0.8, 1)  if ratio > 1.6      (laboured)
```

Rushing is penalised harder than going slow, because momentum-driven reps bypass the muscle work entirely.

#### 6.17.4 Set score

```
SetScore = median(Q_i over valid reps)
completionRate = validReps / prescribedReps
```

Report both. Median rather than mean, so one dropped rep doesn't define the session.

### 6.18 Fatigue and degradation detection

This is the differentiator. Treat it as the centrepiece.

#### 6.18.1 Prerequisites

Requires ≥ 6 valid reps. Below that, report "not enough reps to assess fatigue" — do not produce a spurious change point.

#### 6.18.2 Baseline

```
B = median(Q_2, Q_3, Q_4)
```

Skip `Q_1` — the first rep is the patient finding their position, and including it drags the baseline down and masks real degradation.

Scale:

```
s = max( 1.4826 × MAD(Q_2..Q_4), 3.0 )
```

The floor of 3 points prevents a pathologically tight baseline from making the detector fire on nothing.

#### 6.18.3 CUSUM

One-sided (we only care about decline):

```
k    = 0.5 · s                     // allowance / slack
h    = 5.0 · s                     // decision threshold
S_1  = 0
S_i  = max(0, S_{i−1} + (B − Q_i − k))
```

**Alarm** at the first `i` with `S_i > h`.

**Change point** = the last index `c < i` at which `S_c = 0`, plus 1. That is the rep at which the decline began accumulating — which is what produces *"deteriorated since Rep 5"* as a defensible number rather than a vibe.

#### 6.18.4 Corroboration

CUSUM alone will occasionally fire on noise. Require **both**:

```
declare_degradation = cusumAlarm
                      AND slope < −1.5 points/rep
```

where `slope` is the least-squares fit of `Q_i` against `i` over the whole set. Two independent signals agreeing is enough; either alone is not.

#### 6.18.5 Attribution — which joints degraded

For each watch-listed feature, compute the mean **signed** z before and after the change point:

```
e_j^pre  = mean( z_j over reps 1..c−1 )
e_j^post = mean( z_j over reps c..N )
Δ_j      = e_j^post − e_j^pre           // in units of σ
```

Report the top 2 by `|Δ_j|`, with direction.

Also report the classical fatigue signature, as percentage changes:

```
ΔROM   = (mean ROM_post / mean ROM_pre − 1) × 100        // typically shrinks
ΔTempo = (mean T_post / mean T_pre − 1) × 100            // typically slows
ΔJerk  = jerk_post / jerk_pre                            // typically rises
```

**Do not compute jerk as a third time-derivative.** An earlier draft specified this; differentiating a noisy 30 Hz signal three times amplifies noise by roughly the cube of the frequency, and the result is dominated by landmark jitter rather than movement quality. It would produce a number that looks meaningful and is not.

Use **spectral arc length (SPARC)** instead — the standard smoothness metric in movement science, computed in the frequency domain and therefore robust to exactly the noise that destroys jerk:

```
1. Take the dominant angle's velocity profile over the rep.
2. FFT it; normalise the magnitude spectrum by its value at DC.
3. Truncate at the frequency where magnitude first falls below 0.05,
   or at 10 Hz, whichever is lower.
4. SPARC = −∫ sqrt( (1/ω_c)² + (dM̂/dω)² ) dω   over the truncated band.
```

SPARC is negative; **more negative means less smooth.** Report `ΔSmooth = SPARC_post − SPARC_pre`; a decrease indicates roughening movement.

Rising roughness with falling ROM and slowing tempo is the textbook fatigue pattern; naming it explicitly is what a physiotherapist will recognise instantly.

**If SPARC proves fiddly to implement in Kotlin, drop the smoothness term entirely.** ROM shrinkage and tempo change already carry the fatigue signature, and the CUSUM change point does not depend on smoothness at all. This is the first thing to cut in the hours 19–22 block if you are behind.

#### 6.18.6 The safety intervention

If `declare_degradation` fires **and** `Q_i < 0.7 × B` for two consecutive reps:

> Stop the set. Say so aloud. Log it.

*"The app told the patient to stop before they hurt themselves"* is a categorically stronger claim than *"the app counted reps,"* and it is the clinically correct behaviour.

### 6.19 Hold mode

Simpler than cyclic — no phase axis at all.

**Reference construction.** From the physio's recording, discard the first and last 2 seconds (getting into and out of position), then:

```
μ_j = median over the stable window
σ_j = 1.4826 × MAD over the stable window, with the same floor/cap/inflation as 6.9.4
```

**Dominant feature and watch-list selection differ in hold mode**, because both are defined in §6.7.1 and §6.10 via range of motion — and in a hold, range of motion is near zero by construction. Applying the cyclic definitions would select on noise and produce a meaningless watch list.

Hold-mode substitutes:

```
// Dominant feature: the one whose deviation from the reference
// most reliably indicates the hold is breaking down.
// Use the largest ABSOLUTE angle, not the largest variance.
dominant = argmax_j |μ_j − θ_j(neutral_standing)|

// Salience: replace ROM/σ with a tightness measure.
salience_j = 1 / max(σ_j, σ_floor_j)
```

`θ_j(neutral_standing)` is captured in a one-second neutral pose at the start of the physio's recording — a required step in the hold-mode authoring flow. It gives you "how far from neutral is this joint being held," which is the hold-mode analogue of range of motion.

Watch-list filters 1 and 4 from §6.10 do not apply (both are ROM-based). Filters 2 and 3 apply unchanged. Take the top K = 5 by the substituted salience.

Add a stability precondition: if `MAD(θ_dom)` over the stable window exceeds 8°, the physio wasn't actually holding still — reject the recording.

**Live scoring.**

```
z_j(t) = (θ_j(t) − μ_j) / σ_j
FrameScore(t) as in 6.17.1
HoldQuality = fraction of hold time with all |z_j| < 2
```

No phase-uncertainty inflation — there is no phase.

**Break detection.** If any watch-listed `|z| > 4` continuously for 1.0 s, the hold is broken: stop the timer, log `holdDuration`, announce it.

**Drift detection (this is hold mode's fatigue signal).** Fit a least-squares line to each `z_j(t)` over the hold:

```
driftSlope_j = d z_j / dt        [σ per second]
```

Report the largest `|driftSlope_j|`. Slope > 0.15 σ/s sustained means the patient is progressively losing the position — announce a correction and, above 0.3 σ/s, end the hold.

The change-point machinery applies here too: run CUSUM on `FrameScore` sampled at 1 Hz instead of per rep, giving *"your form started slipping at 0:38."*

### 6.20 Calibration

A gate, not a formality. Refuse to start until it passes.

#### 6.20.1 Checks

| # | Check | Threshold |
|---|---|---|
| 1 | All watch-list contributing landmarks visible | `visibility ≥ 0.7` continuously for 2.0 s |
| 2 | Body fills frame appropriately | bounding-box height / frame height ∈ [0.5, 0.92] |
| 3 | Body horizontally centred | box centre within middle 60% of frame width |
| 4 | Required view satisfied | see 6.20.2 |
| 5 | Phone approximately upright | `\|gravity_z\| / \|gravity\| < 0.17` (≈ within 10° of vertical) |
| 6 | Adequate lighting | mean landmark `presence ≥ 0.75` |
| 7 | Stationary start | `\|v_dom\| < V_STALL` for 1.0 s |

Show each as a live checklist with a tick, so the patient can see what to fix.

#### 6.20.2 View classification

`required_view` is stored in the manifest. Determine the current view geometrically:

```
shoulderWidth = |LShoulder − RShoulder|          in the image plane
torsoHeight   = |midShoulder − midHip|           in the image plane
ratio         = shoulderWidth / torsoHeight
```

| Ratio | View |
|---|---|
| `< 0.25` | Sagittal (person side-on; shoulders project onto each other) |
| `0.25 – 0.35` | Oblique — **reject**, ambiguous |
| `> 0.35` | Frontal |

This is a genuine computable test, robust to distance, and it is why single-camera depth ambiguity does not sink the system: **we force the camera perpendicular to the plane of motion**, which is what a physio does with a mirror anyway.

Re-check the view every 5 s during the set. If it drifts, pause and re-calibrate.

#### 6.20.3 Session-1 baseline capture

The patient's first session on any exercise is a **measurement session**, not a scored one:

- Cues are suppressed (we do not yet know their baseline).
- The app records `ROM_peak`, `Q_mean`, `reps_at_quality`, `holdDuration`.
- These become the baseline `B` for progression (6.21).

This gives the progression system a real starting point and gives the patient a gentle first experience.

### 6.21 Progression and goal state

#### 6.21.1 Metrics

Do not compute a distance between pose vectors — it is uninterpretable and no clinician will trust it. Use clinically meaningful scalars:

| Metric | Definition |
|---|---|
| `ROM_peak` | Median of the top 3 reps' `ROM_i` in the session |
| `Q_mean` | Median `Q_i` over valid reps |
| `reps_at_quality` | Count of reps with `Q_i ≥ 80` |
| `hold_duration` | Longest unbroken hold (hold mode) |
| `degradation_onset` | Change-point rep index; later is better. **When no degradation is detected, set this to `N_valid + 1`, not null** — "never degraded" must sort as better than "degraded at the last rep," and a null would either crash the comparison in §6.21.5 or silently exclude the session. When fewer than 6 valid reps make CUSUM inapplicable, set it to null and **exclude the session from the progression median** rather than treating it as a perfect score. |

#### 6.21.2 Robust current estimate

```
current_m = median over the last 3 sessions of session_m
```

Median-of-sessions on top of median-of-reps. One bad day does not move the curve, and one heroic day does not either.

#### 6.21.3 Progress

```
progress_m = clamp( (current_m − B_m) / (G_m − B_m), 0, 1 )
```

where `B_m` is the session-1 baseline and `G_m` the physio's goal.

Guard: if `|G_m − B_m| < ε`, the goal is not meaningfully different from the baseline — display "goal not set" rather than dividing by near-zero.

Overall progress: weighted mean with default weights `ROM 0.5, Q_mean 0.3, reps_at_quality 0.2`, physio-adjustable.

#### 6.21.4 Intermediate target

The "in-between pose" is linear interpolation in angle space. Not a model:

```
θ_target_j(p) = (1 − progress) · μ_baseline_j(p)  +  progress · μ_goal_j(p)
```

Ten lines. This is what the ghost overlay shows during early sessions, so the patient chases an achievable next step rather than the full goal.

#### 6.21.5 Advancement rule

```
readyToProgress =
    progress_ROM ≥ 0.90
    AND Q_mean ≥ 80
    AND both conditions met in ≥ 2 of the last 3 sessions
    AND degradation_onset ≥ 0.8 × prescribed_reps
```

The last clause matters: a patient who hits the range but fatigues at rep 4 of 10 is not ready.

**On trigger: notify the physio. Never auto-advance.** Requiring clinician sign-off is the clinically correct answer and it is a strong line in front of judges.

#### 6.21.6 Regression rule

```
needsReview =
    Q_mean < 50 for 2 consecutive sessions
    OR completionRate < 0.5 for 2 consecutive sessions
    OR patient reported pain
```

Flag to the physio with the supporting data attached.

### 6.22 Numerical guards

Implement all of these. Each corresponds to a crash or a silently-wrong output we would otherwise ship.

| Guard | Condition | Action |
|---|---|---|
| Vector normalisation | `\|v\| < 1e-6` | Feature invalid |
| Angle computation | Use `atan2(\|v1×v2\|, v1·v2)` | Never `acos` |
| Signed planar angle | `\|θ\| > 90°` | Feature invalid (probable wrap) |
| σ division | `σ_eff < 0.5°` | Clamp to 0.5° |
| ROM division | `ROM_ref < 5°` | Set `romPenalty = 1.0`, log |
| Progress division | `\|G − B\| < 2°` | Display "goal not set" |
| dt | `dt ≤ 0` or `dt > 0.25 s` | Reset filter velocity, mark gap, no derivatives across gap |
| Phase interpolation | `p(t_{n+1}) − p(t_n) < 1e-6` | Take `θ(t_n)` |
| Autocorrelation | No lag reaches `r ≥ 0.4` | Fall back to unconstrained peak detection; validator will reject |
| CUSUM | `N < 6` valid reps | Skip; report "insufficient reps" |
| MAD | `MAD = 0` (all identical) | Apply σ floor |
| Body frame | `\|yRaw\| < 0.10 m` | Reuse previous `R`; invalid after 500 ms |
| NaN / Inf | Any feature non-finite | Feature invalid, log the landmark set for debugging |
| Object churn | 30 fps allocation | Pool `FrameResult` objects; avoid per-frame allocation in the inference thread |

---

## 7. Parameter table

Every tunable in one place. Put these in a single `Params.kt` object so they can be adjusted from one screen during the event.

| Symbol | Name | Value | Section |
|---|---|---|---|
| `FC_MIN` | One Euro min cutoff | 1.0 Hz | 6.3.1 |
| `BETA` | One Euro velocity coefficient | 0.02 | 6.3.1 |
| `D_CUTOFF` | One Euro derivative cutoff | 1.0 Hz | 6.3.1 |
| — | Offline Butterworth cutoff | 6 Hz, 4th order, filtfilt | 6.3.2 |
| `VIS_MIN` | Landmark visibility threshold | 0.6 | 6.6 |
| `k_min, k_max` | Autocorrelation lag bounds | 0.5 s, 8.0 s | 6.7.2 |
| `R_MIN` | Autocorrelation acceptance | 0.40 | 6.7.2 |
| — | Peak min spacing | 0.6 × T | 6.7.3 |
| — | Peak min prominence | 0.25 × swing | 6.7.3 |
| — | Duration outlier threshold | 0.35 × median | 6.7.6 |
| `P` | Phase bins | 101 | 6.8.4 |
| — | σ smoothing window | 9 median then 9 mean | 6.9.3 |
| `σ_floor` | Sigma floor | `max(2.5°, 0.05·ROM)` | 6.9.4 |
| `σ_cap` | Sigma cap | 15° | 6.9.4 |
| `k_inflate` | Anthropometry inflation | 1.15 (1.30 non-upright) | 6.9.4 |
| `K` | Watch list size | 5 | 6.10 |
| — | Min salience | 2.0 | 6.10 |
| `V_TH` | Phase transition velocity | 0.15 /s | 6.12.3 |
| `V_STALL` | Phase stall velocity | 0.05 /s | 6.12.3 |
| — | State min dwell | 150 ms | 6.12.3 |
| — | Rep acceptance φ_max | 0.75 | 6.12.4 |
| `δp` | Phase uncertainty | 3.0 units | 6.13.2 |
| `Z_ON` | Fault entry | 3.0 | 6.14.1 |
| `Z_OFF` | Fault exit | 1.5 | 6.14.1 |
| — | Fault entry dwell | 300 ms | 6.14.1 |
| — | Fault exit dwell | 500 ms | 6.14.1 |
| — | Per-cue cooldown | 5 s | 6.15.3 |
| — | Global cue cooldown | 3 s | 6.15.3 |
| `λ` | Frame score decay | 0.15 | 6.17.1 |
| — | z clamp | 6.0 | 6.17.1 |
| — | Critical phase weight | 2.0 | 6.17.2 |
| — | ROM full-credit fraction | 0.95 | 6.17.3 |
| `k` | CUSUM allowance | 0.5 · s | 6.18.3 |
| `h` | CUSUM threshold | 5.0 · s | 6.18.3 |
| — | Corroborating slope | −1.5 pts/rep | 6.18.4 |
| — | Hold break threshold | \|z\| > 4 for 1.0 s | 6.19 |
| — | Hold drift alarm | 0.15 σ/s | 6.19 |
| — | View ratio bounds | 0.25 / 0.35 | 6.20.2 |
| — | Progression threshold | 0.90 progress, Q ≥ 80, 2 of 3 | 6.21.5 |

---

## 8. Edge case register

Every one of these will occur. Handle them by design, not by discovery at 3 a.m.

### 8.1 Movement pattern edge cases

| Case | Symptom | Handling |
|---|---|---|
| Pause at end range | φ flatlines, phase freezes, z-scores go stale | `BOTTOM_HOLD` state (6.12.3); freeze phase, switch to hold-mode scoring, resume on movement |
| Non-monotonic dominant joint | Phase estimate oscillates | Cumulative max/min monotonicity enforcement (6.8.3) |
| Alternating sides | Active leg swaps each rep | Active-side detection per rep, canonical remap (6.2) |
| Bilateral simultaneous | Both sides active | Manifest declares `laterality: bilateral`; skip active-side detection |
| Partial rep | Patient doesn't complete range | `φ_max < 0.75` → partial rep, counted but unscored (6.12.4) |
| ROM overshoot | Patient exceeds reference range | `φ > 1.0` allowed; log as `overshoot`. **If the joint is one where hyperextension is a risk (knee, elbow), fire a safety fault** |
| Two reps merged | Patient doesn't return to start between reps | Rep acceptance requires `φ < 0.15` — the merged rep simply never completes, and the 20 s timeout returns to IDLE |
| Tremor | High-frequency oscillation in φ | One Euro filter plus `V_TH` deadband; if tremor amplitude > 0.1 φ sustained, flag for physio |

### 8.2 Camera and environment edge cases

| Case | Symptom | Handling |
|---|---|---|
| Partial occlusion | One limb behind furniture | Feature invalid; weights renormalised; UI names the missing limb |
| Full occlusion | Patient walks out of frame | Dominant feature invalid > 1.5 s → IDLE; pause session |
| Second person in frame | Landmarks jump between people | MediaPipe returns one pose by default; add a check that hip-centre displacement between frames is `< 0.5 m` — if exceeded, treat as re-detection and reset filter state |
| Phone bumped | View changes mid-set | View re-check every 5 s; pause and re-calibrate |
| Poor lighting | Low presence scores | Calibration gate check 6; if degraded mid-set, warn |
| Depth-dominated motion | Movement toward/away from camera | `required_view` forces perpendicular framing (6.20.2). Additionally: weight sagittal-plane features above depth-dependent ones in the salience ranking |
| Mirror mismatch | Physio recorded rear camera, patient uses front | Canonicalisation at ingest (6.2) |
| Loose clothing | Landmark noise on limbs | Nothing algorithmic; add a line to the calibration screen: "fitted clothing helps accuracy" |

### 8.3 Reference quality edge cases

| Case | Symptom | Handling |
|---|---|---|
| Physio recorded sloppily | Enormous σ everywhere | Validator check 4 rejects (6.11) |
| Too few reps | N < 4 after outlier rejection | Validator check 1 rejects |
| Physio recorded a hold as cyclic | ROM < 15° | Validator check 2 offers hold mode |
| Physio changed position mid-recording | View classifier unstable | Validator check 6 rejects |
| Non-upright exercise | World-vertical reference meaningless; landmark quality degraded | Manifest `body_position`; disable **feature 7 only**, `k_inflate = 1.3` (6.5.4) |
| Fine motor exercise | Landmark noise >> signal | Out of scope; refuse at authoring with an explanation |
| Invisible load | Wrong resistance band, perfect-looking form | Not solvable by camera. Name it as a known limitation |

### 8.4 System edge cases

| Case | Symptom | Handling |
|---|---|---|
| Thermal throttling | Frame rate drops mid-session | Timestamps not frame counts (6.1); all thresholds in seconds. Log fps; if < 15 fps sustained, warn and reduce overlay complexity |
| LLM fails to load | Cue generation hangs | Timeout at 20 s → mechanical template fallback (section 10.4) |
| LLM returns malformed JSON | Parse failure | Output validator, one retry, then fallback |
| Network unavailable | Sync fails | Room is the source of truth; queue sync, retry with backoff. **The full session works offline** |
| Sync conflict | Physio edits while patient exercises | Last-write-wins per document; `.rpose` bundles are immutable and versioned, so a mid-session edit creates v2 rather than mutating v1 |
| App killed mid-set | Session lost | Persist rep records to Room as each rep completes, not at set end |
| TTS not ready | First cue silent | Initialise TTS at app start, speak a silent warm-up utterance |

---

## 9. The .rpose format

A zip archive, not a flat file. It is a portable clinical prescription — that framing is worth more in a pitch than "we save a JSON."

```
exercise.rpose  (zip)
├── manifest.json
├── reference.json
├── faults.json
├── goals.json
├── validation.json
└── demo.mp4
```

### 9.1 manifest.json

```json
{
  "format_version": "1.0",
  "id": "rp_8f2a1c",
  "version": 1,
  "name": "Seated knee extension",
  "created_by": "physio_uid_123",
  "created_at": "2026-09-06T09:14:22Z",
  "mode": "cyclic",
  "reference_source": "self",
  "body_position": "seated",
  "laterality": "unilateral",
  "required_view": "sagittal",
  "camera_facing": "rear",
  "mirrored": false,
  "capture": {
    "fps_nominal": 30,
    "frames": 1847,
    "duration_s": 61.6,
    "device": "iQOO loaner"
  },
  "segmentation": {
    "reps_detected": 9,
    "reps_used": 6,
    "period_s": 4.2,
    "dominant_feature": "knee_flex_active"
  },
  "phase": {
    "bins": 101,
    "theta_start": 92.4,
    "theta_peak": 171.8,
    "critical_window": [42, 62],
    "v_ref": 0.62
  },
  "watch_list": [
    { "feature": "knee_flex_active",  "weight": 0.41, "salience": 9.2 },
    { "feature": "trunk_flexion",     "weight": 0.24, "salience": 5.4 },
    { "feature": "hip_flex_active",   "weight": 0.18, "salience": 4.1 },
    { "feature": "pelvic_obliquity",  "weight": 0.10, "salience": 2.3 },
    { "feature": "ankle_dorsiflex_active", "weight": 0.07, "salience": 2.0 }
  ],
  "prescription": {
    "reps": 12, "sets": 3, "rest_s": 60, "hold_s": null
  }
}
```

### 9.2 reference.json

```json
{
  "features": {
    "knee_flex_active": {
      "mu":    [92.4, 93.1, 94.6, "… 101 values …"],
      "sigma": [3.2, 3.3, 3.5,   "… 101 values …"],
      "dmu":   [0.7, 1.1, 1.4,   "… 101 values …"],
      "rom": 79.4,
      "sigma_floor": 4.0,
      "mean_visibility": 0.93
    }
  }
}
```

Store as flat float arrays. At 5 watch-listed features × 3 arrays × 101 floats, the file is a few kilobytes.

### 9.3 faults.json

```json
{
  "faults": [
    {
      "id": "knee_incomplete_extension",
      "conditions": [
        { "feature": "knee_flex_active", "sign": "-", "z_on": 3.0 }
      ],
      "phase_range": [40, 62],
      "priority": 1.0,
      "masked_by": ["trunk_compensation"],
      "cue_short": "Straighten all the way.",
      "cue_full": "Push your heel forward until your leg is fully straight.",
      "cue_alt": "Reach your toes toward the far wall.",
      "arrow_joint": "ankle",
      "arrow_axis": "reference_position",
      "generated_by": "llm",
      "validated": true
    }
  ],
  "mask_rules_version": "builtin_v1"
}
```

### 9.4 goals.json

```json
{
  "baseline": { "rom_peak": null, "q_mean": null, "reps_at_quality": null },
  "goal":     { "rom_peak": 175.0, "q_mean": 85.0, "reps_at_quality": 12 },
  "weights":  { "rom_peak": 0.5, "q_mean": 0.3, "reps_at_quality": 0.2 },
  "baseline_captured": false
}
```

`baseline` is null until the patient's session-1 measurement (6.20.3).

### 9.5 validation.json

Persisted so the physio can see *why* the reference was accepted, and so we can debug a bad reference after the fact.

```json
{
  "checks": [
    { "id": "rep_count",        "pass": true,  "value": 6,    "threshold": 4 },
    { "id": "rom",              "pass": true,  "value": 79.4, "threshold": 15 },
    { "id": "duration_consistency", "pass": true, "value": 0.11, "threshold": 0.25 },
    { "id": "band_tightness",   "pass": true,  "value": 4,    "threshold": 2 },
    { "id": "visibility",       "pass": true,  "value": 0.93, "threshold": 0.7 },
    { "id": "view_stability",   "pass": true,  "value": 0.98, "threshold": 0.9 },
    { "id": "watch_list_size",  "pass": true,  "value": 5,    "threshold": 3 }
  ],
  "accepted": true
}
```

### 9.6 Versioning

`.rpose` bundles are **immutable**. Editing produces `version: 2` with a new file; the old one stays. This means a patient mid-programme is never disrupted by an edit, and a session record can always point at exactly the reference it was scored against.

---

## 10. LLM cue generation

### 10.1 The hard line

> **The LLM decides the words. Our code decides the direction.**

We pass it *"knee flexion is too small at the end of the movement."* It never decides whether the knee should bend more or less — that comes from the sign of the z-score, which is arithmetic. This means a hallucination can produce a badly-worded cue but **never a backwards one**.

This distinction is the whole safety argument for using a generative model in a clinical context, and it should be stated explicitly in the pitch.

### 10.2 When it runs

**Once, at exercise save time**, batched into a single call. Results cached in `faults.json`. Runtime is a dictionary lookup — the real-time path never touches the model.

Show it as a visible step in the physio's flow ("Generating coaching cues…") because it is a genuine on-device LLM moment a judge can watch happen.

### 10.3 Prompt

Ground it in measured data, not just the exercise name:

```
You are writing spoken coaching cues for a physiotherapy app.

Exercise: "Seated knee extension"
Body position: seated
Patient is viewed from: the side

Faults to write cues for. For each, the error direction is already
determined — write cues that correct it in that direction. Do not
question or reverse the direction.

  1. knee_flex_active — patient is NOT STRAIGHTENING ENOUGH — occurs
     at the end of the movement
  2. trunk_flexion — patient is LEANING BACK TOO FAR — occurs throughout
  3. hip_flex_active — patient's HIP IS LIFTING OFF THE SEAT — occurs
     at the end of the movement
  4. tempo — patient is MOVING TOO FAST

Write three cues per fault:
  "short" — max 5 words
  "full"  — max 12 words
  "alt"   — max 8 words, a different mental image

Rules:
- Imperative. Tell them what to DO.
- External focus: describe an action on the world, not a muscle.
  Good: "Push the floor away."  Bad: "Contract your quadriceps."
- No numbers, no percentages, no anatomical jargon.
- Positive phrasing. Say what to do, never what to stop doing.
- Each cue must be executable on purpose by an untrained person.

Return ONLY a JSON array. No preamble, no markdown fences.
Schema: [{"fault_index": int, "short": str, "full": str, "alt": str}]
```

**Why external-focus phrasing:** the motor learning literature is consistent that external focus cues (attention on the effect of the movement) outperform internal focus cues (attention on the body part) for both immediate performance and retention. This is a real, citable design decision, not a style preference.

Temperature 0.3. Low, for format stability.

### 10.4 Output validation

Small models drift off-format. Validate every generated cue; reject and retry once with the failures listed in the prompt.

| Check | Rule |
|---|---|
| JSON parses | Valid array, all keys present, `fault_index` in range |
| Length | short ≤ 5 words, full ≤ 12, alt ≤ 8 |
| No digits | No `0-9` or `%` |
| No negatives | No "don't", "stop", "avoid", "never" |
| No hedging opener | Doesn't start with "you", "the", "try to", "make sure" |
| Uniqueness | No two faults share a cue string |
| Imperative | First word is a verb (check against a small verb list, or just require it isn't a pronoun/article) |

### 10.5 Fallback

If generation fails twice, or the model won't load on the loaner device, fall back to a mechanical template built from data we already have:

```
"{featureDisplayName} — {more|less}"     →   "Knee — straighten more"
```

Four lines of code. Ugly, and we will not use it on stage. It exists so that if the LLM hangs at 2 a.m. Sunday, the app is never silent.

### 10.6 Optional review screen

After generation, show the physio the cue list with a play button beside each so they can hear the exact TTS output. Editable. Skippable. Costs an hour to build, and demoing *"the physio can hear exactly what the patient will hear"* lands well.

### 10.7 Post-set summary (the second LLM use)

After a set, generate a patient-facing summary from the computed statistics. Inputs are numbers we already have; the model only phrases them.

```
Set complete. Data:
- Reps completed: 10 of 12
- Median quality: 71 / 100
- Form degradation detected starting at rep 5 (CUSUM, corroborated by slope)
- Largest changes after rep 5:
    knee extension: 14% less range
    trunk lean: increased by 1.8 sigma
- Cues given: "Straighten all the way" ×3, resolved 1 of 3

Write 3–4 short sentences for the patient. Encouraging but honest.
Name the specific change. End with one concrete thing to do next session.
No numbers above 2 decimal places. No medical advice beyond what is stated.
```

---

## 11. On-device model deployment

### 11.1 Pose estimation on the NPU

Qualcomm AI Hub publishes MediaPipe Pose pre-compiled for Hexagon. The detector and landmark models profile at roughly 1 ms each on NPU.

**Do this before the event, not during it:**

```bash
pip install qai-hub-models
qai-hub configure --api_token <TOKEN>
python -m qai_hub_models.models.mediapipe_pose.export --device "Snapdragon 8 Elite QRD"
```

This produces `.tflite` (with NPU delegate) and `.so` (QNN) artefacts. Bundle both. Ship the plain MediaPipe Tasks `.task` bundle as a third fallback.

**Runtime delegate selection with graceful degradation:**

```
try QNN / NPU delegate
  → on failure, try GPU delegate
    → on failure, CPU
```

Log which path was taken and surface it in a debug screen. This matters for the rubric: the phone/Office Kit component is 25% of the score and is measured from device data, so being able to demonstrate NPU utilisation is worth real points.

### 11.2 LLM

MediaPipe LLM Inference API on Android with a LiteRT-optimised Gemma-class model. Verify on the actual loaner device in hour 1 — measure time-to-first-token and decode speed before committing. The AI Edge Gallery app is the fastest way to benchmark candidate models on the device.

**Pre-warm the model at app launch, in the background.** Otherwise the first generation pays 5–10 s of load time and the physio thinks the app has frozen.

Budget: cue generation for 4 faults × 3 variants is one call, a few hundred output tokens. Even at modest decode speeds this is under 20 s, which is acceptable for a one-time authoring step with a visible progress indicator.

### 11.3 Model size and memory

Pose models are a few tens of megabytes. The LLM is the constraint — keep the total under control and unload the LLM while a live set is running. Never hold both under load simultaneously; the pose pipeline must have the NPU to itself during scoring.

---

## 12. Android implementation

### 12.1 Camera

```kotlin
val analysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .setTargetResolution(Size(720, 1280))
    .build()
```

`KEEP_ONLY_LATEST` is correct here: if we fall behind, we want the newest frame, not a backlog. Dropped frames are handled by timestamp-based timing.

720p is sufficient — the pose model downsamples anyway, and higher resolution costs latency for no accuracy gain.

### 12.2 Pipeline skeleton

```kotlin
class ExerciseEngine(
    private val reference: RposeReference,
    private val params: Params
) {
    private val filters = Array(33) { OneEuroFilter3D(params) }
    private val bodyFrame = BodyFrameTracker()
    private val phase = PhaseTracker(reference)
    private val scorer = DeviationScorer(reference)
    private val faults = FaultDetector(reference.faults)
    private val cues = CueArbiter(reference.faults, params)
    private val repAcc = RepAccumulator()

    private val _state = MutableStateFlow(FrameResult.EMPTY)
    val state: StateFlow<FrameResult> = _state

    fun onFrame(lm: LandmarkFrame, tNs: Long) {
        val dt = computeDt(tNs) ?: run { resetOnGap(); return }

        val sm  = filters.applyTo(lm, dt)
        val can = Canonicaliser.apply(sm, reference.manifest)
        val R   = bodyFrame.update(can) ?: return markInvalid()
        val f   = FeatureExtractor.extract(can, R)
        val v   = ValidityMask.compute(can, f, reference.watchList)

        val ph  = phase.update(f, v, dt)
        val z   = scorer.zScores(f, v, ph)
        val fs  = faults.update(z, ph, dt)
        val cue = cues.arbitrate(fs, dt)

        repAcc.accumulate(f, z, ph, v)
        if (ph.repCompleted) repAcc.closeRep()?.let { onRepComplete(it) }

        _state.value = FrameResult(can, f, v, ph, z, cue, repAcc.snapshot())
    }
}
```

Everything after `onFrame` is pure and synchronous. No coroutines inside the hot path.

### 12.3 Overlay rendering

Compose `Canvas`, reading `FrameResult` from the `StateFlow`. Three layers, bottom to top:

1. **Ghost skeleton** — reference pose at phase `p`, anchored at the patient's hip midpoint, scaled to their limb lengths, 35% alpha.
2. **Live skeleton** — neutral colour; segments belonging to the active fault's feature rendered in the danger colour.
3. **Correction arrow** — from the current joint position along the correction vector, length scaled by `|z|`.

Plus a single horizontal quality bar (`FrameScore`, 0–100) that transitions from danger to success colour as z drops. When it crosses into the success range: brief chime, arrow fades, cue clears.

**The bar is the feedback loop the patient can actually see working.** Prioritise it.

### 12.4 TTS

```kotlin
tts.speak(cue.text, TextToSpeech.QUEUE_FLUSH, null, cue.id)
```

`QUEUE_FLUSH` so a new cue interrupts a stale one. Set `Locale("en", "IN")`. Speak a silent warm-up utterance at app start so the first real cue isn't swallowed.

### 12.5 Haptics

`HapticFeedbackConstants.CONFIRM` on fault onset (short buzz) and `CLOCK_TICK` on fault resolution. Useful when the patient is holding the phone or has it in a pocket during a hold exercise, and it costs three lines.

---

## 13. Data model and sync

### 13.1 Room entities (local, source of truth)

```
User(uid, role, name, email)
Exercise(id, version, physioUid, name, rposePath, createdAt)
Assignment(id, exerciseId, exerciseVersion, patientUid, reps, sets,
           restS, sequenceIndex, activeFrom, activeTo)
Session(id, assignmentId, patientUid, startedAt, endedAt,
        setScore, completionRate, degradationOnsetRep, deviceFps, delegate)
Rep(id, sessionId, index, quality, rom, durationMs, valid, partial, jerk)
FeatureSample(repId, feature, meanZ, maxZ, meanSignedZ)
FaultEvent(id, sessionId, repIndex, faultId, zAtFire, tFireMs)
CueEvent(id, faultEventId, cueVariant, zAtFire, zAt2s, zNextRep, resolved)
```

Persist a `Rep` row **as each rep completes**, not at set end. If the app is killed mid-set, the session is still partially recoverable.

### 13.2 Firestore mirror

```
/users/{uid}
/exercises/{exerciseId}                       (metadata; .rpose in Storage)
/assignments/{assignmentId}
/patients/{patientUid}/sessions/{sessionId}
/patients/{patientUid}/sessions/{sessionId}/reps/{repIndex}
```

`.rpose` bundles and `demo.mp4` in Cloud Storage, referenced by path.

### 13.3 Offline-first

The **entire patient session works with no network.** Room is authoritative. Sync is a background `WorkManager` job with exponential backoff. This is not a nice-to-have — home patients have unreliable connectivity, and a rehab app that requires a connection to score a set is not a rehab app.

### 13.4 Conflict handling

Last-write-wins per document. `.rpose` immutability (9.6) means the interesting conflict — the physio editing an exercise while the patient performs it — cannot corrupt anything: the patient's session is bound to `(exerciseId, version)` and the edit creates a new version.

---

## 14. UI specification

### 14.1 Physio flow

| Screen | Contents |
|---|---|
| Patient list | Name, adherence %, last session, flag badge (progression-ready / needs review) |
| Patient detail | Assigned exercises, progression curves per metric, session history |
| Exercise library | Physio's saved `.rpose` exercises, with mode/view badges |
| **Record exercise** | Live camera, rep counter ticking up as they perform, big stop button |
| **Reference review** | Dominant-feature chart with rep boundaries marked and the μ ± 3σ band drawn on top; validator results; watch-list with derived weights; "re-record" button |
| Cue review | Generated cues with a play button beside each; editable; skippable |
| Assign | Select patient, set reps/sets/rest, set goal, order in daily sequence |
| Session report | Per-set quality curve, degradation change-point marked, attribution, cue effectiveness table |

### 14.2 Patient flow

| Screen | Contents |
|---|---|
| Today | Ordered list of prescribed exercises, completion ticks |
| Demo | Physio's `demo.mp4` looping, plus a skeleton animation of the reference |
| **Calibration** | Live camera with the 7-item checklist, ticks appearing as each passes |
| **Exercise** | Camera + ghost + live skeleton + arrow + quality bar + rep counter + phase ring |
| Set summary | LLM-written summary, quality-per-rep chart with the change point marked |
| Progress | Progression curves toward goal, per metric |

### 14.3 Office Kit integration

Two authentic uses. Not bolted on.

1. **Reference tuning on the laptop.** The physio records on the phone (phone-first, scores well), then reviews and adjusts the σ envelopes on the laptop via Office Kit screen mirroring and remote control. Dragging a tolerance band on a chart is genuinely painful on a 6" screen and obvious on a 15" one.
2. **Clinic mode.** Mirror the live patient session to the laptop so a physio can observe remotely while the patient exercises.

Office Kit usage is 10% of the score and is measured from device data, not self-reported. Design these in from the start rather than adding them Sunday morning.

---

## 15. Hackathon rubric mapping

| Criterion | Weight | How we address it |
|---|---|---|
| End product quality | **30%** | Four exercises working flawlessly end to end. Offline-capable. Validator prevents bad states. This is the highest-weighted criterion — protect it by cutting scope, not by adding features. |
| Novelty and impact | **20%** | Within-set fatigue detection with change-point localisation is genuinely unaddressed. Auto-derived tolerance from clinician variance is a real idea. Clinical impact is concrete and explainable. |
| Technical depth | **15%** | Full biomechanics pipeline: zero-phase filtering, body-frame construction with Gram–Schmidt, autocorrelation segmentation, phase-indexed statistical reference with error propagation, CUSUM change-point detection. Architecture and robustness, not model count. |
| Demo and presentation | **10%** | Rehearsed 4-minute script (section 17) ending on the fatigue-stop moment. |
| Creative phone use | **15%** | Camera as the primary sensor, NPU for pose inference, on-device LLM, TTS out, haptics, accelerometer for phone-tilt gating. Measured from device data. |
| Office Kit usage | **10%** | Reference tuning on laptop + clinic mode mirroring. Both genuine. Measured from device data. |

**Red Light planning.** 55% of build time is phone-only with laptop access routed through Office Kit. Consequences:

- Set up Android Studio to work acceptably through Office Kit remote control **before the event**. Test it at home.
- Do all environment setup, model export, and dependency resolution **before Saturday**. Nothing that requires unrestricted laptop access should be on the critical path.
- Test on-device continuously rather than in an emulator; the emulator is a laptop-bound workflow and it doesn't have the NPU anyway.

---

## 16. Build schedule

30 hours, roughly 19 hours of pure build time after ceremonies and evaluation rounds.

| Hours | Block | Deliverable | Gate |
|---|---|---|---|
| 0–2 | **Foundation** (Green Light) | Pose pipeline running on device, NPU delegate confirmed, LLM benchmarked, **axis conventions determined empirically** | Landmarks rendering on live camera at ≥25 fps |
| 2–5 | **Geometry** | One Euro filter, body frame, all 14 features, validity mask | Angles stable and correct on a person standing and moving |
| 5–9 | **Authoring pipeline** | Butterworth, autocorrelation, segmentation, resampling, reference builder, watch-list, validator | Record 8 knee extensions → correct rep count, sensible bands, chart renders |
| 9–13 | **Live scoring** | Phase state machine, z-scoring with inflation, rep counting, rep acceptance | Live rep counter matches reality; z-scores sane |
| 13–16 | **Overlay** | Ghost skeleton, live skeleton with fault colouring, correction arrow, quality bar | Visible feedback loop working |
| 16–19 | **Cues** | Fault detector with hysteresis, masking, arbitration, escalation, TTS | One cue at a time, correct direction, no chatter |
| 19–22 | **Analysis** | Rep scoring, CUSUM, attribution, LLM summary | Fatigue demo produces a correct change point |
| 22–25 | **Two roles + sync** | Auth, patient list, assignment, Firestore, progression math | Physio records → patient performs → physio sees result |
| 25–27 | **Hold mode + 4th exercise** | Wall sit path, remaining exercises validated | All four demo exercises pass the validator |
| 27–29 | **Polish + rehearsal** | Office Kit flows, Room persistence hardening, demo run-throughs | Demo runs clean three times consecutively |
| 29–30 | **Freeze** | No new code. Rehearse. Charge devices. | — |

**Freeze at hour 26 if anything is unstable.** Four hours of rehearsal on a working build beats two more features on a broken one.

**Dependency order matters:** the authoring pipeline must be right before live scoring, because live scoring consumes its output. Do not start the live path before the reference builder produces a chart you believe.

---

## 17. Demo script

Four minutes. Rehearse until it runs clean three times in a row.

**0:00 — The problem (25 s).**
"A physio prescribes 12 knee extensions. The patient does them at home. Reps 1 through 4 are perfect. By rep 8 they're fatigued, they're compensating with their trunk, and the exercise has stopped doing anything therapeutic — it may be making things worse. Nobody sees it. That's what we built for."

**0:25 — Authoring, including a deliberate failure (60 s).**
Physio records a deliberately sloppy set. App rejects it: *"Your reps varied too much for me to learn a reliable target."* Re-record properly. Show the derived band chart appearing, with rep boundaries marked. Point at the band: "We never picked those numbers. The tolerance comes from the physio's own rep-to-rep consistency."

Show cue generation running on-device.

**1:25 — Patient calibration (25 s).**
Show the checklist. Deliberately stand at the wrong angle — the view check fails. Turn side-on; it passes. "Single-camera depth is unreliable, so we force the camera perpendicular to the movement. Same thing a physio does with a mirror."

**1:50 — Live scoring (60 s).**
Perform reps. Ghost overlay visible. Deliberately let the trunk lean — red segment, arrow, spoken cue. Correct it — bar goes green, chime. "One cue at a time. Never two. And notice it cued the trunk, not the knee, because the knee deviation is downstream of the trunk compensation."

**2:50 — The fatigue moment (50 s).**
Continue reps, progressively degrading. Around rep 8 the app says: *"Your form has been declining since rep 5. Knee range is down 14%, trunk lean is up. Stop the set and rest."*

Hold on that screen. "It didn't count reps. It told the patient to stop before they hurt themselves."

**3:40 — Physio side (20 s).**
Switch to the physio dashboard. Session appears. Point at the cue effectiveness line: "We cued knee valgus 8 times. It resolved twice. That's a strength deficit, not a technique problem — and that's a clinical finding the app produced."

**Close.** "Everything you saw ran on the phone. Pose on the NPU, LLM on-device, works with no network. The scoring is deterministic, so a physio can ask why rep 6 was flagged and we answer with a number."

### 17.1 Anticipated judge questions

**"Where's your model?"** — Pose estimation is the ML. Everything downstream is deterministic by design: auditable (we answer "why rep 6" with a number), needs zero training data per exercise (a physio invents an exercise Tuesday, it scores Tuesday), personalised by construction (tolerance from the prescribing clinician's own movement), and fast enough to leave NPU headroom for the LLM.

**"Single camera can't do 3D properly."** — Correct, and that's why `required_view` exists and why calibration enforces it. We force the camera perpendicular to the plane of motion and weight sagittal-plane features above depth-dependent ones. We also abstain rather than guess when landmarks are occluded.

**"How do you know your tolerance bands are clinically valid?"** — We don't claim clinical validation; we claim the bands reflect the prescribing clinician's own demonstrated tolerance, which is a defensible proxy and is exactly what the physio is implicitly communicating when they demonstrate. Clinical validation would be the next step, and the data structure supports it.

**"What can't it do?"** — Fine motor (hand/wrist) — landmark noise exceeds the signal, so we refuse rather than pretend. Load — a patient using the wrong resistance band looks perfect on camera. Pain, which is the thing that actually matters most and no camera detects.

---

## 18. Risk register

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 1 | Axis sign conventions wrong → backwards cues | High | Severe | Determine empirically hour 1, encode in one constant, unit-test with a synthetic upright pose |
| 2 | Phase estimation chatters at turnaround | High | High | Deadband + minimum dwell (6.12.3). Test with deliberately slow reps early |
| 3 | Spurious faults at steep part of movement | High | High | Phase-uncertainty inflation (6.13.2). If still noisy, raise `δp` |
| 4 | LLM won't load on loaner device | Medium | Medium | Benchmark hour 1. Template fallback (10.5) |
| 5 | NPU delegate fails | Medium | Low | GPU then CPU fallback chain; log which path |
| 6 | Thermal throttling degrades fps | Medium | Medium | Timestamp-based timing throughout; degrade overlay complexity below 15 fps |
| 7 | Firestore setup consumes hours | Medium | Medium | Configure project **before the event**. If it slips, demo with local-only and two devices side by side |
| 8 | Red Light blocks needed laptop work | High | High | Complete all environment setup pre-event; test Android Studio through Office Kit at home |
| 9 | Scope creep | High | Severe | The cut list (3.1) is binding. Four exercises. Freeze at hour 26 |
| 10 | Demo device battery / thermal at pitch time | Medium | Severe | Two charged devices, app pre-warmed, airplane mode off but no heavy sync, rehearse on the actual demo device |
| 11 | Reference recording fails on stage | Medium | High | Pre-record and bundle a known-good `.rpose` for each demo exercise as a fallback; the live recording is the *demo*, not the *dependency* |
| 12 | Segmentation fails on an unusual exercise | Medium | Medium | Validator catches it and asks for a re-record. Restrict the demo to the four validated exercises |

---

## 19. Pre-event checklist

Complete every item before Saturday 08:00. None of these should consume build hours.

**Environment**
- [ ] Android Studio, SDK, NDK installed and building a hello-world on the loaner-equivalent device
- [ ] Android Studio verified usable through Office Kit remote control
- [ ] Git repo created, both teammates cloned, branch strategy agreed

**Models**
- [ ] Qualcomm AI Hub account created, API token configured
- [ ] `qai_hub_models.models.mediapipe_pose.export` run; `.tflite` and `.so` artefacts downloaded and committed
- [ ] Plain MediaPipe Tasks `.task` bundle downloaded as fallback
- [ ] Candidate LiteRT LLM downloaded and benchmarked on device (record TTFT and decode speed)

**Backend**
- [ ] Firebase project created, Auth enabled, Firestore rules drafted, Storage bucket created
- [ ] `google-services.json` committed

**Scaffolding (permitted — this is setup, not the build)**
- [ ] Project skeleton with the module structure from 4.3, empty classes
- [ ] `Params.kt` populated with the section 7 table
- [ ] CameraX preview + MediaPipe landmarks rendering, verified at ≥25 fps on device
- [ ] One Euro filter implemented and unit-tested against a synthetic noisy sine
- [ ] **Synthetic landmark generator** built — parametric sine trajectories with known rep count, known ROM, injectable Gaussian noise and dropout. Without it you cannot test the authoring pipeline at hour 6 unless a person is standing in front of a working camera. Two hours pre-event, saves five during.
- [ ] Watch-list direction test written (§6.10) — a deliberately unconstrained feature must be excluded
- [ ] Mask-rule startup assertion written (§6.15.1) — every feature name resolves

**Knowledge**
- [ ] Every team member has read section 6 and can explain the tolerance-band idea in one sentence
- [ ] Compensation chain table (6.15.1) reviewed with a physiotherapist if one is reachable
- [ ] Demo script (17) read aloud at least once

**Logistics**
- [ ] Two devices charged, chargers packed
- [ ] Tripod or phone stand (essential — handheld calibration will fail)
- [ ] Fitted clothing for whoever demos
- [ ] Clear floor space requirements understood for the venue

---

## Appendix A — Positive-z meaning per feature

The single most common source of backwards cues. Fill this in during implementation and treat it as authoritative.

| Feature | Positive z means | Cue direction |
|---|---|---|
| `knee_flex_active` | Angle larger than reference = **straighter** than target | "Bend more" |
| `knee_frontal_active` | Shank deviates **inward** relative to thigh | "Knees out" |
| `hip_flex_active` | Angle larger = **more open** hip | "Bring your knee up more" |
| `hip_abduction_active` | Leg further **out** to the side | "Bring it back in" |
| `trunk_flexion` | **More** forward lean | "Chest up" |
| `trunk_lateral` | Leaning toward the **active** side | "Stay centred" |
| `trunk_rotation` | Shoulders rotated **toward active side** | "Square your shoulders" |
| `pelvic_obliquity` | Active-side hip **higher** | "Level your hips" |
| `shoulder_flex_active` | Arm **higher** than target | "Lower it slightly" |
| `shoulder_elevation` | Shoulder **shrugged up** | "Relax your shoulders down" |
| `elbow_flex_active` | Arm **straighter** than target | "Bend your elbow more" |
| `ankle_dorsiflex_active` | **Less** dorsiflexion (heel rising) | "Keep your heel down" |
| `knee_flex_passive` | Same meaning as active, non-working side | Usually not cued; watch for compensation |
| `hip_flex_passive` | Same meaning as active, non-working side | Usually not cued; watch for compensation |
| `lumbar_flexion` | Lower back **rounding** (positive) / **arching** (negative) | "Tuck your ribs down" / "Lengthen your back" |

Verify each empirically. Do not infer them from the formulas.

---

## Appendix B — Implementation order within section 6

If time compresses, build in this order. Each step is testable in isolation.

1. 6.1, 6.2 — conventions and canonicalisation
2. 6.4, 6.5 — body frame and features (**test: angles correct on a stationary person**)
3. 6.3.1 — One Euro (**test: jitter gone, no perceptible lag**)
4. 6.6 — validity mask
5. 6.3.2, 6.7 — offline filter and segmentation (**test: 8 reps → 8 detected, first/last dropped**)
6. 6.8, 6.9 — resampling and reference (**test: band chart looks sane**)
7. 6.10, 6.11 — watch-list and validator (**test: bad recording rejected with correct reason**)
8. 6.12 — phase tracker (**test: live rep count matches reality over 20 reps**)
9. 6.13 — z-scoring (**test: deliberate deviation produces the expected sign and magnitude**)
10. 6.17 — frame and rep scoring
11. 6.16 — correction vector and ghost
12. 6.14, 6.15 — faults and cues (**test: one cue at a time, no chatter, correct direction**)
13. 6.18 — CUSUM (**test: deliberately degrading set produces the right change point**)
14. 6.20 — calibration
15. 6.19 — hold mode
16. 6.21 — progression

Steps 1–9 are the critical path. Everything from step 10 onward is presentation of results the maths has already produced.
