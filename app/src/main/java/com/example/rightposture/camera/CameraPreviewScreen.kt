package com.example.rightposture.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.rightposture.core.pose.CameraFacing
import com.example.rightposture.authoring.ReferenceReviewScreen
import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.OneEuroLandmarkFilter
import com.example.rightposture.core.reference.AuthoringResult
import com.example.rightposture.core.reference.CapturedFrame
import com.example.rightposture.core.reference.runAuthoringPipeline
import com.example.rightposture.core.reference.readyForAutoStop
import com.example.rightposture.core.reference.remainingAuthoringSeconds
import com.example.rightposture.data.RightPostureDatabase
import com.example.rightposture.data.SessionRepository
import com.example.rightposture.data.persistPending
import com.example.rightposture.core.rpose.writeRposeFile
import com.example.rightposture.authoring.SaveExerciseDialog
import com.example.rightposture.authoring.exerciseIdFromName
import com.example.rightposture.catalog.CatalogWarning
import com.example.rightposture.catalog.DemoExercise
import com.example.rightposture.catalog.ExerciseMode
import com.example.rightposture.catalog.checkAgainstCatalog
import com.example.rightposture.core.reference.bundleFromAuthoring
import com.example.rightposture.core.reference.bundleFromHold
import com.example.rightposture.core.rpose.HOLD_MODE
import com.example.rightposture.core.reference.neutralPose
import com.example.rightposture.core.reference.runHoldAuthoring
import com.example.rightposture.core.live.CalibrationGate
import com.example.rightposture.core.live.CalibrationState
import com.example.rightposture.core.live.CueDispatcher
import com.example.rightposture.core.live.ExerciseEngine
import com.example.rightposture.core.live.FatigueResult
import com.example.rightposture.core.live.HoldReference
import com.example.rightposture.core.live.HoldState
import com.example.rightposture.core.live.HoldTracker
import com.example.rightposture.core.live.HoldUpdate
import com.example.rightposture.core.live.HoldAuthoringFailure
import com.example.rightposture.core.live.ViewRecheck
import com.example.rightposture.core.live.activeSideOrDefault
import com.example.rightposture.core.live.requiredViewFromLabel
import com.example.rightposture.core.live.FrameResult
import com.example.rightposture.core.live.SpeechLanguage
import com.example.rightposture.core.live.phraseBookFor
import com.example.rightposture.speech.GoogleTtsSpeaker
import com.example.rightposture.speech.TtsState
import com.example.rightposture.speech.ViewHaptics
import com.example.rightposture.speech.ttsSetupMessage
import com.example.rightposture.exercise.CalibrationPanel
import com.example.rightposture.ui.components.WizardHeader
import com.example.rightposture.exercise.HoldPanel
import com.example.rightposture.exercise.ExercisePanel
import com.example.rightposture.sensors.GravitySensor
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.rightposture.pose.POSE_SKELETON_CONNECTIONS
import com.example.rightposture.pose.PoseFrameResult
import com.example.rightposture.pose.PoseLandmarkerSource
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Composable
fun CameraPreviewRoute(
    /**
     * 7.2's patient path. A bundle here means the reference already exists and this screen opens
     * straight into §6.20 calibration; null is the physio path, which starts at authoring.
     */
    startBundle: com.example.rightposture.core.rpose.RposeBundle? = null,
    /**
     * §5.5 step 1's choice: true for §6.19's hold, false for a repeated movement.
     *
     * Null is the patient path, which is not authoring at all. On the authoring path this decides
     * which single recording control the screen offers, so a physio cannot start the wrong pipeline
     * and find out a minute later when the recording is refused.
     */
    authoringHold: Boolean? = null,
    onFinished: () -> Unit = {},
    /**
     * Called once a reference has been written to disk and to Room. The physio path uses it to
     * return to the library; the patient path never authors, so it never fires there.
     */
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as Activity

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    var rationale by remember {
        mutableStateOf(
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA),
        )
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
        rationale = ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.CAMERA,
        )
    }

    when (val state = cameraPermissionState(granted, asked, rationale)) {
        CameraPermissionState.GRANTED ->
            CameraPreviewScreen(startBundle, authoringHold, onFinished, onSaved)
        else -> PermissionGate(
            state = state,
            onRequest = { launcher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
        )
    }
}

@Composable
private fun PermissionGate(
    state: CameraPermissionState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Camera access", style = MaterialTheme.typography.headlineMedium)
            Text(
                when (state) {
                    CameraPermissionState.NEEDS_RATIONALE ->
                        "RightPosture watches your movement to coach your form. Nothing leaves the phone."
                    CameraPermissionState.BLOCKED ->
                        "Camera access is turned off for RightPosture. Enable it in system settings to continue."
                    else -> "RightPosture needs the camera to see your movement."
                },
            )
            if (canRequest(state)) {
                Button(onClick = onRequest) { Text("Allow camera") }
            } else {
                Button(onClick = onOpenSettings) { Text("Open settings") }
            }
        }
    }
}

private tailrec fun activityOf(context: android.content.Context): Activity = when (context) {
    is Activity -> context
    is android.content.ContextWrapper -> activityOf(context.baseContext)
    else -> error("Camera preview needs an Activity context")
}

/** §6.19's authoring sequence, which is not the cyclic one. */
private enum class HoldStage { OFF, NEUTRAL, POSITIONING, RECORDING, REVIEW, LIVE }

/**
 * The shortest recording §6.19 can build a hold reference from, at a given frame rate.
 *
 * `buildHoldReference` discards `HOLD_TRIM_SECONDS` from each end and then needs a stable window of
 * at least fifteen frames out of what is left, so the floor is four seconds plus fifteen frames.
 * That is 4.5 s at 30 fps and 5.2 s at 13 fps — which is why a five-second hold authors on the
 * fast phone and is refused on the slow one, and why this is computed rather than hard-coded.
 *
 * The fifteen mirrors `MIN_STABLE_SAMPLES`, which is private to the core module. It is used here
 * only to word a warning and to decide when to show it; the actual accept/reject decision stays
 * where it belongs, in `buildHoldReference`. If that constant moves, this message goes stale but
 * nothing misbehaves.
 */
private fun minimumHoldSeconds(fps: Int): Double {
    val rate = if (fps > 0) fps.toDouble() else Params.NOMINAL_FPS.toDouble()
    return 2 * Params.HOLD_TRIM_SECONDS + 15.0 / rate
}

/**
 * How long the person can be out of frame - a blink the pose model stumbles on, a half-step to
 * reset footing - before a hold recording treats it as a genuine break rather than noise.
 *
 * Shorter than this and a single missed detection restarts a thirty-second hold over nothing;
 * longer than this and a real interruption sits in the buffer as if it were part of the hold,
 * which is the bug this whole mechanism exists to close: elapsed time was measured from the first
 * to the last captured frame's timestamp, so a gap in detection did not pause that clock, it
 * counted straight through it.
 */
private const val HOLD_RECORD_DISTURBANCE_GRACE_MS = 500L

/**
 * How long a hold reference records for, once the get-into-position countdown ends.
 *
 * Fixed rather than chosen on screen: that screen already carries a countdown, and two timers on
 * it read as one setting with two values.
 *
 * Ten seconds, raised from five. `HOLD_TRIM_SECONDS` discards two seconds from each end, so this
 * leaves about **six seconds of measured window** where five left about one. That one second was
 * the problem: a per-joint sigma estimated from roughly thirty frames of a single breath is far too
 * narrow, so the live hold scored everything as a deviation and holds returned 4-19% quality
 * against their own reference on device. Six seconds spans several breaths and the natural sway
 * that comes with them, which is what sigma is supposed to describe.
 *
 * Ten also clears the authoring floor on both phones rather than only the fast one:
 * [minimumHoldSeconds] is 4.5 s at 30 fps and 5.2 s at 13 fps, and five seconds sat between those.
 *
 * The cost is five seconds per recording, paid once by the physio. Note the interaction with
 * `Params.HOLD_BREAK_Z`, tightened at the same time: a wider sigma makes a given multiple of it a
 * larger real-world angle, so the two changes work against each other and the result has to be
 * judged on a device rather than predicted.
 */
private const val HOLD_RECORD_DEFAULT_SECONDS = 10

