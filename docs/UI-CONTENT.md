# RightPosture — UI Content Reference

Everything the app puts in front of a user: every screen, every label, every number and where it
comes from, every state a screen can be in, and the full text of every coaching phrase the app
speaks or writes. Meant as the base for a design pass — nothing here is aspirational, all of it is
what the current build actually shows.

Two roles use this app: **Physio** and **Patient**. Screens are marked accordingly.

---

## 1. Global conventions

The current app has no design system beyond these conventions — a redesign can keep or discard any
of them, but they're listed because they're what "looks native to the app" means today.

**Layout.** Every screen is `Scaffold { Column(fillMaxSize + padding(20.dp), verticalScroll,
Arrangement.spacedBy(12.dp)) }` (24.dp padding + 16.dp spacing on the three "arrival" screens: role
picker, sign-in, permission gate, redeem-invite). No `LazyColumn` anywhere — every list is a plain
`forEach` in a scrolling column. No `Card`, `Surface`, `TopAppBar`, or `Icon` anywhere in the app.

**Row shape.** A tappable row is a full-width `Button`/`OutlinedButton`/`TextButton` wrapping a
`Row(SpaceBetween)` or `Column`. A non-tappable info row is a bare `Column` with
`padding(vertical = 4.dp)`.

**Typography.** `headlineSmall`/`headlineMedium` for screen titles, `titleMedium`/`titleSmall` for
section headers and row titles, `bodyMedium` for primary text, `bodySmall` + muted color for
secondary/metadata text. `displaySmall`/`displayLarge` for the two "read across the room" numbers:
the invite code and the hold timer.

**Empty states.** Always a `bodySmall`/`bodyMedium` sentence in muted color telling the user what to
do next — never a blank list.

**Colors** (hex, all file-local `private val` constants — none are theme colors):

| Name | Hex | Used for |
|---|---|---|
| Muted / secondary text | `#6B6B6B` | metadata lines, empty states, disabled-feeling text |
| Warn / notice | `#E08A00` | partial reps, unfinished sessions, non-fatal notices |
| Bad / danger / fail | `#C62828` | rejections, safety stop, errors, drift-away on charts |
| Good / pass / success | `#2E7D32` | success states, drift-back on charts, "ready" |
| Unknown (two variants) | `#9E6A00` (authoring) / `#9E9E9E` (live/calibration) | not-yet-evaluated, pending checklist items |
| Trace (chart line) | `#1565C0` | chart lines, highlighted numerals in LLM summary |
| Band fill | `#332E7D32` (33% alpha green) | ±3σ reference band |
| Chart ground | `#11000000` (7% black) | canvas background wash |
| Hold tag wash / ink | `#E3EEF6` / `#1B4F72` | "Hold" category pill |
| Cyclic tag wash / ink | `#E7F0E8` / `#2E6B3A` | "Exercise" category pill |

**Quality color bands** (`qualityBand()`, used for the live quality bar and rep dots):

| Quality | Band | Color |
|---|---|---|
| null (not scored) | UNKNOWN | grey |
| ≥ 80 | SUCCESS | green |
| 55–79 | WARNING | amber |
| < 55 | DANGER | red |

**The one rule that shapes every number in this app:** an absent measurement is drawn as a **gap**,
never a zero. "Not scored" and "scored 0" are treated as different claims everywhere — the quality
bar, the rep list, both charts, the progression verdict, and the LLM summary.

---

## 2. Screen inventory

| Screen | Role | Reached from |
|---|---|---|
| Role picker | both | first launch, no account configured |
| Sign in / sign up | both | first launch, account configured |
| Permission gate | both | first entry to camera |
| Physio home | Physio | sign-in / role pick |
| Patient today | Patient | sign-in / role pick |
| Record reference (calibration → recording → review) | Physio | "Record a new exercise" |
| Hold reference (neutral → position → recording → review) | Physio | "Record hold" |
| Reference review | Physio | after either recording flow |
| Invite a patient | Physio | "Invite a patient" |
| Enter your code | Patient | "Enter an invite code" |
| Calibration (live) | both | before any performed set |
| Live exercise (cyclic) | both | starting a cyclic exercise |
| Live hold | both | starting a hold |
| Session report | both | tapping a past session |
| Progress (analytics) | both | "Progress over time" / "My progress" |

