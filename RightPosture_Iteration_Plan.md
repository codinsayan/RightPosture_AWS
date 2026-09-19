# RightPosture — Short Implementation Iterations

Source: `RightPosture_Implementation_Plan (3).md`. This file reorganises that plan; it does not replace its formulas, thresholds, schemas, or safety rules.

## Working rule

Complete one iteration per coding session. Do not begin next iteration until its gate passes.

For every iteration:

1. Read only listed source sections and touched files.
2. Implement smallest complete slice. Reuse existing code and installed libraries.
3. Add smallest test that can catch regression.
4. Run `./gradlew testDebugUnitTest` (`.\gradlew.bat testDebugUnitTest` on Windows).
5. Run named device test when iteration needs camera, hardware, UI, or Firebase.
6. Report changed files, test result, remaining blocker. Stop.

Do not silently change constants. Keep all plan parameters in one `Params.kt`. Keep clinical direction, masking, validation, and abstention deterministic. LLM may phrase cues only.

## Confirmed starting point

- Single `:app` Android module; package `com.example.rightposture`.
- Kotlin, Jetpack Compose, min SDK 24, target/compile SDK 36.
- Default `MainActivity`, theme, example JVM test, and example instrumented test only.
- No CameraX, MediaPipe, navigation, Room, Firebase, LLM, or feature pipeline dependencies yet.
- Plan's `core/` and `data/` module tree is proposed, not present. Keep one module for MVP; use packages under `app/src/main/java`. Split Gradle modules only after measured need.

## Phase 0 — Preconditions

### Iteration 0.1 — Make baseline repeatable

- Read: §4.1, §15, §19.
- Build: no feature code. Confirm Gradle sync, JVM test, debug APK, physical-device install.
- Test: replace placeholder addition test only when first real unit test exists. Run `testDebugUnitTest` and `assembleDebug`.
- Device gate: app opens on intended Android phone.
- User action: install Android Studio/SDK if missing; enable USB debugging; connect phone; accept debugging prompt.

### Iteration 0.2 — Lock external choices

- Read: §4.1, §11, §19.
- Build: record chosen pose asset/delegate and fallback path in a short setup note. Add only dependencies needed for next iteration.
- Test: Gradle sync and dependency resolution.
- Gate: chosen MediaPipe pose model loads on target phone; CPU fallback is known.
- User action: obtain model files legally; create Qualcomm AI Hub account/token only if NPU export remains in scope. Never paste token into source or chat.

### Iteration 0.3 — Empirical axis convention

- Read: §6.1, Appendix A, risk #1.
- Build: temporary debug view/log for raw landmarks; one `AxisConvention` definition.
- Test: synthetic upright pose validates configured signs.
- Device gate: record values while standing upright and raising right arm on actual phone. Verify front/rear mirroring separately.
- User action: perform movement and confirm labels. This cannot be inferred safely from documentation.

## Phase 1 — Pure geometry foundation

### Iteration 1.1 — Domain primitives and parameters

- Read: §6.1–6.2, §7.
- Build: vector, landmark frame, timestamp gap result, feature identifiers, `Params.kt`. Copy values exactly from §7.
- Test: `dt <= 0` and `dt > 0.25 s` produce gap; feature identifier set is unique.
- Gate: JVM tests pass; no Android dependency in math types.

### Iteration 1.2 — Canonicalisation

- Read: §6.2.
- Build: mirror x and swap left/right labels; separate recording metadata from live camera facing.
- Test: mirror twice returns original; mirror both negates x and swaps labels.
- Gate: canonical output matches expected synthetic coordinates.

### Iteration 1.3 — Stable angle math

- Read: §6.5.1–6.5.2, §6.22.
- Build: unsigned three-point `atan2` angle, signed planar angle, magnitude/non-finite guards.
- Test: 90°, near-180°, zero-length invalid, signed ± cases, `|angle| > 90°` invalid.
- Gate: all JVM tests pass.

