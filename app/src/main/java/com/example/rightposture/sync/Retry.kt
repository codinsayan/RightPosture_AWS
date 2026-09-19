package com.example.rightposture.sync

import kotlinx.coroutines.delay

/**
 * 7.4's "repository retry behaviour", in one place so no repository invents its own.
 *
 * ## What counts as retryable
 *
 * Only failures a later attempt could plausibly resolve:
 *
 * - **status 0** - the request never left the device. §13.3's ordinary case.
 * - **5xx** - the server broke, not the request.
 * - **429** - rate limited, which is a request to come back later, stated explicitly.
 *
 * Everything else is left alone on purpose. A 400, 401, 403 or 409 describes something wrong with
 * the request itself, and sending the identical bytes again produces the identical rejection while
 * burning the user's battery and the project's quota. A 409 in particular must **not** be retried
 * here: an invite-code collision needs a *different* code, which is [InviteRepository.create]'s job,
 * not this function's.
 *
 * ## Why exponential
 *
 * A fixed short interval turns one server hiccup into a burst from every device at once. Doubling
 * spreads the load out, and 7.5's WorkManager backoff follows the same shape, so a request that
 * fails inside the app and one that fails in the background behave the same way.
 *
 * [sleep] is injected so the tests can assert the delays without spending them.
 */
suspend fun retryingSupabase(
    attempts: Int = 3,
    initialDelayMs: Long = 500,
    sleep: suspend (Long) -> Unit = { delay(it) },
    call: suspend (attempt: Int) -> SupabaseResponse,
): SupabaseResponse {
    require(attempts >= 1) { "a call is attempted at least once" }
    var wait = initialDelayMs
    var last = call(1)
    for (attempt in 2..attempts) {
        if (!last.retryable) return last
        sleep(wait)
        wait *= 2
        last = call(attempt)
    }
    return last
}

/** See [retryingSupabase] for why this list is short and why 4xx is absent from it. */
val SupabaseResponse.retryable: Boolean
    get() = !ok && (offline || status >= 500 || status == 429)
