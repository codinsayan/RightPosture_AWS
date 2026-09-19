package com.example.rightposture.core.pose

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

data class SyntheticPoseConfig(
    val repCount: Int = 8,
    val samplesPerRep: Int = 60,
    val minimumKneeAngleDegrees: Float = 80f,
    val maximumKneeAngleDegrees: Float = 170f,
    val sampleRateHz: Double = 30.0,
    val noiseStandardDeviationMetres: Float = 0f,
    val frameDropoutProbability: Double = 0.0,
    val seed: Long = 0,
) {
    init {
        require(repCount > 0)
        require(samplesPerRep >= 4 && samplesPerRep % 2 == 0)
        require(minimumKneeAngleDegrees in 0f..<maximumKneeAngleDegrees)
        require(maximumKneeAngleDegrees <= 180f)
        require(sampleRateHz > 0.0 && sampleRateHz.isFinite())
        require(noiseStandardDeviationMetres >= 0f && noiseStandardDeviationMetres.isFinite())
        require(frameDropoutProbability in 0.0..1.0)
    }
}

data class TimedLandmarkFrame(val timestampNs: Long, val frame: LandmarkFrame)

/** Deterministic right-knee repetitions for camera-free authoring tests. */
fun syntheticKneeRepetitions(config: SyntheticPoseConfig = SyntheticPoseConfig()): List<TimedLandmarkFrame> {
    val random = Random(config.seed)
    val sampleCount = config.repCount * config.samplesPerRep
    return (0..sampleCount).map { sample ->
        val phase = 2.0 * PI * sample / config.samplesPerRep
        val progress = ((1.0 - cos(phase)) / 2.0).toFloat()
        val kneeAngle = config.minimumKneeAngleDegrees +
            (config.maximumKneeAngleDegrees - config.minimumKneeAngleDegrees) * progress
        val dropped = random.nextDouble() < config.frameDropoutProbability
        TimedLandmarkFrame(
            timestampNs = (sample * 1_000_000_000.0 / config.sampleRateHz).roundToLong(),
            frame = pose(kneeAngle, progress, dropped, config.noiseStandardDeviationMetres, random),
        )
    }
}

/**
 * A movement made of **two** steps: the knee sweeps and the elbow bends with it.
 *
 * The single-joint generator above cannot express the thing that actually goes wrong with a
 * combination of steps, because there is no second step to leave out. Set [elbowSwingDegrees] to 0
 * to get a patient who performs the dominant sweep and omits the rest of the exercise.
 *
 * A beat is held at both extrema. §6.12.3 completes a rep on `phi < 0.15` **and** a stalled
 * velocity, and an unbroken sinusoid is inside the stall band for well under one frame, so without
 * a pause at each end reps pass undetected - the finding recorded in the v5.1 changelog entry.
 */
fun syntheticTwoStepRepetitions(
    repCount: Int = 8,
    samplesPerRep: Int = 60,
    beatFrames: Int = 15,
    kneeSwingDegrees: Float = 90f,
    elbowSwingDegrees: Float = 70f,
    sampleRateHz: Double = 30.0,
): List<TimedLandmarkFrame> {
    val frames = mutableListOf<LandmarkFrame>()
    repeat(repCount) {
        (0 until samplesPerRep).forEach { sample ->
            val progress = ((1.0 - cos(2.0 * PI * sample / samplesPerRep)) / 2.0).toFloat()
            val frame = twoStepPose(
                kneeAngleDegrees = KNEE_REST_DEGREES - kneeSwingDegrees * progress,
                elbowAngleDegrees = ELBOW_REST_DEGREES - elbowSwingDegrees * progress,
            )
            frames += frame
            if (sample == 0 || sample == samplesPerRep / 2) repeat(beatFrames) { frames += frame }
        }
    }
    frames += List(beatFrames) { twoStepPose(KNEE_REST_DEGREES, ELBOW_REST_DEGREES) }
    val step = (1_000_000_000.0 / sampleRateHz).toLong()
    return frames.mapIndexed { index, frame -> TimedLandmarkFrame(index * step, frame) }
}