### Iteration 1.4 — Body frame

- Read: §6.4.
- Build: Gram–Schmidt body basis, world-to-body transform, reuse/invalid rules. No smoothing yet.
- Test: basis unit length and orthogonal; transformed known vectors; 0.10 m and 0.05 m degeneracy guards.
- Gate: deterministic synthetic tests pass.

### Iteration 1.5 — Feature extraction

- Read: §6.5, Appendix A.
- Build: plan-defined feature set only. Note plan says “14” but table includes 15 with `lumbar_flexion`; use identifiers in table and Appendix A, then resolve wording mismatch before release.
- Test: synthetic stationary poses for each implemented feature; positive-z physical meaning tests.
- Device gate: visually check angles while standing and moving.
- User action: confirm cue direction empirically for every demo-used feature. Never guess Appendix A signs.

### Iteration 1.6 — Validity and abstention

- Read: §6.6, §8.2, §6.22.
- Build: visibility/presence/vector/body-frame validity, 300 ms recovery, downstream mask contract.
- Test: invalid feature cannot score or fire fault; weights renormalise; >30% invalid dominant frames makes rep unscored.
- Gate: all invalid paths abstain, never fabricate values.

## Phase 2 — Camera and live landmarks

### Iteration 2.1 — Camera preview

- Read: §12.1, §5.2.
- Build: CameraX preview, permission handling, front-camera default, `KEEP_ONLY_LATEST`, 720p analysis.
- Test: permission-denied UI test or logic test.
- Device gate: preview opens, rotates correctly, survives background/foreground.
- User action: grant camera permission; provide tripod/stand and fitted clothing.

### Iteration 2.2 — Pose inference and skeleton

- Read: §11.1, §12.1–12.3.
- Build: MediaPipe pose source and basic live skeleton. Log active delegate and FPS.
- Test: adapter maps all 33 landmarks and confidence values.
- Device gate: ≥25 fps target from plan; test NPU, then GPU/CPU fallback.
- User action: supply target iQOO/loaner-equivalent device. Emulator cannot validate NPU.

### Iteration 2.3 — One Euro filter

- Read: §6.3.1.
- Build: 99 scalar filter states, timestamp-based update, reset on gap/re-detection.
- Test: synthetic noisy sine has lower stationary jitter; step/fast motion remains bounded; gap resets.
- Device gate: tune `FC_MIN` with `BETA=0`, then `BETA`, using plan procedure. Record final values if changed.
- User action: hold still, then move quickly during tuning.

## Phase 3 — Authoring pipeline

### Iteration 3.1 — Synthetic landmark generator

- Read: §19 scaffolding checklist.
- Build: deterministic cyclic trajectories with known rep count/ROM plus optional noise/dropout.
- Test: seed reproduces identical sequence; requested rep count and ROM are measurable.
- Gate: later authoring tests need no person/camera.

### Iteration 3.2 — Offline Butterworth

- Read: §6.3.2.
- Build: 4th-order 6 Hz zero-phase Butterworth using two biquads, reflection padding, forward/reverse passes.
- Test: preserves low-frequency sine, reduces high-frequency component, introduces no measurable phase shift.
- Gate: test passes at representative timestamps/rates.

### Iteration 3.3 — Coarse dominant feature and period

- Read: §6.7.1–6.7.2.
- Build: 5th/95th swing ranking and per-lag normalised autocorrelation; first local maximum ≥0.40 within 0.5–8.0 s.
- Test: synthetic 8-rep signal returns correct dominant feature and period; aperiodic signal takes documented fallback.
- Gate: deterministic test passes.

### Iteration 3.4 — Extrema and rep segments

- Read: §6.7.3–6.7.6.
- Build: prominence/spacing extrema, alternation repair, boundary choice, first/last removal, duration rejection.
- Test: 8 clean synthetic reps produce 8 detected then 6 usable; mid-rep wobble does not split; duration outlier drops.
- Gate: at least 4 survivors required.

