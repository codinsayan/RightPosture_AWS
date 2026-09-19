package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId
import java.util.Locale

/**
 * The languages §12.4's speech supports.
 *
 * Hindi is here because the patients this is being built for are in India and a cue that has to be
 * translated in the patient's head is a cue that arrives after the rep it describes.
 */
enum class SpeechLanguage(
    /** What to show in the picker, in that language. */
    val label: String,
    /**
     * Locales to try, best first. Google's engine ships `hi-IN` but not every device has the data,
     * and falling back to the bare language is better than falling silent.
     */
    val locales: List<Locale>,
) {
    ENGLISH("English", listOf(Locale("en", "IN"), Locale.UK, Locale.US)),
    HINDI("हिन्दी", listOf(Locale("hi", "IN"), Locale("hi"))),
}

/**
 * Everything the app says to the patient in its own voice, in one place per language.
 *
 * Cue text is **not** here: that comes from the fault definitions in the bundle (§8.1), which the
 * physio authors. These are the app's own messages - the ones it already puts on screen - so that
 * speaking them does not mean inventing a second, untranslated vocabulary.
 */
interface PhraseBook {
    val language: SpeechLanguage

    /** §6.12.4's "it did not count, and here is why". */
    fun rejection(reason: RepRejection): String

    /**
     * The same refusal, when what went wrong was a joint left out rather than a range jumped.
     *
     * "Follow the whole movement" is no help to a patient who thinks they did: they moved the joint
     * they were watching. Naming the one they forgot is the only version of this sentence they can
     * act on.
     */
    fun skippedFeature(id: FeatureId): String

    /** "It counted, and here is what to change." */
    fun repNote(note: RepNote): String

    /** A joint named the way a patient would name it, not the way the feature table does. */
    fun featureName(id: FeatureId): String

    /** Spoken and shown when the prescribed reps are done. */
    fun setComplete(): String

    /** Spoken after a rep that counted, e.g. "three". */
    fun repCount(rep: Int): String

    fun status(status: ExerciseStatus): String

    /** The "N didn't count" line under the rep counter. */
    fun partialReps(count: Int): String

    /** §6.20's checklist label for one calibration check, shown next to its tick. */
    fun calibrationCheck(id: CalibrationCheckId): String

    /** Shown while calibration is still gathering its seven checks. */
    fun calibrationInProgress(): String

    /** §8.2 "phone bumped": the view calibration approved no longer holds. */
    fun recalibrating(): String

    /**
     * §6.18.6's safety intervention, said aloud the moment it fires.
     *
     * Describes what was measured and what to do. It makes no claim about injury or diagnosis:
     * §6.18's device gate is explicit that no medical-validity claim is made, and the honest
     * statement is that form has fallen off, which is a thing the app did measure.
     */
    fun safetyStop(): String

    /** §6.19: the hold broke. Spoken once, with the duration achieved. */
    fun holdBroken(seconds: Int): String

    /** §6.19's drift correction, naming the joint that is slipping. */
    fun holdDrift(id: FeatureId): String

    /** Shown while the physio's one-second neutral pose is being captured. */
    fun neutralCapture(): String
}

fun phraseBookFor(language: SpeechLanguage): PhraseBook = when (language) {
    SpeechLanguage.ENGLISH -> EnglishPhrases
    SpeechLanguage.HINDI -> HindiPhrases
}

object EnglishPhrases : PhraseBook {
    override val language = SpeechLanguage.ENGLISH

    override fun rejection(reason: RepRejection) = when (reason) {
        RepRejection.RANGE -> "That one didn't count — go all the way down."
        RepRejection.TOO_FAST -> "That one didn't count — that was too fast."
        RepRejection.TOO_SLOW -> "That one didn't count — that was too slow."
        RepRejection.UNSEEN -> "That one didn't count — I lost sight of you."
        RepRejection.UNSCORED -> "That one didn't count — I couldn't score it."
        RepRejection.SKIPPED -> "That one didn't count — follow the whole movement, don't jump to the end."
    }

    override fun skippedFeature(id: FeatureId) =
        "That one didn't count — you left out the ${featureName(id)}."

    override fun repNote(note: RepNote) = when (note) {
        RepNote.Overshot -> "That counted — but you went past the target. Stop where the movement ends."
        RepNote.ShortOfRange -> "That counted — but go a little further next time."
        is RepNote.Compensation -> "That counted — but keep your ${featureName(note.feature)} still."
    }

