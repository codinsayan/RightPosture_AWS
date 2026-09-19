package com.example.rightposture.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import com.example.rightposture.core.live.Speaker
import com.example.rightposture.core.live.SpeechLanguage
import java.util.Locale

/** The engine §12.4 names. Nothing here falls back to a different one. */
const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"

/**
 * What the patient needs to be told about speech, if anything.
 *
 * A missing engine and missing voice data are separate states because they need separate
 * instructions: one is an install, the other is a download inside a settings screen.
 */
enum class TtsState {
    INITIALISING,
    READY,

    /** Google's TTS engine is not installed, so §12.4's engine is unavailable. */
    ENGINE_MISSING,

    /** The engine is present but the chosen language's voice data is not. */
    LANGUAGE_MISSING,
}

/** The setup message for a state, or null when there is nothing to say. */
fun ttsSetupMessage(state: TtsState): String? = when (state) {
    TtsState.INITIALISING, TtsState.READY -> null
    TtsState.ENGINE_MISSING ->
        "Spoken cues are off. Install \"Speech Recognition & Synthesis\" by Google from Play, " +
            "then reopen RightPosture."
    TtsState.LANGUAGE_MISSING ->
        "Spoken cues are off. In Android's text-to-speech settings, install the voice data for the " +
            "language you picked, then reopen RightPosture."
}

/**
 * §12.4 speech, on Google's engine specifically.
 *
 * §12.4 pins the engine rather than taking the system default, and this does not quietly substitute
 * another one: a different engine has different voice coverage and different timing, and a cue that
 * arrives late or mispronounced during a rep is worse than a cue the patient reads instead. When
 * Google's engine or its en-IN data is absent, speech is disabled and [state] says which.
 */
class GoogleTtsSpeaker(
    context: Context,
    private var language: SpeechLanguage = SpeechLanguage.ENGLISH,
    private val onStateChange: (TtsState) -> Unit = {},
) : Speaker {

    var state: TtsState = TtsState.INITIALISING
        private set(value) {
            if (field != value) {
                field = value
                onStateChange(value)
            }
        }

    override val isReady get() = state == TtsState.READY

    private var engine: TextToSpeech? = null

    init {
        // Explicitly the Google package, per §12.4. If it is absent, init reports an error rather
        // than silently binding whatever engine the device does have.
        engine = TextToSpeech(context.applicationContext, ::onInit, GOOGLE_TTS_PACKAGE)
    }

    private fun onInit(status: Int) {
        val tts = engine
        if (status != TextToSpeech.SUCCESS || tts == null) {
            state = TtsState.ENGINE_MISSING
            return
        }
        // §12.4 sets the locale only after a successful init; before that the engine has no voice
        // list to answer against and the result is meaningless.
        if (!applyLanguage(tts, language)) {
            state = TtsState.LANGUAGE_MISSING
            return
        }
        // §12.4's warm-up. The engine allocates its voice on first use and swallows that utterance;
        // without this the first real cue - the one that matters most - is the one lost.
        tts.playSilentUtterance(WARM_UP_MILLIS, TextToSpeech.QUEUE_FLUSH, WARM_UP_UTTERANCE_ID)
        state = TtsState.READY
    }

    /**
     * Switches language on a live engine, e.g. English to Hindi mid-session.
     *
     * Returns false and moves to [TtsState.LANGUAGE_MISSING] when the device has no data for it,
     * which is a real case: Google's engine ships Hindi, but the voice files are a separate
     * download and many phones have only the system language installed.
     */
    fun setLanguage(next: SpeechLanguage): Boolean {
        language = next
        val tts = engine ?: return false
        val ok = applyLanguage(tts, next)
        state = if (ok) TtsState.READY else TtsState.LANGUAGE_MISSING
        if (ok) tts.playSilentUtterance(WARM_UP_MILLIS, TextToSpeech.QUEUE_FLUSH, WARM_UP_UTTERANCE_ID)
        return ok
    }

    /** Tries the language's locales in order and keeps the first the engine actually has. */
    private fun applyLanguage(tts: TextToSpeech, language: SpeechLanguage): Boolean =
        language.locales.any { locale ->
            val result = tts.setLanguage(locale)
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }

    override fun speak(utteranceId: String, text: String) {
        // QUEUE_FLUSH, per §12.4: a newer cue describes what the patient is doing now, so it
        // interrupts the stale one rather than waiting behind it.
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    /** Releases the engine. §12.4: the owner calls this when it is destroyed. */
    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        state = TtsState.INITIALISING
    }

    private companion object {
        const val WARM_UP_MILLIS = 1L
        const val WARM_UP_UTTERANCE_ID = "warm-up"
    }
}
