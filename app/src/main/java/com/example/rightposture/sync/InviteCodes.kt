package com.example.rightposture.sync

import kotlin.random.Random

/**
 * §0's six-character invite code.
 *
 * The alphabet is Crockford's Base32: the digits and the uppercase letters **minus I, L, O and U**.
 * I/1 and O/0 are the pairs people actually mistype, and U is dropped so a random code cannot spell
 * something a physiotherapist would rather not read out to a patient over the phone.
 *
 * That leaves 32 symbols and 32^6 ≈ 1.07 billion codes. Collisions are handled by the primary key on
 * `invites.code` rather than by hoping - see [InviteRepository.create], which regenerates on a
 * conflict. At the scale this app operates on, a collision is a once-in-a-project event; the retry
 * exists because "unlikely" is not "impossible" and the failure mode is a physio handing out a code
 * that links their patient to a stranger.
 */
object InviteCodes {

    const val LENGTH = 6

    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun generate(random: Random = Random.Default): String =
        buildString(LENGTH) { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /**
     * Turns what a patient typed into a code, or null if it cannot be one.
     *
     * Lowercase is accepted, spaces and hyphens are dropped because people group characters when
     * they copy them down, and Crockford's decoding rules fold the confusable letters onto the
     * digits they look like. A patient who writes down `O` for zero should get their exercises, not
     * an error message about a character they cannot tell apart from the one they meant.
     */
    fun normalise(typed: String): String? {
        val cleaned = buildString {
            for (raw in typed.uppercase()) {
                when (raw) {
                    ' ', '-', '\t', '\u00A0' -> Unit  // nbsp survives copy-paste
                    'O' -> append('0')
                    'I', 'L' -> append('1')
                    else -> if (raw in ALPHABET) append(raw) else return null
                }
            }
        }
        return cleaned.takeIf { it.length == LENGTH }
    }
}
