package com.example.rightposture.llm

import android.content.Context
import android.util.Log
import com.example.rightposture.core.summary.StreamingTextGenerator
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * §11.2 and §11.3. Gemma on the phone, through LiteRT-LM, preferring the NPU.
 *
 * ## The delegate ladder, and why it is the same shape as the pose one
 *
 * NPU → GPU → CPU, exactly as `PoseLandmarkerSource` does for landmarks. The measured spread on
 * Qualcomm hardware is wide enough that this is not a micro-optimisation: the Hexagon NPU runs a
 * Gemma-class model at roughly 50-80 tokens/second, the Adreno GPU at 30-50, and the CPU at 4-5.
 * A summary that takes two seconds on the NPU takes most of a minute on the CPU, which is the
 * difference between a feature and a spinner nobody waits for.
 *
 * The NPU path needs `libQnnHtp.so` in the APK's native library directory and a model carrying
 * pre-compiled QNN payloads. Both are things the build either shipped or did not, so the check is
 * a file test rather than a guess.
 *
 * ## Why an NPU attempt is recorded before it is made
 *
 * A failed QNN initialisation does not always throw. On some driver versions it takes the process
 * down natively, and no `try`/`catch` in Kotlin sees that. So the attempt is written to disk first
 * and cleared on success: if the marker is still there at the next launch, the previous run died
 * trying and this run silently starts at GPU instead. It costs one small file and it is the only
 * thing standing between a driver bug and an app that crashes on every launch forever.
 *
 * ## §11.3's exclusion
 *
 * "Never hold both under load simultaneously; the pose pipeline must have the NPU to itself during
 * scoring." [releaseForScoring] is that rule, and [busy] lets the caller check it. A model left
 * resident during a set does not merely slow the pipeline down - it competes for the accelerator
 * the scoring depends on, and the scoring is the part that must not miss frames.
 */
