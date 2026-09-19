package com.example.rightposture.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.rightposture.camera.CameraPreviewRoute
import com.example.rightposture.core.rpose.RposeBundle
import com.example.rightposture.core.rpose.readRpose
import com.example.rightposture.ui.prescriptionLine
import com.example.rightposture.data.ExerciseEntity
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.data.SessionEntity
import com.example.rightposture.llm.LlmBackend
import com.example.rightposture.llm.OnDeviceLlm
import com.example.rightposture.core.progression.evaluateProgression
import com.example.rightposture.report.AnalyticsData
import com.example.rightposture.report.ExerciseProgression
import com.example.rightposture.report.analyticsOf
import com.example.rightposture.report.sessionMetricsOf
import com.example.rightposture.sync.Account
import com.example.rightposture.sync.AuthOutcome
import com.example.rightposture.sync.AuthRepository
import com.example.rightposture.sync.CreateInviteOutcome
import com.example.rightposture.sync.ExerciseSync
import com.example.rightposture.sync.InviteRepository
import com.example.rightposture.sync.LinkedPatient
import com.example.rightposture.sync.LocalOnlyAuthRepository
import com.example.rightposture.sync.Prescription
import com.example.rightposture.sync.RedeemOutcome
import com.example.rightposture.sync.RemoteSession
import com.example.rightposture.sync.SessionSync
import com.example.rightposture.sync.SessionSyncWorker
import com.example.rightposture.sync.SupabaseAuthRepository
import com.example.rightposture.sync.SupabaseClient
import com.example.rightposture.sync.SupabaseConfig
import com.example.rightposture.sync.SyncOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.rightposture.BuildConfig
import java.io.File
import java.util.Calendar

/**
 * 7.2's host: one place that owns the back stack and the role, so no screen has to know how it was
 * reached.
 *
 * The lists are re-read whenever the stack changes rather than held in a long-lived observable.
 * Room's Flow support would be tidier, but the data changes exactly twice - a new reference is
 * authored, or a set finishes - and both of those are navigations. Reloading on navigation is
 * simpler than a subscription and cannot go stale.
 */
