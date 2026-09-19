package com.example.rightposture.sync

import com.example.rightposture.BuildConfig
import com.example.rightposture.nav.Role

/**
 * The project URL and anon key, read from `local.properties` at build time.
 *
 * [isConfigured] is load-bearing rather than cosmetic. §13.3 requires the entire patient session to
 * work with no network, and 7.1's gate is that it works in airplane mode - so a build with no
 * backend configured must behave exactly like the app did before 7.3, all the way down to the local
 * role picker. Every call site checks this rather than assuming a server exists.
 */
data class SupabaseConfig(val url: String, val anonKey: String) {
    val isConfigured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()

    companion object {
        fun fromBuildConfig() = SupabaseConfig(
            url = BuildConfig.SUPABASE_URL.trimEnd('/'),
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
        )
    }
}

/** A signed-in person, and which of §2.4's two roles they hold. */
data class Account(val id: String, val email: String, val role: Role)

sealed interface AuthOutcome {
    data class SignedIn(val account: Account) : AuthOutcome

    /** Credentials were rejected, or the account already exists. Actionable by the user. */
    data class Rejected(val message: String) : AuthOutcome

    /**
     * The server could not be reached. Distinct from [Rejected] on purpose: telling a patient their
     * password is wrong when the wifi is down sends them to reset a password that was fine.
     */
    data object Offline : AuthOutcome
}

/**
 * §7.3's auth, behind an interface so the UI never touches HTTP and the tests never touch a network.
 */
interface AuthRepository {
    val configured: Boolean
    suspend fun signIn(email: String, password: String): AuthOutcome
    suspend fun signUp(email: String, password: String, role: Role): AuthOutcome
    suspend fun signOut()
}

/**
 * GoTrue, plus the `users` row that carries the role.
 *
 * The role is a table row rather than a JWT claim: a custom claim needs an Edge Function on signup
 * to write it into the token, which is more moving parts than a two-role demo justifies. The cost is
 * one extra request after sign-in, off the critical path.
 */
class SupabaseAuthRepository(
    private val client: SupabaseClient,
    private val config: SupabaseConfig,
) : AuthRepository {

    override val configured: Boolean get() = config.isConfigured

    override suspend fun signIn(email: String, password: String): AuthOutcome {
        val response = client.postAuth(
            "/token?grant_type=password",
            mapOf("email" to email, "password" to password),
        )
        if (response.offline) return AuthOutcome.Offline
        if (!response.ok) return AuthOutcome.Rejected(response.error ?: "Could not sign in")

        val body = response.asObject() ?: return AuthOutcome.Rejected("Unexpected reply from server")
        val token = body["access_token"] as? String
            ?: return AuthOutcome.Rejected("Sign-in did not return a token")
        val user = body["user"] as? Map<*, *>
        val id = user?.get("id") as? String ?: return AuthOutcome.Rejected("Sign-in returned no user")

        client.accessToken = token
        return AuthOutcome.SignedIn(
            Account(id = id, email = user["email"] as? String ?: email, role = roleOf(id)),
        )
    }

    override suspend fun signUp(email: String, password: String, role: Role): AuthOutcome {
        val response = client.postAuth(
            "/signup",
            mapOf("email" to email, "password" to password),
        )
        if (response.offline) return AuthOutcome.Offline
        if (!response.ok) return AuthOutcome.Rejected(response.error ?: "Could not create the account")

        val body = response.asObject() ?: return AuthOutcome.Rejected("Unexpected reply from server")
        // With email confirmation on, signup returns a user and no session. Signing in afterwards
        // would fail with a confusing message, so say what actually has to happen instead.
        val token = body["access_token"] as? String
        val user = (body["user"] as? Map<*, *>) ?: body
        val id = user["id"] as? String ?: return AuthOutcome.Rejected("Sign-up returned no user")
        if (token == null) {
            return AuthOutcome.Rejected("Account created. Confirm the email, then sign in.")
        }

        client.accessToken = token
        // The role row is written by the signed-in user, so it must come after the token is set -
        // row-level security ties `users.id` to `auth.uid()`, and an anonymous insert is refused.
        val written = client.insert(
            "users",
            listOf(mapOf("id" to id, "email" to email, "role" to role.name.lowercase())),
            upsert = true,
        )
        if (!written.ok && !written.offline) {
            return AuthOutcome.Rejected(written.error ?: "Could not save the account role")
        }
        return AuthOutcome.SignedIn(Account(id, email, role))
    }

    override suspend fun signOut() {
        client.postAuth("/logout", emptyMap())
        client.accessToken = null
    }

    /**
     * Reads the role row. Defaults to patient when it is missing or unreadable.
     *
     * Defaulting to the *less* privileged role is deliberate: a physio wrongly shown the patient
     * flow is inconvenienced, whereas a patient wrongly shown authoring can overwrite the reference
     * their exercises are scored against.
     */
    private suspend fun roleOf(userId: String): Role {
        val response = client.select("users", "?id=eq.$userId&select=role")
        val row = response.asArray().firstOrNull() as? Map<*, *>
        return when (row?.get("role") as? String) {
            "physio" -> Role.PHYSIO
            else -> Role.PATIENT
        }
    }
}

/** Used when no backend is configured: the app stays entirely local, as it was before 7.3. */
object LocalOnlyAuthRepository : AuthRepository {
    override val configured: Boolean = false
    override suspend fun signIn(email: String, password: String) = AuthOutcome.Offline
    override suspend fun signUp(email: String, password: String, role: Role) = AuthOutcome.Offline
    override suspend fun signOut() = Unit
}
