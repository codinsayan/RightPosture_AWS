# RytPosture / RightPosture — Project Overview

> Synthesized from `CHANGELOG.md`, `CHANGELOG-core.md`, `RightPosture_Implementation_Plan (3).md`, `RightPosture_Iteration_Plan.md`, `docs/`, `docs/iterations/*.md`, `supabase/schema.sql`, and the Gradle build config, as of 2026-09-19. This is a snapshot for onboarding/upgrade purposes — treat status claims below as of the date above and re-check against `docs/iterations/*.md` (status field is authoritative) before relying on anything load-bearing.

## 1. What the product is

**RightPosture** (package `com.example.rightposture`, app name **RytPosture**) is a native Android app (Kotlin, Jetpack Compose) built for the **iQOO Hackathon 2026** (Pune City Battle, 5–6 Sept 2026, HealthTech track) as a **30-hour prototype**, not a shipping product.

It targets home physiotherapy's three failure modes: non-adherence nobody detects, incorrect execution from rep 1, and — the gap nobody else addresses — **progressive within-set form degradation**, where a patient starts a set correctly and silently deteriorates by the final reps.

**Central idea:** an exercise is not a pose, it's a *phase-indexed trajectory through joint-angle space*, with a tolerance envelope derived from the clinician's own rep-to-rep variance. A physiotherapist records 6–8 reps; the system computes, per joint per phase-of-movement, a mean (μ) and standard deviation (σ). σ *is* the tolerance band — no threshold is ever hand-picked.

**Two roles:**
- **Physiotherapist** — records a reference performance, reviews the auto-derived tolerance/validator verdict, assigns exercises (reps/sets/hold-duration/goal) to patients, views per-patient dashboards, approves progression (system flags, never auto-advances).
- **Patient** — sees today's prescribed exercises, watches the physio's demo, calibrates the camera, performs with live scoring/cues/overlay, gets a post-set LLM summary; data syncs to their physio.

**Explicitly out of scope:** consent/privacy/medical-disclaimer flows, clinical validation of tolerance bands, compound multi-segment exercises, hand/wrist rehab, multi-set rest timers, voice control, accessibility/localization/tablet support. Runs fully on-device (phone-only, no cloud inference).

**Demo exercise set (4):**

| Exercise | Mode | View | Why chosen |
|---|---|---|---|
| Seated knee extension | cyclic | sagittal | clean textbook signal |
| Standing hip abduction | cyclic | frontal | proves view-handling + unilateral logic |
| Wall sit | hold | sagittal | proves the hold scoring path |
| Sit-to-stand | cyclic | sagittal | largest ROM, best fatigue story |

## 2. High-level architecture

Two pipelines share one mathematical vocabulary but run at different times.

### Authoring pipeline (offline, physio's phone, batch)
```
Record video + landmark stream
 → Butterworth zero-phase filter (offline, 4th-order, 6 Hz)
 → Feature extraction (15 joint-angle/planar features)
 → Dominant joint selection (5th/95th percentile swing)
 → Autocorrelation → rep period estimate
 → Peak/valley detection → rep segmentation
 → Refine dominant joint using clean segments
 → Phase-based resampling to 101 bins (phase from dominant joint's value, not time)
 → Reference statistics: median μ[101] + MAD-based σ[101] per feature (smoothed/floored/capped/inflated)
 → Watch-list selection by salience (ROM / σ)
 → Validator (8 checks) → accept or reject with actionable reason
 → (LLM cue generation — designed, never wired to real faults)
 → Write .rpose bundle (versioned ZIP)
```

### Live pipeline (streaming, patient's phone, per-frame)
```
CameraX frame (MediaPipe NPU→GPU→CPU delegate ladder)
 → One Euro filter (adaptive low-pass)
 → Canonicalisation (mirror + left/right swap, active-side resolution)
 → Body frame construction (Gram–Schmidt orthonormal basis)
 → Feature extraction (15 features)
 → Validity gating (abstention on occlusion/low confidence)
 → Phase tracker state machine (IDLE→DESCEND→BOTTOM_HOLD→ASCEND; separate tracker for Hold mode)
 → μ/σ lookup + phase-uncertainty-inflated interpolation
 → Z-scores (signed, per feature)
 → Fault detection (hysteresis + dwell + phase-gating)
 → Cue arbitration (masking/compensation-chain rules, priority, cooldown) — one cue at a time
 → Correction vector (built but unverified/dark)
 ├→ TTS (Google TTS, en-IN + Hindi)
 ├→ Overlay state (skeleton colour, quality bar, ghost)
 └→ Rep accumulator → rep score → CUSUM fatigue detection → SessionRecord → Room → Supabase sync
```