### Iteration 3.5 — Phase and resampling

- Read: §6.8.
- Build: feature-derived phase, outbound/return monotonic enforcement, 101-bin linear interpolation, separate absolute ROM.
- Test: same motion at different tempos resamples to same trajectory; stalls avoid divide-by-zero; undershoot preserves smaller ROM.
- Gate: 101 finite bins per valid feature.

### Iteration 3.6 — Robust reference builder

- Read: §6.9, §6.22.
- Build: median trajectory, corrected MAD, sigma smoothing, floor/cap, post-clamp inflation, derivative.
- Test: outlier barely shifts median; exact N=4–8 correction factors; upright ceiling 17.25°, non-upright 19.50°; MAD zero uses floor.
- Gate: reference arrays are finite and 101 long.

### Iteration 3.7 — Watch list

- Read: §6.10.
- Build: plan filters, salience sort, top 5, mandatory dominant feature, normalised weights.
- Test: required P3-15 cases: ROM 60°/sigma 22° excluded; ROM 40°/sigma 3° included.
- Gate: weights sum to 1 within tolerance.

### Iteration 3.8 — Reference validator and review

- Read: §6.11, §14.1.
- Build: eight validator checks with exact actionable failures; minimal dominant-feature chart and re-record action.
- Test: one focused case per validator rule; first failure is stable/actionable.
- Device gate: bad recording rejected; good knee-extension recording accepted and boundaries look correct.
- User action: record 8 steady reps, then one deliberate bad set.

### Iteration 3.9 — `.rpose` read/write

- Read: §9.
- Build: ZIP containing five JSON files; `demo.mp4` optional until captured; immutable ID/version rule. Use existing/platform JSON support where practical.
- Test: round trip preserves manifest/reference/faults/goals/validation; missing/unknown version rejected; arrays stay 101 long.
- Gate: known-good bundle reloads without data loss.

## Phase 4 — Live scoring

### Iteration 4.1 — Phase tracker

- Read: §6.12.
- Build: `IDLE`, `DESCEND`, `BOTTOM_HOLD`, `ASCEND`; EMA velocity; deadbands/dwell; timeout; partial-rep rules.
- Test: synthetic slow, fast, pause, chatter, partial, invalid, and 20-rep sequences.
- Device gate: live counter matches 20 real reps without phantom reps.

### Iteration 4.2 — Z-score lookup

- Read: §6.13.
- Build: phase interpolation, derivative-based uncertainty, old-bundle fallback, sigma clamp.
- Test: known deviation gives expected sign/magnitude; uncertainty widens steep phases; invalid feature returns no score.
- Gate: Appendix A direction cases pass for demo features.

### Iteration 4.3 — Frame and rep scoring

- Read: §6.17.
- Build: exponential frame score, phase weighting, ROM/tempo penalties, median set score/completion.
- Test: all 2-sigma features score about 55; 1-sigma about 86; short ROM loses credit; rushed rep penalised more than slow rep.
- Gate: partial/invalid reps excluded from statistics.

### Iteration 4.4 — Fault state machine

- Read: §6.14.
- Build: named conditions, phase gating, 3.0/1.5 hysteresis, 300/500 ms dwell, validity gate.
- Test: spikes never fire; sustained fault fires once; phase mismatch/invalid feature never fires; recovery clears.
- Gate: no chatter in synthetic sequence.

### Iteration 4.5 — Cue arbitration

- Read: §6.15, Appendix A.
- Build: exact five mask rules, startup identifier assertion, ranking, 5 s/3 s cooldowns, escalation, cue-effect record.
- Test: unknown feature fails startup; masker suppresses target; only one cue selected; cooldown/escalation order exact.
- Device gate: correct cue direction, one at a time, no chatter.
- User action: verify compensation-chain table with physiotherapist if available. Treat it as unvalidated clinical logic until reviewed.

## Phase 5 — Patient feedback and safety

### Iteration 5.1 — Minimal exercise UI