    override fun featureName(id: FeatureId) = when (id) {
        FeatureId.KNEE_FLEX_ACTIVE, FeatureId.KNEE_FRONTAL_ACTIVE -> "knee"
        FeatureId.KNEE_FLEX_PASSIVE -> "other knee"
        FeatureId.HIP_FLEX_ACTIVE, FeatureId.HIP_ABDUCTION_ACTIVE -> "hip"
        FeatureId.HIP_FLEX_PASSIVE -> "other hip"
        FeatureId.TRUNK_FLEXION -> "back"
        FeatureId.TRUNK_LATERAL -> "torso"
        FeatureId.TRUNK_ROTATION -> "shoulders"
        FeatureId.PELVIC_OBLIQUITY -> "hips"
        FeatureId.SHOULDER_FLEX_ACTIVE -> "arm"
        FeatureId.SHOULDER_ELEVATION -> "shoulder"
        FeatureId.ELBOW_FLEX_ACTIVE -> "elbow"
        FeatureId.ANKLE_DORSIFLEX_ACTIVE -> "ankle"
        FeatureId.LUMBAR_FLEXION -> "lower back"
    }

    override fun setComplete() = "Set complete. That's all of them, you can stop."

    override fun repCount(rep: Int) = rep.toString()

    override fun status(status: ExerciseStatus) = when (status) {
        ExerciseStatus.NO_PERSON -> "Step into frame."
        ExerciseStatus.OCCLUDED -> "I can't see you clearly — check the framing."
        ExerciseStatus.READY -> "Ready when you are."
        ExerciseStatus.TRACKING -> "Tracking."
        ExerciseStatus.SET_COMPLETE -> "Set complete — that's all of them. You can stop."
    }

    override fun partialReps(count: Int) = "$count didn't count"

    override fun calibrationCheck(id: CalibrationCheckId) = when (id) {
        CalibrationCheckId.VISIBILITY -> "I can see the joints I need to watch"
        CalibrationCheckId.FRAME_FILL -> "You're the right distance from the camera"
        CalibrationCheckId.CENTERED -> "You're centred in the frame"
        CalibrationCheckId.VIEW -> "The camera is angled correctly for this exercise"
        CalibrationCheckId.UPRIGHT -> "The phone is upright"
        CalibrationCheckId.LIGHTING -> "The lighting is good enough"
        CalibrationCheckId.STATIONARY -> "You're holding still"
    }

    override fun calibrationInProgress() = "Getting ready — follow the checklist."

    override fun recalibrating() = "The view has changed. Line the camera back up to continue."

    override fun safetyStop() =
        "Stop the set here. Your form has dropped well below how you started — rest before you do more."

    override fun holdBroken(seconds: Int) = "Hold finished — $seconds seconds."

    override fun holdDrift(id: FeatureId) = "You're slipping — bring your ${featureName(id)} back."

    override fun neutralCapture() = "Stand naturally and still."
}

/**
 * Hindi, in Devanagari, addressed to the patient as आप.
 *
 * Written to be *spoken*, not to be a literal translation: "go all the way down" becomes
 * "पूरा नीचे जाइए" rather than a word-for-word rendering that a TTS voice would stumble over.
 */
object HindiPhrases : PhraseBook {
    override val language = SpeechLanguage.HINDI

    override fun rejection(reason: RepRejection) = when (reason) {
        RepRejection.RANGE -> "यह गिनती में नहीं आया — पूरा नीचे जाइए।"
        RepRejection.TOO_FAST -> "यह गिनती में नहीं आया — बहुत तेज़ था।"
        RepRejection.TOO_SLOW -> "यह गिनती में नहीं आया — बहुत धीमा था।"
        RepRejection.UNSEEN -> "यह गिनती में नहीं आया — आप दिखाई नहीं दिए।"
        RepRejection.UNSCORED -> "यह गिनती में नहीं आया — मैं इसे माप नहीं सका।"
        RepRejection.SKIPPED -> "यह गिनती में नहीं आया — पूरी हरकत कीजिए, सीधे अंत तक मत जाइए।"
    }

    override fun skippedFeature(id: FeatureId) =
        "यह गिनती में नहीं आया — आपने ${featureName(id)} को छोड़ दिया।"

    override fun repNote(note: RepNote) = when (note) {
        RepNote.Overshot -> "यह गिना गया — लेकिन आप ज़रूरत से ज़्यादा गए। जहाँ हरकत ख़त्म होती है वहीं रुकिए।"
        RepNote.ShortOfRange -> "यह गिना गया — लेकिन अगली बार थोड़ा और आगे तक जाइए।"
        is RepNote.Compensation -> "यह गिना गया — लेकिन अपनी ${featureName(note.feature)} स्थिर रखिए।"
    }

