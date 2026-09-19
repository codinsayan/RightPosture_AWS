package com.example.rightposture.nav

/**
 * §2.4's two roles, as a navigation state machine.
 *
 * Hand-rolled rather than Navigation Compose, for one reason: §7.2's gate is "navigation state tests
 * and a back-stack smoke test", and a plain immutable stack is testable in the JVM suite the rest of
 * this project runs in. Navigation Compose would push those same tests onto an instrumented device,
 * which is a worse place to find out the back stack is wrong.
 *
 * There are five screens, not §14's fourteen. §7.2 says to defer the decorative ones, and most of
 * §14's table cannot be built honestly yet: a patient list needs 7.4's linking, progression curves
 * need 8.4, and a demo screen needs a `demo.mp4` nothing records. Building empty shells of them now
 * would be scaffolding that has to be rewritten once the data behind them exists.
 */

enum class Role {
    PHYSIO,
    PATIENT,
}

sealed interface Screen {
    /**
     * §7.3's sign-in. The first screen when a backend is configured; skipped entirely when one is
     * not, because §13.3 will not let an unreachable server stop someone exercising.
     */
    data object SignIn : Screen

    /** The local role choice, used when there is no backend or the user continued without one. */
    data object RolePicker : Screen

    /**
     * §5.1's "Activity" - the physio home, and where the role lands.
     *
     * It used to be everything at once: the feed, the library, the invite, the roster. The other
     * three physio tabs below exist because those are different jobs, and a screen doing four of
     * them does none of them in thirty seconds.
     */
    data object PhysioActivity : Screen

    /** §5.2's roster, and the entry to inviting. */
    data object PhysioPatients : Screen

    /**
     * §5.3's patient detail: one patient, in four tabs.
     *
     * Carries the id rather than the patient, because the roster is refetched whenever the physio
     * arrives at a screen that needs it - and a [LinkedPatient] captured into the back stack would
     * be a stale copy of a row the server has since renamed.
     */
    data class PhysioPatientDetail(val patientId: String) : Screen

    /** §5.4's recorded references, and the entry to recording one. */
    data object PhysioLibrary : Screen

    /** §5.8's account and role switch. Minimal until it is built in its own right. */
    data object PhysioMe : Screen

    /**
     * §5.5 step 1: which *kind* of movement is about to be recorded.
     *
     * A step of its own rather than controls on the camera screen, and the only question it asks.
     * The app offers no list of exercises: the physio demonstrates whatever movement they are
     * prescribing and names it at step 5, because the whole design is that their own recording is
     * the clinical standard. The kind has to be settled first because §6.19's hold is not a variant
     * of the cyclic path, it is a different pipeline with no phase axis.
     */
    data object RecordChoose : Screen

    /**
     * §5.5 steps 2 to 5: set up, record, review, name and save - one continuous camera flow.
     *
     * [hold] is step 1's choice, carried so this step opens in the right mode and offers only the
     * one recording control that applies.
     */
    data class Authoring(val hold: Boolean = false) : Screen

    /**
     * §4.2's post-set summary, shown automatically the moment a set ends.
     *
     * Separate from [SessionReport] because they answer different questions. This one is read by a
     * tired patient who has just put the phone down and wants to know how it went; the report is
     * read by whoever wants the rep-by-rep record. Fusing them, which is what the app did, produced
     * one scroll that served neither.
     */
    data class SessionSummary(val sessionId: String) : Screen

    /**
     * §4.3's session detail: the full record of one set, tabbed into summary, reps and form.
     *
     * Reached from five places - the summary that follows a set, the patient's history, a completed
     * row on Today, the physio's activity feed and their patient detail - and it returns to
     * whichever of them opened it, which is what the plain back stack already does.
     */
    data class SessionDetail(val sessionId: String) : Screen

    /** §0's physio half: mint an invite code, and prescribe to whoever redeemed one. */
    data object InvitePatient : Screen

    /** §14.2's "Today". */
    data object PatientToday : Screen

    /** §0's patient half: enter the code once. Always skippable - see [RedeemInviteScreen]. */
    data object RedeemInvite : Screen

    /**
     * §4.5's "Me", the third patient tab.
     *
     * It exists because the settings it holds - the physio link, the role switch - used to be a
     * stack of buttons at the bottom of Today, and Today's job is starting a set. Moving them here
     * is what lets Today end at its last exercise.
     */
    data object PatientMe : Screen

    /**
     * §14.2's calibration and exercise, which are one flow: §6.20 is a gate on starting the set,
     * not a screen the patient can leave and come back to.
     */
    data class PatientExercise(val exerciseId: String, val exerciseVersion: Int) : Screen

    /**
     * The physio performing their own reference, to check it scores the way they intended.
     *
     * Mechanically identical to [PatientExercise] - same calibration gate, same engine, same
     * scoring - and separate only so that finishing returns to the exercise library rather than to
     * a patient Today screen the physio never came from. A physio who cannot try a reference before
     * prescribing it is guessing, and the reference is the thing every future session is scored
     * against.
     */
    data class TestExercise(val exerciseId: String, val exerciseVersion: Int) : Screen

    /**
     * §4.4's "Progress", and the physio's progression curves - the same screen.
     *
     * A null [exerciseId] means "whatever the patient has most history for", resolved on arrival;
     * a set one is a deep link to that exercise. Which exercise is shown after that is the chip
     * row's business rather than the back stack's, because switching exercise is changing the
     * subject of one screen, not moving to another - and a patient with five exercises should not
     * need five back presses to leave.
     */
    data class Progress(val exerciseId: String?) : Screen
}

/** Where a role starts. §7.2's gate is that both of these are reachable. */
fun Role.home(): Screen = when (this) {
    Role.PHYSIO -> Screen.PhysioActivity
    Role.PATIENT -> Screen.PatientToday
}

/**
 * An immutable back stack.
 *
 * Immutable because it crosses into composition, and a mutable stack read during a recomposition is
 * the same race `FrameResult` is immutable to avoid.
 */
data class NavState(val stack: List<Screen>) {

    init {
        require(stack.isNotEmpty()) { "a navigation stack is never empty" }
    }

    val current: Screen get() = stack.last()

    val canGoBack: Boolean get() = stack.size > 1

    fun navigateTo(screen: Screen): NavState {
        // Navigating to the screen already on top is a double tap, not a new entry. Without this a
        // back press appears to do nothing, because it pops to an identical screen.
        if (screen == current) return this
        return NavState(stack + screen)
    }

    /** Pops one entry, or returns null when there is nothing left to pop and the app should exit. */
    fun back(): NavState? = if (canGoBack) NavState(stack.dropLast(1)) else null

    /**
     * Replaces the whole stack.
     *
     * Used when the role changes: a physio's authoring screen has no meaning in a patient's history,
     * and leaving it below would let a back press cross between roles.
     */
    fun resetTo(screen: Screen): NavState = NavState(listOf(screen))

    /**
     * Pops back to [screen] if it is in the stack, otherwise resets to it.
     *
     * This is what "finish and return home" needs. Pushing home on top instead would grow the stack
     * on every set, so a patient who did five exercises would need five back presses to leave.
     */
    fun popTo(screen: Screen): NavState {
        val index = stack.indexOfLast { it == screen }
        return if (index >= 0) NavState(stack.take(index + 1)) else resetTo(screen)
    }

    companion object {
        fun startingAt(screen: Screen) = NavState(listOf(screen))
    }
}
