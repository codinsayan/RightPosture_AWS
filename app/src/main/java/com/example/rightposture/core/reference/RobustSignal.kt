package com.example.rightposture.core.reference

import com.example.rightposture.core.Params
import kotlin.math.abs

/** Replaces isolated landmark jumps; sustained movement remains untouched. Window is symmetric. */
internal fun rejectIsolatedOutliers(values: List<Float>): List<Float> {
    if (values.size < 3) return values
    val radius = Params.OFFLINE_OUTLIER_RADIUS_FRAMES
    return values.mapIndexed { index, value ->
        val window = values.subList(
            (index - radius).coerceAtLeast(0),
            (index + radius + 1).coerceAtMost(values.size),
        )
        val centre = median(window.map(Float::toDouble))
        val mad = median(window.map { abs(it - centre) })
        val threshold = maxOf(
            Params.OFFLINE_OUTLIER_MIN_JUMP,
            Params.OFFLINE_OUTLIER_MAD_MULTIPLIER * Params.MAD_TO_SIGMA * mad,
        )
        if (abs(value - centre) > threshold) centre.toFloat() else value
    }
}

/** Bridges brief confidence dropouts without inventing motion across a long occlusion. */
internal fun bridgeShortGaps(values: List<Float?>, maxGapSamples: Int): List<Float>? {
    if (values.isEmpty() || maxGapSamples < 0) return null
    val output = values.toMutableList()
    var index = 0
    while (index < output.size) {
        if (output[index] != null) {
            index++
            continue
        }
        val start = index
        while (index < output.size && output[index] == null) index++
        val length = index - start
        if (length > maxGapSamples) return null
        val left = output.getOrNull(start - 1)
        val right = output.getOrNull(index)
        if (left == null && right == null) return null
        repeat(length) { offset ->
            output[start + offset] = when {
                left == null -> right
                right == null -> left
                else -> {
                    val weight = (offset + 1f) / (length + 1f)
                    left + weight * (right - left)
                }
            }
        }
    }
    return output.map { it!! }
}