- Read: §12.2–12.3, §14.2.
- Build: immutable frame state, live skeleton, quality bar, rep counter. Skip ghost/arrow for now.
- Test: state reducer/UI semantics test for invalid, partial, good, fault states.
- Device gate: quality bar responds without blocking inference.

### Iteration 5.2 — TTS and haptics

- Read: §12.4–12.5.
- Build: en-IN TTS, silent warm-up, `QUEUE_FLUSH`, onset/resolution haptics.
- Test: cue dispatcher deduplicates and obeys arbitration; use fake speaker in JVM tests.
- Device gate: first real cue speaks; newer cue interrupts stale cue.
- User action: ensure TTS voice/data is installed and media volume audible.

### Iteration 5.3 — Correction arrow, then ghost

- Read: §6.16.
- Build: image-space 2D correction arrow first. Add chained ghost only after arrow signs pass.
- Test: known vector rotations and clamps.
- Device gate: determine per-feature/view rotation sign empirically; deliberately deviate each demo feature and confirm arrow points toward correction.
- User action: perform direction checks. Do not ship guessed rotation signs.

### Iteration 5.4 — Calibration and mid-set recheck

- Read: §6.20, §8.2, §14.2.
- Build: seven plan checks, view classification, 5-second view recheck, pause/recalibrate path.
- Test: each failed check blocks start with correct reason; ratio boundaries exact.
- Device gate: wrong view fails; correct sagittal/frontal view passes; phone bump pauses.

## Phase 6 — Fatigue and second mode

### Iteration 6.1 — Rep persistence model in memory

- Read: §6.17–6.18, §13.1.
- Build: minimal rep/session result types needed by CUSUM. No database yet.
- Test: valid, partial, unscored reps aggregate correctly.
- Gate: scoring output contains every CUSUM prerequisite.

### Iteration 6.2 — CUSUM and attribution

- Read: §6.18.
- Build: baseline, CUSUM, slope corroboration, per-feature attribution, safety intervention.
- Test: fewer than 6 valid reps returns “insufficient reps”; stable set does not trigger; known degrading set produces expected change point and attribution.
- Device gate: rehearsed degrading set identifies plausible onset; no medical-validity claim.

### Iteration 6.3 — Hold mode

- Read: §6.19.
- Build: single reference mean/sigma, hold breaks, drift, duration. No cyclic phase path reuse where unnecessary.
- Test: stable hold, >4 z for 1 s break, 0.15 sigma/s drift alarm.
- Device gate: wall sit authoring and live scoring pass.

### Iteration 6.4 — Validate four demo exercises

- Read: §3.3, §8, §17.
- Build: exercise-specific manifests/faults only; fix shared pipeline defects at source.
- Test/device gate: seated knee extension, standing hip abduction, wall sit, sit-to-stand each author, validate, score, cue, and finish.
- User action: supply performer, safe space, phone stand. Stop if pain occurs.

## Phase 7 — Persistence, roles, and sync

### Iteration 7.1 — Room offline source of truth

- Read: §13.1, §13.3.
- Build: only entities/DAOs needed for exercise, assignment, session, rep, fault/cue events. Save each rep immediately.
- Test: Room in-memory DAO tests; killed-session data survives completed-rep writes.
- Device gate: full patient session works in airplane mode.

### Iteration 7.2 — Minimal role navigation

- Read: §2.4, §14.
- Build: physio and patient start flows; Today, authoring, patient list/report only. Defer decorative screens.
- Test: navigation state tests; back-stack smoke test.
- Gate: both roles reach their core flow.

### Iteration 7.3 — Firebase setup and authentication

- Read: §4.1, §13, §19.
- Build: email/password sign-in and role record. Add configuration outside source control if secrets are present.
- Test: repository tests with fakes; Firebase emulator tests if configured.
- Device gate: two test accounts sign in.
- User action: create Firebase project; enable Email/Password Auth, Firestore, Storage; provide `google-services.json`. Review security rules. Do not commit secrets merely because source plan says to commit this file.

