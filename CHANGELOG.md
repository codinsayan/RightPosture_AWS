# Changelog

Running record of RightPosture iterations: what changed, why it was decided that way, and when.

Each entry maps to one gated version in [`docs/iterations/README.md`](docs/iterations/README.md).
Mathematical truth lives in `RightPosture_Implementation_Plan (3).md`; per-iteration requirements
live in `RightPosture_Iteration_Plan.md`. This file records the *reasoning* behind each step so the
decisions survive without re-reading the diff.

Timestamps are local (IST, UTC+05:30).

---

## One summary, not three - 2026-09-06

Streaming made §10.4's retry visible, and what it looked like was the app changing its mind: a
paragraph appeared, vanished, was replaced by a second one, and that could be replaced again by the
template. Three texts for one summary.

The screens now ask for **one attempt**. A draft that fails its checks goes straight to the template
rather than being rewritten in front of the reader. The retry still exists in the generator and is
still the default - it is right whenever the reader only ever sees the finished paragraph - but a
streamed retry costs more in confusion than the better wording gains.

To make a single attempt worth having, both prompts now state the bounds the validator actually
enforces rather than a vague "3-4 short sentences": a sentence count, a word count, and an explicit
instruction that every number must be copied exactly and never combined, rounded, converted or
turned into a percentage. An over-long draft was the commonest refusal, and it used to cost a silent
retry; now it costs the reader the whole paragraph, which is worth two lines of prompt to avoid.

Refusal reasons are logged under `RPSummary`, so a summary that falls back to the template can be
diagnosed from a device rather than guessed at.

767 unit tests pass, including one asserting that a single-attempt generation calls the model
exactly once.

---

## Summaries stream, word by word, without letting an unchecked claim through - 2026-09-06

Every generated summary in the app - the post-set one, the session detail, the patient's progress
and the physio's patient overview - now appears as the model writes it rather than arriving whole
after a spinner. LiteRT-LM hands back a `Flow` as it decodes, so this is Gemma's real output
arriving, not a finished paragraph revealed on a timer. On the CPU rung a summary takes most of a
minute, and a paragraph that is visibly being written is a very different wait from a spinner that
might be broken.

### The problem streaming creates, and what is done about it

`checkSummary` needs a finished paragraph. Streaming shows an unfinished one. Left alone, that
reopens exactly the hole the validator exists to close - "your range improved by 15 percent" does no
less damage for being on screen only briefly, because a patient reads it, believes it, and repeats
it to their physiotherapist.

So the stream is gated rather than trusted. `safeVisiblePrefix` reveals text up to, and never past:

- **the first completed number that is not in the fact sheet.** A digit run still being written is
  held back entirely - "9" is not yet evidence that "93" is coming, and flashing it only to take it
  away is its own kind of wrong;
- **the first word refused outright** - a diagnosis, a treatment, unsafe advice. None of those
  depend on the fact sheet, so they can be caught the moment they appear.

That is deliberately the cheap half of the rules, chosen because those two are what put a false
measurement or a clinical claim in front of a reader. Everything else - sentence count, spelled-out
units, joints nobody watched - is a property of the finished text and is still checked when the
stream ends, exactly as before.

### The retry is now legible

A refused first draft starts §10.4's second attempt from an empty paragraph, so the reader watched
the text they were half way through reading disappear. It now says why: *"The first draft did not
pass its checks. Writing it again…"*.

### One card, four screens

The three places that rendered a summary each had their own copy of the card. They now share one, so
streaming arrived everywhere at once and the attribution line §11.1 asks for cannot go missing from
one of them.

Seven new tests, 766 in total. They assert the property that matters rather than the prose: that an
invented number is never among the strings a reader was shown, that a partial digit is held back
until complete, and that a generator with no streaming support still behaves exactly as it did.

---

## A written summary of a run of sessions, in both roles - 2026-09-06

§10.7's on-device summary described one set. It now also describes a *run* of them, in the two
places somebody asks how things are going overall: the patient's **Progress → Overview**, and the
physio's **patient detail → Overview**.

Both are written by Gemma on the phone. Neither is allowed to say anything we did not measure.

### The safety machinery is shared, not copied

`checkSummary` was tied to the per-set fact type. It now takes a `SummarisableFacts` interface that
both fact sheets implement, so there is one copy of the rules rather than two - and the second copy
is exactly the one that would miss a rule and ship a fabricated measurement.

`ProgressFacts` is computed from the numbers already on the tiles beside the paragraph, so the prose
is a reading of the screen rather than a second measurement of the same sets. Absences stay absent:
a patient with nothing scored gets a sentence saying so, never a median of zero.

Two things the fact sheet refuses on purpose. **Percent is never admitted** - nothing in a run of
sessions is a rate, so a model reaching for one is converting a count into a statistic we did not
compute. **Degrees are admitted only when a range was measured.** The exercise name *is*
whitelisted, because without it the body-part check refuses every summary of an exercise named after
the joint it works, which is most of them.

### Trend is halves, not endpoints

Quality direction compares the first half of the run against the second, and says "not enough yet"
below four scored sets. First-against-last would turn one bad day into "your scores are falling",
which is a sentence a patient repeats to their physiotherapist.

### Two audiences, one fact sheet

The patient is addressed as "you" and is never told to change their programme. The physio is briefed
about "this patient" and is never told whether to advance them - §6.21.5 reserves that for a
clinician, and a summary that recommended it would be the app making the call.

### Numbers are highlighted rather than recoloured

The measurements in generated prose now carry a pale yellow marker-pen wash instead of a blue tint.
Recoloured digits inside running prose are easy to read straight past; a highlight is found at a
glance and survives greyscale. The weight is kept as a second signal so the emphasis never depends
on colour alone.

That change exposed a layout defect: `SpanStyle` paints its background over the whole line box, and
`BodyLg` hangs its extra leading above the glyphs, so every mark floated a few pixels above the
number it belonged to. Centring the text in its line puts the mark where a pen would have left it.

Nine new tests, 759 in total, all passing. They cover the properties that matter rather than the
prose: that the fallback template passes the validator it falls back to, that an invented number and
a spelled-out percentage are both refused, and that an unscored run is stated rather than zeroed.

---

## Recording a reference becomes a wizard, and stops shipping a list of exercises - 2026-09-06

§5.5 is five steps. It was one camera screen whose buttons appeared and disappeared, so a physio
halfway through could not tell whether the next tap would record, save, or discard four minutes of
setup. Every step now says which one it is, out of how many.

### Step 1 is its own screen, and asks one question

Which kind of movement: a **repeated movement** or a **static hold**. That has to be settled before
the viewfinder opens, because §6.19's hold is not a variant of the cyclic path - it has no phase
axis and is measured with a timer rather than a rep counter. It used to be discovered from which
of two buttons at the bottom of the camera screen had greyed out.

The camera step therefore opens knowing what it is recording, and carries only the one recording
control that applies. A disabled button for the other pipeline is a permanent question about a
decision already made.

### The built-in exercise catalogue is gone from the flow

The wizard used to offer four shipped exercises - seated knee extension, standing hip abduction,
wall sit, sit to stand - as a list to pick from. It no longer offers any list. The physiotherapist
demonstrates whatever movement they are prescribing and names it at step 5.

A shipped catalogue implies the app approves a particular set of exercises, when the whole design is
that the physio's own recording is the clinical standard. It also quietly limits a clinician to
movements somebody else chose.

