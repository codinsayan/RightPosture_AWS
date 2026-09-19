package com.example.rightposture

import com.example.rightposture.speech.TtsState
import com.example.rightposture.speech.ttsSetupMessage
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §12.4's failure states. A missing engine and missing voice data need different instructions, and
 * neither may be reported as "working but quiet".
 */
class TtsStateTest {

    @Test
    fun `a working engine has nothing to say about setup`() {
        assertNull(ttsSetupMessage(TtsState.READY))
        assertNull(ttsSetupMessage(TtsState.INITIALISING))
    }

    @Test
    fun `a missing engine names the install, not the voice data`() {
        val message = ttsSetupMessage(TtsState.ENGINE_MISSING)
        assertNotNull(message)
        assertTrue(message!!.contains("Play"))
    }

    @Test
    fun `missing voice data names the settings screen, not the install`() {
        val message = ttsSetupMessage(TtsState.LANGUAGE_MISSING)
        assertNotNull(message)
        // Language-neutral now that the app speaks more than one: the message must not name a
        // language the patient did not choose.
        assertTrue(message!!.contains("text-to-speech settings"))
        assertTrue(message.contains("language you picked"))
    }

    @Test
    fun `every failure state produces a message and every working state does not`() {
        TtsState.entries.forEach { state ->
            val message = ttsSetupMessage(state)
            val working = state == TtsState.READY || state == TtsState.INITIALISING
            assertTrue("$state", working == (message == null))
        }
    }
}
