# Parallel tracks — core tuning alongside feature iterations

Two people, one repo, no merge conflicts. Phase 9 is out of scope here.

Conflicts are a property of **files**, not of topics, so the split below is a file-ownership map
first and an iteration list second. If both tracks respect the ownership table, the only files that
can ever conflict are the four named in "Shared files".

---

## The seam

The codebase already has a natural boundary, and the split follows it rather than inventing one:

```
                    Track A (core)                    Track B (features)
  frames ──► core/pose ──► core/reference ──► [ RposeBundle ] ──► camera, UI, Room, sync
                          core/live ────────► [ FrameResult  ]
                                              [ SessionRecord]
                                              [ FatigueResult]
```

Track A owns everything **behind** the seam. Track B owns everything **in front of** it and consumes
the four bracketed types. Those four types are the contract: changing their *shape* needs a word
between you; changing what goes *into* them does not.

---

## Ownership map

### Track A — core tuning (your teammate)

```
app/src/main/java/com/example/rightposture/core/**      ← except Phrases.kt, see below
```

That is: `Params.kt`, `FeatureId.kt`, `filter/`, `pose/`, `reference/`, `live/`, `rpose/`.

Tests for those files: `AnglesTest`, `BodyFrameTest`, `CanonicalisationTest`,
`FeatureExtractionTest`, `FeatureValidityTest`, `OneEuroFilterTest`, `ButterworthFilterTest`,
`ViewClassificationTest`, `SyntheticLandmarkGeneratorTest`, `DominantPeriodTest`,
`SegmentationTest`, `PhaseResamplingTest`, `ReferenceBuilderTest`, `ReferenceValidatorTest`,
`RobustSignalTest`, `WatchListTest`, `AuthoringPipelineTest`, `RposeTest`, `PhaseTrackerTest`,
`TrajectoryTest`, `PoseConformanceTest`, `ZScoreTest`, `ScoringTest`, `FaultDetectorTest`,
`CueArbiterTest`, `ExerciseEngineTest`, `RepNoteTest`, `SessionRecordTest`, `FatigueTest`,
`HoldModeTest`, `CalibrationTest`, `ArrowSelectorTest`, `ArrowSignDerivationTest`,
`CorrectionArrowTest`, `DomainPrimitivesTest`.

### Track B — feature iterations (you)

```
MainActivity.kt
authoring/**   camera/**   exercise/**   pose/**   sensors/**   speech/**   ui/**
core/live/Phrases.kt                        ← carve-out: patient-facing wording, not maths
app/build.gradle.kts, gradle/libs.versions.toml
```

Plus every new package: `catalog/` (built in 6.4), `data/`, `nav/`, `sync/`, `llm/`, `progress/`, `cues/`.

Tests: `CameraPermissionTest`, `PoseBackendTest`, `PoseFrameMapperTest`, `AxisConventionTest`,
`CueDispatcherTest`, `TtsStateTest`, and anything new.

> `pose/` (Android: MediaPipe, skeleton drawing) and `core/pose/` (maths: angles, body frame) are
> different packages with confusingly similar names. Track A owns the one with `core/` in front.

---

## Shared files — the only four that can conflict

| File | Rule |
|---|---|
| `core/Params.kt` | **Track A only.** Track B declares its own constants in its own packages — Room page sizes and sync backoff do not belong in a biomechanics constants object anyway. |
| `core/live/ExerciseEngine.kt` | **Track A only.** Track B reads `engine.state`, `engine.session`, `engine.fatigue` and never edits the file. Every Track B iteration below is a *consumer*; none needs to modify it. |
| `core/rpose/Rpose.kt` | **Track A owns**, but the manifest schema is the wire format between you. A field added there is a conversation, not a commit. |
| `CHANGELOG.md` | **Split it.** Both tracks prepend at the top of the file, which conflicts on every single entry. Track A writes `CHANGELOG-core.md`; Track B keeps `CHANGELOG.md`. |
| `docs/iterations/README.md` | **Split it.** Same reason — both edit the status table. Track A keeps its statuses in `docs/iterations/README-core.md`. |

`camera/CameraPreviewScreen.kt` is 893 lines and holds authoring, calibration, the cyclic set and
the hold flow in one composable. It is Track B's, but it will be the worst file to work in even
alone. Splitting it per flow is worth doing before 7.2 adds navigation on top.

---

## Track A iterations

These are not in `RightPosture_Iteration_Plan.md`; they are the outstanding core work the iteration
docs and changelog have accumulated. Numbered `C` so they cannot collide with the plan's numbering.

### C1 — Close the in-progress core iterations

