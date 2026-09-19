package com.example.rightposture.pose

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

enum class PoseDelegate { NPU, GPU, CPU }

data class PoseFrameResult(
    val frame: com.example.rightposture.core.pose.LandmarkFrame,
    val timestampNs: Long,
    val inputWidth: Int,
    val inputHeight: Int,
    val inferenceMs: Long,
)

class PoseLandmarkerSource(
    context: Context,
    private val onResult: (PoseFrameResult?) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private data class Pending(val width: Int, val height: Int, val startedMs: Long)

    private val pending = linkedMapOf<Long, Pending>()

    @Volatile
    var delegate: PoseDelegate? = null
        private set
    val model = "Lite"
    private var landmarker: PoseLandmarker? = null

    /**
     * §4.1's delegate ladder: NPU, then GPU, then CPU, each falling through on failure.
     *
     * §11.1 wants the Hexagon NPU for the pose model so the GPU stays free for the overlay and the
     * NPU-resident LLM (§10) is not competing with pose for the same silicon. The ladder is written
     * so that adding the QNN libraries is the only step needed to switch paths - there is no code
     * change waiting behind them.
     *
     * NPU is only *attempted* when [npuLibraryDirectory] finds the dispatch library, because
     * MediaPipe's NpuOptions requires it and a missing one throws on every cold start otherwise.
     */
    private fun initialise() {
        if (landmarker != null) return
        val attempts = buildList {
            npuLibraryDirectory()?.let { directory ->
                add(PoseDelegate.NPU to { create(Delegate.NPU, directory) })
            }
            add(PoseDelegate.GPU to { create(Delegate.GPU) })
            add(PoseDelegate.CPU to { create(Delegate.CPU) })
        }
        for ((which, build) in attempts) {
            val attempt = runCatching(build)
            attempt.getOrNull()?.let {
                delegate = which
                landmarker = it
                Log.i(TAG, "Pose delegate: $which")
                return
            }
            Log.w(TAG, "$which pose delegate unavailable; trying the next one", attempt.exceptionOrNull())
        }
        // CPU is the last rung and is always available, so reaching here means the model itself is
        // unreadable rather than the delegate being unsupported. Fail loudly rather than silently
        // running with no pose.
        error("No pose delegate could be created, including CPU")
    }

    /**
     * The directory holding the QNN dispatch library, or null when this build does not carry them.
     *
     * The libraries live in the APK's own native library directory. The device's copies under
     * `/vendor/lib64/hw` cannot be used: they are absent from `public.libraries.txt`, so the app
     * linker namespace refuses to open them, and pointing MediaPipe at that path fails at dlopen.
     * Shipping them means adding the Qualcomm AI Engine Direct binaries to `jniLibs` per ABI.
     */
    private fun npuLibraryDirectory(): String? {
        val directory = appContext.applicationInfo.nativeLibraryDir ?: return null
        val dispatch = java.io.File(directory, QNN_DISPATCH_LIBRARY)
        return if (dispatch.exists()) directory else null
    }

    fun detect(image: ImageProxy, mirrored: Boolean) {
        runCatching(::initialise).onFailure {
            image.close()
            onError(it.message ?: "Pose model failed to load")
            return
        }
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val rotation = image.imageInfo.rotationDegrees
        val timestampMs = image.imageInfo.timestamp / 1_000_000L
        image.use { bitmap.copyPixelsFromBuffer(it.planes[0].buffer) }
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirrored) postScale(-1f, 1f)
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        synchronized(pending) {
            pending[timestampMs] = Pending(oriented.width, oriented.height, SystemClock.uptimeMillis())
            while (pending.size > MAX_PENDING_FRAMES) pending.remove(pending.keys.first())
        }
        landmarker!!.detectAsync(BitmapImageBuilder(oriented).build(), timestampMs)
    }

    override fun close() {
        landmarker?.close()
        landmarker = null
        synchronized(pending) { pending.clear() }
    }

    private fun create(delegate: Delegate, npuLibraryDirectory: String? = null) = PoseLandmarker.createFromOptions(
        appContext,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL)
                    .setDelegate(delegate)
                    .apply {
                        // Both directories are mandatory on NpuOptions, and MediaPipe ships
                        // neither - they come from the Qualcomm AI Engine Direct SDK.
                        if (npuLibraryDirectory != null) {
                            setDelegateOptions(
                                BaseOptions.DelegateOptions.NpuOptions.builder()
                                    .setDispatchLibraryDirectory(npuLibraryDirectory)
                                    .setCompilerPluginLibraryDirectory(npuLibraryDirectory)
                                    .build(),
                            )
                        }
                    }
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setResultListener { result, _ ->
                val timestamp = result.timestampMs()
                val pendingFrame = synchronized(pending) { pending.remove(timestamp) } ?: return@setResultListener
                val world = result.worldLandmarks().firstOrNull()
                val image = result.landmarks().firstOrNull()
                onResult(
                    if (world == null || image == null) null else mapPoseFrame(
                        world.map { it.raw() },
                        image.map { it.raw() },
                    )?.let {
                        PoseFrameResult(
                            it,
                            timestamp * 1_000_000L,
                            pendingFrame.width,
                            pendingFrame.height,
                            SystemClock.uptimeMillis() - pendingFrame.startedMs,
                        )
                    },
                )
            }
            .setErrorListener {
                synchronized(pending) { pending.clear() }
                onError(it.message ?: "Pose inference failed")
            }
            .build(),
    )

    private fun Landmark.raw() = RawPoseLandmark(x(), y(), z())
    private fun NormalizedLandmark.raw() = RawPoseLandmark(
        x(), y(), z(), visibility().orElse(0f), presence().orElse(0f),
    )

    private companion object {
        const val TAG = "RightPosturePose"
        const val MODEL = "pose_landmarker_lite.task"
        const val MAX_PENDING_FRAMES = 120

        /** QNN's HTP dispatch library. Its presence is what says this build can try the NPU. */
        const val QNN_DISPATCH_LIBRARY = "libQnnHtp.so"
    }
}
