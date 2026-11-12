package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File

/**
 * Custom LifecycleOwner that stays in RESUMED state so CameraX VideoCapture
 * continues recording in the background when the user navigates to the Home screen
 * while RecordingForegroundService is active.
 */
class BackgroundCameraLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun startAndResume() {
        if (registry.currentState != Lifecycle.State.RESUMED) {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }

    override val lifecycle: Lifecycle
        get() = registry
}

class CameraManager(private val context: Context) {

    private val backgroundLifecycleOwner = BackgroundCameraLifecycleOwner()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var isCameraBound = false

    var isFrontCamera: Boolean = false
        private set

    var isTorchEnabled: Boolean = false
        private set

    fun attachPreviewView(
        previewView: PreviewView,
        onInitialized: () -> Unit = {}
    ) {
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        backgroundLifecycleOwner.startAndResume()

        if (cameraProvider != null && isCameraBound) {
            // Re-attach surfaceProvider without interrupting active VideoCapture
            preview?.surfaceProvider = previewView.surfaceProvider
            onInitialized()
            return
        }

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera(previewView)
                onInitialized()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(previewView: PreviewView) {
        if (isRecordingVideo()) return
        isFrontCamera = !isFrontCamera
        isTorchEnabled = false
        bindCamera(previewView)
    }

    fun toggleTorch(enabled: Boolean) {
        isTorchEnabled = enabled
        camera?.cameraControl?.enableTorch(enabled)
    }

    private fun bindCamera(previewView: PreviewView) {
        val provider = cameraProvider ?: return
        backgroundLifecycleOwner.startAndResume()

        try {
            provider.unbindAll()

            val cameraSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            camera = provider.bindToLifecycle(
                backgroundLifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )
            isCameraBound = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun takePhoto(
        outputFile: File,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture ?: run {
            onError("Camera capture not ready")
            return
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onSuccess(outputFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception.message ?: "Photo capture failed")
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    fun startVideoRecording(
        outputFile: File,
        enableAudio: Boolean = true,
        onVideoStarted: () -> Unit = {},
        onVideoSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = videoCapture ?: run {
            onError("Video capture not ready")
            return
        }

        backgroundLifecycleOwner.startAndResume()
        val outputOptions = FileOutputOptions.Builder(outputFile).build()

        val pendingRecording = capture.output.prepareRecording(context, outputOptions)
        if (enableAudio) {
            try {
                pendingRecording.withAudioEnabled()
            } catch (e: SecurityException) {
                // Audio permission might not be granted, continue video-only
            }
        }

        currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    onVideoStarted()
                }
                is VideoRecordEvent.Finalize -> {
                    if (!event.hasError()) {
                        onVideoSaved(outputFile)
                    } else {
                        val errorMsg = event.cause?.message ?: "Video recording failed (${event.error})"
                        onError(errorMsg)
                    }
                    currentRecording = null
                }
            }
        }
    }

    fun stopVideoRecording() {
        currentRecording?.stop()
        currentRecording = null
    }

    fun isRecordingVideo(): Boolean = currentRecording != null

    fun release() {
        stopVideoRecording()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            // Ignore
        }
        backgroundLifecycleOwner.destroy()
        isCameraBound = false
    }
}
