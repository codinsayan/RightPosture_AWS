package com.example.rightposture.core.reference

import com.example.rightposture.core.Params
import com.example.rightposture.core.filter.butterworthFiltFilt
import com.example.rightposture.core.pose.BodyFrameResult
import com.example.rightposture.core.pose.BodyFrameStatus
import com.example.rightposture.core.pose.GHOST_LANDMARKS
import com.example.rightposture.core.pose.NormalisedPose
import com.example.rightposture.core.pose.Vec3
import com.example.rightposture.core.pose.normalisedPose
import kotlin.math.abs

/**
 * 10.2. The physio's body over one rep, on the §6.8.4 phase grid.
 *
 * The rest of the reference is fifteen independent scalar curves (§6.9). Those are enough to
 * *score* a pose and nothing like enough to *draw* one: a skeleton needs positions, and no angle
 * reference can be inverted back into one without limb lengths it never stored.
 *
 * Stored flat with a documented stride rather than nested, because that is how it will be written
 * to the bundle (10.4): `Json.kt` has no nested-array reader.
 */
data class SkeletonReference(
    /** Landmark ids in slot order. Carried so a later build can change [GHOST_LANDMARKS]. */
    val landmarks: List<Int>,
    /** `[bin][slot][xyz]`, stride `landmarks.size * 3` then 3, in torso lengths. Median across reps. */
    val positions: List<Float>,
    /**
     * `[bin][slot]`. How far the physio's own reps scattered around [positions], as a distance.
     *
     * The drawing counterpart of §6.9's sigma: a landmark the physio did not place consistently is
     * one a ghost should not draw confidently.
     */
    val spread: List<Float>,
    /** `[bin][slot]`. The **lowest** visibility any contributing rep had there. */
    val visibility: List<Float>,
    /**
     * Per bin, whether any contributing frame had a [BodyFrameStatus.FRESH] body frame.
     *
     * Provenance, not a rejection. A sagittal recording is mostly REUSED by design (§6.4); a bin
     * with no fresh support rests on a rotation nobody measured at that instant, and a reader
     * deciding how far to trust it needs to know that.
     */
    val fresh: List<Boolean>,
    /** How many reps the medians were taken over. */
    val repCount: Int,
    /** 10.3. How the physio moved through [positions] in time, for a ghost that leads. */
    val timing: PhaseTiming,
) {
    init {
        val bins = fresh.size
        val slots = landmarks.size
        require(positions.size == bins * slots * 3) { "positions: ${positions.size} for $bins x $slots x 3" }
        require(spread.size == bins * slots) { "spread: ${spread.size} for $bins x $slots" }
        require(visibility.size == bins * slots) { "visibility: ${visibility.size} for $bins x $slots" }
    }

    val binCount: Int get() = fresh.size

    /** The median pose at a whole bin. */
    fun poseAt(bin: Int): NormalisedPose {
        val base = checkedBin(bin) * landmarks.size * 3
        return NormalisedPose(
            List(landmarks.size) { slot ->
                val at = base + slot * 3
                Vec3(positions[at], positions[at + 1], positions[at + 2])
            },
        )
    }

    /**
     * 10.5. The pose at a phase between bins, linearly interpolated. Clamped to the grid: phase
     * outside 0..100 is a caller's rounding, not a request for a pose that does not exist.
     */
    fun poseAtPhase(phase: Double): NormalisedPose {
        val clamped = phase.coerceIn(0.0, (binCount - 1).toDouble())
        val lower = clamped.toInt().coerceAtMost(binCount - 2)
        val t = (clamped - lower).toFloat()
        val a = poseAt(lower).points
        val b = poseAt(lower + 1).points
        return NormalisedPose(
            List(a.size) { slot ->
                Vec3(
                    a[slot].x + (b[slot].x - a[slot].x) * t,
                    a[slot].y + (b[slot].y - a[slot].y) * t,
                    a[slot].z + (b[slot].z - a[slot].z) * t,
                )
            },
        )
    }

    fun spreadAt(bin: Int, slot: Int): Float = spread[checkedBin(bin) * landmarks.size + slot]

    fun visibilityAt(bin: Int, slot: Int): Float = visibility[checkedBin(bin) * landmarks.size + slot]

    private fun checkedBin(bin: Int): Int {
        require(bin in 0 until binCount) { "bin $bin outside 0 until $binCount" }
        return bin
    }
}

