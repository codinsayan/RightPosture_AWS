package com.example.rightposture.camera

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Where an image of [inputAspect] (width / height) sits when fitted, uncropped and centred, inside a
 * canvas of [canvas] size.
 *
 * One definition for every overlay drawn over MediaPipe's normalised coordinates - the live skeleton
 * and 10.5's ghost. If they ever computed this separately, the ghost would sit correctly on a 4:3
 * device and offset on every other, which is the hardest kind of report to act on.
 */
internal data class Letterbox(val left: Float, val top: Float, val width: Float, val height: Float) {
    /** A MediaPipe normalised image point (0-1, y down) in canvas pixels. */
    fun toCanvas(x: Float, y: Float) = Offset(left + x * width, top + y * height)
}

internal fun letterboxOf(canvas: Size, inputAspect: Float): Letterbox {
    val viewAspect = canvas.width / canvas.height
    val width = if (viewAspect > inputAspect) canvas.height * inputAspect else canvas.width
    val height = if (viewAspect > inputAspect) canvas.height else canvas.width / inputAspect
    return Letterbox((canvas.width - width) / 2f, (canvas.height - height) / 2f, width, height)
}