@Composable
fun AppHost() {
    val context = LocalContext.current
    val roleStore = remember { RoleStore(context) }
    val database = remember { RightPostureDatabase.get(context) }

    // 7.3. A build with no URL and key configured gets the local-only repository, and the app
    // behaves exactly as it did before this iteration - which is what keeps 7.1's airplane-mode
    // gate and §13.3 true.
    val config = remember { SupabaseConfig.fromBuildConfig() }
    val client = remember { config.takeIf { it.isConfigured }?.let { SupabaseClient(it) } }
    val auth: AuthRepository = remember {
        client?.let { SupabaseAuthRepository(it, config) } ?: LocalOnlyAuthRepository
    }
    // 7.4. Null on an unconfigured build, which is what removes the invite controls from the UI
    // rather than showing ones that cannot work.
    val invites = remember { client?.let { InviteRepository(it) } }
    val sync = remember {
        client?.let {
            ExerciseSync(
                client = it,
                exercises = database.exercises(),
                assignments = database.assignments(),
                bundleDirectory = File(context.filesDir, "exercises"),
            )
        }
    }
    // 8.3. Null when no model has been side-loaded, which is a supported state: §10.5's template
    // covers every summary and the app behaves exactly as it did before this iteration.
    val llm = remember {
        OnDeviceLlm(context, OnDeviceLlm.defaultModelFile(context)).takeIf { it.isAvailable }
    }
    var llmBackend by remember { mutableStateOf<LlmBackend?>(null) }

    // §11.2's pre-warm happens in the navigation effect below, which fires on first composition
    // as well as on every later move. One path rather than two, so a launch-time warm-up and a
    // navigation-driven one cannot race for the engine mutex.

    var account by remember { mutableStateOf<Account?>(null) }
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    // 7.4's invite state.
    val sessionSync = remember {
        client?.let { SessionSync(it, database.sessions(), database.events()) }
    }
    var remoteSessions by remember { mutableStateOf<List<RemoteSession>>(emptyList()) }

    var inviteCode by remember { mutableStateOf<String?>(null) }
    var inviteExpiry by remember { mutableStateOf<String?>(null) }
    var patients by remember { mutableStateOf<List<LinkedPatient>>(emptyList()) }
    // The roster row §5.3 was opened from, kept so the detail screen survives a failed refresh.
    var openPatient by remember { mutableStateOf<LinkedPatient?>(null) }
    var linkedPhysio by remember { mutableStateOf<String?>(null) }
    var syncBusy by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var syncError by remember { mutableStateOf<String?>(null) }

    var role by remember { mutableStateOf(roleStore.role()) }
    var nav by remember {
        mutableStateOf(
            NavState.startingAt(
                when {
                    role != null -> role!!.home()
                    auth.configured -> Screen.SignIn
                    else -> Screen.RolePicker
                },
            ),
        )
    }
    var exercises by remember { mutableStateOf<List<ExerciseEntity>>(emptyList()) }
    var sessions by remember { mutableStateOf<List<SessionEntity>>(emptyList()) }
    var today by remember { mutableStateOf<List<TodayItem>>(emptyList()) }
    var progression by remember { mutableStateOf<List<ExerciseProgression>>(emptyList()) }
    var analyticsEntries by remember { mutableStateOf<List<AnalyticsEntry>>(emptyList()) }
    var analytics by remember { mutableStateOf<AnalyticsData?>(null) }
    var analyticsLoading by remember { mutableStateOf(false) }
    var startBundle by remember { mutableStateOf<RposeBundle?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val activity = LocalContext.current as? androidx.activity.ComponentActivity
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(nav.current, account?.id) {
        exercises = withContext(Dispatchers.IO) { database.exercises().all() }
        sessions = withContext(Dispatchers.IO) { database.sessions().recentSessions(10) }
        today = withContext(Dispatchers.IO) { todayItems(database, account?.id) }
        progression = withContext(Dispatchers.IO) { progressionFor(database) }
    }

    // §14.1's curves. Loaded only on the analytics screen because it is the only reader, and the
    // rest of the app pays nothing for a screen nobody opened.
    LaunchedEffect(nav.current) {
        val screen = nav.current as? Screen.Progress ?: return@LaunchedEffect
        // Cleared before the load, not after. Moving from one exercise to another re-runs this
        // effect with the previous exercise's data still held, and a chart labelled "hamstring"
        // drawn from the knee's sessions is a wrong clinical reading, not a flicker.
        analytics = null
        analyticsLoading = true
        val entries = withContext(Dispatchers.IO) { analyticsEntriesOf(database) }
        analyticsEntries = entries
        // With no exercise named, open on the one with the most history rather than on a picker.
        // A patient with a single exercise should never have to choose it, and the chip row above
        // the tabs is how anyone with several changes their mind.
        val exerciseId = screen.exerciseId ?: entries.firstOrNull()?.exerciseId
        if (exerciseId != null) {
            analytics = withContext(Dispatchers.IO) { analyticsFor(database, exerciseId) }
        }
        analyticsLoading = false
    }

    // 7.4's pull. Fired when a signed-in patient arrives at Today, not on a timer: a set is the
    // only thing that makes the list stale, and §13.3 means an unreachable server here costs the
    // patient nothing - the exercises already on disk are still listed and still startable.
    LaunchedEffect(nav.current, account?.id) {
        val id = account?.id ?: return@LaunchedEffect
        if (nav.current != Screen.PatientToday || role != Role.PATIENT) return@LaunchedEffect

        // 7.5. Queued before the pull, because anything performed offline should be on its way up
        // before new work comes down. WorkManager owns the timing and the backoff from here.
        SessionSyncWorker.enqueue(context, id, client?.accessToken)

        val pulled = sync?.pullAssignments(id)
        if (pulled is SyncOutcome.Done && pulled.count > 0) {
            today = withContext(Dispatchers.IO) { todayItems(database, id) }
        }
    }

    // 7.5's other half: §14.1's report. The physio reads sets performed on their own exercises,
    // which is a row the RLS policy decides rather than a filter this query sends.
    LaunchedEffect(nav.current, account?.id) {
        val id = account?.id ?: return@LaunchedEffect
        if (role != Role.PHYSIO) return@LaunchedEffect
        val screen = nav.current
        val wantsReport = screen == Screen.PhysioActivity ||
            screen == Screen.PhysioPatients ||
            screen is Screen.PhysioPatientDetail
        if (!wantsReport) return@LaunchedEffect
        // The feed reads the most recent sets across everybody; §5.3 reads one patient's whole
        // history out of the same list, and fifty rows shared between six patients is a handful
        // each. A deeper page is the same query with a different bound - not a different read.
        val limit = if (screen is Screen.PhysioPatientDetail) 250 else 50
        remoteSessions = sessionSync?.pullReport(id, limit).orEmpty()
        // The roster is a separate question from the report: somebody can be linked and have done
        // nothing yet, and the physio needs to see them to prescribe anything at all.
        patients = invites?.linkedPatients(id).orEmpty()
    }

    // One back handler for the whole app. `back()` returning null means the stack is at its root,
    // and the system should get the press - otherwise the app would trap the user on the home
    // screen with no way out.
    BackHandler(enabled = true) {
        val popped = nav.back()
        if (popped == null) activity?.finish() else nav = popped
    }

    // §11.3. The pose pipeline must have the accelerator to itself while a set is scored, so the
    // model is unloaded on the way *into* an exercise rather than on the way out - by the time a
    // set has ended, contention would already be in the data.
    //
    // Re-warming on the way out is the other half of the same rule. Without it the model is
    // released once and never comes back, so every summary after the first set of the session
    // silently falls to the template - which is the failure mode §10.5 is meant to catch, not one
    // it should be causing.
    LaunchedEffect(nav.current) {
        if (llm == null) return@LaunchedEffect
        val scoring = nav.current is Screen.PatientExercise || nav.current is Screen.TestExercise
        if (scoring) {
            if (llm.isLoaded) {
                llm.releaseForScoring()
                llmBackend = null
            }
        } else if (!llm.isLoaded) {
            llmBackend = llm.warmUp()
        }
    }

    when (val screen = nav.current) {
        Screen.SignIn -> SignInScreen(
            busy = authBusy,
            error = authError,
            onSignIn = { email, password ->
                authBusy = true
                authError = null
                scope.launch {
                    when (val outcome = auth.signIn(email, password)) {
                        is AuthOutcome.SignedIn -> {
                            account = outcome.account
                            // The account's role wins over any local choice: 7.3 makes role a
                            // property of the person, not of the handset.
                            roleStore.setRole(outcome.account.role)
                            role = outcome.account.role
                            nav = nav.resetTo(outcome.account.role.home())
                        }
                        is AuthOutcome.Rejected -> authError = outcome.message
                        AuthOutcome.Offline -> authError =
                            "Can't reach the server. You can still work offline."
                    }
                    authBusy = false
                }
            },
            onSignUp = { email, password, chosen ->
                authBusy = true
                authError = null
                scope.launch {
                    when (val outcome = auth.signUp(email, password, chosen)) {
                        is AuthOutcome.SignedIn -> {
                            account = outcome.account
                            roleStore.setRole(outcome.account.role)
                            role = outcome.account.role
                            nav = nav.resetTo(outcome.account.role.home())
                        }
                        is AuthOutcome.Rejected -> authError = outcome.message
                        AuthOutcome.Offline -> authError =
                            "Can't reach the server. You can still work offline."
                    }
                    authBusy = false
                }
            },
            onContinueOffline = { nav = nav.resetTo(Screen.RolePicker) },
        )

        Screen.RolePicker -> RolePickerScreen(
            onPick = {
                roleStore.setRole(it)
                role = it
                nav = nav.resetTo(it.home())
            },
        )

        Screen.PhysioActivity -> PhysioActivityScreen(
            remoteSessions = remoteSessions,
            localSessions = sessions,
            exerciseNames = exercises.associate { it.id to it.name },
            progression = progression,
            onOpenSession = { nav = nav.navigateTo(Screen.SessionDetail(it)) },
            // A flag opens the evidence behind it, and the evidence is the exercise's own curves:
            // §6.21's verdict is computed per exercise over this device's sessions, not per
            // patient, so §5.3's patient detail would be a different claim than the one flagged.
            onOpenFlag = { nav = nav.navigateTo(Screen.Progress(it)) },
            onOpenPatients = { nav = nav.navigateTo(Screen.PhysioPatients) },
            onOpenLibrary = { nav = nav.navigateTo(Screen.PhysioLibrary) },
            onOpenMe = { nav = nav.navigateTo(Screen.PhysioMe) },
        )

        Screen.PhysioPatients -> PhysioPatientsScreen(
            patients = patients,
            // Inviting needs both a backend and somebody to be the physio on the invite row.
            canInvite = invites != null && account != null,
            onInvite = {
                syncMessage = null
                syncError = null
                nav = nav.navigateTo(Screen.InvitePatient)
            },
            onOpenPatient = {
                openPatient = it
                nav = nav.navigateTo(Screen.PhysioPatientDetail(it.id))
            },
            onOpenActivity = { nav = nav.popTo(Screen.PhysioActivity) },
            onOpenLibrary = { nav = nav.popTo(Screen.PhysioActivity).navigateTo(Screen.PhysioLibrary) },
            onOpenMe = { nav = nav.popTo(Screen.PhysioActivity).navigateTo(Screen.PhysioMe) },
        )

        is Screen.PhysioPatientDetail -> {
            // The roster is refetched whenever a physio screen is entered, and a refetch that
            // fails comes back as an empty list - which would otherwise make the patient the
            // physio is *currently reading* vanish and bounce them back to the list. The row they
            // tapped is held here so an unreachable server costs a refresh, not the screen.
            val patient = patients.firstOrNull { it.id == screen.patientId }
                ?: openPatient?.takeIf { it.id == screen.patientId }
            if (patient == null) {
                LaunchedEffect(screen.patientId) { nav = nav.popTo(Screen.PhysioPatients) }
            } else {
                PhysioPatientDetailScreen(
                    patient = patient,
                    // §14.1's report, filtered here rather than at the server: the policy already
                    // decides which rows this physio may read, and a `patient_id` filter on top
                    // would either restate that rule or quietly disagree with it.
                    sessions = remoteSessions.filter { it.patientId == patient.id },
                    exerciseNames = exercises.associate { it.id to it.name },
                    llm = llm,
                    backend = llmBackend,
                    onBack = { nav = nav.popTo(Screen.PhysioPatients) },
                )
            }
        }

        Screen.PhysioLibrary -> PhysioLibraryScreen(
            exercises = exercises,
            onRecordNew = { nav = nav.navigateTo(Screen.RecordChoose) },
            onTestExercise = { exercise ->
                val loaded = runCatching {
                    File(exercise.rposePath).inputStream().use { readRpose(it) }
                }.getOrNull()
                if (loaded == null) {
                    loadError = "That reference could not be opened. Record it again."
                } else {
                    startBundle = loaded
                    nav = nav.navigateTo(
                        Screen.TestExercise(exercise.id, exercise.version),
                    )
                }
            },
            onOpenActivity = { nav = nav.popTo(Screen.PhysioActivity) },
            onOpenPatients = { nav = nav.popTo(Screen.PhysioActivity).navigateTo(Screen.PhysioPatients) },
            onOpenMe = { nav = nav.popTo(Screen.PhysioActivity).navigateTo(Screen.PhysioMe) },
        )

        Screen.PhysioMe -> PhysioMeScreen(
            appVersion = BuildConfig.VERSION_NAME,
            onSwitchRole = {
                roleStore.clear()
                role = null
                account = null
                scope.launch { auth.signOut() }
                nav = nav.resetTo(if (auth.configured) Screen.SignIn else Screen.RolePicker)
            },
            onOpenActivity = { nav = nav.popTo(Screen.PhysioActivity) },
            onOpenPatients = { nav = nav.popTo(Screen.PhysioActivity).navigateTo(Screen.PhysioPatients) },
            onOpenLibrary = { nav = nav.popTo(Screen.PhysioActivity).navigateTo(Screen.PhysioLibrary) },
        )

        is Screen.Progress -> ProgressScreen(
            entries = analyticsEntries,
            selected = analytics,
            loading = analyticsLoading,
            llm = llm,
            backend = llmBackend,
            onPick = { picked ->
                // Replaces rather than pushes: the chip row changes the subject of this screen.
                nav = nav.back()?.navigateTo(Screen.Progress(picked))
                    ?: nav.resetTo(Screen.Progress(picked))
            },
            onOpenSession = { nav = nav.navigateTo(Screen.SessionDetail(it)) },
            onOpenToday = { nav = nav.popTo(Screen.PatientToday) },
            onOpenMe = { nav = nav.popTo(Screen.PatientToday).navigateTo(Screen.PatientMe) },
        )

        Screen.RecordChoose -> RecordChooseScreen(
            onCancel = { nav = nav.popTo(Screen.PhysioLibrary) },
            onContinue = { hold -> nav = nav.navigateTo(Screen.Authoring(hold)) },
        )

        is Screen.Authoring -> CameraPreviewRoute(
            authoringHold = screen.hold,
            // Saving is the end of authoring. Staying on the camera afterwards leaves the physio
            // looking at a viewfinder with nothing left to do, and the exercise they just named is
            // on the library screen behind them. It lands on the library rather than on Activity,
            // because that is where the new reference now is.
            onSaved = { nav = nav.popTo(Screen.PhysioLibrary) },
        )

        Screen.InvitePatient -> InvitePatientScreen(
            code = inviteCode,
            codeExpiry = inviteExpiry,
            patients = patients,
            exercises = exercises,
            busy = syncBusy,
            message = syncMessage,
            error = syncError,
            onGenerate = {
                val physio = account ?: return@InvitePatientScreen
                syncBusy = true
                syncError = null
                syncMessage = null
                scope.launch {
                    when (val outcome = invites?.create(physio.id)) {
                        is CreateInviteOutcome.Created -> {
                            inviteCode = outcome.code
                            inviteExpiry = outcome.expiresAt.asShortDate()
                        }
                        CreateInviteOutcome.Offline -> syncError =
                            "Can't reach the server. Try again when you're online."
                        is CreateInviteOutcome.Failed -> syncError = outcome.message
                        null -> Unit
                    }
                    syncBusy = false
                }
            },
            onRefresh = {
                val physio = account ?: return@InvitePatientScreen
                syncBusy = true
                scope.launch {
                    patients = invites?.linkedPatients(physio.id) ?: emptyList()
                    syncBusy = false
                }
            },
            onAssign = { patient, exercise ->
                val physio = account ?: return@InvitePatientScreen
                syncBusy = true
                syncError = null
                syncMessage = null
                scope.launch {
                    // The reference has to be on the server before it can be prescribed, and the
                    // physio has no reason to think of those as two steps. Push, then assign.
                    val pushed = sync?.pushExercise(exercise, physio.id)
                    val assigned = if (pushed is SyncOutcome.Done) {
                        sync?.assign(
                            Prescription(
                                exerciseId = exercise.id,
                                exerciseVersion = exercise.version,
                                patientId = patient.id,
                                reps = DEFAULT_REPS,
                                sets = DEFAULT_SETS,
                                restSeconds = DEFAULT_REST_SECONDS,
                                sequenceIndex = 0,
                            ),
                        )
                    } else {
                        pushed
                    }
                    when (assigned) {
                        is SyncOutcome.Done -> syncMessage =
                            "${exercise.name} sent to ${patient.name ?: patient.email}."
                        SyncOutcome.Offline -> syncError =
                            "Can't reach the server. Nothing was sent - try again when you're online."
                        is SyncOutcome.Failed -> syncError = assigned.message
                        null -> Unit
                    }
                    syncBusy = false
                }
            },
            onBack = { nav = nav.back() ?: nav },
        )

        Screen.RedeemInvite -> RedeemInviteScreen(
            busy = syncBusy,
            error = syncError,
            onRedeem = { typed ->
                syncBusy = true
                syncError = null
                scope.launch {
                    when (val outcome = invites?.redeem(typed)) {
                        is RedeemOutcome.Linked -> {
                            linkedPhysio = outcome.physioId
                            account?.id?.let { sync?.pullAssignments(it) }
                            today = withContext(Dispatchers.IO) { todayItems(database, account?.id) }
                            nav = nav.popTo(Screen.PatientToday)
                        }
                        // Every one of these is worded so the patient knows what to do next, which
                        // is why the server bothers to tell them apart. "Invalid code" would send
                        // someone with an expired code to retype a code that was never wrong.
                        RedeemOutcome.Malformed -> syncError =
                            "That doesn't look like a code. It's six letters and numbers."
                        RedeemOutcome.Unknown -> syncError =
                            "No code like that. Check it with your physiotherapist."
                        RedeemOutcome.AlreadyUsed -> syncError =
                            "That code has already been used. Ask for a new one."
                        RedeemOutcome.Expired -> syncError =
                            "That code has expired. Ask for a new one."
                        RedeemOutcome.NotSignedIn -> syncError =
                            "Sign in first, then enter your code."
                        RedeemOutcome.Offline -> syncError =
                            "Can't reach the server. You can still do the exercises on this phone."
                        is RedeemOutcome.Failed -> syncError = outcome.message
                        null -> Unit
                    }
                    syncBusy = false
                }
            },
            onSkip = { nav = nav.popTo(Screen.PatientToday) },
        )

        Screen.PatientToday -> {
            loadError?.let { LoadFailure(it) { loadError = null } } ?: PatientTodayScreen(
                items = today,
                linkedTo = linkedPhysio,
                canLink = invites != null && account != null,
                onStart = { item ->
                    val bundle = runCatching {
                        File(item.rposePath).inputStream().use { readRpose(it) }
                    }
                    val loaded = bundle.getOrNull()
                    if (loaded == null) {
                        // The row survives but the file did not - reinstall, cleared storage, or a
                        // half-written bundle. Say so rather than opening a camera that will never
                        // score anything.
                        loadError = "That exercise's reference could not be opened. Ask your " +
                            "physiotherapist to record it again."
                    } else {
                        startBundle = loaded
                        nav = nav.navigateTo(
                            Screen.PatientExercise(item.exerciseId, item.exerciseVersion),
                        )
                    }
                },
                onOpenSession = { item ->
                    // The row is tappable once it is done, and it opens the set that made it done.
                    sessions.firstOrNull { it.exerciseId == item.exerciseId }?.let {
                        nav = nav.navigateTo(Screen.SessionDetail(it.id))
                    }
                },
                onEnterCode = {
                    syncError = null
                    nav = nav.navigateTo(Screen.RedeemInvite)
                },
                onOpenProgress = { nav = nav.navigateTo(Screen.Progress(null)) },
                onOpenMe = { nav = nav.navigateTo(Screen.PatientMe) },
            )
        }

        Screen.PatientMe -> PatientMeScreen(
            linkedTo = linkedPhysio,
            canLink = invites != null && account != null,
            appVersion = BuildConfig.VERSION_NAME,
            onEnterCode = {
                syncError = null
                nav = nav.navigateTo(Screen.RedeemInvite)
            },
            onSwitchRole = {
                roleStore.clear()
                role = null
                account = null
                linkedPhysio = null
                scope.launch { auth.signOut() }
                nav = nav.resetTo(if (auth.configured) Screen.SignIn else Screen.RolePicker)
            },
            onOpenToday = { nav = nav.popTo(Screen.PatientToday) },
            onOpenProgress = { nav = nav.navigateTo(Screen.Progress(null)) },
        )

        is Screen.PatientExercise -> {
            val bundle = startBundle
            if (bundle == null) {
                // Reached without a loaded bundle, which means process death restored the stack
                // but not the in-memory reference. Going home is recoverable; a null-check crash
                // in front of a patient is not.
                LaunchedEffect(Unit) { nav = nav.popTo(Screen.PatientToday) }
            } else {
                CameraPreviewRoute(
                    startBundle = bundle,
                    onFinished = {
                        // 7.5. Queued the moment the set ends rather than on next launch: the
                        // patient may not open the app again for a day, and the physio should not
                        // have to wait for that to see what happened.
                        account?.id?.let { SessionSyncWorker.enqueue(context, it, client?.accessToken) }
                        scope.launch {
                            sessions = withContext(Dispatchers.IO) {
                                database.sessions().recentSessions(10)
                            }
                            today = withContext(Dispatchers.IO) { todayItems(database, account?.id) }
                            // The set that just ended is the newest row. Read back rather than
                            // passed down, so the summary shows what was actually written to disk
                            // rather than what the camera screen believed it wrote.
                            val finished = sessions.firstOrNull()
                            nav = if (finished == null) {
                                nav.popTo(Screen.PatientToday)
                            } else {
                                // The set screen is dropped from the stack on the way past: §6 says
                                // a finished set can never be reversed into.
                                nav.popTo(Screen.PatientToday)
                                    .navigateTo(Screen.SessionSummary(finished.id))
                            }
                            // Cleared last, and that order matters. Clearing it first re-composes
                            // this branch with a null bundle, which is indistinguishable from
                            // process death - so the recovery below fires and sends the patient
                            // home, throwing away the summary they just earned.
                            startBundle = null
                        }
                    },
                )
            }
        }

        is Screen.TestExercise -> {
            val bundle = startBundle
            if (bundle == null) {
                // Same recovery as the patient path: process death restored the stack but not the
                // in-memory bundle. Going back to the library beats crashing.
                LaunchedEffect(Unit) { nav = nav.popTo(Screen.PhysioActivity) }
            } else {
                CameraPreviewRoute(
                    startBundle = bundle,
                    onFinished = {
                        startBundle = null
                        // Deliberately not enqueued for sync. This set is the physio checking their
                        // own reference on their own phone; pushing it would put a session on a
                        // patient report that no patient performed.
                        nav = nav.popTo(Screen.PhysioActivity)
                    },
                )
            }
        }

        is Screen.SessionSummary -> {
            var summaryData by remember(screen.sessionId) {
                mutableStateOf<SessionSummaryData?>(null)
            }
            var summaryLoading by remember(screen.sessionId) { mutableStateOf(true) }
            LaunchedEffect(screen.sessionId) {
                summaryData = withContext(Dispatchers.IO) {
                    sessionSummaryOf(database, screen.sessionId, account?.id)
                }
                summaryLoading = false
            }
            SessionSummaryScreen(
                data = summaryData,
                loading = summaryLoading,
                database = database,
                llm = llm,
                backend = llmBackend,
                onStartNextSet = {
                    val exerciseId = summaryData?.session?.exerciseId
                    today.firstOrNull { it.exerciseId == exerciseId }?.let { item ->
                        val loaded = runCatching {
                            File(item.rposePath).inputStream().use { readRpose(it) }
                        }.getOrNull()
                        if (loaded == null) {
                            loadError = "That exercise's reference could not be opened. Ask your " +
                                "physiotherapist to record it again."
                            nav = nav.popTo(Screen.PatientToday)
                        } else {
                            startBundle = loaded
                            nav = nav.popTo(Screen.PatientToday).navigateTo(
                                Screen.PatientExercise(item.exerciseId, item.exerciseVersion),
                            )
                        }
                    }
                },
                onOpenDetail = { nav = nav.navigateTo(Screen.SessionDetail(screen.sessionId)) },
                onDone = { nav = nav.popTo(Screen.PatientToday) },
            )
        }

        is Screen.SessionDetail -> SessionDetailScreen(
            session = sessions.firstOrNull { it.id == screen.sessionId },
            database = database,
            llm = llm,
            backend = llmBackend,
            onBack = { nav = nav.back() ?: nav },
        )
    }
}

/**
 * Builds §14.2's Today list: what was prescribed, plus anything on this phone that was not.
 *
 * The second half is not a convenience. 7.1's gate is a full patient session in airplane mode, and
 * on a phone where the physio recorded the reference there is no assignment row at all - an
 * assignments-only list would be empty exactly where the gate says it must not be.
 */
private suspend fun todayItems(
    database: RightPostureDatabase,
    patientUid: String?,
): List<TodayItem> {
    val prescribed = database.assignments().prescribedFor(patientUid)
    val items = prescribed.map {
        TodayItem(
            exerciseId = it.exerciseId,
            exerciseVersion = it.exerciseVersion,
            name = it.name,
            rposePath = it.rposePath,
            mode = it.mode,
            // A hold prescribed as "3 x 1" is technically true and useless. §9.1 stores the
            // duration precisely so the row can say what is actually being asked for.
            prescription = prescriptionLine(it.mode, it.sets, it.reps, it.holdSeconds),
        )
    }
    // A signed-in patient sees what their physiotherapist prescribed, and nothing else. References
    // that happen to be on the handset - because the physio recorded one here, or because this
    // phone was used for a demo - are not their programme, and listing them would invite somebody
    // to perform an exercise nobody prescribed for them.
    if (patientUid != null) return items.withTodaysResults(database)

    // With no account there is no prescription to respect, and refusing to list anything would
    // leave Today empty on exactly the phone 7.1's airplane-mode gate runs on. Everything stored
    // locally is offered instead.
    val claimed = items.map { it.exerciseId to it.exerciseVersion }.toSet()
    val loose = database.exercises().all()
        .filter { (it.id to it.version) !in claimed }
        .map { TodayItem(it.id, it.version, it.name, it.rposePath, it.mode, null) }
    return (items + loose).withTodaysResults(database)
}

/**
 * Marks the rows a set was already done for today.
 *
 * Today's tick is a claim about today specifically, so the window is local midnight rather than a
 * rolling twenty-four hours - a set finished at 23:50 last night is yesterday's work, and showing
 * it as done this morning would tell a patient they had already trained when they had not.
 *
 * A session that ran but produced no set score keeps its row and reports no number. §1's first
 * principle: unscored is not zero, and the row says which of the two it is.
 */
private suspend fun List<TodayItem>.withTodaysResults(
    database: RightPostureDatabase,
): List<TodayItem> {
    val since = startOfToday()
    val todays = database.sessions().recentSessions(30)
        .filter { it.startedAt >= since && it.exerciseId != null }
    if (todays.isEmpty()) return this

    val countedBySession = database.sessions()
        .repsForSessions(todays.map { it.id })
        .filter { it.valid }
        .groupingBy { it.sessionId }
        .eachCount()

    // The latest attempt wins. A patient who repeated an exercise is looking at what they just did,
    // not at the first go.
    val latestByExercise = todays
        .sortedBy { it.startedAt }
        .associateBy { it.exerciseId!! }

    return map { item ->
        val session = latestByExercise[item.exerciseId] ?: return@map item
        item.copy(
            doneToday = TodayResult(
                score = session.setScore,
                repsCompleted = countedBySession[session.id] ?: 0,
                repsPrescribed = session.prescribedReps.takeIf { it > 0 },
            ),
        )
    }
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

/**
 * §6.21's verdict per exercise, over the sessions recorded against it.
 *
 * The baseline is the first qualifying session's ROM, per §6.21.3's `B_m`. There is no goal-setting
 * screen yet, so `G_m` is absent and `progressOf` returns [Progress.GoalNotSet] - which is why
 * readiness will read as "continuing" until a physio can set one. That is the honest rendering:
 * §6.21.3's guard exists precisely so a missing goal does not become a fabricated percentage.
 *
 * §6.21.6's review flags need no goal and work today.
 */
private suspend fun progressionFor(
    database: RightPostureDatabase,
): List<ExerciseProgression> {
    val sessions = database.sessions().recentSessions(60)
    val names = database.exercises().all().associate { it.id to it.name }
    // One query for every rep of every session considered. Asking per session cost sixty round
    // trips to draw one screen, and the grouping below needs all of them anyway.
    val repsBySession = database.sessions()
        .repsForSessions(sessions.map { it.id })
        .groupBy { it.sessionId }

    return sessions
        .filter { it.exerciseId != null }
        .groupBy { it.exerciseId!! }
        .mapNotNull { (exerciseId, forExercise) ->
            // Oldest first: §6.21.2's window is the *last* three, and §6.21.6 counts consecutive
            // sessions, so the order this list arrives in changes the answer.
            val ordered = forExercise.sortedBy { it.startedAt }
            val metrics = ordered.map { session ->
                sessionMetricsOf(session, repsBySession[session.id].orEmpty())
            }
            val baseline = metrics.firstOrNull { it.countsTowardProgression }?.romPeak
            val verdict = evaluateProgression(metrics, romBaseline = baseline, romGoal = null)
            // Nothing to say about an exercise with no usable history. Rendering an empty verdict
            // would fill the physio's screen with rows that carry no information.
            if (verdict.reasons.isEmpty() && !verdict.needsReview) return@mapNotNull null
            ExerciseProgression(exerciseId, names[exerciseId] ?: exerciseId, verdict)
        }
        .sortedByDescending { it.verdict.needsReview }
}

/**
 * The exercises there is any history to chart, most-performed first.
 *
 * Built from sessions rather than from the exercise library: an exercise that was authored and never
 * performed has nothing to show, and listing it invites a tap that leads to an empty screen.
 */
private suspend fun analyticsEntriesOf(database: RightPostureDatabase): List<AnalyticsEntry> {
    val names = database.exercises().all().associate { it.id to it.name }
    return database.sessions().recentSessions(200)
        .mapNotNull { it.exerciseId }
        .groupingBy { it }
        .eachCount()
        .map { (id, count) -> AnalyticsEntry(id, names[id] ?: id, count) }
        .sortedByDescending { it.sessions }
}

/** §14.1's curves for one exercise. Two queries; every decision lives in [analyticsOf]. */
/**
 * Assembles §4.2's summary for one finished set.
 *
 * The set index is the part worth explaining. Nothing in the schema records that a session was
 * "set 2 of 3", so it is counted: how many sets of this exercise were finished today, up to and
 * including this one. That inference is only made when there is a prescription to count against -
 * with no assignment there is no "of 3", and the screen then says nothing about sets rather than
 * inventing a denominator.
 */
private suspend fun sessionSummaryOf(
    database: RightPostureDatabase,
    sessionId: String,
    patientUid: String?,
): SessionSummaryData? {
    val session = database.sessions().recentSessions(30).firstOrNull { it.id == sessionId }
        ?: return null
    val exerciseId = session.exerciseId
    val name = exerciseId?.let { database.exercises().latestVersionOf(it)?.name }
        ?: exerciseId
        ?: "This exercise"

    val counted = database.sessions().reps(sessionId).count { it.valid }

    val prescription = exerciseId?.let { id ->
        database.assignments().prescribedFor(patientUid).firstOrNull { it.exerciseId == id }
    }
    val setIndex = if (prescription == null || exerciseId == null) {
        null
    } else {
        val since = startOfToday()
        database.sessions().recentSessions(30)
            .count { it.exerciseId == exerciseId && it.startedAt in since..session.startedAt }
    }

    return SessionSummaryData(
        session = session,
        exerciseName = name,
        repsCounted = counted,
        setIndex = setIndex,
        setsPrescribed = prescription?.sets,
        restSeconds = prescription?.restSeconds,
        analytics = exerciseId?.let { analyticsFor(database, it) },
    )
}

private suspend fun analyticsFor(
    database: RightPostureDatabase,
    exerciseId: String,
): AnalyticsData? {
    val sessions = database.sessions().sessionsForExercise(exerciseId)
    if (sessions.isEmpty()) return null
    val reps = database.sessions()
        .repsForSessions(sessions.map { it.id })
        .groupBy { it.sessionId }
    val name = database.exercises().latestVersionOf(exerciseId)?.name ?: exerciseId
    return analyticsOf(exerciseId, name, sessions, reps)
}

private const val DEFAULT_REPS = 10
private const val DEFAULT_SETS = 3
private const val DEFAULT_REST_SECONDS = 60

private fun Long.asShortDate(): String =
    java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(this))

@Composable
private fun LoadFailure(message: String, onDismiss: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Can't start", style = MaterialTheme.typography.headlineSmall)
            Text(message, color = Color(0xFFC62828))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}