/**
 * 10.3. Phase at evenly spaced points through the median rep's **duration**.
 *
 * The one place in the reference pipeline indexed by time rather than by phase, and it exists only
 * because the ghost leads. Phase comes from the dominant joint's value and deliberately carries no
 * timing (§6.8.1) - which is what makes scoring fair to a patient at their own speed, and what makes
 * phase useless for driving a demonstration. An end-range hold is one phase bin and several
 * seconds, so a ghost advanced linearly through phase would skip straight through the part of the
 * exercise the physio spent longest on. This is the missing axis: it flattens where they paused.
 */
data class PhaseTiming(
    /**
     * `p` at [Params.PHASE_BINS] evenly spaced instants from the start of the rep to its end.
     * Non-decreasing, because phase is (§6.8.3) and a pointwise median of non-decreasing curves is.
     */
    val phaseByTime: List<Float>,
    /** Median rep duration, seconds. [phaseByTime] spans exactly this long. */
    val repDurationSeconds: Double,
) {
    init {
        require(phaseByTime.size >= 2) { "a timing profile needs at least two samples" }
        require(repDurationSeconds > 0.0 && repDurationSeconds.isFinite()) {
            "rep duration must be positive, got $repDurationSeconds"
        }
        require(phaseByTime.zipWithNext().all { (a, b) -> b >= a }) {
            "phase must not go backwards in time"
        }
    }

    /**
     * The phase to show [elapsedSeconds] into a rep, by the physio's own timing.
     *
     * Past the end this holds the last sample rather than wrapping. The cycle boundary belongs to the
     * ghost clock (10.8), the only part that knows about the rest beat between reps.
     */
    fun phaseAt(elapsedSeconds: Double): Double {
        val fraction = (elapsedSeconds / repDurationSeconds).coerceIn(0.0, 1.0)
        val position = fraction * phaseByTime.lastIndex
        val lower = position.toInt().coerceAtMost(phaseByTime.lastIndex - 1)
        val t = position - lower
        return phaseByTime[lower] + (phaseByTime[lower + 1] - phaseByTime[lower]) * t
    }
}

/**
 * Builds the physio's skeleton from the same reps §6.9 built the angle reference from, or null when
 * fewer than [Params.MIN_SURVIVING_REPS] reps could support one.
 *
 * Driven by the same [phaseProfile] and the same [interpolateOntoBins] as the features, so the pose
 * at bin `p` is the pose the z-scores at bin `p` are about. A skeleton resampled by any other rule
 * drifts out of step with them, and the disagreement is invisible - both halves look individually
 * right. §6.8.1 calls that the subtlest correctness issue in the system; it applies here unchanged.
 *
 * Landmark coordinates are filtered by §6.3.2's zero-phase Butterworth at the same cutoff as the
 * features, for the same reason: [SkeletonReference.spread] is meant to be the physio's rep-to-rep
 * variation, as sigma is, and unfiltered it would mostly be MediaPipe's frame-to-frame jitter.
 *
 * @param bodyFrames one per entry in [captured], as the pipeline already computed them.
 * @param dominantTrace the filtered dominant-feature trace phase is derived from, frame-aligned
 *   with [captured].
 */
