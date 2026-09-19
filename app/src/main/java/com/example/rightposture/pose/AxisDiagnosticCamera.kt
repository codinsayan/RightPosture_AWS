package com.example.rightposture.pose

import com.example.rightposture.core.pose.Vec3
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.concurrent.Executors
import java.io.File
import java.io.FileOutputStream

class AxisDiagnosticCamera(
    context: Context,
    private val onReading: (AxisReading) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val providerFuture = ProcessCameraProvider.getInstance(appContext)
    private var boundFacing: Int? = null
    @Volatile private var lastInputFrame: Bitmap? = null
    private val landmarker = PoseLandmarker.createFromOptions(
        appContext,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("pose_landmarker_full.task")
                    .setDelegate(Delegate.CPU)
                    .build(),
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(1)
            .setResultListener { result, inputImage ->
                val world = result.worldLandmarks().firstOrNull()
                val image = result.landmarks().firstOrNull()
                if (world == null || image == null) {
                    onError("No pose detected. Keep shoulders and wrists visible.")
                    return@setResultListener
                }
                if (world.size <= RIGHT_WRIST || image.size <= RIGHT_WRIST) return@setResultListener
                val wrist = world[RIGHT_WRIST]
                val shoulder = world[RIGHT_SHOULDER]
                val leftWrist = world[LEFT_WRIST]
                val leftShoulder = world[LEFT_SHOULDER]
                val wristImage = image[RIGHT_WRIST]
                onReading(
                    AxisReading(
                        wristWorld = Vec3(wrist.x(), wrist.y(), wrist.z()),
                        shoulderWorld = Vec3(shoulder.x(), shoulder.y(), shoulder.z()),
                        leftWristWorld = Vec3(leftWrist.x(), leftWrist.y(), leftWrist.z()),
                        leftShoulderWorld = Vec3(leftShoulder.x(), leftShoulder.y(), leftShoulder.z()),
                        wristImage = Vec3(wristImage.x(), wristImage.y(), wristImage.z()),
                        imageLandmarks = image.map { Vec3(it.x(), it.y(), it.z()) },
                        imageWidth = inputImage.width,
                        imageHeight = inputImage.height,
                    ),
                )
            }
            .setErrorListener { onError(it.message ?: "Pose detection failed") }
            .build(),
    )

    fun createPreviewView() = PreviewView(appContext).apply {
        scaleType = PreviewView.ScaleType.FIT_CENTER
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
    }

    fun bind(owner: LifecycleOwner, view: PreviewView, front: Boolean) {
        val facing = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        if (boundFacing == facing) return
        boundFacing = facing
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { useCase -> useCase.setAnalyzer(executor) { analyze(it, front) } }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.Builder().requireLensFacing(facing).build(), preview, analysis)
            }.onFailure { onError(it.message ?: "Camera binding failed") }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun analyze(image: ImageProxy, mirror: Boolean) {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        val rotation = image.imageInfo.rotationDegrees
        image.use { bitmap.copyPixelsFromBuffer(it.planes[0].buffer) }
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            if (mirror) postScale(-1f, 1f, image.width.toFloat(), image.height.toFloat())
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        lastInputFrame = oriented
        landmarker.detectAsync(BitmapImageBuilder(oriented).build(), SystemClock.uptimeMillis())
    }

    fun saveCurrentFrame(name: String): File? {
        val bitmap = lastInputFrame ?: return null
        val directory = File(appContext.getExternalFilesDir(null), "axis-diagnostic").apply { mkdirs() }
        val file = File(directory, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun close() {
        if (providerFuture.isDone) providerFuture.get().unbindAll()
        landmarker.close()
        executor.shutdown()
    }

    private companion object {
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
    }
}