### Iteration 7.4 — Invite link and assignment sync

- Read: §0 linking requirement, §13.
- Build: six-character invite code, one-time patient linking, exercise metadata and assignment sync.
- Test: valid, invalid, expired/used or collision handling according to chosen minimal schema; repository retry behavior.
- Device gate: physio creates code, patient enters it, assignment appears.

### Iteration 7.5 — Session sync and physio report

- Read: §13–14.
- Build: WorkManager retry with exponential backoff, immutable exercise-version binding, minimal session report/change-point chart.
- Test: queued upload after offline session; duplicate retry is idempotent; session remains tied to original exercise version.
- Device gate: patient performs offline, reconnects, physio sees report.

## Phase 8 — Optional intelligence and progression

### Iteration 8.1 — Deterministic cue templates

- Read: §10.4.
- Build: mechanical template fallback first.
- Test: every fault/sign resolves to non-empty safe direction already decided by code.
- Gate: app is never silent because LLM is absent.

### Iteration 8.2 — On-device LLM cue wording

- Read: §10.1–10.5, §11.2–11.3.
- Build: authoring-time batch only, 20 s timeout, strict validator, one retry, fallback. Unload before live set.
- Test: malformed JSON, missing keys, word limits, digits, negatives, duplicates, retry/fallback.
- Device gate: benchmark load, time-to-first-token, decode speed, memory on target phone.
- User action: obtain compatible LiteRT model and accept its license/storage cost. If it cannot load reliably, retain templates.

### Iteration 8.3 — Post-set summary

- Read: §10.7.
- Build: statistics-to-prompt adapter and template fallback; model phrases facts only.
- Test: generated input contains computed facts; malformed/unsupported claims rejected or replaced by template.
- Gate: no new medical advice or fabricated measurement can enter summary.

### Iteration 8.4 — Progression flags

- Read: §6.20.3, §6.21.
- Build: session-1 baseline, last-three medians, progress, ready/review flags. Notify physio; never auto-advance.
- Test: near-zero goal guard, no-degradation sentinel, insufficient-rep exclusion, exact 2-of-3 advancement and regression rules.
- Gate: flags show supporting data.

## Phase 9 — Demo hardening

### Iteration 9.1 — Office Kit flows

- Read: §14.3, §15.
- Build: use screen mirroring/remote control for reference tuning and clinic viewing. Add no custom integration unless event tooling requires it.
- Device gate: organiser device data registers intended use.
- User action: install/configure event Office Kit and test Android Studio remote control before event.

### Iteration 9.2 — Failure-path rehearsal

- Read: §8, §18.
- Build: only fixes revealed by tests; bundle known-good immutable `.rpose` files and demo media.
- Test: no network, app kill, occlusion, second-person jump, phone bump, low FPS, TTS not ready, LLM failure, delegate fallback.
- Gate: each failure abstains, recovers, or falls back as specified.

### Iteration 9.3 — Demo freeze

- Read: §16–19.
- Build: no new features. Run unit tests, assemble APK, and run full four-minute script three consecutive times.
- Gate: three clean runs on actual charged device; backup device/build/reference available.
- User action: charge two phones; pack chargers/tripod; confirm floor space and clothing.

## Binding cuts

Do not build before all gates above pass:

- custom rep-detection ML;
- real-time LLM cues;
- hand/wrist/finger rehab;
- compound exercises;
- multi-set rest flow;
- voice commands;
- production consent/privacy/deletion/accessibility/localisation/tablet work.

Before real-patient or production use, deferred privacy, consent, deletion, accessibility, security, medical disclaimers, and clinical validation become mandatory. Hackathon prototype must not claim clinical validation.

## Per-iteration handoff template

```text
Iteration: X.Y
Done: <one outcome>
Changed: <files>
Tests: <command and pass/fail>
Device check: <pass/fail/not needed>
User action: <exact action or none>
Next: <next iteration only>
```