fun buildSkeletonReference(
    captured: List<CapturedFrame>,
    bodyFrames: List<BodyFrameResult>,
    segmentation: SegmentationResult,
    dominantTrace: List<Float>,
    sampleRateHz: Double,
    cutoffHz: Double,
): SkeletonReference? {
    if (captured.size != bodyFrames.size || captured.size != dominantTrace.size) return null
    if (!(sampleRateHz > 0.0) || !(cutoffHz > 0.0) || cutoffHz >= sampleRateHz / 2.0) return null

    val slots = GHOST_LANDMARKS.size
    val trace = dominantTrace.map(Float::toDouble)

    /** Per rep: `[channel][bin]`, channel = slot * 3 + axis. */
    val repPositions = mutableListOf<List<List<Float>>>()
    /** Per rep: `[slot][bin]`. */
    val repVisibility = mutableListOf<List<List<Float>>>()
    val repFresh = mutableListOf<List<Boolean>>()
    /** Per rep: phase at evenly spaced instants, each rep's own time axis normalised to [0, 1]. */
    val repPhaseByTime = mutableListOf<List<Float>>()
    val repDurations = mutableListOf<Double>()

    for (segment in segmentation.usable) {
        if (segment.endIndex > captured.lastIndex) continue
        val extremum = extremumInside(segmentation, segment) ?: continue
        val profile = phaseProfile(trace, segment.startIndex, extremum, segment.endIndex) ?: continue
        val window = segment.startIndex..segment.endIndex

        // A rep needs a pose on every frame. Interpolating across a hole would invent the movement
        // through it, which is the one thing a picture of the physio must never do - §6.6's rule
        // that the unseen part is the part we cannot judge. The rep is dropped, not repaired.
        val poses = window.map { index -> bodyFrames[index].frame?.let { normalisedPose(captured[index].frame, it) } }
        if (poses.any { it == null }) continue

        repPositions += List(slots * 3) { channel ->
            val slot = channel / 3
            val raw = poses.map { pose ->
                val point = pose!!.points[slot]
                when (channel % 3) {
                    0 -> point.x
                    1 -> point.y
                    else -> point.z
                }
            }
            interpolateOntoBins(profile, butterworthFiltFilt(raw, sampleRateHz, cutoffHz))
        }
        repVisibility += List(slots) { slot ->
            val id = GHOST_LANDMARKS[slot]
            interpolateOntoBins(profile, window.map { captured[it].frame.visibility[id] })
        }
        // A bin is fresh if any frame feeding it was. Interpolation mixes the two frames either
        // side, so any non-zero weight from a fresh frame means a measured rotation contributed.
        repFresh += interpolateOntoBins(
            profile,
            window.map { if (bodyFrames[it].status == BodyFrameStatus.FRESH) 1f else 0f },
        ).map { it > 0f }
        // Frames arrive at a fixed rate, so frame index is time. Each rep is normalised onto its
        // own [0, 1] *before* the median: reps of different lengths compared frame for frame would
        // put one rep's pause against another's descent.
        repPhaseByTime += resampleOverTime(profile, Params.PHASE_BINS)
        repDurations += segment.lengthSamples / sampleRateHz
    }

    if (repPositions.size < Params.MIN_SURVIVING_REPS) return null

    val bins = Params.PHASE_BINS
    val positions = ArrayList<Float>(bins * slots * 3)
    val spread = ArrayList<Float>(bins * slots)
    val visibility = ArrayList<Float>(bins * slots)

    for (bin in 0 until bins) {
        for (slot in 0 until slots) {
            // Median, not mean, for §6.9.1's reason: with five to seven reps a single atypical rep
            // still moves a mean noticeably.
            val centre = Vec3(
                medianAt(repPositions, slot * 3, bin),
                medianAt(repPositions, slot * 3 + 1, bin),
                medianAt(repPositions, slot * 3 + 2, bin),
            )
            positions += centre.x
            positions += centre.y
            positions += centre.z

            // A distance, not three per-axis deviations. A landmark two centimetres out is two
            // centimetres out whichever way it went, and per-axis numbers would have to be
            // recombined at every read to mean anything. MAD-scaled, like §6.9's sigma.
            val distances = repPositions.map { rep ->
                Vec3(
                    rep[slot * 3][bin] - centre.x,
                    rep[slot * 3 + 1][bin] - centre.y,
                    rep[slot * 3 + 2][bin] - centre.z,
                ).length().toDouble()
            }
            spread += (Params.MAD_TO_SIGMA * median(distances)).toFloat()

            // Minimum, not mean. One rep where the joint was hidden is a reason to doubt the
            // skeleton there, and a mean averages that doubt away.
            visibility += repVisibility.minOf { it[slot][bin] }
        }
    }

    return SkeletonReference(
        landmarks = GHOST_LANDMARKS,
        positions = positions,
        spread = spread,
        visibility = visibility,
        fresh = List(bins) { bin -> repFresh.any { it[bin] } },
        repCount = repPositions.size,
        timing = PhaseTiming(
            phaseByTime = List(Params.PHASE_BINS) { sample ->
                median(repPhaseByTime.map { it[sample].toDouble() }).toFloat()
            },
            repDurationSeconds = median(repDurations),
        ),
    )
}

