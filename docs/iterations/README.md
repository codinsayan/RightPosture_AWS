# RightPosture iteration index

Each file is one independently gated implementation version. Detailed requirements remain in `../../RightPosture_Iteration_Plan.md`; mathematical truth remains in `../../RightPosture_Implementation_Plan (3).md`.

Status values: `planned`, `in-progress`, `complete`, `blocked`.

Work is split across two tracks — see [PARALLEL-TRACKS.md](PARALLEL-TRACKS.md). This table is the
**feature track**; core-tuning statuses live in [README-core.md](README-core.md).

| Version | Iteration | Status |
|---|---|---|
| 0.1 | [Make baseline repeatable](v0.1-baseline.md) | complete |
| 0.2 | [Lock external choices](v0.2-pose-runtime.md) | complete |
| 0.3 | [Empirical axis convention](v0.3-axis-convention.md) | complete |
| 1.1 | [Domain primitives and parameters](v1.1-domain-params.md) | complete |
| 1.2 | [Canonicalisation](v1.2-canonicalisation.md) | complete |
| 1.3 | [Stable angle math](v1.3-angle-math.md) | complete |
| 1.4 | [Body frame](v1.4-body-frame.md) | complete |
| 1.5 | [Feature extraction](v1.5-feature-extraction.md) | in-progress |
| 1.6 | [Validity and abstention](v1.6-validity.md) | complete |
| 2.1 | [Camera preview](v2.1-camera-preview.md) | complete |
| 2.2 | [Pose inference and skeleton](v2.2-pose-inference.md) | in-progress |
| 2.3 | [One Euro filter](v2.3-one-euro.md) | complete |
| 3.1 | [Synthetic landmark generator](v3.1-synthetic-landmarks.md) | complete |
| 3.2 | [Offline Butterworth](v3.2-butterworth.md) | complete |
| 3.3 | [Dominant feature and period](v3.3-period.md) | complete |
| 3.4 | [Extrema and rep segments](v3.4-segmentation.md) | complete |
| 3.5 | [Phase and resampling](v3.5-resampling.md) | complete |
| 3.6 | [Robust reference builder](v3.6-reference-builder.md) | complete |
| 3.7 | [Watch list](v3.7-watch-list.md) | complete |
| 3.8 | [Reference validator and review](v3.8-validator.md) | in-progress |
| 3.9 | [.rpose read/write](v3.9-rpose.md) | complete |
| 4.1 | [Phase tracker](v4.1-phase-tracker.md) | in-progress |
| 4.2 | [Z-score lookup](v4.2-z-score.md) | complete |
| 4.3 | [Frame and rep scoring](v4.3-scoring.md) | complete |
| 4.4 | [Fault state machine](v4.4-faults.md) | complete |
| 4.5 | [Cue arbitration](v4.5-cues.md) | in-progress |
| 5.1 | [Minimal exercise UI](v5.1-exercise-ui.md) | in-progress |
| 5.2 | [TTS and haptics](v5.2-tts-haptics.md) | planned |
| 5.3 | [Correction arrow and ghost](v5.3-overlay.md) | planned |
| 5.4 | [Calibration](v5.4-calibration.md) | complete |
| 6.1 | [Rep persistence model](v6.1-rep-model.md) | complete |
| 6.2 | [CUSUM and attribution](v6.2-cusum.md) | complete |
| 6.3 | [Hold mode](v6.3-hold.md) | complete |
| 6.4 | [Four demo exercises](v6.4-demo-exercises.md) | in-progress |
| 7.1 | [Room offline source of truth](v7.1-room.md) | in-progress |
| 7.2 | [Minimal role navigation](v7.2-role-navigation.md) | in-progress |
| 7.3 | [Supabase authentication](v7.3-supabase-auth.md) | in-progress |
| 7.4 | [Invite and assignment sync](v7.4-invite-sync.md) | in-progress |
| 7.5 | [Session sync and report](v7.5-session-sync.md) | planned |
| 8.1 | [Deterministic cue templates](v8.1-cue-templates.md) | planned |
| 8.2 | [On-device LLM cues](v8.2-llm-cues.md) | planned |
| 8.3 | [Post-set summary](v8.3-summary.md) | in-progress |
| 8.4 | [Progression flags](v8.4-progression.md) | in-progress |
| 9.1 | [Office Kit flows](v9.1-office-kit.md) | planned |
| 9.2 | [Failure-path rehearsal](v9.2-failure-rehearsal.md) | planned |
| 9.3 | [Demo freeze](v9.3-demo-freeze.md) | planned |
| 10.9 | [Ghost overlay in the live set](v10.9-ghost-overlay.md) | in-progress |

## 10.x and 11.x

Two series added after a first attempt built the whole thing as one change and could not be gated on
a device. **Almost all of both is Track A**, so their statuses live in
[README-core.md](README-core.md) — only 10.9 is in this table, because it is the only one whose
files (`camera/`, `exercise/`) are Track B's. 10.5 also needs a small Track B screen; see its doc.

**10.x — the leading ghost.** The physio's body, animated at the physio's own tempo, drawn over the
patient's so they can follow it. Strictly ordered, and **10.5 is a hard gate**: nothing after it
should be built until a human has confirmed on a device that the placement is not mirrored. §6.16's
correction arrow is dead code today for exactly the failure 10.5 exists to catch.

**11.x — trajectory-aware rep acceptance.** Closes the compound-exercise hole in §6.12.4, where a
rep is accepted on a dominant angle plus four loosely-credited checkpoints. Needs no part of 10.x
and can ship on its own.

## Running a device gate

Do not read logcat live — it is minutes of output for a handful of useful lines. Capture it:

```
python tools/devicelog.py start                    # mark where this run begins
  ... perform the gate on the phone ...
python tools/devicelog.py stop --note "10.5 front camera, sagittal"
```

`stop` appends one dated block to `docs/device-run-log.md` headed by a **VERDICT** line, and prints
only that line. Add `--show` to also print the problem lines. The detail stays on disk, searchable
when something actually needs diagnosing.

It reads the app's own `RP*` tags plus any error from the app's process, denoises the vendor chatter
this phone emits constantly, and takes the crash verdict from the dedicated crash buffer rather than
guessing from tag names. New subsystems should log under a tag registered in `TAGS` — `RPGhost` and
`RPRep` are already reserved for 10.x and 11.x, and a gate is much easier to judge when the numbers
it turns on are in the log rather than only on screen.

Two quirks of the current test phone (CPH2613, ColorOS), both handled: `logcat -c` sometimes hangs
and takes the adb connection down with it, so every call is short-timeout and the tool falls back to
timestamp filtering; and a killed read can leave the device `offline`, which needs a physical
replug.

## Completion rule

Update status only after listed automated tests and device gate pass. Record exact commands and results in file's Result section.