Nothing in the pipeline needed the catalogue: the free-form path was always supported, so the
prescription and body position fall back to their existing defaults and §3.8's catalogue
cross-check simply has nothing to compare against. What the catalogue *was* carrying that mattered
is the setup instruction and the safety note, and both now live on step 1 in general form - the
safety line said once rather than per exercise, and the framing advice ("whole body in frame, the
working joint facing the lens, a single camera reads depth worst") said where the phone is actually
being positioned.

### Cancel

One Cancel, in the header, confirmed once past step 3 - which is §5.5's rule and was not
implemented. Before a recording exists there is nothing to lose and a dialog is friction; after one
exists, leaving throws away the setup and the recording both.

Steps 2 to 5 still wear their original Material styling inside the camera screen; this pass gave
them the wizard chrome and the mode, not the visual rebuild. 750 unit tests pass.

---

## The library says what a reference actually measures - 2026-09-06

§5.4's Library listed a name, a category pill and a version number. That is enough to *find* a
reference and nowhere near enough to *trust* one, which is what the screen is for: every set any
patient performs is scored against one of these recordings, so choosing from here is choosing the
standard, not just the exercise.

Each card now carries the prescription the recording was authored with - "3 x 10s hold, 60s rest" -
and the joints §6.10 decided to watch, ordered by the weight each carries in the score, so the first
joint named is the one that moves the number most. Both come out of the `.rpose` bundle already on
disk, read on the IO dispatcher after first paint: the name, category and version come off the
database row and are on screen immediately, and the rest fills in.

### What else changed

- **Versions are grouped.** §9.6 keeps every version on disk because older sessions were scored
  against them, so the list showed the same exercise several times. Each exercise now appears once
  at its newest version and says how many versions stand behind it.
- **A reference that will not open says so**, in red, with the only repair there is: record a new
  version. §8's table already promised this; nothing implemented it. The alternative is the patient
  discovering it when Start does nothing.
- **A filter appears only when it would do something** - when the library holds both cyclic
  exercises and holds.
- **An explainer sits above the list.** Without it a reference reads as a saved video rather than as
  the tolerance band every set is measured against.

### The duplicate joint chip

`displayNameOf` drops the active/passive suffix on purpose - a patient reading "hip flex passive"
learns nothing they can act on. On a calibration card it printed **Hip flex** twice, which reads as
a rendering fault rather than as two features on two legs. The library labels the passive one
"(other side)"; the patient-facing wording is untouched.

Backend, scoring and sync are unchanged. 750 unit tests pass.

---

## A physio can open one patient - 2026-09-06

§5.3's patient detail did not exist. The roster listed names and nothing else, so "how is this
patient doing?" had to be answered by scrolling the whole activity feed and filtering it mentally.
Tapping a row now opens four tabs about one person: **Overview**, **Progress**, **Programme** and
**Sessions**.

### What it is built from, and what that rules out

The backend is untouched, so everything on the screen comes from §14.1's report - the sessions the
server already hands a physio for their own exercises - filtered to one patient. That report carries
each set's outcome: score, reps counted against reps prescribed, §6.18.3's degradation onset, and
whether the set finished. It does not carry per-rep rows, joint samples, range peaks, or the
patient's prescription.

Three consequences, each made visible rather than papered over:

- **Programme says it cannot read the prescription.** The alternative was to fill the tab with the
  *physio's own* assignments and let them read as the patient's. Sets, reps and range goals are the
  things a clinician acts on directly, so showing the wrong ones is worse than showing none. What
  the tab does show is what the patient has actually been **performing**, per exercise and per
  reference version - a different claim, labelled as one.
- **Overview does not render a progression verdict.** §6.21 decides on three criteria and this
  device can evaluate two. Calling two out of three a recommendation is the same error as scoring a
  set on half its reps, so the card shows the two criteria as evidence, marks the range-goal
  criterion unassessed, and stops - which is what §6.21.3 already does when no goal exists. There is
  no Approve button, because approving would write to a programme this build cannot read.
- **Session rows do not open.** §4.3's detail reads reps and joint samples out of the local
  database, and these sets were recorded on someone else's phone. A row that opened would land on
  "that set is no longer on this device", so everything the report carries is on the row instead.

### Absent is not zero, again

The median quality drops unscored sets rather than counting them as nought, and quotes how many were
scored beside it; a patient with no scored set reads as a grey dash. The median is used rather than
the mean for the same reason - one abandoned set at 20 pulls a mean under the pass mark and would
have a clinician reviewing a patient who is fine. An unreadable timestamp sorts into its own
"Undated" heading rather than to January 1970.

### Two defects found on the device

The segmented tab bar was built for three tabs. At four, "Programme" wrapped onto a second line and
a two-digit badge split down the middle, so the bar now steps its label down a size at four tabs
rather than truncating a word.

Reopening a patient bounced straight back to the roster. The roster is refetched on entering any
physio screen and a failed refetch returns an empty list, which made the patient being read vanish.
The tapped row is now held, so an unreachable server costs a refresh rather than the screen.

### Naming

Sentences about a patient fall back to "this patient" rather than to the email's local part. Real
rosters contain `patient@` and `admin@`, and "stored against patient's account" reads as a rendering
bug on the one card whose whole job is to be believed about what it cannot see.

750 unit tests pass, including nine new ones covering the median, the streak, the undated bucket and
the back stack.

---

## The physio home stops being seven screens at once - 2026-09-06

`PhysioHomeScreen` carried the patient feed, local sessions, progression verdicts, a progress
button, the exercise library, a record button, an invite button, the linked-patient roster and a
stray "switch role" - nine things on one scroll. §5.1 says the physio opens this between patients to
answer one question, in thirty seconds: who did what, and does anyone need me.

It now answers exactly that, in three sections: **what needs attention**, **what patients did**, and
**what was recorded on this handset**. Everything else moved to the three tabs beside it.

### Why the flag strip is at the top

It is the only thing on the screen that is time-sensitive. Everything else is a record of something
that already happened; a flag is a request. It used to sit below the exercise library, which is the
direct cause of the "I cannot see any recent sets" complaint recorded against the old screen.

8.4's gate is that a flag shows its supporting data, so the reasons are on the card rather than
behind a tap, and tapping through goes to the evidence rather than to a confirmation. §5.3's patient
detail is where that will land once it exists; until then it opens the exercise's own curves, which
are the same evidence. A flag that says "needs review" and cannot show why is worse than no flag.

The card carries a coloured edge rather than a coloured field, because the reasons underneath have
to stay readable and dark red text on a red ground is the first thing to go.

### The three tabs that took the rest

**Patients** is the roster and the invite. Who is linked is a different question from who has done a
set, and only this list answers it - a physio who invited someone and has seen nothing back needs to
tell "they have not started yet" apart from "the code never worked". Rows are not tappable, because
§5.3 does not exist yet and a row that opens nothing is worse than a row that admits it is a list.

**Library** is the recorded references, each with its version - §9.6 makes the version part of the
identity, and prescribing the wrong one is silent - and the Record action. Tapping a reference still
performs it, for the reason it always did: the physio decided what correct looks like, so they are
the only one who can tell whether the reference actually scores that way, and finding out after
prescribing is finding out too late.

**Me** is minimal, exactly as the patient's is, and for the same reason: "switch role" had to go
somewhere the moment it left the home screen.

### One small thing worth fixing

Server timestamps were printed raw, so "2026-09-05T20:54" sat beside "Sat 5 Sep · 20:54" on the same
screen. Two identically-shaped facts looked like they came from different systems, and the reader
was left doing the timezone arithmetic. They are parsed as UTC and rendered in the device's zone,
falling back to the raw prefix rather than to a blank if the format ever changes.

### Naming

`Screen.PhysioHome` is `Screen.PhysioActivity`, with `PhysioPatients`, `PhysioLibrary` and
`PhysioMe` beside it.

699 unit tests pass. Verified on a device across all four tabs, signed in as the demo physio.

---

## Progress becomes four tabs, and answers the question first - 2026-09-06

`AnalyticsScreen` was a picker followed by one dense scroll: pick an exercise, get two charts, a
verdict and a list of reasons stacked together. §4.4 asks for four tabs, and the split is worth more
here than anywhere else in the app because "am I getting better?" has four different answers and a
patient checking in for thirty seconds wants the first one.

**Overview** carries four stat tiles, one chart and the verdict card. **Quality** and **Range** each
carry their chart plus the explanation of what the number means. **History** is every set, grouped
by week and tappable through to §4.3.

### Picking an exercise stopped being navigation

Progress is per exercise and always has been - a knee extension and a wall sit are measured by
different things, and one number over both would be meaningless in a way that is hard to see and
easy to act on. What changed is how the exercise is chosen. It used to be a screen you pushed
through, so a patient with five exercises needed five back presses to leave. It is now a chip row
above the tabs, and picking replaces the entry rather than stacking on it. Arriving with no exercise
named opens the one with the most history instead of a picker: a patient with a single exercise
should never have to choose it.

The tab survives the switch, because someone reading Range for one exercise almost certainly wants
Range for the next.

### The four numbers, and what they do when there is nothing to say

Every tile is nullable where the measurement can be absent and none of them substitutes a zero.
No scoreable set yet means no median - a dash and "nothing scored yet" - rather than a median of
nothing. Best range behaves the same.

The streak is the exception and deliberately so: it counts consecutive days **ending today**, not
ending whenever the patient last trained. A streak that survives a missed day is not a streak, and
zero is an honest measurement here rather than an absent one.

### The range goal

§6.21.3's guard says a missing goal must not become a fabricated percentage, so the Range tab states
plainly that no goal is set, who sets one, and that the chart is showing what was reached without
judging it against a target. That card disappears the day §5.6 exists.

### Verdict wording

§6.21.5 reserves advancement for a clinician and this card is read by the patient, so "ready to
progress" has to arrive as evidence for a conversation rather than as permission. The line naming
who decides is part of the card, not a footnote under it.

### Removed

`nav/Charts.kt` is deleted. Its three chart composables had no callers left once the session detail
and this screen moved to `TrendChartCard` and `AttributionBar`. `Screen.Analytics` is now
`Screen.Progress`.

699 unit tests pass. Verified on a device across Overview, Range and History, including the
no-goal state and a set that could not be scored appearing in history as a dash rather than a zero.

---

## The session record splits into three tabs - 2026-09-06

`SessionReportScreen` opened with a paragraph, four stat lines, two charts and twelve rep rows on one
scroll. The thing most readers came for - what happened - was underneath the evidence for it.

§4.3 is now `SessionDetailScreen`, tabbed into **Summary / Reps / Form**. Summary answers the
question; Reps and Form hold the working. It opens on Summary always, with one exception reserved
for the physio side: a "form slipped" flag will deep-link to **Form**, so a claim and the chart
behind it are one tap apart. `initialTab` exists for that and is unused until §5.1 is rebuilt.

### The rep row

§8.4's requirement is a row that holds two independent facts without looking like it contradicts
itself: a rep can score well and still not count. The score is a measurement of how the movement
looked; countability is a judgement about whether it was the movement at all. So the score keeps its
normal weight, the counted state is a pill in its own right, and the reason sits underneath in a
sentence of its own rather than being appended to the headline. A rep nothing scored shows a dash
and the words "not scored", never a zero.

### The attribution bar

§6.18.5's joint shift is signed, and the sign is the clinically interesting part - drifting away
from the reference and moving back toward it are opposite events. A bar that encoded only magnitude
would render them identically, so `AttributionBar` diverges from a centre line, and direction is
carried by side and by words as well as by colour (§11 - red/green colour-blindness is common).

A joint with too few frames to judge draws no bar at all: only the centre line, a dashed outline and
the words "not measured". Silence about a joint is not a report that it was steady.

### The chart gained a change point

`TrendChartCard` now takes a `changePointX` and recolours the trace from that point on, so
§6.18.3's onset is visible rather than described. Its x-axis also stops assuming the first point sits
at zero, which was fine for sessions indexed from zero and wrong for reps numbered from one.

### Naming

`Screen.SessionReport` is now `Screen.SessionDetail`, matching the documents. The old file is gone;
`highlightNumbers` moved to `SessionSummaryText.kt` beside the summary generation it belongs with,
and its hard-coded blue became `Tokens.ChartTrace`.

`nav/Charts.kt`'s `QualityCurve` and `JointShiftBars` are now unused. They are left in place rather
than deleted because §4.4's Progress screen is next and will decide what replaces them.

699 unit tests pass, including the navigation tests updated for the rename. The screen is installed
but not yet checked on the handset - the phone was mid-set in someone else's hands.

---

## The post-set summary becomes its own screen - 2026-09-06

`SessionReportScreen` was two screens fused into one scroll: a written summary, four stat lines, two
charts and a twelve-row rep list, shown to a patient who had just put the phone down. UI-SCREENS.md
§12 asks for the split, and until now finishing a set did not even reach it - the patient was sent
straight back to Today, so the paragraph the model writes was never seen unless somebody went
looking for it.

§4.2 now exists as its own screen, reached automatically the moment a set ends.

### The charts are on it, and that was a decision

The mock draws two trend charts on the summary; §4.2 says charts live one tap deeper. The user
settled it: include the charts that real data can fill, exclude anything that would be a placeholder.
`analyticsOf` already computes median quality and peak range per session, so both are real, and both
suppress themselves below two judged sessions rather than drawing a one-point line that says nothing.

### "Set 2 of 3" is inferred, and only where it can be

Nothing in the schema records that a session was one set of three. The index is counted instead -
how many sets of this exercise finished today, up to this one - and the inference is only made when
there is a prescription to count against. With no assignment there is no denominator, so the screen
says nothing about sets rather than making one up. The rest countdown is the prescription's own
`restSeconds`, and it gates nothing: starting early is one tap, because §10 forbids telling anyone to
push through anything.

### Two bugs worth recording

**Loading is not the same as missing.** The screen took a nullable `data` and, on null, navigated
home. But null is also what it looks like while the row is still being read off disk - so the screen
left the composition, which cancelled the very read it was waiting for, which left `data` null, in a
loop that ended with the app closing. `LeftCompositionCancellationException` in the log was the
tell. The two states are now distinguished explicitly.

**Clearing state before navigating.** `onFinished` set `startBundle = null` first, which recomposed
the exercise branch with a null bundle - indistinguishable from process death - so its recovery
effect fired and sent the patient home, discarding the summary. The bundle is now cleared last, and
the comment says why the order matters.

### Repeats

Today's completed rows had swapped their **Start** for a tick, which quietly removed the ability to
do an exercise twice. That was wrong: how many times to repeat an exercise is the patient's and the
physiotherapist's decision, not the app's - the same principle as the app never advancing anyone on
its own. A completed row now keeps an **Again** button beside its tick, and the hero card offers the
first exercise again once everything is done rather than disappearing.

### Not touched

Pose, scoring, references, sync, the schema. The summary generation moved into a shared helper so the
post-set screen and the detail screen cannot phrase the same set differently, but the generation
itself is unchanged.

699 unit tests pass. Verified on a device, including the unscored path: a set with nothing to judge
renders a dash and "Not scored" beside a plain explanation, never a zero.

---

## The app gets a design system, and Today gets redrawn - 2026-09-06

`UI_screens/` holds a mock of every screen and `docs/UI-FLOWS.md` says how they connect. The app
itself was still wearing the Compose project template: `Theme.kt` was the wizard's purple with
dynamic colour left on, `Type.kt` had one overridden text style, and Today was a column of stock
`Button`s ending in a stack of unrelated links.

This is the first screen rebuilt against the mocks, plus the foundation it could not be built
without.

### Why dynamic colour is off

Material You would let the device recolour this palette from the wallpaper. Here the palette carries
clinical meaning - amber is "form slipped", grey is "never measured", green is "cleared the 80 pass
bar" - and §11 additionally requires the live HUD to hold a known contrast ratio at the 2-3 m a
propped-up phone actually sits at. A theme we do not control cannot promise either. There is no dark
scheme for the same reason.

`Tokens` names the colours exactly as the mocks' Tailwind config does, so a value in a `code.html`
can be found in Kotlin without translation. Two families are load-bearing rather than decorative:
`status-absent`, which is what a measurement that was never taken looks like, and the
`tag-exercise` / `tag-hold` pair, because a hold and a cyclic exercise are measured by different
things and a patient reading a list should be able to tell before they start.

### Fonts are bundled, not downloaded

Plus Jakarta Sans and JetBrains Mono ship in `res/font` under their OFL licence rather than through
the downloadable-fonts provider, which would put a network dependency in front of the first frame on
a phone §7.1 tests in airplane mode. Devanagari is outside both families; Android's fallback fills
those glyphs from the system font, which is the behaviour §11 wants - a missing glyph should degrade
to something readable rather than to a box.

Mono is reserved for values read as data rather than prose. A hold timer in a proportional face
shifts the layout under it on every tick.

### Icons

Five of the icons the mocks use are not in Compose's core Material set. They are drawn as stroked
`ImageVector`s rather than pulled in with `material-icons-extended`, which is roughly nine megabytes
of vectors for the dozen we would use - and release builds here run with `isMinifyEnabled = false`,
so none of the unused ones would be stripped from the APK a patient installs.

### Today

The screen has one job: start today's work with no navigation. So the first exercise not yet done is
promoted out of the list into a card carrying the setup instruction and the safety note from the
catalogue - the two things a patient needs before starting rather than during - and everything else
is a compact row beneath it. Rows that were done today carry a tick and their result.

That result is where the clinical-safety rule shows up in ordinary code. `TodayResult.score` is
nullable and the nullability is the point: a set that ran but could not be scored has a result and
no number, and the row reads "0 of 12 reps &middot; not scored" in the absent grey rather than
printing a zero in the failing red. The first version of this shipped with the enrichment on only
one of the two `todayItems` paths, so a signed-in patient - which is every real patient - saw no
ticks at all. Caught on a device, not in a test, which is an argument for a test.

### What moved off Today

"My progress" and "Switch role" were links at the bottom of a screen whose job is starting a set.
The tab bar takes the first and a new `PatientMeScreen` takes the second. That screen is deliberately
minimal: it exists now because those affordances had to go somewhere the moment they left Today, and
removing working features to make a screen prettier is not a trade worth making. §4.5's real design -
voice, language, the linked-physio card - arrives when that screen is built in its own right.

### Not touched

The pose pipeline, scoring, references, sync and the database schema. This is a frontend change; the
only non-UI addition is one query that reads sessions already recorded to work out what was done
today.

699 unit tests still pass. Verified on a device: Today, the tick states and the Me tab.

---

## Navigation flow written down - 2026-09-06

`docs/UI-SCREENS.md` describes what every screen contains but never says how anyone reaches it. A
designer could draw all eighteen screens from it and still not know whether Skip on the physio-link
step is a dead end, what a system back press does mid-set, or which screen a rejected recording
returns to. Those are the decisions that get made accidentally during implementation and are
expensive to reverse.

`docs/UI-FLOWS.md` records them: entry points, exits, back behaviour and failure path for each
screen in the §7 inventory, plus a launch-routing table, a cross-screen entry-point table for the
six screens with more than one caller, and an offline/failure table.

### The decisions it fixes

**A set cannot be lost to navigation.** System back during a set is *Finish set*, not discard, and a
summary is produced from whatever was captured - including when the app is backgrounded or the
camera drops. There is no path where a patient does the work and gets no record.

**The safety stop does not navigate.** It overrides the screen, but the patient still ends the set
deliberately. An automatic exit would be the app making a clinical decision, which §1's second
principle forbids.

**Skipping stays a real path.** No account and no physio link, at every gate that could demand one -
sign-in failures keep "Continue without an account" available, and an unreachable server during code
entry offers Skip rather than blocking.

**Flags deep-link to their evidence.** A physio tapping "form slipped" lands on the Form tab, not on
Summary, so the claim and the chart backing it are one tap apart.

The document is design intent, not a description of the current build, and says so at the top
alongside UI-SCREENS.md.

---

## Hold mode becomes a shareable exercise - 2026-09-06

A hold could be recorded and performed, and then it was gone. `buildHoldReference` produced a
`HoldReference` that lived in a Compose `var` and died with the screen: nothing packaged it, so
there was nothing to write, upload or prescribe. Of the nine stages the cyclic path runs between
authoring and a patient performing the exercise, holds had two.

They now have all nine, plus a category tag on both screens that list exercises.

### The design decision

A hold reference is a scalar per joint; a cyclic reference is a trajectory per joint. They do not
fit in the same box.

The rejected option was storing a hold as a one-bin cyclic reference - no format change, no Track A
involvement. But `derivative`, `rom`, `theta_start`, `theta_peak`, `critical_window` and `v_ref` are
all required by `readRpose`, and every one of them would have had to be filled with a number that
means nothing. It would also have dropped `offsetFromNeutral` and `salience`, which are exactly what
§6.19 substitutes for range of motion. A file that lies about its own contents is worse than a
format change.

So: a `hold.json` member in the zip, present exactly when `manifest.mode` is `hold`, mirroring how
`demo.mp4` is already optional. `RposeBundle` carries the live `HoldReference` type rather than a
parallel wire type, for the same reason it already carries `Reference` and `WatchList` - one
definition of what a hold reference is, so a field cannot be added to the maths and quietly left out
of the file.

What a hold has no answer to is written as zero, not as a placeholder: no reps, no period, no phase
axis, because §6.19 makes that absence the design rather than a gap. The reference is empty for the
same reason - every per-joint number a hold has lives in `hold.json`, and duplicating a subset into a
one-bin trajectory would create two places to disagree about the same angle.

The invariant, enforced in `RposeBundle`'s constructor and again on load: `mode` is `hold` if and
only if a hold reference is present. A prescription that disagrees with itself about what kind of
exercise it is cannot be safely acted on.

### TRACK OWNERSHIP - core files changed from Track B

`core/rpose/Rpose.kt` and `core/reference/BundleAssembly.kt` are **Track A's**.
[PARALLEL-TRACKS.md:74](docs/iterations/PARALLEL-TRACKS.md) is explicit: the manifest schema is the
wire format between the tracks, and "a field added there is a conversation, not a commit."

This was written from Track B at the user's direction, and it is the second time a core file has
moved from outside Track A's branch - `HOLD_BREAK_Z` was the first. The `hold.json` shape is the
part Track A should review, and it is specified in the implementation plan rather than only in the
diff.

### Format version

`RPOSE_FORMAT_VERSION` goes 1.0 to 1.1, and 1.0 stays readable. A 1.0 build meeting a hold bundle
would parse the manifest field by field - its shape is unchanged - and produce an exercise with an
empty reference and no hold data, which fails silently in front of a patient. The bump turns that
into a refusal. Every bundle already on a phone is 1.0 and could only have been cyclic, so they all
keep loading.

### Tags

`Exercise` and `Hold`, from `manifest.mode` rendered - not a second field that could be set wrong.
`cyclic` is a term from the maths that nobody says aloud, so the pair a patient reads is the pair
they would say back.

The detail line branches with it. A hold prescribed as "3 x 1" is technically true and useless - one
repetition of an unnamed duration - so §9.1's `prescription.hold_s` is read out as "3 x 30s hold".

`exercises.mode` and `exercises.holdSeconds` are denormalised into Room so Today can draw its rows
from one list query instead of opening every zip on disk. They cannot drift from the file: §9.6
bundles are immutable, so the manifest they were copied from can never be edited, only superseded by
a version that copies its own.

### Schema

Room 2 -> 3 with `MIGRATION_2_3`, written rather than left to `fallbackToDestructiveMigration`, which
is still configured and would silently drop every session a patient recorded. Existing rows default
to `cyclic` with no duration, which is not a guess - a hold had no way to be saved before this, so
every row already in the table came from the cyclic path.

Postgres gains `exercises.mode` (checked), `exercises.hold_seconds` and `assignments.hold_seconds`.
The prescribed duration is kept apart from the recorded one because they legitimately differ - a
thirty-second reference prescribed at fifteen for a patient starting out - and `COALESCE` resolves
the two in the Today query so no screen has to know there are two places to look.

`schema.sql` gains an idempotent migration block. `create table if not exists` does nothing to a
table that already exists, so a project created before this would have kept the old shape and every
push of a hold would have failed on an unknown column, on the demo phone, in front of whoever was
watching.

**RLS needed no change**, and that is worth stating rather than discovering: every policy on
`exercises` and `assignments` keys off `physio_id` and `is_assigned_exercise`, and neither asks what
kind of exercise it is. A hold inherits exactly the protection a cyclic reference already had.

### Performing one

`CameraPreviewScreen` took every `startBundle` through §6.20's calibration gate, which measures a
phase span that is zero by construction for a hold. A hold bundle now branches straight to the live
hold: its own settling gate does the job calibration does for a cyclic set, and there is no recording
stage because the physio authored the reference days ago.

### Not done, deliberately

The physio cannot yet see what the patient's hold achieved. `sessions` and `reps` are shaped around
repetitions, and a hold has attempts, seconds achieved and a hold quality instead - no ROM, no rep
duration, no SPARC. Mapping one onto the other is a decision worth taking on its own rather than
smuggling into this change.

### Gates

699 unit tests pass, 26 of them new. The one that matters most is the round trip end to end: author a
wall sit from frames, write the bundle, read it back, and drive `HoldTracker` with what came out of
the file. It reaches HOLDING and stays there. Every other test asserts that a value survives; that
one asserts the reference is still *usable*, which is what actually fails in front of a patient days
later on a different phone.

**Known risk carried into this change:** on device, the live hold has been observed logging
`live session started` and then nothing - entry to HOLDING needs all watched joints inside
`HOLD_QUALITY_Z = 2.0` at once for 0.5 s, and a 10 s recording of someone very still produces a small
sigma. If that is a real tuning problem it now affects patients as well as the physio, and
`HOLD_QUALITY_Z` is Track A's constant.

---

## Summary context: the fatigue deltas §6.18.5 defined and nothing computed - 2026-09-06

Source: 10.7, 6.18.5, 10.1, 1.6. Iteration [v8.7](docs/iterations/v8.7-summary-context.md).

§6.18.5 defines ΔROM, ΔTempo and ΔSmooth and calls rising roughness with falling range and slowing
tempo "the textbook fatigue pattern; naming it explicitly is what a physiotherapist will recognise
instantly". Every column needed had been on disk since 7.1. Nothing read them, so the summary never
named the pattern.

The model now gets range and tempo change as whole percentages, smoothness as a direction, how many
reps did not count and the commonest reason — and the joint lines carry the magnitude that
`changesAfterOnset` used to discard, as a word ("drifted clearly further") rather than a z-score.

### The edit that would have failed silently

Adding a fact takes five coordinated edits, and a sixth that is easy to miss. `SPELLED_MAGNITUDES`
contains `"percent"`, and the validator refuses any unit not already present in a change
description — separately from the digit check. So a model correctly writing "12 percent smaller" was
**rejected even with 12 whitelisted**. Every delta-bearing summary would have been rejected twice,
fallen back to the template, and shown no symptom beyond the attribution line reading "Summary from
measurements only".

`allowedMagnitudeWords()` admits `percent`, and only when a percentage was actually computed.
`degrees`, `sigma` and `standard deviation` stay refused — ΔROM is a ratio, and the model is never
handed a raw angle or a z-score. A test now fails if the escape hatch is removed.

A second trap alongside it: `NUMBER_RUN` matches digit runs, so it never captures a leading minus.
Whitelisting `"-12"` would admit nothing while still refusing the true sentence. The values go in
through `abs`.

### Why every number is an integer

`checkSummary` counts sentences by splitting on `[.!?]+`, so a decimal like `1.8` reads as a sentence
boundary and pushes a summary toward the five-sentence rejection — and the whitelist matches numerals
whole, so `"1"` and `"8"` would not admit it anyway. ΔSmooth is therefore reported as a direction with
no number at all, which is also the whole of what a patient can act on.

### Valid reps only, and not for tidiness

A rep rejected for `RANGE` has a small `romDegrees` *by definition* — that is the rejection criterion,
not an observation about the patient. Including rejected reps in the post-onset window would
manufacture a range collapse out of the criterion itself and report it as fatigue. Pinned by a test.

The template gained exactly one sentence, ΔROM's. With every fact present it runs to 5 sentences and
about 55 words against bounds of 5 and 90 — which is precisely why the other three facts stay
prompt-only, where the model can fold them into 3-4 sentences a fixed template cannot.

717 tests, up from 701.

**Outstanding:** not measured on device. More facts in a prompt whose word budget did not grow may
raise the template-fallback rate, and only a phone will say.

---

## Rep detail: "Rep 1: 94 · SKIPPED" explained rather than fixed - 2026-09-06

Source: 14.1, 14.2, 6.6, 6.12.4. Iteration [v8.6](docs/iterations/v8.6-rep-detail.md).

The report rendered `Rep 1: 94 · SKIPPED`. It reads like a bug, and it is not — which is the reason
it needed changing. `ExerciseEngine.closeRep` scores a rep from its frames and *then* asks the phase
tracker whether the movement was the prescribed one:

    val valid = completed.valid && quality != null

So a rep can match the reference on every frame the scorer saw and still be refused for jumping
straight to the end position. Both facts are true; printing them side by side with no explanation
sent someone looking for a defect that was never there.

The invariant, now written down where a query author will find it: `rejection != null` if and only if
`!valid`, and **`quality != null` is orthogonal to both**. Never read a score as proof a rep counted.

Rows now read `Rep 3 · scored 94 · not counted` with "It scored well frame by frame, but it jumped to
the end instead of following the whole movement — so it does not count toward your total." underneath.

### The wording was load-bearing

Rejection phrases reach the summary prompt, the model echoes them, and the validator then checks
them. `UNSAFE_ADVICE_WORDS` contains **"skip"**, so describing `SKIPPED` with the obvious word would
have rejected every summary mentioning it — twice, silently, falling back to the template with
nothing on screen but "Summary from measurements only". A test now runs every `RepRejection` value
through `checkSummary`.

`rejectionReason` resolves by name rather than `valueOf`, because stored rows are not guaranteed to
carry enum names — there is real data holding the string `"too fast"` — and `valueOf` would take the
report down rather than degrade.

### Highlighted numbers

`NUMBER_RUN` went `private` to `internal` and `numeralRanges` shares it, so the numerals the screen
emphasises are exactly the ones the validator checked. That is the whole safety argument: rule 1
refuses any digit run outside `allowedNumbers`, so in an accepted summary every number is one we
computed. A second regex would eventually disagree, and the first symptom would be a highlighted
number nobody validated.

`SessionReportScreen` also moved out of `AppHost.kt` into its own file — 852 lines down to 673.

701 tests, up from 691.

---

## Analytics: the charts §14.1 promised, and the data that was already there - 2026-09-06

Source: 14.1, 14.2, 6.18.5, 6.21, 6.6. Iteration [v8.5](docs/iterations/v8.5-analytics.md).

§14.1 promised the physio a per-set quality curve with the change point marked, and §14.2 promised
the patient the same plus progression curves. Neither existed; the session report was a list of
`Text` rows. Every column needed had been on disk since 7.1 and nothing read it.

**No schema change, deliberately.** `RightPostureDatabase.kt` shows a zero diff.
`fallbackToDestructiveMigration(dropAllTables = true)` is armed, so a `version` bump without a
matching migration silently destroys every session recorded on the device — and nothing this needed
justified going near it. Two `@Query` methods are schema-neutral.

### What landed

- `report/Analytics.kt` — `ChartPoint`, `segmentsOf`, `repQualitySeries`, `sessionTrendSeries`,
  `analyticsOf`. Pure, so the decisions that can be wrong are testable on the JVM.
- `nav/Charts.kt` — `QualityCurve`, `TrendCurve`, `JointShiftBars`, in the same hand-rolled Canvas
  idiom §6.11's reference charts already use. No charting library; the first one would arrive
  carrying Cards and theme colours into a codebase that uses neither.
- `Screen.Analytics(exerciseId: String?)` — one screen for both roles, reached from "Progress over
  time" and "My progress".
- `jointShiftsOf` recovers the magnitude `changesAfterOnset` computed and threw away, so §6.18.5's
  attribution can be drawn as well as spoken.

### The rule the whole thing is built around

§6.6: **an absent measurement is a gap, never a zero.** A chart is where breaking that does the most
damage, because a line touching the floor reads as a bad performance rather than a missing one, and
nothing on screen says which it was. Three separate things can be absent, and each needed its own
handling:

- An unscored rep — `quality` is null, so no point is drawn and no line passes through it.
- A rejected rep — plotted against `repIndex`, so reps 1, 2, 4 sit at x = 1, 2, 4. By list position
  they would be 0, 1, 2, sliding rep 4 into rep 3's slot and relabelling which rep the drop belonged
  to.
- A session too short to judge — `y = null` but its x position kept, drawn as a hollow marker.
  Dropping it compresses the axis and hides that the patient turned up; flooring it claims they
  scored nothing.

`segmentsOf` is the one abstraction worth having: every chart draws
`segmentsOf(points).forEach { drawPath }`, so a single test pins the no-interpolation rule for all of
them. Writing it the obvious way — `points.forEach { lineTo(...) }` — draws a line straight through
the missing rep and asserts a value nobody recorded.

### Trust, not just magnitude

`FeatureSampleEntity.frames` is the trust column — "a mean over 3 frames is not a mean over 90".
Without a test on it, a nine-sigma shift measured on three frames outranks a modest one seen on
ninety, purely by being noisier. `MIN_TRUSTED_FRAMES = 5` sorts those last and draws them at 40%
alpha rather than hiding them: that the camera barely saw a joint is itself worth knowing.

### Two N+1 queries removed

`progressionFor` was issuing one `reps(session.id)` per session — sixty round trips to draw one
screen. The session report was doing the same per rep for feature samples, when `allFeatureSamples`
already existed. Both are single queries now.

691 tests, up from 675.

**Outstanding:** `romGoal` is still null everywhere, so §6.21.3's guard reports "no range goal set"
rather than a readiness percentage — honest, but the goal-setting screen is what turns these curves
into progress *toward* something. The readiness verdict is shown to patients as well as physios,
which §14.2 does not grant and §6.21.5 argues against; decided by the project owner, with "Your
physiotherapist decides when to move you on." carried underneath it.

---

## LLM working on device - GPU inference, and four bugs it took to get there - 2026-09-05

Source: 6.21, 10.7, 11.2. Status: **working on the target device.** Gemma 4 E2B runs on the Adreno
GPU at roughly 1.5 s per summary; 8.4's flags are on screen and firing on real data.

Measured on the iQOO (SM8850):

```
I RPLlm: Loaded on GPU in 6706 ms
I RPLlm: Generated 184 chars on GPU in 1589 ms
I RPLlm: Generated 250 chars on GPU in 1819 ms
```

### The model file - chip-specific builds are NPU-only

`gemma-4-E2B-it_qualcomm_sm8750.litertlm` failed on **every** backend with `Input tensor not found`.
CPU failing is the diagnosis: CPU has no driver dependency, so the container simply has no
CPU-executable text graph. The per-chip builds are NPU artifacts, not portable models with an NPU
bonus. The plain `gemma-4-E2B-it.litertlm` loads.

### Fixed - the encoders are not the text graph

With the right file, GPU was still refused:

```
Audio backend constraint mismatch. Model requires one of [cpu] but Audio backend is GPU
```

Self-inflicted. Pinning vision and audio to the same backend as the text graph looked like tidy
explicitness and cost the GPU rung outright, because Gemma 4 E2B's audio encoder is CPU-only and one
rejected modality fails the whole engine. Encoders are pinned to CPU; the ladder's choice applies to
the graph that does the work.

### Fixed - an app may not dlopen a vendor library it has not declared

GPU then loaded and failed *every generation*:

```
Can not find OpenCL library on this device
```

The device has it - `/vendor/lib64/libOpenCL.so` is right there. Since Android 12 an app cannot
dlopen a vendor library without declaring it, so the manifest now carries
`<uses-native-library android:name="libOpenCL.so" android:required="false"/>` and the Mali and Pixel
equivalents. All optional, so a device missing one falls down the ladder rather than failing to
install.

### Fixed - a ladder that tests loading tests the wrong thing

The deeper bug, and the one worth keeping. `warmUp` accepted a rung that **initialised**, which is
not the same as one that **works**: GPU initialised in five seconds and then failed every request.
Nothing looked broken - summaries just quietly became templates forever, which is §10.5's safety net
covering for a defect instead of an outage.

The ladder now requires a rung to produce a token before it is accepted. One short prompt, once, on
the background thread that was already warming up.

### Fixed - the patient was being shown filenames

`summaryFactsOf` was passed `session.exerciseId`, so the prompt - and the sentence a patient reads -
named the exercise `rp_hands_on_3` rather than "hands-on 3". The session list had the same fault.
Both now resolve the id to the name the physio typed.

### Fixed - 8.4 was computed and never displayed

`evaluateProgression` and `sessionMetricsOf` existed, were tested, and were called from nowhere.
They are now computed per exercise on the physio's home screen, with §6.21.6's review flags firing
on real device data:

```
Recorded exercise - needs review
· Fewer than half the prescribed reps completed, 2 sessions running.
```

There is no goal-setting screen yet, so §6.21.5's readiness rule has no `G_m` and cannot answer.
Rather than report "range met in 0 of 3" - which describes a failing patient when the truth is a
missing field - the verdict says so plainly: *"No range goal set for this exercise, so readiness
cannot be assessed."* §6.21.6's review flags need no goal and work today.

### Changed - the physio home screen opens on what the physio came to read

§14.1 says the physio opens the app to read what their patients did, and the code said so in a
comment while putting sessions underneath the exercise library - five full-width buttons and off the
bottom of the screen. Reported as "I cannot see any recent sets". Sessions and progression now come
first.

### Outstanding

- **LLM on NPU is blocked upstream.** The repo publishes Qualcomm builds for sm8750 and qcs8275
  only; there is no sm8850 build, and QNN payloads are compiled per Hexagon version. GPU is the
  achievable acceleration until one exists. Pose-on-NPU is unaffected and is what §15 measures.
- A rep was observed rendering as `Rep 1: 94 · SKIPPED` - a quality score and a rejection reason at
  once. Not investigated yet.

Tests: 675, all pass.

---

## v8.3 / v8.4 - On-device summary and progression flags - 2026-09-05

Source: 6.21, 10.7, 11.2, 11.3. Status: in-progress - builds, 673 tests pass, and none of the model
path has run on the phone because the model and the QNN runtime are not on it yet.

8.1 and 8.2 (cue templates and cue wording) are deliberately skipped for now. This is the summary
and progression half of the LLM work.

### Corrected before building anything

I assumed "Gemma 4 E2B" was a slip for Gemma 3n E2B and was about to say so. It is not: Gemma 4
shipped on 31 March 2026 in E2B, E4B, 26B A4B and 31B sizes, and E2B is the edge variant. Checked
rather than asserted, which is the only reason this is not a wrong correction in the changelog.

### Decided - the model phrases, arithmetic decides

§10.1 draws this line for cues and it applies unchanged here. Progression is computed in
`core/progression/Progression.kt` with no model anywhere near it; the summary is generated from a
fact sheet built in `report/SessionFacts.kt` and then **validated against that same fact sheet**.

`checkSummary` rejects any digit run that is not one of the numbers we computed. It is a blunt rule
and it is blunt on purpose: "you managed 8 of 10 reps" is checkable, whereas "your knee improved by
about fifteen percent" is a fabricated measurement wearing the clothes of encouragement, and no
softer rule catches it reliably. Spelled-out units are refused for the same reason - "a few percent"
contains no digits at all.

It also refuses diagnosis, treatment, "push through the pain", unilateral programme changes, and any
joint the set did not measure. That last one has no number in it and still describes a measurement
that does not exist.

Two attempts, then §10.5's template. The retry names what was wrong with the first answer, because a
blind second attempt from a small model usually reproduces the first.

### Found by a test, not on a device

The template failed its own validator. It writes "quality of 71 out of 100", and `100` was not in
the whitelist, so the fallback - the thing that ships when everything else has failed - would itself
have been rejected. The quality scale is a constant rather than a measurement and is now whitelisted
as one. The test that caught it exists because a fallback nobody exercises is a fallback that only
breaks on the worst day.

### 8.4 - progression, and the guards that make it honest

§6.21 implemented in full: median-of-top-3 ROM, median quality, the last-three-sessions median, the
near-zero goal guard, weighted overall progress, §6.21.4's interpolated target, and the exact
"2 of the last 3" advancement rule.

The guards are the point, and each has an obvious wrong answer that looks fine on screen:

- A goal equal to the baseline reports **goal not set**, never a division. "100% progress" because a
  physio left a field blank is a lie a patient would act on.
- A session with fewer than 6 valid reps is **excluded**, not scored as "never degraded". §6.21.1
  calls this out by name, and the wrong version lets a patient who gave up early outrank one who
  finished.
- An unscored session has **no** quality, not a quality of zero, and it breaks a bad run rather than
  extending it - "we could not measure this" is not evidence of deterioration.
- Overall progress drops components whose goal is unset instead of folding them in as zero.

**Never auto-advances.** `evaluateProgression` returns a flag and the reasons behind it, and there
is deliberately no method on it that could change a prescription.

### §11.1's ladder, now for the language model too

`OnDeviceLlm` walks NPU → GPU → CPU exactly as `PoseLandmarkerSource` does. The published spread is
wide enough to matter: 50-80+ tokens/second on the Hexagon NPU, 30-50 on the Adreno GPU, 4-5 on the
CPU - two seconds against most of a minute for a four-sentence summary.

An NPU attempt is **recorded to disk before it is made** and cleared on success. A failed QNN
initialisation does not always throw; on some driver versions it takes the process down natively,
which no `try`/`catch` in Kotlin sees. If the marker survives a launch, the previous run died trying
and this run starts at GPU. One small file between a driver bug and an app that crashes forever.

§11.3's exclusion is enforced in `AppHost`: the model is released on the way **into** an exercise
rather than on the way out, because contention during scoring is already in the data by the time a
set ends. It is re-warmed on the way out - which was missing from the first version, and would have
meant every summary after the first set silently fell to the template.

### Toolchain - verified, not guessed

LiteRT-LM **0.17.0 cannot be used**. It is compiled with Kotlin 2.4.0 metadata, and the build fails
with "binary version of its metadata is 2.4.0, expected version is 2.2.0". This is the supabase-kt
problem again, and this time the escape route was checked: **KSP tops out at 2.2.21**, there is no
KSP for Kotlin 2.3 or 2.4, and Room needs KSP. So upgrading is not available, not merely unpleasant.

0.16.1 is the last release built on 2.2.21 and is pinned with that reasoning recorded in the version
catalog. `kotlin-reflect` is excluded because LiteRT-LM only needs it for annotation-driven tool
calling, which this app does not use, and because it is the one dependency that can drag Kotlin
forward quietly.

### Changed - the APK stopped being 125 MB

LiteRT-LM ships a 21 MB arm64 JNI library and a 25 MB x86_64 one. With MediaPipe's four the debug
APK reached 125 MB, over half of it native code for architectures no phone runs. `abiFilters` now
packages arm64-v8a only: **125 MB to 62 MB**. The cost is that x86 emulators can no longer install
it, which nothing automated depends on - the suite is JVM and Robolectric, and every device gate
needs real hardware anyway.

### Outstanding - the two things that gate the NPU

- **No model on the device.** `docs/MODEL-SETUP.md` has the exact `adb` sequence. Without one the
  app is fully functional and every summary is the template.
- **`libQnnHtp.so` is not in the APK.** Verified by unzipping both the AAR and the built APK: the
  LiteRT-LM AAR carries only `liblitertlm_jni.so`, and the QNN runtime comes from Qualcomm's SDK.
  Until it is copied into `app/src/main/jniLibs/arm64-v8a/`, **both** ladders skip their NPU rung
  and start at GPU. `app/src/main/jniLibs/README.md` lists the files and the Hexagon version to
  match. This is a packaging gap, not a device limitation.

Tests: `SummarySafetyTest` (19), `ProgressionTest` (21), `SessionFactsTest` (10).
`./gradlew testDebugUnitTest` - 673 tests, all pass.

---

## Role flows - naming, testing, and demo credentials - 2026-09-05

Source: 0, 9.6, 14.1, 14.2. Status: in-progress - builds and the suite is green, none of it run on
the phone yet.

Merged `feature/hold-auto-stop` first. It forked from v7.2, before any of the Supabase work, and
touched five files none of 7.3-7.5 went near, so the only conflict was this changelog. The hold
logic is unchanged from that branch.

### Fixed - every free-form recording overwrote the last one

Authoring wrote `id = chosen?.id ?: "rp_local"` and `version = 1`. A physio who recorded without
picking from 6.4's catalogue therefore produced `rp_local` v1 **every time**, and each save
replaced the row before it. The library could never hold more than one such exercise, and nothing
said so.

The id is now derived from the name the physio types, and the version is read from Room and
incremented. Saving under a new name is a new exercise; saving under one already used publishes
v+1, which is what §9.6 means by immutable. `ExerciseNamingTest` pins the part that is easy to get
silently wrong - "Heel Slide", "heel slide" and "Heel-Slide" have to be one exercise, or the
version history forks with nothing on screen to show it.

### Fixed - a half-finished save could not be retried

`writeRposeFile` refuses to overwrite, which is §9.6 enforced rather than merely documented. But
the version now comes from Room, so a save that wrote the file and then failed on the row left the
retry computing the same version, finding the orphan and failing forever. A file that no row points
at is debris from a half-finished save rather than an immutable bundle, and is now deleted before
the retry. The refusal itself is untouched.

### Added - the recording is named before it is written

Finishing a recording used to save it and open calibration in one tap, under a name the physio
never chose. It now asks. `SaveExerciseDialog` takes the name, warns when it will publish a new
version of something that already exists, and offers Save and Cancel. Nothing is written until
Save, so Cancel genuinely costs nothing - the reference stays on screen.

### Added - the physio can perform their own reference

Every exercise in the library is a button that runs it, through the same calibration gate and the
same engine a patient gets. The physio decided what correct looks like, so they are the only person
who can tell whether the reference actually scores that way, and every future session is measured
against it - discovering the problem after prescribing is discovering it too late.

These sets are deliberately **not** queued for sync. A physio checking their own reference on their
own phone is not a patient session, and pushing it would put a set on a patient report that no
patient performed.

### Changed - a signed-in patient sees only what was prescribed

Today used to append every reference stored on the handset to the prescribed list. On a phone the
physio had recorded on, that offered the patient exercises nobody prescribed for them.

The fallback is kept for the case it was written for: with **no account** there is no prescription
to respect, and listing nothing would leave Today empty on exactly the phone 7.1's airplane-mode
gate runs on. Signed in, the list is the programme and nothing else.

### Added - demo credentials

Two buttons on the sign-in screen fill and submit the demo physio and patient accounts. They exist
to make the two-role walkthrough fast; the credentials are weak and public by design and belong
nowhere near a project holding anything real.

### Found, not fixed - `demo.mp4` has two homes and no source

Worth writing down because the schema implies otherwise. §9 carries the demo **inside** the
bundle, and `writeRposeFile` supports it. 7.4 additionally uploads a demo as a **separate** Storage
object and records `exercises.demo_path`. That is two mechanisms for one video.

Neither is fed. There is no `VideoCapture` or `MediaRecorder` anywhere in the app, the single call
site passes `demoVideo = null`, and `ExerciseEntity.demoPath` is therefore always null - so
`demo_path` is always null on the server too. Nothing is broken today; the duplication has to be
settled before anything records a demo, or the same video will exist in two places that can
disagree.

Tests: `ExerciseNamingTest`, 6 cases. `./gradlew testDebugUnitTest` - 623 tests, all pass.

---

## v7.5 - Session sync and the physio report - 2026-09-05

Source: 13.3-13.4, 14.1. Status: in-progress - code complete and green; the device gate (an offline
patient session later appearing on the physio's phone) needs two devices and has not been run.

### Added

- `sync/SessionSync.kt` - the mirror for performed work: Room to Postgres, one direction, never
  back. Pushes sessions, reps, feature samples, fault events and cue events; `pullReport` reads
  §14.1's report back for the physio.
- `sync/SessionSyncWorker.kt` - WorkManager push with exponential backoff (30 s base, CONNECTED
  constraint), queued when the patient arrives at Today and again the moment a set ends.
- `supabase/schema.sql` - `sessions`, `reps`, `feature_samples`, `fault_events`, `cue_events`, with
  RLS on every table: the patient writes their own rows, the physio reads sessions on exercises
  they own.
- The physio home lists recent sets from the mirror, and a `SessionReport` screen shows one set at
  7.2's depth - reps counted, set score, §6.18.3's change point in words.

### Decided

- **Idempotency comes from key shape, not a dedup pass.** `sessions` is keyed by the id the device
  minted, `reps` by `(session_id, rep_index)`, `feature_samples` by `(session_id, rep_index,
  feature)`, and the event tables by ids minted when the events happened. Every write asks the
  server to merge duplicates (`Prefer: resolution=merge-duplicates`), so a replayed retry writes the
  same rows over itself and is a no-op. There is nothing to dedup because there is nothing distinct
  to dedup against.
- **Children follow parents, because the server declares the foreign keys.** Session, then reps,
  then samples; fault events, then cue events. A partial push is therefore always a *prefix* of a
  complete one - never a set of orphans - and the retry replays from the top.
- **Sessions are marked one at a time, not in a batch.** If the network drops after three of five,
  those three are done and the next run starts at the fourth. Marking the batch at the end would
  either re-send all five or mark two that never left.
- **A set still in progress is not uploaded out from under the patient.** Unfinished sessions are
  queued only once they are 30 minutes stale - longer than any plausible set, so someone resting
  between sets is not pushed mid-rest. A session the OS killed two hours ago *is* uploaded: §13.1
  kept those reps on disk precisely so "the patient started and something went wrong" would be
  visible rather than lost.
- **Offline stops the run; a rejection stops it too.** Hammering a server that is not there wastes
  the battery of someone who has just exercised, and WorkManager's backoff owns the retry. A 4xx
  means the server understood and refused - resending identical bytes earns an identical refusal,
  and Room still holds the set either way.
- **The signed-in patient owns the row, not the stored column.** A session recorded in airplane
  mode has a null `patientUid` because nobody was signed in; RLS needs an owner, so the id comes
  from whoever is signed in when the push finally runs. 7.1's gate and 7.5's sync meet exactly here.
- **An unscored rep travels as null, never as zero.** §1.6 at the wire: "not scored" and "scored 0"
  are different clinical claims, and the mirror is where a careless default would quietly turn one
  into the other.
- **The report row is a separate type from the local entity.** A session pulled from the server is
  somebody else's record and is never written to Room - reusing the entity would invite exactly the
  confusion §13.3 forbids.
- **No client-side physio filter on the report query.** The `physio reads sessions on own
  exercises` policy already decides this; a filter on top would either duplicate that rule or
  quietly contradict it.
- **The worker owns no logic.** `syncWorkResultFor` maps the outcome - Done is success (an empty
  queue is not a problem to retry), Offline retries, Failed fails - as a plain function testable
  without a worker, a context or a scheduler. `KEEP` rather than `REPLACE` on enqueue: the queue is
  read from Room when the worker runs, so replacing would restart the backoff every time a set
  finished and defer the push by the very activity it exists to upload.

### Kept true

- §13.4's version binding: the session carries `exercise_id`/`exercise_version` copied at set start
  and never recomputed. A physio who publishes v2 while a set is queued does not retag it - §9.6
  makes `(id, version)` immutable, so v1 still names exactly what was scored.
- An unconfigured build is a supported state: the worker returns success with nowhere to push, and
  the physio home simply shows an empty report.

### Outstanding

- The device gate is unrun: an offline patient session appearing on the physio's phone needs two
  devices (or one device and two accounts) and a live project with the updated schema applied.
- `schema.sql` needs one more run against the live project for the 7.5 tables and policies.
- The Supabase free-tier auto-pause (7 days idle) remains a demo-day operational risk, noted in the
  iteration doc.

Tests: `SessionSyncTest`, 16 cases against real Room under Robolectric and `MockEngine`.
`./gradlew testDebugUnitTest` - 614 tests, all pass.

---

## v7.4 - Invite and assignment sync - 2026-09-05

Source: 0, 13. Status: in-progress - code complete and green, but `supabase/schema.sql` has to be
re-run before any of it works against the live project, and the device gate needs two phones.

### The gap that had to be closed before anything else

7.4's device gate is "physio creates code, patient enters it, assignment appears", and the schema as
7.3 left it could not reach the second half. Every `select` policy on `public.users` restricted a
physio to their own row, so the patient who had just redeemed a code was **invisible to the physio
who minted it** - there was nobody to assign to. `is_linked_patient` and a matching policy fix it,
and the redeemed invite is the link, so no join table is needed.

The mirror of that was wrong too. `is_linked_physio` was defined through `assignments`, so a patient
who had redeemed a code but had not been prescribed anything yet could not read the name of the
physio they had just linked to - which is exactly the window between the two halves of the gate. It
now considers redeemed invites as well.

### Decided - redemption is a database function, not a policy

The first design gave patients `select` and `update` on any open invite. Three things were wrong
with it, and one function fixes all three.

- **It was a read followed by a write.** Two patients entering the same code could both read it as
  open, and the second `update` would overwrite the first. `select ... for update` inside
  `redeem_invite` takes a row lock, so exactly one wins.
- **It leaked.** A select policy that returns open invites returns *everybody's* open invites. Any
  signed-in user could page through live codes. Patients now have no read access to `invites` at
  all; the function reveals the status of the one code that was typed in.
- **It could not tell the truth.** 7.4 has to distinguish expired from already-used from no-such-
  code, and a select policy tight enough to be safe returns an empty set for all three. Those are
  three different things for the patient to do next: "that code has expired, ask for a new one"
  versus "check it with your physiotherapist". Collapsing them into "invalid code" would send
  someone with an expired code off to retype a code that was never wrong.

Redeeming your **own** code twice returns `ok`, not `used`. A patient who reinstalls, or who taps
again because the first reply was lost, has to end up linked rather than locked out.

### Added

- `InviteCodes` - Crockford Base32, which drops I, L, O and U. I/1 and O/0 are the pairs actually
  mistyped off a handwritten note, and dropping U means a random code cannot spell something a
  physiotherapist would rather not read out. Typed codes are normalised before they are sent, so
  writing `O` for zero links the patient instead of rejecting them.
- `InviteRepository` - mint, redeem, and list the patients who have redeemed. A `409` on the primary
  key regenerates the code rather than retrying it; see below for why that is the one failure the
  generic retry must not touch.
- `ExerciseSync` - the bundle and its row travel to the patient, and prescriptions come back.
- `retryingSupabase` - one retry policy so no repository invents its own.
- Invite and redeem screens, `Screen.InvitePatient` and `Screen.RedeemInvite`, and a Today list that
  finally shows prescriptions.

### Decided - what is retried, and what is not

Only status 0, 5xx and 429: failures a later attempt could plausibly resolve. A 400, 401, 403 or 409
describes something wrong with the *request*, and resending identical bytes earns an identical
rejection while spending the patient's battery.

409 is the one worth naming. An invite-code collision needs a **different** code, not the same one
again, so it is handled by regeneration in `InviteRepository.create` and explicitly excluded from
the generic helper. Backoff doubles rather than being fixed, so one server hiccup does not become a
synchronised burst from every device that saw it; 7.5's WorkManager backoff will use the same shape.

### Decided - ordering, and why the bundle goes first

`pushExercise` uploads the `.rpose` **before** it registers the row. The other order has an
unrecoverable failure: a row with no upload behind it is an exercise the patient can see and cannot
start, and no retry fixes it, because the retry would re-register the row rather than supply the
missing bytes. A demo upload that fails does *not* fail the exercise - §9 makes it optional, and the
patient can work from the cues.

Assignment ids are generated on the device. A request that timed out *after* the server committed it
would otherwise replay as a second prescription, and the patient would be told to do the same
exercise twice.

Downloads land in a `.part` file and are renamed once whole. A half-written bundle is worse than an
absent one: the row would point at a file that fails to open in front of the patient.

### Kept true

- §13.3 still holds: the server is a mirror and cannot delete or contradict a local row. The worst a
  failed pull can do is leave the patient with exactly the exercises they already had.
- Today lists prescriptions **and** references recorded on this phone, under separate headings. That
  second half is not a convenience - 7.1's airplane-mode gate runs on a phone where the physio
  recorded the reference, where there is no account and no assignment row, and an assignments-only
  list would be empty precisely where the gate says it must not be.
- The invite step is skippable, and the invite controls are absent entirely on an unconfigured
  build. A linking step that could not be passed over would put a network dependency in front of the
  one flow that must not have one.
- The Today join is an inner join. An assignment whose bundle has not arrived is not startable, and
  listing it would put a button on the screen that does nothing when pressed. It appears by itself
  once the bundle lands.

### Fixed against the live project - three bugs no mock could have caught

The schema was applied and the whole path was exercised end to end with three real accounts: mint,
redeem, list patients, upload, assign, pull with the embedded join, download. All of it works. Three
things were wrong anyway, and every one of them was invisible to `MockEngine`, because a mock
answers the question it was asked rather than the question the server was asked.

**`anon` could still call `redeem_invite`.** `revoke all on function ... from public` was written on
the assumption that it removes every grant. It does not: Supabase grants EXECUTE on functions in
`public` to `anon` and `authenticated` through default privileges, and an explicit grant is not what
`from public` revokes. Measured, not guessed - an anon call reached the function and got a reply.
`anon` is now named in its own `revoke`. The `auth.uid() is null` guard inside the function stays
and is the defence that actually holds: a grant is configuration a later migration or a dashboard
action can silently restore, whereas the guard travels with the function.

**The demo never reached the patient.** `pushExercise` uploaded `demo.mp4` and recorded its path,
and `pullAssignments` then never asked for the `demo_path` column and hard-coded the local demo to
null. The mock replied with exactly the fields it was handed, so the missing column only showed up
against a real reply. The demo now travels, is written through the same `.part`-then-rename path as
the bundle, and its failure is swallowed - §9 makes it optional, and losing a prescribed exercise
over a video that would not fetch is the wrong trade.

**The patient's copy claimed the wrong recording date.** `created_at` was requested and then thrown
away in favour of `now()`, so an exercise recorded in March and downloaded in September was dated
September on the patient's phone. It is parsed now. That needed a reader, because `timestamptz` does
not come back in the format `iso8601` writes - the live value was
`2026-09-05T07:21:17.291458+00:00`: microseconds, and an offset written with a colon.

Writing that reader produced a fourth bug, this one caught by its own test rather than by the
server: `Calendar` is lenient by default and silently rolls nonsense over, so `2026-13-99` parsed to
a real instant in 2027 instead of being rejected. `isLenient = false`. A date that cannot be read
has to fall back, not quietly become a different date.

### Outstanding

- **`supabase/schema.sql` needs one more run** for the `revoke ... from anon` line. Everything else
  in it is already applied and verified.
- The device gate needs two phones, or one phone and two accounts, and has not been run.
- Prescriptions are fixed at 3 x 10 with 60 s rest. Letting the physio choose is a form, and it can
  wait until the path it would feed is proven.

Tests: `InviteTest` (20) and `ExerciseSyncTest` (16, real Room under Robolectric).
`./gradlew testDebugUnitTest` - 599 tests, all pass.

---

## v7.3 - Supabase authentication - 2026-09-05

Source: 4.1, 13, 19. Status: in-progress - code complete and verified against a live project; the
device gate needs the phone and has not been run.

### Decided - the Supabase SDK was abandoned before it was ever used

`supabase-kt` 3.8.0 forces `kotlin-stdlib` to **2.4.0**, and this project's KSP (2.0.21-1.0.28,
required by Room) cannot read 2.4.0 metadata. The build failed with
`[ksp] [MissingType]: Element 'RightPostureDatabase' references a type that is not present` before
any of our own code compiled. Bisected dependency by dependency: without Supabase the toolchain
settles on the **2.3.21** stdlib AGP 9's built-in Kotlin supplies and everything builds.

Rather than upgrade the Kotlin toolchain underneath a green 553-test suite to satisfy one library,
`sync/SupabaseClient.kt` now talks to the same HTTP API the SDK wraps - Supabase is PostgREST and
GoTrue over HTTP, and the surface needed here is small. Three things fall out: the
community-maintained-SDK risk flagged when the backend was chosen disappears; every call is mockable
with Ktor's `MockEngine`, so auth is fully tested in the JVM suite with no network and no project;
and the toolchain stays put.

### Added

- `sync/` package: a typed Supabase REST client, `AuthRepository` behind an interface, and
  `SupabaseConfig` reading the URL and anon key from gitignored `local.properties` via `BuildConfig`.
- A sign-in / sign-up screen, and `Screen.SignIn` ahead of the role picker when a backend is
  configured.
- `supabase/schema.sql` - §13.2's mirror redrawn relational, with row-level security on **every**
  table and a private Storage bucket. Not optional: PostgREST exposes these with the anon key, the
  anon key ships inside the APK, so a table with RLS off is readable by anyone who has the app.

### Kept true

- **Not configured is a supported state.** With no URL and key the app uses a local-only repository,
  skips sign-in and behaves exactly as before this iteration. §13.3 and 7.1's airplane-mode gate both
  depend on that. "Continue without an account" keeps it true even on a configured build - being
  unable to sign in must never be what stops someone exercising.
- Half-configured counts as unconfigured. Pasting the URL and forgetting the key is the likelier
  mistake, and it must not look ready or every call fails at the server instead of being skipped.

### Decided

- **Role is a table row, not a JWT claim.** A custom claim needs an Edge Function on signup to write
  it into the token, which is more moving parts than a two-role demo justifies.
- **A missing role row defaults to patient**, failing to the *less* privileged role on purpose: a
  physio wrongly shown the patient flow is inconvenienced, whereas a patient wrongly shown authoring
  can overwrite the reference their own exercises are scored against.
- `AuthOutcome` separates `Rejected` from `Offline`, because telling a patient their password is
  wrong when the wifi is down sends them to reset a password that was fine.

### Changed

- **minSdk 24 -> 26.** Required by the original SDK evaluation and kept: Android 8.0 and up. Drops
  Android 7.x, which is negligible now and irrelevant to the target device.

### Fixed against the live project - a bug no mock could have caught

The first `schema.sql` returned **500 on eight of nine tables**. Postgres reported
`42P17 infinite recursion detected in policy`: the policy on `exercises` queried `assignments`, and
the policy on `assignments` queried `exercises`. Mutual recursion between two policies, each of
which looked correct read on its own.

`MockEngine` cannot find this. The client sent a well-formed request and would have accepted a
well-formed reply; the fault was entirely inside Postgres. Five `SECURITY DEFINER` helper functions
now break the cycle by reading their tables as the function owner, with `search_path` pinned - a
`SECURITY DEFINER` function that resolves names through the caller's path is a privilege-escalation
hole. This is the argument for pointing at a real instance before calling an iteration done.

### Outstanding

- The access token lives in memory, so a restart returns to sign-in. Refresh-token storage belongs
  with 7.5, when background sync must authenticate with no user present.
- The device gate (two accounts sign in *through the app*) is unrun, batched with the deferred
  6.4/7.1/7.2 gates. Sign-up and sign-in were verified end to end over HTTP against the live
  project, which exercises the same code path the app calls.

Tests: `SupabaseAuthTest`, 9 cases against `MockEngine`. `./gradlew testDebugUnitTest` - 563 tests,
all pass.

---

## Hold thresholds - break tightened, reference recording lengthened - 2026-09-05

Source: 6.19. Status: landed on `feature/hold-auto-stop`, **needs Track A's attention** - see below.

### Track ownership - read this first

`core/Params.kt` belongs to **Track A** per
[docs/iterations/PARALLEL-TRACKS.md](docs/iterations/PARALLEL-TRACKS.md), and threshold calibration
against real recordings is their **C3** iteration. Two hold constants were nevertheless changed from
the feature branch, deliberately and with the feature track's knowledge, because the live hold was
unusable on device without them. Recorded here rather than discovered in a diff:

| Constant | Was | Now |
|---|---|---|
| `HOLD_BREAK_Z` | 4.0 | **3.0** |
| `HOLD_BREAK_SECONDS` | 1.0 | **0.6** |

No other hold constant moved. `HOLD_DRIFT_END_SIGMA_PER_SECOND`, `HOLD_DRIFT_SIGMA_PER_SECOND`,
`HOLD_DRIFT_MIN_WINDOW_SECONDS` and `HOLD_QUALITY_Z` are untouched, so two variables changed at once
and the effect stays attributable. If Track A would rather own these numbers, the change is two
lines and a test file.

### What changed and why

- **The break was too forgiving.** A wall sit that had visibly collapsed went on being scored as a
  hold: 4.0 sigma sustained for a full second is a large, long departure. 3.0 sigma for 0.6 s ends
  the hold nearer the moment a person watching would say it ended.
- **The reference now records for 10 s, not 5.** `HOLD_RECORD_DEFAULT_SECONDS` in
  `camera/CameraPreviewScreen.kt`. `HOLD_TRIM_SECONDS` discards two seconds from each end, so five
  seconds left about **one second** of measured window - a per-joint sigma estimated from roughly
  thirty frames of a single breath. Holds recorded that way scored **4-19% quality against their own
  reference** on device. Ten seconds leaves about six, which spans several breaths and the sway that
  comes with them.

### The interaction, which is the part not to assume

Both thresholds are in **units of sigma**, and sigma comes from the reference recording. A 10 s
recording yields a *larger* sigma than a 5 s one, because six seconds of window captures natural
sway that one second missed. So 3.0 sigma after this change may correspond to a similar or even
larger real-world angle than 4.0 sigma did before: **the two changes partly cancel**, and the net
tightening is an empirical question to settle on a device, not an arithmetic one.

If the hold still breaks too late after this, the next step is `HOLD_BREAK_Z = 2.5` and nothing
else. **2.5 is the floor.** Re-entry requires every watched joint inside `HOLD_QUALITY_Z` = 2.0 for
`HOLD_SETTLE_CONFIRM_SECONDS`, and the gap between those two values is the hysteresis that prevents
a break -> settle -> break oscillation. The live hold now re-arms automatically on break, so a
margin spent here appears as a visible loop rather than a subtle mis-score. A test pins that
invariant.

### Tests

`HoldModeTest`, now 25 cases. Per C3's gate each changed constant has a test that fails at the value
it replaced, verified by reverting both and re-running - exactly two failures, one per constant:

- a breach between three and four sigma breaks the hold - fails at `HOLD_BREAK_Z` 4.0;
- a breach shorter than a second still breaks the hold - fails at `HOLD_BREAK_SECONDS` 1.0;
- the break threshold keeps a margin over the re-entry band - pins the 2.5 floor against
  `HOLD_QUALITY_Z`, so the oscillation constraint cannot be spent by accident.

Two existing tests named for "4 sigma" were renamed rather than re-pinned: both drove a 6 sigma
breach, so they never tested the threshold their names claimed.

`./gradlew testDebugUnitTest` - 556 tests, all pass.

### Outstanding

- **The device verdict is the point and is not in yet.** Whether the hold now breaks when a person
  would say it broke, and whether "held well %" becomes plausible against a 10 s reference, is what
  this change exists to answer.

---

## v7.2 - Minimal role navigation - 2026-09-05

Source: 2.4, 14. Status: in-progress - the device gate needs the phone unlocked and has not been run.

### Added

- **A role picker, a physio home and a patient "Today"**, in a new `nav/` package. Five screens, not
  §14's fourteen: §7.2 says to defer the decorative ones, and most of §14's table cannot be built
  *honestly* yet - a patient list needs 7.4's linking, progression curves need 8.4, a demo screen
  needs a `demo.mp4` nothing records, and cue review needs faults 8.1 has not defined.
- **The patient path reads the stored `.rpose` off disk** and opens straight into §6.20 calibration.
  7.1 wrote those bundles and recorded their paths, and nothing read them back; this is the first
  time the stored bundle is load-bearing rather than merely written.
- A session report showing what was performed - reps counted, set score, and §6.18.3's change point
  stated in words rather than left in a chart nobody has built yet.

### Decided

- **Hand-rolled navigation rather than Navigation Compose.** §7.2's gate is navigation-state and
  back-stack tests, and a plain immutable stack is testable in the JVM suite the other 543 tests run
  in. Navigation Compose would have pushed exactly those tests onto an instrumented device, which is
  a worse place to find out the back stack is wrong.
- **`back()` returns null at the root** rather than an empty stack, so the system gets the press. An
  empty stack leaves nothing to render, and swallowing the press traps the user on the home screen.
- **A duplicate push is a no-op**, because otherwise a double tap makes the next back press look
  broken - it pops to a screen identical to the one showing.
- **Finishing a set pops home rather than pushing it**, so a patient doing five exercises in a row
  does not need five back presses to leave.
- **Changing role resets the stack.** A physio's authoring screen has no meaning in a patient's
  history, and leaving it below lets a back press cross between roles.
- `CameraPreviewScreen` was **not** split apart. That refactor is real and still wanted, but doing it
  in the same change as navigation would have made any regression impossible to attribute.

### Handled rather than assumed away

- The `.rpose` file missing while its row survives - reinstall, cleared storage, a half-written
  bundle. The patient is told to ask for a re-record instead of being dropped into a camera that can
  never score anything.
- Process death restoring the stack but not the in-memory bundle. The exercise screen returns to
  Today rather than dereferencing null in front of a patient.

### Outstanding

- Device gate unrun: the phone was locked, so both roles reaching their flow is proven by tests and
  a successful install, not by hand.
- `CameraPreviewScreen` is now ~910 lines holding authoring, calibration, the cyclic set and the
  hold flow. Splitting it per flow is the next structural job.
- Role is per-device, not per-account, until 7.3.

Tests: `NavigationTest`, 10 cases. `./gradlew testDebugUnitTest` - 553 tests, all pass.

---

## v2.2.1 / v7.1.1 - NPU delegate ladder, and a durability defect - 2026-09-05

Source: 4.1, 11.1, 13.1. First session with the target device in hand (iQOO I2501, SM8850,
Snapdragon 8 Elite Gen 5, Hexagon HTP v81, Android 16).

### Measured

- **`Lite/GPU · 29 fps` on the target device.** The 2.2 gate asks for >=25 fps and CameraX supplies
  30, so the pipeline sits one frame off the ceiling the camera imposes. 2.2's frame-rate device
  gate is **passed**; skeleton alignment with a person in frame still remains.

### Added

- **The delegate ladder is now NPU -> GPU -> CPU**, each rung falling through on failure, replacing
  the GPU-then-CPU pair. NPU is attempted only when the QNN dispatch library is present in the APK,
  so the common case costs nothing and does not throw on every cold start. Verified on device: the
  ladder skips NPU and reports `Pose delegate: GPU` with no error.

### Found

- **MediaPipe 1.0.0 does expose `Delegate.NPU`** - checked with `javap` against the AAR rather than
  assumed. But `NpuOptions` requires two mandatory native library directories, MediaPipe ships
  neither, and while the device does carry `/vendor/lib64/hw/libQnnHtp.so` those are absent from
  `public.libraries.txt`, so an app's linker namespace refuses to open them. Engaging the NPU means
  bundling Qualcomm AI Engine Direct binaries into `jniLibs` per ABI - a licensed SDK download, not
  a Gradle dependency - plus most likely an NPU-compiled model rather than the stock float bundle.
- Deferred deliberately, with the ladder left in place so those binaries are the only remaining
  step. The case for it is 10/11.1's - keep the GPU free for the overlay, leave NPU headroom for the
  on-device LLM, and save power over a long session. It is **not** a frame-rate case, and there is
  now a number saying why.

### Fixed

- **7.1's watermark advanced before the write was confirmed.** The sampler set
  `persistedReps = record.reps.size` and *then* wrote, logging failures. A write that threw left
  those reps marked durable while absent, and nothing ever retried them - silently losing exactly
  the rep the write path exists to keep. It now advances one rep at a time, only past reps that
  actually reached disk, so the next tick retries; the `(sessionId, repIndex)` upsert makes the
  retry free.
- The ordering moved out of the composable into `SessionRepository.persistPending`, because the
  ordering *is* the guarantee and it was the one part of the path that could not be checked by
  reading it.

### Tested

- `SessionDurabilityTest`, 5 cases. Four cover the failed-write path using a DAO that delegates to
  real SQLite with a fault injected at one rep index - a pure fake would pass while the schema
  rejected the row. The fifth issues a real `DELETE FROM sessions WHERE id = ?` and asserts the
  cascade, which is the **first** proof `PRAGMA foreign_keys=ON` takes effect: the previous test
  used Room's `clearAllTables()`, a bulk wipe that never touches SQLite's foreign-key machinery.
- `./gradlew testDebugUnitTest` - 543 tests, all pass.

### Outstanding

- 7.1's airplane-mode device gate still unrun.
- Exercise version is hardcoded to 1, so re-authoring the same catalogue exercise overwrites version
  1 rather than creating version 2 - contradicting the immutability the `(id, version)` key exists
  for. Dormant today, belongs with 7.4.

---

## v7.1 - Room as the offline source of truth - 2026-09-05

Source: 13.1, 13.3. Status: in-progress - the airplane-mode device gate needs a phone and has not
been run.

### Added

- **Seven Room tables in a new `data/` package**, and the write path §13.1 is built around: *a Rep
  row as each rep completes, not at set end.* A session killed mid-set keeps every rep that finished
  before the kill, and comes back from `unfinishedSessions()`.
- The session row is opened **before the first rep**, so a set abandoned after zero reps is still a
  fact on disk. "Started and did nothing" and "never started" are different clinical observations
  and only the first one is evidence.
- The `.rpose` bundle is now **written to a file** at `filesDir/exercises/{id}_v{version}.rpose`.
  §13.1's `Exercise.rposePath` has to point at something; until now the bundle existed only in
  memory, and 7.4/7.5 need a real file to upload.

### Decided

- **The rep is written from the 33 ms UI sampler, not the analysis thread.** §12.2 requires nothing
  after `onFrame` to be asynchronous, and disk I/O on the camera thread drops frames - which would
  corrupt the very measurement being saved. The cost is a window of at most one tick in which a kill
  loses the rep just finished; every rep before it is already durable. Losing 33 ms of the newest rep
  beats dropping frames on every rep.
- **`reps` is keyed `(sessionId, repIndex)`**, a natural key rather than a surrogate id, and every
  insert is `REPLACE`. That makes 7.5's retry idempotent for free: replaying rep 4 hits the same row,
  so a duplicated sync is a no-op rather than a duplicated rep. Proven by a test, not assumed.
- **`exercises` is keyed `(id, version)`** per §9.6 immutability, so a session recorded against
  version 1 still names what it was scored against after version 2 is published - which is what
  §13.4 relies on to make the physio-edits-mid-set conflict harmless.
- Three departures from §13.1's field sketch, each documented at the point it occurs: no `User`
  table (7.3 owns identity, and 7.1 must work with nobody signed in), `rejection` stored in place of
  a separate `partial` flag (derivable, and two columns that can disagree are a bug waiting to
  happen), and `jerk` named `smoothnessSparc` (§6.18.5's measure is spectral arc length, which is
  not jerk, and a report labelled "jerk" showing SPARC would mislead the one person qualified to
  read it).

### Build

- Room needs KSP, which needed `android.disallowKotlinSourceSets=false` - AGP 9's built-in Kotlin
  rejects the `kotlin.sourceSets` DSL that KSP registers generated sources through. This is the
  escape hatch AGP's own error message names.
- **Room 2.7.1, not 2.6.1.** 2.6.1 fails under KSP2 with `unexpected jvm signature V`. `SessionDao`
  is an `abstract class` rather than an `interface` for a related reason: the `@Transaction` method
  needs a real body, and a suspend default method on an interface is what KSP chokes on.
- Robolectric added for the DAO tests, pinned to `@Config(sdk = [34])` - it ships android-all jars
  per API level and has none for compileSdk 36. The schema does not vary by API level.

### Outstanding

- Device gate unrun: "entire patient session works in airplane mode" needs a phone and a performer.
- Fault and cue events are not written. Tables and DAO exist; §6.14's detector does not yet surface
  an event stream to persist. Belongs with 8.1, when faults have definitions to fire.
- `unfinishedSessions()` answers the recovery question and nothing asks it. The data survives a
  kill; the affordance to resume does not exist yet.
- `fallbackToDestructiveMigration` is correct pre-release and a bug the moment a build is on
  someone else's phone.

Tests: `SessionPersistenceTest`, 10 cases against a real in-memory Room database rather than a fake
DAO - a fake proves the repository calls what it says it calls, which is not the thing that goes
wrong. `./gradlew testDebugUnitTest` - 538 tests, all pass.

---

## Decision - Backend switched from Firebase to Supabase - 2026-09-05

Source: §4.1, §13. Affects: v7.3, v7.4, v7.5 (not yet built - docs updated, no code exists yet).

§4.1 named Firebase (Auth/Firestore/Storage) for the whole stack. Reopened when the demo video was
confirmed in scope: §9 makes `demo.mp4` part of the `.rpose` bundle, a real one measures roughly
4-22 MB, and that needs real object storage regardless of vendor - it does not fit in a Firestore
document field the way the ~6 KB JSON bundle does (measured directly: 14 features, 4-item watch
list, zipped bundle was 6,279 B). Once object storage is required either way, Firebase Storage's
requirement of a card on the Blaze plan and Supabase's card-free 1 GB free-tier storage stopped being
a wash - that was the deciding fact.

Verified before committing, not assumed: Supabase's free plan needs no card (confirmed against
current pricing); free projects auto-pause after 7 days of no database activity, data frozen and
restorable for up to a year, not lost; the Kotlin client (`supabase-kt`) is community-maintained
under `jan-tennert`, not first-party, but actively updated as of mid-2026.