/**
 * The patient who goes straight to the end pose, holds, and comes back.
 *
 * Not a teleport: [moveFrames] of 12 at 30 Hz is 0.4 s each way, which is how fast a person
 * actually moves when they are cutting a corner. A teleport is the one shortcut a body cannot
 * perform, so testing against it proves nothing about a real patient.
 */
fun syntheticEndPoseDash(
    repCount: Int = 4,
    moveFrames: Int = 12,
    holdFrames: Int = 45,
    restFrames: Int = 30,
    kneeSwingDegrees: Float = 90f,
    elbowSwingDegrees: Float = 0f,
    sampleRateHz: Double = 30.0,
): List<TimedLandmarkFrame> {
    val frames = mutableListOf<LandmarkFrame>()
    fun at(progress: Float) = twoStepPose(
        kneeAngleDegrees = KNEE_REST_DEGREES - kneeSwingDegrees * progress,
        elbowAngleDegrees = ELBOW_REST_DEGREES - elbowSwingDegrees * progress,
    )
    repeat(repCount) {
        frames += List(restFrames) { at(0f) }
        (1..moveFrames).forEach { frames += at(it.toFloat() / moveFrames) }
        frames += List(holdFrames) { at(1f) }
        (1..moveFrames).forEach { frames += at(1f - it.toFloat() / moveFrames) }
    }
    frames += List(restFrames) { at(0f) }
    val step = (1_000_000_000.0 / sampleRateHz).toLong()
    return frames.mapIndexed { index, frame -> TimedLandmarkFrame(index * step, frame) }
}

private const val KNEE_REST_DEGREES = 170f
private const val ELBOW_REST_DEGREES = 175f

/** Both joints placed from an explicit angle, so a test can state the movement it means. */
private fun twoStepPose(kneeAngleDegrees: Float, elbowAngleDegrees: Float): LandmarkFrame {
    val knee = Vec3(0.15f, -0.4f, 0f)
    val kneeRadians = Math.toRadians(kneeAngleDegrees.toDouble())
    val ankle = Vec3(
        knee.x,
        knee.y + 0.4f * cos(kneeRadians).toFloat(),
        knee.z + 0.4f * sin(kneeRadians).toFloat(),
    )
    val elbow = Vec3(0.2f, 0.2f, 0f)
    val elbowRadians = Math.toRadians(elbowAngleDegrees.toDouble())
    val wrist = Vec3(
        elbow.x,
        elbow.y + 0.25f * cos(elbowRadians).toFloat(),
        elbow.z + 0.25f * sin(elbowRadians).toFloat(),
    )
    val points = mapOf(
        PoseLandmark.NOSE to Vec3(0f, 0.75f, 0f),
        PoseLandmark.LEFT_SHOULDER to Vec3(-0.2f, 0.5f, 0f),
        PoseLandmark.RIGHT_SHOULDER to Vec3(0.2f, 0.5f, 0f),
        PoseLandmark.LEFT_ELBOW to Vec3(-0.2f, 0.2f, 0f),
        PoseLandmark.RIGHT_ELBOW to elbow,
        PoseLandmark.LEFT_WRIST to Vec3(-0.2f, -0.05f, 0f),
        PoseLandmark.RIGHT_WRIST to wrist,
        PoseLandmark.LEFT_HIP to Vec3(-0.15f, 0f, 0f),
        PoseLandmark.RIGHT_HIP to Vec3(0.15f, 0f, 0f),
        PoseLandmark.LEFT_KNEE to Vec3(-0.15f, -0.4f, 0f),
        PoseLandmark.RIGHT_KNEE to knee,
        PoseLandmark.LEFT_ANKLE to Vec3(-0.15f, -0.8f, 0f),
        PoseLandmark.RIGHT_ANKLE to ankle,
        PoseLandmark.LEFT_HEEL to Vec3(-0.15f, -0.82f, -0.05f),
        PoseLandmark.RIGHT_HEEL to Vec3(ankle.x, ankle.y - 0.02f, ankle.z - 0.05f),
        PoseLandmark.LEFT_FOOT_INDEX to Vec3(-0.15f, -0.85f, 0.15f),
        PoseLandmark.RIGHT_FOOT_INDEX to Vec3(ankle.x, ankle.y - 0.05f, ankle.z + 0.15f),
    )
    val world = List(POSE_LANDMARK_COUNT) { points[it] ?: Vec3(0f, 0f, 0f) }
    return LandmarkFrame(
        worldLandmarks = world,
        imageLandmarks = world.map { Vec3(0.5f + it.x, 0.3f - it.y * 0.5f, it.z) },
        visibility = List(POSE_LANDMARK_COUNT) { 0.99f },
        presence = List(POSE_LANDMARK_COUNT) { 0.99f },
    )
}

