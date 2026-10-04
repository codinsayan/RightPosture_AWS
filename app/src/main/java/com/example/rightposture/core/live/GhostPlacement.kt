package com.example.rightposture.core.live

import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.CameraView
import com.example.rightposture.core.pose.GHOST_LANDMARK_SLOT
import com.example.rightposture.core.pose.LandmarkFrame
import com.example.rightposture.core.pose.NormalisedPose
import com.example.rightposture.core.pose.PoseLandmark
import com.example.rightposture.core.pose.mirrored
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 10.5. A reference pose placed over a live body, in image space.
 *
 * Pure: the overlay draws what it is handed and decides nothing. Coordinates out are MediaPipe's own
 * normalised image coordinates (0-1, y down), so the ghost goes through exactly the letterbox
 * arithmetic the live skeleton does.
 */

/** A point in MediaPipe's normalised image coordinates: fractions of width and height, y down. */
data class GhostPoint(val x: Float, val y: Float)

/** One point per [com.example.rightposture.core.pose.GHOST_LANDMARKS] slot. */
data class PlacedGhost(val points: List<GhostPoint>) {
    /** The point for landmark [id], or null when that landmark is not carried. */
    operator fun get(id: Int): GhostPoint? = GHOST_LANDMARK_SLOT[id]?.let { points[it] }
}

/**
 * Places [pose] over the body in [frame], or returns null whenever the placement cannot be trusted.
 *
 * The fit is a 2D similarity - rotation, uniform scale, translation - carrying the reference's
 * hip-to-shoulder vector onto the live one. It needs only the four torso landmarks, so it survives a
 * REUSED body frame. It is also exactly determined: four equations, four unknowns, zero residual
 * always. It can never report being wrong, which is why 10.5 ships a sign-check screen before any
 * patient sees a ghost, and why every refusal below errs towards drawing nothing.
 *
 * @param imageAspect width / height of the image the landmarks were measured on. MediaPipe's x and
 *   y are fractions of *different* lengths, so a similarity fitted in them directly is a stretch in
 *   pixels on every non-square image. The fit runs in aspect-corrected units and converts back.
 * @param liveView the live body's own view classification, which picks the projection plane.
 * @param requiredView the view the exercise was recorded in. Only a gate: a body in another view
 *   gets no ghost, because a frontal projection cannot show a sagittal movement (and vice versa).
 */
fun placeGhost(
    pose: NormalisedPose,
    frame: LandmarkFrame,
    imageAspect: Float,
    liveView: CameraView?,
    requiredView: CameraView,
): PlacedGhost? {
    if (liveView == null || liveView == CameraView.OBLIQUE || liveView != requiredView) return null
    if (!(imageAspect > 0f) || !imageAspect.isFinite()) return null
    if (TORSO.any { frame.visibility[it] < Params.VIS_MIN }) return null
    val sagittal = liveView == CameraView.SAGITTAL
    if (sagittal && frame.visibility[PoseLandmark.NOSE] < Params.VIS_MIN) return null

    // 1. Project, choosing the plane from the live view. Frontal keeps the medio-lateral axis (body
    //    x), sagittal the antero-posterior one (body z); world depth never drives a drawn
    //    coordinate otherwise. Body y runs up the torso and image y runs down the screen, so y is
    //    negated here - left alone, the fit below would resolve the disagreement as a 180 degree
    //    rotation, which also flips the horizontal axis and silently mirrors the whole ghost.
    var flat = pose.points.map { Planar(if (sagittal) it.z else it.x, -it.y) }

    // 2. The live body, in aspect-corrected units so that one unit is the same length both ways.
    val live = PoseLandmarkPlanar(frame, imageAspect)
    val liveHip = midpoint(live(PoseLandmark.LEFT_HIP), live(PoseLandmark.RIGHT_HIP))
    val liveTorso = midpoint(live(PoseLandmark.LEFT_SHOULDER), live(PoseLandmark.RIGHT_SHOULDER)) - liveHip
    val liveLength = liveTorso.length()
    if (liveLength < Params.MIN_VECTOR_LENGTH) return null

    // 3. Facing. The similarity has no reflection, so which way the horizontal axis runs has to be
    //    measured, not assumed - assuming it is what left §6.16's arrow dead. The same quantity is
    //    taken on both bodies, across their own torso axis and in their own torso lengths, and the
    //    reference's horizontal axis is negated when they disagree. Below the margin on either side
    //    the answer could go either way, and nothing is drawn.
    val referenceFacing = facing(flatAt(flat), sagittal)
    val liveFacing = facing(live, sagittal)
    if (referenceFacing == null || liveFacing == null) return null
    if (abs(referenceFacing) < Params.GHOST_FACING_MARGIN || abs(liveFacing) < Params.GHOST_FACING_MARGIN) return null
    if ((referenceFacing > 0f) != (liveFacing > 0f)) flat = flat.map { Planar(-it.x, it.y) }

    // 4. The similarity, as one complex division: live torso / reference torso = scale * rotation.
    //    No trigonometry, so no quadrant to get wrong.
    val reference = flatAt(flat)
    val referenceHip = midpoint(reference(PoseLandmark.LEFT_HIP), reference(PoseLandmark.RIGHT_HIP))
    val referenceTorso = midpoint(reference(PoseLandmark.LEFT_SHOULDER), reference(PoseLandmark.RIGHT_SHOULDER)) - referenceHip
    val referenceLengthSquared = referenceTorso.x * referenceTorso.x + referenceTorso.y * referenceTorso.y
    if (referenceLengthSquared < Params.MIN_VECTOR_LENGTH) return null
    val cos = (liveTorso.x * referenceTorso.x + liveTorso.y * referenceTorso.y) / referenceLengthSquared
    val sin = (liveTorso.y * referenceTorso.x - liveTorso.x * referenceTorso.y) / referenceLengthSquared

    val points = flat.map { point ->
        val dx = point.x - referenceHip.x
        val dy = point.y - referenceHip.y
        val u = liveHip.x + cos * dx - sin * dy
        val v = liveHip.y + sin * dx + cos * dy
        GhostPoint(u / imageAspect, v)
    }
    if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    return PlacedGhost(points)
}

