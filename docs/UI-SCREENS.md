# RightPosture — Screen Blueprint

The target screen architecture: every screen, what it's *for*, and what belongs on it. Written to be
handed to a designer as the base for the actual UI.

> **This describes the target, not the current build.** For exact strings, numbers, thresholds and
> the full bilingual phrase bank as they exist today, see [UI-CONTENT.md](UI-CONTENT.md). Where this
> document moves something, it says so.

---

## 1. The two people using this app

Everything below follows from these being genuinely different jobs, not two views of one job.

**The physio** opens the app between patients, for thirty seconds, to answer: *who did what, and does
anyone need me?* They also record exercises — a deliberate, focused task done once per exercise.

**The patient** opens the app to do their session. They're often across the room from a propped-up
phone. Everything during a set must be legible from 2–3 metres, and everything after it must be
readable in under a minute.

### Design principles that fall out of the domain

1. **Absent is not zero.** A measurement that wasn't taken renders as a gap, a dash, or an explicit
   "not scored" — never as 0, never as an empty bar at the floor. This is a clinical-safety rule, not
   a style choice, and it constrains every chart, badge and stat in this document.
2. **The clinician decides.** The app flags and evidences; it never advances a patient. Any "ready to
   progress" state must read as a recommendation with its supporting numbers attached.
3. **Legible across a room.** During a set, the three things that matter (rep count, quality, and the
   current instruction) must be readable at 2–3 m. Everything else is small.
4. **Say what to do next.** Every empty state and every failure state names the next action.
5. **One screen, one job.** See the density budget in §9.

---

## 2. Navigation model

The current app is a single back-stack with no persistent navigation — every home screen ends in a
stack of unrelated buttons. **Proposal: a bottom tab bar per role.**

### Patient — 3 tabs

| Tab | Purpose |
|---|---|
| **Today** | Do the session. The default landing screen. |
| **Progress** | See how it's going over time. |
| **Me** | Physio link, language, account. |

### Physio — 4 tabs

| Tab | Purpose |
|---|---|
| **Activity** | What patients did. The default landing screen. |
| **Patients** | The roster, and the drill-down into one patient. |
| **Library** | Recorded exercises; the entry to recording a new one. |
| **Me** | Account, language, role. |

Full-screen flows that take over (no tab bar): **recording an exercise**, **performing a set**, and
**onboarding**. These are focused tasks; a tab bar during a set is an invitation to lose the set.

---

## 3. Onboarding

### 3.1 Role choice — *part of sign-in, not its own screen*
**Purpose:** Establish which app this person is using.
**Where it lives:** On 3.2, beside the credentials. A dedicated role-picker screen was considered and
dropped: it makes first launch two screens instead of one, and the choice is trivially reversible
from Me, so it does not earn a screen of its own.

### 3.2 Sign in / Create account
**Purpose:** Get an account so data syncs — while making clear it's optional — and establish the role.
**Contains:** Email, password, primary action, a toggle between sign-in and sign-up, **the role
choice**, and a clearly secondary **"Continue without an account"** with the reassurance that
sessions stay on the phone until they sign in.
**Moved off:** Demo credential buttons should be behind a debug affordance (long-press the logo, or a
build-flavour flag), not a permanent block of the sign-in screen.

### 3.3 Link to your physio — *part of Me, not onboarding* *(patient)*
**Purpose:** One-time code entry.
**Where it lives:** On 4.5 Me, as *"Change clinic or enter referral code · Link New"*. It is not a
step between first launch and the first set, because a patient must be able to train without ever
reaching a server — and a screen that can always be skipped does not belong in a flow the patient
has to walk through.
**Contains:** A short explanation, a 6-character code input styled as a code (spaced, monospace,
large), and the primary action.

### 3.4 Camera permission
**Purpose:** Explain before asking.
**Contains:** One sentence on why the camera is needed and that nothing leaves the phone. One button.
Different copy for the blocked state, pointing at system settings.

---

## 4. Patient screens

### 4.1 Today — *patient home*
**Purpose:** Start today's work with zero navigation.

**Contains:**
- A short greeting line with the day's shape: e.g. *"3 exercises today · about 12 minutes"*.
- One card per prescribed exercise: category pill (Exercise / Hold), name, the prescription in
  human terms (*"3 × 10"* or *"3 × 30s hold"*), a completion tick if already done today, and a
  prominent **Start**.
