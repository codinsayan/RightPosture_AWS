package com.example.rightposture.pose

import com.example.rightposture.core.pose.Vec3
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

enum class Axis { X, Y, Z }

data class AxisDirection(val axis: Axis, val sign: Int, val delta: Float)

data class SemanticAxis(val axis: Axis, val sign: Int) {
    init {
        require(sign == -1 || sign == 1)
    }

    fun component(value: Vec3): Float = sign * when (axis) {
        Axis.X -> value.x
        Axis.Y -> value.y
        Axis.Z -> value.z
    }
}

data class AxisResult(
    val up: AxisDirection?,
    val anatomicalRight: AxisDirection?,
    val towardCamera: AxisDirection?,
    val movingLabel: String,
) {
    fun displayText() = "Observed physical right arm: label=$movingLabel, up=${up.label()}, right=${anatomicalRight.label()}, toward=${towardCamera.label()}"
}

fun inferAxisResult(captures: List<AxisReading>): AxisResult? {
    if (captures.size != 6) return null
    val label = movingLabel(captures)
    if (label == "unclear") return AxisResult(null, null, null, label)
    fun arm(reading: AxisReading) = if (label == "LEFT") {
        reading.leftWristRelativeToShoulder
    } else {
        reading.wristRelativeToShoulder
    }
    return AxisResult(
        up = inferAxis(arm(captures[0]), arm(captures[1])),
        anatomicalRight = inferAxis(arm(captures[2]), arm(captures[3])),
        towardCamera = inferAxis(arm(captures[4]), arm(captures[5])),
        movingLabel = label,
    )
}

data class AxisReading(
    val wristWorld: Vec3,
    val shoulderWorld: Vec3,
    val leftWristWorld: Vec3,
    val leftShoulderWorld: Vec3,
    val wristImage: Vec3,
    val imageLandmarks: List<Vec3>,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val wristRelativeToShoulder get() = wristWorld - shoulderWorld
    val leftWristRelativeToShoulder get() = leftWristWorld - leftShoulderWorld
    val captureReady get() = listOf(11, 12, 15, 16).all { index ->
        imageLandmarks.getOrNull(index)?.let { it.x in 0f..1f && it.y in 0f..1f } == true
    }

    fun displayText(): String = String.format(
        Locale.US,
        "RIGHT wrist world (%.3f, %.3f, %.3f) · relative (%.3f, %.3f, %.3f) · image (%.3f, %.3f, %.3f)",
        wristWorld.x, wristWorld.y, wristWorld.z,
        wristRelativeToShoulder.x, wristRelativeToShoulder.y, wristRelativeToShoulder.z,
        wristImage.x, wristImage.y, wristImage.z,
    )
}

fun inferAxis(
    neutral: Vec3,
    moved: Vec3,
    minimumDelta: Float = 0.03f,
    minimumDominance: Float = 1.25f,
): AxisDirection? {
    val delta = moved - neutral
    val values = listOf(Axis.X to delta.x, Axis.Y to delta.y, Axis.Z to delta.z)
        .sortedByDescending { abs(it.second) }
    val (axis, value) = values[0]
    if (abs(value) < minimumDelta) return null
    if (abs(value) < abs(values[1].second) * minimumDominance) return null
    return AxisDirection(axis, if (value > 0f) 1 else -1, value)
}

fun movingLabel(captures: List<AxisReading>): String {
    if (captures.size != 6) return "unclear"
    val pairs = listOf(0 to 1, 2 to 3, 4 to 5)
    val right = pairs.sumOf { (a, b) -> magnitude(captures[b].wristRelativeToShoulder - captures[a].wristRelativeToShoulder).toDouble() }
    val left = pairs.sumOf { (a, b) -> magnitude(captures[b].leftWristRelativeToShoulder - captures[a].leftWristRelativeToShoulder).toDouble() }
    return when {
        right > left * 1.5 -> "RIGHT"
        left > right * 1.5 -> "LEFT"
        else -> "unclear"
    }
}

private fun magnitude(value: Vec3) = sqrt(value.x * value.x + value.y * value.y + value.z * value.z)

private fun AxisDirection?.label() = this?.let {
    String.format(Locale.US, "%s%s (Δ%.3f m)", it.axis, if (it.sign > 0) "+" else "-", it.delta)
} ?: "unclear"

// Canonical convention verified on the target phone with both cameras.
// Front-camera input must first be X-unmirrored and LEFT/RIGHT labels swapped.
object AxisConvention {
    const val VERIFIED = true
    const val FRONT_INPUT_MIRRORED = true
    const val REAR_PHYSICAL_RIGHT_LABEL = "RIGHT"
    const val FRONT_PHYSICAL_RIGHT_LABEL = "LEFT"

    val UP = SemanticAxis(Axis.Y, -1)
    val ANATOMICAL_RIGHT = SemanticAxis(Axis.X, -1)
    val TOWARD_CAMERA = SemanticAxis(Axis.Z, -1)
}