---

## 3. Role picker

> **RightPosture**
> Who is using this phone?

- Button: **"I'm the physiotherapist"** — sub-line: *"Record an exercise once. The app learns the target from how you do it."*
- Button: **"I'm the patient"** — sub-line: *"Do today's exercises with live coaching."*

---

## 4. Sign in / sign up

> **RightPosture**
> **Sign in** (or **Create an account**, toggled)

- Email field, Password field (masked).
- If creating an account: **"I am the"** + two role choices, **Physiotherapist** / **Patient**.
- Error line (red) when present.
- Primary button: **"Sign in"** / **"Create account"** / **"Working…"** while busy.
- Toggle link: **"Create an account"** / **"I already have an account"**.
- **"Continue without an account"** — sub-line: *"Sessions stay on this phone until you sign in."*
- Divider, then **"Demo accounts"** (amber) + explanatory sentence, then two buttons:
  - **Physio** → fills `test@gmail.com` / `123test`
  - **Patient** → fills `patient@gmail.com` / `123patient`

---

## 5. Permission gate

> **Camera access**

Body text varies by state:
- Not yet asked: *"RightPosture needs the camera to see your movement."*
- Rationale needed: *"RightPosture watches your movement to coach your form. Nothing leaves the phone."*
- Blocked: *"Camera access is turned off for RightPosture. Enable it in system settings to continue."*

Button: **"Allow camera"** or **"Open settings"**, depending on state.

---

## 6. Physio home

> **Your patients' sets** *(only if any remote sessions exist)*
Each row (`RemoteSessionRow`): exercise name (or "Unnamed exercise") · **"N of M reps"** · **"score
NN"** or **"not scored"** · **"interrupted"** if unfinished (amber) · **"Form started slipping at
rep N"** (amber) if a degradation point was found. Not tappable.

> **Recent sets** *(heading reads "On this phone" instead, if remote sessions exist above)*
Empty: *"No sets performed yet."*
Each row (`SessionRow`, tappable → Session report): exercise name · date (`d MMM, HH:mm`) ·
**"unfinished"** (amber) if not ended · **"score NN"** if scored.

> **Progression rows** (`ProgressionRow`, one per exercise with enough history), each showing:
- Headline: `<exercise name> - <needs review | ready to progress | continuing>`, colored red / green
  / muted respectively.
- Bulleted (`· `) reasons, muted — the exact sentences are listed in §11.
- If ready to progress: *"Your call - the app never advances anyone on its own."*

> Button: **"Progress over time"** → Analytics screen.

> **Your exercises**
Empty: *"Nothing recorded yet. Record one and it becomes the reference every session is scored against."*
Each row: exercise name + `CategoryTag` ("Exercise"/"Hold" pill) · **"vN · recorded <date>"** ·
**"Test"** label on the right. Tapping tests the reference.

Button: **"Record a new exercise"**.
Button (if backend configured): **"Invite a patient"**.

> **Linked patients** *(only if backend configured)*
Empty: *"Nobody linked yet. Invite a patient and read them the code."*
Otherwise: each patient's name or email, one per line.

Link: **"Switch role"**.

---

## 7. Patient today

> **Today**
Sub-line (if linked): *"Prescribed by \<physio name\>"*.
Empty: *"Nothing to do yet. Enter the code your physiotherapist gave you and your exercises will
appear here."* (if linkable) or *"...Your physiotherapist records an exercise on this phone first,
and it will appear here."* (if not).

Each row (`TodayRow`, tappable → calibration → exercise): `CategoryTag` + **`<name> · <prescription
line>`** or just the name. **"Start"** label on the right.

Prescription line format (`prescriptionLine`): `"3 × 10"` for a cyclic exercise, `"3 × 30s hold"`
for a hold with a known duration, `"3 × hold"` if the duration is unknown.

If local (unprescribed) items exist alongside prescribed ones, a **"On this phone"** sub-heading
separates them.

Button (if linkable and unlinked): **"Enter an invite code"**.
Button: **"My progress"** → Analytics screen.
Link: **"Switch role"**.

---

## 8. Invite a patient (Physio)

> **Invite a patient**