- A subtle "recorded on this phone" group beneath, if any unprescribed local exercises exist.
- Empty state naming the next action (enter a code / ask your physio to record one).

**Moved off:** "My progress" (now the Progress tab), "Switch role" (now Me).

### 4.2 Session summary — *immediately after a set*
**Purpose:** Close the loop while the set is still fresh. This is the emotional payoff screen.

**Contains, in this order:**
1. A hero result: reps completed of prescribed, and the set quality as one number.
2. The written summary paragraph, with its numbers emphasised. Attribution line beneath it
   (*written on this phone* vs. *from measurements only*).
3. One clear next-step line.
4. A single link to full detail (§4.3).

**Deliberately light.** This is the screen a tired patient reads. Charts and rep-by-rep detail live
one tap deeper.

### 4.3 Session detail — *tabbed*
**Purpose:** The full record of one set, for whoever wants it.

| Tab | Contains |
|---|---|
| **Summary** | The written summary + attribution, headline counts, set score, "form started slipping at rep N" if applicable, interrupted-session warning if applicable. |
| **Reps** | One row per rep: the rep number, its score (or "not scored"), whether it counted, and a plain-language reason when it didn't. Best/worst/onset reps visually marked. |
| **Form** | The quality-per-rep chart with the change point marked, plus the joint-attribution bars and their one-line explanations. |

**Why split:** all of this currently sits on one scroll, and the rep list alone can be 12+ rows.

### 4.4 Progress — *tabbed*
**Purpose:** Answer "am I getting better?" — the question that keeps someone doing their rehab.

| Tab | Contains |
|---|---|
| **Overview** | 3–4 hero stat cards (sessions this week, median quality, best range, current streak). One combined sparkline. The progression status card (§8.2). Nothing else. |
| **Quality** | Quality-per-session trend chart, fixed 0–100 scale. A short "what moves this" explainer. Sessions listed beneath with their individual scores. |
| **Range** | Range-per-session trend, goal progress if a goal exists (otherwise an honest "no goal set yet" state), and the "your best three reps" explanation. |
| **History** | Chronological session list, tappable through to §4.3. Grouped by week. |

An exercise selector sits above the tabs (a chip row or dropdown) when the patient has more than one
exercise — progress is always *per exercise*, never averaged across different movements.

**Empty state:** before any session exists, Progress shows a single friendly card explaining that
finishing a set unlocks it — not four empty tabs.

### 4.5 Me *(patient)*
**Purpose:** The handful of settings that currently have no home.
**Contains:** Linked physio status **and the code entry (§3.3)**, voice/language choice, account
(sign in/out), switch role, app version.
**Moved here from:** the language toggle currently on the camera screen; "Switch role" currently at
the bottom of Today; the physio-link code entry, which was an onboarding screen.

---

## 5. Physio screens

### 5.1 Activity — *physio home*
**Purpose:** The thirty-second read. Who trained, and does anyone need attention?

**Contains:**
- A flag strip at the very top: any patient needing review or ready to progress, as compact cards.
  If nothing needs attention, one calm line saying so.
- A reverse-chronological feed of patient sessions: patient name, exercise, reps completed of
  prescribed, score, and a warning marker if interrupted or if form slipped. Tappable into §5.3.
- Sessions recorded on this phone in a separate, clearly secondary group.

**Moved off:** the exercise library (now Library tab), invite (now Patients), linked-patient roster
(now Patients), switch role (now Me). This screen currently carries seven sections — it's the reason
"I can't see any recent sets" was a real complaint.

### 5.2 Patients
**Purpose:** The roster and the entry to inviting.
**Contains:** One row per linked patient — name, last-active, adherence at a glance, and a flag badge
if they need review. A prominent **Invite a patient** action. Empty state explaining the invite flow.

### 5.3 Patient detail — *tabbed* — **new screen**
**Purpose:** Everything about one patient. The spec asks for this and it does not exist today.

| Tab | Contains |
|---|---|
| **Overview** | Adherence, recent activity, the progression verdict with its supporting reasons, and the approve-progression action when applicable. |
| **Progress** | The same charts as the patient's Progress tab, per assigned exercise, plus the joint-attribution trend the patient doesn't see. |
| **Programme** | Assigned exercises with prescriptions; edit, add, set goals (§5.6). |
| **Sessions** | That patient's session history, tappable into the session detail. |

