package com.example.rightposture.sync

import kotlin.random.Random

/** A patient a physio has linked, as the physio home screen needs to show them. */
data class LinkedPatient(val id: String, val email: String, val name: String?)

sealed interface CreateInviteOutcome {
    /** [expiresAt] is epoch milliseconds, so the screen can say how long the patient has. */
    data class Created(val code: String, val expiresAt: Long) : CreateInviteOutcome

    data object Offline : CreateInviteOutcome

    data class Failed(val message: String) : CreateInviteOutcome
}

sealed interface RedeemOutcome {
    data class Linked(val physioId: String) : RedeemOutcome

    /** Not a code at all - wrong length, or a character the alphabet does not contain. */
    data object Malformed : RedeemOutcome

    /** Well-formed, but no such code exists. */
    data object Unknown : RedeemOutcome

    /** Someone else redeemed it. Redeeming your **own** code again returns [Linked], not this. */
    data object AlreadyUsed : RedeemOutcome

    data object Expired : RedeemOutcome

    /** Nobody is signed in, so there is no identity to record against the code. */
    data object NotSignedIn : RedeemOutcome

    data object Offline : RedeemOutcome

    data class Failed(val message: String) : RedeemOutcome
}

/**
 * §0's linking step: the physio generates a six-character code, the patient enters it once.
 *
 * Both halves are deliberately thin. The interesting decisions - atomicity, and telling "expired"
 * apart from "already used" - live in the `redeem_invite` function in `supabase/schema.sql`, because
 * neither can be made correct from the client side. See that file for why.
 */
class InviteRepository(
    private val client: SupabaseClient,
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Mints a code for [physioId].
     *
     * A code lives for [VALID_FOR_MS]. §0 does not set a lifetime, so this picks the shortest one
     * that still survives a real handover: a physio reads the code out at the end of an appointment
     * and the patient enters it when they get home. A code that never expired would sit in a
     * message thread indefinitely, redeemable by whoever else read it.
     *
     * On a primary-key conflict the code is regenerated rather than retried, which is the one case
     * [retryingSupabase] must not handle - resending the same colliding code collides again.
     */
    suspend fun create(physioId: String): CreateInviteOutcome {
        val expiresAt = now() + VALID_FOR_MS
        var lastError: String? = null

        repeat(COLLISION_ATTEMPTS) {
            val code = InviteCodes.generate(random)
            val response = retryingSupabase {
                client.insert(
                    "invites",
                    listOf(
                        mapOf(
                            "code" to code,
                            "physio_id" to physioId,
                            "expires_at" to iso8601(expiresAt),
                        ),
                    ),
                )
            }
            when {
                response.ok -> return CreateInviteOutcome.Created(code, expiresAt)
                response.offline -> return CreateInviteOutcome.Offline
                // 409 is the unique-violation on `invites.code`. The only fix is a different code.
                response.status == 409 -> lastError = response.error
                else -> return CreateInviteOutcome.Failed(
                    response.error ?: "Could not create an invite",
                )
            }
        }
        return CreateInviteOutcome.Failed(
            lastError ?: "Could not find an unused invite code. Please try again.",
        )
    }

    /**
     * Redeems [typed] on behalf of whoever is signed in.
     *
     * The code is normalised before it is sent: a patient reading a handwritten code cannot be
     * expected to tell `O` from `0`, and rejecting them for it would be the app's fault, not theirs.
     */
    suspend fun redeem(typed: String): RedeemOutcome {
        val code = InviteCodes.normalise(typed) ?: return RedeemOutcome.Malformed

        val response = retryingSupabase { client.rpc("redeem_invite", mapOf("invite_code" to code)) }
        if (response.offline) return RedeemOutcome.Offline
        if (!response.ok) return RedeemOutcome.Failed(response.error ?: "Could not redeem that code")

        // The function returns a table, so PostgREST sends back an array of one row.
        val row = response.asArray().firstOrNull() as? Map<*, *>
            ?: return RedeemOutcome.Failed("Unexpected reply from server")

        return when (row["status"] as? String) {
            "ok" -> (row["physio"] as? String)?.let { RedeemOutcome.Linked(it) }
                ?: RedeemOutcome.Failed("That code is not linked to a physiotherapist")
            "used" -> RedeemOutcome.AlreadyUsed
            "expired" -> RedeemOutcome.Expired
            "unknown" -> RedeemOutcome.Unknown
            "unauthenticated" -> RedeemOutcome.NotSignedIn
            else -> RedeemOutcome.Failed("Unexpected reply from server")
        }
    }

    /**
     * The patients who have redeemed one of [physioId]'s codes.
     *
     * Two requests rather than an embedded join: PostgREST can only embed across a declared foreign
     * key, and `invites.used_by` points at `users` while the rows the physio may read are decided by
     * a policy on `users`. Asking separately keeps both policies doing their own job.
     */
    suspend fun linkedPatients(physioId: String): List<LinkedPatient> {
        val invites = client.select(
            "invites",
            "?physio_id=eq.$physioId&used_by=not.is.null&select=used_by",
        )
        val ids = invites.asArray()
            .mapNotNull { (it as? Map<*, *>)?.get("used_by") as? String }
            .distinct()
        if (ids.isEmpty()) return emptyList()

        val people = client.select("users", "?id=in.(${ids.joinToString(",")})&select=id,email,name")
        return people.asArray().mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"] as? String ?: return@mapNotNull null
            LinkedPatient(id, map["email"] as? String ?: "", map["name"] as? String)
        }
    }

    private companion object {
        /** Seven days. Long enough to get home from an appointment, short enough to go stale. */
        const val VALID_FOR_MS = 7L * 24 * 60 * 60 * 1000

        const val COLLISION_ATTEMPTS = 5
    }
}

