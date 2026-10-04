# Core track iteration index

Track A of [PARALLEL-TRACKS.md](PARALLEL-TRACKS.md). Statuses live here rather than in
[README.md](README.md) so the two tracks never edit the same table.

Status values: `planned`, `in-progress`, `complete`, `blocked`.

| Version | Iteration | Status |
|---|---|---|
| C1 | Close the in-progress core iterations (1.5, 3.8, 4.1, 4.5) | planned |
| C2 | Decide the correction arrow | planned |
| C3 | Calibrate thresholds against real recordings | planned |
| C4 | Change point over a hold | planned |
| C5 | Per-patient reference offset | planned |
| C6 | Review MASK_RULES | blocked |
| 10.1 | [Normalised pose](v10.1-normalised-pose.md) | complete |
| 10.2 | [Skeleton reference builder](v10.2-skeleton-reference.md) | complete |
| 10.3 | [Time to phase profile](v10.3-time-phase-profile.md) | in-progress |
| 10.4 | [skeleton.json (shipped at format 1.1)](v10.4-rpose-skeleton.md) | in-progress |
| 10.5 | [Ghost placement and sign check](v10.5-ghost-placement.md) | complete |
| 10.6 | [Chirality across cameras](v10.6-chirality.md) | complete |
| 10.7 | [Bone-length retargeting](v10.7-bone-retargeting.md) | in-progress |
| 10.8 | [Ghost clock](v10.8-ghost-clock.md) | in-progress |
| 11.1 | [Checkpoint crediting by majority](v11.1-bucket-conformance.md) | in-progress |
| 11.2 | [Whole-rep conformance gate](v11.2-rep-conformance.md) | in-progress |
| 11.3 | [Watch-list size for compound exercises](v11.3-watch-list-size.md) | in-progress |

C6 is `blocked`, not `planned`: it needs a physiotherapist, not an implementation.

The 10.x and 11.x docs are numbered in the main track's sequence because they are product
iterations, not core-tuning items like C1–C6 — but their files are all behind the seam, so their
statuses are tracked here. The two exceptions are noted in [README.md](README.md): 10.9 is Track B's
entirely, and 10.5 needs a small Track B screen to display what it gates on.

10.4 touches `core/rpose/Rpose.kt`, which PARALLEL-TRACKS calls "a conversation, not a commit". It
adds an optional `skeleton.json` entry and deliberately does **not** bump the format version, so a
Track B build without it still reads every new bundle (it just shows no ghost). Track B should still
hear about the new entry — see the 10.4 entry in `CHANGELOG-core.md`.

## Completion rule

Unchanged from the main track. Update status only after the listed automated tests and the device
gate pass, and record the exact commands and results in the iteration's own section.
