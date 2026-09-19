package com.example.rightposture

import com.example.rightposture.sync.CreateInviteOutcome
import com.example.rightposture.sync.InviteCodes
import com.example.rightposture.sync.InviteRepository
import com.example.rightposture.sync.RedeemOutcome
import com.example.rightposture.sync.SupabaseClient
import com.example.rightposture.sync.SupabaseConfig
import com.example.rightposture.sync.SupabaseResponse
import com.example.rightposture.sync.iso8601
import com.example.rightposture.sync.parseIso8601
import com.example.rightposture.sync.retryingSupabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 7.4's invite path.
 *
 * The interesting cases are the ones a happy-path demo never reaches: a code someone else already
 * redeemed, a code that went stale in a message thread, a collision on the primary key, and a
 * server that fails in a way worth retrying versus one that fails in a way that is not. None of
 * those can be produced on demand by pointing at a live project, so all of them are produced here.
 */
class InviteTest {

    private val config = SupabaseConfig("https://example.supabase.co", "anon-key")

    private fun repository(
        random: Random = Random(7),
        handler: (HttpRequestData, Int) -> Pair<HttpStatusCode, String>,
    ): Pair<InviteRepository, MutableList<String>> {
        val seen = mutableListOf<String>()
        var call = 0
        val engine = MockEngine { request ->
            call++
            seen += request.url.toString()
            val (status, body) = handler(request, call)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return InviteRepository(SupabaseClient(config, engine), random) to seen
    }

    private fun redeemReply(status: String, physio: String? = null) =
        """[{"status":"$status","physio":${physio?.let { "\"$it\"" } ?: "null"}}]"""

    // -------------------------------------------------------------------------------------
    // The code itself
    // -------------------------------------------------------------------------------------

    @Test
    fun `a generated code is six characters from the alphabet`() {
        repeat(500) {
            val code = InviteCodes.generate()
            assertEquals(6, code.length)
            assertTrue(code, code.all { it in InviteCodes.ALPHABET })
        }
    }

    @Test
    fun `the alphabet omits the characters people confuse`() {
        // I/1 and O/0 are the pairs that actually get mistyped off a handwritten note, and U is out
        // so a random six-character code cannot spell something read aloud in a clinic.
        for (excluded in listOf('I', 'L', 'O', 'U')) {
            assertTrue("$excluded should not be in the alphabet", excluded !in InviteCodes.ALPHABET)
        }
        assertEquals(32, InviteCodes.ALPHABET.length)
    }

    @Test
    fun `normalising folds the lookalikes and drops the grouping people add`() {
        // A patient reading a handwritten code cannot tell O from 0. Rejecting them for it would be
        // the app's fault, not theirs.
        assertEquals("0123AB", InviteCodes.normalise("oi23ab"))
        assertEquals("0123AB", InviteCodes.normalise(" 012-3AB "))
        assertEquals("1123AB", InviteCodes.normalise("Ll23AB"))
    }

    @Test
    fun `normalising rejects the wrong length and characters outside the alphabet`() {
        assertNull(InviteCodes.normalise("ABC"))
        assertNull(InviteCodes.normalise("ABCDEFG"))
        assertNull(InviteCodes.normalise("ABC!EF"))
        // U is deliberately not in the alphabet, so it is a typo rather than something to fold.
        assertNull(InviteCodes.normalise("ABCUEF"))
    }

    // -------------------------------------------------------------------------------------
    // Creating
    // -------------------------------------------------------------------------------------

    @Test
    fun `creating an invite returns a code and an expiry in the future`() = runTest {
        val (invites, _) = repository { _, _ -> HttpStatusCode.Created to "" }
        val outcome = invites.create("physio-1")
        assertTrue(outcome is CreateInviteOutcome.Created)
        val created = outcome as CreateInviteOutcome.Created
        assertEquals(6, created.code.length)
        assertTrue(created.expiresAt > System.currentTimeMillis())
    }

    @Test
    fun `a collision on the primary key retries with a different code, not the same one`() = runTest {
        // The one failure `retryingSupabase` must not handle: resending the colliding code collides
        // again. A physio handing out a code that links their patient to a stranger is the failure
        // being designed against, so this path exists even though it is vanishingly rare.
        val bodies = mutableListOf<String>()
        var call = 0
        val engine = MockEngine { request ->
            call++
            bodies += String(request.body.toByteArray())
            if (call == 1) {
                respond(
                    """{"code":"23505","message":"duplicate key value violates unique constraint"}""",
                    HttpStatusCode.Conflict,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respond("", HttpStatusCode.Created)
            }
        }
        val invites = InviteRepository(SupabaseClient(config, engine), Random(1))

        val outcome = invites.create("physio-1")
        assertTrue(outcome is CreateInviteOutcome.Created)
        assertEquals(2, bodies.size)
        // The whole point: the second attempt carried a *different* code.
        assertTrue(bodies[0] != bodies[1])
    }

    @Test
    fun `an unreachable server is offline, not a failure`() = runTest {
        // §13.3. "Try again when you're online" and "something is wrong" send the physio to
        // different places, and only one of them is true here.
        val engine = MockEngine { throw java.io.IOException("no route to host") }
        val invites = InviteRepository(SupabaseClient(config, engine))
        assertEquals(CreateInviteOutcome.Offline, invites.create("physio-1"))
    }

    // -------------------------------------------------------------------------------------
    // Redeeming
    // -------------------------------------------------------------------------------------

    @Test
    fun `a valid code links the patient to the physio who minted it`() = runTest {
        val (invites, urls) = repository { _, _ ->
            HttpStatusCode.OK to redeemReply("ok", "physio-9")
        }
        assertEquals(RedeemOutcome.Linked("physio-9"), invites.redeem("ABCDEF"))
        // Redemption goes through the function, never a table read - the patient has no select
        // policy on `invites` and must not need one.
        assertTrue(urls.single(), urls.single().endsWith("/rest/v1/rpc/redeem_invite"))
    }

    @Test
    fun `a code that is not a code never reaches the server`() = runTest {
        val (invites, urls) = repository { _, _ -> HttpStatusCode.OK to redeemReply("ok", "x") }
        assertEquals(RedeemOutcome.Malformed, invites.redeem("nope"))
        assertTrue(urls.isEmpty())
    }

    @Test
    fun `used, expired and unknown stay three different answers`() = runTest {
        // They are three different things to do next. Collapsing them into "invalid code" would
        // send a patient whose code expired to retype a code that was never wrong.
        for ((status, expected) in listOf(
            "used" to RedeemOutcome.AlreadyUsed,
            "expired" to RedeemOutcome.Expired,
            "unknown" to RedeemOutcome.Unknown,
            "unauthenticated" to RedeemOutcome.NotSignedIn,
        )) {
            val (invites, _) = repository { _, _ -> HttpStatusCode.OK to redeemReply(status) }
            assertEquals(status, expected, invites.redeem("ABCDEF"))
        }
    }

    @Test
    fun `redeeming your own code again links you, it does not tell you it is spent`() = runTest {
        // A patient who reinstalls, or who taps again because the first reply was lost, must end up
        // linked rather than locked out. The server decides this; the client only has to not
        // second-guess it.
        val (invites, _) = repository { _, _ -> HttpStatusCode.OK to redeemReply("ok", "physio-9") }
        assertEquals(RedeemOutcome.Linked("physio-9"), invites.redeem("ABCDEF"))
    }

    @Test
    fun `a typed code is normalised before it is sent`() = runTest {
        var body = ""
        val engine = MockEngine { request ->
            body = String(request.body.toByteArray())
            respond(
                redeemReply("ok", "physio-9"),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val invites = InviteRepository(SupabaseClient(config, engine))
        invites.redeem(" ab-cd0o ")
        assertTrue(body, body.contains("\"invite_code\":\"ABCD00\""))
    }

    // -------------------------------------------------------------------------------------
    // Retry behaviour
    // -------------------------------------------------------------------------------------

    @Test
    fun `a transient failure is retried and a rejection is not`() = runTest {
        var transientCalls = 0
        val recovered = retryingSupabase(sleep = {}) { attempt ->
            transientCalls++
            if (attempt < 3) SupabaseResponse(503, "", "unavailable")
            else SupabaseResponse(201, "", null)
        }
        assertTrue(recovered.ok)
        assertEquals(3, transientCalls)

        var rejectedCalls = 0
        val rejected = retryingSupabase(sleep = {}) {
            rejectedCalls++
            SupabaseResponse(400, "", "malformed")
        }
        // Resending identical bytes earns an identical rejection. Retrying it spends the patient's
        // battery to learn nothing.
        assertEquals(1, rejectedCalls)
        assertEquals(400, rejected.status)
    }

    @Test
    fun `offline is retried and the backoff doubles`() = runTest {
        val waits = mutableListOf<Long>()
        var calls = 0
        val outcome = retryingSupabase(attempts = 4, initialDelayMs = 100, sleep = { waits += it }) {
            calls++
            SupabaseResponse(0, "", "no network")
        }
        assertEquals(4, calls)
        // Doubling rather than a fixed interval, so one server hiccup does not become a synchronised
        // burst from every device that saw it. 7.5's WorkManager backoff uses the same shape.
        assertEquals(listOf(100L, 200L, 400L), waits)
        assertTrue(outcome.offline)
    }

    @Test
    fun `a 409 is never retried by the generic helper`() = runTest {
        // Invite collisions are resolved by picking a new code, which is `create`'s job. A generic
        // retry here would resend the colliding code and defeat that.
        var calls = 0
        retryingSupabase(sleep = {}) {
            calls++
            SupabaseResponse(409, "", "duplicate key")
        }
        assertEquals(1, calls)
    }

    // -------------------------------------------------------------------------------------
    // Timestamps
    // -------------------------------------------------------------------------------------

    @Test
    fun `a Postgres timestamptz round-trips through the parser`() {
        // The shape the live project actually sends: microseconds, and an offset written with a
        // colon. Neither is what `iso8601` emits, which is why parsing cannot assume symmetry.
        assertEquals(1_788_592_877_291L, parseIso8601("2026-09-05T07:21:17.291458+00:00"))
        assertEquals(1_788_592_877_291L, parseIso8601("2026-09-05T07:21:17.291Z"))
        assertEquals(1_788_592_877_000L, parseIso8601("2026-09-05T07:21:17Z"))
        // A non-zero offset is the local time, so the instant is earlier than the digits read.
        assertEquals(
            parseIso8601("2026-09-05T07:21:17Z"),
            parseIso8601("2026-09-05T12:51:17+05:30"),
        )
    }

    @Test
    fun `an unreadable timestamp is null rather than an exception`() {
        // Falling back is right; taking down a sync over a date field is not.
        assertNull(parseIso8601(null))
        assertNull(parseIso8601("not a date"))
        assertNull(parseIso8601("2026-13-99T99:99:99Z"))
    }

    @Test
    fun `writing then reading a timestamp is lossless to the millisecond`() {
        val instant = 1_788_592_877_291L
        assertEquals(instant, parseIso8601(iso8601(instant)))
    }

    // -------------------------------------------------------------------------------------
    // Linked patients
    // -------------------------------------------------------------------------------------

    @Test
    fun `linked patients are read without a second request when nobody has redeemed`() = runTest {
        val (invites, urls) = repository { _, _ -> HttpStatusCode.OK to "[]" }
        assertTrue(invites.linkedPatients("physio-1").isEmpty())
        assertEquals(1, urls.size)
    }

    @Test
    fun `linked patients resolve their redeemed invites to people`() = runTest {
        val (invites, urls) = repository { _, call ->
            if (call == 1) HttpStatusCode.OK to """[{"used_by":"p1"},{"used_by":"p1"},{"used_by":"p2"}]"""
            else HttpStatusCode.OK to
                """[{"id":"p1","email":"a@b.c","name":"Asha"},{"id":"p2","email":"d@e.f","name":null}]"""
        }
        val people = invites.linkedPatients("physio-1")
        assertEquals(2, people.size)
        assertEquals("Asha", people[0].name)
        assertNull(people[1].name)
        // A patient who redeemed twice is one patient, not two rows in the physio's list.
        assertTrue(urls[1], urls[1].contains("id=in.(p1,p2)"))
        assertNotNull(people.firstOrNull { it.id == "p2" })
    }
}