Chosen over the alternative of a Firebase/Supabase hybrid (Firebase Auth+Firestore, Supabase Storage
only) because one vendor is less integration surface than two, and Postgres is a better fit for
§6.21 progression queries than Firestore's document model was ever going to be.

`docs/iterations/v7.3-firebase-auth.md` renamed to `v7.3-supabase-auth.md`. §13.1's Room schema
(local, source of truth) is untouched - it was already vendor-independent. §13.2's Firestore mirror
is redrawn as Postgres tables in v7.4/v7.5, with the same information and the same offline-first,
last-write-wins design §13.3-13.4 specify.

Outstanding: the auto-pause is a live operational risk for a project rehearsed once and presented
weeks later - noted in v7.5, not yet mitigated.

---

## v6.4 - Four demo exercises - 2026-09-05

Source: 3.3, 8, 17. Status: in-progress - the device gate needs a performer and has not been run.

### Added

- **A catalogue of §3.3's four exercises** in a new `catalog/` package: seated knee extension,
  standing hip abduction, wall sit, sit to stand. It is descriptive, not prescriptive - it supplies
  no thresholds and no tolerances, because every number the pipeline scores against still comes from
  the physio's own recording. What it supplies is what a recording cannot contain: the name, the
  scoring path, where the phone goes, and what it is unsafe to keep doing.