Everything downstream of the reference maps back to a number: rep counting = phase zero-crossings; "is form wrong" = z-score vs μ(p)±σ(p); "what to fix" = sign of z-score selects a cue; "how close to correct" = exp(-weighted Σz²); "when did degradation start" = CUSUM change-point on per-rep quality.

**Threading:** camera thread (CameraX, KEEP_ONLY_LATEST) → single-threaded inference executor (pose + entire scoring chain) → main thread renders an immutable `FrameResult` from a StateFlow. A separate single-threaded LLM executor handles authoring/summaries only — the model is explicitly unloaded before a live set starts.

## 3. Module layout (single `:app` module)

Under `app/src/main/java/com/example/rightposture/`:

- **`core/pose/`** — pure math, no Android imports: `Angles.kt`, `BodyFrame.kt`, `Canonicalisation.kt`, `FeatureExtraction.kt`, `FeatureValidity.kt`, `LandmarkFrame.kt`, `OneEuroFilter.kt`, `PoseLandmark.kt`, `ViewClassification.kt`, `SyntheticLandmarkGenerator.kt`.
- **`core/filter/ButterworthFilter.kt`** — offline zero-phase 4th-order Butterworth.
- **`core/reference/`** — authoring pipeline: `DominantPeriod.kt`, `Segmentation.kt`, `PhaseResampling.kt`, `ReferenceBuilder.kt`, `WatchList.kt`, `ReferenceValidator.kt`, `AuthoringPipeline.kt`, `BundleAssembly.kt`, `HoldAuthoring.kt`, `RobustSignal.kt`.
- **`core/live/`** — live engine, orchestrated by **`ExerciseEngine.kt`**: `PhaseTracker.kt`, `ZScore.kt`, `Scoring.kt`, `FaultDetector.kt`, `CueArbiter.kt`, `CueDispatcher.kt`, `Fatigue.kt` (CUSUM), `Sparc.kt`, `SessionRecord.kt`, `HoldMode.kt`, `Calibration.kt`, `Phrases.kt`, `CorrectionArrow.kt`/`ArrowSelector.kt`/`ArrowSignCheck.kt` (dead/unverified).
- **`core/rpose/`** — `Rpose.kt` (`.rpose` bundle format), `Json.kt` (hand-rolled).
- **`core/progression/Progression.kt`**, **`core/summary/`** (`SummaryFacts.kt`, `SummaryPrompt.kt`, `SummaryGenerator.kt`, `SummaryValidator.kt`, `Numerals.kt`, `ProgressFacts.kt`), **`core/FeatureId.kt`**, **`core/Params.kt`** (the single tuning surface).
- **`pose/`** (Android layer) — `PoseLandmarkerSource.kt`, `PoseBackend.kt`, `PoseFrameMapper.kt`, `PoseSkeleton.kt`, `AxisConvention.kt`, `AxisDiagnosticCamera.kt`.
- **`camera/`** — `CameraController.kt`, `CameraPermission.kt`, `CameraPreviewScreen.kt` (large composable, flagged for splitting).
- **`exercise/`** — `ExercisePanel.kt`, `HoldPanel.kt`, `CalibrationPanel.kt`, `ExercisePicker.kt`.
- **`authoring/`** — `ReferenceReview.kt`, `SaveExerciseDialog.kt`.
- **`catalog/DemoExercises.kt`**, **`sensors/GravitySensor.kt`**, **`speech/`** (`GoogleTts.kt`, `ViewHaptics.kt`).
- **`data/`** — Room: `Entities.kt`, `Daos.kt`, `RightPostureDatabase.kt`, `SessionRepository.kt`.
- **`sync/`** — Supabase (hand-rolled HTTP, not the official SDK): `SupabaseClient.kt`, `SupabaseAuth.kt`, `InviteCodes.kt`, `InviteRepository.kt`, `ExerciseSync.kt`, `SessionSync.kt`, `SessionSyncWorker.kt`, `Retry.kt`.
- **`llm/OnDeviceLlm.kt`** — LiteRT-LM Gemma wrapper.
- **`report/`** — `Analytics.kt`, `Deltas.kt`, `RepReadout.kt`, `SessionFacts.kt`.
- **`nav/`** — hand-rolled navigation (`Navigation.kt`, `Screens.kt`, `AppHost.kt`, one file per screen).
- **`ui/`** — design system (`theme/`, `components/`, `CategoryTag.kt`) — no Material You/dynamic colour, since clinical colour meanings must be exact.