private fun pose(
    rightKneeAngleDegrees: Float,
    movementProgress: Float,
    dropped: Boolean,
    noiseStandardDeviationMetres: Float,
    random: Random,
): LandmarkFrame {
    val knee = Vec3(0.15f, -0.4f, 0f)
    val radians = Math.toRadians(rightKneeAngleDegrees.toDouble())
    val ankle = Vec3(
        knee.x,
        knee.y + 0.4f * cos(radians).toFloat(),
        knee.z + 0.4f * sin(radians).toFloat(),
    )
    val rightElbow = Vec3(0.2f, 0.2f + 0.05f * movementProgress, 0.1f * movementProgress)
    val rightWrist = Vec3(rightElbow.x, rightElbow.y - 0.25f, rightElbow.z)
    val points = mapOf(
        PoseLandmark.NOSE to Vec3(0f, 0.75f, 0f),
        PoseLandmark.LEFT_SHOULDER to Vec3(-0.2f, 0.5f, 0f),
        PoseLandmark.RIGHT_SHOULDER to Vec3(0.2f, 0.5f, 0f),
        PoseLandmark.LEFT_ELBOW to Vec3(-0.2f, 0.2f, 0f),
        PoseLandmark.RIGHT_ELBOW to rightElbow,
        PoseLandmark.LEFT_WRIST to Vec3(-0.2f, -0.05f, 0f),
        PoseLandmark.RIGHT_WRIST to rightWrist,
        PoseLandmark.LEFT_HIP to Vec3(-0.15f, 0f, 0f),
        PoseLandmark.RIGHT_HIP to Vec3(0.15f, 0f, 0f),
        PoseLandmark.LEFT_KNEE to Vec3(-0.15f, -0.4f, 0f),
        PoseLandmark.RIGHT_KNEE to knee,
        PoseLandmark.LEFT_ANKLE to Vec3(-0.15f, -0.8f, 0f),
        PoseLandmark.RIGHT_ANKLE to ankle,
        PoseLandmark.LEFT_HEEL to Vec3(-0.15f, -0.82f, -0.05f),
        PoseLandmark.RIGHT_HEEL to Vec3(ankle.x, ankle.y - 0.02f, ankle.z - 0.05f),
        PoseLandmark.LEFT_FOOT_INDEX to Vec3(-0.15f, -0.85f, 0.15f),
        PoseLandmark.RIGHT_FOOT_INDEX to Vec3(ankle.x, ankle.y - 0.05f, ankle.z + 0.15f),
    )
    val world = List(POSE_LANDMARK_COUNT) { index ->
        val point = points[index] ?: Vec3(0f, 0f, 0f)
        if (noiseStandardDeviationMetres == 0f) point else point.noisy(random, noiseStandardDeviationMetres)
    }
    val confidence = if (dropped) 0f else 0.99f
    return LandmarkFrame(
        worldLandmarks = world,
        imageLandmarks = world.map { Vec3(0.5f + it.x, 0.3f - it.y * 0.5f, it.z) },
        visibility = List(POSE_LANDMARK_COUNT) { confidence },
        presence = List(POSE_LANDMARK_COUNT) { confidence },
    )
}

private fun Vec3.noisy(random: Random, standardDeviation: Float) = Vec3(
    x + (random.nextGaussian() * standardDeviation).toFloat(),
    y + (random.nextGaussian() * standardDeviation).toFloat(),
    z + (random.nextGaussian() * standardDeviation).toFloat(),
)
