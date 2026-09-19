package com.example.rightposture.pose

enum class PoseBackend { NPU, GPU, CPU }

val POSE_BACKEND_ORDER = listOf(PoseBackend.NPU, PoseBackend.GPU, PoseBackend.CPU)

fun selectPoseBackend(available: Set<PoseBackend>) = POSE_BACKEND_ORDER.firstOrNull(available::contains)