- **The exercise is chosen before recording.** The choice is what routes to the cyclic or the hold
  path, so it has to come first - discovering that the wall sit needed the hold flow only after
  eight reps have been refused is a worse way to find out. The picker disables the wrong record
  button rather than letting the recording be refused a minute later.
- **Catalogue conformance**, reported on the review screen as a warning and never as a rejection.
  §3.8 rejects recordings that cannot produce a usable reference, which is a property of the
  recording alone. These are recordings that are perfectly usable but appear to be of something
  else - the physio picked the wrong entry, or recorded hip abduction side-on - and only the person
  who pressed record can settle that.
- Setup and safety lines for each exercise, on the screen the physio is looking at while placing the
  stand rather than behind an info button nobody presses.

### Changed

- The bundle takes its §9.1 identity from the catalogue: `id`, `name`, `body_position` and the whole
  `prescription`. Every bundle before this was `rp_local` / "Recorded exercise" / 12 reps.
- `required_view` still comes from **the view actually recorded**, not from the catalogue. Overriding
  it would make calibration demand a view the reference was not built in, which is worse than a name
  that disagrees with a plane. The mismatch is warned about instead.

### Decided

- **`expectedDominant` is a set, not one id.** Sit-to-stand is genuinely a hip, knee and trunk
  movement and which one has the largest normalised range depends on the performer's proportions.
  Asserting a single one would make a correct recording look wrong.
- **`faults.json` stays empty for all four.** §6.14's definitions are 8.1 and 8.2, and §6.15.1's
  masking table is unvalidated clinical logic pending a physiotherapist review. An exercise with no
  faults still scores, counts reps and drives the quality bar; it simply has nothing to say.
  Inventing placeholder faults would put unvalidated clinical claims in front of a patient.

### Outstanding

- The device gate is unrun: no exercise in this set has been recorded from a real body.
- Hip abduction is the untested case twice over - the only frontal entry, and the only one whose
  dominant feature the pipeline has never once produced.
- The wall sit produces a `HoldReference` and no manifest, so its `hold_seconds` is read by nobody
  until 7.1 stores holds.

Tests: `DemoExerciseCatalogTest`, 11 cases. `./gradlew testDebugUnitTest` - 528 tests, all pass.

---

## v6.3.1 - The hold starts when you reach the position - 2026-09-05

Source: 6.19. Status: shipped. Reported from a device: the hold ended on its own after ~3 seconds.

### Fixed

- **The hold was breaking before it began.** `HoldTracker` started scoring on the frame the patient
  pressed start, while they were still standing up and walking to the wall. Their knee is then tens
  of sigma from a reference that describes a wall sit, so §6.19's break condition was satisfied
  within a second. Reproduced at **1.03 s** before the fix, against three cases: getting into
  position broke, holding 8 degrees off the physio did not, holding on the reference did not - so
  the arrival was the whole cause.
- `HoldState` gained `SETTLING`. Nothing is timed, scored, broken or fitted until every watched
  joint is inside the same band §6.19 scores against, held for `HOLD_SETTLE_CONFIRM_SECONDS`, so
  crossing the pose on the way somewhere else does not start the clock either.
- Drift can only end a hold after `HOLD_DRIFT_MIN_WINDOW_SECONDS` of holding. A rate fitted across
  two seconds is dominated by the patient settling in, and reads as a steep trend that is really a
  transient.

### Added

- **A countdown before the hold recording**, 3 / 5 / 10 seconds, chosen by the physio. It is spoken
  as well as shown, because the point of it is that the physio is away from the phone getting into
  position, and a number only on screen is a number nobody can read. Recording starts automatically
  when it reaches zero.
- The live panel now says "get into the position - the timer starts when you are in it" while
  settling, rather than showing a running zero.

### Reasoning

- **The clock had to move, not the thresholds.** The tempting fix is a grace period or a looser
  break bound, and both would have made a genuinely broken hold harder to detect. The actual error
  was in what was being timed: pressing a button and reaching a wall sit are different moments, and
  only the second one is the hold.
- **An abstaining hold now stays SETTLING rather than HOLDING.** That test changed with the fix, and
  its point survives: a hold must never become BROKEN for want of a camera. Waiting to see the
  position is the honest state when nothing is measurable.

---

## v6.3 - Hold mode, and three bugs it turned up - 2026-09-04

Source: 6.19, 6.12.3. Status: shipped.

### Changed

- `core/live/HoldMode.kt` - §6.19 end to end: reference construction over the trimmed stable window,
  the hold-mode dominant feature and salience substitutions, the stability precondition, live
  z-scoring without phase, break detection, drift detection, and duration.
- New constants: `HOLD_DRIFT_END_SIGMA_PER_SECOND` 0.30, `HOLD_DRIFT_SUSTAINED_SECONDS` 2.0,
  `HOLD_TRIM_SECONDS` 2.0, `HOLD_MAX_DOMINANT_MAD_DEGREES` 8.0, `HOLD_QUALITY_Z` 2.0,
  `HOLD_WATCHLIST_SIZE` 5.
- `HoldModeTest` - 17 cases. Suite is 512.

### Fixed

- **A bumped phone was banking a false "too slow".** §6.20.2's pause withholds frames from the
  engine, but the rep in progress kept its start timestamp, so on resume it completed carrying the
  entire pause in its duration. Measured before fixing: an 8-second pause produced a 10.9 s rep
  refused as `TOO_SLOW`. So a phone knock cost the patient the rep *and* told them they were slow,
  which is false and unactionable. `PhaseTracker` now abandons the rep in progress when the gap
  between frames exceeds `OCCLUSION_IDLE_SECONDS` - the same rule §6.12.3 already applies when the
  person leaves the frame - and the camera screen abandons it on the edge into a pause rather than
  waiting for resume. Reps already completed are kept either way.
- **The hold timer kept running past the break**, so `holdDuration` counted the time the patient
  spent sitting on the floor afterwards. Frozen at the break.
- **A transient read as drift.** §6.19 says the slope must be *sustained* and the first pass ignored
  the word: a least-squares fit over the whole hold spikes the instant a transient lands and only
  flattens once later samples arrive, so a 0.4 s wobble ended a hold the patient had recovered from.
- `ExercisePanel`'s fatigue state was not cleared when a set was finished.

### Reasoning

- **Almost nothing is shared with the cyclic path, deliberately.** §6.19 is explicit that the §6.7.1
  dominant feature and §6.10 salience "would select on noise and produce a meaningless watch list"
  in a hold, because both are defined through range of motion and a hold's range of motion is zero
  by construction. Dominant becomes the joint furthest from neutral; salience becomes tightness,
  `1/sigma`; watch-list filters 1 and 4 drop out as ROM-based. `PhaseTracker` is not used at all,
  and there is no phase-uncertainty inflation because there is no phase to be uncertain about.
- **The stability precondition runs after the dominant joint is chosen.** §6.19's 8 degree MAD bound
  is on `theta_dom`, and which joint that is depends on the offsets from neutral.
- **The two hold bugs were both found by their own tests**, which is the argument for writing the
  break and drift cases as behaviour rather than as coverage: neither would have shown up in a
  smoke test, and both would have been infuriating on a device.

### Device gate

- Built, installed and launched; no crash. **Not run:** hold mode has no UI entry point yet, so a
  wall sit cannot be authored on the device. §3.8 check 2 already "offers hold mode" when ROM is
  under 15 degrees, and that offer is where this should be reachable from.

---

## v6.2 - Fatigue detection, and refusing to detect it - 2026-09-04

Source: 6.18. Status: shipped.

### Changed

- `core/live/Fatigue.kt` - §6.18 end to end: baseline over `Q_2..Q_4` with a MAD scale and its
  floor, one-sided CUSUM, least-squares slope corroboration, per-feature attribution across the
  change point, the ROM/tempo/smoothness signature, and §6.18.6's safety intervention.
- `core/live/Sparc.kt` - spectral arc length, the smoothness term.
- `ExerciseEngine` re-assesses on every completed rep and exposes `engine.fatigue`. A recommended
  stop closes the set and raises a single-frame `FrameResult.safetyStop`.
- `PhraseBook.safetyStop()` in both languages; the dispatcher speaks it ahead of everything else on
  that frame; the panel shows it with the rep the decline began at.
- `FatigueTest` - 26 cases. Suite is 495.

### Deviation from the plan, measured not assumed

§6.18.5 says to truncate the SPARC spectrum "at the frequency where magnitude first falls below
0.05". Taken literally that defeats the metric. A movement spectrum is not monotonic: on a smooth
half-sine rep with a 9-cycle tremor added, the spectrum dips below 0.05 at bin 2 while the tremor
peaks at bin 9, so the literal band stops short of the roughness and the rougher movement scores as
the *smoother* one - backwards from §6.18.5's own statement that "a decrease indicates roughening
movement". Implemented instead as the **last** frequency still above the threshold, which is the
published definition and which makes the plan's claim true. Pinned by a test.

SPARC is also computed on the **speed** profile rather than signed velocity: it normalises by the DC
term, and a rep's signed velocity is positive out and negative back, so its DC term is about zero
and the normalisation divides by nothing.

### Reasoning

- **Nothing rests on one signal.** CUSUM localises *where* a decline began; an independent
  least-squares slope confirms *that* one happened; only their agreement is reported. §6.18.4 is
  explicit that either alone fires on noise. Both are reported separately anyway - a reader who
  cannot see which of the two failed cannot tell a stable set from one that nearly fired.
- **The change point is where the decline began, not where the alarm tripped.** On the safety
  fixture the alarm trips at rep 6, but rep 5 scored 88 against a baseline of 90, which is where the
  sum first leaves zero, so the answer is 5. That distinction is what makes "deteriorated since rep
  5" defensible rather than a vibe.
- **Below six valid reps the answer is "not enough reps", never a change point.** §6.18.1. A change
  point derived from four numbers is worse than none, because it would be believed.
- **The scale floor exists so a tight baseline cannot fire on nothing.** Three near-identical reps
  give MAD = 0, which without the floor collapses k and h to zero and alarms on the next rep a point
  down.
- **The assessment runs live.** §6.18.6 is an intervention - "stop the set, say so aloud, log it"
  cannot be said after the set has already ended - so it is evaluated per completed rep.
- **Announced once, and ahead of the rep count.** A stop that repeats every frame is noise, and
  speaking "eight" over an instruction to stop would read as encouragement to continue.
- **No medical-validity claim.** The spoken line says form has dropped well below where it started
  and to rest. That is something the app measured; injury and diagnosis are not.

### Device gate

- Built, installed and launched on the Motorola Edge 60 Pro; no crash. **Not run:** a rehearsed
  degrading set, confirming the identified onset is plausible.

---

## v5.4.1 - Two checks that could never pass - 2026-09-04

Source: 6.20, 6.12.3. Status: shipped. Reported from a device: five ticks, two stuck.

### Changed

- Fixed: **check 7 compared a degrees-per-second rate against a phi-per-second bound.**
  `V_STALL_PER_SECOND` is 0.05 and §6.12.3 compares it against `dphi/dt`, where phi is the movement
  normalised to its own range. `CalibrationGate` was differencing the raw joint angle instead, so on
  a 90 degree exercise the check demanded 0.05 deg/s where the plan asks for 4.5 - a stillness no
  living person produces. The gate now takes `spanDegrees` and normalises before comparing.
- Fixed: **`required_view` was hardcoded `"sagittal"` for every exercise.** Nothing derived it, so a
  frontally recorded exercise produced a reference whose check 4 the patient could never satisfy -
  it compared their view against a default nobody chose. `AuthoringResult` gained `recordedView`,
  derived as the commonest unambiguous classification over the captured frames, and
  `bundleFromAuthoring` defaults the manifest field to it.
- Fixed: the gate was handed **unfiltered** landmarks. It owns no One Euro filter of its own, and
  check 7 asks whether the patient is holding still, which raw landmark jitter answers "no" to for
  everybody. The gate and the view recheck now take the filtered frame; the engine still takes the
  raw one, because it filters internally.
- `CalibrationState` carries `requiredView`, `viewRatio` and `dominantVelocity`, and a
  `diagnostic()` line logged to `RPCalib` once a second. The panel shows the same numbers.
- Added a **Start anyway** button, which names the checks it is skipping.
- `viewRatio(frame)` extracted from `classifyView`, which now delegates to it.
- `CalibrationTest` - 26 cases. Suite is 469.

### Reasoning

- **Both bugs were the same mistake in different clothes: a number compared against a threshold
  from a different space.** One compared degrees against phi; the other compared a measured view
  against a constant that was never measured. Neither could fail a unit test, because both tests
  supplied the same wrong assumption the code did - the synthetic rig held perfectly still, so a
  zero rate passed any bound, and the gate was constructed with the view the test wanted.
- **The regression test pins the units rather than the symptom.** It drives a 2 deg/s drift, which
  is 0.022 phi/s and inside the bound, and asserts stationary; then drives the same absolute rate on
  a 10 degree exercise and asserts not. A test that only checked "perfectly still passes" would go
  on passing with the bug restored.
- **The gate now reports what it measured, not just its verdict.** A checklist saying "the camera is
  angled wrong" cannot distinguish a badly placed tripod from a reference authored against the wrong
  view. That is precisely the confusion this shipped with, and it cost a device session to unpick.
- **Start anyway is a deliberate exception to "a gate, not a formality."** A gate the patient cannot
  get past strands them whenever a check is wrong about the room rather than about them - which
  this one just was. It is labelled as an override, not as a start button, and it lists what is
  being skipped, so nobody presses it without being told what they are giving up.

### Device gate

- Built, installed and launched on the Motorola Edge 60 Pro; no crash. The seven checks passing
  against a real body still needs a run: `adb logcat -s RPCalib:I` prints a line a second while the
  calibration screen is up.

---

## v6.1 - The rep model CUSUM will read - 2026-09-04

Source: 6.17-6.18, 13.1. Status: shipped.

### Changed

- `core/live/SessionRecord.kt` - `RepRecord`, `FeatureSample`, `SessionRecord`,
  `FeatureAccumulator`. §13.1's `Rep` and `FeatureSample` rows, in memory; no database yet.
- `ExerciseEngine` keeps `repRecords` in place of `(quality, valid)` pairs, accumulates signed z
  per feature across each rep's frames, and exposes `engine.session`, reassigned immutably per
  completed rep on the same discipline as `engine.state`.
- Each record carries index, quality, ROM, duration, phi max, validity, rejection, per-feature
  signed/absolute/max z with a frame count, and the dominant velocity profile.
- Fixed: the engine stored `quality ?: 0.0` per rep.
- `SessionRecordTest` - 22 cases. Suite is 465.

### Reasoning

- **The engine was computing all of this every rep and throwing it away every rep.** ROM, duration
  and per-feature z existed inside `closeRep` and left it as two numbers. §6.18 needs the quality
  series in order, signed z per feature per rep for attribution, and ROM and duration for the
  fatigue signature, so this iteration is mostly about keeping what was already there.
- **`quality` is nullable and stays nullable.** The `?: 0.0` was unreachable for valid reps, so it
  never touched the set median - but it would have gone straight into §6.18.2's baseline the moment
  CUSUM read the records, and a median dragged down by a rep nobody could score invents a collapse
  that never happened. §1.6: absent means no score, never zero. This is the first place that
  distinction has a consumer rather than a principle.
- **Partial reps are excluded from the series rather than zeroed**, for the same reason. §6.12.4
  already decided they are not reps; a zero dropped into the middle would read to CUSUM as a
  catastrophic decline at exactly the moment the app declined to measure anything.
- **Magnitude and direction are both kept.** §6.17.1 scores on `z^2`, so quality is made of
  magnitude; §6.18.5 attributes on *signed* z, because a knee drifting 2 sigma into valgus and one
  drifting 2 sigma out of it are opposite findings that share a magnitude. Storing one would pick a
  side silently.