/**
 * Epoch milliseconds as the UTC timestamp Postgres expects.
 *
 * Hand-rolled because `java.time` needs desugaring on minSdk 26 for some of its surface and this is
 * three fields; `SimpleDateFormat` is not thread-safe and this is called from coroutines.
 */
/**
 * The inverse of [iso8601], tolerant of what Postgres actually sends.
 *
 * `timestamptz` comes back as `2026-09-05T07:21:17.291458+00:00` - microseconds, and an offset
 * written with a colon. Neither is what [iso8601] emits, so this reads the fixed-width prefix and
 * the offset separately rather than assuming the two formats match. Returns null rather than
 * throwing: a timestamp that cannot be read is worth falling back on, not worth losing the row for.
 */
internal fun parseIso8601(text: String?): Long? {
    if (text == null || text.length < 19) return null
    return runCatching {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.clear()
        // Lenient is the default, and it silently rolls nonsense over: month 13 becomes January of
        // the next year, so `2026-13-99` would parse to a real instant instead of being rejected.
        // A date that cannot be read must fall back, not quietly become a different date.
        calendar.isLenient = false
        calendar.set(
            text.substring(0, 4).toInt(),
            text.substring(5, 7).toInt() - 1,
            text.substring(8, 10).toInt(),
            text.substring(11, 13).toInt(),
            text.substring(14, 16).toInt(),
            text.substring(17, 19).toInt(),
        )
        val fraction = text.drop(19).takeWhile { it == '.' || it.isDigit() }
        val millis = fraction.removePrefix(".").take(3).padEnd(3, '0').toIntOrNull() ?: 0
        calendar.set(java.util.Calendar.MILLISECOND, if (fraction.isEmpty()) 0 else millis)

        val zone = text.drop(19 + fraction.length)
        val offsetMinutes = when {
            zone.isEmpty() || zone.startsWith("Z") -> 0
            zone.length >= 3 && (zone[0] == '+' || zone[0] == '-') -> {
                val digits = zone.drop(1).filter { it.isDigit() }
                if (digits.length < 4) return@runCatching calendar.timeInMillis
                val total = digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()
                if (zone[0] == '+') total else -total
            }
            else -> 0
        }
        calendar.timeInMillis - offsetMinutes * 60_000L
    }.getOrNull()
}

internal fun iso8601(epochMillis: Long): String {
    val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    calendar.timeInMillis = epochMillis
    return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(
        calendar.get(java.util.Calendar.YEAR),
        calendar.get(java.util.Calendar.MONTH) + 1,
        calendar.get(java.util.Calendar.DAY_OF_MONTH),
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
        calendar.get(java.util.Calendar.SECOND),
        calendar.get(java.util.Calendar.MILLISECOND),
    )
}
