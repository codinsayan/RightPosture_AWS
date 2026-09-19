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

C6 is `blocked`, not `planned`: it needs a physiotherapist, not an implementation.

## Completion rule

Unchanged from the main track. Update status only after the listed automated tests and the device
gate pass, and record the exact commands and results in the iteration's own section.