- **`frames` per sample, so cross-rep means can weight.** A mean over three frames is not evidence
  equal to a mean over ninety, and averaging the averages would say it was.
- **The velocity profile is captured rather than reduced.** SPARC is frequency-domain and has no
  scalar to carry forward in its place. §6.18.5 explicitly permits dropping smoothness; keeping the
  profile leaves that as 6.2's decision instead of one made by default here, for about ninety
  doubles a rep.
- **`hasEnoughRepsForFatigue` counts valid reps, not finished ones.** §6.18.1's floor is about how
  much evidence there is, not how many times the patient moved.

### Gate

- Every §6.18 prerequisite is present and asserted against a real synthetic set, not just against
  hand-built records: ROM positive, duration positive, features non-empty, profile non-empty, and
  `engine.session` agreeing with the `FrameResult` the UI draws.

---

## v5.4 - Calibration is a gate - 2026-09-04

Source: 6.20, 8.2, 14.2. Status: shipped.

### Changed

- **A set no longer starts because someone tapped start.** Accepting a reference now begins
  §6.20's calibration; the set begins when all seven checks pass. `CalibrationGate` drives them
  from the live frame stream, `CalibrationPanel` shows them as §14.2's live checklist.
- The seven: watched landmarks visible for 2.0 s, body filling 50-92% of frame height, centred in
  the middle 60%, the manifest's required view satisfied, phone within 10 degrees of vertical, mean
  landmark presence at least 0.75, and the dominant joint stationary for 1.0 s.
- `sensors/GravitySensor.kt` reads check 5 off the accelerometer, low-passed so a phone being set
  down does not fail for the second it is moving.
- `ViewRecheck` re-classifies the view every 5 s during the set. On drift the set pauses and frames
  stop reaching the engine until the required view is held again for 1 s.
- `PhraseBook` gained the checklist labels and the recalibration line, in English and Hindi.
- `CalibrationTest` - 22 cases. Suite is 443.

### Reasoning

- **The plan's own words are "a gate, not a formality."** Every check therefore has a test that
  fails it *in isolation*: a check that only ever passes alongside the other six proves nothing
  about which one is doing the work.
- **An unread sensor fails check 5 rather than passing it.** §6.6's abstention discipline cuts both
  ways - not knowing is never the same as agreeing - and a gate that silently passes what it could
  not measure is not a gate. Same reason a device with no accelerometer at all is refused rather
  than waved through.
- **The two timed checks reset rather than accumulate.** A patient visible for 1.9 s, gone for one
  frame, then visible for 1.9 s more has not been visible for 2.0 s. Counting true frames instead
  of measuring an unbroken run would let a flickering landmark satisfy a continuity requirement.
- **The recheck is deliberately sparse.** Once every 5 s, and only a *confident* wrong
  classification counts: a frame that cannot be classified - a momentary occlusion, the patient
  mid-stride - is not evidence the phone moved. Checking every frame would pause sets on pose noise.
- **Frames are withheld from the engine while paused.** A rep measured through a camera that has
  moved is not a measurement, and banking it would be worse than losing it. Reps already completed
  are kept: a bump costs the rep in progress and nothing behind it.
- **§6.20.2's ratio was reused, not rewritten.** `classifyView` and its boundaries landed at 3.8 in
  `core/pose/ViewClassification.kt` with `ViewClassificationTest` pinning them. A second
  implementation of the same formula is a second thing to keep correct.

### Device gate

- **Not run.** The phone was disconnected when this landed. Wrong view fails, correct view passes
  and a bump pauses are all proven against the gate in unit tests, which is not the same as proven
  against a room and a tripod.

### Known gap

`requiredView` is always `"sagittal"`: `bundleFromAuthoring` defaults it and nothing yet derives it
from the recording, so check 4 currently enforces a default rather than the physio's intent.

---

## v5.3.6 - Conformance is a pose, not an angle - 2026-09-04

Source: 6.12.4, 6.13. Status: shipped.

### Changed

- **A checkpoint is now credited only when the whole pose was right there**, not just the dominant
  angle. `PhaseTracker.update` takes a `conformant` flag; `ExerciseEngine` computes it from the
  z-scores as "every watch-listed joint but the dominant one is within
  `TRAJECTORY_CONFORMANCE_Z` = 3 sigma of the reference at this phase".
- `CompletedRep` gained `nonConformantFrames`. `FrameResult` gained `skippedFeature`, and a rep
  refused for an omitted joint now names it, on screen and in speech, in both languages
  (`PhraseBook.skippedFeature`).
- Fixed: `maxPhiRatePerSecond` was read **after** `clearRep()` zeroed it, so every rep ever
  completed reported `0.0`.
- Fixed: three `TrajectoryTest` cases guarded their assertions behind `if (rep != null)` on a
  scenario that produces no rep, so the assertions never executed.
- Added `syntheticTwoStepRepetitions` and `syntheticEndPoseDash`, a knee-and-elbow movement whose
  second step can be omitted. New `PoseConformanceTest` (14 cases); suite is 421.

### Reasoning

- **Everything 6.12.4 accepted a rep on was a function of one joint.** Range, duration, checkpoints,
  rate and valid fraction all read phi, and phi is `segmentation.dominantFeature` - the single
  largest swing at authoring. An exercise built from a combination of steps is not one joint, so a
  patient who swept the knee and never bent the elbow moved the dominant joint through every
  checkpoint at a legal speed and was counted. Measured, not inferred: a two-step reference replayed
  with the elbow held still scored 3 valid reps out of 3, identical to the faithful movement, at set
  quality 64.6 against 99.9.
- **Quality was never a gate.** `valid = completed.valid && quality != null` tests for *nullness*,
  never against a threshold, which is why 64.6 counted. Rather than turn quality into a gate - it
  aggregates every joint into one number, so a large error on one joint and small errors everywhere
  look alike - the path check was made per joint, where the physio's own sigma says what "wrong"
  means for that joint.
- **Three sigma, and only ever a floor on crediting.** A joint left out sits at its rest angle while
  the reference has moved on: tens of degrees against a sigma the reference builder floors at 2.5.
  A patient performing the step imperfectly stays well inside. A bucket also stays credited once
  *any* frame in it was conformant, so one jittering landmark cannot cost a good rep.
- **One frame of lag, deliberately.** Z-scores are computed from the phase the same call produces,
  so the current frame's verdict does not exist yet. 33 ms against buckets a quarter of the range
  wide is nothing.
- **Abstention is not disagreement.** A joint the landmarks could not support is absent from the
  z-scores and has no opinion; a frame where nothing could be scored is conformant. Refusing a rep
  for what was never seen is the error 6.6 exists to prevent.
- **Defaults to true, so single-joint exercises are untouched.** When the watch list is the dominant
  feature alone there is no second joint to disagree, and the rep is judged exactly as before.
- **The refusal names the joint.** "Follow the whole movement" is no help to a patient who believes
  they did - they moved the joint they were watching. The joint is named only when a joint is what
  went wrong, never on a rep refused for range.

### Device gate

- Built, installed and launched on the Motorola Edge 60 Pro; no crash. **Not yet confirmed on a
  real body:** that a two-step exercise refuses the omitted step and counts the faithful movement
  has been proven against the engine, not against a patient.

---

## v5.3.5 - Retire the arrow, tighten the trajectory rule - 2026-09-04

Source: 6.12.4, 6.16. Status: shipped.

### Changed

- **The correction arrow is out of the live path.** `FrameResult.arrow`, the overlay, the range
  guidance and the sign-check controls are gone; nothing is drawn over the skeleton.
- `CorrectionArrow.kt`, `ArrowSelector.kt`, `ArrowSignCheck.kt`, `deriveArrowSigns` and the
  `arrow_signs` manifest field are **kept**, with their tests, so the feature can be reinstated
  without redoing it. `camera/ArrowDiagnostics.kt` was deleted (it reads `FrameResult.arrow`, which
  no longer exists) and is recoverable from v5.3.4.
- Added a per-frame trajectory rate bound: phi may not advance faster than
  `TRAJECTORY_MAX_RATE_MULTIPLE` times the reference movement's own tempo on any single frame.
  `CompletedRep` gained `maxPhiRatePerSecond`.
- `TrajectoryTest` grew to 14 cases.

### Reasoning

- **The arrow was removed, not disabled.** A field that is always null and an overlay that never
  draws are worse than neither: they read as working code and go stale. The pure geometry and the
  sign derivation are the parts that were expensive to get right - the measured rotation sign in
  particular - so those stay, and the wiring is what a future iteration re-adds.
- **Checkpoints alone leave a gap the size of a checkpoint.** They are quarter-width, so a patient
  can jump *within* one quarter, satisfy coverage on both legs, and be counted. The requirement is
  that the patient follows the recorded trajectory, which is a statement about every part of the
  path, not about four samples of it.
- **So the second test is on rate, at frame resolution.** The reference covers phi 0 to 1 in half a
  period, giving a mean rate of 2/T. The fastest rep the duration bound accepts runs 2.5x that, and
  a sinusoidal profile peaks about 1.57x above its own mean, so a legitimate sprint reaches about
  3.9x; a jump from one end to the other inside one frame lands above twenty. Five separates them
  with margin, and both ends of that argument are now asserted rather than left in a comment.
- **The two tests catch different things, which is why both are kept.** Coverage catches a patient
  who never visits part of the range on one of the legs; rate catches a discontinuity anywhere,
  including inside a checkpoint. Neither subsumes the other.
- **The rate bound is measured only across adjacent, valid frames.** The frames either side of a
  dropout are not adjacent, so their difference is not a derivative - the first frame back would
  otherwise read as a jump across the part the patient moved through unobserved. §6.6 already says
  the unseen part is the part that cannot be judged; it must not be judged as a skip either. This
  was caught by an existing dropout test, not by reasoning.
- **The rep's opening frame is included in its rate.** A patient who snaps from rest to the end pose
  enters DESCEND on the very step that skipped the movement, so measuring only from the following
  frame would miss the jump that matters most.

### Device gate

- Builds, installs and runs clean on the Motorola Edge 60 Pro. **Pass.**
- A jump to the end pose is refused and a faithful rep counts. **Pending** a run on device.

---

## v5.3.4 - Reps must follow the path, not just reach the end - 2026-09-04

Source: 6.12.4, 6.16. Status: shipped - reported from device use.

### Fixed

- **Jumping straight to the end pose counted as a rep.** 6.12.4 accepts a rep on where it started,
  how far it went and how long it took; none of those describe the path. A patient who snapped to
  the final position, held it, and dropped back satisfied all three. Reps are now also required to
  have been *observed* passing through each quarter of their own range, on both legs of the
  movement, and are refused with `RepRejection.SKIPPED` otherwise.
- **The arrow aimed at the end pose, which is the shortcut's own target.** Range guidance now aims
  one checkpoint ahead, taken from the reference's own curve at that phase, so it paces the movement
  instead of describing its destination.

### Changed

- `CompletedRep` gained `checkpointsVisited` and `checkpointsRequired`; added `RepRejection.SKIPPED`
  with wording in English and Hindi.
- `PhaseTracker` tracks two checkpoint bitmasks per rep, one per leg.
- `ExerciseEngine.rangeTarget` targets `referenceAt(dominant, nextCheckpointPhase(p))`.
- Added `Params.TRAJECTORY_CHECKPOINTS`.
- Added `TrajectoryTest` with 10 cases.

### Reasoning

- **Checkpoints are bucketed over phi, not over the phase index `p`.** `p` runs 0-50 outward and
  50-100 back, so a rep peaking below phi = 1 never produces values near 50 at all: the index has a
  gap at the turnaround whose width is exactly how far short the rep stopped. Bucketing `p` scored
  that gap as a skipped checkpoint and rejected every rep that did not reach the full reference
  range - which is not what a skipped rep is. This was caught by an existing acceptance-boundary
  test rather than by reasoning, which is the argument for having had it.
- **Only the checkpoints a rep fully crosses are required.** A rep peaking at phi = 0.8 grazes the
  top quarter for a frame or two at the turnaround; demanding it on both legs would reject a rep
  whose only fault is stopping slightly short, and stopping short is already RANGE's business.
- **Both legs are tracked separately.** A single combined mask is filled by whichever leg went well,
  so moving out properly and then collapsing straight back would pass.
- **Four checkpoints, sized against the fastest legal rep.** That rep lasts
  `REP_DURATION_MIN_REF_FRACTION` of the reference period - about 9 frames per leg at the device's
  ~15 fps - so phi advances about 0.11 per frame and about 0.17 at the peak of the velocity profile.
  Quarter-width buckets are wider than that largest legitimate step, so being fast cannot look like
  skipping, while jumping end to end misses every bucket between.
- **Duration is judged before the path.** Past the duration bound the sampling genuinely cannot
  separate a continuous sprint from a jump, so "too fast" is the verdict that is certainly true
  rather than the one that merely might be.
- **The guidance target had to move with the acceptance rule.** Aiming at the extremum answers
  "where does this finish", which is exactly the question the shortcut answers correctly. Aiming one
  checkpoint ahead answers "where next", so the arrow holds until that checkpoint is reached and
  then advances - and past the extremum it turns the patient back toward the start on its own.

### Device gate

- Builds, installs and runs clean on the Motorola Edge 60 Pro. **Pass.**
- A jump to the end pose is refused, and a faithful rep counts. **Pending** a run on device.

---

## v5.3.3 - Measure the arrow sign instead of guessing it - 2026-09-04

Source: 6.16. Status: shipped - diagnosed from a live capture on the RMX3997.

### Fixed

- **Every arrow was drawn backwards.** A 1983-frame capture of an arm raise gives the rotation sign
  for `shoulder_flex_active` on the front camera as **-1**, with 593 of 595 usable frame pairs
  agreeing; the app was drawing +1. Signs are now derived from the reference recording rather than
  taken from a table, so this class of error cannot recur for any feature.
- **The arrow blinked near the turnaround.** The same capture shows it present for only 60% of
  mid-rep frames with 40 on/off transitions in about a minute, because the range deadband had a
  single threshold that a decelerating patient crosses repeatedly. Added hysteresis: appear at 15%
  of the movement's range, clear at 8%.

### Changed

- Added `deriveArrowSigns` to the authoring pipeline; `AuthoringResult.arrowSigns` and
  `RposeManifest.arrowSigns` (`arrow_signs` in the JSON) carry it into the bundle.
- `ArrowTarget` gained `sign`; the overlay reads it and `ArrowSignCheck` is now an override for
  confirming the derivation rather than the way signs are determined.
- Added `camera/ArrowDiagnostics.kt`: one line per frame on `RPArrow`, enabled with the sign check.
- Added `Params.ARROW_RANGE_CLEAR_FRACTION`.
- Added `ArrowSignDerivationTest` with 6 cases.

### Reasoning

- **The sign was always measurable; nobody had noticed.** §6.16 says to determine `s` empirically per
  feature and view, and the iteration plan says not to ship a guess - both phrased as though a person
  must sit with a phone and a Flip button. But `s` only answers "when this feature's angle increases,
  which way does the segment turn on screen?", and a reference recording contains both quantities on
  every frame. `sign(sum of d(measured) * d(imageAngle))` extracts it for every feature at once.
- **Deriving beats tabulating on correctness, not just effort.** The sign depends on the camera - the
  front camera mirrors, which flips it - and on `required_view`, which §6.16 says outright. A
  constant table has to enumerate those combinations and be re-verified whenever one changes; a
  measurement taken from the recording the bundle is built from is correct for that bundle by
  construction.
- **A stride of three frames, not adjacent frames.** Adjacent-frame deltas sit on the noise floor:
  the same recording yields 16 usable pairs adjacent against 48 at stride three, all agreeing. The
  threshold was rejecting real motion as noise.
- **A joint that did not move still gets no sign.** The recording has to demonstrate the
  relationship; where it does not, §6.16 draws nothing, the same abstention rule as everywhere else.
- **The geometry was never the problem.** The capture puts the frame-to-frame direction swing at 0.6
  degrees median with the correct sign and 2.0 with the wrong one - smooth either way. Adding
  smoothing would have hidden a sign error behind a filter and left the arrow confidently wrong.
- **The diagnostic stays in the build.** It is how this was found, it costs one log line per frame
  while the sign check is running, and the next sign question is answered by reading it rather than
  by reasoning about mirroring.

### Device gate

- Rotation sign for `shoulder_flex_active`, front camera: **-1, measured, 100% agreement.**
- Arrow points toward the correction, and holds without blinking. **Pending** a confirming run.

---

## v5.3.2 - Range guidance and notes on reps that counted - 2026-09-04

Source: 6.12.4, 6.15.1, 6.16. Status: shipped - found by running an arm-raise on a physical phone.

### Fixed

- **A rep could count and be told nothing, however wrong it was.** 6.12.4 decides only whether a rep
  is a rep. A patient lifting well past the recorded range cleared acceptance, watched the quality
  bar drop, and was counted in silence - which teaches them the overshoot was correct. Added
  `RepNote`: the rep still counts, and the app now says what to change, in text and aloud.
- **The arrow could never address the joint being exercised.** Phase is derived from the dominant
  feature, so that feature is compared against itself and its z is ~0 by construction. It could not
  win a 6.16 deviation contest at any range, which is why an arm raise put an arrow on the trunk.
  Added range guidance: for the dominant feature the meaningful question is not "where should this
  be right now" but "where is the movement meant to end".
- **The arrow gave no help getting to the target.** It now points toward the extremum while the
  patient is travelling out, holds while they are short of it, and reverses toward the start once
  they turn around.

### Changed

- Added `RepNote` (`Overshot`, `ShortOfRange`, `Compensation`) and `FrameResult.lastRepNote`.
- `PhraseBook` gained `repNote` and `featureName`, in English and Hindi.
- Added `ArrowKind`, `ArrowTarget`, `lengthFractionForZ`, `lengthFractionForRange`;
  `correctionArrow` takes a length fraction rather than a z, and `FrameResult.arrowFeature` became
  `FrameResult.arrow`.
- Range arrows draw cyan, compensation arrows yellow, unverified-sign arrows magenta.
- Added `Params.ARROW_RANGE_MIN_FRACTION`, `ARROW_RANGE_FULL_FRACTION`, `REP_OVERSHOOT_TOLERANCE`,
  `REP_FULL_RANGE_FRACTION`.
- Added `RepNoteTest` with 11 cases; `CorrectionArrowTest` migrated.

### Reasoning

- **The dominant feature's silence was structural, not a tuning problem.** 4.1 derives phase from
  it; 4.2 then scores it against the reference *at that phase*. The measurement is therefore
  compared against itself, and no threshold change could ever have made it eligible. Recognising
  that is what separates the two kinds of arrow: deviation asks "is this joint where it should be
  now", which is meaningless for the joint that defines "now", while range asks "did the movement
  get there", which is the only question that has content for it.
- **This generalises past the arm raise that exposed it.** Every exercise has a dominant feature and
  every dominant feature has this property, so before this change the arrow was structurally unable
  to address the exercised joint in *any* exercise - it could only ever comment on compensations.
- **phi is the right signal for overshoot, for the same reason.** It is normalised so 1.0 is the
  reference extremum whatever the joint, exercise or units, so "past what the physio recorded" is
  one comparison that needs nothing authored.
- **Notes stay geometric, and that is the 6.15.1 line.** Overshoot, short range and "another joint
  moved" are statements about the reference the physio actually recorded. Naming a *diagnosis* would
  need 8.1's authored fault definitions; naming a *measurement* does not.
- **Range outranks compensation.** Not reaching the range is a failure to do the exercise; a
  compensation is a failure to do it cleanly. Telling a patient to keep their torso still while they
  are still not lifting the arm inverts the order those need fixing in, and 6.15's arbitration
  argument says only one instruction may be live at a time.
- **The note is latched, like the rejection reason.** `completedRep` lives one frame and the UI
  samples at 33 ms; this is the third time that has bitten, so it is now the default assumption for
  anything the engine emits at a rep boundary.
- **The dominant feature is excluded from compensation notes.** Its z is an artefact of the phase
  derivation, and reporting it would blame the patient for how the measurement is built.
- **Range guidance is suppressed between reps**, where phase is pinned near the rep start: a target
  there would be an instruction to start moving that the patient never asked for.

### Device gate

- Builds, installs and runs clean on the RMX3997. **Pass.**
- Arrow addresses the exercised joint. **Pending** a run with the sign check on.
- Per-feature rotation signs. **Still not determined.** Unchanged from v5.3.

---

## v5.3.1 - Hold the arrow still - 2026-09-04

Source: 6.16, 6.14.1, 6.15.2. Status: shipped - found by running v5.3 on a physical phone.

### Fixed

- **The arrow landed on whichever joint had the largest raw z, which was often not the joint being
  exercised** - it appeared on a stationary leg during an arm raise. Selection now ranks on
  `|z| x watch-list weight`, the same ranking 6.15.2 already uses to choose between spoken cues.
- **The arrow strobed on and off at frame rate.** There was no hysteresis: one threshold decided
  both entry and exit, so a feature sitting on the line flickered every frame. Entry is now `Z_ON`
  after `FAULT_ENTRY_DWELL`, exit is `Z_OFF` after `FAULT_EXIT_DWELL`.
- **The arrow was drawn between reps**, where phase is pinned near the rep start and every z is
  measured against a reference point the patient is not trying to be at yet. Suppressed unless a rep
  is under way, and while occluded.
- **Two comparable features traded the arrow back and forth.** A challenger must now beat the held
  feature by `ARROW_SWITCH_MARGIN` and stay ahead for `ARROW_SWITCH_DWELL_SECONDS`.

### Changed

- Added `core/live/ArrowSelector.kt`; `ExerciseEngine` owns one and resets it with the rest of the
  per-frame state.
- Removed the `|z|` gate from `correctionArrow`: visibility is the selector's decision now.
- Removed `Params.ARROW_MIN_Z`; added `ARROW_SWITCH_MARGIN` and `ARROW_SWITCH_DWELL_SECONDS`.
- Added `ArrowSelectorTest` with 12 cases; `CorrectionArrowTest` updated.

### Reasoning

- **This is 6.14.1's argument arriving one iteration late.** That section says the dwell "is the
  difference between a demo that feels magical and one that feels broken", and it was written about
  fault detection. The arrow is a second consumer of the same z-scores and needed the same
  treatment; picking `argmax |z|` fresh every frame reproduced precisely the failure 6.14.1 warns
  about.
- **Ranking by weight is what makes the arrow belong to the exercise.** Raw z treats every
  watch-listed feature as equally relevant, so any joint that happens to sit off-reference can win.
  The watch-list weight already encodes how much a feature has to do with this movement, and 6.15.2
  reaches for it for exactly this reason when arbitrating speech.
- **The z-gate had to leave `correctionArrow`, not just be lowered.** With a gate there the arrow
  blanked the instant the patient improved, which cancelled the exit dwell and brought the flicker
  straight back. Splitting the decision - the selector decides *whether*, the geometry decides
  *how* - keeps each answerable for one thing.
- **6.16's 0.2 length floor stopped being dead code as a result.** A corrected patient inside the
  exit dwell has a falling |z|, and the floor is what keeps the shrinking arrow visible instead of
  letting it collapse to a dot before it goes. The v5.3 entry recorded the clamp as unreachable;
  that is no longer true, and it now does the job the section wrote it for.

### Device gate

- Builds, installs and runs clean on the RMX3997. **Pass.**
- Arrow stays on the exercised joint and does not flicker. **Pending** - needs a run with the sign
  check on.
- Per-feature rotation signs. **Still not determined.** Unchanged from v5.3.

---

## v5.3 - Correction arrow - 2026-09-04

Source: 6.16. Status: in-progress - geometry and sign-check built and unit tested; the per-feature
rotation signs are still unverified, so no arrow is shown to a patient. Ghost not started.

### Changed

- Added `core/live/CorrectionArrow.kt`: `Point2`, `Arrow`, `rotate2D`, `correctionArrow`,
  `arrowLandmarks`, `ArrowSigns`.
- Added `core/live/ArrowSignCheck.kt`: the on-device procedure for determining `s`.
- `FrameResult` gained `activeSide` and `arrowFeature`.
- `CameraPreviewScreen` draws the arrow on the same letterboxed rectangle as the skeleton, and has
  "Check arrow" / "Flip" controls during an exercise.
- Added `Params.ARROW_MAX_PX`, `ARROW_MIN_LENGTH_FRACTION`, `ARROW_Z_FULL_SCALE`, `ARROW_MIN_Z`.
- Added `CorrectionArrowTest` with 19 cases.

### Reasoning

- **Everything happens in image space, and that is 6.16 correcting itself.** An earlier draft said
  to compute the target in world space and project it back with "the same transform used for
  skeleton drawing". That transform does not exist: MediaPipe emits world landmarks and image
  landmarks as two independent outputs with no intrinsics relating them. 6.16 says implementing the
  original wording is impossible and that discovering it late costs a day, so this is written the
  way the section was corrected, not the way it was first drafted.
- **The pixel/normalised distinction is load-bearing.** Normalised image coordinates are scaled
  independently by width and height, so rotating in them shears the limb instead of turning it.
  `correctionArrow` takes pixels and the overlay converts once, on the same letterboxed rectangle
  the skeleton uses - otherwise the arrow lands near the limb rather than on it.
- **No sign is shipped.** 6.16 requires `s` per feature and view to be determined empirically and
  says the failure mode of guessing is "an arrow that confidently points the wrong way"; the
  iteration plan repeats it as a user action. `ArrowSigns` is therefore empty, `correctionArrow`
  returns null without one, and a test fails the moment anyone fills the table in without doing the
  device check. Showing no arrow costs a hint; showing a backwards one coaches the patient into the
  fault.