class OnDeviceLlm(
    private val context: Context,
    private val modelFile: File,
    private val generationTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : StreamingTextGenerator {

    /** Which backend actually took the model. Null until [warmUp] has run. */
    @Volatile
    var backend: LlmBackend? = null
        private set

    @Volatile
    private var engine: Engine? = null

    /** One generation at a time. The engine is not safe to drive from two coroutines at once. */
    private val lock = Mutex()

    val isLoaded: Boolean get() = engine != null

    val isAvailable: Boolean get() = modelFile.isFile && modelFile.length() > 0

    /**
     * Loads the model, trying each backend in turn.
     *
     * §11.2: call this in the background at launch. Initialisation can take the better part of ten
     * seconds, and paying that at the moment a physio taps "summarise" makes the app look frozen at
     * precisely the moment they are watching it.
     *
     * Returns the backend that accepted the model, or null if none did - which is not an error
     * condition. §10.5's template covers it, and the app stays useful with no model at all.
     */
    suspend fun warmUp(): LlmBackend? = lock.withLock {
        engine?.let { return@withLock backend }
        if (!isAvailable) {
            Log.i(TAG, "No model at ${modelFile.absolutePath}; summaries will use the template.")
            return@withLock null
        }

        for (candidate in ladder()) {
            val started = System.currentTimeMillis()
            val loaded = withContext(Dispatchers.IO) { tryLoad(candidate) }
            if (loaded == null) {
                Log.w(TAG, "$candidate refused the model; falling back.")
                continue
            }
            // Loading is not the same as working, and this was measured rather than assumed: the
            // GPU rung initialised in 5 seconds and then failed every generation with "Can not
            // find OpenCL library on this device". A ladder that only checks initialisation
            // selects a backend that cannot answer, and every summary silently becomes a template.
            //
            // So the rung has to produce a token before it is accepted. One cheap prompt, once,
            // at warm-up - paid on a background thread the user is not waiting on.
            if (!withContext(Dispatchers.IO) { canGenerate(loaded, candidate) }) {
                runCatching { loaded.close() }
                Log.w(TAG, "$candidate loaded but could not generate; falling back.")
                continue
            }
            engine = loaded
            backend = candidate
            clearAttemptMarker()
            Log.i(TAG, "Loaded on $candidate in ${System.currentTimeMillis() - started} ms")
            return@withLock candidate
        }
        null
    }

    /**
     * The backends worth trying on this device, best first.
     *
     * NPU is dropped when the QNN dispatch library is absent - there is no point paying an
     * initialisation attempt to discover a file is missing - and when the marker says a previous
     * attempt took the process down.
     */
    private fun ladder(): List<LlmBackend> = buildList {
        val npuLibraries = npuLibraryDirectory()
        when {
            npuLibraries == null ->
                Log.i(TAG, "$QNN_DISPATCH_LIBRARY absent; NPU not attempted.")
            attemptMarker().exists() ->
                // The previous run did not come back from this. Trying again would reproduce the
                // crash, and an app that dies on launch is worse than one that runs on the GPU.
                Log.w(TAG, "A previous NPU attempt did not complete; skipping NPU this run.")
            else -> add(LlmBackend.NPU)
        }
        add(LlmBackend.GPU)
        add(LlmBackend.CPU)
    }

    /**
     * Proves a loaded engine can actually produce a token.
     *
     * Deliberately tiny, and deliberately not part of [generate]'s timeout budget: the question is
     * only whether the backend answers at all, not how fast. A backend that throws here is broken
     * for every request that would follow, so finding out now costs one short prompt and saves
     * every summary after it.
     */
    private fun canGenerate(candidate: Engine, rung: LlmBackend): Boolean = try {
        candidate.createConversation().use { conversation ->
            conversation.sendMessage(SMOKE_PROMPT).contents.contents
                .filterIsInstance<Content.Text>()
                .any { it.text.isNotBlank() }
        }
    } catch (failure: Throwable) {
        Log.w(TAG, "$rung failed its generation check", failure)
        false
    }

    private fun tryLoad(candidate: LlmBackend): Engine? {
        val native = npuLibraryDirectory()
        if (candidate == LlmBackend.NPU) {
            if (native == null) return null
            markAttempt()
        }
        return try {
            val chosen = when (candidate) {
                LlmBackend.NPU -> Backend.NPU(nativeLibraryDir = native!!)
                LlmBackend.GPU -> Backend.GPU()
                LlmBackend.CPU -> Backend.CPU()
            }
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = chosen,
                // Gemma 4 E2B is multimodal, and its encoders do not accept every backend the
                // text graph does. Gemma 4 E2B's audio encoder is CPU-only, and asking for GPU
                // there fails the **whole** engine:
                //
                //     Audio backend constraint mismatch. Model requires one of [cpu]
                //     but Audio backend is GPU
                //
                // Measured on device, and it cost the GPU rung until it was found. The text graph
                // is what this app actually runs, so the encoders are pinned to CPU and the
                // ladder's choice applies to the part that does the work.
                visionBackend = Backend.CPU(),
                audioBackend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath,
            )
            Engine(config).also { it.initialize() }
        } catch (failure: Throwable) {
            // Deliberately Throwable: a native loader failure surfaces as UnsatisfiedLinkError or
            // ExceptionInInitializerError, neither of which is an Exception, and neither of which
            // should stop the ladder from trying the next rung.
            Log.w(TAG, "$candidate failed to initialise", failure)
            if (candidate == LlmBackend.NPU) clearAttemptMarker()
            null
        }
    }

    /**
     * Generates text, or null if the model is not loaded or did not finish in time.
     *
     * Null rather than an exception, because the caller's response is the same either way: use the
     * template. §10.5's rule is that the app is never silent, and a timeout is just another way for
     * the model to be unavailable.
     */
    override suspend fun generate(prompt: String): String? = lock.withLock {
        val active = engine ?: return@withLock null
        val started = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(generationTimeoutMs) {
                runCatching {
                    active.createConversation().use { conversation ->
                        conversation.sendMessage(prompt).contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString("") { it.text }
                            .trim()
                            .ifEmpty { null }
                    }
                }.onFailure { Log.w(TAG, "Generation failed", it) }.getOrNull()
            }
        }?.also {
            // §15 scores accelerator use from device data rather than from claims, so the number
            // that demonstrates it is logged rather than left to be inferred from a stopwatch.
            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "Generated ${it.length} chars on $backend in $elapsed ms")
        } ?: run {
            Log.w(TAG, "No text produced within ${generationTimeoutMs} ms on $backend")
            null
        }
    }

    /**
     * The same generation, reported as it is produced.
     *
     * LiteRT-LM hands back a `Flow<Message>` as the model decodes, so this is the model's real
     * output arriving rather than a finished paragraph revealed on a timer. It matters for more
     * than decoration: on the CPU rung a summary takes most of a minute, and a paragraph that is
     * visibly being written is a very different wait from a spinner that might be broken.
     *
     * ## Cumulative or incremental
     *
     * The library does not promise which, and the two produce very different text if guessed
     * wrong - either a paragraph that repeats itself or one missing every word after the first
     * chunk. So each emission is tested rather than assumed: an emission that starts with what we
     * already have replaces it, anything else is appended.
     *
     * The mutex is held for the whole stream, as it is for [generate]. The engine cannot be driven
     * from two coroutines at once, and a stream is a generation that happens to report early.
     */
    override suspend fun generateStreaming(
        prompt: String,
        onPartial: (String) -> Unit,
    ): String? = lock.withLock {
        val active = engine ?: return@withLock null
        val started = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(generationTimeoutMs) {
                runCatching {
                    val builder = StringBuilder()
                    active.createConversation().use { conversation ->
                        val stream: Flow<Message> = conversation.sendMessageAsync(prompt)
                        stream.collect { message ->
                            val chunk = message.contents.contents
                                .filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                            if (chunk.isEmpty()) return@collect
                            if (chunk.startsWith(builder)) {
                                builder.setLength(0)
                                builder.append(chunk)
                            } else {
                                builder.append(chunk)
                            }
                            onPartial(builder.toString())
                        }
                    }
                    builder.toString().trim().ifEmpty { null }
                }.onFailure { Log.w(TAG, "Streaming generation failed", it) }.getOrNull()
            }
        }?.also {
            val elapsed = System.currentTimeMillis() - started
            Log.i(TAG, "Streamed ${it.length} chars on $backend in $elapsed ms")
        } ?: run {
            Log.w(TAG, "No streamed text within ${generationTimeoutMs} ms on $backend")
            null
        }
    }

    /**
     * §11.3. Unloads the model so the pose pipeline has the accelerator to itself.
     *
     * Called before a set starts, not after it ends. The competition that matters is for the NPU
     * during scoring, and by the time a set has finished the damage would already be in the data.
     */
    suspend fun releaseForScoring() = lock.withLock {
        engine?.let {
            runCatching { it.close() }.onFailure { failure -> Log.w(TAG, "close failed", failure) }
        }
        engine = null
    }

    private fun npuLibraryDirectory(): String? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        return if (File(dir, QNN_DISPATCH_LIBRARY).exists()) dir else null
    }

    private fun attemptMarker() = File(context.cacheDir, NPU_ATTEMPT_MARKER)

    private fun markAttempt() {
        runCatching { attemptMarker().writeText(modelFile.name) }
    }

    private fun clearAttemptMarker() {
        runCatching { attemptMarker().delete() }
    }

    companion object {
        private const val TAG = "RPLlm"

        /** The same library the pose delegate ladder gates its NPU attempt on. */
        const val QNN_DISPATCH_LIBRARY = "libQnnHtp.so"

        private const val NPU_ATTEMPT_MARKER = "llm_npu_attempt"

        /** Short enough to cost nothing, open enough that any working model answers it. */
        private const val SMOKE_PROMPT = "Say OK."

        /**
         * §11.2 budgets under 20 s for a much larger cue batch. A summary is three or four
         * sentences, so 30 s is generous even on the CPU rung - and past that the patient is
         * staring at a spinner and the template would have been the better answer.
         */
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /** Where the model is expected. §11.3 keeps it off the APK - it is far too large to ship. */
        fun defaultModelFile(context: Context): File =
            File(context.filesDir, "models/gemma.litertlm")
    }
}

/** The rungs of §11.1's ladder, for the LLM rather than the pose model. */
enum class LlmBackend {
    NPU,
    GPU,
    CPU,
}
