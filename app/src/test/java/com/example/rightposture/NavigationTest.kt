package com.example.rightposture

import com.example.rightposture.nav.NavState
import com.example.rightposture.nav.Role
import com.example.rightposture.nav.Screen
import com.example.rightposture.nav.home
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 7.2's gate: both roles reach their core flow, and the back stack behaves.
 *
 * These run in the JVM suite rather than on a device, which is the reason the navigation state is a
 * plain immutable stack rather than Navigation Compose - a back-stack bug is much cheaper to find
 * here than in an instrumented run.
 */
class NavigationTest {

    @Test
    fun `each role reaches its own core flow`() {
        // §7.2's gate, stated directly.
        assertEquals(Screen.PhysioActivity, Role.PHYSIO.home())
        assertEquals(Screen.PatientToday, Role.PATIENT.home())
    }

    @Test
    fun `a stack is never empty`() {
        val failure = runCatching { NavState(emptyList()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `back pops one entry at a time`() {
        val state = NavState.startingAt(Screen.PhysioActivity).navigateTo(Screen.Authoring())

        assertTrue(state.canGoBack)
        assertEquals(Screen.PhysioActivity, state.back()!!.current)
    }

    @Test
    fun `back from the root asks the app to exit rather than emptying the stack`() {
        val root = NavState.startingAt(Screen.PatientToday)

        assertFalse(root.canGoBack)
        // Null is the signal to let the system handle it. Returning an empty stack instead would
        // leave nothing to render.
        assertNull(root.back())
    }

    @Test
    fun `navigating to the screen already on top does nothing`() {
        // A double tap must not push a duplicate: the back press afterwards would look broken,
        // popping to a screen identical to the one already showing.
        val state = NavState.startingAt(Screen.PhysioActivity).navigateTo(Screen.Authoring())

        assertEquals(state, state.navigateTo(Screen.Authoring()))
        assertEquals(2, state.navigateTo(Screen.Authoring()).stack.size)
    }

    @Test
    fun `changing role clears the other role's history`() {
        // A physio's authoring screen has no meaning in a patient's back stack, and leaving it
        // there would let a back press cross between roles.
        val physio = NavState.startingAt(Screen.PhysioActivity)
            .navigateTo(Screen.Authoring())
            .navigateTo(Screen.SessionDetail("s1"))

        val patient = physio.resetTo(Role.PATIENT.home())

        assertEquals(listOf(Screen.PatientToday), patient.stack)
        assertFalse(patient.canGoBack)
    }

    @Test
    fun `finishing a set returns home without growing the stack`() {
        // A patient doing five exercises in a row must not need five back presses to leave.
        var state = NavState.startingAt(Screen.PatientToday)
        repeat(5) { index ->
            state = state.navigateTo(Screen.PatientExercise("rp_knee_extension", index + 1))
            state = state.popTo(Screen.PatientToday)
        }

        assertEquals(listOf(Screen.PatientToday), state.stack)
        assertFalse(state.canGoBack)
    }

    @Test
    fun `popping to a screen that was never visited resets rather than throwing`() {
        val state = NavState.startingAt(Screen.Authoring())

        assertEquals(listOf(Screen.PhysioActivity), state.popTo(Screen.PhysioActivity).stack)
    }

    @Test
    fun `a session report is addressed by its session, so two reports are different screens`() {
        // Data class identity, relied on by popTo and the duplicate-push guard above.
        val state = NavState.startingAt(Screen.PhysioActivity)
            .navigateTo(Screen.SessionDetail("s1"))
            .navigateTo(Screen.SessionDetail("s2"))

        assertEquals(3, state.stack.size)
        assertEquals(Screen.SessionDetail("s2"), state.current)
        assertEquals(Screen.SessionDetail("s1"), state.back()!!.current)
    }

    @Test
    fun `analytics is reachable from both roles and returns to the caller`() {
        // §14.1 and §14.2 both promise progression curves, and it is one screen serving both, so
        // the back stack has to land each role where it started.
        val physio = NavState.startingAt(Screen.PhysioActivity).navigateTo(Screen.Progress(null))
        val patient = NavState.startingAt(Screen.PatientToday).navigateTo(Screen.Progress(null))

        assertEquals(Screen.PhysioActivity, physio.back()!!.current)
        assertEquals(Screen.PatientToday, patient.back()!!.current)
    }

    @Test
    fun `analytics is addressed by its exercise, so the picker and a chart are different screens`() {
        val state = NavState.startingAt(Screen.PatientToday)
            .navigateTo(Screen.Progress(null))
            .navigateTo(Screen.Progress("rp_heel_slide"))

        assertEquals(3, state.stack.size)
        assertEquals(Screen.Progress("rp_heel_slide"), state.current)
        // Back from a chart lands on the picker, not on Today.
        assertEquals(Screen.Progress(null), state.back()!!.current)
    }

    @Test
    fun `two exercises are two analytics screens`() {
        val state = NavState.startingAt(Screen.PhysioActivity)
            .navigateTo(Screen.Progress("a"))
            .navigateTo(Screen.Progress("b"))

        assertEquals(Screen.Progress("b"), state.current)
        assertEquals(Screen.Progress("a"), state.back()!!.current)
    }

    @Test
    fun `an exercise is addressed by id and version`() {
        // §9.6: version 1 and version 2 of one exercise are different references, so they are
        // different destinations even though they share a name.
        val v1 = Screen.PatientExercise("rp_wall_sit", 1)
        val v2 = Screen.PatientExercise("rp_wall_sit", 2)

        assertEquals(2, NavState.startingAt(v1).navigateTo(v2).stack.size)
    }
}