- **So the check itself had to be built.** The gate is a procedure - deviate a feature deliberately,
  confirm the arrow points toward correction - which needs somewhere to happen. `ArrowSignCheck`
  draws with a *candidate* sign, only while a tester has explicitly started a check, and never
  writes to `ArrowSigns`.
- **Out-of-plane features draw nothing at all.** `TRUNK_ROTATION` is a rotation about the
  longitudinal axis; no in-plane arrow can depict it. `PELVIC_OBLIQUITY`, `LUMBAR_FLEXION` and
  `SHOULDER_ELEVATION` are not single-segment rotations either. An arrow for these would be a
  confident picture of nothing.
- **One arrow, not one per joint.** 6.15's arbitration argument - competing instructions cancel -
  applies to the visual as much as to speech, so the engine publishes the single watch-listed
  feature furthest outside its band.
- **6.16's 0.2 length floor is dead code, and is recorded as such.** It binds below |z| = 1.2, but
  arrows are suppressed below `ARROW_MIN_Z` = 1.5 (`Z_OFF`, the threshold at which a fault stops
  being a fault). The clamp is kept because it is what the section specifies, and a test states that
  it is unreachable so the next reader does not have to work out which of the two is wrong.
- **The ghost was not started.** The plan gates it explicitly: "Add chained ghost only after arrow
  signs pass." The signs have not passed.

### Device gate

- Builds, installs and runs clean on the RMX3997. **Pass.**
- Per-feature rotation signs determined empirically. **Not started** - needs a recorded reference
  and a person to deviate each feature and press Flip until the arrow points toward the correction.
- Ghost overlay. **Blocked** on the signs, by design.

---

## v5.2.1 - Speech the app can actually use, in English and Hindi - 2026-09-04

Source: 12.4-12.5. Status: in-progress - built and unit tested; device gate pending, phone was
disconnected before the install.

### Fixed

- **Haptics ran on the wrong thread and therefore never fired.** `CueDispatcher` is driven from the
  camera analysis thread and `View.performHapticFeedback` touches view state, so every buzz was
  silently dropped. `ViewHaptics` now posts to the view's own thread.
- **`HapticFeedbackConstants.CONFIRM` and `REJECT` are API 30; `minSdk` is 24.** Guarded, with the
  nearest older constant on older phones rather than no feedback at all.

### Changed

- Added `core/live/Phrases.kt`: `SpeechLanguage` (English, Hindi), `PhraseBook`, `EnglishPhrases`,
  `HindiPhrases`.
- `CompletedRep.partialReason` (a sentence) became `CompletedRep.rejection: RepRejection` (a code);
  `FrameResult.lastPartialReason` became `lastRejection`.
- `CueDispatcher` now also speaks the app's own announcements: rep counted, rep refused with its
  reason, set complete. `Haptics` gained `onRepCounted`, `onRepRejected`, `onSetComplete`.
- `GoogleTtsSpeaker` takes a `SpeechLanguage`, tries its locales best-first, and can switch on a
  live engine.
- `ExercisePanel` renders through the phrase book; added a "Voice: English / हिन्दी" toggle.
- `CueDispatcherTest` grew to 11 cases; `PhaseTrackerTest` and `TtsStateTest` updated.

### Reasoning

- **Nothing spoke because nothing had anything to say, and that was not a bug.**
  `bundleFromAuthoring` ships `FaultSet(emptyList())`, so `FaultDetector.update` iterates an empty
  list, `CueArbiter.select` returns on `active.isEmpty()`, and `cue`, `faultOnsetIds` and
  `faultClearIds` are empty on every frame. The dispatcher was being called correctly and handed
  nothing. Waiting for 8.1 would have left 5.2 unverifiable on a real phone for several iterations.
- **So the app was given its own voice, distinct from cues.** Cue text is a clinical claim a physio
  authored (8.1); announcements are the app's own messages about the set, and every one of them was
  already on screen. Speaking what is already displayed adds no unvalidated claim - which is the
  line 6.15.1 draws - and makes the speech path exercisable now.
- **The rejection reason became a code because it has to exist in two languages.** A sentence baked
  into `PhaseTracker` cannot be translated without the phase tracker knowing about languages, which
  it has no business knowing.
- **The rep tick buzzes even when speech is unavailable.** 12.5's argument - the patient is holding
  the phone or has it in a pocket and is watching their own knee, not the screen - applies at least
  as strongly to "did that rep count?" as to a fault edge.
- **Hindi is written to be spoken, not transliterated.** "Go all the way down" is
  "पूरा नीचे जाइए", not a word-for-word rendering a TTS voice would stumble over, and rep counts are
  spoken as words up to twenty so the engine does not read digits in English.
- **Language falls back through a list of locales.** Google's engine ships Hindi but the voice files
  are a separate download; trying `hi-IN` then `hi` before declaring the language missing avoids
  reporting a setup problem the device does not actually have.
- **Language switches mid-set.** A patient who cannot follow the cue should not have to restart the
  set to change it.

### Device gate

- Pending: the phone was disconnected before this build could be installed. Needs: Hindi voice data
  present or the setup message shown, a counted rep both spoken and felt, and a refused rep giving
  its reason aloud.

---

## v5.2 - TTS and haptics - 2026-09-04

Source: 12.4-12.5. Status: in-progress - engine and voice data confirmed on device; the spoken-cue
gate cannot be met until 8.1 defines faults.

### Changed

- Added `core/live/CueDispatcher.kt`: `CueDispatcher`, and the `Speaker` and `Haptics` ports.
- Added `speech/GoogleTts.kt`: `GoogleTtsSpeaker`, `TtsState`, `ttsSetupMessage`.
- Added `speech/ViewHaptics.kt`: `CONFIRM` on fault onset, `CLOCK_TICK` on resolution.
- `FrameResult` gained `faultOnsetIds` and `faultClearIds`.
- `AndroidManifest.xml` declares the `TTS_SERVICE` intent query.
- `CameraPreviewScreen` owns the speaker, shuts it down on dispose, and shows the setup message.
- Added `CueDispatcherTest` (6 cases) and `TtsStateTest` (4 cases).

### Reasoning

- **The dispatcher is driven from the analysis thread, not from the UI's sampled state.** A cue and
  a fault edge are both live for exactly one frame. The screen samples at 33 ms, so wiring speech to
  what the UI sees would drop most cues at random - the same class of bug as the partial-rep reason
  in v5.1.1, which was computed every rep and displayed almost never.
- **The dispatcher does not re-decide whether to speak.** 6.15 already ran the escalation ladder and
  both cooldowns before a `Cue` exists. A second opinion here would silently override arbitration,
  so the dispatcher only refuses an exact repeat of the utterance it last spoke.
- **A cue that arrives before the engine is ready is dropped, not queued.** 6.15's cooldowns are
  wall-clock. Replaying a backlog once TTS warms up would talk over a movement the cue no longer
  describes, which is worse than the silence it was trying to fix.
- **The engine is pinned to `com.google.android.tts` with no fallback**, per 12.4. Another engine has
  different voice coverage and different latency; a cue that lands late or mispronounced mid-rep is
  worse than one the patient reads. When it is absent, speech is disabled and the reason is shown.
- **A missing engine and missing voice data are separate states** because they need different
  instructions: one is a Play install, the other a download inside Android's TTS settings.
- **The locale is set only after a successful init.** Before that the engine has no voice list to
  answer against, so the `setLanguage` result carries no information.
- **The `<queries>` entry is load-bearing, not boilerplate.** Android 11 and newer hide other
  packages, and without it `TextToSpeech` cannot see Google's engine and reports it missing on a
  device where it is installed - a failure that would have looked like the user's setup problem.
- **Haptics do not depend on TTS.** 12.5 exists for the patient holding the phone or with it in a
  pocket, which is exactly the case where speech may also be unavailable, so the two paths are
  independent and tested to stay that way.
- **Both haptics use the global setting rather than overriding it.** A patient who turned haptics off
  has said what they want, and this is a redundant channel, not the primary one.
- **The set-complete state suppresses onsets, clears and cues**, so a finished set cannot keep
  buzzing or coaching at someone who has been told to stop.

### Device gate

- `com.google.android.tts` present on the RMX3997. **Pass.**
- en-IN voice data present - the app reaches `TtsState.READY` and shows no setup message. **Pass.**
- Pose pipeline unaffected: `Lite/GPU`, 720x1280, 15 fps, 75 ms. **Pass.**
- First real cue speaks, and a newer cue interrupts a stale one. **Blocked.** `bundleFromAuthoring`
  ships `FaultSet(emptyList())` by design, so a locally authored reference can raise no fault and
  therefore emit no cue. This gate needs 8.1's fault definitions; the path is covered by
  `CueDispatcherTest` in the meantime.
- Onset and resolution haptics. **Blocked**, for the same reason.

---

## v5.1.1 - First device run fixes - 2026-09-04

Source: 6.2, 6.12.4, 12.2. Status: shipped - found by running v5.1 on a physical phone.

### Fixed

- **The live engine scored the wrong limb.** `resolveActiveSide` picks the side the movement
  happened on, and `AuthoringResult` then discarded it: nothing carried it into the bundle, so
  `ExerciseEngine` fell back to a hardcoded `Side.RIGHT`. `AuthoringResult.activeSide` now exists,
  the manifest gained `active_side`, and the engine defaults to the side the bundle was authored on.
- **A single dropped detection frame wiped the whole set.** `CameraPreviewScreen` called
  `ExerciseEngine.reset()` whenever the detector returned no person, which cleared the completed-rep
  list and not just the rep in flight. Added `onPersonLost()`, which abandons the current rep and
  keeps the set.
- **The set never ended.** Nothing compared the rep count to the prescription, so a patient given 12
  reps kept being counted and cued indefinitely. Added `ExerciseStatus.SET_COMPLETE`; the engine
  stops banking, scoring and cueing reps once the prescribed count of *valid* reps is reached.
- **`prescribedReps` was hardcoded to 12 in the UI** while the engine read the bundle, so the two
  could disagree about the same set. The screen now reads `manifest.prescription.reps`.
- **The reason a rep did not count was computed and never shown.** `CompletedRep.partialReason` is
  live for exactly one frame and the UI samples at 33 ms, so in practice the patient saw the counter
  stop with no explanation. `FrameResult.lastPartialReason` latches it until another rep finishes.
- **The screen slept mid-set.** `MainActivity` sets `FLAG_KEEP_SCREEN_ON`.

### Changed

- `FrameResult` gained `phi` and `velocity`, and the exercise screen shows
  `STATE - phi - v - p` under the fps line.
- Added `Map.strOrNull` to the JSON accessors, for manifest fields that post-date a bundle.
- Added `ExerciseEngineTest` case for set completion.

### Reasoning

- **A wrong-limb score is worse than no score, because it looks right.** The `*_ACTIVE` feature ids
  are side-agnostic by design, which is what let the side get lost silently. Measuring the still
  limb against the moving limb's reference parks phi near 0 forever: no rep ever starts, while the
  quality bar reads near 100, because a limb sitting at reference phase 0 genuinely does match the
  reference at phase 0. The observable symptom - full confidence, zero reps - names neither cause.
  The side is now stored in the manifest rather than passed at the call site, because a bundle that
  cannot say which limb it describes is not scoreable on its own terms.
- **Bundles written before `active_side` read back as `right`**, which is exactly what the engine
  assumed when they were written. Defaulting preserves their behaviour instead of reinterpreting
  old references.
- **Losing sight of the patient must not undo work they already did.** 6.12.3 discards the rep in
  progress because the unseen part is the unscoreable part; that argument does not reach the reps
  already banked. Zeroing the counter there makes it look permanently stuck, which is the same
  visible failure as not counting at all.
- **Only valid reps end the set.** Counting partials toward the prescription would let a patient
  finish a set without completing a single full-range rep, which is the fatigue case 6.12.4 exists
  to catch.
- **Reps past the prescription are discarded, not counted.** Continuing to count is the app failing
  to tell the patient they are done - it turns a prescribed set into an open-ended one.
- **phi is on screen because "it isn't counting" is not a diagnosis.** phi has to cross
  `PHASE_ENTRY` and reach `REP_ACCEPTANCE_PHASE_MAX` for a rep to register, and both thresholds were
  invisible from the patient's side of the camera.

---

## v5.1 - Minimal exercise UI - 2026-09-04

Source: 12.2-12.3, 14.2. Status: in-progress - device gate needs a phone.

### Changed

- Added `core/live/ExerciseEngine.kt`: `ExerciseEngine`, `FrameResult`, `ExerciseStatus`,
  `QualityBand`, `qualityBand`.
- Added `core/reference/BundleAssembly.kt`: `bundleFromAuthoring` and `referenceVelocity`.
- `AuthoringResult` now carries `meanVisibility`.
- Added `exercise/ExercisePanel.kt`: quality bar, rep counter, set score, status line, cue text.
- `ReferenceReviewScreen` gained an optional "Use this - start exercise" action, shown only on an
  accepted reference.
- `CameraPreviewScreen` drives the engine from the analysis thread and samples its published state
  at 30 Hz.
- Added `ExerciseEngineTest` with 11 cases.

### Reasoning

- **This is the first iteration where the two halves of the app have to agree.** Authoring produced a
  `Reference`; the live side consumed an `RposeBundle`; nothing joined them. `bundleFromAuthoring` is
  that join, and `ExerciseEngineTest` authors a reference from the 3.1 generator and then replays the
  same movement through the live pipeline. Every stage was already unit tested; this is the first
  test that can catch two stages disagreeing about the same recording.
- **`FrameResult` is immutable, and that is not stylistic.** 12.2 hands it to Compose. A mutable
  frame result read during recomposition is a race by construction, and the analysis thread writes
  it on every frame.
- **The engine takes the raw frame, not the screen's filtered one.** The screen filters for the
  skeleton overlay; the engine owns its own One Euro filter so it can be tested without a camera.
  Feeding it the already-filtered frame would double-filter and quietly change the tuning that 2.3
  measured on the device.
- **`v_ref` is derived, not guessed.** 6.13.2 wants the reference movement's own median `|dphi/dt|`.
  Phase advances 100 units per reference period and the stored derivative is `dmu` per bin, so
  `dphi/dt = (dmu/dp) * (100 / T) / span`. A guessed constant here would silently mistune 4.2's
  tolerance widening for every exercise.
- **The bundle ships with an empty fault set.** Fault definitions and their wording are 8.1 and 8.2.
  An exercise with no faults still scores, counts reps and drives the bar; it simply has nothing to
  say. Inventing placeholder faults to make the demo louder would put unvalidated clinical claims in
  front of a patient, which 6.15.1 is explicit about.
- **The quality bar draws nothing when there is no score.** 1.6, 4.2 and 4.3 all refuse to emit a
  zero for "we could not see you", so the bar must not draw one either. An empty bar and a zero bar
  mean opposite things to a patient.
- **Partial reps are shown, not hidden.** 6.12.4 tells the patient the rep did not count; hiding it
  would leave the counter looking stuck for no visible reason.
- **`statusLine` and `qualityBand` live outside Compose** so 5.1's semantics can be asserted in a JVM
  test at all. 12.3 calls the bar "the feedback loop the patient can actually see working", which
  makes its thresholds behaviour rather than styling.

### Finding: a smoothly cycling patient may not have reps counted

6.12.3 completes a rep on `phi < 0.15` **and** `|v| < V_STALL`, held for 150 ms. On an unbroken
2 second cycle the velocity passes through the 0.05 stall band in well under one frame at 30 fps, so
no frame satisfies both conditions and the tracker sits in ASCEND indefinitely. The diagnostic run
for this iteration showed exactly that: 69 frames in DESCEND, then 354 consecutive frames in ASCEND,
zero reps closed, on a stream containing six complete repetitions.

Real reps have a beat at each end, and the test fixtures now include one - a 0.5 s pause at both
extrema, after which all six reps are counted and score above 80. But the fixture change documents
the risk rather than removing it: a patient cycling continuously, or a fast rep at a high frame rate,
can still fall through.

Left as the plan specifies rather than tuned here. Lowering `V_STALL` or dropping it from the
condition reopens the turnaround chatter that 6.12.3's deadband exists to prevent, and 4.1's device
gate - 20 real reps counted with no phantom reps - is the measurement that should decide it. Flagged
there.

### Note: the dominant joint cannot be faulted meaningfully

The 5.1 fault test uses a threshold of 0.1 sigma, far below anything clinical, and that is not
laziness. Phase is *derived from* the dominant joint, so that joint sits near its own reference by
construction no matter how wrongly the patient moves - its z stays small and a realistic 3.0
threshold would never fire. Real faults live on the other watch-listed joints, which the 3.1
generator holds static. 4.4 owns detection thresholds; what 5.1 needs to prove is that a cue reaches
the frame state at all and that "speak exactly one" survives the trip through the engine.

### Build note

OneDrive interference twice more, and one full-suite run took **58 minutes**. Occurrences six and
seven. This is now the dominant cost of the project: every iteration pays it, the failures look like
code defects, and the slowdown makes diagnosing a real failure expensive enough to encourage
guessing. Moving the repository off OneDrive is overdue.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 329 tests, 0 failures
  (11 in `ExerciseEngineTest`).
- `gradlew.bat compileDebugKotlin --no-daemon` - BUILD SUCCESSFUL.
- Device gate - quality bar responds without blocking inference: **not run**. Needs a phone.

---

## v4.5 - Cue arbitration - 2026-09-04

Source: 6.15, Appendix A. Status: complete - device gate blocked on 5.1 and 5.2.

### Changed

- Added `core/live/CueArbiter.kt`: `CueArbiter`, `MASK_RULES`, `MaskRule`, `Cue`, `CueLevel`,
  `CueEvent`, `assertCueSetup`.
- `ActiveFault` gained `currentAbsZ` alongside `peakAbsZ`.
- No new constants; both cooldowns were already in section 7.
- Added `CueArbiterTest` with 22 cases.

### Reasoning

- **The mask rules are typed, which is stronger than the assertion the plan asks for.** 6.15.1 warns
  that an earlier draft used shortened feature names matching no feature, and that this would have
  made every rule silently inert: the lookup returns null, no mask applies, and no error ever
  appears. It prescribes a startup `require` over a string map. Using `FeatureId` instead moves that
  failure from runtime to compile time, so the class of bug the assertion existed to catch cannot be
  written. The five rules are still pinned value-for-value by a test, because transcription is the
  other half of that risk.
- **The startup assertion is kept where it can still fail.** A fault's `masked_by` is a list of free
  text fault ids out of the `.rpose` bundle, and one naming a fault that does not exist fails exactly
  the way 6.15.1 describes. `assertCueSetup` throws on it at startup, not at first use.
- **Ranking moved to current severity.** 4.4 recorded only the peak `|z|` since onset. Ranking on
  that would keep cueing the joint that was briefly worst several seconds ago over the one that is
  worst now. `ActiveFault` now carries both; the peak is still there for the effect log.
- **The per-fault cooldown skips the fault, the global cooldown skips the frame.** Five seconds of
  silence about the knee is not a reason to stay silent about the back. Read the other way round,
  one fault would gag the whole app for five seconds at a time.
- **A frame silenced by the global cooldown does not consume an escalation step.** Otherwise the
  ladder would climb at the frame rate and a patient would be told to end the set within a second of
  their first mistake. The order of the two checks is what guarantees it, so a test pins it.
- **The escalation ladder is per set and per fault.** 6.15.4: never nag. A patient cued on every rep
  stops listening by rep four, and a fault surviving three cues means the exercise is too hard for
  them today - information for the physio, not a cue problem. After the fourth occurrence the fault
  is retired for the set rather than repeating the end-of-set line.
- **A cue that did not land retries the wording before climbing the ladder.** 6.15.5's first use of
  the effect log. Escalating instead would punish the patient for our phrasing.

### Outstanding: the compensation table is unvalidated

4.5's own plan says to verify the table with a physiotherapist and to "treat it as unvalidated
clinical logic until reviewed". It is transcribed from 6.15.1 and pinned by a test; that is
transcription, not review. Marked in source. No claim should be made about compensation-chain
correctness before that conversation happens.

This is also why 6.15.1 forbids handing compensation reasoning to the on-device model: it is the one
place in the app where being confidently wrong could cause harm rather than annoyance.

### Build note

OneDrive interference twice more, this time failing `packageDebugResources` with "Cannot snapshot
... values.xml: not a regular file". Deleting `app/build` and `.kotlin` clears it. Fourth and fifth
occurrences.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 318 tests, 0 failures
  (22 in `CueArbiterTest`).
- `core` verified free of Android imports.
- Device gate - correct cue direction, one at a time, no chatter: **not run**. Needs cues on screen
  and audible, which is 5.1 and 5.2.

---

## v4.4 - Fault state machine - 2026-09-04

Source: 6.14. Status: complete.

### Changed

- Added `core/live/FaultDetector.kt`: `FaultDetector`, `ActiveFault`, `FaultUpdate`,
  `unbindableFaults`.
- No new constants; `Z_ON`, `Z_OFF` and both dwell times were already in section 7.
- Added `FaultDetectorTest` with 19 cases.

### Reasoning

- **The live detector reuses 3.9's `FaultDefinition` rather than declaring its own.** What fires on
  the patient's phone is then exactly what the physio reviewed and the bundle stores. A parallel
  live-only fault type would be a second place for the two to drift, and the one that drifts is
  always the one nobody is looking at.
- **Every dwell test is written against the failure, not the success.** 6.14.1 says the dwell "is
  the difference between a demo that feels magical and one that feels broken". So a single-frame
  spike, an 8-frame deviation at 267 ms just under the 300 ms bar, and two seconds of alternation
  either side of 3.0 all assert that **nothing** is said. Asserting that a three-second fault fires
  would not have caught a missing dwell.
- **Hysteresis is tested in the gap between the thresholds.** Once active, a fault holds at z = 2.0 -
  below the 3.0 entry, above the 1.5 exit. That band is the whole reason the two numbers differ, and
  a symmetric implementation would pass every other test in the file.
- **Phase gating is tested with the same value in two places.** 6.14.2's example is the point: 40
  degrees of trunk lean at mid-movement is correct and at the top is a fault. The test drives an
  identical z at phase 50 and phase 5 and asserts opposite verdicts.
- **Losing sight of a joint retracts a fault already standing, immediately.** 6.14.3 says no fault
  may fire on an invalid feature, ever - "if the contributing landmarks are occluded, we do not know,
  and we say nothing". A claim already on screen is still a claim. The 1.6 recovery hold upstream is
  what stops this becoming its own source of flicker.
- **Peak z is retained for the life of the fault**, because 4.5 arbitrates on severity and the frame
  where a fault is worst is rarely the frame where the cue is chosen.

### Addition: faults are bound, and failures are reported

6.14 says "fault condition names are bound and asserted at startup alongside the mask rules" without
saying what binding checks. The feature name is a `FeatureId`, so a typo cannot compile. What can
still go wrong is a fault naming a feature this reference does not watch: it binds fine and then
never fires, which is worse than an error because nothing distinguishes it from a patient who never
made that mistake. `unbindableFaults` reports those, plus empty condition lists and inverted phase
ranges.

### Note on the phase window default

6.14.2's default phase range for an auto-generated fault - the contiguous window where sigma is in
its lowest tercile - is the same derivation 6.17.2 uses for the scoring window, which 4.3 already
implements as `criticalWindow`. It is reused rather than reimplemented; two derivations of "where the
physio was most controlled" would be two things that can disagree about the same reference.

### Build note

The OneDrive interference recurred, failing `compileDebugKotlin` with "Cannot snapshot
... ComposableSingletons$ReferenceReviewKt.class: not a regular file". Deleting `app/build` and
`.kotlin` clears it, as in v3.3 and v3.4. Third occurrence; excluding the repo from OneDrive sync
would remove a recurring failure that looks like a code defect and is not.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 296 tests, 0 failures
  (19 in `FaultDetectorTest`).
- Gate met: twenty synthetic reps whose z grazes the entry threshold for one or two frames at a time
  produce zero onsets.
- `core` verified free of Android imports. No device gate required.

---

## v4.3 - Frame and rep scoring - 2026-09-04

Source: 6.17. Status: complete.

### Changed

- Added `core/live/Scoring.kt`: `frameScore`, `criticalWindow`, `phaseWeight`, `romPenalty`,
  `tempoPenalty`, `repScore`, `setScore`, `ScoredFrame`, `ScoredRep`, `SetScore`.
- Added eight constants to `Params.kt`; lambda, the z clamp and the critical-phase weight were
  already in section 7.
- Added `ScoringTest` with 23 cases.

### Reasoning

- **The calibration points are pinned, not trusted.** 6.17.1 chooses lambda so that every joint at
  2 sigma scores 55, and notes 1 sigma should still score 86. Both are asserted, along with 100 on
  the reference. A future edit to lambda that quietly rescales the whole app now fails the suite
  instead of shipping.
- **The ROM penalty is tested against the failure 6.17.3 names.** It calls this "the single most
  important line in the scoring function" and explains why: without it a patient who barely moves
  scores beautifully, because they never leave the reference band near their own shortened
  turnaround. A test scores identical frames at full range and at half range and asserts the second
  is lower.
- **Rushing costs more than labouring, and the asymmetry is asserted directly.** Floors of 0.6 and
  0.8. A momentum-driven rep bypasses the muscle work; a slow one does not. The test compares twice
  as fast against twice as slow rather than checking each bound in isolation, because the asymmetry
  is the point.
