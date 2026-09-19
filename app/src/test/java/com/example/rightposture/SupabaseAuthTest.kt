package com.example.rightposture

import com.example.rightposture.nav.Role
import com.example.rightposture.sync.AuthOutcome
import com.example.rightposture.sync.LocalOnlyAuthRepository
import com.example.rightposture.sync.SupabaseAuthRepository
import com.example.rightposture.sync.SupabaseClient
import com.example.rightposture.sync.SupabaseConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 7.3's auth path, against Ktor's MockEngine rather than a live project.
 *
 * The point is that every branch is reachable without credentials: a build with no backend
 * configured, a wrong password, an unreachable server, and a successful sign-in that has to go and
 * read the role row afterwards. None of that can be exercised by pointing at a real Supabase
 * instance and hoping it misbehaves on cue.
 */
class SupabaseAuthTest {

    private val config = SupabaseConfig("https://example.supabase.co", "anon-key")

    private fun repository(vararg replies: (String) -> Pair<HttpStatusCode, String>): SupabaseAuthRepository {
        var call = 0
        val engine = MockEngine { request ->
            val reply = replies[minOf(call, replies.size - 1)](request.url.toString())
            call++
            respond(
                content = reply.second,
                status = reply.first,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return SupabaseAuthRepository(SupabaseClient(config, engine), config)
    }

    // -------------------------------------------------------------------------------------
    // The unconfigured build must behave exactly as the app did before 7.3
    // -------------------------------------------------------------------------------------

    @Test
    fun `a build with no backend reports itself unconfigured rather than failing`() = runTest {
        // §13.3 and 7.1's airplane-mode gate both depend on this: with no project configured the
        // app is fully functional and entirely local, not broken.
        val blank = SupabaseConfig("", "")
        assertFalse(blank.isConfigured)
        assertFalse(LocalOnlyAuthRepository.configured)
        assertEquals(AuthOutcome.Offline, LocalOnlyAuthRepository.signIn("a@b.c", "pw"))
    }

    @Test
    fun `a configured client is only configured when both values are present`() {
        // Half-configured is the likelier mistake - pasting the URL and forgetting the key - and it
        // must not look configured, or every call fails at the server instead of being skipped.
        assertFalse(SupabaseConfig("https://x.supabase.co", "").isConfigured)
        assertFalse(SupabaseConfig("", "key").isConfigured)
        assertTrue(config.isConfigured)
    }

    // -------------------------------------------------------------------------------------
    // Sign-in
    // -------------------------------------------------------------------------------------

    @Test
    fun `a successful sign-in reads the role from the users table`() = runTest {
        val repo = repository(
            { _ -> HttpStatusCode.OK to TOKEN_WITH_USER },
            { _ -> HttpStatusCode.OK to """[{"role":"physio"}]""" },
        )

        val outcome = repo.signIn("physio@clinic.test", "correct-horse")

        assertTrue(outcome.toString(), outcome is AuthOutcome.SignedIn)
        val account = (outcome as AuthOutcome.SignedIn).account
        assertEquals("user-1", account.id)
        assertEquals(Role.PHYSIO, account.role)
    }

    @Test
    fun `a missing role row defaults to patient, not physio`() = runTest {
        // Failing to the *less* privileged role on purpose. A physio wrongly shown the patient flow
        // is inconvenienced; a patient wrongly shown authoring can overwrite the reference their
        // own exercises are scored against.
        val repo = repository(
            { _ -> HttpStatusCode.OK to TOKEN_WITH_USER },
            { _ -> HttpStatusCode.OK to "[]" },
        )

        val outcome = repo.signIn("someone@clinic.test", "pw") as AuthOutcome.SignedIn

        assertEquals(Role.PATIENT, outcome.account.role)
    }

    @Test
    fun `a wrong password is rejected with the server's own wording`() = runTest {
        val repo = repository({ _ ->
            HttpStatusCode.BadRequest to """{"error_description":"Invalid login credentials"}"""
        })

        val outcome = repo.signIn("physio@clinic.test", "wrong")

        assertTrue(outcome is AuthOutcome.Rejected)
        assertEquals("Invalid login credentials", (outcome as AuthOutcome.Rejected).message)
    }

    @Test
    fun `an unreachable server is offline, not a rejected password`() = runTest {
        // The distinction that matters most here. Telling a patient their password is wrong when
        // the wifi is down sends them to reset a password that was fine.
        val engine = MockEngine { throw java.io.IOException("network is unreachable") }
        val repo = SupabaseAuthRepository(SupabaseClient(config, engine), config)

        assertEquals(AuthOutcome.Offline, repo.signIn("physio@clinic.test", "pw"))
    }

    // -------------------------------------------------------------------------------------
    // Sign-up
    // -------------------------------------------------------------------------------------

    @Test
    fun `sign-up writes the role and returns the account`() = runTest {
        val repo = repository(
            { _ -> HttpStatusCode.OK to TOKEN_WITH_USER },
            { _ -> HttpStatusCode.Created to "" },
        )

        val outcome = repo.signUp("physio@clinic.test", "pw", Role.PHYSIO)

        assertTrue(outcome.toString(), outcome is AuthOutcome.SignedIn)
        assertEquals(Role.PHYSIO, (outcome as AuthOutcome.SignedIn).account.role)
    }

    @Test
    fun `sign-up without a session says to confirm the email`() = runTest {
        // With email confirmation left on, signup returns a user and no token. Silently trying to
        // continue would fail later with a message that does not name the actual cause.
        val repo = repository({ _ -> HttpStatusCode.OK to """{"id":"user-9","email":"a@b.c"}""" })

        val outcome = repo.signUp("a@b.c", "pw", Role.PATIENT)

        assertTrue(outcome is AuthOutcome.Rejected)
        assertTrue(
            (outcome as AuthOutcome.Rejected).message,
            outcome.message.contains("Confirm the email"),
        )
    }

    @Test
    fun `a duplicate account is rejected rather than reported as signed in`() = runTest {
        val repo = repository({ _ ->
            HttpStatusCode.UnprocessableEntity to """{"msg":"User already registered"}"""
        })

        val outcome = repo.signUp("taken@clinic.test", "pw", Role.PHYSIO)

        assertEquals("User already registered", (outcome as AuthOutcome.Rejected).message)
    }

    @Test
    fun `an unparseable error still produces an actionable message`() = runTest {
        // Supabase behind a proxy can return HTML. Surfacing "Request failed (503)" beats throwing
        // a JSON parse exception from inside a coroutine that was not expecting one.
        val repo = repository({ _ -> HttpStatusCode.ServiceUnavailable to "<html>gateway</html>" })

        val outcome = repo.signIn("a@b.c", "pw")

        assertTrue(outcome is AuthOutcome.Rejected)
        assertTrue((outcome as AuthOutcome.Rejected).message, outcome.message.contains("503"))
    }

    private companion object {
        const val TOKEN_WITH_USER =
            """{"access_token":"jwt-abc","user":{"id":"user-1","email":"physio@clinic.test"}}"""
    }
}
