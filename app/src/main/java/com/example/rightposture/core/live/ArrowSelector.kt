package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import com.example.rightposture.core.Params
import com.example.rightposture.core.pose.Side
import com.example.rightposture.core.reference.WatchList
import kotlin.math.abs

/**
 * Picks the one feature §6.16's arrow addresses, and then **holds it still**.
 *
 * Choosing `argmax |z|` fresh on every frame - which is what this replaced - fails in three ways at
 * once, all of them visible on a phone:
 *
 * 1. It ignores what the exercise is about. Every watch-listed feature competes on raw z, so a leg
 *    that happens to be off-reference wins during an arm raise. §6.15.2 already solved this for
 *    speech by ranking on `|z| x priority`, where priority defaults to the watch-list weight; the
 *    arrow uses the same ranking, because it is answering the same question.
 * 2. It has no hysteresis, so a feature hovering at the threshold flickers on and off at frame rate.
 *    §6.14.1 says of the equivalent problem in fault detection that the dwell "is the difference
 *    between a demo that feels magical and one that feels broken".
 * 3. It has no allegiance, so two features with similar scores trade the arrow back and forth.
 *
 * So: enter on `Z_ON` after a dwell, leave on `Z_OFF` after a longer one, and only switch to a
 * different feature when the challenger is clearly better and stays better.
 */
class ArrowSelector(watchList: WatchList, private val activeSide: Side) {

    private val weights = watchList.features.associate { it.id to it.weight }

    private var held: FeatureId? = null
    private var belowExitSinceNs: Long? = null
    private var challenger: FeatureId? = null
    private var challengerSinceNs: Long? = null

    fun reset() {
        held = null
        belowExitSinceNs = null
        challenger = null
        challengerSinceNs = null
    }

    /**
     * @param midRep false between reps. Phase is pinned near the rep start then, so every z is
     *   measured against a reference point the patient is not trying to be at yet - an arrow drawn
     *   from it would be confidently wrong. §6.16's arrow describes a fault *during* a movement.
     */
    fun select(
        zScores: Map<FeatureId, FeatureZ>,
        midRep: Boolean,
        occluded: Boolean,
        timestampNs: Long,
    ): FeatureId? {
        // §6.6: a cue chosen off a half-seen body is worse than silence, and the same holds for a
        // picture drawn on one.
        if (!midRep || occluded) {
            reset()
            return null
        }

        fun score(id: FeatureId): Double {
            val z = zScores[id] ?: return 0.0
            if (!z.z.isFinite()) return 0.0
            if (arrowLandmarks(id, activeSide) == null) return 0.0
            return abs(z.z) * (weights[id] ?: 0.0)
        }

        val candidates = weights.keys.filter { score(it) > 0.0 }
        val best = candidates.maxByOrNull { score(it) }

        val current = held
        if (current != null) {
            val currentZ = zScores[current]?.z?.let { abs(it) } ?: 0.0
            // Leave on Z_OFF, not on Z_ON. Dropping at the same threshold it entered on is what
            // makes a feature sitting on the line strobe.
            if (currentZ < Params.Z_OFF) {
                val since = belowExitSinceNs ?: timestampNs.also { belowExitSinceNs = it }
                if (seconds(timestampNs - since) >= Params.FAULT_EXIT_DWELL_SECONDS) {
                    reset()
                    return null
                }
            } else {
                belowExitSinceNs = null
            }

            // A challenger has to be decisively better, and stay better, before the arrow moves.
            // Otherwise two comparable features hand it back and forth every few frames.
            if (best != null && best != current &&
                score(best) > score(current) * Params.ARROW_SWITCH_MARGIN &&
                (zScores[best]?.z?.let { abs(it) } ?: 0.0) >= Params.Z_ON
            ) {
                if (challenger != best) {
                    challenger = best
                    challengerSinceNs = timestampNs
                }
                val since = challengerSinceNs ?: timestampNs
                if (seconds(timestampNs - since) >= Params.ARROW_SWITCH_DWELL_SECONDS) {
                    held = best
                    belowExitSinceNs = null
                    challenger = null
                    challengerSinceNs = null
                }
            } else {
                challenger = null
                challengerSinceNs = null
            }
            return held
        }

        // Nothing held. Enter only on a real fault-sized error, sustained.
        if (best == null) {
            challenger = null
            challengerSinceNs = null
            return null
        }
        val bestZ = zScores[best]?.z?.let { abs(it) } ?: 0.0
        if (bestZ < Params.Z_ON) {
            challenger = null
            challengerSinceNs = null
            return null
        }
        if (challenger != best) {
            challenger = best
            challengerSinceNs = timestampNs
        }
        val since = challengerSinceNs ?: timestampNs
        if (seconds(timestampNs - since) >= Params.FAULT_ENTRY_DWELL_SECONDS) {
            held = best
            belowExitSinceNs = null
            challenger = null
            challengerSinceNs = null
        }
        return held
    }

    private fun seconds(nanos: Long) = nanos / 1_000_000_000.0
}
