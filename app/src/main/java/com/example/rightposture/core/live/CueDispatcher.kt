package com.example.rightposture.core.live

/**
 * §12.4 and §12.5. Turns published [FrameResult]s into speech and haptics.
 *
 * Kept free of Android types so the whole policy - what is spoken, what is skipped, what buzzes -
 * is testable on the JVM against a fake speaker. The platform pieces behind [Speaker] and [Haptics]
 * then have nothing left to decide.
 *
 * Driven from the analysis thread, not from the UI's sampled state: cues, rep completions and fault
 * edges are all live for exactly one frame, and a UI sampling at 33 ms drops most of them.
 *
 * Two sources of speech meet here, and they are not the same kind of thing. **Cues** come from the
 * bundle's fault definitions (§8.1) - clinical claims a physio authored. **Announcements** are the
 * app's own messages about the set, the ones already on screen: the rep counter, §6.12.4's reason a
 * rep did not count, and the end of the set. Until 8.1 ships a fault set, cues are the empty half.
 */
class CueDispatcher(
    private val speaker: Speaker,
    private val haptics: Haptics,
    private var phrases: PhraseBook = EnglishPhrases,
    /** §12.4 speaks cues unconditionally; counting every rep aloud is a preference. */
    private val announceReps: Boolean = true,
) {
    /** The last utterance actually spoken, so the same cue is never said twice. */
    private var spoken: CueKey? = null
    private var announcedSetComplete = false

    private data class CueKey(val faultId: String, val occurrence: Int, val level: CueLevel)

    fun setLanguage(phraseBook: PhraseBook) {
        phrases = phraseBook
    }

    fun onFrame(result: FrameResult) {
        // §12.5 costs three lines and works when the phone is in a pocket, so it runs whether or not
        // the patient can hear anything.
        if (result.faultOnsetIds.isNotEmpty()) haptics.onFaultOnset()
        if (result.faultClearIds.isNotEmpty()) haptics.onFaultResolved()

        announce(result)

        val cue = result.cue ?: return
        // §6.15 already decided *whether* to speak, using the escalation ladder and both cooldowns.
        // Re-deciding here would silently override arbitration; this only refuses an exact repeat.
        val key = CueKey(cue.faultId, cue.occurrence, cue.level)
        if (key == spoken) return

        // A cue dropped because the engine is not up yet is dropped, not queued. By the time speech
        // is available the patient has moved on, and §6.15's cooldowns are wall-clock: replaying a
        // stale backlog would talk over the movement it no longer describes.
        if (!speaker.isReady) return

        spoken = key
        speaker.speak(utteranceId(cue), cue.text)
    }

    /**
     * The app's own voice: rep counted, rep refused, set finished.
     *
     * A fault cue outranks these - it is the thing that needs fixing right now - but they cannot
     * collide in practice, because a rep boundary and a mid-rep fault do not fall on one frame.
     */
    private fun announce(result: FrameResult) {
        // §6.18.6 outranks everything else the app might say on this frame, including the rep it
        // just counted: the instruction is to stop, and a rep count spoken over it would read as
        // encouragement to continue.
        if (result.safetyStop) {
            haptics.onSetComplete()
            say("safety-stop", phrases.safetyStop())
            announcedSetComplete = true
            return
        }
        val completed = result.completedRep
        if (completed != null) {
            if (completed.valid) {
                // The counted rep gets a tick even when muted: this is the confirmation the patient
                // is waiting for, and they are looking at their own knee, not at the phone.
                haptics.onRepCounted()
                val note = result.lastRepNote
                if (note != null) {
                    // A rep that counted but was not the movement prescribed. Saying only the number
                    // here is what teaches a patient that overshooting is fine.
                    say("note:${result.validReps}", phrases.repNote(note))
                } else if (announceReps) {
                    say("rep:${result.validReps}", phrases.repCount(result.validReps))
                }
            } else {
                haptics.onRepRejected()
                val reason = completed.rejection ?: RepRejection.UNSCORED
                val joint = result.skippedFeature
                val text = if (joint != null) phrases.skippedFeature(joint) else phrases.rejection(reason)
                say("rejected:${result.validReps + result.partialReps}", text)
            }
        }

        // Once per set, on the frame the set closes.
        if (result.status == ExerciseStatus.SET_COMPLETE) {
            if (!announcedSetComplete) {
                announcedSetComplete = true
                haptics.onSetComplete()
                say("set-complete", phrases.setComplete())
            }
        } else {
            announcedSetComplete = false
        }
    }

    private fun say(utteranceId: String, text: String) {
        if (!speaker.isReady) return
        speaker.speak(utteranceId, text)
    }

    /** A new set forgets what was said in the last one. */
    fun resetSet() {
        spoken = null
        announcedSetComplete = false
    }

    private fun utteranceId(cue: Cue) = "${cue.faultId}:${cue.occurrence}:${cue.level.name}"
}

/**
 * §12.4. `speak(text, QUEUE_FLUSH, null, id)`.
 *
 * [isReady] is false until the engine has initialised *and* its language data is confirmed present;
 * §12.4's warm-up exists because the first utterance is otherwise swallowed.
 */
interface Speaker {
    val isReady: Boolean
    fun speak(utteranceId: String, text: String)
}

/**
 * §12.5. `CONFIRM` on fault onset, `CLOCK_TICK` on resolution.
 *
 * The rep-boundary feedback is not in §12.5, which only names the fault pair. It is here because
 * §12.5's own reason - the patient is holding the phone or has it in a pocket and cannot watch the
 * counter - applies at least as strongly to "did that rep count?".
 */
interface Haptics {
    fun onFaultOnset()
    fun onFaultResolved()
    fun onRepCounted()
    fun onRepRejected()
    fun onSetComplete()
}