### 5.4 Library
**Purpose:** The physio's recorded exercises, and the way to make a new one.
**Contains:** One row per exercise — name, category pill, version, recorded date, and a **Test**
action. A prominent **Record a new exercise**. Empty state explaining that a recording becomes the
reference everything is scored against.

### 5.5 Record an exercise — *full-screen wizard*
**Purpose:** Author a reference. One decision per step, because a wrong recording is discovered
expensively.

| Step | Contains |
|---|---|
| **1 · Choose** | Which exercise, and cyclic vs. hold. Shows the setup instruction and safety note for the choice. |
| **2 · Set up** | Live camera with the calibration checklist — each item ticking green as it passes, all items always visible, plus an explicit override that names what it's skipping. |
| **3 · Record** | Camera, minimal chrome, a large timer or countdown, and one clear stop. For holds: neutral capture → positioning countdown → recording, each a distinct state with its own instruction. |
| **4 · Review** | Accept/reject verdict, the trace chart with rep boundaries, the reference band, the validator results, and the watch list. Always a **"Looks wrong — re-record"**. |
| **5 · Name & save** | Name field, the note that this is what patients will see, and the version warning if the name already exists. |

### 5.6 Set a goal — **new screen**
**Purpose:** Capture the range goal that progression readiness needs.
**Contains:** The patient's current baseline, a target input in the same units, and a plain statement
of what changes when it's met.

**Why it matters:** without this, every progression verdict in the app reads *"No range goal set for
this exercise, so readiness cannot be assessed."* This one small screen turns the entire progression
feature on.

### 5.7 Invite a patient
**Purpose:** Mint a code and read it aloud.
**Contains:** The code, very large, spaced, monospace. Expiry. Regenerate. Then the assign step —
which patient, which exercise, what prescription.
**Suggested:** present as a bottom sheet from Patients rather than a full screen; it's a short task.

### 5.8 Me *(physio)*
**Purpose:** As §4.5 — account, language, switch role, version. Not yet designed; it mirrors 4.5
minus the physio-link entry.

---

## 6. Performing a set — *full screen, both roles*

The most constrained screen in the app. The patient is 2–3 m away and moving.

**Contains, in strict priority order:**
1. **The camera feed** with the skeleton overlay — fills most of the screen.
2. **The quality bar** — the single largest UI element, full-width, colour-coded. Renders *empty*,
   not zero-width-at-the-floor, when nothing can be scored.
3. **Rep counter** — `7 / 12`, very large.
4. **The current instruction** — one line, high contrast. Cues, rejections and status share this
   slot; only one speaks at a time.
5. Small: didn't-count tally, set score so far, technical readout (fps, model, delegate).

**Hold variant:** the rep counter is replaced by a large `M:SS` timer, plus attempt history
(*attempt 3 · last 24s · best 31s*) and the watched-joints line.

**Safety stop** (form has degraded past the threshold) overrides everything: full-width, alarm
colour, unmissable, with the instruction to stop.

**Rules:** no tab bar, no back-stack chrome, nothing tappable near the edges that could be hit while
setting the phone down. One escape action (**Finish set**), always in the same place.

---

## 7. Screen inventory

| # | Screen | Role | Type |
|---|---|---|---|
| 3.1 | Role choice | both | *part of 3.2* |
| 3.2 | Sign in / create account | both | onboarding |
| 3.3 | Link to your physio | patient | *part of 4.5* |
| 3.4 | Camera permission | both | system |
| 4.1 | Today | patient | tab |
| 4.2 | Session summary | patient | post-set |
| 4.3 | Session detail | both | tabbed (3) |
| 4.4 | Progress | patient | tabbed (4) |
| 4.5 | Me | patient | tab |
| 5.1 | Activity | physio | tab |
| 5.2 | Patients | physio | tab |
| 5.3 | Patient detail | physio | tabbed (4) · **new** |
| 5.4 | Library | physio | tab |
| 5.5 | Record an exercise | physio | wizard (5 steps) |
| 5.6 | Set a goal | physio | modal · **new** |
| 5.7 | Invite a patient | physio | sheet |
| 5.8 | Me | physio | tab |
| 6 | Performing a set | both | full screen |

---

## 8. Components to design

Building these once keeps every screen consistent and keeps the density budget honest.

### 8.1 Stat card
A single number with a label and optional trend arrow. Must have a defined **"no data"** rendering
that shows a dash and a short reason — never a zero.