- **Renormalisation is tested against the wrong answer, not the right one.** When a feature abstains
  (1.6) the remaining features must be scored as if they were the whole watch list. The test asserts
  the partial frame equals a frame from a watch list that never contained the missing feature -
  which fails if the absent feature is treated as sitting perfectly on the reference.
- **No valid feature means no score, never zero.** Same rule as 1.6 and 4.2. A zero reads as a
  perfect failure the patient earned by being invisible.
- **The z clamp caps damage without hiding the fault.** 6.17.1 clamps at 6 so one catastrophic
  landmark glitch cannot zero an otherwise good rep. 6.14 still sees the raw z, so the fault is
  detected even though the score stops falling.
- **Median over mean for the set, per 6.17.4**, so one dropped rep does not define the session.

### Addition: a near-uniform reference falls back to the default window

6.17.2 derives the critical window as the contiguous range where the dominant feature's sigma is in
its lowest tercile, and says to default to [40, 60] "if derivation fails". It does not say what
failure looks like.

One case is not obvious and would pass silently: a reference with near-uniform sigma puts every bin
in the lowest tercile, so the derivation "succeeds" and returns a window covering the whole rep.
That weights everything double, which is the same as weighting nothing, while looking in the manifest
like a derived clinical window. A run covering more than half the grid is therefore treated as a
failed derivation and falls back to the default.

### Note on testing the tercile rule

The first fixture for the window test used a narrow 11-bin dip in an otherwise flat sigma and
expected those 11 bins back. That is not what a tercile is: with 11 low bins out of 101 the lowest
tercile threshold sits on the flat baseline, so every bin qualifies. The rule is correct and the
fixture was wrong - a real end-range hold has sigma tightening smoothly toward the middle, not a
rectangular notch. The fixtures now vary smoothly, and the second one places exactly 34 tight bins
so the threshold lands where the test intends.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 277 tests, 0 failures
  (23 in `ScoringTest`).
- Gate met: `setScore` filters on the 6.12.4 valid flag itself, so a partial rep affects neither the
  median nor the completion numerator. Asserted with three valid and two partial reps.
- `core` verified free of Android imports. No device gate required.

---

## v4.2 - Z-score lookup - 2026-09-04

Source: 6.13. Status: complete.

### Changed

- Added `core/live/ZScore.kt`: `referenceAt`, `phaseUncertainty`, `effectiveSigma`, `zScoreOf`,
  `zScores`, `ReferencePoint`, `FeatureZ`.
- Added `PHASE_UNCERTAINTY_MIN_VELOCITY`, `PHASE_UNCERTAINTY_MAX_SCALE` and
  `SIGMA_EFF_MIN_DEGREES` to `Params.kt`.
- Added `ZScoreTest` with 17 cases.

### Reasoning

- **The inflation is tested against the failure it prevents, not against itself.** 6.13.2 warns that
  without widening the band where the reference is steep, you get a burst of spurious faults every
  rep at the fastest part of the movement. The test takes one 12 degree deviation against one 4
  degree band and shows it reading as a 3 sigma fault on a flat stretch and as under 2 sigma on a
  stretch climbing 3 degrees per phase unit - because a three-unit phase error alone explains it.
  Asserting that a wider band is wider would have proved nothing.
- **A missing `v_ref` falls back to a flat 3.0, and does not guess.** 6.13.2 says so for older
  bundles. Estimating it from the current rep would make the patient's own tempo set their own
  tolerance, which is backwards - the reference defines the target, not the person being measured.
- **The sigma floor is 6.22's rule, not a defensive habit.** `σ_eff < 0.5°` clamps to 0.5. Without
  it a bin where the physio happened to be perfectly consistent turns a rounding error into a
  ten-sigma fault.
- **Interpolate, do not round.** 6.13.1. `p` is continuous and the reference is binned at integers;
  rounding to the nearest bin would quantise every z-score by up to half a bin of reference slope,
  which on a steep phase is degrees.
- **Only valid, watch-listed features are scored.** A feature that abstained this frame (1.6) is
  absent from the input map and stays absent from the output. Same rule as 1.6: absent means no
  score, never zero, because a zero reads as perfect agreement.

### Appendix A and what this iteration can honestly claim

The gate reads "Appendix A direction cases pass for demo features". Appendix A has two halves and
4.2 only owns one of them.

The half it owns - **positive z means the measured angle exceeds the reference** - is asserted for
every demo feature and in both directions, and 6.13.3 calls getting this backwards the single most
common source of backwards cues.

The other half - what a larger raw angle physically *means* per feature - is Appendix A's own
instruction to "verify each empirically, do not infer them from the formulas". That is 1.5's
outstanding item, `FeatureSigns` is still all `+1` placeholders, and it stays open there rather than
being quietly claimed here. No cue may ship against it until it is done.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 254 tests, 0 failures
  (17 in `ZScoreTest`).
- `core` verified free of Android imports. No device gate required.

---

## v4.1 - Phase tracker - 2026-09-04

Source: 6.12. Status: in-progress - device gate blocked on the 5.1 UI.

### Changed

- Added `core/live/PhaseTracker.kt`: `PhaseTracker`, `PhaseState`, `PhaseConfig`, `PhaseUpdate`,
  `CompletedRep`.
- Added ten 6.12 constants to `Params.kt`; `V_TH`, `V_STALL`, the dwell time and the acceptance
  phase were already in section 7.
- Added `PhaseTrackerTest` with 19 cases.

### Reasoning

- **No lookahead, and that is the whole difficulty.** 3.4 picked rep boundaries with the entire
  recording in hand. This has to decide on the frame in front of it. Everything below is a
  consequence of that.
- **theta_start and theta_peak come from the reference, never from the current rep.** 6.12.2 is
  explicit. The current rep's extremum is not knowable in advance, which is exactly why phase must
  be indexed against what the physio recorded.
- **The deadband and the dwell are tested against the failure they exist to prevent.** 6.12.3 warns
  that without them you get three phantom reps per real rep. A test injects a wobble that genuinely
  reverses the trace several times mid-rep - asserted, so the test cannot pass on a smooth signal -
  and asserts one rep still comes out.
- **Partial reps are a first-class outcome, not a rejection.** 6.12.4 says this distinction matters
  "enormously": without it a fatiguing patient's shrinking reps score as good reps, because they
  never leave the band near their own shortened turnaround, and the fatigue detector sees nothing.
  Each partial carries the sentence the patient hears.
- **A gap in the frame stream drops the velocity estimate.** Two frames either side of a gap are not
  adjacent, so the difference between them is not a derivative. Same rule as 2.3.
- **A lost patient discards the rep in progress rather than completing it.** The part we could not
  see is exactly the part we cannot score.

### Deviation: rep-complete returns to IDLE, not to DESCEND

6.12.1's diagram loops rep-complete straight back to DESCEND. The tracker goes to IDLE instead. The
next rep starts on the same condition either way, so the count is identical, but the rep clock now
starts when the patient moves rather than when the previous rep ended.

Following the diagram literally has two consequences the plan does not intend. Every rep taken after
a breather would carry the rest into its own duration and be rejected by 6.12.4's `3.0 x T_ref`
bound as "too slow". And a patient who stops mid-set sits in DESCEND indefinitely with a rep timer
that never stops, because the only route out of DESCEND is movement or the invalid-feature timeout.

### Finding: a rep below 60% of reference ROM is never reported at all

DESCEND leaves for ASCEND only once `phi > 0.60`. A rep that never passes 60% of reference ROM
therefore cannot reach rep-complete, so 6.12.4's partial-rep message never fires for it - the
patient who most needs "go all the way down" is the one least likely to hear it. Between 0.60 and
0.75 the partial path works as intended; below 0.60 there is silence.

Left as the plan specifies, and pinned by a test so it is documented behaviour rather than a
surprise. Fixing it means either lowering the ASCEND guard, which reopens the turnaround chatter the
guard exists to stop, or adding a separate abandoned-rep path. Neither belongs in 4.1 without an
owner decision.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 237 tests, 0 failures
  (19 in `PhaseTrackerTest`).
- `core` verified free of Android imports.
- Device gate - 20 real reps counted with no phantom reps: **not run**. It needs the live counter on
  screen, which is 5.1. The tracker has no UI caller yet, so there is nothing on the phone to count.

---

## v3.9 - .rpose read/write - 2026-09-04

Source: 9. Status: complete.

### Changed

- Added `core/rpose/Json.kt`: a minimal JSON reader/writer.
- Added `core/rpose/Rpose.kt`: `RposeBundle`, the 9.1-9.5 models, `writeRpose`, `writeRposeFile`,
  `readRpose`, `readRposeDemoVideo`, `nextVersion`.
- Added `RposeTest` with 17 cases.

### Reasoning

- **No JSON dependency, and the reason is the test.** `org.json` ships with Android but is a
  throwing stub under JVM unit tests, so the round-trip test - the entire point of this iteration -
  could not have run against it. kotlinx-serialization means a second Kotlin plugin on a build that
  already breaks for environmental reasons (see the v3.3 and v3.4 build notes). The 9 schema is
  ours, closed, and made of objects, arrays, numbers, strings and booleans, so a small parser covers
  all of it and is testable on the JVM where the format actually gets exercised.
- **Immutability is enforced, not documented.** 9.6 says an edit produces `version: 2` as a new
  file. `writeRposeFile` refuses to write over an existing path, and `nextVersion` keeps the id
  while bumping the version. Overwriting in place would silently change what an already-recorded
  session was scored against - the exact thing 9.6 exists to prevent - and a rule that lives only in
  prose is a rule that gets broken by the next caller.
- **The format version is checked before any other field is read.** A bundle written by a future
  build can look parseable field by field and still mean something different. Reading it and hoping
  is worse than refusing it.
- **101 is asserted on load, not assumed.** 6.8.4 fixes the grid. An array of any other length would
  index silently wrong at runtime rather than fail, which is the hardest kind of error to trace back
  to its cause.
- **Non-finite numbers are refused at write time.** JSON has no NaN or Infinity token. 6.22 already
  treats both as invalid data, so emitting something no parser will read back would turn a bad value
  into a corrupt file.

### Two deliberate additions to the 9 schema

Both exist because the round trip has to be lossless, and both are noted in source rather than left
for someone to rediscover from a diff.

1. **`reference.json` also stores `rep_count`, `body_position` and `median_sigma_raw`.** 9.2 lists
   only the feature arrays, but a `Reference` cannot be rebuilt without the first two, and
   `median_sigma_raw` is unrecoverable by construction - 6.9.4 clamps the stored band at 15 degrees,
   which destroys it. 6.10 filter 2 and 6.11 check 4 both read it. Re-deriving the first two from
   the manifest instead would let the two files disagree.
2. **`manifest.json` also stores `watch_list_rejected`.** 3.7 records why each joint was dropped and
   3.8 shows it to the physio. That explanation is worth as much when the bundle is reopened a week
   later as when it was recorded.

### Deviation: validation.json keeps the check, not the 9.5 row shape