Four are still marked `in-progress` and are core-owned: **1.5** feature extraction, **3.8** reference
validator, **4.1** phase tracker, **4.5** cue arbitration. (2.2 and 5.1 are also in-progress but are
Track B's — Android pose backend and the exercise UI.)

Read each doc's own Outstanding section, finish it, move the status to `complete`.

Gate: no core iteration remains `in-progress` without a written reason.

### C2 — Decide the correction arrow

`CorrectionArrow.kt`, `ArrowSelector.kt` and `ArrowSignCheck.kt` have been dead runtime code since
v5.3.5, along with `arrowSigns` in the manifest, `deriveArrowSigns` in the authoring pipeline, and
six `ARROW_*` constants. They are kept, tested, and reachable from nothing.

Either reinstate the overlay against the measured rotation signs (v5.3.4 has the wiring) or delete
the lot. Carrying a third option — tested code nothing calls — is the only outcome to avoid.

Gate: `grep -r Arrow core/` returns either a live path or nothing.

### C3 — Calibrate thresholds against real recordings

The actual fine-tuning, and the highest-value item here. Almost every constant in `Params.kt` came
from the implementation plan and has never been checked against a real body — `V_TH_PER_SECOND`,
`REP_ACCEPTANCE_PHASE_MAX`, `TRAJECTORY_CONFORMANCE_Z`, `SIGMA_FLOOR_DEGREES`, `HOLD_BREAK_Z`,
`CALIBRATION_*`. Two device bugs so far were threshold-domain errors that unit tests could not see,
because the tests supplied the same assumption the code did.

Build: a recording harness that replays captured sessions through the pipeline offline, so a
threshold change can be evaluated against a corpus instead of against one live attempt.

Test: each changed constant gets a test that fails at the old value.

Gate: every constant either has a recording that justifies it or a comment saying it is unvalidated.

### C4 — Change point over a hold

§6.19 says to run §6.18's CUSUM over `FrameScore` sampled at 1 Hz, giving *"your form started
slipping at 0:38"*. `HoldUpdate.qualitySamples` already carries that series and nothing reads it.

Needs `assessFatigue` generalised past `SessionRecord` to any indexed quality series.

Test: a hold that degrades at a known second produces that second.

### C5 — Per-patient reference offset

The reference is the **physio's** body. A patient with different proportions sits at a systematic
offset on every joint, and nothing currently corrects for it. Probed on the cyclic path this is
tolerable at ~3σ; in hold mode it may stop the tracker ever leaving `SETTLING`.

§6.20.3's session-1 baseline capture is the plan's hook for this and is not built. Note the tension
with 8.4, which also reads that baseline — settle the storage shape with Track B before building.

### C6 — Review MASK_RULES

`v4.5-cues.md` flags the §6.15.1 masking table as **unvalidated clinical logic**: transcribed and
pinned by a test, but never reviewed by a physiotherapist. Compensation-chain correctness cannot be
claimed until that conversation happens. This is a blocker to resolve, not code to write.

---

## Track B iterations

Straight from the plan, minus phase 9: **6.4** (validation half), **7.1**–**7.5**, **8.1**–**8.4**.

Two need care at the boundary:

- **6.4** says "fix shared pipeline defects at source". Validation is yours; any core defect it
  turns up is a ticket for Track A, not an edit you make.
- **8.1** deterministic cue templates is generated *text*, so build it in a new `cues/` package and
  keep it out of `CueArbiter.kt`. If the template needs a field the fault schema lacks, that is a
  `Rpose.kt` change and therefore a conversation.
- **8.4** progression is §6.21 maths but operates on stored sessions, so it belongs in `progress/`
  next to Room rather than in `core/`.

---

## Mechanics

`origin/main` is currently **behind local main by several commits**. Push before anyone branches, or
the teammate starts from a tree that does not have hold mode in it.

```bash
git push origin main
```

Then:

- Track A branches `core/<topic>`, Track B branches `feature/<iteration>`.
- Core is upstream of everything, so **Track A merges to main often** — small, frequent, green.
  Track B rebases onto main rather than merging, so its branch history stays linear over a moving
  core.
- Both keep the existing rule: the suite is green before any merge. It is 517 tests and runs in
  about two minutes, which is cheap enough that there is no excuse.

Run only your own half while iterating:

```bash
./gradlew testDebugUnitTest --tests "com.example.rightposture.HoldModeTest"
```

## What still needs a decision from you

- **Whether Track A can change behaviour or only tune it.** C3 will change what counts as a rep. If
  Track B is demoing against a fixed reference, a threshold change under it is a surprise. Agree
  whether core lands freely or behind a flag.
- **Who owns `.rpose` versioning.** Both tracks can force a bundle rewrite; the format is versioned
  but nothing yet migrates.