**Team split** (`docs/iterations/PARALLEL-TRACKS.md`): Track A ("core") owns `core/` except `Phrases.kt`; Track B ("features") owns UI/camera/exercise/sync/data/nav. Shared/conflict-prone files: `core/Params.kt`, `core/live/ExerciseEngine.kt`, `core/rpose/Rpose.kt`.

## 4. Key algorithms

- **One Euro Filter** (live, per-landmark) — adaptive cutoff, `FC_MIN=1.0 Hz, BETA=0.02, D_CUTOFF=1.0 Hz`; resets on timestamp gap or re-detection.
- **Zero-phase Butterworth** (offline only) — 4th-order, 6 Hz, forward+reverse biquad cascade (filtfilt-equivalent), hand-implemented (no Kotlin scipy).
- **Body frame** — Gram–Schmidt-orthonormalized basis from hip/shoulder midpoints, makes angles view-invariant.
- **Joint angles** — unsigned via `atan2(|v1×v2|, v1·v2)` (not `acos`, which loses precision near full extension); signed planar angles for lateral/valgus deviations.
- **15 features** — knee/hip/shoulder/elbow/ankle flexion, knee frontal (valgus), hip abduction, trunk flexion/lateral/rotation, pelvic obliquity, shoulder elevation, lumbar flexion (coarse proxy).
- **Dominant-feature + period detection** — percentile-swing ranking + normalized autocorrelation, first local max ≥0.4 (not global max, to avoid 2×period aliasing).
- **Rep segmentation** — prominence-ranked peak/valley detection, `0.6×period` min spacing, alternation repair, MAD-based duration-outlier rejection, min 4 surviving reps.
- **Phase definition** — φ from the dominant feature's own value (not elapsed time), monotonicity enforced per half-rep, resampled to 101 bins.
- **Reference building** — median trajectory, MAD-based σ with finite-sample correction, smoothed, floored (`max(2.5°, 0.05×ROM)`, 6° for lumbar), capped at 15°, then inflated ×1.15/×1.30 (upright/non-upright) after the cap.
- **Watch-list** — `salience = ROM / median(σ)`; PASS filters on ROM, σ, visibility, salience; top-5 + mandatory dominant feature.
- **Reference validator** — 8 checks with actionable failure messages.
- **Live phase tracker** — 4-state machine, EMA-smoothed velocity, deadbands + min dwell times, 15% overshoot tolerance; rep acceptance needs ≥75% reference ROM, duration in [0.4T,3.0T], ≥70% valid frames, else "partial rep."
- **Z-scoring** — phase-interpolated μ/σ with phase-uncertainty inflation (`σ_eff = sqrt(σ² + (dμ/dp·δp)²)`).
- **Fault detection** — named conditions (feature+sign+phase-range+priority), hysteresis (enter |z|≥3.0/300ms, exit |z|≤1.5/500ms), phase-gating.
- **Cue arbitration** — exactly one cue at a time, hardcoded masking/compensation-chain rules (deliberately not delegated to the LLM) — **flagged as unvalidated clinical logic pending physiotherapist review**.
- **Scoring** — exponential frame score off weighted Σz², phase-weighted; set score = median over valid reps.
- **CUSUM fatigue detection** — baseline from early reps, CUSUM change-point + slope corroboration on per-rep quality, needs ≥6 valid reps. Safety stop: two consecutive reps <0.7×baseline halts the set with a spoken warning.
- **SPARC** (smoothness) — computed on speed profile (not signed velocity), last frequency bin above 0.05 magnitude cutoff.
- **Hold mode** — deliberately separate from cyclic: dominant = furthest from neutral, salience = 1/σ, no phase/PhaseTracker, sustained-drift detection, 1 Hz frame-score sampling.
- **Calibration** — 7 independent checks (visibility, distance, centering, view angle, phone uprightness via gravity sensor, lighting, stillness); 5s periodic re-check; phone bump pauses rather than corrupts.
- **Progression/goal state** — 2-of-3 "ready" rule, review-flag rule, never auto-advances (physio approves).
- **LLM safety validator** (`SummaryValidator.kt`) — every digit/unit word in an LLM summary must be in a computed whitelist; diagnosis/treatment/unsafe-advice word-lists block clinical claims; one retry then falls back to a pure-arithmetic template.