@Composable
private fun CameraPreviewScreen(
    startBundle: com.example.rightposture.core.rpose.RposeBundle? = null,
    authoringHold: Boolean? = null,
    onFinished: () -> Unit = {},
    onSaved: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var facing by remember { mutableStateOf(CameraFacing.FRONT) }
    var error by remember { mutableStateOf<String?>(null) }
    var fps by remember { mutableIntStateOf(0) }
    var pose by remember { mutableStateOf<PoseFrameResult?>(null) }
    var inferenceMs by remember { mutableStateOf<Long?>(null) }

    // The analyser runs on its own thread, so it may not touch Compose state. It only bumps a
    // counter; the UI samples that once a second. Writing state per frame would both race and
    // force a recomposition on every single frame.
    val frameCount = remember { AtomicInteger(0) }
    val personVisible = remember { AtomicBoolean(false) }
    val landmarkFilter = remember { OneEuroLandmarkFilter() }
    val holdBodyFrames = remember { com.example.rightposture.core.pose.BodyFrameTracker() }
    val latestPose = remember { AtomicReference<PoseFrameResult?>(null) }
    val latestError = remember { AtomicReference<String?>(null) }
    var analysisSize by remember { mutableStateOf("") }

    // 3.8 authoring. Frames are appended on the analysis thread, so the buffer is synchronised and
    // the flag is atomic; the UI only ever reads a snapshot of it after recording stops.
    val recording = remember { AtomicBoolean(false) }
    val captured = remember { java.util.Collections.synchronizedList(mutableListOf<CapturedFrame>()) }
    var isRecording by remember { mutableStateOf(false) }
    /** §5.5's guard on leaving the wizard once a recording exists to lose. */
    var confirmCancel by remember { mutableStateOf(false) }
    var recordedCount by remember { mutableIntStateOf(0) }
    var remainingSeconds by remember {
        mutableIntStateOf(Params.AUTHORING_ACCEPTANCE_TIMEOUT_SECONDS.toInt())
    }
    var analysing by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<AuthoringResult?>(null) }

    // 6.4. Which of §3.3's four is being recorded. Chosen before the recording rather than inferred
    // after it, because the choice is what routes to the cyclic or the hold path.
    /**
     * The catalogue entry this recording is being checked against, which is now always none.
     *
     * The app used to offer a list of built-in exercises here. It does not any more: the physio
     * defines the movement, and a shipped list would imply the app approves a particular set of
     * exercises when the entire design is that the physio's own recording is the standard. Every
     * read of this is already null-safe - the free-form path was always supported - so the
     * prescription and body position fall back to their defaults and §3.8's catalogue cross-check
     * simply has nothing to compare against.
     */
    val exercise: DemoExercise? = null
    var catalogWarnings by remember { mutableStateOf<List<CatalogWarning>>(emptyList()) }

    // 7.1. §13.3: Room is the source of truth, not a cache. The repository is created once and
    // outlives any single set.
    val repository = remember {
        val database = RightPostureDatabase.get(context)
        SessionRepository(database.sessions(), database.exercises())
    }
    val scope = rememberCoroutineScope()
    var sessionId by remember { mutableStateOf<String?>(null) }
    // How many of the engine's reps are already durable. §13.1 wants each rep written as it
    // completes, so this is an index into the engine's list rather than a count of writes.
    var persistedReps by remember { mutableIntStateOf(0) }

    // 5.1. The engine runs on the analysis thread, so the reference is atomic and the UI only ever
    // reads the immutable FrameResult it publishes. §12.2: nothing after onFrame is asynchronous.
    val engine = remember { AtomicReference<ExerciseEngine?>(null) }
    var inExercise by remember { mutableStateOf(false) }
    var exerciseState by remember { mutableStateOf(FrameResult.EMPTY) }
    var prescribedReps by remember { mutableIntStateOf(0) }

    // 5.4. §6.20 is a gate: the bundle is built when the reference is accepted, and the set does
    // not start until all seven checks pass. The gate runs on the analysis thread beside the
    // engine, so its state crosses to the UI the same way the engine's does - through an atomic.
    val calibrationGate = remember { AtomicReference<CalibrationGate?>(null) }
    val latestCalibration = remember { AtomicReference<CalibrationState?>(null) }
    val viewRecheck = remember { AtomicReference<ViewRecheck?>(null) }
    val paused = remember { AtomicBoolean(false) }
    var calibrating by remember { mutableStateOf(false) }
    var calibrationState by remember { mutableStateOf<CalibrationState?>(null) }
    var lastCalibrationLogMs by remember { mutableStateOf(0L) }
    var startAnyway by remember { mutableStateOf(false) }
    var recalibrating by remember { mutableStateOf(false) }
    var fatigue by remember { mutableStateOf<FatigueResult?>(null) }
    var pendingBundle by remember {
        mutableStateOf<com.example.rightposture.core.rpose.RposeBundle?>(null)
    }
    // The reference is not written until the physio has named it and pressed Save. Holding the
    // decision here rather than writing on acceptance is what makes Cancel mean "nothing happened"
    // instead of "a row was left behind under a name nobody chose".
    var namingSave by remember { mutableStateOf(false) }
    var savingBusy by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // Loaded once when the review appears, so the naming dialog can answer "is this name taken?"
    // without a database round trip on every keystroke.
    var savedExercises by remember {
        mutableStateOf<List<com.example.rightposture.data.ExerciseEntity>>(emptyList())
    }
    // 6.3. Hold mode is a parallel flow, not a flag on the cyclic one: §6.19 shares only the front
    // end (frames, body frame, validity) and nothing after it.
    val holdTracker = remember { AtomicReference<HoldTracker?>(null) }
    val latestHold = remember { AtomicReference<HoldUpdate?>(null) }
    var holdStage by remember { mutableStateOf(HoldStage.OFF) }
    var holdReference by remember { mutableStateOf<HoldReference?>(null) }
    var holdFailure by remember { mutableStateOf<String?>(null) }
    var holdUpdate by remember { mutableStateOf<HoldUpdate?>(null) }
    var holdSeconds by remember { mutableIntStateOf(0) }
    // Kept apart from the cyclic `namingSave` rather than shared. The two review screens are
    // mutually exclusive, but a flag left set on the way out of one would open a dialog over the
    // other, and the recording it named would not be the one it saved.
    var holdNaming by remember { mutableStateOf(false) }
    /** The version this hold was published as, once it has been. Null while it is unsaved. */
    var holdSavedVersion by remember { mutableStateOf<Int?>(null) }
    var neutral by remember { mutableStateOf<Map<com.example.rightposture.core.FeatureId, Float>?>(null) }
    var announcedHoldBreak by remember { mutableStateOf(false) }
    // Diagnostics for a live hold nobody is standing next to. The first SETTLING -> HOLDING was the
    // one transition that logged nothing: a hold that never starts and a hold that starts and is
    // still running both left the same silence in the log, which is exactly the pair a reader needs
    // told apart.
    var lastLoggedHoldState by remember { mutableStateOf<HoldState?>(null) }
    var lastSettlingLogMs by remember { mutableLongStateOf(0L) }
    // A broken hold re-arms rather than ending the session, so these carry the attempts already
    // made across the re-arm. Without them the previous attempt's result vanishes the instant the
    // tracker is replaced, and the patient is told nothing about the hold they just did.
    var holdAttempts by remember { mutableIntStateOf(0) }
    var holdLastSeconds by remember { mutableStateOf<Int?>(null) }
    var holdBestSeconds by remember { mutableIntStateOf(0) }
    // The update from the moment the hold broke, kept on screen - stopped, in red, showing the
    // seconds achieved - for as long as the re-armed tracker is still waiting for the patient to
    // get back into position. Zeroing the clock the instant the hold breaks throws away the only
    // number the patient was trying to make bigger, and does it while they are too far from the
    // phone to have read it.
    var holdBrokenSnapshot by remember { mutableStateOf<HoldUpdate?>(null) }
    var lastDriftAnnounceMs by remember { mutableStateOf(0L) }
    // The physio is the one being recorded, so they cannot also be holding the phone. A countdown
    // is what makes a hold recordable alone.
    var countdownSeconds by remember { mutableIntStateOf(0) }
    var countdownRemaining by remember { mutableIntStateOf(0) }
    // The physio is *in* the hold, several feet away, and cannot reach the phone to stop it. So the
    // recording ends on its own clock. This is the length of the hold itself, which is a different
    // number from the countdown above it: that one is time to walk to the wall, this one is time
    // spent holding once there.
    // Fixed rather than offered: see HOLD_RECORD_DEFAULT_SECONDS. Still a state value so the
    // recording clock and the countdown read one number, and so restoring a picker later is a
    // matter of writing to it rather than rethreading it.
    val holdRecordSeconds = HOLD_RECORD_DEFAULT_SECONDS
    var holdRemaining by remember { mutableIntStateOf(0) }
    // Shown the instant detection drops, ahead of the grace period that decides whether to actually
    // restart the recording - so a person glancing at the phone sees why the count is behaving
    // strangely well before the buffer clear itself becomes visible as a count that jumped to zero.
    var holdLostVisible by remember { mutableStateOf(false) }

    val gravity = remember { GravitySensor(context) }
    DisposableEffect(gravity) {
        gravity.start()
        onDispose { gravity.stop() }
    }

    // 5.2. Speech and haptics are driven from the analysis thread, because a cue and a fault edge
    // are live for one frame and the 30 Hz UI sampler below would drop most of them.
    val view = LocalView.current
    var ttsState by remember { mutableStateOf(TtsState.INITIALISING) }
    var language by remember { mutableStateOf(SpeechLanguage.ENGLISH) }
    val phrases = phraseBookFor(language)
    val speaker = remember {
        GoogleTtsSpeaker(context, SpeechLanguage.ENGLISH) { ttsState = it }
    }
    val dispatcher = remember { CueDispatcher(speaker, ViewHaptics(view)) }
    LaunchedEffect(language) {
        speaker.setLanguage(language)
        dispatcher.setLanguage(phraseBookFor(language))
    }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }

    // Rotation cannot be read off the PreviewView at bind time, so it is read here and passed in.
    // Reading the configuration also makes this recompose on rotation, which rebinds analysis.
    LocalConfiguration.current
    val displayRotation = activityOf(context).windowManager.defaultDisplay.rotation

    val landmarker = remember {
        PoseLandmarkerSource(
            context = context,
            onResult = { rawResult ->
                latestPose.set(
                    rawResult?.let { result ->
                        if (recording.get()) {
                            // Authoring uses the offline Butterworth path, not the live One Euro path.
                            captured.add(CapturedFrame(result.frame, result.timestampNs))
                        }
                        val redetected = !personVisible.getAndSet(true)
                        val filteredFrame = landmarkFilter.filter(
                            result.frame,
                            result.timestampNs,
                            personRedetected = redetected,
                        )
                        // The gate and the view recheck take the **filtered** frame: neither owns a
                        // filter, and check 7 asks whether the patient is holding still, which raw
                        // landmark jitter answers "no" to for every human being alive.
                        calibrationGate.get()?.let { gate ->
                            latestCalibration.set(
                                gate.update(filteredFrame, gravity.current(), result.timestampNs),
                            )
                        }
                        holdTracker.get()?.let { tracker ->
                            val features = com.example.rightposture.core.pose.validFeatures(
                                filteredFrame,
                                holdBodyFrames.update(filteredFrame, result.timestampNs),
                                com.example.rightposture.core.pose.FeatureContext(
                                    com.example.rightposture.core.pose.Side.RIGHT,
                                    com.example.rightposture.core.pose.Vec3(0f, 1f, 0f),
                                ),
                            )
                            latestHold.set(tracker.update(features, result.timestampNs))
                        }
                        // §6.20.2: the view is re-checked every 5 s, and a set whose framing has
                        // drifted is paused rather than scored. Frames are withheld from the engine
                        // while paused - a rep measured through a camera that has moved is not a
                        // measurement, and banking it would be worse than losing it.
                        val stillPaused =
                            viewRecheck.get()?.update(filteredFrame, result.timestampNs) ?: false
                        // On the edge into a pause the rep in progress is abandoned, not frozen.
                        // The reps already banked are kept - onPersonLost is exactly that contract.
                        if (stillPaused && !paused.getAndSet(true)) engine.get()?.onPersonLost()
                        if (!stillPaused) paused.set(false)
                        // The engine owns its own One Euro filter, so it takes the raw frame.
                        if (!stillPaused) {
                            engine.get()
                                ?.onFrame(result.frame, result.timestampNs, redetected)
                                ?.let(dispatcher::onFrame)
                        }
                        result.copy(frame = filteredFrame)
                    }.also { result ->
                        if (result == null) {
                            personVisible.set(false)
                            landmarkFilter.reset()
                            // Not reset(): losing sight of the patient must not wipe the reps they
                            // have already completed.
                            engine.get()?.onPersonLost()
                            val nowNs = SystemClock.elapsedRealtimeNanos()
                            calibrationGate.get()?.let { gate ->
                                latestCalibration.set(gate.update(null, gravity.current(), nowNs))
                            }
                            viewRecheck.get()?.update(null, nowNs)
                        }
                    },
                )
                frameCount.incrementAndGet()
            },
            onError = latestError::set,
        )
    }
    val controller = remember {
        CameraController(
            context = context,
            onFrame = { image ->
                landmarker.detect(image, mirrored = facing == CameraFacing.FRONT)
            },
            onError = latestError::set,
            onAnalyzerClose = landmarker::close,
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            controller.close()
        }
    }

    // 7.2's patient entry. The reference was authored earlier and read off disk, so there is no
    // recording step - §6.20's gate is the first thing the patient sees.
    LaunchedEffect(startBundle) {
        val bundle = startBundle ?: return@LaunchedEffect

        // §6.19 is a parallel flow, and this is where the two part company for a patient. A hold
        // has no phase axis, so §6.20's gate would be measuring a span that is zero by construction
        // and check 7's phi-rate bound would have nothing to bound. The hold begins directly, with
        // its own settling gate - "get into the position" - doing the job calibration does for a
        // cyclic set.
        //
        // There is no recording stage either: the physio authored this reference days ago, and the
        // patient is here to perform it, not to record their own.
        val holdReferenceFromBundle = bundle.hold
        if (bundle.manifest.mode == HOLD_MODE && holdReferenceFromBundle != null) {
            holdReference = holdReferenceFromBundle
            holdTracker.set(HoldTracker(holdReferenceFromBundle))
            latestHold.set(null)
            holdUpdate = null
            announcedHoldBreak = false
            holdAttempts = 0
            holdLastSeconds = null
            holdBestSeconds = 0
            holdBrokenSnapshot = null
            // Saving is the physio's action on their own recording. A patient performing a
            // prescription has nothing to save and nothing to name.
            holdSavedVersion = null
            holdNaming = false
            android.util.Log.i(
                "RPHold",
                "live session started from bundle %s v%d (%ss prescribed)".format(
                    bundle.manifest.id,
                    bundle.manifest.version,
                    bundle.manifest.prescription.holdSeconds?.toString() ?: "-",
                ),
            )
            holdStage = HoldStage.LIVE
            return@LaunchedEffect
        }

        pendingBundle = bundle
        calibrationGate.set(
            CalibrationGate(
                requiredView = requiredViewFromLabel(bundle.manifest.requiredView),
                dominant = bundle.manifest.segmentation.dominantFeature,
                watchedFeatures = bundle.watchList.features.map { it.id },
                activeSide = bundle.manifest.activeSideOrDefault(),
                spanDegrees = bundle.manifest.phase.thetaPeak - bundle.manifest.phase.thetaStart,
            ),
        )
        latestCalibration.set(null)
        calibrationState = null
        calibrating = true
    }

    LaunchedEffect(Unit) {
        var lastMs = SystemClock.elapsedRealtime()
        while (true) {
            kotlinx.coroutines.delay(33)
            pose = latestPose.get()
            pose?.let {
                analysisSize = "${it.inputWidth}x${it.inputHeight}"
                inferenceMs = it.inferenceMs
            }
            latestError.getAndSet(null)?.let { error = it }
            if (isRecording) recordedCount = captured.size
            if (calibrating) {
                calibrationState = latestCalibration.get()
                // §6.20 is a gate, so when it refuses it has to be answerable on a device. The
                // verdicts alone cannot separate "stand differently" from "this reference was
                // authored against the wrong view", which is exactly the confusion that shipped.
                val now = SystemClock.elapsedRealtime()
                if (now - lastCalibrationLogMs >= 1000) {
                    lastCalibrationLogMs = now
                    calibrationState?.let { android.util.Log.i("RPCalib", it.diagnostic()) }
                }
                if (calibrationState?.ready == true || startAnyway) {
                    pendingBundle?.let { bundle ->
                        engine.set(ExerciseEngine(bundle))
                        dispatcher.resetSet()
                        prescribedReps = bundle.manifest.prescription.reps
                        exerciseState = FrameResult.EMPTY
                        viewRecheck.set(
                            ViewRecheck(requiredViewFromLabel(bundle.manifest.requiredView)),
                        )
                        paused.set(false)
                        recalibrating = false
                        calibrationGate.set(null)
                        pendingBundle = null
                        calibrating = false
                        startAnyway = false
                        persistedReps = 0
                        // Opened before the first rep, so that a set the patient abandons after
                        // zero reps is still a fact on disk. "Started and did nothing" and "never
                        // started" are different observations and only the first is evidence.
                        sessionId = runCatching {
                            repository.startSession(
                                manifest = bundle.manifest,
                                prescribedReps = bundle.manifest.prescription.reps,
                                deviceFps = fps.toDouble(),
                                delegate = landmarker.delegate?.name,
                            )
                        }.getOrNull()
                        inExercise = true
                    }
                }
            }
            if (inExercise) {
                exerciseState = engine.get()?.state ?: FrameResult.EMPTY
                fatigue = engine.get()?.fatigue
                recalibrating = paused.get()
                // §13.1: a Rep row as each rep completes, not at set end.
                //
                // Written from this 33 ms sampler rather than from the analysis thread on purpose.
                // §12.2 requires nothing after onFrame to be asynchronous, and disk I/O on the
                // camera thread drops frames - which would corrupt the very measurement being
                // saved. The cost is a window of at most one tick in which a kill loses the rep
                // just finished; every rep before it is already durable.
                val open = sessionId
                val record = engine.get()?.session
                if (open != null && record != null && record.reps.size > persistedReps) {
                    // The watermark only advances past reps that actually reached disk, so a
                    // failed write is retried on the next tick rather than being skipped.
                    persistedReps = repository.persistPending(
                        sessionId = open,
                        reps = record.reps,
                        alreadyPersisted = persistedReps,
                        onFailure = { android.util.Log.e("RPStore", "rep write failed", it) },
                    )
                }
            }
            if (holdStage == HoldStage.LIVE) {
                holdUpdate = latestHold.get()
                val current = holdUpdate
                if (current != null) {
                    if (current.state != lastLoggedHoldState) {
                        lastLoggedHoldState = current.state
                        android.util.Log.i(
                            "RPHold",
                            "state -> %s at %.1fs".format(current.state, current.durationSeconds),
                        )
                    }
                    // A stuck settle is the failure that looks like nothing happening at all, so it
                    // says so periodically rather than only on a transition that never comes.
                    val settlingNowMs = SystemClock.elapsedRealtime()
                    if (current.state == HoldState.SETTLING &&
                        settlingNowMs - lastSettlingLogMs > 3_000
                    ) {
                        lastSettlingLogMs = settlingNowMs
                        android.util.Log.i(
                            "RPHold",
                            "still settling - not yet inside %.1f sigma on all %d watched joints"
                                .format(Params.HOLD_QUALITY_Z, holdReference?.watchList?.size ?: 0),
                        )
                    }
                    // §6.19: announce the break once, with the duration achieved.
                    if (current.state == HoldState.BROKEN && !announcedHoldBreak) {
                        announcedHoldBreak = true
                        // Rounded, not truncated, to match HoldPanel: a log saying 1s beside a
                        // screen saying 2 seconds is a discrepancy someone will chase.
                        val achieved = kotlin.math.round(current.durationSeconds).toInt()
                        speaker.speak("hold-break", phrases.holdBroken(achieved))
                        holdAttempts++
                        holdLastSeconds = achieved
                        if (achieved > holdBestSeconds) holdBestSeconds = achieved
                        android.util.Log.i(
                            "RPHold",
                            "live hold broke at %ds (attempt %d) quality=%s drift=%s on %s - re-arming"
                                .format(
                                    achieved,
                                    holdAttempts,
                                    current.holdQuality?.let { "%.0f%%".format(it * 100) } ?: "-",
                                    current.worstDriftPerSecond?.let { "%+.2f".format(it) } ?: "-",
                                    current.driftFeature ?: "-",
                                ),
                        )
                        // Re-arm rather than discard. A broken hold used to set the tracker to null,
                        // which left the screen frozen on the break message with no way back except
                        // Done: getting into position again did nothing, because nothing was
                        // watching any more.
                        //
                        // A **fresh tracker** rather than reset() on the live one: HoldTracker holds
                        // plain vars and mutable collections, and the analysis thread is inside
                        // update() on it. Swapping the AtomicReference hands that thread a new
                        // instance between frames instead of mutating the one it is using.
                        //
                        // The new tracker starts in SETTLING, which is already the "wait until they
                        // are actually in the position" state - so the clock restarts at zero when
                        // they get back into the pose, not when the break happened.
                        val ref = holdReference
                        if (ref != null) {
                            // Held for the display while the new tracker settles, so the break stays
                            // readable instead of being replaced by a zeroed clock in the same frame.
                            holdBrokenSnapshot = current
                            holdTracker.set(HoldTracker(ref))
                            // Cleared so the stale BROKEN snapshot cannot be read again on the next
                            // tick and announce a second break for the same attempt.
                            latestHold.set(null)
                            holdUpdate = null
                            announcedHoldBreak = false
                        }
                    }
                    // The new hold has actually begun, so the previous one stops being the headline.
                    if (current.state == HoldState.HOLDING && holdBrokenSnapshot != null) {
                        holdBrokenSnapshot = null
                        android.util.Log.i(
                            "RPHold",
                            "back in position - hold restarted from zero (attempt ${holdAttempts + 1})",
                        )
                    }
                    // The drift correction repeats, but not faster than the cue cooldown: a
                    // correction said every frame is noise, and noise gets ignored.
                    val nowMs = SystemClock.elapsedRealtime()
                    if (current.state == HoldState.HOLDING && current.driftWarning &&
                        current.driftFeature != null && nowMs - lastDriftAnnounceMs > 5_000
                    ) {
                        lastDriftAnnounceMs = nowMs
                        speaker.speak("hold-drift", phrases.holdDrift(current.driftFeature!!))
                    }
                }
            }
            if (holdStage == HoldStage.NEUTRAL || holdStage == HoldStage.RECORDING) {
                val snapshot = synchronized(captured) { captured.toList() }
                holdSeconds = if (snapshot.size < 2) {
                    0
                } else {
                    ((snapshot.last().timestampNs - snapshot.first().timestampNs) / 1_000_000_000L).toInt()
                }
                // §6.19's neutral pose is a one-second capture; a few extra frames cost nothing and
                // a median over 30 of them is steadier than over 30 exactly.
                if (holdStage == HoldStage.NEUTRAL && holdSeconds >= 2) {
                    neutral = neutralPose(snapshot)
                    recording.set(false)
                    synchronized(captured) { captured.clear() }
                    holdStage = HoldStage.POSITIONING
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastMs >= 1000) {
                fps = (frameCount.getAndSet(0) * 1000L / (now - lastMs)).toInt()
                lastMs = now
            }
        }
    }

    LaunchedEffect(countdownSeconds) {
        if (countdownSeconds <= 0) return@LaunchedEffect
        countdownRemaining = countdownSeconds
        while (countdownRemaining > 0) {
            // Spoken as well as shown: the whole point is that the physio is away from the phone
            // and getting into position, so a number only on screen is a number nobody can read.
            if (countdownRemaining <= 3 || countdownRemaining % 5 == 0) {
                speaker.speak("count-$countdownRemaining", countdownRemaining.toString())
            }
            kotlinx.coroutines.delay(1_000)
            countdownRemaining--
        }
        speaker.speak("count-go", "Hold it.")
        synchronized(captured) { captured.clear() }
        recording.set(true)
        countdownSeconds = 0
        holdRemaining = holdRecordSeconds
        holdStage = HoldStage.RECORDING
    }

    // The timer and the manual button end the recording the same way: §6.19's authoring run over
    // the captured frames, then review. Extracted so the two paths cannot drift apart - the bug
    // this replaces was a recording with no path out of it but the button.
    val finishHoldRecording: suspend () -> Unit = {
        // The timer and the button can land in the same frame. Whichever clears the flag first owns
        // the authoring run; the loser does nothing rather than authoring the same hold twice and
        // racing to publish two different reviews of it.
        if (recording.getAndSet(false)) {
            val snapshot = synchronized(captured) { captured.toList() }
            // Off the main thread, unlike the button that used to do this inline: a thirty-second hold
            // is nine hundred frames of feature extraction and it janks the preview visibly.
            val result = withContext(Dispatchers.Default) {
                runHoldAuthoring(snapshot, neutral ?: emptyMap())
            }
            holdReference = result.reference
            catalogWarnings = exercise?.let { chosen ->
                result.reference?.let { checkAgainstCatalog(chosen, it) }
            }.orEmpty()
            holdFailure = when (result.failure) {
                // Quote the measured floor rather than a fixed "15 seconds". The floor moves with the
                // frame rate, and on a slow phone a hold that authored yesterday will not today.
                HoldAuthoringFailure.TOO_SHORT ->
                    ("That hold was too short to author. At %d fps it needs more than %.1f s of " +
                        "recording; this one was %d s.").format(fps, minimumHoldSeconds(fps), holdSeconds)
                HoldAuthoringFailure.NO_FEATURES ->
                    "No joint stayed visible for the whole hold. Move the camera back."
                HoldAuthoringFailure.NOT_HELD_STILL ->
                    "That was not a steady hold — the main joint moved by %.0f°. Hold more still."
                        .format(result.dominantMadDegrees ?: 0.0)
                null -> null
            }
            // The log is the only record of a hold session once the physio has walked away from the
            // phone, so it carries what the review screen shows: whether authoring took, and on
            // what. FeatureIds go in raw rather than through PhraseBook, because two features can
            // share a display name and a log reading "knee, knee" names neither of them.
            val authored = result.reference
            android.util.Log.i(
                "RPHold",
                if (authored != null) {
                    "authored ok - watch=%s dominant=%s stable=%.1fs features=%d frames=%d".format(
                        authored.watchList.joinToString("|"),
                        authored.dominant,
                        authored.stableWindowSeconds,
                        authored.features.size,
                        snapshot.size,
                    )
                } else {
                    "authored FAILED - reason=%s mad=%s frames=%d".format(
                        result.failure,
                        result.dominantMadDegrees?.let { "%.1f".format(it) } ?: "-",
                        snapshot.size,
                    )
                },
            )
            holdStage = HoldStage.REVIEW
        }
    }

    // §6.19's recording ends on its own clock. Elapsed time is measured from the captured frames'
    // own timestamps rather than from a wall clock, for the same reason §6.1 does: a dropped frame
    // must shorten the recording, not silently stretch it.
    LaunchedEffect(holdStage, holdRecordSeconds) {
        if (holdStage != HoldStage.RECORDING) return@LaunchedEffect
        // Frames are only appended while a pose is detected, so if the patient leaves the frame -
        // or the phone at hip height never sees them to begin with - the captured clock stops and
        // a purely frame-driven timer waits forever. That was the original bug in a new shape, so
        // there is a wall clock behind the frame clock whose only job is to guarantee an ending.
        var startedAtMs = SystemClock.elapsedRealtime()
        val ceilingMs = (holdRecordSeconds + 10L) * 1000L
        // Tracks one loss episode at a time: when it started, and whether this episode has already
        // been acted on, so a person who stays out of frame for two seconds gets one reset and one
        // announcement, not one every 100 ms until they return.
        var lostSinceMs = -1L
        var lossHandled = false
        var restarts = 0
        holdLostVisible = false
        android.util.Log.i("RPHold", "recording started - target ${holdRecordSeconds}s at $fps fps")
        while (holdStage == HoldStage.RECORDING) {
            val nowMs = SystemClock.elapsedRealtime()
            if (!personVisible.get()) {
                holdLostVisible = true
                if (lostSinceMs < 0) lostSinceMs = nowMs
                if (!lossHandled && nowMs - lostSinceMs >= HOLD_RECORD_DISTURBANCE_GRACE_MS) {
                    // A confirmed break, not a flicker: discard the interrupted attempt entirely
                    // rather than resume it, so the reference is built from one continuous hold
                    // instead of two stitched-together fragments either side of a gap it cannot see.
                    synchronized(captured) { captured.clear() }
                    holdRemaining = holdRecordSeconds
                    startedAtMs = nowMs
                    lossHandled = true
                    restarts++
                    // Logged for the same reason §6.20's gate is: a recording that restarted twice
                    // and a recording that never started look identical at the review screen, and
                    // the person who would know the difference was across the room facing a wall.
                    android.util.Log.i("RPHold", "restart $restarts - lost for ${nowMs - lostSinceMs} ms")
                    speaker.speak("hold-lost", "Lost you. Get back into the hold.")
                }
            } else {
                holdLostVisible = false
                lostSinceMs = -1L
                lossHandled = false
            }
            val snapshot = synchronized(captured) { captured.toList() }
            val elapsed = if (snapshot.size < 2) {
                0.0
            } else {
                (snapshot.last().timestampNs - snapshot.first().timestampNs) / 1_000_000_000.0
            }
            holdRemaining = kotlin.math.ceil(holdRecordSeconds - elapsed).toInt().coerceAtLeast(0)
            val stalled = nowMs - startedAtMs > ceilingMs
            if (elapsed >= holdRecordSeconds || stalled) {
                // A stalled recording still goes to review, where authoring names what was wrong
                // with it. Ending silently and returning to the picker would tell the physio
                // nothing about why the hold they just performed produced no exercise.
                android.util.Log.i(
                    "RPHold",
                    "finishing - elapsed %.1fs, stalled=%s, restarts=%d".format(elapsed, stalled, restarts),
                )
                speaker.speak("hold-done", if (stalled) "Stopped." else "Done. Relax.")
                finishHoldRecording()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(100)
        }
    }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            kotlinx.coroutines.delay(1_000)
            val snapshot = synchronized(captured) { captured.toList() }
            if (snapshot.size < 2) continue
            val durationSeconds =
                (snapshot.last().timestampNs - snapshot.first().timestampNs) / 1_000_000_000.0
            remainingSeconds = remainingAuthoringSeconds(durationSeconds)
            if (durationSeconds < Params.VALIDATOR_MIN_DURATION_SECONDS) continue
            val result = withContext(Dispatchers.Default) { runAuthoringPipeline(snapshot) }
            val timedOut = durationSeconds >= Params.AUTHORING_ACCEPTANCE_TIMEOUT_SECONDS
            if (isRecording && (result.readyForAutoStop(durationSeconds) || timedOut)) {
                recording.set(false)
                isRecording = false
                catalogWarnings = exercise?.let { checkAgainstCatalog(it, result) }.orEmpty()
                review = result
            }
        }
    }

    // §6.19's authoring flow, which is a different sequence to the cyclic one: neutral pose, then
    // get into position, then the hold itself.
    if (holdStage == HoldStage.REVIEW) {
        // Needed by the save dialog's version warning, which has to follow what the physio types.
        LaunchedEffect(holdStage) {
            savedExercises = withContext(Dispatchers.IO) { repository.allExercises() }
        }
        val reference = holdReference
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Hold reference", style = MaterialTheme.typography.headlineSmall)
                exercise?.let { Text(it.name, style = MaterialTheme.typography.titleMedium) }
                // Advisory, not a rejection: the reference is usable, it just does not look like
                // the exercise the physio said they were recording.
                catalogWarnings.forEach {
                    Text(it.message, color = Color(0xFFE08A00), style = MaterialTheme.typography.bodySmall)
                }
                if (reference == null) {
                    Text(holdFailure ?: "That recording could not be used.", color = Color(0xFFC62828))
                } else {
                    Text("Main joint: ${phrases.featureName(reference.dominant)}")
                    Text("Steady for %.0f seconds".format(reference.stableWindowSeconds))
                    Text("Watching: " + reference.watchList.joinToString(", ") { phrases.featureName(it) })
                    reference.watchList.forEach { id ->
                        reference.features[id]?.let {
                            Text(
                                "  %s  %.0f° ± %.1f°".format(phrases.featureName(id), it.mean, it.sigma),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Button(
                    onClick = {
                        holdStage = HoldStage.OFF
                        holdReference = null
                        holdFailure = null
                        holdSavedVersion = null
                        holdNaming = false
                        saveError = null
                        countdownSeconds = 0
                        countdownRemaining = 0
                        recording.set(false)
                        synchronized(captured) { captured.clear() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Re-record") }
                saveError?.let {
                    Text(it, color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall)
                }
                holdSavedVersion?.let {
                    Text(
                        "Saved as version $it. Your patients can be prescribed this hold.",
                        color = Color(0xFF2E7D32),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (reference != null && holdSavedVersion == null) {
                    // Saving does not leave the screen, unlike the cyclic path. The physio has just
                    // performed this pose and the obvious next thing is to check it holds, which is
                    // the button directly below - sending them home to find it again would be a
                    // worse flow than the one that saved nothing at all.
                    Button(
                        onClick = { holdNaming = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save hold") }
                }
                if (reference != null) {
                    Button(
                        onClick = {
                            holdTracker.set(HoldTracker(reference))
                            latestHold.set(null)
                            holdUpdate = null
                            announcedHoldBreak = false
                            // A new session, not a re-arm: the attempt history belongs to the
                            // session that produced it.
                            holdAttempts = 0
                            holdLastSeconds = null
                            holdBestSeconds = 0
                            holdBrokenSnapshot = null
                            android.util.Log.i("RPHold", "live session started")
                            holdStage = HoldStage.LIVE
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start hold") }
                }
            }
            if (holdNaming && reference != null) {
                SaveExerciseDialog(
                    suggestedName = exercise?.name ?: "",
                    existingVersionFor = { typed ->
                        val id = exerciseIdFromName(typed)
                        savedExercises.filter { it.id == id }.maxOfOrNull { it.version }
                    },
                    busy = savingBusy,
                    onCancel = { if (!savingBusy) holdNaming = false },
                    onSave = { chosenName ->
                        savingBusy = true
                        saveError = null
                        val snapshot = synchronized(captured) { captured.toList() }
                        val id = exerciseIdFromName(chosenName)
                        scope.launch {
                            val nextVersion = withContext(Dispatchers.IO) {
                                (repository.latestVersionOf(id)?.version ?: 0) + 1
                            }
                            val bundle = bundleFromHold(
                                hold = reference,
                                name = chosenName,
                                id = id,
                                version = nextVersion,
                                createdAt = java.time.Instant.now().toString(),
                                // What the physio actually held, not the stable window: trimming
                                // two seconds off each end is how the band is measured, not how
                                // long the pose was maintained, and the patient is being asked to
                                // repeat the pose rather than the measurement.
                                holdSeconds = holdSeconds,
                                device = android.os.Build.MODEL,
                                sets = exercise?.prescription?.sets ?: 3,
                                restSeconds = exercise?.prescription?.restSeconds ?: 60,
                                frames = snapshot.size,
                                durationSeconds = if (snapshot.size < 2) 0.0 else
                                    (snapshot.last().timestampNs - snapshot.first().timestampNs) / 1_000_000_000.0,
                                mirrored = facing == CameraFacing.FRONT,
                                cameraFacing = if (facing == CameraFacing.FRONT) "front" else "rear",
                                bodyPositionLabel = exercise?.bodyPosition ?: "standing",
                            )
                            // Same ordering as the cyclic save, for the same reason: the row's
                            // rposePath has to point at something, so the file is written first and
                            // the row saved only if that succeeded.
                            val written = withContext(Dispatchers.IO) {
                                runCatching {
                                    val dir = java.io.File(context.filesDir, "exercises").apply { mkdirs() }
                                    val file = java.io.File(
                                        dir,
                                        bundle.manifest.id + "_v" + bundle.manifest.version + ".rpose",
                                    )
                                    // Debris from a half-finished save: a file no row points at is
                                    // not an immutable bundle, and writeRposeFile's refusal to
                                    // overwrite would otherwise strand every retry.
                                    if (file.exists() &&
                                        repository.latestVersionOf(bundle.manifest.id) == null
                                    ) {
                                        file.delete()
                                    }
                                    writeRposeFile(bundle, file)
                                    repository.saveExercise(bundle.manifest, file.absolutePath)
                                    file
                                }
                            }
                            savingBusy = false
                            holdNaming = false
                            if (written.isFailure) {
                                android.util.Log.e("RPStore", "hold write failed", written.exceptionOrNull())
                                saveError = "Could not save that hold to this phone."
                            } else {
                                holdSavedVersion = nextVersion
                                android.util.Log.i(
                                    "RPHold",
                                    "saved hold %s v%d (%ds, %d frames)"
                                        .format(id, nextVersion, holdSeconds, snapshot.size),
                                )
                                onSaved()
                            }
                        }
                    },
                )
            }
        }
        return
    }

    review?.let { result ->
        LaunchedEffect(result) {
            savedExercises = withContext(Dispatchers.IO) { repository.allExercises() }
        }
        Scaffold { padding ->
            Box(Modifier.padding(padding)) {
                ReferenceReviewScreen(
                    result = result,
                    warnings = catalogWarnings.map { it.message },
                    onReRecord = {
                        review = null
                        synchronized(captured) { captured.clear() }
                        recordedCount = 0
                    },
                    // The recording is accepted; naming it is what turns it into an exercise.
                    // Nothing is written until Save, so Cancel costs the physio nothing.
                    onStartExercise = {
                        saveError = null
                        namingSave = true
                    },
                    saveLabel = "Save this exercise",
                )
                saveError?.let {
                    Text(
                        it,
                        color = androidx.compose.ui.graphics.Color(0xFFC62828),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        if (namingSave) {
            val chosen = exercise
            SaveExerciseDialog(
                suggestedName = chosen?.name ?: "",
                existingVersionFor = { typed ->
                    val id = exerciseIdFromName(typed)
                    savedExercises.filter { it.id == id }.maxOfOrNull { it.version }
                },
                busy = savingBusy,
                onCancel = { if (!savingBusy) namingSave = false },
                onSave = { chosenName ->
                    savingBusy = true
                    saveError = null
                    val snapshot = synchronized(captured) { captured.toList() }
                    // §9.6: the id is derived from the name, so saving under a name that already
                    // exists publishes the next version rather than a second unrelated exercise.
                    // Deriving it also fixes a defect in the previous flow, where every reference
                    // recorded without picking from the catalogue was written as `rp_local` v1 and
                    // silently overwrote the one before it.
                    val id = exerciseIdFromName(chosenName)
                    scope.launch {
                        val nextVersion = withContext(Dispatchers.IO) {
                            (repository.latestVersionOf(id)?.version ?: 0) + 1
                        }
                        val bundle = bundleFromAuthoring(
                            result = result,
                            name = chosenName,
                            id = id,
                            version = nextVersion,
                            createdAt = java.time.Instant.now().toString(),
                            device = android.os.Build.MODEL,
                            // The catalogue still supplies the prescription and body position when
                            // one was picked; a free-form recording falls back to defaults.
                            prescribedReps = chosen?.prescription?.reps ?: 12,
                            sets = chosen?.prescription?.sets ?: 3,
                            restSeconds = chosen?.prescription?.restSeconds ?: 60,
                            bodyPositionLabel = chosen?.bodyPosition ?: "seated",
                            frames = snapshot.size,
                            durationSeconds = if (snapshot.size < 2) 0.0 else
                                (snapshot.last().timestampNs - snapshot.first().timestampNs) / 1_000_000_000.0,
                            mirrored = facing == CameraFacing.FRONT,
                            cameraFacing = if (facing == CameraFacing.FRONT) "front" else "rear",
                        )
                        if (bundle == null) {
                            savingBusy = false
                            namingSave = false
                            saveError = "That reference could not be packaged. Re-record it."
                            return@launch
                        }
                        // §13.1's `Exercise.rposePath` has to point at something, so the bundle is
                        // written before the row that names it, and the row is saved only if the
                        // write succeeded. A row pointing at a file that is not there is a broken
                        // row, and the physio would not find out until a patient tapped it.
                        val written = withContext(Dispatchers.IO) {
                            runCatching {
                                val dir = java.io.File(context.filesDir, "exercises").apply { mkdirs() }
                                val file = java.io.File(
                                    dir,
                                    "${bundle.manifest.id}_v${bundle.manifest.version}.rpose",
                                )
                                // `writeRposeFile` refuses to overwrite, which is §9.6 enforced
                                // rather than documented. That refusal is right, and it also
                                // strands a save that got as far as the file and failed on the
                                // row: the version comes from Room, so the retry computes the same
                                // version, finds the orphaned file and can never get past it.
                                // A file no row points at is not an immutable bundle - it is
                                // debris from a half-finished save, and deleting it is the only
                                // way out.
                                if (file.exists() &&
                                    repository.latestVersionOf(bundle.manifest.id) == null
                                ) {
                                    file.delete()
                                }
                                writeRposeFile(bundle, file)
                                repository.saveExercise(bundle.manifest, file.absolutePath)
                                file
                            }
                        }
                        savingBusy = false
                        namingSave = false
                        if (written.isFailure) {
                            android.util.Log.e("RPStore", "bundle write failed", written.exceptionOrNull())
                            saveError = "Could not save that exercise to this phone."
                        } else {
                            review = null
                            synchronized(captured) { captured.clear() }
                            recordedCount = 0
                            onSaved()
                        }
                    }
                },
            )
        }
        return
    }

    if (analysing) {
        LaunchedEffect(Unit) {
            val snapshot = synchronized(captured) { captured.toList() }
            val result = withContext(Dispatchers.Default) { runAuthoringPipeline(snapshot) }
            catalogWarnings = exercise?.let { checkAgainstCatalog(it, result) }.orEmpty()
            review = result
            analysing = false
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // §5.5's chrome, on the authoring path only. The patient path shares this composable
            // but is not a wizard - it is one continuous set - and a "Step 3 of 5" above a
            // patient's exercise would be counting something they are not doing.
            if (startBundle == null) {
                val step = authoringStep(
                    calibrating = calibrating,
                    recording = isRecording,
                    analysing = analysing,
                    holdStage = holdStage.name,
                    naming = namingSave || holdNaming,
                    reviewing = holdReference != null,
                )
                // §5.5: one Cancel, confirmed once past step 3. Before a recording exists there is
                // nothing to lose and a dialog is friction; after one exists, leaving throws away
                // the minutes of setup and the recording itself, and that is expensive to redo.
                if (confirmCancel) {
                    AlertDialog(
                        onDismissRequest = { confirmCancel = false },
                        title = { Text("Discard this recording?") },
                        text = {
                            Text(
                                "Nothing has been saved yet. Leaving now means setting the phone " +
                                    "up and performing the movement again.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { confirmCancel = false; onSaved() }) {
                                Text("Discard")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmCancel = false }) { Text("Keep going") }
                        },
                    )
                }
                WizardHeader(
                    title = "Record a reference",
                    stepLabel = authoringStepLabel(
                        calibrating = calibrating,
                        recording = isRecording,
                        analysing = analysing,
                        holdStage = holdStage.name,
                        naming = namingSave || holdNaming,
                        reviewing = holdReference != null,
                    ),
                    step = step,
                    steps = 5,
                    onCancel = { if (step >= 3) confirmCancel = true else onSaved() },
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (startBundle != null) {
                    Text("Record reference", style = MaterialTheme.typography.headlineSmall)
                }
                if (isRecording) {
                    Text(
                        "%d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (remainingSeconds <= 10) Color.Red else MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Text(
                if (calibrating) {
                    "Calibration"
                } else if (inExercise) {
                    "Exercise"
                } else if (facing == CameraFacing.FRONT) {
                    "Front camera"
                } else {
                    "Rear camera"
                },
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(
                    factory = { controller.createPreviewView() },
                    modifier = Modifier.fillMaxSize().semantics {
                        contentDescription = "Live camera preview"
                    },
                    update = { controller.bind(lifecycleOwner, it, facing, displayRotation) },
                )
                pose?.let { SkeletonOverlay(it) }
            }
            if (holdStage == HoldStage.NEUTRAL) {
                Text(phrases.neutralCapture(), style = MaterialTheme.typography.titleMedium)
                Text("Capturing your neutral standing pose — $holdSeconds s")
            }
            if (holdStage == HoldStage.POSITIONING) {
                if (countdownRemaining > 0) {
                    Text(
                        "$countdownRemaining",
                        style = MaterialTheme.typography.displayLarge,
                        modifier = Modifier.semantics {
                            contentDescription = "Recording starts in $countdownRemaining seconds"
                        },
                    )
                    Text("Get into the hold.", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("Now get into the hold.", style = MaterialTheme.typography.titleMedium)
                    Text("The hold records for $holdRecordSeconds s once the countdown ends.")
                    // The length itself is no longer offered as a choice - one timer on this screen
                    // is the countdown to get into position, and two timers side by side read as
                    // one setting with two values. Only the frame rate can still make the fixed
                    // length unusable, and that is worth saying because it is not a preference:
                    // below the floor §6.19 refuses the recording outright, so the physio would
                    // perform a hold that could never have been authored.
                    val floor = minimumHoldSeconds(fps)
                    if (holdRecordSeconds <= floor) {
                        Text(
                            ("At %d fps a %d s hold cannot be authored — §6.19 needs more than " +
                                "%.1f s. Use the faster phone, or raise HOLD_RECORD_DEFAULT_SECONDS.")
                                .format(fps, holdRecordSeconds, floor),
                            color = Color(0xFFC62828),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Pick how long you need to get into position.")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(3, 5, 10).forEach { seconds ->
                            Button(
                                onClick = { countdownSeconds = seconds },
                                modifier = Modifier.weight(1f),
                            ) { Text("${seconds}s") }
                        }
                    }
                }
            }
            if (holdStage == HoldStage.RECORDING) {
                // The count is what the physio needs from across the room, so it is the large
                // number. Seconds elapsed is the small print beneath it.
                Text(
                    "$holdRemaining",
                    style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.semantics {
                        contentDescription = "$holdRemaining seconds of hold remaining"
                    },
                )
                Text("Hold it — $holdSeconds of $holdRecordSeconds s", style = MaterialTheme.typography.titleMedium)
                if (holdLostVisible) {
                    // Shown the instant detection drops; the count itself does not reset to 0 until
                    // the grace period confirms this is a real break, not a flicker, so this is the
                    // only feedback available in that window.
                    Text(
                        "Lost sight of you — get back into the hold. It restarts if this continues.",
                        color = Color(0xFFC62828),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("Recording stops on its own. Stepping out restarts it from zero.")
                }
                // Kept as an escape hatch, not as the way out: a hold that has gone wrong should be
                // abandonable without waiting for a timer. It no longer gates on a duration,
                // because a hold shorter than the floor is now refused with a reason at review
                // rather than by a button that never enables.
                OutlinedButton(
                    onClick = { scope.launch { finishHoldRecording() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop now") }
            }
            if (holdStage == HoldStage.LIVE) {
                val reference = holdReference
                val live = holdUpdate
                val frozen = holdBrokenSnapshot
                // While the re-armed tracker is still settling, the broken hold is what the patient
                // needs to see: stopped, red, and showing the seconds they actually achieved. The
                // live SETTLING update sitting underneath it reads 0:00, which would look like the
                // hold had already restarted when it has not.
                val awaitingRestart = frozen != null && (live == null || live.state == HoldState.SETTLING)
                val current = if (awaitingRestart) frozen else live
                if (reference != null && current != null) {
                    HoldPanel(
                        update = current,
                        reference = reference,
                        phrases = phrases,
                        attempts = holdAttempts,
                        lastSeconds = holdLastSeconds,
                        bestSeconds = holdBestSeconds,
                        awaitingRestart = awaitingRestart,
                        onFinish = {
                            android.util.Log.i(
                                "RPHold",
                                "live session ended - attempts=%d best=%ds"
                                    .format(holdAttempts, holdBestSeconds),
                            )
                            holdTracker.set(null)
                            holdStage = HoldStage.OFF
                            holdUpdate = null
                            holdReference = null
                            holdSavedVersion = null
                            announcedHoldBreak = false
                            holdBrokenSnapshot = null
                            // Arrived here from Today or the physio's library, so Done means done
                            // with the screen. Without this the patient is left on a camera preview
                            // with no way back to their programme.
                            if (startBundle != null) onFinished()
                        },
                    )
                } else {
                    // The single frame between a re-arm and the new tracker's first update lands
                    // here, so it carries the attempt history rather than looking like a fresh start.
                    Text("Get into position…", style = MaterialTheme.typography.titleMedium)
                    holdLastSeconds?.let {
                        Text("Last attempt: ${it}s · best ${holdBestSeconds}s")
                    }
                }
            }
            if (calibrating) {
                calibrationState?.let {
                    CalibrationPanel(
                        state = it,
                        phrases = phrases,
                        onContinueAnyway = { startAnyway = true },
                        onCancel = {
                            calibrationGate.set(null)
                            pendingBundle = null
                            calibrating = false
                            calibrationState = null
                            startAnyway = false
                        },
                    )
                } ?: Text(phrases.calibrationInProgress())
            }
            // §8.2 phone bumped. Shown above the panel rather than replacing it: the reps already
            // banked are still there, and hiding the count would read as having lost them.
            if (inExercise && recalibrating) {
                Text(
                    phrases.recalibrating(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE08A00),
                )
            }
            if (inExercise) {
                ExercisePanel(
                    state = exerciseState,
                    fatigue = fatigue,
                    // The bundle carries the prescription; a hardcoded 12 here would disagree with
                    // the count the engine actually ends the set on.
                    prescribedReps = prescribedReps,
                    phrases = phrases,
                    onFinish = {
                        // The summary only. Every rep it summarises is already on disk, so losing
                        // this write costs the set score and nothing else.
                        val open = sessionId
                        val record = engine.get()?.session
                        val finalFatigue = engine.get()?.fatigue
                        if (open != null && record != null) {
                            scope.launch(Dispatchers.IO) {
                                runCatching { repository.finishSession(open, record, finalFatigue) }
                                    .onFailure { android.util.Log.e("RPStore", "finish failed", it) }
                            }
                        }
                        sessionId = null
                        persistedReps = 0
                        inExercise = false
                        // The patient came here from Today and goes back to it; the physio stays
                        // put, because their next action is usually to record another exercise.
                        if (startBundle != null) onFinished()
                        engine.set(null)
                        viewRecheck.set(null)
                        paused.set(false)
                        recalibrating = false
                        exerciseState = FrameResult.EMPTY
                        fatigue = null
                    },
                )
            }
            Text(
                error ?: "${landmarker.model}/${landmarker.delegate?.name ?: "Starting"} · $analysisSize · " +
                    "$fps fps · ${inferenceMs ?: "-"} ms",
            )
            // §12.4: when speech is unavailable, say so and say what to do about it. Silently
            // running without cues would read as the app having nothing to say.
            ttsSetupMessage(ttsState)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE08A00))
            }
            // Why a rep did or did not count is otherwise invisible: the counter just sits still.
            // φ has to cross PHASE_ENTRY and reach REP_ACCEPTANCE_PHASE_MAX for a rep to register,
            // so showing it turns "it isn't counting" into a number you can read off the screen.
            if (inExercise) {
                Text(
                    "%s · φ %.2f · v %+.2f/s · p %.0f".format(
                        exerciseState.phaseState.name,
                        exerciseState.phi,
                        exerciseState.velocity,
                        exerciseState.phase,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (isRecording && !inExercise) {
                Text("Finding a valid pattern — stops on acceptance or at 1:00.")
                Button(
                    onClick = {
                        recording.set(false)
                        isRecording = false
                        analysing = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop and review") }
            }
            if (!inExercise && !calibrating && holdStage == HoldStage.OFF) {

            // §5.5 step 1 chose the pipeline, so only its control is on screen. A disabled button
            // for the other one would be a permanent question about a decision already made.
            if (authoringHold != true) {
            Button(
                onClick = {
                    if (isRecording) {
                        recording.set(false)
                        isRecording = false
                        synchronized(captured) { captured.clear() }
                        recordedCount = 0
                        remainingSeconds = Params.AUTHORING_ACCEPTANCE_TIMEOUT_SECONDS.toInt()
                    } else {
                        synchronized(captured) { captured.clear() }
                        recordedCount = 0
                        remainingSeconds = Params.AUTHORING_ACCEPTANCE_TIMEOUT_SECONDS.toInt()
                        recording.set(true)
                        isRecording = true
                    }
                },
                enabled = !analysing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        analysing -> "Analysing…"
                        isRecording -> "Cancel recording"
                        else -> "Record 8 reps"
                    },
                )
            }
            }
            Button(
                onClick = {
                    facing = if (facing == CameraFacing.FRONT) CameraFacing.REAR else CameraFacing.FRONT
                    error = null
                },
                enabled = !isRecording && !analysing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Switch camera") }
            // §6.19. An explicit choice rather than §3.8's "offer hold mode when ROM < 15 degrees":
            // the physio knows which of the two they are about to record, and discovering the mode
            // only after a recording is refused is a worse way to find out. That choice is now made
            // on step 1, so this control appears only when a hold is what was chosen.
            if (authoringHold == true) {
            Button(
                onClick = {
                    synchronized(captured) { captured.clear() }
                    neutral = null
                    holdReference = null
                    holdFailure = null
                    holdSeconds = 0
                    // A new recording is a new exercise. Carrying the last one's save state over
                    // would show "Saved as version 2" above a hold that is not saved at all.
                    holdSavedVersion = null
                    holdNaming = false
                    saveError = null
                    recording.set(true)
                    catalogWarnings = emptyList()
                    holdStage = HoldStage.NEUTRAL
                },
                enabled = !isRecording && !analysing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Record the hold") }
            }
            }
            // Language is switchable at any time, including mid-set: a patient who cannot follow
            // the cue does not benefit from being made to restart the set to change it.
            Button(
                onClick = {
                    language = if (language == SpeechLanguage.ENGLISH) {
                        SpeechLanguage.HINDI
                    } else {
                        SpeechLanguage.ENGLISH
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Voice: ${language.label}") }
        }
    }
}

@Composable
private fun SkeletonOverlay(result: PoseFrameResult) {
    Canvas(
        modifier = Modifier.fillMaxSize().semantics {
            contentDescription = "Live pose skeleton"
        },
    ) {
        val inputAspect = result.inputWidth.toFloat() / result.inputHeight
        val viewAspect = size.width / size.height
        val drawnWidth = if (viewAspect > inputAspect) size.height * inputAspect else size.width
        val drawnHeight = if (viewAspect > inputAspect) size.height else size.width / inputAspect
        val left = (size.width - drawnWidth) / 2f
        val top = (size.height - drawnHeight) / 2f
        fun point(index: Int) = result.frame.imageLandmarks[index].let {
            Offset(left + it.x * drawnWidth, top + it.y * drawnHeight)
        }
        POSE_SKELETON_CONNECTIONS.forEach { (start, end) ->
            if (result.frame.visibility[start] >= 0.5f && result.frame.visibility[end] >= 0.5f) {
                drawLine(Color.Green, point(start), point(end), strokeWidth = 6f)
            }
        }
    }
}

/**
 * Which of §5.5's five steps the camera flow is currently on.
 *
 * Derived from the states that were already driving what is on screen rather than from a new step
 * variable, so the header cannot disagree with the buttons under it. Steps 2 to 5 only: step 1 is
 * its own screen, and by the time this composable exists it has already been answered.
 */
private fun authoringStep(
    calibrating: Boolean,
    recording: Boolean,
    analysing: Boolean,
    holdStage: String,
    naming: Boolean,
    reviewing: Boolean,
): Int = when {
    naming -> 5
    analysing || reviewing || holdStage == "REVIEW" -> 4
    recording || holdStage in RECORDING_STAGES -> 3
    calibrating -> 2
    else -> 2
}

private fun authoringStepLabel(
    calibrating: Boolean,
    recording: Boolean,
    analysing: Boolean,
    holdStage: String,
    naming: Boolean,
    reviewing: Boolean,
): String = when (
    authoringStep(calibrating, recording, analysing, holdStage, naming, reviewing)
) {
    5 -> "Name and save"
    4 -> "Review"
    3 -> "Record"
    else -> "Set up"
}

/** The hold stages that are a recording in progress, by name so the enum stays private. */
private val RECORDING_STAGES = setOf("NEUTRAL", "POSITIONING", "RECORDING")