No code yet: *"Generate a code and read it out. Your patient enters it once on their own phone, and
everything you prescribe reaches them from then on."*

Code minted: shown **letter-spaced, monospace, huge** (`displaySmall`) — e.g. `A B C 1 2 3` — because
it's read aloud across a desk. Sub-line: **"Valid until \<expiry\>"**.

Button: **"Generate a code"** / **"Generate another"**.
Spinner while busy. Error (red) / success message (green) if present.

> **Your patients**
Empty: *"Nobody has entered a code yet."* If patients exist but no exercises: *"Record an exercise
before you can prescribe one."*

Per patient: name or email, then one row per exercise — **"\<name\> vN"** left, **"Assign"** right.

Links: **"Refresh"**, **"Back"**.

---

## 9. Enter your code (Patient)

> **Enter your code**
> *"Your physiotherapist will give you a six-character code."*

Text field: **"Invite code"**.
Button: **"Link me"**.
Spinner while busy. Error (red) if present.
Link: **"Skip for now"**.

---

## 10. Record reference — physio authoring (cyclic path)

Header row: **"Record reference"** left, live countdown timer (`M:SS`, turns red under 10s) right,
while recording.

Sub-line: **"Calibration"** / **"Exercise"** / **"Front camera"** / **"Rear camera"**, depending on
stage.

Live camera preview with a green skeleton overlay drawn over tracked landmarks.

Exercise picker (before recording starts): **"Which exercise?"** + up to 4 buttons (2 per row) from
the demo catalog (§15), the selected one highlighted. Selecting one shows its `setup` text and its
`safety` text (amber).

Recording button: **"Record 8 reps"** / **"Record 8 reps of \<exercise name, lowercase\>"** /
**"Cancel recording"** / **"Analysing…"**. Disabled while the exercise is a Hold.

While recording, not yet in a recognized exercise phase: *"Finding a valid pattern — stops on
acceptance or at 1:00."* + button **"Stop and review"**.

While in a live rep-scoring phase mid-authoring: the same `ExercisePanel` used in real exercises
(§12) renders.

Bottom controls (always visible outside calibration/hold): **"Switch camera"**, **"Record hold (wall
sit, plank)"** (disabled once a cyclic recording exists), **"Voice: English"** / **"Voice: हिन्दी"**
(toggles TTS language).

Status line at the bottom, always: `"<model>/<delegate> · <resolution> · <fps> fps · <inference ms>
ms"`, or an error string in its place.

While in a live exercise phase: an extra debug line `"<phase state> · φ <phi 2dp> · v
<velocity, signed 2dp>/s · p <phase 0dp>"`.

TTS unavailable notice (amber), when applicable: *"Spoken cues are off. Install \"Speech Recognition
& Synthesis\" by Google from Play, then reopen RightPosture."* or *"...install the voice data for
the language you picked..."*.

---

## 11. Record reference — hold path

**Neutral capture** stage: *"Stand naturally and still."* + *"Capturing your neutral standing pose —
\<n\> s"*.

**Positioning** stage:
- Counting down: huge countdown number + *"Get into the hold."*
- Countdown zero, not yet recording: *"Now get into the hold."* + *"The hold records for \<n\> s once
  the countdown ends."* + optionally a red warning if the frame rate can't support the fixed
  recording length + *"Pick how long you need to get into position."* + three buttons **"3s" / "5s" /
  "10s"**.

**Recording** stage: huge remaining-seconds number + *"Hold it — \<elapsed\> of \<total\> s"*. If
tracking is lost: red *"Lost sight of you — get back into the hold. It restarts if this
continues."*; otherwise *"Recording stops on its own. Stepping out restarts it from zero."* Button:
**"Stop now"**.

**Review** stage — **"Hold reference"** + exercise name:
- Rejected: red failure message (or *"That recording could not be used."*).
- Accepted: **"Main joint: \<joint name\>"**, **"Steady for N seconds"**, **"Watching: \<joint,
  joint, ...\>"**, then per watched joint: **"  \<joint\>  N° ± N.N°"**.
