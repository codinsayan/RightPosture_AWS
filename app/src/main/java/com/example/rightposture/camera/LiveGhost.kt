package com.example.rightposture.camera

import com.example.rightposture.core.Params
import com.example.rightposture.core.live.Bone
import com.example.rightposture.core.live.FrameResult
import com.example.rightposture.core.live.GhostClock
import com.example.rightposture.core.live.GhostState
import com.example.rightposture.core.live.GhostTick
import com.example.rightposture.core.live.PhaseState
import com.example.rightposture.core.live.PlacedGhost
import com.example.rightposture.core.live.forCamera
import com.example.rightposture.core.live.medianBoneLengths
import com.example.rightposture.core.live.phaseLag
import com.example.rightposture.core.live.placeGhost
import com.example.rightposture.core.live.retargeted
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.GHOST_LANDMARK_SLOT
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.classifyView
import com.example.rightposture.core.reference.SkeletonReference
import com.example.rightposture.core.rpose.RposeBundle
import kotlin.math.roundToInt

/**
 * 10.9. What the panel says about the guide, at most one line at a time.
 *
 * Ordered by what the patient needs: the count-in tells them it is about to move, the prompt asks
 * a patient who has stopped to move with it, and behind is a nudge, never a fault.
 */
sealed interface GhostHint {
    data class CountIn(val seconds: Int) : GhostHint
    data object Prompt : GhostHint
    data object Behind : GhostHint
}

/** One sampler tick of the live ghost: where to draw it, which bones, and what to say. */
data class LiveGhostFrame(
    val tick: GhostTick,
    /** Null when there is nothing to draw: set done, wrong view, or placement refused. */
    val ghost: PlacedGhost?,
    /** The connections worth drawing at this phase; see [drawableBones]. */
    val bones: List<Pair<Int, Int>>,
    val hint: GhostHint?,
)

/**
 * 10.9. The ghost in a patient's live set: 10.8's clock beside the scoring engine, 10.5's placement,
 * 10.6's camera relabelling and 10.7's limb lengths, run once per UI tick.
 *
 * Beside the engine, not inside it, on purpose. The engine scores the patient on their **own**
 * phase; this only reads the engine's result to know whether the patient is moving and when a rep
 * ended, and nothing here flows back into a score.
 *
 * No Android types, so a JVM test can drive it.
 */