9.5's example row is `{ id, pass, value, threshold }`. A boolean `pass` cannot express 6.11's third
outcome, `NOT_EVALUATED`, and the row drops the physio-facing message entirely - which would make
the stored record strictly less useful than the screen that produced it, defeating the stated
purpose of persisting it ("so the physio can see *why* the reference was accepted, and so we can
debug a bad reference after the fact"). The `ValidatorCheck` fields are stored instead, with `pass`
retained alongside `outcome` so the simple read still works.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 218 tests, 0 failures.
- Gate met: a fully populated bundle written and reloaded compares equal field for field, including
  nulls, the 101-bin arrays, the watch list, the fault set, the goals and every validator check.
- `core` verified free of Android imports. No device gate required.

---

## v3.8 - Reference validator and review - 2026-09-04

Source: 6.11, 14.1. Status: in-progress - the accept half of the device gate is not yet evidenced.

This entry is written after the fact. The code landed across three commits (`873f58d`, `f4c0d87`,
`cc4928f`) without a changelog entry, which is the one thing this file exists to prevent.

### Changed

- Added `core/reference/ReferenceValidator.kt`: the eight 6.11 checks, `ValidationResult`,
  `ValidatorCheck`, `CheckOutcome`, `RecordingSummary`.
- Added `core/pose/ViewClassification.kt`: `classifyView` and `viewStability` (6.20.2), which check 6
  needs.
- Added `core/reference/AuthoringPipeline.kt`: the first end-to-end wiring of 1.2 through 3.7, plus
  `readyForAutoStop` and `remainingAuthoringSeconds`.
- Added `authoring/ReferenceReview.kt`: the verdict, the dominant-feature trace with rep boundaries,
  the mean +/- 3 sigma band, the check table, the watch list, and the re-record button.
- `CameraPreviewScreen` now records, counts down, auto-stops on a clean pattern, and routes to the
  review screen.
- Added eight validator constants and `AUTHORING_ACCEPTANCE_TIMEOUT_SECONDS` to `Params.kt`.
- Added `ReferenceValidatorTest`, `ViewClassificationTest` and `AuthoringPipelineTest`.

### Reasoning

- **A check that could not run is not a pass.** Check 6 needs the 6.20.2 classifier; when view
  stability is unavailable the check reports `NOT_EVALUATED` and blocks acceptance rather than
  quietly passing. This is why `CheckOutcome` has three values and not a boolean.
- **Every failure names the joint and the fix.** 6.11 spells its messages out for a reason: "I
  couldn't see your left leg clearly. Move the camera back" is actionable and "validation failed" is
  not. Check 5 lists the actual features that fell below the visibility bar.
- **One reason leads, but all eight are shown.** `firstFailure` drives the headline; the table below
  it shows every check with its measured value, so a physio can see a marginal pass.
- **The re-record button is unconditional.** 6.11: "Provide a 'looks wrong - re-record' button.
  Always." It is shown even when every check passes, because the physio's eye overrules the
  validator, not the other way round.
- **The chart is the point of the screen.** 6.11 says two seconds of visual confirmation beats any
  amount of algorithmic cleverness. A physio spots a bad rep split instantly and no threshold will.
- **Auto-stop requires a fully accepted pattern, not a partial one.** Stopping early on an
  intermediate result would end the recording on something the validator would then reject.

### Outstanding - why this is not `complete`

The device gate has two halves. The reject half is evidenced (`artifacts/v3.8/bad-recording.png`,
plus the recording and countdown screens). The accept half - a good knee-extension recording
accepted with visually credible rep boundaries - has not been captured, and it needs a phone, a
person and eight steady reps.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 0 failures.
- Device gate: bad recording rejected with a specific reason - passed. Good recording accepted -
  not yet run.

---

## v3.7 - Watch list - 2026-09-03

Source: 6.10. Status: complete.

### Changed

- Added `core/reference/WatchList.kt`: `selectWatchList`, `salienceOf`, `WatchList`,
  `WatchedFeature`.
- Replaced `FeatureReference.reachedCap` with `FeatureReference.medianSigmaRaw` (see correction
  below).
- Added two watch-list constants to `Params.kt`.
- Added `WatchListTest` with 17 cases.

### Reasoning

- **P3-15 is implemented under that name.** 6.10 asks for it specifically: ROM 60 with sigma 22 must
  be excluded, ROM 40 with sigma 3 must be included. The first case clears filters 1 and 4 by
  itself, so filter 2 is the only thing that can reject it. That is the point - the plan says an
  inverted filter should fail here immediately rather than surfacing three hours later as
  inexplicable scoring behaviour.
- **All four filters written as pass conditions.** 6.10 rewrote its own table for exactly this
  reason: mixing pass and fail phrasing across rows is how an inverted filter gets written. Each
  filter has a boundary test - 7.9 vs 8.0 ROM, sigma 4 kept vs 18 rejected, visibility 0.69 vs 0.70,
  salience 1.99 vs 2.00.
- **Filter 2 shares `SIGMA_CAP_DEGREES` with the reference builder.** 6.9.4 warns that if the cap
  moves and this threshold does not, the two disagree silently. Sharing the constant makes that
  impossible rather than merely unlikely.
- **A feature with no recorded visibility fails rather than passing by default.** Absent data is not
  evidence of good observation.
- **The dominant feature is added after filtering, so it survives rejection.** Phase itself is
  derived from it (6.8.2), so a reference that does not score it is not scoring anything coherent.
  It still appears in `rejected` with its reason, so the physio can see the problem.
- **Rejections carry their reason.** The 3.8 review screen needs to tell a physio why a joint was
  dropped; a silently shorter list tells them nothing.
- **A degenerate reference shares weight equally rather than emitting NaN.** NaN weights would
  poison every frame score downstream with no obvious cause.

### Correction to v3.6

3.6 recorded `reachedCap` - sigma reached the 15 degree cap in **any** bin - and documented it as
feeding filter 2. Wrong on two counts. Filter 2 tests the median over bins, not "any", and it has
to read the **pre-clamp** sigma: 6.9.4 clamps at 15 before inflating, so a post-clamp median can
never exceed the threshold it is compared against, and 6.10's own required test case of sigma = 22
would not even be representable.

`reachedCap` is replaced by `medianSigmaRaw`. The v3.6 doc and its changelog entry above are both
corrected rather than left standing. `salienceOf` reads the same pre-clamp value for the same
reason - measuring salience off the stored band would compress the ranking and scale every value by
k_inflate.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 162 tests, 0 failures.
- Gate met: weights sum to 1.0 within 1e-9 for watch lists of 1, 3, 5 and 6 features, including the
  case where the dominant feature pushes the list past K.
- `core` verified free of Android imports. No device gate required.

---

## v3.6 - Robust reference builder - 2026-09-03

Source: 6.9, 6.22. Status: complete.

### Changed

- Added `core/reference/ReferenceBuilder.kt`: `buildReference`, `Reference`, `FeatureReference`,
  `BodyPosition`, and the deferred 6.7.6 step 3 shape-outlier rejection.
- Added three constants to `Params.kt`; the rest were already in section 7.
- Added `ReferenceBuilderTest` with 15 cases.

### Reasoning

- **Median over mean, and the test proves the difference.** 6.9.1 warns that with N = 5-7 one
  atypical rep that survived earlier rejection still moves a mean. A test shifts one rep of seven by
  25 degrees and asserts the median trajectory moves under 1 degree, where a mean would move 3.6.
- **The correction table is asserted value-for-value.** Transcription is exactly the kind of thing
  that goes wrong silently, so 1.36 / 1.21 / 1.14 / 1.10 / 1.06 are pinned rather than trusted.
- **Smoothing is tested against the failure it prevents.** 6.9.3 explains that a bin where the reps
  happened to agree gets an artificially tight band, producing a spurious fault at exactly that
  phase. The test notches one bin to zero amid scattered reps and asserts the band does not collapse
  there. Testing that smoothing "smooths" would have proved nothing.
- **Inflation after the clamp, never inside it.** 6.9.4 is emphatic and explains why: the 15 degree
  cap is a statement about the *recording* ("the physio was not controlling this joint"), the
  inflation is a statement about *transfer* ("the patient is not the physio"). Independent
  corrections, both apply, ceiling 17.25 upright and 19.50 non-upright. Three tests pin it - both
  ceilings and the ratio - so the refactor the plan warns against fails loudly.
- **The pre-clamp sigma is recorded, not just the final band.** 6.10 filter 2 needs to drop
  unconstrained features from the watch list, and recomputing that from the inflated sigma would
  compare against the wrong threshold.

  *(Corrected in v3.7: this originally stored `reachedCap`, a boolean for "sigma reached the cap in
  any bin". Filter 2 tests the median over bins, not "any", so the field is now `medianSigmaRaw`)*

### Finding: a zero MAD must not skip outlier rejection

The first implementation bailed out when MAD came to zero, reasoning there was no dispersion to
threshold against. That is backwards, and the test caught it: with five identical reps and one of a
different shape, MAD is exactly zero, and bailing kept the very rep the step exists to remove.

With a zero MAD the threshold equals the centre, which is right in both directions - identical reps
pass because 0 is not greater than 0, and a differently shaped rep still fails. 6.22's "MAD = 0
applies the sigma floor" rule is about dispersion estimates, not this filter; reading it as covering
both is what produced the bug.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 145 tests, 0 failures.
- Gate met: `mean`, `sigma` and `derivative` are each exactly 101 long and finite, asserted per bin.
- `core` verified free of Android imports. No device gate required.

---

## v3.5 - Phase and resampling - 2026-09-03

Source: 6.8. Status: complete.

### Changed

- Added `core/reference/PhaseResampling.kt`: `phaseProfile`, `resampleRep`, `resampleUsableReps`,
  `ResampledRep`.
- Added `PhaseResamplingTest` with 11 cases.

### Reasoning

- **One phase definition, in `core`, for both pipelines.** 6.8.1 calls this the subtlest correctness
  issue in the system, and the failure mode is nasty: if authoring indexed the reference by elapsed
  time while the live pipeline derived phase from the dominant joint's value, a patient moving at a
  different tempo would be compared against the wrong part of the reference. Every z-score would be
  wrong, and wrong *as a function of tempo*, which is the hardest kind of error to notice. Putting
  the definition in `core` rather than an authoring package is what stops the two drifting.
- **The tempo test is the iteration.** The same movement at 120 samples and at 31 samples must land
  on the same 101 bins. Everything else here is support for that one property.
- **Monotonicity is enforced per half-rep, and tested against wobble.** 6.8.3 permits it because
  inside a half-rep the joint genuinely moves one way, so any reversal is noise or tremor. The test
  injects a wobble big enough to reverse the raw ratio and asserts phase still never goes backwards.
- **ROM is kept separate from phase.** 6.8.4 is explicit that these are different jobs. Phase
  normalises against each rep's own extremum, so a short rep still spans 0 to 100 - meaning phase
  alone would hide an undershoot completely. The test runs a full and a short rep, asserts identical
  phase coverage, and asserts ROM of 80 versus 44 degrees.
- **The mid-rep extremum comes from segmentation, not a recomputation.** Two pieces of code deciding
  independently where a rep turns around is two pieces of code that can disagree.
- **A feature with one bad sample is dropped from that rep, not allowed to poison it.** Same rule as
  1.5 and 1.6: one bad landmark costs one feature.

### Correction to the v3.4 deferral

v3.4 said section 6.7.6 step 3 (shape outliers) would arrive in 3.5. That was wrong, and both that
file and the v3.4 entry above are corrected. Resampling alone is not enough: the step thresholds RMS
deviation against a provisional median trajectory using MAD, and both land in 3.6. Writing a one-off
median and MAD here would have duplicated 3.6 - the exact duplication the original deferral existed
to avoid.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 130 tests, 0 failures.
- Gate met: every returned feature has exactly 101 bins and every bin is finite, asserted per bin.
- `core` verified free of Android imports. No device gate required.

---

## v3.4 - Extrema and rep segments - 2026-09-03

Source: 6.7.3-6.7.6. Status: complete.

### Changed

- Added `core/reference/Segmentation.kt`: `segmentReps`, `Extremum`, `RepSegment`,
  `SegmentationResult`.
- Added `MIN_SURVIVING_REPS = 4` to `Params.kt`; every other threshold was already in section 7.
- Added `SegmentationTest` with 11 cases.

### Reasoning

- **The spacing rule is the one that earns its test.** 6.7.3 calls `0.6 x T` minimum spacing the
  single highest-value rule in the section. A test feeds a signal with three local maxima per rep -
  the mid-rep wobble the rule exists to survive - and asserts exactly eight reps come out, not
  twenty-four.
- **Valleys reuse the peak path on a negated signal.** Prominence is a vertical distance and is
  unchanged by the negation, so one implementation covers both and the two cannot drift apart.
- **Alternation repair drops rather than guesses.** When two same-kind extrema have nothing between
  them, 6.7.4 says drop the pair. Keeping the stronger one would look tidier and would silently bias
  the rep count.
- **Duration outliers use the median, not the mean.** With six to eight reps one double-length rep
  drags the mean far enough to hide inside its own threshold.
- **A rejected recording returns an empty `usable` list, not a short one.** Partial results from a
  rejected recording are the kind of thing a caller uses by accident.

### Deferred: shape outliers

6.7.6 step 3 rejects reps by RMS deviation from a provisional mean trajectory. That needs every rep
resampled onto a common phase grid, which is 3.5, and the median trajectory and MAD it thresholds
against, which are 3.6. Implementing it now would mean inventing a second notion of rep shape that
is guaranteed to disagree with the real one later.

*(Corrected in v3.5: this said 3.5. Resampling alone is not enough - the step also needs 3.6's
median and MAD, so it is implemented in 3.6.)*

### Finding: a rep boundary on the first or last sample cannot be detected

A local extremum needs neighbours on both sides, so an extremum landing exactly on sample 0 or the
last sample is invisible. A recording that starts and ends precisely on a rep boundary therefore
reports one rep fewer than it contains.

This caught out this iteration's own first test signals - all eight failed the same way before the
cause was clear. It is benign in practice, and 6.7.6 step 1 already assumes it: real recordings begin
with the physio settling in and end with them reaching for stop. The test signals were given that
lead-in and lead-out rather than the detector being changed to special-case endpoints, because the
unrealistic thing here was the synthetic signal, not the detector.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 119 tests, 0 failures.
- Eight clean reps detect eight and keep six; wobble does not split; a double-length rep drops; five
  reps is rejected under the four-survivor bar; six reps is exactly the minimum that passes.
- `core` verified free of Android imports. No device gate required.

### Build note

The OneDrive interference from v3.3 recurred and worsened, this time failing `compileDebugKotlin`
with `Cannot access output property 'destinationDirectory'`. Deleting `app/build`, `build` and
`.kotlin` clears it. Excluding the repo from OneDrive sync would remove a recurring source of
failures that look like code defects but are not.

---

## v3.3 - Dominant feature and period - 2026-09-03

Source: 6.7.1-6.7.2. Status: complete.

### Changed

- Added `core/reference/DominantPeriod.kt`: `selectCoarseDominantFeature` and `estimatePeriod`.
- Added `DominantPeriodTest` with 8 cases.

### Reasoning

- **Percentiles, not min/max.** 6.7.1 ranks features by peak-to-peak swing over a recording whose
  rep boundaries are not known yet, so the input is contaminated by definition. 5th/95th means one
  spurious frame cannot win the ranking; a test spikes a flat signal to 1000 and asserts it loses.
- **Per-lag normalisation, not division by r[0].** 6.7.2 calls this out: dividing by r[0] leaves a
  triangular bias that makes short lags spuriously dominant, which would report a period shorter
  than the real one.
- **First qualifying local maximum, not the global one.** On a highly periodic signal the global
  maximum can sit on 2T and halve the rep count. Tested with a signal whose reps alternate in shape
  so r[2T] genuinely exceeds r[T] - the test asserts that inequality too, otherwise it would pass
  for a signal where the distinction never arose.
- **Coarse pass only.** 6.7.1's second pass recomputes swing over clean segments and confirms or
  switches the dominant feature, which needs the segmentation from 3.4. The function is named
  `Coarse` so a caller cannot mistake the two.
- **Non-finite returns null instead of throwing.** The draft used `require`. Section 6.22 treats
  NaN and Inf as invalid, not as a crash, and a single bad sample must not take the authoring
  pipeline down.

### Finding: the lower lag bound is not a plausibility screen

A rep faster than the 0.5 s lower bound is not rejected. A signal with a 0.167 s period correlates
perfectly at every multiple of it, and 0.5 s is the first multiple inside the search window, so the
estimator confidently returns 0.5 s - a rep count wrong by a factor of three.

6.7.2's bounds limit what the search can *see*; they do not screen out impossible movement. This is
now pinned as the actual behaviour rather than left to surprise someone later, and catching it
belongs to the 3.8 validator. Movement slower than the 8 s upper bound *is* rejected, because no
local maximum exists inside the window at all.

### Finding: swing ranking mixes units

Fourteen features are degrees; `shoulder_elevation` is a dimensionless ratio x100. 6.7.1 says to
compare raw swing and does not address this, so nothing was invented - it is noted in source. The
plan's own argument is that the principal joint clears the rest by an order of magnitude, which
holds for the four demo exercises but is not guaranteed in general.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 108 tests, 0 failures.
- `core` verified free of Android imports. No device gate required.

### Build note

OneDrive repeatedly locked and de-materialised files under `app/build/intermediates`, failing
`mergeDebugResources` and `packageDebugResources`. Deleting that directory clears it. Environmental,
not a code defect, but it will recur while the repo lives inside OneDrive.

---

## v3.2 - Offline Butterworth filter - 2026-09-03

Source: 6.3.2. Status: complete.

### Changed

- Added a fourth-order 6 Hz zero-phase Butterworth filter using two biquads, reflection padding,
  and forward/backward passes.
- Added `ButterworthFilterTest` for low-frequency preservation, high-frequency suppression, and
  zero sample lag.

### Reasoning

- Used direct biquad equations and Kotlin/JDK math only; no signal-processing dependency is needed
  for one fixed filter.
- Reflection padding and steady-state initialization limit edge transients without changing signal
  length.
- The filter accepts a sample rate because coefficients must match recorded frame timing.

### Gate

- Targeted Butterworth tests: BUILD SUCCESSFUL; 3 tests, 0 failures.
- Full JVM suite: BUILD SUCCESSFUL; 100 tests, 0 failures.
- No phone/device gate; this path is not connected to UI yet.

---

## v3.1 - Synthetic landmark generator - 2026-09-03

Source: 19 pre-event scaffolding. Status: complete.

### Changed

- Added deterministic timestamped right-knee repetition generation with configurable rep count,
  ROM, sample rate, Gaussian landmark noise, whole-frame dropout, and seed.
- Added `SyntheticLandmarkGeneratorTest` with reproducibility, measurable rep/ROM, noise, and
  dropout checks.

### Reasoning

- A single anatomically valid knee trajectory is enough to drive the upcoming authoring pipeline;
  generating multiple exercise families now would be unused scope.
- Dropout sets confidence to zero instead of deleting frames, matching `LandmarkFrame` and existing
  validity handling.
- Even samples per repetition guarantee exact generated extrema instead of approximate requested
  ROM.

### Gate

- Targeted generator tests: BUILD SUCCESSFUL; 3 tests, 0 failures.
- Full JVM suite: BUILD SUCCESSFUL; 97 tests, 0 failures.
- No camera, person, or phone required.

---

## v2.3 - One Euro filter - 2026-09-03

Source: 6.3.1. Status: complete.

### Changed

- Added `core/pose/OneEuroFilter.kt`: one scalar filter and a 99-channel landmark filter.
- Live pose results now carry camera timestamps and filter world landmarks before later geometry.
- Added `OneEuroFilterTest` with 4 cases.
- RMX3997 stationary/rapid-motion check retained plan defaults: stationary frame-to-frame motion
  fell roughly 50-70%, while rapid movement added roughly one processed frame of lag.

### Reasoning

- **World landmarks only.** Section 6.2 reserves image landmarks for drawing. Filtering both would
  double state to 198 channels and add visible overlay lag without improving geometry.
- **All resets share one path.** Invalid timestamp order, gaps over 0.25 seconds, lost/re-detected
  people, and explicit reset clear all 99 channels. No derivative crosses a discontinuity.
- **Defaults retained from evidence.** The section 7 values reduced stationary jitter while keeping
  rapid-motion lag near one processed frame, so changing them would add no measured benefit.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` - BUILD SUCCESSFUL; 94 tests, 0 failures.
- `gradlew.bat assembleDebug --no-daemon` - BUILD SUCCESSFUL.
- RMX3997 stationary/rapid-motion gate: passed.

---

## v2.2 - Pose inference and skeleton - 2026-09-03

Source: 11.1, 12.1-12.3. Status: in-progress; performance gate not met on RMX3997.

### Changed

- Added MediaPipe Lite pose inference, 33-landmark/confidence mapping, live skeleton, and visible
  model/delegate/FPS/latency diagnostics.
- Runtime preference is locked as NPU, GPU, CPU. Current executable MediaPipe fallback is GPU then
  CPU; QNN awaits target-device artifacts and QAIRT libraries.
- Preview and analysis now request the same 16:9 geometry. This fixes oversized/misaligned overlay
  caused by drawing analysis coordinates over a differently cropped preview stream.

### Device evidence

- Full/GPU: 11-16 fps. Lite/GPU: 12-19 fps at 68-90 ms. Lite/CPU: 6 fps at 266 ms.
- Front skeleton rendered and tracked. Corrected same-stream overlay geometry still needs a person
  screenshot; last connected frame pointed at the ceiling.
- Required 25 fps was not met. Iteration remains open for QNN target-device work.

---

## v2.1 - Camera preview - 2026-09-03

Source: 12.1, 5.2. Status: complete. First iteration with a real device gate.

### Changed

- Added `camera/CameraController.kt`, `camera/CameraPermission.kt`, `camera/CameraPreviewScreen.kt`.
- `MainActivity` now routes to the camera preview instead of the v0.3 axis diagnostic.
- Added `CameraPermissionTest` with 6 cases.

### Reasoning

- **Permission logic is a pure function, so it can be tested at all.** Three booleans in, one state
  out. The case that matters is telling "never asked" apart from "permanently denied": Android
  reports no rationale for both, and only the app knows whether it has already asked. Conflating
  them puts an Allow button on screen that silently does nothing when tapped.
- **`CameraFacing` was reused from `core.pose`, not redeclared.** The canonicalisation layer already
  owns that concept.
- **The v0.3 diagnostic was left in the repo, just unrouted.** Deleting it would make the v0.3 axis
  measurements unreproducible, and those measurements are load-bearing for every angle downstream.

### Three defects the device gate caught

None of these were visible from a unit test, and two are places where the plan's own snippet does
not do what the plan's prose asks for on the pinned CameraX version.

1. **Landscape preview rendered sideways.** `setTargetRotation` was fed `view.display?.rotation`,
   but at bind time the `PreviewView` is not attached yet, so its display is null and every
   orientation bound as ROTATION_0. Rotation now comes from the Activity's display and is passed
   into `bind`, which rebinds when it changes. `Preview` no longer sets a target rotation at all:
   `PreviewView` applies the display transform itself and setting both fights it. `ImageAnalysis`
   still needs it, having no view to correct it, and that is what keeps 2.2's landmark coordinates
   orientation-consistent.
2. **Analysis ran at 1920x1440 instead of 720p.** Section 12.1 asks for `Size(720, 1280)`. That
   bound is portrait; the camera advertises landscape sizes, so it matched nothing and
   `CLOSEST_HIGHER_THEN_LOWER` resolved upwards to a stream 3.7x the requested pixel count. Now a
   sensor-oriented `Size(1280, 720)` with a 16:9 aspect strategy and `CLOSEST_LOWER_THEN_HIGHER`, so
   the fallback undershoots rather than overshoots. Measured 1920x1440 at 15 fps before, 1280x720 at
   24 fps after.
3. **The fps counter raced.** It wrote Compose state from the analysis thread, which both races and
   forces a recomposition every single frame. Replaced with an `AtomicInteger` sampled once a second.

### Device gate - Realme RMX3997

Preview opens front-default, correct in portrait and landscape, survives background and foreground
with no black frame, switches front to rear, analysis stream 1280x720 at 24 fps.
Evidence in `artifacts/camera-preview/`.

**Warning for 2.2:** that 24 fps is with the analyser doing nothing but closing the frame. The 2.2
gate is 25 fps *with* pose inference running, so there is zero headroom on this device using the CPU
delegate. Expect to need GPU or NPU.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon --rerun-tasks` - BUILD SUCCESSFUL; 87 tests, 0 failures.
- `gradlew.bat assembleDebug --no-daemon` - BUILD SUCCESSFUL; installed and launched on RMX3997.
- Device gate: passed.

---

## v1.6 - Validity and abstention - 2026-09-03

Source: 6.6, 8.2, 6.22. Status: complete.

### Changed

- Added `core/pose/FeatureValidity.kt`: `validFeatures`, `ValidityTracker`, `renormaliseWeights`,
  `repIsScorable`, `FeatureKind`, and the `contributingLandmarks` table.
- Added seven validity constants to `Params.kt`.
- Added `FeatureValidityTest` with 20 cases.

### Reasoning

- **Layered on extraction, not folded into it.** Section 6.6 is a stricter filter over the same
  numbers, not a different computation. Two parallel validity systems drift apart, and the one that
  drifts is always the one nobody is looking at.
- **The 1e-3 m floor is a second, separate guard.** Section 6.22 sets 1e-6 to stop a divide-by-zero;
  section 6.6 sets 1e-3 because a 1 mm shank is a detection failure, not a short leg. Different
  jobs, so both stay. A test pins a value that clears the first and trips the second.
- **The recovery hold is tested against flicker, not against a clean recovery.** A landmark that
  comes back cleanly is the easy case. The case section 6.6 actually cares about is one dropping in
  and out, which without the hold produces a stream of spurious faults at every boundary. The test
  cycles a feature on a 250 ms period and asserts it never emits.
- **Chain-order landmark lists make rule 2 free.** Storing contributing landmarks in anatomical
  chain order means consecutive pairs are exactly the vectors that need a magnitude check. The
  alternative was a second hand-maintained table of vectors that could silently disagree with the
  first.
- **Empty means "no score", not "zero".** `renormaliseWeights` returns an empty map when nothing
  valid is left. A zero would read as a perfect failure and feed the patient a score they earned by
  being invisible.
- **Section 8.2 timers live here too.** The 500 ms warn and 1.5 s idle thresholds run off the same
  invalid-duration clock as the recovery hold. Splitting them across files would mean two clocks.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon --rerun-tasks` - BUILD SUCCESSFUL; 81 tests, 0 failures
  (20 in `FeatureValidityTest`).
- `core` verified free of Android imports. No device gate required.

Phase 1 is now closed except for the 1.5 device gate and Appendix A sign verification, both blocked
on the camera pipeline in 2.1-2.2.

---

## v1.5 - Feature extraction - 2026-09-03

Source: 6.5, Appendix A. Status: **in-progress** - code and JVM tests done, device gate blocked.

### Changed

- Added `core/pose/FeatureExtraction.kt`: all 15 features from the 6.5.3 table, plus `Side`,
  `FeatureContext`, and the `FeatureSigns` table Appendix A asks for.
- Added `angleBetweenDegrees` to `Angles.kt`; `jointAngleDegrees` now delegates to it.
- Added `FeatureExtractionTest` with 19 cases.

### Reasoning

- **Invalid features are absent from the map, not null-valued.** One landmark going bad should cost
  one feature, not the frame. A missing foot drops ankle dorsiflexion and nothing else, and two
  tests pin that isolation down.
- **Active side is an input, not computed here.** Section 6.2 resolves it per rep from the first 25%
  of the rep, which needs segmentation that does not land until 3.4. Guessing at it now would mean
  writing rep logic twice.
- **Expected values come from the geometry, not from the code.** Every synthetic pose is placed by
  hand so the answer is known before running: a straight leg is 180 degrees, a right-angled knee is
  90. A test that records whatever the implementation printed proves only that it is deterministic.
- **Signed features are tested for sign, not just magnitude.** Dropping the left hip and dropping
  the right hip must produce opposite signs; the test asserts the product is negative. That is the
  property Appendix A cares about and the one a formula error silently breaks.

### Plan discrepancy: feature 8 cannot be body-intrinsic

Section 6.5.4 says that for non-upright exercises only feature 7 needs disabling, because features 8
and 10 "are computed entirely within the body frame and remain valid in any body position".

Feature 10 checks out. Gram-Schmidt strips the longitudinal component from the basis but not from
the raw hip line, and that leftover component is the hip drop being measured.

Feature 8 does not. Section 6.4 defines the y axis **as** the torso axis, so in body coordinates the
torso is identically (0, 1, 0). Trunk lean measured against the body frame is therefore structurally
zero for every pose - not approximate, exactly zero. It needs an external reference to mean
anything, so it is implemented against the vertical alongside feature 7 and abstains without one.

This contradicts 6.5.4 and needs an owner decision before non-upright exercises ship. None of the
four demo exercises is affected.

### Outstanding - why this is not `complete`

1. **Appendix A signs are placeholders.** Every `FeatureSigns` entry is +1. Appendix A says "Verify
   each empirically. Do not infer them from the formulas." So nothing was inferred and nothing was
   verified; the placeholders are labelled as such in source and no cue may ship against them.
2. **The device gate cannot run.** It needs live camera and pose output, which arrive in 2.1 and
   2.2. The iteration plan schedules this gate at 1.5, but nothing that can satisfy it exists yet.
3. **Feature 15 is an interpretation.** "The shoulder-line normal" is ambiguous - a line has
   infinitely many normals. Taken as `line x y`, the forward normal of each girdle. Section 6.5.3
   already calls feature 15 a coarse proxy and says to drop it rather than debug it live.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon --rerun-tasks` - BUILD SUCCESSFUL; 61 tests, 0 failures
  (19 in `FeatureExtractionTest`).
- `core` verified free of Android imports.
- Device gate: not run, blocked on 2.2.

---

## v1.4 — Body frame — 2026-09-03

Source: §6.4. Status: complete.

### Changed

- Added `core/pose/BodyFrame.kt`: `BodyFrame` with `toBody`, a `build` function, and
  `BodyFrameTracker` holding the cross-frame reuse state.
- Added four guard constants to `Params.kt`.
- Added `BodyFrameTest` with 13 cases.

### Reasoning

- **What this buys us.** Raw world coordinates change when the patient turns or steps sideways.
  The body frame re-expresses everything relative to the patient's own torso, so a knee angle is
  the same number whichever way they face. Every feature in §1.5 depends on this.
- **Gram–Schmidt is not optional.** §6.4 says so and explains why: hip-to-hip is not exactly
  perpendicular to the torso axis once pelvic tilt and noise are involved, and a non-orthonormal
  basis has an inverse that is not its transpose. The resulting shear varies with posture, so it
  would not show up as a constant offset anyone could spot — it would look like real movement.
- **Invariance is tested, not assumed.** Orthonormality is necessary but not sufficient. Two tests
  assert the actual property: yaw the body 90° in the world with the arm held in the same place
  relative to the torso and body coordinates must be identical; translate the whole person and the
  basis must not move.
- **Three states, not a nullable frame.** `REUSED` is a normal condition — §6.4 calls it expected
  in sagittal-view exercises — while `INVALID` means abstain. Returning null for both would erase
  a distinction §1.6 has to act on.
- **Visibility bar is 0.5 here, not the 0.6 `VIS_MIN` used elsewhere.** §6.4 sets a separate,
  looser threshold for frame construction. Reusing the stricter constant would have been tidier
  and wrong.
- **The 500 ms window runs from the last good frame.** Measuring from the first degenerate frame
  would let a long run of bad frames stay "recent" indefinitely as long as they kept arriving.
- **No smoothing yet.** §6.4 also asks for One Euro filtering of the basis vectors, which the
  iteration plan defers to 2.3. Building it now would mean building it against no real camera data.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon --rerun-tasks` — BUILD SUCCESSFUL; 42 tests, 0 failures
  (13 in `BodyFrameTest`).
- `core` verified free of Android imports. No device gate required.

---

## v1.3 — Stable angle math — 2026-09-03

Source: §6.5.1–6.5.2, §6.22. Status: complete.

### Changed

- Added `core/pose/Angles.kt`: `jointAngleDegrees` (unsigned three-point joint angle) and
  `signedPlanarAngleDegrees` (signed in-plane deviation).
- Added `dot`, `cross`, `times` and `length` to `Vec3`.
- Added `MIN_VECTOR_LENGTH` and `SIGNED_ANGLE_MAX_DEGREES` to `Params.kt`.
- Added `AnglesTest` with 12 cases.

### Reasoning

- **atan2, never acos.** §6.5.1 is emphatic about this and it is the whole point of the iteration.
  `acos(v1·v2 / (|v1||v2|))` is ill-conditioned as its argument approaches ±1 — several degrees of
  error on float32 at 178°. That is full extension, exactly where knee-extension exercises are
  scored. A test drives 179°, 179.5° and 179.9° and asserts 0.01° accuracy, so a future rewrite to
  `acos` fails the suite instead of silently degrading the feature everything else is built on.
- **Invalidity is `Float?`, not a new result type.** Nothing downstream consumes angles yet, and
  §1.6 defines the real validity and abstention contract. Inventing a wrapper type now would mean
  designing that contract twice.
- **Non-finite is checked on the output, not the input.** A NaN coordinate slips past the length
  guard because `NaN < 1e-6` evaluates false. Checking `isFinite` on the computed angle catches
  NaN and infinity in one place instead of validating nine input components.
- **The plane normal is normalised inside the function.** §6.5.2 writes it as unit `n̂`, but §1.4
  builds the body basis by Gram–Schmidt and a normal that drifts off unit length would scale the
  `cross · n̂` term and skew every signed reading. One line to remove a whole class of caller bug.
- **|θ| > 90° returns invalid rather than a wrapped value.** §6.22 treats this as probable wrap.
  Anatomical deviations are small, so a large reading is more likely a sign error than a real one,
  and abstaining beats coaching a patient off a wrapped number.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon --rerun-tasks` — BUILD SUCCESSFUL; 29 tests, 0 failures
  (12 in `AnglesTest`).
- `core` verified free of Android imports. No device gate required.

---

## v1.2 — Canonicalisation — 2026-09-03

Source: §6.2. Status: complete.

### Changed

- Added `core/pose/PoseLandmark.kt`: named constants for all 33 MediaPipe pose landmarks, plus a
  `LATERAL_PAIRS` table listing every (left, right) anatomical pair.
- Reworked `core/pose/Canonicalisation.kt`: mirror permutation is now derived from `LATERAL_PAIRS`
  rather than hand-written integer literals; added the §6.2 rationale as source comments.
- Extended `CanonicalisationTest` from 3 to 6 tests with invariant coverage of the mirror table.

### Reasoning

- **Both operations, always.** §6.2 requires negating x *and* relabelling left/right. Doing only the
  first leaves a left knee sitting at the right knee's position — geometrically plausible, silently
  wrong. The two are applied in a single pass so they cannot drift apart.
- **World vs image landmarks mirror differently.** World landmarks are metric and hip-centred, so
  the reflection is `x = -x`. Image landmarks are normalised to [0, 1], so the same reflection is
  `x = 1 - x`. Using one formula for both would put the drawn skeleton off-screen.
- **`visibility` and `presence` are permuted too.** They are per-landmark channels. Permuting
  coordinates but not confidences would attach the left leg's confidence to the right leg — the
  exact failure mode that later abstention logic (v1.6) is supposed to catch, made invisible.
- **Recording and live metadata are separate types.** §6.2 warns explicitly that the manifest's
  `mirrored` describes the physio's recording only, and that reading it as a runtime setting would
  mirror the patient's feed to match the physio's — backwards. Modelling these as two distinct
  types instead of one type with a flag turns that mistake into a compile error.
- **The mirror table needed a real invariant test.** Correctness of every downstream feature rests
  on this permutation being a bijection and its own inverse. A typo in the pair table could produce
  a duplicated target that a round-trip test alone would not reliably surface, so the involution,
  bijectivity, and "nose is the only unpaired landmark" properties are now asserted directly.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon --rerun-tasks` — BUILD SUCCESSFUL; 17 tests, 0 failures
  (6 in `CanonicalisationTest`).
- Verified `core` sources import no Android APIs; the math layer stays pure JVM.
- No device gate required for this iteration.

---

## v1.1 — Domain primitives and parameters — 2026-09-03

Source: §6.1–6.2, §7. Status: complete.

### Changed

- Added pure Kotlin `Vec3`, a 33-landmark `LandmarkFrame`, and guarded nanosecond frame timing.
- Added every numeric setting from §7 to `Params.kt`, plus §6.1's nominal FPS and 0.25 s gap limit.
- Added all 15 feature identifiers from §6.5.3 with their exact strings locked by a uniqueness test.

### Reasoning

- Parameters were centralised in one object so tuning never means hunting through call sites.
- Feature identifier strings are serialised into `.rpose` references, so a uniqueness test guards
  against a silent collision that would misroute a feature.
- **Plan discrepancy retained deliberately:** §6.5 says "14 scalar features" but its numbered table
  lists 15. The code follows the 15-row table. Nothing was guessed at or dropped to reconcile the
  prose — the table is the more specific statement.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` — passed; 11 tests, 0 failures.
- `core` verified free of Android imports. No device gate required.

---

## v0.3 — Empirical axis convention — 2026-09-03

Source: §6.1, Appendix A, risk #1. Status: complete.

### Changed

- Added a temporary raw-landmark diagnostic and a single `AxisConvention` definition.

### Reasoning

- The axis convention was **measured, not assumed**. Appendix A flags sign conventions as risk #1;
  guessing them would corrupt every angle downstream in a way that still looks like a plausible
  skeleton. Both cameras were exercised with four prompted movements and the readings recorded.
- Rear camera: up `Y-` (-0.903 m), anatomical right `X-` (-0.704 m), toward camera `Z-` (-0.207 m).
- Front camera (mirrored input): physical right reports as `LEFT`; anatomical right reads `X+`.
- After front-camera x-unmirroring and left/right relabelling, both cameras agree on canonical
  up `Y-`, anatomical right `X-`, toward camera `Z-`. This is what makes v1.2 possible.
- Evidence retained as image captures rather than described in prose, so the readings are auditable.

### Gate

- `gradlew.bat testDebugUnitTest --no-daemon` and `gradlew.bat assembleDebug --no-daemon` passed.
- Device gate: passed on Realme RMX3997, front and rear cameras.
- Evidence: `artifacts/axis-diagnostic/rear-valid/`, `artifacts/axis-diagnostic/front-final/`.

---

## v0.2 — Lock external choices — 2026-09-03

Source: §4.1, §11, §19. Status: complete.

### Changed

- Pinned the MediaPipe dependency and verified the Full pose model loads on the CPU delegate.
- Documented the QNN/GPU/CPU delegate boundary in [`docs/pose-runtime.md`](docs/pose-runtime.md).

### Reasoning

- External runtime choices were locked before any math was built on top of them. A later delegate
  or model swap changes landmark noise characteristics, which would invalidate filter tuning and
  reference statistics — cheaper to fix now than after v3.x references exist.
- CPU delegate confirmed as the working baseline; the accelerated paths are documented as an
  optimisation with a known boundary rather than an assumption.

### Gate

- JVM tests and `connectedDebugAndroidTest` passed on RMX3997; Full model loaded via CPU delegate.

---

## v0.1 — Make baseline repeatable — 2026-09-03

Source: §4.1, §15, §19. Status: complete.

### Changed

- Confirmed the Gradle build, JVM tests, debug APK, and physical-device install and launch.
- Moved the MediaPipe dependency from the root module to the `app` module.

### Reasoning

- Nothing is worth building until build → test → install → launch is repeatable on real hardware;
  otherwise every later failure is ambiguous between "my code" and "my toolchain".
- The MediaPipe dependency was declared at the root project, where it does not reach the app's
  compile classpath. Corrected to the `app` module.

### Gate

- `testDebugUnitTest assembleDebug` passed; ADB install and cold launch verified on RMX3997.
  `MainActivity` reached the resumed state.