/**
 * 10.5. The phase of every frame of the authoring recording, by the same [phaseProfile] the
 * reference was built with - or null for a frame outside every usable rep.
 *
 * What the sign-check screen replays the ghost against. It asks for the ghost at exactly the phase
 * the physio was at, so on their own recording any disagreement is placement, never timing.
 *
 * Adjacent reps share their boundary frame - one ends where the next starts. It takes the later
 * rep's phase, 0: the start of what comes next. Its other reading, 100, is the same rest pose, so
 * the ghost drawn is the same either way; the rule only has to be one rule.
 */
fun replayPhases(segmentation: SegmentationResult, dominantTrace: List<Float>): List<Double?> {
    val phases = MutableList<Double?>(dominantTrace.size) { null }
    val trace = dominantTrace.map(Float::toDouble)
    for (segment in segmentation.usable) {
        if (segment.endIndex > trace.lastIndex) continue
        val extremum = extremumInside(segmentation, segment) ?: continue
        val profile = phaseProfile(trace, segment.startIndex, extremum, segment.endIndex) ?: continue
        profile.forEachIndexed { offset, phase -> phases[segment.startIndex + offset] = phase }
    }
    return phases
}

/**
 * [values] resampled onto [samples] evenly spaced points from its first element to its last, by
 * linear interpolation. With one value per frame at a fixed rate, that is evenly spaced in time.
 */
internal fun resampleOverTime(values: List<Double>, samples: Int): List<Float> {
    require(samples >= 2 && values.isNotEmpty())
    if (values.size == 1) return List(samples) { values[0].toFloat() }
    return List(samples) { sample ->
        val position = sample.toDouble() / (samples - 1) * values.lastIndex
        val lower = position.toInt().coerceAtMost(values.lastIndex - 1)
        val t = position - lower
        (values[lower] + (values[lower + 1] - values[lower]) * t).toFloat()
    }
}

/**
 * One line for the device log, which is what 10.2's gate reads: spread should be small on the
 * joints the physio controlled and large on the ones they did not. A reference where every joint
 * scatters alike means the builder is measuring noise.
 *
 * Per landmark: the median spread across bins, its maximum, and the lowest visibility anywhere.
 */
fun SkeletonReference.diagnostic(): String {
    val perLandmark = landmarks.indices.joinToString(" ") { slot ->
        val spreads = (0 until binCount).map { spreadAt(it, slot).toDouble() }
        val lowestVisibility = (0 until binCount).minOf { visibilityAt(it, slot) }
        "%d:%.3f/%.3f/v%.2f".format(landmarks[slot], median(spreads), spreads.max(), lowestVisibility)
    }
    return "skeleton bins=%d reps=%d fresh=%d/%d rep=%.2fs endHold=%.2f spread(median/max/minVis) %s".format(
        binCount, repCount, fresh.count { it }, binCount,
        timing.repDurationSeconds, timing.endRangeShare(), perLandmark,
    )
}

/**
 * Share of the rep's duration spent within [halfWidth] phase of the turnaround (p = 50).
 *
 * 10.3's device gate in one number: a deliberate two-second pause at end range must show up here as
 * a large share. A profile that climbs smoothly through the pause scores near the share a pause-free
 * rep would, which means the time axis is not being measured.
 */
fun PhaseTiming.endRangeShare(halfWidth: Double = 3.0): Double =
    phaseByTime.count { abs(it - 50.0) <= halfWidth }.toDouble() / phaseByTime.size

private fun medianAt(reps: List<List<List<Float>>>, channel: Int, bin: Int): Float =
    median(reps.map { it[channel][bin].toDouble() }).toFloat()