- Catalog warnings (amber), if the recording doesn't match the chosen exercise's expectations.
- Button: **"Re-record"**.
- After saving: green *"Saved as version N. Your patients can be prescribed this hold."*
- Button: **"Save hold"** (opens the `SaveExerciseDialog`, §14) if not yet saved.
- Button: **"Start hold"** — tries the reference live.

**Live hold performance** (`HoldPanel`, both roles) — see §13.

---

## 12. Live exercise (cyclic) — `ExercisePanel`

The quality bar is the single largest, only-color-changing element on the screen (24dp tall, full
width), filled proportional to quality 0–100 and colored by `qualityBand` (§1). **Empty (undrawn)
when quality is null** — not a zero-width bar.

Below it, a row: **"\<validReps\> / \<prescribedReps\>"** (headline size, left) and **"Set NN"** /
**"Set —"** (right, em-dash when nothing scored yet).

If reps didn't count: **"N didn't count"** (amber) + the specific reason (one of the rejection
phrases in §17, or the "you left out the \<joint\>" variant).

Safety stop (§18.6), shown only at `SET_COMPLETE` with fatigue triggered, in the danger color:
*"Stop the set here. Your form has dropped well below how you started — rest before you do more."*
+ *"Form started dropping at rep N."*

A counted-but-notable rep note, one of: *"That counted — but you went past the target. Stop where
the movement ends."* / *"That counted — but go a little further next time."* / *"That counted — but
keep your \<joint\> still."*

Status line (always): one of *"Step into frame."* / *"I can't see you clearly — check the
framing."* / *"Ready when you are."* / *"Tracking."* / *"Set complete — that's all of them. You can
stop."*

Live cue text (from the physio-authored fault definitions, not app copy), shown in red when active.

Button: **"Finish set"** / **"Done"** (label changes once the set is complete).

---

## 13. Live hold — `HoldPanel`

Big clock, `M:SS`, green while holding / red once broken.

Status line, one of:
- *"Get back into the position — the timer restarts from zero when you are in it."* (re-settling
  after a break)
- *"Get into the position — the timer starts when you are in it."* (first attempt)
- *"Held well NN% of the time"* (quality known)
- *"Getting a reading…"* (quality not yet known)

On break: *"Hold finished — N seconds."* (title size, red) + if awaiting restart: *"Get back into
the recorded position and the timer starts again from zero."* (amber). If drifting instead of
broken: *"You're slipping — bring your \<joint\> back."* (amber).

History line (once at least one attempt exists): **"Attempt N · best Ns"** while awaiting restart,
or **"Attempt N+1 · last Ns · best Ns"** while live.

**"Watching: \<joint, joint, ...\>"**.
**"drift +N.NN σ/s"** (signed) if measurable.

Button: **"Stop hold"** / **"Done"**.

---

## 14. Calibration checklist — `CalibrationPanel`

Title: **"Ready — starting"** or **"Getting ready — follow the checklist."**

Seven checks, each shown the whole time (not just failures), tick (✓, green) or circle (○, grey):
1. "I can see the joints I need to watch"
2. "You're the right distance from the camera"
3. "You're centred in the frame"
4. "The camera is angled correctly for this exercise"
5. "The phone is upright"
6. "The lighting is good enough"
7. "You're holding still"

If any are failing: *"Start anyway skips: \<comma-separated failing checks\>"*.

Debug line: `"view <detected|unknown> (need <required>)"` + optionally `" · ratio N.NN"` and/or `"
· v N.NNN/s"`.

Buttons: **"Cancel"** / **"Continue"** or **"Start anyway"**.

---

## 15. Reference review (`ReferenceReviewScreen`, physio)

> **Reference review**

Verdict banner (colored background wash): **"Rejected"** (red) with the failure message, or
**"Accepted"** (green) with **"N clean reps, N joints watched."**

Advisory catalog warnings (amber), if any.

**"Dominant joint: \<name\>"** + trace chart with rep boundaries marked (see §16 for chart idiom) +
caption *"Trace with rep boundaries. Check the red lines land where each rep really starts."*

**"Reference band (mean ± 3σ)"** + band chart + caption *"One averaged rep, 0 to 100% through the
movement."*

**Validator table**: each check as `PASS`/`FAIL`/`SKIP` (monospace, colored) + `"N. <name> —
<detail>"` + a message line if present.

