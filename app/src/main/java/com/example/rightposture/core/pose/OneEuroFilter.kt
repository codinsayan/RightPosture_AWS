package com.example.rightposture.core.pose

import com.example.rightposture.core.Params
import kotlin.math.PI
import kotlin.math.abs

class OneEuroFilter(
    private val minCutoffHz: Double = Params.FC_MIN_HZ,
    private val beta: Double = Params.BETA,
    private val derivativeCutoffHz: Double = Params.D_CUTOFF_HZ,
) {
    private var previousValue = 0.0
    private var previousDerivative = 0.0
    private var initialised = false

    init {
        require(minCutoffHz > 0.0)
        require(beta >= 0.0)
        require(derivativeCutoffHz > 0.0)
    }

    fun filter(value: Float, dtSeconds: Double): Float {
        require(dtSeconds > 0.0)
        if (!initialised) {
            previousValue = value.toDouble()
            previousDerivative = 0.0
            initialised = true
            return value
        }

        val derivative = (value - previousValue) / dtSeconds
        val derivativeAlpha = alpha(derivativeCutoffHz, dtSeconds)
        val filteredDerivative =
            derivativeAlpha * derivative + (1.0 - derivativeAlpha) * previousDerivative
        val cutoff = minCutoffHz + beta * abs(filteredDerivative)
        val valueAlpha = alpha(cutoff, dtSeconds)
        val filteredValue = valueAlpha * value + (1.0 - valueAlpha) * previousValue

        previousValue = filteredValue
        previousDerivative = filteredDerivative
        return filteredValue.toFloat()
    }

    fun reset() {
        initialised = false
        previousValue = 0.0
        previousDerivative = 0.0
    }

    private fun alpha(cutoffHz: Double, dtSeconds: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoffHz)
        return 1.0 / (1.0 + tau / dtSeconds)
    }
}

class OneEuroLandmarkFilter(
    minCutoffHz: Double = Params.FC_MIN_HZ,
    beta: Double = Params.BETA,
    derivativeCutoffHz: Double = Params.D_CUTOFF_HZ,
) {
    private val channels = Array(POSE_LANDMARK_COUNT * 3) {
        OneEuroFilter(minCutoffHz, beta, derivativeCutoffHz)
    }
    private var previousTimestampNs: Long? = null

    fun filter(
        frame: LandmarkFrame,
        timestampNs: Long,
        personRedetected: Boolean = false,
    ): LandmarkFrame {
        val previous = previousTimestampNs
        val timing = previous?.let { frameTiming(it, timestampNs) }
        if (personRedetected || timing == FrameTiming.Gap) reset()
        previousTimestampNs = timestampNs

        val dtSeconds = (timing as? FrameTiming.Continuous)?.dtSeconds
        var channel = 0
        val filteredWorld = frame.worldLandmarks.map { point ->
            Vec3(
                channels[channel++].filter(point.x, dtSeconds ?: 1.0),
                channels[channel++].filter(point.y, dtSeconds ?: 1.0),
                channels[channel++].filter(point.z, dtSeconds ?: 1.0),
            )
        }
        return frame.copy(worldLandmarks = filteredWorld)
    }

    fun reset() {
        channels.forEach(OneEuroFilter::reset)
        previousTimestampNs = null
    }
}