    override fun featureName(id: FeatureId) = when (id) {
        FeatureId.KNEE_FLEX_ACTIVE, FeatureId.KNEE_FRONTAL_ACTIVE -> "घुटने"
        FeatureId.KNEE_FLEX_PASSIVE -> "दूसरे घुटने"
        FeatureId.HIP_FLEX_ACTIVE, FeatureId.HIP_ABDUCTION_ACTIVE -> "कूल्हे"
        FeatureId.HIP_FLEX_PASSIVE -> "दूसरे कूल्हे"
        FeatureId.TRUNK_FLEXION -> "पीठ"
        FeatureId.TRUNK_LATERAL -> "धड़"
        FeatureId.TRUNK_ROTATION -> "कंधों"
        FeatureId.PELVIC_OBLIQUITY -> "कमर"
        FeatureId.SHOULDER_FLEX_ACTIVE -> "बाँह"
        FeatureId.SHOULDER_ELEVATION -> "कंधे"
        FeatureId.ELBOW_FLEX_ACTIVE -> "कोहनी"
        FeatureId.ANKLE_DORSIFLEX_ACTIVE -> "टखने"
        FeatureId.LUMBAR_FLEXION -> "कमर"
    }

    override fun setComplete() = "सेट पूरा हुआ। बस इतना ही, अब आप रुक सकते हैं।"

    override fun repCount(rep: Int) = HINDI_NUMBERS.getOrElse(rep) { rep.toString() }

    override fun status(status: ExerciseStatus) = when (status) {
        ExerciseStatus.NO_PERSON -> "कैमरे के सामने आइए।"
        ExerciseStatus.OCCLUDED -> "आप साफ़ नहीं दिख रहे — कैमरा ठीक कीजिए।"
        ExerciseStatus.READY -> "जब आप तैयार हों, शुरू कीजिए।"
        ExerciseStatus.TRACKING -> "देख रहा हूँ।"
        ExerciseStatus.SET_COMPLETE -> "सेट पूरा हुआ — बस इतना ही। अब आप रुक सकते हैं।"
    }

    override fun partialReps(count: Int) = "$count गिनती में नहीं आए"

    override fun calibrationCheck(id: CalibrationCheckId) = when (id) {
        CalibrationCheckId.VISIBILITY -> "मुझे ज़रूरी जोड़ दिख रहे हैं"
        CalibrationCheckId.FRAME_FILL -> "आप कैमरे से सही दूरी पर हैं"
        CalibrationCheckId.CENTERED -> "आप फ़्रेम के बीच में हैं"
        CalibrationCheckId.VIEW -> "इस एक्सरसाइज़ के लिए कैमरे का कोण सही है"
        CalibrationCheckId.UPRIGHT -> "फ़ोन सीधा खड़ा है"
        CalibrationCheckId.LIGHTING -> "रोशनी पर्याप्त है"
        CalibrationCheckId.STATIONARY -> "आप स्थिर खड़े हैं"
    }

    override fun calibrationInProgress() = "तैयार हो रहे हैं — चेकलिस्ट पूरी कीजिए।"

    override fun recalibrating() = "कैमरे का नज़ारा बदल गया है। दोबारा सही स्थिति में लाइए।"

    override fun safetyStop() =
        "यहीं रुक जाइए। आपका फ़ॉर्म शुरुआत से काफ़ी गिर गया है — और करने से पहले आराम कीजिए।"

    override fun holdBroken(seconds: Int) = "होल्ड पूरा — $seconds सेकंड।"

    override fun holdDrift(id: FeatureId) = "आप फिसल रहे हैं — अपनी ${featureName(id)} वापस लाइए।"

    override fun neutralCapture() = "सामान्य रूप से सीधे और स्थिर खड़े रहिए।"

    /**
     * Spoken forms up to a plausible set length. Beyond this the numeral is handed over and the
     * engine reads it in whatever way its Hindi voice does, which is better than saying nothing.
     */
    private val HINDI_NUMBERS = listOf(
        "शून्य", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ", "दस",
        "ग्यारह", "बारह", "तेरह", "चौदह", "पंद्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस", "बीस",
    )
}

/**
 * Something worth saying about a rep that **counted**.
 *
 * §6.12.4 only decides whether a rep is a rep. A rep can clear that bar and still not be the
 * movement that was prescribed, and counting it in silence tells the patient it was fine.
 *
 * Deliberately derived from geometry rather than from authored faults: overshoot and short range
 * are statements about the reference the physio recorded, true for any exercise, and so carry no
 * clinical claim of the kind §6.15.1 requires authoring for.
 */
sealed interface RepNote {
    /** Past the reference extremum: phi > 1 by more than the tolerance. */
    data object Overshot : RepNote

    /** Counted under §6.12.4, but short of the reference's own range. */
    data object ShortOfRange : RepNote

    /** The range was right; another watch-listed joint drifted while getting there. */
    data class Compensation(val feature: FeatureId) : RepNote
}