**Watch list table**: `"<featureId> — salience N.N, weight NN%"` per watched joint; a **"Not
watched"** sub-section listing rejected features with their reason.

Buttons: **"Use this - start exercise"** (only if accepted, label overridable by caller) and always
**"Looks wrong — re-record"**.

---

## 16. Save exercise dialog

Modal, title **"Save this exercise"**.

Body: *"This name is what your patients will see on their Today screen."* + a text field
(**"Exercise name"**, pre-filled with a suggested name).

If the typed name already has a saved version: amber notice — *"You already have an exercise called
this. Saving publishes version N+1. Sessions already recorded against version N are unaffected."*

Buttons: **"Save"** / **"Saving…"**, **"Cancel"**.

---

## 17. Session report

> **Session**

- If the session no longer exists on device: *"That session is no longer on this device."*
- Else:
  - LLM summary paragraph (`bodyLarge`) with every numeral in it bold + colored (§19), or *"Writing
    your summary…"* while generating.
  - Attribution line under it: **"Written on this phone by Gemma on the \<NPU|GPU|CPU\>"** or
    **"Summary from measurements only."**
  - Exercise name (or the raw id / "Unnamed exercise" as a fallback).
  - **"N of M reps counted"**.
  - **"Set score NN"** (only if a score exists).
  - **"Form started slipping at rep N"** (amber), only if a degradation point was found.
  - **"This set was interrupted. The reps below were still saved."** (amber), only if the session
    never ended.
  - **"Quality per rep"** + the quality-per-rep chart (§18) + caption: *"0 to 100, with the pass mark
    drawn across. Gaps are reps we could not score\<; the red line is where form started
    slipping\>."*
  - **"What changed"** section (omitted entirely if there's no change point or no reportable shift):
    joint-shift bars (§18) + one line per joint — **"· \<joint\> drifted further from the target"**
    or **"· \<joint\> moved back toward the target"**, appending *" (too few frames to judge)"* at
    reduced opacity when the shift rests on too few frames.
  - **"Every rep"** section, one line per rep (§20 has the exact rules):
    - Headline, e.g. `"Rep 3 · 94"`, `"Rep 3 · scored 94 · not counted"`, `"Rep 3 · not scored · not
      counted"` — amber when not counted.
    - A muted detail sentence underneath explaining *why*, when relevant (§20).
- Button: **"Back"**.

---

## 18. Progress / analytics screen

> **Progress**

**Picker** (no exercise chosen yet): a button per exercise that has any recorded sessions —
**"\<name\>"** + **"N set(s)"**. Empty: *"No sets recorded yet. Finish an exercise and your progress
appears here."*

**Exercise detail** (once one is picked):
- Exercise name.
- **"N set(s)\<, M with too few reps to judge\>"**.
- If nothing is judgeable yet: *"None of these sets has enough completed reps to chart yet. Six is
  the minimum."*
- Else:
  - **"Quality per set"** + trend chart (fixed 0–100 scale) + caption *"0 to 100. Hollow marks are
    sets we could not judge."*
  - **"Range per set"** + trend chart (data-derived scale) + caption *"Your best three reps in each
    set, in degrees."*
- Verdict headline: **"Needs review"** (red) / **"Ready to progress"** (green) / **"Keep going"**
  (muted).
- Bulleted reasons (exact sentences in §11).
- Always: *"Your physiotherapist decides when to move you on."*
- Button: **"Back"**.

Identical content is shown to both roles today (a design decision the project owner made knowingly —
see §11 for what that trades away).

---

## 19. Chart specification

Three hand-drawn (no library) canvas charts, all 160dp tall, drawn over a 7%-black background:

**Quality-per-rep curve** (session report). Y axis **fixed 0–100** (never rescaled to data — a flat
90 must not look like a collapse). X axis is `repIndex`, so a rejected rep leaves a real gap in x,
not a compressed one. A faint horizontal line marks the quality pass bar (80). A vertical red line
marks the degradation onset rep, if any. Points: filled dot if the rep counted, nothing at all if
unscored. Lines are drawn only between adjacent scored points — never across a gap.

**Trend curve** (analytics — quality and range-per-set). X axis is session index (oldest left). Y
axis is fixed 0–100 for quality, or data-derived (min/max with a small floor) for range. A session
excluded from judgement (fewer than 6 valid reps) draws as a **hollow muted ring at the baseline**,
not a missing point and not a zero.

**Joint-shift bars** (session report, "What changed"). One horizontal bar per joint that shifted
meaningfully after the degradation point. Bar length ∝ magnitude of shift. Color: red = drifted
*away* from the reference, green = moved *back toward* it. Bars from a shift measured on too few
frames render at 40% opacity rather than being hidden.

**Reference charts** (physio authoring only): a dominant-feature trace with vertical rep-boundary
markers, and a mean ± 3σ band chart (filled band + solid mean line).

---

## 20. Rep-detail phrasing rules

A rep's **quality score** and whether it **counted** are independent facts — a rep can score 94 and
still not count (it matched the reference frame-by-frame but skipped to the end position without
the movement in between). The report states both, never conflates them:

| quality | counted | headline | detail |
|---|---|---|---|
| 94 | yes | `Rep 3 · 94` | (none) |
| 94 | no | `Rep 3 · scored 94 · not counted` | "It scored well frame by frame, but \<reason\> — so it does not count toward your total." |
| null | no | `Rep 3 · not scored · not counted` | "It did not count because \<reason\>." |
| null | yes | `Rep 3 · counted, not scored` | "It counted, but no frame in it could be scored." (should not occur today) |

Not-counted reasons, in report (past-tense) wording:

| Rejection | Report phrasing |
|---|---|
| Range not reached | "it didn't reach the full range" |
| Too fast | "it was faster than the movement allows" |
| Too slow | "it was slower than the movement allows" |
| Lost sight of patient | "the camera lost sight of you" |
| Nothing scoreable | "no frame in it could be scored" |
| Skipped to end pose | "it jumped to the end instead of following the whole movement" |

---

## 21. The LLM-written summary — what it may and may not say

Generated after every finished set, from numbers alone — never from the patient's identity, the
physio's identity, or the device. 3–4 sentences, encouraging but honest, one concrete next step.

**Every number that can appear** is one of: reps completed, reps prescribed, median quality (and the
literal "100" as its scale), the degradation-onset rep number, ΔROM/ΔTempo as signed whole percents,
and the count of reps that didn't count. **Nothing else.** No decimals ever appear — every quantity
is pre-rounded to a whole number specifically so it can be verified against this whitelist.

Numerals in the accepted text are shown **bold and colored** (the trace color) in the UI — this
highlight is only trustworthy because every digit that reaches the screen already passed the
whitelist check.

**Never appears:** a diagnosis word (e.g. "sprain", "tendinitis", "arthritis"), a treatment
suggestion (e.g. "ibuprofen", "physiotherapy", "massage"), unsafe advice (e.g. "push through the
pain", "add weight", "stop doing"), or the name of a joint that wasn't actually measured this set.

**Fallback**, shown when no model is available or the model's answer fails the above checks twice: a
template sentence built purely from arithmetic, e.g. *"You finished 8 of 10 reps, with a median
quality of 71 out of 100. Your form started to slip around rep 5. Your range was 12 percent smaller
after that point. Next session, aim to finish all 10 reps."* — attributed on-screen as *"Summary from
measurements only."* rather than *"Written on this phone by Gemma..."*.

---

## 22. Full phrase bank (spoken + written coaching text)

Every string the app says or writes to the patient in its own voice, in both languages it ships
today.

### Rejections (why a rep didn't count)

| Reason | English | Hindi |
|---|---|---|
| Range not reached | "That one didn't count — go all the way down." | "यह गिनती में नहीं आया — पूरा नीचे जाइए।" |
| Too fast | "That one didn't count — that was too fast." | "यह गिनती में नहीं आया — बहुत तेज़ था।" |
| Too slow | "That one didn't count — that was too slow." | "यह गिनती में नहीं आया — बहुत धीमा था।" |
| Lost sight | "That one didn't count — I lost sight of you." | "यह गिनती में नहीं आया — आप दिखाई नहीं दिए।" |
| Unscored | "That one didn't count — I couldn't score it." | "यह गिनती में नहीं आया — मैं इसे माप नहीं सका।" |
| Skipped movement | "That one didn't count — follow the whole movement, don't jump to the end." | "यह गिनती में नहीं आया — पूरी हरकत कीजिए, सीधे अंत तक मत जाइए।" |
| Left a joint out | "That one didn't count — you left out the \<joint\>." | "यह गिनती में नहीं आया — आपने \<joint\> को छोड़ दिया।" |

### Rep notes (counted, but worth a word)

| | English | Hindi |
|---|---|---|
| Overshot | "That counted — but you went past the target. Stop where the movement ends." | "यह गिना गया — लेकिन आप ज़रूरत से ज़्यादा गए। जहाँ हरकत ख़त्म होती है वहीं रुकिए।" |
| Short of range | "That counted — but go a little further next time." | "यह गिना गया — लेकिन अगली बार थोड़ा और आगे तक जाइए।" |
| Compensation | "That counted — but keep your \<joint\> still." | "यह गिना गया — लेकिन अपनी \<joint\> स्थिर रखिए।" |

### Status line

| | English | Hindi |
|---|---|---|
| No person in frame | "Step into frame." | "कैमरे के सामने आइए।" |
| Occluded | "I can't see you clearly — check the framing." | "आप साफ़ नहीं दिख रहे — कैमरा ठीक कीजिए।" |
| Ready | "Ready when you are." | "जब आप तैयार हों, शुरू कीजिए।" |
| Tracking | "Tracking." | "देख रहा हूँ।" |
| Set complete | "Set complete — that's all of them. You can stop." | "सेट पूरा हुआ — बस इतना ही। अब आप रुक सकते हैं।" |

### Other spoken/written lines

| Purpose | English | Hindi |
|---|---|---|
| Set complete (spoken) | "Set complete. That's all of them, you can stop." | "सेट पूरा हुआ। बस इतना ही, अब आप रुक सकते हैं।" |
| Partial reps count | "N didn't count" | "N गिनती में नहीं आए" |
| Calibrating | "Getting ready — follow the checklist." | "तैयार हो रहे हैं — चेकलिस्ट पूरी कीजिए।" |
| Recalibrating (phone bumped) | "The view has changed. Line the camera back up to continue." | "कैमरे का नज़ारा बदल गया है। दोबारा सही स्थिति में लाइए।" |
| Safety stop | "Stop the set here. Your form has dropped well below how you started — rest before you do more." | "यहीं रुक जाइए। आपका फ़ॉर्म शुरुआत से काफ़ी गिर गया है — और करने से पहले आराम कीजिए।" |
| Hold broken | "Hold finished — N seconds." | "होल्ड पूरा — N सेकंड।" |
| Hold drift | "You're slipping — bring your \<joint\> back." | "आप फिसल रहे हैं — अपनी \<joint\> वापस लाइए।" |
| Neutral capture | "Stand naturally and still." | "सामान्य रूप से सीधे और स्थिर खड़े रहिए।" |

### Calibration checklist labels

| # | English | Hindi |
|---|---|---|
| 1 | "I can see the joints I need to watch" | "मुझे ज़रूरी जोड़ दिख रहे हैं" |
| 2 | "You're the right distance from the camera" | "आप कैमरे से सही दूरी पर हैं" |
| 3 | "You're centred in the frame" | "आप फ़्रेम के बीच में हैं" |
| 4 | "The camera is angled correctly for this exercise" | "इस एक्सरसाइज़ के लिए कैमरे का कोण सही है" |
| 5 | "The phone is upright" | "फ़ोन सीधा खड़ा है" |
| 6 | "The lighting is good enough" | "रोशनी पर्याप्त है" |
| 7 | "You're holding still" | "आप स्थिर खड़े हैं" |

### Joint names (as spoken to a patient — not the internal feature id)

knee, other knee, hip, other hip, back, torso, shoulders, hips, arm, shoulder, elbow, ankle, lower
back — each has an equivalent Hindi word used identically wherever a joint is named in coaching text
(घुटने, दूसरे घुटने, कूल्हे, दूसरे कूल्हे, पीठ, धड़, कंधों, कमर, बाँह, कंधे, कोहनी, टखने, कमर).

### Rep-count speech (Hindi only; English just reads the digit)

शून्य, एक, दो, तीन, चार, पाँच, छह, सात, आठ, नौ, दस, ग्यारह, बारह, तेरह, चौदह, पंद्रह, सोलह, सत्रह,
अठारह, उन्नीस, बीस (zero through twenty; beyond 20 the raw numeral is handed to the TTS engine).

---

## 23. Demo exercise catalog (reference content, physio can author their own)

Four built-in exercises exist as a starting catalog. A physio's own recording always wins in
production — this content only appears when a demo/reference exercise is selected during authoring.

| Name | Mode | Setup text | Safety text |
|---|---|---|---|
| Seated knee extension | Cyclic, 12×3, 60s rest | "Sit side-on to the phone, working leg nearest the lens. Phone at knee height, about 2 m away, whole body from head to foot in frame." | "Stop if the knee hurts rather than works. Do not lock the knee at the top." |
| Standing hip abduction | Cyclic, 12×3, 60s rest | "Stand facing the phone, one hand lightly on a chair. Phone at hip height, about 2.5 m away, both feet in frame." | "Stop if the standing hip aches. Keep the raised foot pointing forwards." |
| Wall sit | Hold, 30s×3, 60s rest | "Stand side-on to the phone with your back against the wall. Phone at hip height, about 2.5 m away, wall behind you and both feet in frame." | "Stop at the first shake, not the last. Come up if the knees burn sharply." |
| Sit to stand | Cyclic, 10×3, 90s rest | "Sit side-on to the phone on a firm chair, arms folded. Phone at hip height, about 2.5 m away, chair and both feet in frame." | "Keep the chair against a wall. Stop and use your hands rather than fall back." |

Catalog-conformance warnings (amber, advisory only, shown after recording if the physio's recording
doesn't match what was expected of the chosen exercise):
- *"\<exercise\> needs a \<side-on|front-on\> view and this was recorded \<the other\>. The patient
  will be calibrated against the way you recorded, not the way this exercise should be seen."*
- *"The biggest movement here was \<joint\>, not \<expected joint(s)\>. Check the phone is seeing the
  working limb."*
- *"Nothing will be said about \<joint(s)\> - it did not vary in your recording, so there is no band
  to score it against."*
- *"\<exercise\> is a hold. Record it with the hold flow, not eight reps."* (or the reverse)

---

## 24. Numeric thresholds reference

Every constant that decides a color, a verdict, or whether something is shown at all.

| Constant | Value | Meaning |
|---|---|---|
| Quality pass bar | 80 / 100 | quality band SUCCESS at/above this; also the progression quality bar |
| Quality warning floor | 55 / 100 | band WARNING between this and the pass bar; below is DANGER |
| Min valid reps for change-point | 6 | fewer than this and CUSUM / degradation onset cannot be computed at all |
| Progression window | last 3 sessions | §6.21.2's rolling window for all trend/verdict logic |
| Ready — range progress | ≥ 90% of the way from baseline to goal | one of two "ready" conditions, per session |
| Ready — sessions required | 2 of the last 3 | how many sessions must clear both conditions |
| Ready — degradation fraction | 80% of prescribed reps | form must hold at least this far into the set |
| Review — quality floor | 50 / 100 | 2 consecutive sessions below this triggers "needs review" |
| Review — completion floor | 50% | 2 consecutive sessions below this completion rate triggers "needs review" |
| Review — consecutive count | 2 | how many sessions in a row trigger a review flag |
| Min reportable joint shift | 0.5 σ | attribution below this is treated as noise, not shown |
| Min trusted frames | 5 | a joint-shift reading below this is shown but never ranked first |
| Min reportable percent (ΔROM/ΔTempo) | 5% | a delta below this is suppressed as rep-to-rep noise |
| Summary sentence / word cap | 5 sentences / 90 words | hard ceiling on the LLM-written (or template) summary |

---

## 25. Things this doc deliberately does not cover

- A cue-effectiveness table (§14.1 of the spec asks for one) — not buildable yet, because no
  exercise currently defines any faults for the live coaching engine to detect.
- A goal-setting screen — doesn't exist yet, which is why every progression verdict currently says
  "no range goal set" rather than a percentage.
- Anything under `docs/iterations/` that is marked `planned` and has no shipped UI.
