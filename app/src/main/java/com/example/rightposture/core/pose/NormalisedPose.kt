package com.example.rightposture.core.pose

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params

/**
 * 10.1. The body as a set of positions, not angles, so it can be drawn.
 *
 * Every other pose quantity in `core` is a joint angle, which is enough to *score* a pose and
 * nothing like enough to *draw* one. This is the drawable counterpart, in a coordinate system that
 * does not care where the body stood, which way it faced, or how big it was.
 */

/**
 * The skeleton joints the app draws (`pose/PoseSkeleton.kt`'s twelve bones).
 *
 * Restated here rather than imported: `pose/` is the Android package, and `core` is upstream of it.
 * `NormalisedPoseTest` pins that every drawn bone's endpoints are in [GHOST_LANDMARKS], so the two
 * lists cannot drift apart silently.
 */
private val DRAWN_JOINTS = listOf(
    PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
    PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
    PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
    PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
    PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
)

/**
 * Landmarks a normalised pose carries, in slot order.
 *
 * Not all 33. The face points past the nose and the finger points are noise at this distance and
 * no §6.5 feature depends on them. This is the union of the drawn skeleton and every landmark any
 * feature is computed from, on either side - derived from [contributingLandmarks] rather than
 * listed, so a feature added later brings its landmarks with it.
 *
 * The nose stays: it is the only landmark above the shoulders, and it is what tells a sagittal view
 * which way the body faces (10.6).
 */
val GHOST_LANDMARKS: List<Int> = (
    listOf(PoseLandmark.NOSE) +
        DRAWN_JOINTS +
        FeatureId.entries.flatMap { id -> Side.entries.flatMap { contributingLandmarks(id, it) } }
    ).distinct().sorted()

/** Slot of each landmark id within [GHOST_LANDMARKS]. */
val GHOST_LANDMARK_SLOT: Map<Int, Int> =
    GHOST_LANDMARKS.withIndex().associate { (slot, id) -> id to slot }

/**
 * For each slot, the slot holding that landmark's anatomical opposite. Unpaired landmarks (the
 * nose) map to themselves.
 */
val GHOST_MIRROR_SLOT: List<Int> = GHOST_LANDMARKS.map { id ->
    val opposite = PoseLandmark.LATERAL_PAIRS
        .firstOrNull { it.first == id || it.second == id }
        ?.let { if (it.first == id) it.second else it.first }
    // A landmark whose opposite is not carried would leave a hole under mirroring. Every pair in
    // the subset is complete by construction today; fail loudly if that ever stops being true.
    if (opposite == null) GHOST_LANDMARK_SLOT.getValue(id) else GHOST_LANDMARK_SLOT.getValue(opposite)
}

/**
 * One pose, one [Vec3] per [GHOST_LANDMARKS] slot, in torso lengths.
 *
 * ```
 * p_body = BodyFrame.toBody(world[i] − midHip) / |midShoulder − midHip|
 * ```
 *
 * All three normalisations are load-bearing. Without the origin the pose is pinned to wherever the
 * body stood; without the rotation it faces whichever way the camera did; without the torso scale it
 * carries that body's size and distance rather than the one it will be drawn over.
 *
 * Axes are §6.4's: x from the left hip toward the right, y up the torso, z = x × y.
 */
data class NormalisedPose(val points: List<Vec3>) {
    init {
        require(points.size == GHOST_LANDMARKS.size) {
            "a normalised pose has ${GHOST_LANDMARKS.size} points, got ${points.size}"
        }
    }

    /** The point for landmark [id], or null when [id] is not one [GHOST_LANDMARKS] carries. */
    operator fun get(id: Int): Vec3? = GHOST_LANDMARK_SLOT[id]?.let { points[it] }
}

/**
 * [frame] expressed against [body], or null when the frame cannot support a pose: a torso too
 * short to scale by, or a non-finite landmark.
 *
 * `worldLandmarks` only, never `imageLandmarks`. The image ones are perspective-divided and their z
 * is not in the same unit as their x and y, so a pose built from them would be a basis over three
 * unrelated quantities - and §6.4's degeneracy guards would not notice.
 */
fun normalisedPose(frame: LandmarkFrame, body: BodyFrame): NormalisedPose? {
    val world = frame.worldLandmarks
    val midHip = midpoint(world[PoseLandmark.LEFT_HIP], world[PoseLandmark.RIGHT_HIP])
    val midShoulder = midpoint(world[PoseLandmark.LEFT_SHOULDER], world[PoseLandmark.RIGHT_SHOULDER])

    // Same floor §6.4 builds the body frame against. A torso shorter than that is a detection
    // failure, and dividing by it would blow every point out to infinity.
    val torso = (midShoulder - midHip).length()
    if (!torso.isFinite() || torso < Params.BODY_FRAME_MIN_TORSO_METRES) return null

    val scale = 1f / torso
    val points = GHOST_LANDMARKS.map { id -> body.toBody(world[id] - midHip) * scale }
    if (points.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) return null
    return NormalisedPose(points)
}

/**
 * The same pose with left and right swapped, as seen in a mirror.
 *
 * **A mirror is not a negation.** Negating x without relabelling leaves the left knee where the
 * right one belongs and every bone drawn to the wrong joint - the trap §6.2 names for the live feed.
 * The relabel and the negation are one operation, and only x is negated: reflecting the world and
 * relabelling flips the body frame's x axis with it, which leaves y and z where they were.
 */
fun NormalisedPose.mirrored(): NormalisedPose = NormalisedPose(
    List(points.size) { slot ->
        val source = points[GHOST_MIRROR_SLOT[slot]]
        Vec3(-source.x, source.y, source.z)
    },
)

private fun midpoint(a: Vec3, b: Vec3) = Vec3((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f)
