package com.example.rightposture.core.live

import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.GHOST_LANDMARK_SLOT
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.NormalisedPose
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.Vec3

/**
 * 10.7. The ghost drawn with the patient's own limb lengths.
 *
 * [placeGhost] scales the physio's whole body by the torso, so the ghost's wrist lands wherever the
 * *physio's* arm reached. A longer-limbed patient can hold a perfect joint angle and never touch it -
 * for a leading ghost, actively teaching the wrong movement. Here each limb keeps the reference's
 * direction and takes the patient's length, which preserves the joint angles (the thing taught and
 * scored) and makes the target reachable.
 */

/** A limb segment, by landmark id, parent end first. */
data class Bone(val parent: Int, val child: Int)

/**
 * The limbs, parents before children, so one forward pass places everything: an elbow can only be
 * positioned once the shoulder it hangs from is.
 *
 * The torso is absent on purpose. Its four corners are where [placeGhost]'s fit put them, and
 * re-deriving hip or shoulder width here would fight that fit.
 *
 * The foot is ankle to foot index, with no heel: `GHOST_LANDMARKS` carries only what a feature is
 * computed from or the skeleton draws, and ankle dorsiflexion uses the foot index. `GhostRetargetTest`
 * pins that every end here is carried.
 */
val RETARGETED_BONES: List<Bone> = PoseLandmark.run {
    listOf(
        Bone(LEFT_SHOULDER, LEFT_ELBOW), Bone(LEFT_ELBOW, LEFT_WRIST),
        Bone(RIGHT_SHOULDER, RIGHT_ELBOW), Bone(RIGHT_ELBOW, RIGHT_WRIST),
        Bone(LEFT_HIP, LEFT_KNEE), Bone(LEFT_KNEE, LEFT_ANKLE), Bone(LEFT_ANKLE, LEFT_FOOT_INDEX),
        Bone(RIGHT_HIP, RIGHT_KNEE), Bone(RIGHT_KNEE, RIGHT_ANKLE), Bone(RIGHT_ANKLE, RIGHT_FOOT_INDEX),
    )
}

/**
 * The patient's limb lengths in this frame, in their own torso lengths, for every bone whose both
 * ends were seen.
 *
 * Measured in **3D world** coordinates, not in the image. An image length shrinks whenever the limb
 * points at the camera, so a forearm reaching towards the lens would read as short and the ghost's
 * forearm would shrink with it on every such frame. In world units the length is the length.
 */
fun boneLengths(frame: LandmarkFrame): Map<Bone, Float> {
    val world = frame.worldLandmarks
    val torso = (
        midpoint(world[PoseLandmark.LEFT_SHOULDER], world[PoseLandmark.RIGHT_SHOULDER]) -
            midpoint(world[PoseLandmark.LEFT_HIP], world[PoseLandmark.RIGHT_HIP])
        ).length()
    if (!torso.isFinite() || torso < Params.BODY_FRAME_MIN_TORSO_METRES) return emptyMap()
    return RETARGETED_BONES.mapNotNull { bone ->
        if (frame.visibility[bone.parent] < Params.VIS_MIN || frame.visibility[bone.child] < Params.VIS_MIN) {
            return@mapNotNull null
        }
        val length = (world[bone.child] - world[bone.parent]).length() / torso
        if (length.isFinite() && length > 0f) bone to length else null
    }.toMap()
}

/**
 * The median of each bone's length across [frames], ignoring frames where it was not seen.
 *
 * What a caller should pass to [retargeted]. One frame's lengths jitter with MediaPipe's noise, and
 * a ghost whose forearm pulsed from frame to frame would be a worse picture than one that is
 * slightly the wrong length but still.
 */
fun medianBoneLengths(frames: List<LandmarkFrame>): Map<Bone, Float> {
    val perBone = mutableMapOf<Bone, MutableList<Float>>()
    frames.forEach { frame -> boneLengths(frame).forEach { (bone, length) -> perBone.getOrPut(bone) { mutableListOf() } += length } }
    return perBone.mapValues { (_, lengths) ->
        val sorted = lengths.sorted()
        val mid = sorted.size / 2
        if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}

/**
 * This ghost with each limb resized to [patientLengths], keeping the direction [placeGhost] gave it.
 *
 * Works on the ratio of 3D lengths: patient over [pose]'s own, both in torso lengths. Projection is
 * linear, so scaling a bone's 3D vector by that ratio scales its drawn vector by the same ratio -
 * which is why the drawn bone can simply be stretched along itself, and why foreshortening cannot
 * leak in.
 *
 * A bone with no patient length, or a ratio outside [Params.GHOST_RETARGET_MAX_RATIO] either way,
 * keeps its fitted length. It is still carried along by a parent that moved: a missing forearm is
 * more confusing to a patient than a slightly long one, and a detached one worse still.
 *
 * @param pose the reference pose this ghost was placed from, in body-frame torso lengths.
 */
fun PlacedGhost.retargeted(pose: NormalisedPose, patientLengths: Map<Bone, Float>): PlacedGhost {
    val fitted = points
    val out = points.toMutableList()
    for (bone in RETARGETED_BONES) {
        val parent = GHOST_LANDMARK_SLOT.getValue(bone.parent)
        val child = GHOST_LANDMARK_SLOT.getValue(bone.child)
        val referenceLength = (pose.points[child] - pose.points[parent]).length()
        val patientLength = patientLengths[bone]
        val ratio = if (patientLength == null || referenceLength < Params.MIN_VECTOR_LENGTH) {
            1f
        } else {
            (patientLength / referenceLength).takeIf {
                it in (1f / Params.GHOST_RETARGET_MAX_RATIO)..Params.GHOST_RETARGET_MAX_RATIO
            } ?: 1f
        }
        // From the parent's *new* position, along the fitted direction: the child follows its
        // parent whether or not its own length changed.
        out[child] = GhostPoint(
            out[parent].x + (fitted[child].x - fitted[parent].x) * ratio,
            out[parent].y + (fitted[child].y - fitted[parent].y) * ratio,
        )
    }
    return PlacedGhost(out)
}

private fun midpoint(a: Vec3, b: Vec3) = Vec3((a.x + b.x) / 2f, (a.y + b.y) / 2f, (a.z + b.z) / 2f)
