# Changelog — core track

Track A of [docs/iterations/PARALLEL-TRACKS.md](docs/iterations/PARALLEL-TRACKS.md): changes behind
the seam, in `core/**`.

Kept separate from [CHANGELOG.md](CHANGELOG.md) for a mechanical reason rather than an editorial
one. Both tracks prepend their entries to the top of a changelog, which conflicts on every commit;
two files never do.

Same discipline as the main changelog: what changed, why it was decided that way, and what was
measured rather than assumed.

Timestamps are local (IST, UTC+05:30).

---

## 10.8 revised: no parking, an idle flag instead - 2026-10-03

`GhostState.WAITING` removed. The clock keeps cycling for a patient who never moves, and
`GhostTick.patientIdle` (one whole demonstrated rep with no movement, `GHOST_IDLE_CYCLES`) tells the
screen to prompt them; it clears as soon as they move, and the ghost does not jump. Asked for on
device: parking hid the demonstration from the patient who most needed it. 892 tests, 0 failures.

## 10.8: the ghost clock - 2026-10-03

`core/live/GhostClock.kt`: the one clock in the live path, so the ghost can lead instead of only
mirroring a patient who is already moving. It counts the patient in, then plays the physio's own
rep on 10.3's time axis (pauses included), rests a beat, and goes again.

- **Resync on every completed rep**, valid or rejected - without it a 10%-slow patient is most of a
  rep behind by rep ten. With it, within 15 phase on the last rep (`GhostClockTest`).
- **Engagement guard**: one whole demonstrated rep with the patient never leaving IDLE parks the
  ghost at the start pose (`WAITING`); moving resumes it.
- **Stops at set complete** and stays stopped: a ghost still demonstrating is an instruction to
  keep going past the prescription.
- Long or backwards frame gaps are not counted, so a stall does not jump the ghost reps ahead.
- `phaseLag()` for the "you are behind" line - display only, never scored (§6.8.1).

Four new `Params`, all uncalibrated. `FrameResult` is unchanged: the clock needs the set-complete
flag the UI owns, and the scoring engine is exactly what it must stay out of. 10.9 wires it.

Commands: `gradlew testDebugUnitTest` - 881 tests, 0 failures.

## 10.6 complete: the ghost crosses cameras the right way round - 2026-10-03

Device gate passed in both directions on recordings made after the rep-boundary fix below. Rear
exercise over a front recording: `same=0.05 cross=1.27`, against `0.07` for the recording's own
ghost. Front exercise over a rear recording: `same=0.08 cross=1.34`, against `0.08`. A relabelled
ghost from the other camera lands as close as one from the same camera, and on the correct limb.

## Rep boundaries sit at rest, not on the more numerous extremum - 2026-10-03

**Behaviour change, and it changes saved exercises.** `chooseBoundaryKind` (§6.7.5) picked the
extremum kind that gave more segments, with the start pose only breaking ties. A physio who starts
and ends at rest has one more end-range extremum than rest ones (the resting stretches at the edges
are not detected as extrema), so their reps were bounded at **end range**: phase 0, `thetaStart`
and the ghost's first pose all became the arm *up*. Whether that happened depended on where the
recording was cut, so two recordings of one movement could disagree by half a rep.

