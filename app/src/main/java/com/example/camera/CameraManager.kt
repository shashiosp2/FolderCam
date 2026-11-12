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
import androidx.lifecycle.LifecycleOwner
import java.io.File

class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null

    var isFrontCamera: Boolean = false
        private set

    var isTorchEnabled: Boolean = false
        private set

    fun initialize(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onInitialized: () -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera(lifecycleOwner, previewView)
                onInitialized()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        isFrontCamera = !isFrontCamera
        isTorchEnabled = false
        bindCamera(lifecycleOwner, previewView)
    }

    fun toggleTorch(enabled: Boolean) {
        isTorchEnabled = enabled
        camera?.cameraControl?.enableTorch(enabled)
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        try {
            provider.unbindAll()

            val cameraSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val preview = Preview.Builder().build().also {
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
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )
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
}
