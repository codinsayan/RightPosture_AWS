package com.example.rightposture.core.pose

import com.example.rightposture.core.Params
import kotlin.math.abs
import kotlin.math.atan2

/**
 * §6.5.1 unsigned three-point joint angle at joint [b] with neighbours [a] and [c].
 *
 * Uses the atan2 form, never `acos(v1·v2 / (|v1||v2|))`. acos loses precision catastrophically as
 * its argument approaches ±1, which is full extension — exactly where knee-extension exercises
 * live. Returns [0, 180]; null means invalid (§6.22).
 */
fun jointAngleDegrees(a: Vec3, b: Vec3, c: Vec3): Float? = angleBetweenDegrees(a - b, c - b)

/** Unsigned angle between two free vectors, same atan2 form and guards as [jointAngleDegrees]. */
fun angleBetweenDegrees(v1: Vec3, v2: Vec3): Float? {
    if (v1.length() < Params.MIN_VECTOR_LENGTH || v2.length() < Params.MIN_VECTOR_LENGTH) return null
    val degrees = Math.toDegrees(
        atan2((v1 cross v2).length().toDouble(), (v1 dot v2).toDouble()),
    ).toFloat()
    return degrees.takeIf { it.isFinite() }
}

/**
 * §6.5.2 signed angle from [v1] to [v2] in the plane whose normal is [normal].
 *
 * Anatomical deviations are small, so |θ| > 90° is treated as a wrap rather than a real reading
 * and returns null (§6.22).
 */
fun signedPlanarAngleDegrees(v1: Vec3, v2: Vec3, normal: Vec3): Float? {
    val n = normal.length().let { if (it < Params.MIN_VECTOR_LENGTH) return null else normal * (1f / it) }
    val v1p = v1 - n * (v1 dot n)
    val v2p = v2 - n * (v2 dot n)
    if (v1p.length() < Params.MIN_VECTOR_LENGTH || v2p.length() < Params.MIN_VECTOR_LENGTH) return null
    val degrees = Math.toDegrees(
        atan2(((v1p cross v2p) dot n).toDouble(), (v1p dot v2p).toDouble()),
    ).toFloat()
    if (!degrees.isFinite() || abs(degrees) > Params.SIGNED_ANGLE_MAX_DEGREES) return null
    return degrees
}
