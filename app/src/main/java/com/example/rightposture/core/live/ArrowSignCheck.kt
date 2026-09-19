package com.example.rightposture.core.live

import com.example.rightposture.core.FeatureId

/**
 * §6.16's empirical sign determination, as a thing the app can actually do on a phone.
 *
 * The iteration plan's device gate is "deliberately deviate each demo feature and confirm the arrow
 * points toward correction", and its user action is "do not ship guessed rotation signs". That is a
 * procedure, not a calculation, so it needs somewhere to happen.
 *
 * While a check is running the arrow is drawn with a **candidate** sign rather than a verified one.
 * The candidate is the tester's hypothesis, not the app's: nothing here is written into
 * [ArrowSigns], which stays the record of what has actually been confirmed on a device.
 */
class ArrowSignCheck {

    private val candidates = mutableMapOf<FeatureId, Int>()

    /** True while the tester is checking signs, which is the only time an unverified arrow shows. */
    var active = false
        private set

    fun start() {
        active = true
    }

    fun stop() {
        active = false
    }

    /**
     * A tester's override for this feature, or null to use the sign the bundle carries.
     *
     * The bundle's sign is now measured from the reference recording rather than guessed, so this is
     * no longer how signs are *determined* - it is how a person confirms that derivation on a phone
     * without editing code.
     */
    fun overrideFor(id: FeatureId): Int? = if (active) candidates[id] ?: FIRST_CANDIDATE else null

    /** Flips the candidate for a feature. The tester presses this until the arrow points the right way. */
    fun flip(id: FeatureId) {
        candidates[id] = -(candidates[id] ?: FIRST_CANDIDATE)
    }

    /** What to write into [ArrowSigns] once the tester confirms the arrow is correct. */
    fun confirmed(): Map<FeatureId, Int> = candidates.toMap()

    private companion object {
        /**
         * Where each feature starts. Which one it is does not matter - the tester flips until the
         * arrow is right - but starting somewhere fixed makes the check repeatable.
         */
        const val FIRST_CANDIDATE = 1
    }
}
