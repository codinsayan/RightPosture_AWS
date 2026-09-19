package com.example.rightposture.speech

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import com.example.rightposture.core.live.Haptics

/**
 * §12.5. `CONFIRM` on fault onset, `CLOCK_TICK` on resolution, plus the rep-boundary feedback.
 *
 * **Every call is posted to the view's own thread.** The dispatcher runs on the camera analysis
 * thread, and `performHapticFeedback` touches view state: called directly from there it does not
 * buzz, which is precisely the "haptics do nothing" symptom.
 *
 * `FLAG_IGNORE_GLOBAL_SETTING` is deliberately not used: a patient who has turned haptics off has
 * said what they want, and this is a redundant channel, not the primary one.
 */
class ViewHaptics(private val view: View) : Haptics {

    override fun onFaultOnset() = perform(confirm)

    override fun onFaultResolved() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** A rep that counted. Distinct from the rejection buzz, because it means the opposite. */
    override fun onRepCounted() = perform(confirm)

    override fun onRepRejected() = perform(reject)

    override fun onSetComplete() = perform(HapticFeedbackConstants.LONG_PRESS)

    private fun perform(constant: Int) {
        view.post { view.performHapticFeedback(constant) }
    }

    // §12.5 names CONFIRM and REJECT, both of which arrived in API 30 while minSdk is 24. On older
    // phones the closest older constant is used rather than dropping the feedback: a weaker buzz is
    // still the confirmation the patient is waiting for.
    private val confirm
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }

    private val reject
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
}