### 8.2 Verdict card
The progression status: one of *needs review* (red) / *ready to progress* (green) / *keep going*
(neutral), the supporting reasons as short bullets, and — when ready — an explicit note that a
clinician decides. Used on both the patient's Progress > Overview and the physio's Patient detail.

### 8.3 Session row
Exercise name, date, reps completed of prescribed, score or "not scored", and warning markers for
interrupted / form-slipped. One row type used in every list of sessions.

### 8.4 Rep row
Rep number, score or "not scored", counted/not-counted state, and an optional plain-language reason.
Must handle the case where a rep **scored well and still didn't count** — these are independent
facts and the row has to show both without looking like a contradiction.

### 8.5 Chart card
A titled chart with a one-line caption explaining what a gap means. Three variants: quality-per-rep
(fixed 0–100, change-point marker), trend-per-session (hollow markers for unjudgeable sessions), and
joint-attribution bars (direction by colour, magnitude by length).

### 8.6 Category pill
Exercise vs. Hold. Two colours, always beside the exercise name.

### 8.7 Empty state
An illustration or icon, one sentence of explanation, and one action. Every list has one.

### 8.8 Checklist row
Tick/circle, label, colour by state. Used in calibration.

---

## 9. Density budget

The single most common problem in the current build is screens that grew a section at a time.

**Rules:**
- **Max 3 primary sections per screen.** A fourth means a tab or a deeper screen.
- **Max 1 primary action per screen.** Everything else is secondary or tertiary weight.
- **Above the fold belongs to the screen's job.** If the physio's job is reading what patients did,
  that content starts at the top — not below the exercise library.
- **Lists longer than ~5 rows get a "see all"** into a dedicated screen rather than growing inline.
- **Charts are never stacked more than 2 per tab.**

---

## 10. Tone

Calm and factual, warm without being cute. The app is telling someone how their rehab is going —
overclaiming reads as untrustworthy and underclaiming reads as discouraging.

- **Do:** *"You finished 7 of 12 reps."*, *"Form started slipping at rep 5."*, *"We couldn't score
  3 reps."*
- **Don't:** *"Amazing work!! 🎉"*, *"You crushed it!"*, or any celebration not tied to a real number.
- **Never:** anything that reads as diagnosis or treatment advice. No "your knee is injured", no
  "take a painkiller", no "push through it".

Encouragement comes from showing real improvement, not from exclamation marks.

---

## 11. Accessibility and physical context

- **2–3 m legibility** for everything on the performing screen.
- **Colour is never the only signal** — pair every colour with a word or an icon. Red/green matters
  here and red/green colour-blindness is common.
- **Every control has a spoken label**; charts have a text description of their trend.
- **Two languages ship today** (English, हिन्दी) and the language switch must be reachable without
  leaving a set — a patient who can't follow the cue shouldn't have to restart to change it.
- **Assume a propped-up phone**, sometimes in poor light, sometimes with the patient's hands busy or
  on a support.

---

## 12. What this proposal changes

| Change | Why |
|---|---|
| Bottom tab bar per role | No persistent navigation exists today; every home screen ends in a stack of unrelated buttons. |
| Physio home split into Activity / Patients / Library | It currently carries 7 sections on one scroll — the direct cause of a real "I can't find recent sets" complaint. |
| Session report split into Summary / Reps / Form | Currently one scroll with a summary, 4 stat lines, 2 charts and a 12-row rep list. |
| Progress split into Overview / Quality / Range / History | Currently a picker plus a single dense scroll; explicitly requested as tabs. |
| New: Patient detail | The spec asks for it; the physio currently cannot drill into one patient at all. |
| New: Set a goal | Without it, every progression verdict says "no goal set" and the feature can't fire. |
| New: Me / settings, per role | Language currently lives on the camera screen and "Switch role" is a stray link on two home screens. |
| Post-set summary made deliberately light | The payoff screen shouldn't open with a chart and a 12-row table. |
| Demo credentials moved behind a debug affordance | They're a permanent block on the sign-in screen today. |

---

## 13. Reference

- **[UI-CONTENT.md](UI-CONTENT.md)** — exact strings, numbers, thresholds, colour values, chart
  specifications and the full bilingual phrase bank as they exist in the current build. Use it for
  real copy and real data when designing these screens; don't invent placeholder numbers, because
  several of them (quality bands, the 6-rep minimum, the 80/100 pass bar) carry clinical meaning.
