package com.example.rightposture.sync

import com.example.rightposture.core.rpose.jsonParse
import com.example.rightposture.core.rpose.jsonWrite
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * A thin typed client over Supabase's REST surface: GoTrue for auth, PostgREST for data.
 *
 * ## Why not the Supabase Kotlin SDK
 *
 * `supabase-kt` 3.8.0 forces `kotlin-stdlib` to **2.4.0**, and this project's KSP (2.0.21-1.0.28,
 * needed by Room) cannot read 2.4.0 metadata - it fails with `[MissingType]` on the `@Database`
 * class before any of our code is compiled. Without it the toolchain settles on the 2.3.21 stdlib
 * that AGP 9's built-in Kotlin supplies, and everything builds. Bisected 2026-09-05.
 *
 * Rather than fight the toolchain, this talks to the same HTTP API the SDK wraps. Supabase *is*
 * PostgREST and GoTrue over HTTP; the surface we need is small. It also removes a dependency that
 * was already flagged as a risk when the backend was chosen - `supabase-kt` is community
 * maintained, not first-party - and it makes every call mockable with Ktor's `MockEngine`, so the
 * sync layer is testable in the JVM suite with no network and no project.
 *
 * ## Auth model
 *
 * The `apikey` header is always the anon key. `Authorization` carries the anon key when signed out
 * and the user's access token once signed in, which is what makes row-level security apply to the
 * right person - RLS reads `auth.uid()` from that token, so sending the anon key where a user token
 * belongs silently returns nothing rather than failing loudly.
 */
class SupabaseClient(
    private val config: SupabaseConfig,
    engine: HttpClientEngine? = null,
    private val http: HttpClient = engine?.let { HttpClient(it) } ?: HttpClient(),
) {

    /** The signed-in user's access token, or null. Set by [SupabaseAuth]. */
    @Volatile
    var accessToken: String? = null

    val configured: Boolean get() = config.isConfigured

    suspend fun postAuth(path: String, body: Map<String, Any?>): SupabaseResponse =
        request { http.post(config.url + AUTH + path) { common(); setBody(jsonWrite(body)) } }

    suspend fun select(table: String, query: String = ""): SupabaseResponse =
        request { http.get(config.url + REST + table + query) { common() } }

    suspend fun insert(
        table: String,
        rows: List<Map<String, Any?>>,
        upsert: Boolean = false,
    ): SupabaseResponse = request {
        http.post(config.url + REST + table) {
            common()
            // PostgREST's upsert. `merge-duplicates` is what makes §7.5's retry idempotent over
            // the wire, the same way the natural key makes it idempotent in Room.
            if (upsert) header("Prefer", "resolution=merge-duplicates,return=minimal")
            else header("Prefer", "return=minimal")
            setBody(jsonWrite(rows))
        }
    }

    suspend fun update(
        table: String,
        query: String,
        values: Map<String, Any?>,
    ): SupabaseResponse = request {
        http.patch(config.url + REST + table + query) {
            common()
            header("Prefer", "return=minimal")
            setBody(jsonWrite(values))
        }
    }

    suspend fun delete(table: String, query: String): SupabaseResponse =
        request { http.delete(config.url + REST + table + query) { common() } }

    /**
     * Calls a Postgres function.
     *
     * Used where a single statement has to be atomic, or has to see more than the caller's policies
     * allow - §0's invite redemption is both. The reply is shaped like any other PostgREST reply,
     * so [SupabaseResponse.asArray] reads it.
     */
    suspend fun rpc(function: String, args: Map<String, Any?> = emptyMap()): SupabaseResponse =
        request { http.post(config.url + RPC + function) { common(); setBody(jsonWrite(args)) } }

    /** Uploads bytes to a Storage bucket. Used for `.rpose` bundles and `demo.mp4`. */
    suspend fun upload(
        bucket: String,
        path: String,
        bytes: ByteArray,
        contentType: String,
    ): SupabaseResponse = request {
        http.post("${config.url}$STORAGE$bucket/$path") {
            header("apikey", config.anonKey)
            header("Authorization", "Bearer ${accessToken ?: config.anonKey}")
            header("Content-Type", contentType)
            // Storage rejects a repeat upload unless told to replace, and a retried sync is a
            // repeat upload by definition.
            header("x-upsert", "true")
            setBody(bytes)
        }
    }

    /**
     * Fetches an object from a Storage bucket.
     *
     * Returns bytes rather than a [SupabaseResponse] because the body is not JSON and an error body
     * is not worth parsing here - null means "could not fetch", and the caller's only sane response
     * to that is to leave the exercise unavailable and try again later.
     */
    suspend fun download(bucket: String, path: String): ByteArray? = try {
        if (!config.isConfigured) {
            null
        } else {
            val response = http.get("${config.url}$STORAGE$bucket/$path") {
                header("apikey", config.anonKey)
                header("Authorization", "Bearer ${accessToken ?: config.anonKey}")
            }
            if (response.status.isSuccess()) response.body<ByteArray>() else null
        }
    } catch (failure: Throwable) {
        null
    }

    private fun io.ktor.client.request.HttpRequestBuilder.common() {
        header("apikey", config.anonKey)
        header("Authorization", "Bearer ${accessToken ?: config.anonKey}")
        contentType(ContentType.Application.Json)
    }

    /**
     * Every call funnels through here so a network failure is a value, never an exception crossing
     * into a coroutine that was not expecting one. §13.3's offline-first rule means "the server is
     * unreachable" is an ordinary outcome, not an error condition.
     */
    private suspend fun request(call: suspend () -> HttpResponse): SupabaseResponse = try {
        if (!config.isConfigured) {
            SupabaseResponse(0, "", "Supabase is not configured on this build")
        } else {
            val response = call()
            val text = response.bodyAsText()
            SupabaseResponse(
                status = response.status.value,
                body = text,
                error = if (response.status.isSuccess()) null else errorMessage(text, response.status.value),
            )
        }
    } catch (failure: Throwable) {
        SupabaseResponse(0, "", failure.message ?: "Network unavailable")
    }

    /** GoTrue and PostgREST report failures differently; both are JSON objects with a message. */
    private fun errorMessage(body: String, status: Int): String {
        val parsed = runCatching { jsonParse(body) as? Map<*, *> }.getOrNull()
        val message = listOf("msg", "message", "error_description", "error")
            .firstNotNullOfOrNull { parsed?.get(it) as? String }
        return message ?: "Request failed ($status)"
    }

    private companion object {
        const val AUTH = "/auth/v1"
        const val REST = "/rest/v1/"
        const val RPC = "/rest/v1/rpc/"
        const val STORAGE = "/storage/v1/object/"
    }
}

/**
 * The outcome of one call.
 *
 * [status] 0 means the request never reached the server, which §13.3 treats as ordinary: the work
 * stays queued and is retried, rather than being reported to the patient as a failure.
 */
data class SupabaseResponse(val status: Int, val body: String, val error: String?) {
    val ok: Boolean get() = error == null
    val offline: Boolean get() = status == 0

    fun asObject(): Map<*, *>? = runCatching { jsonParse(body) as? Map<*, *> }.getOrNull()
    fun asArray(): List<*> = runCatching { jsonParse(body) as? List<*> }.getOrNull() ?: emptyList<Any>()
}
