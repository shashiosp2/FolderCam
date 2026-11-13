package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.util.Range
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
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
import com.example.model.RecorderMode
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
    val videoResolution: String = "4K 30fps",
    val cameraError: String? = null
)

/**
 * Custom LifecycleOwner that stays in RESUMED state while the app is foregrounded OR while
 * VideoCapture is actively recording in the background via RecordingForegroundService.
 * When the app goes to background while idle, it transitions back to CREATED so Android
 * cleanly closes the camera hardware instead of killing the session.
 */
class BackgroundCameraLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun startAndResume() {
        moveToResumed()
    }

    fun moveToResumed() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        if (registry.currentState != Lifecycle.State.RESUMED) {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun moveToCreated() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        if (registry.currentState != Lifecycle.State.CREATED) {
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    fun destroy() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
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

    val backgroundLifecycleOwner = BackgroundCameraLifecycleOwner()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    private var isRecordingActiveOrFinalizing: Boolean = false

    private var lastPreviewView: PreviewView? = null
    private var boundPreviewView: PreviewView? = null
    private var activeCustomSelector: CameraSelector? = null
    private var isCameraBound = false
    private var boundHasImageCapture = false
    private var boundHasVideoCapture = false

    private var isHostForeground = true
    private var activeMode: RecorderMode = RecorderMode.AUDIO
    private var isProviderRequestInFlight = false

    private val _cameraState = MutableStateFlow(CameraUiState())
    val cameraState: StateFlow<CameraUiState> = _cameraState.asStateFlow()

    var isFrontCamera: Boolean = false
        private set

    var isTorchEnabled: Boolean = false
        private set

    fun onHostStop() {
        isHostForeground = false
        if (!isRecordingVideo()) {
            unbindCameraInternal()
        }
    }

    fun onHostResume(previewView: PreviewView?, mode: RecorderMode) {
        isHostForeground = true
        activeMode = mode
        if (mode == RecorderMode.AUDIO && !isRecordingVideo()) {
            unbindCameraInternal()
            return
        }
        if (previewView != null) {
            bindOrAttachPreview(previewView, mode)
        }
    }

    fun unbindCameraIfIdle() {
        if (!isRecordingVideo()) {
            unbindCameraInternal()
        }
    }

    fun attachPreviewView(
        previewView: PreviewView,
        onInitialized: () -> Unit = {}
    ) {
        val targetMode = if (activeMode == RecorderMode.AUDIO) RecorderMode.PHOTO else activeMode
        bindOrAttachPreview(previewView, targetMode, onInitialized)
    }

    fun bindOrAttachPreview(
        previewView: PreviewView,
        mode: RecorderMode,
        onInitialized: () -> Unit = {}
    ) {
        activeMode = mode
        lastPreviewView = previewView

        if (mode == RecorderMode.AUDIO && !isRecordingVideo()) {
            unbindCameraInternal()
            return
        }

        // If actively recording video (e.g., returned from background), keep VideoCapture alive
        // and only re-attach surfaceProvider if the PreviewView instance changed.
        if (isRecordingVideo() && cameraProvider != null && isCameraBound) {
            backgroundLifecycleOwner.moveToResumed()
            if (boundPreviewView !== previewView) {
                preview?.surfaceProvider = previewView.surfaceProvider
                boundPreviewView = previewView
            }
            onInitialized()
            return
        }

        if (!isHostForeground) {
            return
        }

        val modeSupported = (mode == RecorderMode.PHOTO && boundHasImageCapture) ||
            (mode == RecorderMode.VIDEO && boundHasVideoCapture)

        if (cameraProvider != null &&
            isCameraBound &&
            camera != null &&
            modeSupported &&
            boundPreviewView === previewView
        ) {
            backgroundLifecycleOwner.moveToResumed()
            onInitialized()
            return
        }

        val existingProvider = cameraProvider
        if (existingProvider != null) {
            bindCamera(previewView, mode)
            onInitialized()
            return
        }

        if (isProviderRequestInFlight) return
        isProviderRequestInFlight = true

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            isProviderRequestInFlight = false
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                val latestPreview = lastPreviewView ?: previewView
                if (isHostForeground && activeMode != RecorderMode.AUDIO) {
                    bindCamera(latestPreview, activeMode)
                    onInitialized()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _cameraState.value = _cameraState.value.copy(
                    cameraError = e.message ?: "Failed to initialize camera provider"
                )
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera(previewView: PreviewView) {
        if (isRecordingVideo()) return
        isFrontCamera = !isFrontCamera
        isTorchEnabled = false
        activeCustomSelector = null
        val mode = if (activeMode == RecorderMode.AUDIO) RecorderMode.PHOTO else activeMode
        bindCamera(previewView, mode)
    }

    fun selectLens(option: LensOption) {
        val currentPreview = lastPreviewView
        val mode = if (activeMode == RecorderMode.AUDIO) RecorderMode.PHOTO else activeMode
        if (option.customCameraSelector != null && option.customCameraSelector != activeCustomSelector) {
            if (!isRecordingVideo() && currentPreview != null) {
                activeCustomSelector = option.customCameraSelector
                bindCamera(
                    previewView = currentPreview,
                    mode = mode,
                    initialZoom = option.targetZoomRatio,
                    selectedLensId = option.id
                )
            }
            return
        }

        if (activeCustomSelector != null &&
            option.customCameraSelector == null &&
            !isRecordingVideo() &&
            currentPreview != null
        ) {
            activeCustomSelector = null
            bindCamera(
                previewView = currentPreview,
                mode = mode,
                initialZoom = option.targetZoomRatio,
                selectedLensId = option.id
            )
            return
        }

        setZoomRatio(option.targetZoomRatio, explicitLensId = option.id)
    }

    fun setZoomRatio(ratio: Float, explicitLensId: String? = null) {
        val state = _cameraState.value
        val clamped = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        try {
            camera?.cameraControl?.setZoomRatio(clamped)
        } catch (e: Exception) {
            // Ignore if camera is temporarily unbound
        }

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
        try {
            camera?.cameraControl?.enableTorch(enabled)
        } catch (e: Exception) {
            // Ignore if camera lacks torch or is unbound
        }
        _cameraState.value = _cameraState.value.copy(isTorchEnabled = enabled)
    }

    private fun unbindCameraInternal() {
        try {
            preview?.surfaceProvider = null
        } catch (e: Exception) {
            // Ignore
        }
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            // Ignore
        }
        backgroundLifecycleOwner.moveToCreated()
        camera = null
        preview = null
        imageCapture = null
        videoCapture = null
        boundPreviewView = null
        isCameraBound = false
        boundHasImageCapture = false
        boundHasVideoCapture = false
    }

    private fun bindCamera(
        previewView: PreviewView,
        mode: RecorderMode,
        initialZoom: Float = 1.0f,
        selectedLensId: String = "1x"
    ) {
        val provider = cameraProvider ?: return
        if (isRecordingVideo()) return

        backgroundLifecycleOwner.moveToResumed()

        val cameraSelector = activeCustomSelector ?: if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val previewUseCase = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        val photo12MpResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    DEFAULT_PHOTO_12MP_SIZE,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val image12MpUseCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(photo12MpResolutionSelector)
            .build()

        val video4kQualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.higherQualityOrLowerThan(Quality.UHD)
        )

        val recorder4k = Recorder.Builder()
            .setQualitySelector(video4kQualitySelector)
            .build()

        val video4k30UseCase = VideoCapture.Builder(recorder4k)
            .setTargetFrameRate(DEFAULT_VIDEO_30FPS_RANGE)
            .build()

        var boundCamera: Camera? = null
        var activeImageCapture: ImageCapture? = null
        var activeVideoCapture: VideoCapture<Recorder>? = null
        var effectiveVideoLabel = "4K 30fps"

        // Attempt 1: Full combined bind (Preview + 12MP ImageCapture + 4K 30fps VideoCapture)
        boundCamera = tryBindUseCases(
            provider = provider,
            selector = cameraSelector,
            useCases = arrayOf(previewUseCase, image12MpUseCase, video4k30UseCase)
        )
        if (boundCamera != null) {
            activeImageCapture = image12MpUseCase
            activeVideoCapture = video4k30UseCase
        } else if (mode == RecorderMode.VIDEO) {
            // Attempt 2A (Video mode): Bind Preview + 4K 30fps VideoCapture alone
            boundCamera = tryBindUseCases(
                provider = provider,
                selector = cameraSelector,
                useCases = arrayOf(previewUseCase, video4k30UseCase)
            )
            if (boundCamera != null) {
                activeVideoCapture = video4k30UseCase
            } else {
                // Attempt 2B: Adaptive VideoCapture without strict [30,30] FPS lock
                val fallbackRecorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.UHD)
                        )
                    )
                    .build()
                val adaptiveVideoUseCase = VideoCapture.Builder(fallbackRecorder).build()
                boundCamera = tryBindUseCases(
                    provider = provider,
                    selector = cameraSelector,
                    useCases = arrayOf(previewUseCase, adaptiveVideoUseCase)
                )
                if (boundCamera != null) {
                    activeVideoCapture = adaptiveVideoUseCase
                    effectiveVideoLabel = "4K/HD Auto"
                }
            }
        } else {
            // Attempt 3A (Photo mode): Bind Preview + 12MP ImageCapture alone
            boundCamera = tryBindUseCases(
                provider = provider,
                selector = cameraSelector,
                useCases = arrayOf(previewUseCase, image12MpUseCase)
            )
            if (boundCamera != null) {
                activeImageCapture = image12MpUseCase
            } else {
                // Attempt 3B: Default ImageCapture fallback
                val autoImageUseCase = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                boundCamera = tryBindUseCases(
                    provider = provider,
                    selector = cameraSelector,
                    useCases = arrayOf(previewUseCase, autoImageUseCase)
                )
                if (boundCamera != null) {
                    activeImageCapture = autoImageUseCase
                }
            }
        }

        if (boundCamera == null) {
            isCameraBound = false
            boundHasImageCapture = false
            boundHasVideoCapture = false
            _cameraState.value = _cameraState.value.copy(
                cameraError = "Unable to bind camera preview on this device"
            )
            return
        }

        preview = previewUseCase
        imageCapture = activeImageCapture
        videoCapture = activeVideoCapture
        camera = boundCamera
        boundPreviewView = previewView
        isCameraBound = true
        boundHasImageCapture = activeImageCapture != null
        boundHasVideoCapture = activeVideoCapture != null

        // Apply torch state safely
        if (isTorchEnabled) {
            try {
                boundCamera.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                isTorchEnabled = false
            }
        }

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
            try {
                boundCamera.cameraControl.setZoomRatio(targetZoom)
            } catch (e: Exception) {
                // Ignore
            }
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
            videoResolution = effectiveVideoLabel,
            cameraError = null
        )
    }

    private fun tryBindUseCases(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        vararg useCases: UseCase
    ): Camera? {
        return try {
            provider.unbindAll()
            provider.bindToLifecycle(
                backgroundLifecycleOwner,
                selector,
                *useCases
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
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

        options.add(
            LensOption(
                id = "1x",
                label = "1x",
                name = "Wide",
                targetZoomRatio = 1.0f
            )
        )

        if (maxZoom >= 2.0f) {
            options.add(LensOption(id = "2x", label = "2x", name = "Zoom 2x", targetZoomRatio = 2.0f))
        }
        if (maxZoom >= 3.0f) {
            options.add(LensOption(id = "3x", label = "3x", name = "Telephoto 3x", targetZoomRatio = 3.0f))
        }
        if (maxZoom >= 5.0f) {
            options.add(LensOption(id = "5x", label = "5x", name = "Telephoto 5x", targetZoomRatio = 5.0f))
        }
        if (maxZoom >= 10.0f) {
            options.add(LensOption(id = "10x", label = "10x", name = "Super Zoom 10x", targetZoomRatio = 10.0f))
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
        val capture = imageCapture
        if (capture == null || !isCameraBound) {
            onError("Camera is still initializing, please try again")
            return
        }

        try {
            outputFile.parentFile?.mkdirs()
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
        } catch (e: Exception) {
            onError(e.message ?: "Photo capture failed")
        }
    }

    @SuppressLint("MissingPermission")
    fun startVideoRecording(
        outputFile: File,
        enableAudio: Boolean = true,
        onVideoStarted: () -> Unit = {},
        onVideoSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRecordingActiveOrFinalizing) {
            onError("Previous video recording is still finalizing")
            return
        }

        val capture = videoCapture
        if (capture == null || !isCameraBound) {
            onError("Video camera is still initializing, please try again")
            return
        }

        try {
            outputFile.parentFile?.mkdirs()
            backgroundLifecycleOwner.moveToResumed()
            val outputOptions = FileOutputOptions.Builder(outputFile).build()

            val pendingRecording = capture.output.prepareRecording(context, outputOptions)
            if (enableAudio) {
                try {
                    pendingRecording.withAudioEnabled()
                } catch (e: SecurityException) {
                    // Audio permission might not be granted, continue video-only
                }
            }

            isRecordingActiveOrFinalizing = true
            currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        onVideoStarted()
                    }
                    is VideoRecordEvent.Finalize -> {
                        currentRecording = null
                        isRecordingActiveOrFinalizing = false
                        if (!event.hasError()) {
                            onVideoSaved(outputFile)
                        } else {
                            val errorMsg = event.cause?.message ?: "Video recording failed (${event.error})"
                            onError(errorMsg)
                        }
                        if (!isHostForeground) {
                            unbindCameraInternal()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            currentRecording = null
            isRecordingActiveOrFinalizing = false
            onError(e.message ?: "Failed to start video recording")
        }
    }

    fun stopVideoRecording() {
        try {
            currentRecording?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
            currentRecording = null
            isRecordingActiveOrFinalizing = false
        }
    }

    fun isRecordingVideo(): Boolean = isRecordingActiveOrFinalizing || currentRecording != null

    fun release() {
        stopVideoRecording()
        unbindCameraInternal()
        backgroundLifecycleOwner.destroy()
    }
}