class LiveGhost(
    private val skeleton: SkeletonReference,
    /** `manifest.mirrored` of the bundle the skeleton came from. */
    private val recordedMirrored: Boolean,
    /** The view the exercise was recorded in; any other live view gets no ghost. */
    private val requiredView: CameraView,
) {
    private val clock = GhostClock(skeleton.timing)
    private val recent = ArrayDeque<LandmarkFrame>()
    private var limbs: Map<Bone, Float> = emptyMap()
    private var framesSinceLimbs = LIMB_REFRESH_FRAMES
    private var repsSeen: Int? = null
    private var behind = false

    /**
     * @param state the engine's latest result. Read for movement and rep counts only.
     * @param frame the latest landmarks, or null when nobody is in view.
     * @param timestampNs the frame's timestamp. Repeated timestamps do not advance the clock.
     * @param imageAspect width / height of the analysed image.
     * @param liveMirrored whether the live camera is the front one.
     * @param prescribedReps the set's target; reaching it stops the guide.
     */
    fun update(
        state: FrameResult,
        frame: LandmarkFrame?,
        timestampNs: Long,
        imageAspect: Float,
        liveMirrored: Boolean,
        prescribedReps: Int,
    ): LiveGhostFrame {
        // Rep completion from the counters, not from `completedRep`: that field lives for one
        // engine frame, and a 30 Hz UI sampling a 30 Hz engine misses some of them.
        val reps = state.validReps + state.partialReps
        val repCompleted = repsSeen?.let { reps > it } ?: false
        repsSeen = reps

        val tick = clock.tick(
            timestampNs = timestampNs,
            patientMoving = state.phaseState != PhaseState.IDLE,
            repCompleted = repCompleted,
            setComplete = prescribedReps > 0 && state.validReps >= prescribedReps,
        )

        frame?.let { rememberLimbs(it) }
        val phase = tick.phase
        val ghost = if (phase == null || frame == null) {
            null
        } else {
            val pose = skeleton.poseAtPhase(phase).forCamera(recordedMirrored, liveMirrored)
            placeGhost(pose, frame, imageAspect, classifyView(frame), requiredView)?.retargeted(pose, limbs)
        }

        val lag = if (phase != null && tick.state == GhostState.MOVING) phaseLag(phase, state.phase) else 0.0
        behind = when {
            tick.state != GhostState.MOVING -> false
            behind -> lag > BEHIND_OFF_PHASE
            else -> lag > BEHIND_ON_PHASE
        }

        val hint = when {
            tick.state == GhostState.COUNT_IN ->
                GhostHint.CountIn(kotlin.math.ceil(tick.countInSeconds ?: 0.0).toInt().coerceAtLeast(1))
            tick.state == GhostState.DONE -> null
            // A still patient is not "behind"; they have not started. Ask them to join in.
            tick.patientIdle -> GhostHint.Prompt
            behind -> GhostHint.Behind
            else -> null
        }

        return LiveGhostFrame(
            tick = tick,
            ghost = ghost,
            bones = if (ghost == null || phase == null) emptyList() else drawableBones(skeleton, phase),
            hint = hint,
        )
    }

    /**
     * The patient's limb lengths over the last few seconds, refreshed once a second. A median over
     * a window rather than one frame, because one frame's forearm jitters, and a ghost whose limbs
     * pulse is a worse guide than one a little off.
     */
    private fun rememberLimbs(frame: LandmarkFrame) {
        recent.addLast(frame)
        while (recent.size > LIMB_WINDOW_FRAMES) recent.removeFirst()
        if (++framesSinceLimbs >= LIMB_REFRESH_FRAMES) {
            limbs = medianBoneLengths(recent.toList())
            framesSinceLimbs = 0
        }
    }

    companion object {
        /** Track B's own constants, kept out of `core/Params.kt` per PARALLEL-TRACKS. */
        const val LIMB_WINDOW_FRAMES = 90
        const val LIMB_REFRESH_FRAMES = 30

        /**
         * "Behind" switches on past this much phase lag and off below [BEHIND_OFF_PHASE]. Two
         * thresholds, so a patient hovering at the edge does not make the line flicker. A quarter
         * of a rep is clearly behind; anything less is ordinary following.
         */
        const val BEHIND_ON_PHASE = 25.0
        const val BEHIND_OFF_PHASE = 15.0

        /** The minimum authoring visibility for a bone's two ends to be drawn. */
        const val MIN_BONE_VISIBILITY = 0.5f

        /**
         * The largest spread across the physio's reps, in torso lengths, for a bone end to be
         * drawn. Generous on purpose: on device the moving wrist reached 0.17 at its widest, and
         * hiding the very limb being taught would defeat the guide.
         */
        const val MAX_BONE_SPREAD = 0.25f

        fun from(bundle: RposeBundle): LiveGhost? {
            val skeleton = bundle.skeleton ?: return null
            val view = CameraView.entries.firstOrNull {
                it.name.equals(bundle.manifest.requiredView, ignoreCase = true)
            } ?: return null
            return LiveGhost(skeleton, bundle.manifest.mirrored, view)
        }
    }
}

/**
 * 10.9's per-bone abstention. A bone is drawn when both its ends were seen well in every authoring
 * rep at this phase and the physio put them in the same place each time. Hiding one uncertain ankle
 * is better than hiding the whole guide, and much better than drawing a limb the reference does not
 * actually know.
 */
fun drawableBones(
    skeleton: SkeletonReference,
    phase: Double,
    connections: List<Pair<Int, Int>> = com.example.rightposture.pose.POSE_SKELETON_CONNECTIONS,
): List<Pair<Int, Int>> {
    val bin = phase.roundToInt().coerceIn(0, Params.PHASE_BINS - 1)
    fun trusted(id: Int): Boolean {
        val slot = GHOST_LANDMARK_SLOT[id] ?: return false
        return skeleton.visibilityAt(bin, slot) >= LiveGhost.MIN_BONE_VISIBILITY &&
            skeleton.spreadAt(bin, slot) <= LiveGhost.MAX_BONE_SPREAD
    }
    return connections.filter { (start, end) -> trusted(start) && trusted(end) }
}
