package com.example.rightposture

import com.example.rightposture.pose.POSE_BACKEND_ORDER
import com.example.rightposture.pose.PoseBackend
import com.example.rightposture.pose.selectPoseBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoseBackendTest {
    @Test
    fun runtimeOrderIsNpuThenGpuThenCpu() {
        assertEquals(listOf(PoseBackend.NPU, PoseBackend.GPU, PoseBackend.CPU), POSE_BACKEND_ORDER)
        assertEquals(PoseBackend.NPU, selectPoseBackend(PoseBackend.entries.toSet()))
        assertEquals(PoseBackend.GPU, selectPoseBackend(setOf(PoseBackend.GPU, PoseBackend.CPU)))
        assertEquals(PoseBackend.CPU, selectPoseBackend(setOf(PoseBackend.CPU)))
        assertNull(selectPoseBackend(emptySet()))
    }
}