Found on 10.6's device gate: a rear-camera exercise placed over a front-camera recording was the
right way round (`same=0.43 cross=1.36`, against `0.05` for the recording's own ghost) but in
antiphase - "when the ghost is up, my arm is down".

Now the kind whose median extremum is closest to the recording's rest pose wins, rest being the
median of the opening and closing quarter-rep. Cost: at most one fewer detected segment.

- The synthetic generator was itself end-range bounded. `AuthoringPipelineTest` now expects 6
  detected reps (was 7) and a 4-rep reference (was 5); `SkeletonReferenceTest` uses 9 generated
  reps so its median and drop tests keep 5 usable.
- `RepNoteTest`'s overshoot case bent the knee past the **start** and read as overshoot only under
  the bug. It now extends past a 150-degree reference to 180.
- New `SegmentationTest.boundariesSitAtRestEvenWhenEndRangeHasMoreExtrema`.

**Exercises saved before this build may have their phase upside down** - re-record any whose live
set starts counting from the wrong position, and any used as a "Ghost from" source.

Commands: `gradlew testDebugUnitTest` - 866 tests, 0 failures.

## 2026-09-30 — 10.7 The ghost has the patient's limbs, at the physio's angles

**What changed.** New `core/live/GhostRetarget.kt`. After `placeGhost` fits the torso, each limb
keeps the reference's direction and takes the patient's length. A uniform torso scale put the
ghost's wrist wherever the *physio's* arm reached, which a longer-limbed patient can never touch at
the right angle — for a leading ghost, teaching the wrong movement.

**Decisions.**

- **Length ratios in 3D, drawn in 2D.** Measured in the image, a limb pointing at the camera reads
  as short and the ghost's limb would shrink with it (the reverted attempt did this). World-space
  lengths in each body's own torso lengths give a ratio; projection is linear, so stretching the
  drawn bone by it is exact. Pinned by a test with a forearm reaching at the lens.
- **Measured apart from placement, smoothed by the caller** (`medianBoneLengths`), so limbs do not
  pulse with per-frame jitter.
- **Unmeasured bones keep their fitted length relative to a moved parent**, so a foot is never left
  behind by a retargeted shin. Ratios past `GHOST_RETARGET_MAX_RATIO` (1.6, uncalibrated) are
  treated as unmeasured.

**Finding: the ghost carries no heels.** `GHOST_LANDMARKS` is derived from what features use, and
dorsiflexion uses the foot index. The first draft retargeted through the heel and seven tests failed
on it; the foot bone is now ankle → foot index, with a test that every bone end is carried.

**Device gates, from today's first streamed run.** 10.2 **passed**: spread from 0.003 at the hips to
0.082 median / 0.169 max on the moving wrist, a gradient rather than noise. 10.6 **not yet**: all
three sign-check verdicts were front camera over a front-camera source, which exercises nothing
10.6 added. MediaPipe's `tensor.cc` TFLite warning, the run's one "error", is now filtered as noise.

865 tests, 0 failures (853 before). Device gate outstanding.

## 2026-09-30 — 10.6 A ghost recorded on one camera moves the patient's same limb on the other

**What changed.** `NormalisedPose.forCamera(recordedMirrored, liveMirrored)` relabels and reflects
the reference when the recording camera and the live camera disagree. The front camera flips its
bitmap before inference, so a front-camera recording is of the physio's mirror image; shown on the
rear camera without this, the ghost would lift its other arm.

**The spec asked for a geometric check of the stored bit. There cannot be one.** A mirror body is a
valid body — 10.1 already shows a reflected, relabelled frame normalises to exactly `mirrored()` —
and since the front camera flips both image and labels, a person facing either camera shows their
labelled-right shoulder on screen-left. No per-frame measurement tells a body from its mirror image.
`manifest.mirrored` and the live camera facing are the only source, and the cross-camera device gate
is the only verification. `ChiralityTest` pins both halves: a wrong bit visibly moves the raised arm
to the other side (so the bit is load-bearing), and nothing in the geometry refuses it (so a future
"check" has to be added deliberately, not assumed).

The sign-check screen can now place a **saved** exercise's ghost over the current recording, which
is how the cross-camera gate is run.

**Also today — 10.5 complete, and the device log was blind.** The user confirmed by eye that the
ghost sits on their own skeleton, which is 10.5's gate as written. The `RPGhost` verdict lines never
reached `docs/device-run-log.md`: this phone's main log buffer is 256 KiB, ColorOS overwrites it in
minutes, and `devicelog.py stop` read it with `logcat -d` after the fact. Every "RAN CLEAN, nothing
logged" verdict since the tool existed was that, not a quiet app. `start` now launches a detached
`adb logcat` streaming the app's tags and all errors to `build/devicelog-stream.txt`, and asks for a
4 MiB buffer; `stop` ends the stream and parses the file. Self-tested: a line logged on the phone
arrives in the run log.

853 tests, 0 failures (846 before). Cross-camera device gate outstanding.

## 2026-09-30 — 10.5 The ghost is placed over a body, and a physio can check it is the right way round

**What changed.** New `core/live/GhostPlacement.kt`: `placeGhost` projects a reference pose on the
live view's plane and fits a 2D similarity carrying the reference torso onto the live one. Plus
`SkeletonReference.poseAtPhase`, `replayPhases`, and `Params.GHOST_FACING_MARGIN`. A sign-check
screen on Track B's side replays the physio's recording with the ghost over it. No patient sees a
ghost yet.

**Why a check screen at all.** The fit is exactly determined — four equations, four unknowns, zero
residual always — so it can never report being wrong, and no unit test can see a sign error on a
real phone. That is how §6.16's arrow became dead code. Here a person confirms it on their own
recording before anything is shown to a patient, and the verdict goes to the device log.

**Decisions beyond the spec.**

- **Aspect-corrected fit.** MediaPipe's x and y are fractions of different lengths; fitting in them
  directly stretches the ghost on every non-square image (the reverted attempt did). Tested at 3:4,
  9:16, 16:9.
- **The facing check came forward from 10.6.** Without it the similarity, which has no reflection,
  mirrors the ghost whenever the camera sees labelled-right on screen-left — the rear camera. Facing
  is measured across each body's own torso axis in its own torso lengths, so a tilted body is judged
  like an upright one; the reference's horizontal axis is negated when the two disagree, and
  nothing is drawn below `GHOST_FACING_MARGIN` (0.05, uncalibrated). 10.6 keeps the camera-label
  swap and the mirror-sensitivity tests.
- **Fixtures use an isotropic camera** with switches for aspect, mirroring and roll. The synthetic
  generator's own image mapping is a shear no camera has, and placement tests on it would measure
  the generator.

One bug caught in writing it: `replayPhases` let the next rep overwrite a shared boundary frame's 100
with 0 mid-assertion. Both are the rest pose; the rule is now explicit (later rep wins) and tested.

846 tests, 0 failures (830 before). **Hard device gate outstanding; 10.6 onward waits on it.**

## 2026-09-30 — 10.4 The ghost's reference is saved with the exercise

> **Track B, please read.** `.rpose` bundles now carry an optional sixth entry, `skeleton.json`.
> The format version is **still 1.1** — your builds read new bundles unchanged and simply ignore it.
> `RposeBundle` gains `skeleton: SkeletonReference? = null` as its last field; named-argument
> constructions compile as before. Sync needs no change: the bundle is still opaque bytes.

**What changed.** `RposeBundle.skeleton` is written as `skeleton.json` (10.2's poses, spread,
visibility and freshness plus 10.3's timing) and read back with shape checks. `bundleFromAuthoring`
passes the authoring result's skeleton through, so every newly authored cyclic exercise that could
support one now saves it.

**No format bump, against the 10.4 spec.** `hold.json` bumped 1.0 → 1.1 because an old reader
would have read a hold as an empty cyclic exercise. This entry has no such failure: `parseRpose`
ignores entries it does not name, so an old reader scores exactly as before. Bumping would have made
every build without this code — Track B's included, via Supabase — refuse every new exercise. A test
pins the five older entries byte-identical with and without the skeleton.

**Encoding.** Flat arrays, stride documented at `skeletonJson`, rounded to four decimals (a
twentieth of a millimetre in torso lengths). Under 120 KB, tested. Rounding is monotonic, so the
phase profile survives it still non-decreasing; a second save is exact.

**Refused on load, naming the file:** wrong landmark set, bins ≠ 101, any mis-sized array,
non-finite values, non-boolean `fresh`, backwards profile, non-positive duration. The last two come
from `PhaseTiming`'s own constructor, so build-time and load-time share one check.

Log review: the 23:38 run was clean — no crash, no app errors — and again contained no authoring
run, so the 10.2/10.3/11.x device gates remain outstanding.

830 tests, 0 failures (816 before). Device gate outstanding.

## 2026-09-29 — 10.3 The physio's timing, so a leading ghost pauses where they paused

**What changed.** `SkeletonReference` gains `timing: PhaseTiming` — phase at 101 evenly spaced
instants through the median rep's duration, plus that duration — and `phaseAt(elapsedSeconds)` to
read it.

**Why.** Phase comes from the dominant joint's value and carries no timing, by design (§6.8.1):
that is what makes scoring fair at the patient's own speed. It is also why phase alone cannot drive
a demonstration. An end-range hold is one phase bin and several seconds, so a ghost stepped
linearly through phase would pass through it in a frame. This is the only time-indexed quantity in
the reference pipeline, and it exists only because the ghost leads.

**Decisions.**

- Built from the **same reps as the poses**, inside `buildSkeletonReference`, so there is one
  median rep, not poses from one set timed by another.
- Each rep's time axis is normalised to [0, 1] **before** the pointwise median. The median of
  non-decreasing curves is then non-decreasing; frame-for-frame across unequal reps it would not be.
- `PhaseTiming`'s constructor refuses a backwards profile or a non-positive duration, so the
  load-time check the spec asks 10.4 for is already there.
- Past the end of the rep `phaseAt` holds the last sample. Wrapping is the ghost clock's job
  (10.8), the one part that knows about the rest beat.

`PhaseTimingTest` pins the gate: a 2 s end-range pause replays as ≥ 1.5 s held at the turnaround.
The device log line gains `rep=` and `endHold=` for the on-device version of the same check.

Log review, same day: the one run in `docs/device-run-log.md` since 10.2 had two `IJankManager`
"errors", which are ColorOS's scroll-jank tracker, not the app — now filtered in `devicelog.py`.
No authoring run finished in that window, so the 10.2 and 11.x device gates remain outstanding.

816 tests, 0 failures (807 before). Device gate outstanding.

## 2026-09-29 — 10.2 The physio's body is built on the phase grid, alongside their angles

**What changed.** New `core/reference/SkeletonReference.kt`. Authoring now also builds the physio's
body over one rep: per §6.8.4 phase bin, the median pose, per-landmark spread, per-landmark lowest
visibility, and whether any contributing frame had a `FRESH` body frame. It rides on
`AuthoringResult.skeleton` (defaulted, last). Nothing stores or draws it yet — 10.4 and 10.9.

**Why it is built the way it is.**

- **Same phase grid by the same rule.** `phaseProfile` and `interpolateOntoBins` (widened from
  `private` to `internal`) — so the pose at bin `p` is the pose the z-scores at bin `p` are about.
  `SkeletonReferenceTest` pins it: the generator locks the elbow to the knee, so both angles
  measured off the skeleton must lie on a straight line in `p`. A time-indexed resample bends that
  line by up to ~19°, against a 3° tolerance.
- **Filtered like the features.** Landmark channels go through §6.3.2's zero-phase Butterworth at
  the same cutoff, so spread is the physio's rep-to-rep variation, as sigma is, rather than
  MediaPipe jitter. Not in the 10.2 spec; recorded as a decision in the iteration doc.
- **A rep with a hole is dropped, not repaired.** Interpolating across a missing body frame would
  draw a movement nobody saw. Fewer than `MIN_SURVIVING_REPS` usable reps → null, and authoring
  still succeeds: scoring needs none of this.
- Median pose, minimum visibility, spread as a MAD-scaled distance — each for the reason the spec
  gives, each with a test that fails under the alternative.
- `REUSED` is provenance, not rejection. An all-`REUSED` recording still builds, with `fresh` false.

**Deliberately not here:** the time→phase profile and rep duration (10.3) and the mirrored-source
bit (10.6). The reverted first attempt put all three in one type; they are separate iterations.

**Front of the seam:** one `RPGhost` log line in `CameraPreviewScreen` for the device gate — Track
B's file, logged in `CHANGELOG.md`. `AuthoringResult` is not one of the four seam types.

807 tests, 0 failures (780 before; 12 here, 15 in 10.1). Device gate outstanding.

## 2026-09-29 — 10.1 A pose that does not care where, which way, or how big

**What changed.** New `core/pose/NormalisedPose.kt`: `NormalisedPose`, `normalisedPose(frame,
body)`, `mirrored()`, `GHOST_LANDMARKS` and its slot maps. Pure core; nothing calls it outside 10.2.

`p_body = BodyFrame.toBody(world[i] − midHip) / |midShoulder − midHip|`, from `worldLandmarks`
only.

**The subset is derived, not listed:** nose + the twelve drawn joints + every landmark any
`FeatureId` is computed from on either side, via `contributingLandmarks`. A feature added later
brings its landmarks with it. The drawn joints are restated in core rather than imported from
`pose/PoseSkeleton.kt`, because `core` must not depend on the Android package; a test pins the two
together.

**Mirroring was derived, then tested against the definition.** Reflecting the world and swapping
left/right labels flips the body frame's x axis with it, so in body coordinates a mirror is
*relabel + negate x*, with y and z untouched. `NormalisedPoseTest` checks `mirrored()` against an
actually-reflected body rather than restating the implementation, and uses a fixture with one arm
raised — a symmetric body passes every mirror test with the relabel missing.

No device gate: no Android dependency, so tests alone gate it. **Complete.**

## 2026-09-24 — 11.3 A compound exercise is scored on seven joints, not five

**What changed.** `WATCH_LIST_SIZE` 5 → 7.

**Why.** `frameScore` and pose conformance only see watch-listed features, and the dominant one's z
is ~0 by construction because phase comes from it. Five therefore meant a compound exercise was
scored on four joints at most, dropping ones it was genuinely performed with. Seven is the dominant
joint plus one per remaining major segment of a whole-body movement.

**What it does not change.** §6.10's four PASS filters still decide eligibility; this only caps the
survivors. Single-joint exercises have nowhere near seven survivors and are unaffected. The admitted
features are the least salient, so they carry the least weight — the argument for `qualityBand`'s
55, `PROGRESSION_MIN_QUALITY`, and the CUSUM thresholds all still meaning what they meant. That
argument is unverified; the device gate is a before/after quality comparison for that reason.

**Only newly authored exercises get it.** The watch list is stored in the `.rpose` at authoring, so
bundles already on phones and in Supabase keep five. No format change.

`WatchListTest`'s size tests now read the constant instead of spelling out five. Track B needs no
coordination — no seam type changed.

## 2026-09-24 — 11.2 A rep that was mostly wrong is refused even when its checkpoints passed

**What changed.** `PhaseTracker` refuses a rep as `SKIPPED` when fewer than
`TRAJECTORY_MIN_REP_CONFORMANT_FRACTION` (0.5) of its judged frames had the whole pose right.

**Why.** Checkpoints are only required as far as the rep fully crossed, so a rep peaking at φ = 0.95
never has its top bucket examined — and that is where an end-range hold sits. Travel correctly, hold
the end range out of position, and 11.1 counts it. §6.17.2 puts the therapeutic work at end range.

**Why 0.5.** It has to sit at or under 11.1's 0.6: a rep whose every bucket cleared 0.6 is at least
0.6 overall, so a lower bar cannot reject a rep that was right everywhere the checkpoints looked.
0.5 is the plain-language line — more of the rep out of position than in it. Uncalibrated (C3).

**Denominator.** Judged frames — valid and not past φ = 1, exactly what 11.1 judges — not valid
frames. Overshoot frames are unjudged, and counting them would dilute a wrong hold with frames
nobody scored. Dropout frames count neither way, per §6.6.

**Seam: `CompletedRep` gains `conformantFraction: Double = 1.0`.** Last and defaulted, so every
existing construction compiles unchanged, including Track B's `CueDispatcherTest` (not touched).
It is a shape change to a seam type, so it is flagged here; nothing in front of the seam reads it
yet.

780 tests, 0 failures (774 before, counting 11.3's new test). Device gate outstanding.

## 2026-09-21 19:40 — 11.1 A checkpoint is now a statement about the movement, not its luckiest frame

**What changed.** `PhaseTracker` credits a trajectory checkpoint when a *majority* of the frames in
it had the whole pose right, instead of when any single frame did.

**Why.** Reported from a device: an exercise involving two or more joints is barely checked. §6.12.4
accepts a rep on range, duration, checkpoints, rate and valid fraction — every one of which is a
function of φ, and φ is one joint. v5.x closed half of that by only crediting a checkpoint when the
*pose* was right there, not just the dominant angle. It credited on one frame, which answers "was
the patient ever in the right position in this quarter of the range", and a patient out of position
for three quarters of a bucket who sweeps through the right angle once on the way answers yes. The
dominant joint travels legally, the omitted joint grazes the right position in each bucket, the rep
counts.

**The threshold is 0.6, and it is sized rather than picked.** `PoseConformanceTest` has a case
pinning that one bad frame in seventeen must not lose a rep — that sits at 0.94, so 0.6 leaves ample
room for landmark jitter while refusing a joint left out through most of a bucket. `TRAJECTORY_
CHECKPOINTS` stays at 4: its own comment sizes four against the device frame rate and the fastest
rep the duration bound accepts, and finer buckets would mean fewer frames each and a noisier
fraction — trading one failure mode for another. Both constants remain uncalibrated against real
bodies, like most of `Params.kt`; that is C3.

**One thing this broke, and it is worth knowing about.** The first version refused every
*overshooting* rep as `SKIPPED`. `phaseIndex()` saturates at 50 once φ passes 1, so a patient
swinging beyond what the physio recorded is scored against the turnaround while being well past it,
and every joint that moves with the dominant one reads as a deviation it never made. That is an
artefact of the overshoot, not a second fault, and §6.12.4 plus `RepNote.Overshot` already have
something more specific and more useful to say about it. Counting it here reported one problem twice
and picked the less actionable description — the same reasoning that puts `RANGE` ahead of
everything else in `completeRep`.

Frames past φ = 1 are therefore judged neither way now: they credit no bucket and discredit none.
The line is φ = 1 exactly, because that is where the saturation starts. `REP_OVERSHOOT_TOLERANCE`
was the tempting constant to reach for and is the wrong one — it answers how far past the reference
is worth *mentioning to the patient*, which is a different question.

`RepNoteTest`'s two overshoot cases are what caught this. Without the abstention they fail, and they
are a real regression rather than tests to update.

**Shape unchanged.** `CompletedRep` gains no field, so nothing in front of the seam was touched —
the whole iteration lives behind `FrameResult`. Track B needs no coordination for this one.

774 tests, 0 failures, up from 767. Device gate outstanding: it needs a two-joint exercise performed
on a phone twice, once properly and once with the second joint left out, and unit tests cannot
stand in for that — the conformance verdict they drive comes from a lambda, not from real z-scores
off a real body.