## 5. Chronological build narrative

- **v0.x — Baseline & pose runtime.** Gradle/APK pipeline confirmed on-device; MediaPipe Full pose model pinned; empirical axis-convention diagnostic (`docs/pose-runtime.md`).
- **v1.x — Pure geometry foundation.** Domain primitives, canonicalisation, angle math, body frame, all 15 features, validity/abstention contract.
- **v2.x — Camera & inference.** CameraX preview (rotation/resolution bugs found and fixed on-device); MediaPipe NPU→GPU→CPU delegate ladder (29fps on GPU on target iQOO I2501/SM8850; NPU blocked — no vendor .so binaries); One Euro filter wired to all landmark channels.
- **v3.x — Authoring pipeline.** Synthetic landmark generator; offline Butterworth; dominant/period estimation; segmentation; phase resampling; robust reference builder; watch-list selection; reference validator; `.rpose` read/write.
- **v4.x — Live scoring core.** Phase tracker; z-score lookup; frame/rep/set scoring; fault state machine; cue arbitration (masking rules transcribed but unvalidated by a clinician).
- **v5.x — Patient feedback & calibration.** `ExerciseEngine` created as orchestrator; minimal exercise UI; Google TTS (en-IN + Hindi); correction arrow/ghost overlay built but never verified (dead code); calibration (7 checks).
- **v6.x — Fatigue & hold mode.** Rep/session model; CUSUM + SPARC; hold mode as a genuinely separate path; 4 demo exercises catalogued as descriptive metadata.
- **v7.x — Persistence, roles, backend/sync.** Room schema (7 tables, persist-per-rep for crash recovery); hand-rolled navigation; **backend pivoted from the plan's Firebase to Supabase** (`.rpose` bundles too large for Firestore docs, no-card free tier); official `supabase-kt` SDK abandoned mid-iteration (Kotlin/KSP conflict with Room) → replaced by hand-rolled HTTP client over PostgREST/GoTrue; invite-code linking; WorkManager sync with backoff.
- **v8.x — On-device LLM, analytics, progression.** Deterministic cue templates / LLM cue wording (8.1/8.2) stayed unimplemented (faults never got real definitions). Post-set LLM summaries: Gemma E2B ~1.5s/summary on GPU after fixing 4 device bugs (wrong model file, encoder backend mismatch, missing manifest declaration, silently-failing delegate rung). Analytics screens (absent measurement = gap, never zero). Rep-detail rendering fix (score vs. counted-or-not as orthogonal facts). Richer summary context (ΔROM/ΔTempo/ΔSmooth fatigue-pattern facts).
- **v9.x — Demo hardening.** Office Kit flows, failure-path rehearsal, demo freeze — **all remained `planned`, never executed** per the iteration docs.
- **Post-9.x UI redesign wave** (documented only in `CHANGELOG.md`, dated 2026-09-06): full design-system + Today screen rebuild; session report split into Summary/Reps/Form tabs; Progress split into Overview/Quality/Range/History tabs; physio home split into Activity/Patients/Library/Me tabs; new Patient Detail screen; Library enrichment; 5-step recording wizard; hold mode made first-class/syncable (Room v2→v3, `.rpose` 1.0→1.1); cross-session summaries; streaming LLM output with safety-gated reveal; summary retries reverted to a single attempt.

## 6. Backend (Supabase)

`supabase/schema.sql`: `users`, `exercises` (keyed `(id,version)`, immutable — edits create new versions), `invites` (6-char code, atomic `redeem_invite()` SECURITY DEFINER function), `assignments`, `sessions`, `reps` (keyed `(session_id, rep_index)` for idempotent sync), `feature_samples`, `fault_events`/`cue_events` (schema exists but **unpopulated** — no exercise has real fault definitions yet). RLS enabled everywhere; ownership resolved via `SECURITY DEFINER` helper functions (avoids Postgres 42P17 policy-recursion bugs found during development). Storage: one private bucket `exercises` for `.rpose` bundles + `demo.mp4`.

Room is the offline-first source of truth; WorkManager pushes to Supabase with exponential backoff (5xx/429 only); last-write-wins; `.rpose` immutability avoids the one real conflict (physio editing mid-session).