/**
 * 10.6. The reference pose as the live camera would label it.
 *
 * The front camera's bitmap is flipped before inference (`PoseLandmarkerSource`), so a front-camera
 * recording is of the physio's mirror image: MediaPipe labels their anatomical right `LEFT_*`, and
 * its world coordinates describe a mirror body. A reference and a live feed from cameras that
 * disagree are therefore mirror images of each other, and the reference must be relabelled and
 * reflected - [mirrored] - before it is placed. From the same camera, nothing changes.
 *
 * **The stored bits are the only source of this, and cannot be checked against the geometry.** A
 * mirror body is a valid body: `normalisedPose` of a reflected, relabelled frame is exactly
 * `mirrored()` of the original, and a person facing either camera has their labelled-right shoulder
 * on screen-left, so [placeGhost]'s facing measure reads the same from both. No single frame can say
 * which camera it came from. `ChiralityTest` pins that, and the cross-camera device gate is what
 * verifies the bits.
 *
 * @param recordedMirrored `manifest.mirrored` of the bundle the pose came from.
 * @param liveMirrored whether the live feed is mirrored, i.e. the front camera.
 */
fun NormalisedPose.forCamera(recordedMirrored: Boolean, liveMirrored: Boolean): NormalisedPose =
    if (recordedMirrored != liveMirrored) mirrored() else this

/**
 * Which way a body faces, as a signed number in torso lengths, measured across its own torso axis
 * so that a tilted or lying body is judged the same as an upright one.
 *
 * Frontal: the labelled-right shoulder relative to the labelled-left one, which is wide and well
 * conditioned facing the camera. Sagittal: the nose relative to the hip midpoint - the shoulder line
 * collapses to nearly a point side-on, and the nose is the one landmark clearly forward of the hips.
 */
private fun facing(body: (Int) -> Planar, sagittal: Boolean): Float? {
    val hip = midpoint(body(PoseLandmark.LEFT_HIP), body(PoseLandmark.RIGHT_HIP))
    val torso = midpoint(body(PoseLandmark.LEFT_SHOULDER), body(PoseLandmark.RIGHT_SHOULDER)) - hip
    val length = torso.length()
    if (length < Params.MIN_VECTOR_LENGTH) return null
    val across = if (sagittal) {
        body(PoseLandmark.NOSE) - hip
    } else {
        body(PoseLandmark.RIGHT_SHOULDER) - body(PoseLandmark.LEFT_SHOULDER)
    }
    // 2D cross product with the unit torso axis: the component of `across` perpendicular to it.
    return (torso.x * across.y - torso.y * across.x) / (length * length)
}

private val TORSO = intArrayOf(
    PoseLandmark.LEFT_HIP,
    PoseLandmark.RIGHT_HIP,
    PoseLandmark.LEFT_SHOULDER,
    PoseLandmark.RIGHT_SHOULDER,
)

/** A 2D point in one isotropic unit. Internal to the fit; never leaves this file. */
private data class Planar(val x: Float, val y: Float) {
    operator fun minus(other: Planar) = Planar(x - other.x, y - other.y)
    fun length() = sqrt(x * x + y * y)
}

private fun midpoint(a: Planar, b: Planar) = Planar((a.x + b.x) / 2f, (a.y + b.y) / 2f)

/** Lookup by landmark id into a flat reference projection. */
private fun flatAt(flat: List<Planar>): (Int) -> Planar = { id -> flat[GHOST_LANDMARK_SLOT.getValue(id)] }

/** The live frame's image landmarks, x scaled by the aspect ratio so both axes share one unit. */
private class PoseLandmarkPlanar(private val frame: LandmarkFrame, private val aspect: Float) : (Int) -> Planar {
    override fun invoke(id: Int): Planar =
        frame.imageLandmarks[id].let { Planar(it.x * aspect, it.y) }
}
