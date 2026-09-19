package com.example.rightposture

import com.example.rightposture.camera.CameraPermissionState
import com.example.rightposture.camera.cameraPermissionState
import com.example.rightposture.camera.canRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionTest {
    @Test
    fun firstLaunchAsksWithoutExplaining() {
        val state = cameraPermissionState(granted = false, askedThisSession = false, shouldShowRationale = false)

        assertEquals(CameraPermissionState.NEEDS_REQUEST, state)
        assertTrue(canRequest(state))
    }

    @Test
    fun denyingOnceEarnsAnExplanation() {
        val state = cameraPermissionState(granted = false, askedThisSession = true, shouldShowRationale = true)

        assertEquals(CameraPermissionState.NEEDS_RATIONALE, state)
        assertTrue(canRequest(state))
    }

    @Test
    fun deniedWithNoRationaleAfterAskingIsBlocked() {
        // Android reports no rationale both before the first ask and after a permanent denial.
        // Only "have we already asked" separates them, so asking again would be a silent no-op.
        val state = cameraPermissionState(granted = false, askedThisSession = true, shouldShowRationale = false)

        assertEquals(CameraPermissionState.BLOCKED, state)
        assertFalse(canRequest(state))
    }

    @Test
    fun grantedWinsOverEveryOtherSignal() {
        listOf(false, true).forEach { asked ->
            listOf(false, true).forEach { rationale ->
                assertEquals(
                    CameraPermissionState.GRANTED,
                    cameraPermissionState(granted = true, askedThisSession = asked, shouldShowRationale = rationale),
                )
            }
        }
    }

    @Test
    fun rationaleBeforeAskingStillExplainsRatherThanBlocking() {
        // A permission denied in an earlier session comes back with rationale true and asked false.
        val state = cameraPermissionState(granted = false, askedThisSession = false, shouldShowRationale = true)

        assertEquals(CameraPermissionState.NEEDS_RATIONALE, state)
        assertTrue(canRequest(state))
    }

    @Test
    fun onlyBlockedRefusesToRequest() {
        CameraPermissionState.entries.forEach { state ->
            val expected = state == CameraPermissionState.NEEDS_REQUEST ||
                state == CameraPermissionState.NEEDS_RATIONALE
            assertEquals("$state", expected, canRequest(state))
        }
    }
}
