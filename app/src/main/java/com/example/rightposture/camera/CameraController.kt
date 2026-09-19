package com.example.rightposture.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.rightposture.core.pose.CameraFacing
import java.util.concurrent.Executors

/**
 * CameraX preview plus an analysis stream, per section 12.1.
 *
 * Front camera is the default: seeing yourself is a large usability win for rehab and it matches
 * the mirror a physio uses in clinic. Section 6.2 canonicalisation makes front and rear
 * interoperable, so this is a preference rather than a constraint.
 */
class CameraController(
    context: Context,
    private val onFrame: (ImageProxy) -> Unit,
    private val onError: (String) -> Unit,
    private val onAnalyzerClose: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val providerFuture = ProcessCameraProvider.getInstance(appContext)

    private var boundFacing: CameraFacing? = null
    private var boundView: PreviewView? = null
    private var boundRotation: Int? = null

    @Volatile
    var lastFacing: CameraFacing = CameraFacing.FRONT
        private set

    fun createPreviewView() = PreviewView(appContext).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    /**
     * Idempotent for an unchanged (facing, view, rotation) triple, so Compose can call it from
     * every recomposition. CameraX follows [owner]'s lifecycle itself, which is what makes the
     * preview survive background and foreground without rebinding here.
     *
     * [displayRotation] must be a `Surface.ROTATION_*` value read from the current display. It
     * cannot be read off [view] here: at bind time the view is not attached yet, so its display is
     * null and every orientation would silently bind as ROTATION_0.
     */
    fun bind(owner: LifecycleOwner, view: PreviewView, facing: CameraFacing, displayRotation: Int) {
        if (boundFacing == facing && boundView === view && boundRotation == displayRotation) return
        boundFacing = facing
        boundView = view
        boundRotation = displayRotation
        lastFacing = facing

        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()

                // Section 12.1 asks for a 720x1280 analysis stream. The plan's snippet uses
                // setTargetResolution, which is deprecated in the pinned CameraX 1.4.2;
                // ResolutionSelector is the current spelling of the same request. 720p is enough:
                // the pose model downsamples anyway, so more pixels cost latency for no accuracy.
                val resolution = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            // Sensor-oriented, i.e. landscape, not the 720x1280 the plan's snippet
                            // writes. A portrait bound matches nothing the camera advertises, and
                            // the selector then falls back *upwards*: measured 1920x1440 at 15 fps
                            // on RMX3997 before this was corrected.
                            Size(1280, 720),
                            // Prefer to undershoot. Exceeding 720p costs latency for no accuracy,
                            // because the pose model downsamples anyway.
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                        ),
                    )
                    .build()

                // No target rotation on Preview: PreviewView applies the display transform
                // itself, and setting both fights it.
                val preview = Preview.Builder()
                    // Preview and analysis must negotiate the same aspect ratio. Otherwise
                    // landmarks from analysis are scaled over a differently cropped preview.
                    .setResolutionSelector(resolution)
                    .build()
                    .also { it.surfaceProvider = view.surfaceProvider }

                val analysis = ImageAnalysis.Builder()
                    // If we fall behind we want the newest frame, not a backlog. Dropped frames
                    // are handled by timestamp-based timing (section 6.1), not by frame counting.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setResolutionSelector(resolution)
                    // Analysis has no view to correct it, so it does need the rotation, which is
                    // what makes landmark coordinates orientation-consistent for 2.2.
                    .setTargetRotation(displayRotation)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, onFrame) }

                val selector = CameraSelector.Builder()
                    .requireLensFacing(
                        if (facing == CameraFacing.FRONT) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        },
                    )
                    .build()

                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, preview, analysis)
            }.onFailure {
                // Leave nothing bound, so the next bind() retries rather than short-circuiting.
                boundFacing = null
                boundView = null
                boundRotation = null
                onError(it.message ?: "Camera binding failed")
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun close() {
        if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
        boundFacing = null
        boundView = null
        boundRotation = null
        analysisExecutor.execute(onAnalyzerClose)
        analysisExecutor.shutdown()
    }
}