**Operational note:** Supabase free tier auto-pauses after 7 days of no DB activity (data frozen, not lost).

## 7. UI screens and flows

Patient tabs: Today / Progress / Me. Physio tabs: Activity / Patients / Library / Me. Full-screen flows suspend the tab bar: 5-step recording wizard (Choose → Set up/calibrate → Record → Review → Name & save), performing a set, onboarding.

Design rules carrying clinical meaning: no dynamic/Material-You colour (amber=slipped, grey=never-measured, green=passed); absent-vs-zero enforced everywhere in charts; quality bands 0–54 danger, 55–79 warning, ≥80 success, null=unknown; colour never the sole signal; bundled (not downloaded) fonts; bilingual EN/Hindi phrase bank.

See `docs/UI-SCREENS.md`, `docs/UI-FLOWS.md`, `docs/UI-CONTENT.md` (UI-CONTENT documents the actual current build; the other two skew more aspirational/target).

## 8. Key dependencies

- MediaPipe Tasks Vision 1.0.0 (33-landmark Full pose model)
- LiteRT-LM `litertlm-android` 0.16.1 (Gemma on-device; pinned below latest for Kotlin/KSP compatibility)
- Jetpack Compose (BOM 2024.09.00, Material3), Kotlin 2.0.21, AGP 9.0.1
- CameraX 1.4.2, Room 2.7.1 + KSP 2.0.21-1.0.28
- Ktor 3.5.2 (hand-rolled Supabase client, replacing abandoned `supabase-kt`)
- WorkManager 2.9.1, Robolectric 4.13 (pinned `@Config(sdk=[34])`)
- minSdk 26, target/compileSdk 36, `arm64-v8a` only, `isMinifyEnabled=false`

No Firebase dependency remains despite the original plan — fully replaced by Supabase + Ktor.

## 9. Known limitations / open tracks (as of last iteration docs)

From `docs/iterations/PARALLEL-TRACKS.md`:
- **C1** — 4 core iterations never formally closed (feature-extraction sign verification, reference-validator device gate, phase-tracker device gate, cue-arbitration device gate).
- **C2** — correction arrow/ghost overlay is dead code (rotation signs never verified on-device).
- **C3** — almost every `Params.kt` constant is un-calibrated against real recorded bodies — flagged as the single highest-value remaining item.
- **C4** — hold-mode CUSUM exposed but never wired to `assessFatigue`.
- **C5** — no per-patient reference-offset correction; may prevent hold tracker from leaving SETTLING state for differently-proportioned patients.
- **C6 (blocked)** — compensation-chain masking table transcribed from the plan, pinned by tests, **never reviewed by an actual physiotherapist**.

Other gaps:
- Faults are empty for all 4 demo exercises — live cueing (masking, arbitration, TTS) has never fired on real production data.
- No "Set a goal" screen — progression readiness always reads "no range goal set."
- LLM/pose on NPU blocked upstream (no vendor QNN SDK / no sm8850 Gemma build) — GPU is the practical ceiling.
- Session-1 baseline capture (§6.20.3) never built.
- `fallbackToDestructiveMigration(dropAllTables=true)` still armed in Room — fine pre-release, a real bug once on someone else's phone.
- No consent/privacy/medical-disclaimer/data-deletion flow anywhere.
- v9.1–v9.3 (formal pre-demo hardening) never executed.
- `CameraPreviewScreen.kt` repeatedly flagged as needing a split (~900+ lines, holds authoring/calibration/cyclic/hold flows).
- Repo lives inside a OneDrive-synced folder — has caused documented intermittent Gradle build failures.

## 10. Where to look for more detail

- `RightPosture_Implementation_Plan (3).md` — the math/spec source of truth (§0–§19).
- `RightPosture_Iteration_Plan.md` — the phased build plan.
- `docs/iterations/*.md` — ~48 per-version logs; **status field is authoritative** for what's actually built vs. merely planned.
- `docs/iterations/PARALLEL-TRACKS.md` — current outstanding work + file-ownership map.
- `CHANGELOG.md` / `CHANGELOG-core.md` — narrative reasoning (split to avoid merge conflicts).
- `docs/UI-CONTENT.md` / `UI-FLOWS.md` / `UI-SCREENS.md` — current build vs. target design.
- `docs/pose-runtime.md`, `docs/MODEL-SETUP.md` — pose runtime notes, on-device Gemma setup.
- `supabase/schema.sql` — backend source of truth.
