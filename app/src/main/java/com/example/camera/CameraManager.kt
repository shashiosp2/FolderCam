package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.util.Range
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class LensOption(
    val id: String,
    val label: String,
    val name: String,
    val targetZoomRatio: Float,
    val customCameraSelector: CameraSelector? = null
)

data class CameraUiState(
    val isFrontCamera: Boolean = false,
    val isTorchEnabled: Boolean = false,
    val currentZoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 1.0f,
    val lensOptions: List<LensOption> = listOf(
        LensOption(id = "1x", label = "1x", name = "Wide", targetZoomRatio = 1.0f)
    ),
    val selectedLensId: String = "1x",
    val photoResolution: String = "12MP",
    val videoResolution: String = "4K 30fps"
)

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

    companion object {
        // Default 12 Megapixel (4000 x 3000 = 12,000,000 pixels, 4:3 sensor aspect ratio)
        val DEFAULT_PHOTO_12MP_SIZE = Size(4000, 3000)
        // Default 30 FPS range for 4K video recording
        val DEFAULT_VIDEO_30FPS_RANGE = Range(30, 30)
    }

    private val backgroundLifecycleOwner = BackgroundCameraLifecycleOwner()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var lastPreviewView: PreviewView? = null
    private var activeCustomSelector: CameraSelector? = null
    private var isCameraBound = false

    private val _cameraState = MutableStateFlow(CameraUiState())
    val cameraState: StateFlow<CameraUiState> = _cameraState.asStateFlow()

    var isFrontCamera: Boolean = false
        private set

    var isTorchEnabled: Boolean = false
        private set

    fun attachPreviewView(
        previewView: PreviewView,
        onInitialized: () -> Unit = {}
    ) {
        lastPreviewView = previewView
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        backgroundLifecycleOwner.startAndResume()

        if (cameraProvider != null && isCameraBound) {
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
        activeCustomSelector = null
        bindCamera(previewView)
    }

    fun selectLens(option: LensOption) {
        val currentPreview = lastPreviewView
        if (option.customCameraSelector != null && option.customCameraSelector != activeCustomSelector) {
            if (!isRecordingVideo() && currentPreview != null) {
                activeCustomSelector = option.customCameraSelector
                bindCamera(currentPreview, initialZoom = option.targetZoomRatio, selectedLensId = option.id)
            }
            return
        }

        if (activeCustomSelector != null && option.customCameraSelector == null && !isRecordingVideo() && currentPreview != null) {
            activeCustomSelector = null
            bindCamera(currentPreview, initialZoom = option.targetZoomRatio, selectedLensId = option.id)
            return
        }

        setZoomRatio(option.targetZoomRatio, explicitLensId = option.id)
    }

    fun setZoomRatio(ratio: Float, explicitLensId: String? = null) {
        val state = _cameraState.value
        val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(clamped)

        val matchedLensId = explicitLensId ?: state.lensOptions.minByOrNull {
            abs(it.targetZoomRatio - clamped)
        }?.takeIf { abs(it.targetZoomRatio - clamped) < 0.15f }?.id ?: state.selectedLensId

        _cameraState.value = state.copy(
            currentZoomRatio = clamped,
            selectedLensId = matchedLensId
        )
    }

    fun applyPinchZoom(zoomChange: Float) {
        val current = _cameraState.value.currentZoomRatio
        setZoomRatio(current * zoomChange)
    }

    fun toggleTorch(enabled: Boolean) {
        isTorchEnabled = enabled
        camera?.cameraControl?.enableTorch(enabled)
        _cameraState.value = _cameraState.value.copy(isTorchEnabled = enabled)
    }

    private fun bindCamera(
        previewView: PreviewView,
        initialZoom: Float = 1.0f,
        selectedLensId: String = "1x"
    ) {
        val provider = cameraProvider ?: return
        backgroundLifecycleOwner.startAndResume()

        try {
            provider.unbindAll()

            val cameraSelector = activeCustomSelector ?: if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            preview = Preview.Builder()
                .setTargetFrameRate(DEFAULT_VIDEO_30FPS_RANGE)
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            // Default 12 Megapixel (4000x3000) Photo ResolutionSelector
            val photo12MpResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        DEFAULT_PHOTO_12MP_SIZE,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(photo12MpResolutionSelector)
                .build()

            // Default 4K UHD (3840x2160) Video QualitySelector with graceful fallback if hardware is lower
            val video4kQualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.higherQualityOrLowerThan(Quality.UHD)
            )

            val recorder = Recorder.Builder()
                .setQualitySelector(video4kQualitySelector)
                .build()

            // Default 4K @ 30 FPS VideoCapture
            videoCapture = VideoCapture.Builder(recorder)
                .setTargetFrameRate(DEFAULT_VIDEO_30FPS_RANGE)
                .build()

            val boundCamera = provider.bindToLifecycle(
                backgroundLifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                videoCapture
            )
            camera = boundCamera
            isCameraBound = true

            // Inspect zoom range & available lenses (Ultrawide -> Wide -> Zoom/Telephoto)
            val zoomState = boundCamera.cameraInfo.zoomState.value
            val minZoom = zoomState?.minZoomRatio ?: 1.0f
            val maxZoom = zoomState?.maxZoomRatio ?: 8.0f

            val discoveredLenses = buildLensOptions(
                provider = provider,
                isFront = isFrontCamera,
                minZoom = minZoom,
                maxZoom = maxZoom
            )

            val targetZoom = initialZoom.coerceIn(minZoom, maxZoom)
            if (targetZoom != 1.0f) {
                boundCamera.cameraControl.setZoomRatio(targetZoom)
            }

            _cameraState.value = CameraUiState(
                isFrontCamera = isFrontCamera,
                isTorchEnabled = isTorchEnabled,
                currentZoomRatio = targetZoom,
                minZoomRatio = minZoom,
                maxZoomRatio = maxZoom,
                lensOptions = discoveredLenses,
                selectedLensId = selectedLensId,
                photoResolution = "12MP",
                videoResolution = "4K 30fps"
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Builds the list of lens options from Ultrawide (0.5x/0.6x) to Wide (1x) to Telephoto/Zoom (2x, 3x, 5x, 10x).
     * Also checks Camera2 characteristics for standalone physical ultrawide/telephoto cameras.
     */
    private fun buildLensOptions(
        provider: ProcessCameraProvider,
        isFront: Boolean,
        minZoom: Float,
        maxZoom: Float
    ): List<LensOption> {
        val options = mutableListOf<LensOption>()

        // 1. Ultrawide lens (if supported via logical zoom ratio < 1.0f, e.g., 0.5x or 0.6x)
        if (minZoom < 0.95f) {
            val uwLabel = if (minZoom <= 0.55f) "0.5x" else String.format(Locale.US, "%.1fx", minZoom)
            options.add(
                LensOption(
                    id = "uw",
                    label = uwLabel,
                    name = "Ultrawide",
                    targetZoomRatio = minZoom
                )
            )
        } else if (!isFront && hasStandaloneUltrawideCamera()) {
            // Check if device exposes a standalone back camera in availableCameraInfos
            val backCameras = provider.availableCameraInfos.filter {
                it.lensFacing == CameraSelector.LENS_FACING_BACK
            }
            if (backCameras.size > 1) {
                val secondaryInfo = backCameras[1]
                val customSelector = CameraSelector.Builder()
                    .addCameraFilter { infos -> infos.filter { it == secondaryInfo } }
                    .build()
                options.add(
                    LensOption(
                        id = "uw",
                        label = "0.5x",
                        name = "Ultrawide",
                        targetZoomRatio = 1.0f,
                        customCameraSelector = customSelector
                    )
                )
            }
        }

        // 2. Main Wide lens (1x)
        options.add(
            LensOption(
                id = "1x",
                label = "1x",
                name = "Wide",
                targetZoomRatio = 1.0f
            )
        )

        // 3. 2x Optical / Sensor Crop Zoom
        if (maxZoom >= 2.0f) {
            options.add(
                LensOption(
                    id = "2x",
                    label = "2x",
                    name = "Zoom 2x",
                    targetZoomRatio = 2.0f
                )
            )
        }

        // 4. 3x Telephoto lens
        if (maxZoom >= 3.0f) {
            options.add(
                LensOption(
                    id = "3x",
                    label = "3x",
                    name = "Telephoto 3x",
                    targetZoomRatio = 3.0f
                )
            )
        }

        // 5. 5x Telephoto / Periscope lens
        if (maxZoom >= 5.0f) {
            options.add(
                LensOption(
                    id = "5x",
                    label = "5x",
                    name = "Telephoto 5x",
                    targetZoomRatio = 5.0f
                )
            )
        }

        // 6. 10x Super Zoom
        if (maxZoom >= 10.0f) {
            options.add(
                LensOption(
                    id = "10x",
                    label = "10x",
                    name = "Super Zoom 10x",
                    targetZoomRatio = 10.0f
                )
            )
        }

        return options
    }

    private fun hasStandaloneUltrawideCamera(): Boolean {
        return try {
            val sysCameraManager = context.getSystemService(Context.CAMERA_SERVICE) as SystemCameraManager
            val backFocalLengths = mutableListOf<Float>()
            for (id in sysCameraManager.cameraIdList) {
                val chars = sysCameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    focals?.forEach { backFocalLengths.add(it) }
                }
            }
            backFocalLengths.distinct().size > 1
        } catch (e: Exception) {
            false
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
