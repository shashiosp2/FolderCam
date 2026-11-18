package com.example.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as SystemCameraManager
import android.hardware.display.DisplayManager
import android.util.Range
import android.util.Size
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
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
import com.example.model.CaptureOrientation
import com.example.model.RecorderMode
import com.example.model.VideoLensPreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val isZslActive: Boolean = false,
    val currentZoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 1.0f,
    val lensOptions: List<LensOption> = listOf(
        LensOption(id = "1x", label = "1x", name = "Wide", targetZoomRatio = 1.0f)
    ),
    val selectedLensId: String = "1x",
    val preferredVideoLens: VideoLensPreference = VideoLensPreference.WIDE,
    val photoResolution: String = "12MP",
    val videoResolution: String = "4K 30fps",
    val captureOrientation: CaptureOrientation = CaptureOrientation.AUTO,
    val detectedAutoRotation: Int = Surface.ROTATION_0,
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
        private const val TARGET_PHOTO_12MP_PIXELS = 12_000_000L
        // Threshold to catch devices where ZSL caps ImageCapture down to 1920x1440 (~2.76MP / 3MP)
        private const val MIN_FULL_RES_PHOTO_PIXELS = 8_000_000L
        // Default 30 FPS range for 4K video recording
        val DEFAULT_VIDEO_30FPS_RANGE = Range(30, 30)
        // Burst-tuned JPEG quality (88%) cuts frame footprint ~40-50% for safe 100-shot RAM buffering
        const val BURST_JPEG_QUALITY = 88
        const val MAX_BURST_PHOTOS = 100
        // Concurrent ImageCapture pipeline depth (frames drain to RAM immediately on completion)
        const val MAX_IN_FLIGHT_CAPTURE_PIPELINE = 8
        // Use MINIMIZE_LATENCY instead of ZSL PRIVATE reprocessing so hardware/digital zoom crops
        // (CONTROL_ZOOM_RATIO / SCALER_CROP_REGION) are reliably applied to captured still photos.
        const val DEFAULT_PHOTO_CAPTURE_MODE = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY

        fun mapSensorOrientationToSurfaceRotation(orientation: Int): Int {
            return when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
        }

        fun rankPhotoSizesFor12Mp(supportedSizes: List<Size>): List<Size> {
            val target4By3Ratio = 4.0 / 3.0
            return supportedSizes.sortedWith(
                compareBy<Size>(
                    { size ->
                        val px = size.width.toLong() * size.height.toLong()
                        when {
                            px in 10_000_000L..16_000_000L -> 0
                            px >= MIN_FULL_RES_PHOTO_PIXELS -> 1
                            else -> 2
                        }
                    },
                    { size ->
                        val px = size.width.toLong() * size.height.toLong()
                        if (px >= MIN_FULL_RES_PHOTO_PIXELS) {
                            abs(px - TARGET_PHOTO_12MP_PIXELS)
                        } else {
                            -px
                        }
                    },
                    { size ->
                        val ratio = maxOf(size.width, size.height).toDouble() /
                            minOf(size.width, size.height).coerceAtLeast(1).toDouble()
                        abs(ratio - target4By3Ratio)
                    }
                )
            )
        }
    }

    val backgroundLifecycleOwner = BackgroundCameraLifecycleOwner()
    private val captureExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private val inFlightImageCaptures = AtomicInteger(0)
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
    private var activeBoundSelector: CameraSelector? = null
    private var isCameraBound = false
    private var boundHasImageCapture = false
    private var boundHasVideoCapture = false

    private var isHostForeground = true
    private var activeMode: RecorderMode = RecorderMode.AUDIO
    private var isProviderRequestInFlight = false

    @Volatile
    private var preferredVideoLensPreference: VideoLensPreference = VideoLensPreference.WIDE

    @Volatile
    private var currentTargetRotation: Int = Surface.ROTATION_0

    @Volatile
    private var lastAutoDetectedRotation: Int = Surface.ROTATION_0

    @Volatile
    private var hasSensorRotation: Boolean = false

    @Volatile
    private var captureOrientationPreference: CaptureOrientation = CaptureOrientation.AUTO

    private var isDisplayListenerRegistered = false
    private val displayManager: DisplayManager? by lazy {
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val previewDisplay = lastPreviewView?.display
            if (previewDisplay == null || previewDisplay.displayId == displayId) {
                val displayRot = previewDisplay?.rotation
                    ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                if (displayRot != null && !hasSensorRotation) {
                    updateAutoDetectedRotation(displayRot)
                }
            }
        }
    }

    private val orientationEventListener: OrientationEventListener? by lazy {
        try {
            object : OrientationEventListener(context.applicationContext) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) return
                    val rotation = mapSensorOrientationToSurfaceRotation(orientation)
                    hasSensorRotation = true
                    updateAutoDetectedRotation(rotation)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private val _cameraState = MutableStateFlow(CameraUiState())
    val cameraState: StateFlow<CameraUiState> = _cameraState.asStateFlow()

    var isFrontCamera: Boolean = false
        private set

    var isTorchEnabled: Boolean = false
        private set

    fun setCaptureOrientation(orientation: CaptureOrientation) {
        captureOrientationPreference = orientation
        val resolved = resolveCurrentRotation(lastPreviewView)
        applyTargetRotation(resolved)
        _cameraState.value = _cameraState.value.copy(
            captureOrientation = orientation,
            detectedAutoRotation = lastAutoDetectedRotation
        )
    }

    fun setPreferredVideoLens(preference: VideoLensPreference) {
        preferredVideoLensPreference = preference
        _cameraState.value = _cameraState.value.copy(preferredVideoLens = preference)

        val currentPreview = lastPreviewView
        if (!isRecordingVideo() &&
            isHostForeground &&
            activeMode == RecorderMode.VIDEO &&
            currentPreview != null &&
            cameraProvider != null
        ) {
            isFrontCamera = false
            activeCustomSelector = null
            bindCamera(
                previewView = currentPreview,
                mode = RecorderMode.VIDEO,
                applyVideoLensPref = true
            )
        }
    }

    private fun updateAutoDetectedRotation(rotation: Int) {
        lastAutoDetectedRotation = rotation
        if (captureOrientationPreference == CaptureOrientation.AUTO) {
            applyTargetRotation(rotation)
        }
        if (_cameraState.value.detectedAutoRotation != rotation) {
            _cameraState.value = _cameraState.value.copy(detectedAutoRotation = rotation)
        }
    }

    private fun applyTargetRotation(rotation: Int) {
        currentTargetRotation = rotation
        imageCapture?.targetRotation = rotation
        if (!isRecordingVideo()) {
            videoCapture?.targetRotation = rotation
        }
    }

    fun resolveCurrentRotation(previewView: PreviewView? = lastPreviewView): Int {
        val fixed = captureOrientationPreference.surfaceRotation
        if (fixed != null) {
            currentTargetRotation = fixed
            return fixed
        }
        if (hasSensorRotation) {
            currentTargetRotation = lastAutoDetectedRotation
            return lastAutoDetectedRotation
        }
        val displayRot = previewView?.display?.rotation
            ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: lastAutoDetectedRotation
        lastAutoDetectedRotation = displayRot
        currentTargetRotation = displayRot
        return displayRot
    }

    private fun startOrientationTracking() {
        try {
            if (orientationEventListener?.canDetectOrientation() == true) {
                orientationEventListener?.enable()
            }
        } catch (e: Exception) {
            // Ignore in headless/test environments
        }
        if (!isDisplayListenerRegistered) {
            try {
                displayManager?.registerDisplayListener(displayListener, null)
                isDisplayListenerRegistered = true
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun stopOrientationTracking() {
        try {
            orientationEventListener?.disable()
        } catch (e: Exception) {
            // Ignore
        }
        if (isDisplayListenerRegistered) {
            try {
                displayManager?.unregisterDisplayListener(displayListener)
            } catch (e: Exception) {
                // Ignore
            }
            isDisplayListenerRegistered = false
        }
    }

    fun onHostStop() {
        isHostForeground = false
        if (!isRecordingVideo()) {
            stopOrientationTracking()
            unbindCameraInternal()
        }
    }

    fun onHostResume(previewView: PreviewView?, mode: RecorderMode) {
        isHostForeground = true
        activeMode = mode
        if (mode == RecorderMode.AUDIO && !isRecordingVideo()) {
            stopOrientationTracking()
            unbindCameraInternal()
            return
        }
        startOrientationTracking()
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

        // If actively recording video (started from widget or background), keep VideoCapture alive
        // and attach or update Preview without unbinding the active recording!
        if (isRecordingVideo() && cameraProvider != null && isCameraBound) {
            backgroundLifecycleOwner.moveToResumed()
            val currentPreview = preview
            if (currentPreview != null) {
                if (boundPreviewView !== previewView) {
                    currentPreview.surfaceProvider = previewView.surfaceProvider
                    boundPreviewView = previewView
                }
            } else {
                // Started headlessly without PreviewView -> attach Preview alongside active VideoCapture
                val provider = cameraProvider
                val selector = activeBoundSelector ?: CameraSelector.DEFAULT_BACK_CAMERA
                if (provider != null) {
                    try {
                        val previewDisplayRotation = previewView.display?.rotation
                            ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                            ?: Surface.ROTATION_0
                        val newPreview = Preview.Builder()
                            .setTargetRotation(previewDisplayRotation)
                            .build()
                            .also { it.surfaceProvider = previewView.surfaceProvider }
                        provider.bindToLifecycle(backgroundLifecycleOwner, selector, newPreview)
                        preview = newPreview
                        boundPreviewView = previewView
                    } catch (e: Exception) {
                        // Some HALs forbid adding Preview mid-stream; background video continues safely
                        e.printStackTrace()
                    }
                }
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
            bindCamera(
                previewView = previewView,
                mode = mode,
                applyVideoLensPref = (mode == RecorderMode.VIDEO)
            )
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
                if (isHostForeground && activeMode != RecorderMode.AUDIO && !isRecordingVideo()) {
                    bindCamera(
                        previewView = latestPreview,
                        mode = activeMode,
                        applyVideoLensPref = (activeMode == RecorderMode.VIDEO)
                    )
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
        bindCamera(previewView, mode, initialZoom = 1.0f, selectedLensId = "1x")
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
        activeBoundSelector = null
        isCameraBound = false
        boundHasImageCapture = false
        boundHasVideoCapture = false
    }

    private fun buildVideoUseCase(initialRotation: Int, highQuality4k: Boolean): VideoCapture<Recorder> {
        val qualitySelector = if (highQuality4k) {
            QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.higherQualityOrLowerThan(Quality.UHD)
            )
        } else {
            QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.lowerQualityOrHigherThan(Quality.UHD)
            )
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        val builder = VideoCapture.Builder(recorder)
            .setTargetRotation(initialRotation)
        if (highQuality4k) {
            builder.setTargetFrameRate(DEFAULT_VIDEO_30FPS_RANGE)
        }
        return builder.build()
    }

    private fun bindCamera(
        previewView: PreviewView,
        mode: RecorderMode,
        initialZoom: Float = _cameraState.value.currentZoomRatio,
        selectedLensId: String = _cameraState.value.selectedLensId,
        applyVideoLensPref: Boolean = false
    ) {
        val provider = cameraProvider ?: return
        if (isRecordingVideo()) return

        backgroundLifecycleOwner.moveToResumed()

        val cameraSelector = activeCustomSelector ?: if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        if (isHostForeground) {
            startOrientationTracking()
        }
        val initialRotation = resolveCurrentRotation(previewView)
        val previewDisplayRotation = previewView.display?.rotation
            ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0

        val previewUseCase = Preview.Builder()
            .setTargetRotation(previewDisplayRotation)
            .build()
            .also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        val photo12MpFilter = ResolutionFilter { supportedSizes, _ ->
            rankPhotoSizesFor12Mp(supportedSizes)
        }

        val photo12MpResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .setResolutionStrategy(
                ResolutionStrategy(
                    DEFAULT_PHOTO_12MP_SIZE,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .setResolutionFilter(photo12MpFilter)
            .build()

        var boundCamera: Camera? = null
        var activeImageCapture: ImageCapture? = null
        var activeVideoCapture: VideoCapture<Recorder>? = null
        var effectiveVideoLabel = "4K 30fps"

        if (mode == RecorderMode.VIDEO) {
            val video4k30UseCase = buildVideoUseCase(initialRotation, highQuality4k = true)
            boundCamera = tryBindUseCases(
                provider = provider,
                selector = cameraSelector,
                useCases = arrayOf(previewUseCase, video4k30UseCase)
            )
            if (boundCamera != null) {
                activeVideoCapture = video4k30UseCase
            } else {
                val adaptiveVideoUseCase = buildVideoUseCase(initialRotation, highQuality4k = false)
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
            val minLatencyCandidate = buildPhotoUseCase(
                captureMode = DEFAULT_PHOTO_CAPTURE_MODE,
                selector = photo12MpResolutionSelector,
                targetRotation = initialRotation
            )
            val minLatencyCamera = tryBindUseCases(
                provider = provider,
                selector = cameraSelector,
                useCases = arrayOf(previewUseCase, minLatencyCandidate)
            )
            val minLatencyPixels = resolutionPixelCount(minLatencyCandidate)
            val minLatencyMeets12Mp = minLatencyCamera != null &&
                (minLatencyPixels == 0L || minLatencyPixels >= MIN_FULL_RES_PHOTO_PIXELS)

            if (minLatencyMeets12Mp) {
                boundCamera = minLatencyCamera
                activeImageCapture = minLatencyCandidate
            } else {
                val maxQualityCandidate = buildPhotoUseCase(
                    captureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY,
                    selector = photo12MpResolutionSelector,
                    targetRotation = initialRotation
                )
                val maxQualityCamera = tryBindUseCases(
                    provider = provider,
                    selector = cameraSelector,
                    useCases = arrayOf(previewUseCase, maxQualityCandidate)
                )
                val maxQualityPixels = resolutionPixelCount(maxQualityCandidate)

                when {
                    maxQualityCamera != null && maxQualityPixels > minLatencyPixels -> {
                        boundCamera = maxQualityCamera
                        activeImageCapture = maxQualityCandidate
                    }
                    minLatencyCamera != null -> {
                        val reboundMinLatency = buildPhotoUseCase(
                            captureMode = DEFAULT_PHOTO_CAPTURE_MODE,
                            selector = photo12MpResolutionSelector,
                            targetRotation = initialRotation
                        )
                        boundCamera = tryBindUseCases(
                            provider = provider,
                            selector = cameraSelector,
                            useCases = arrayOf(previewUseCase, reboundMinLatency)
                        )
                        if (boundCamera != null) {
                            activeImageCapture = reboundMinLatency
                        }
                    }
                }

                if (boundCamera == null) {
                    val autoImageUseCase = ImageCapture.Builder()
                        .setCaptureMode(DEFAULT_PHOTO_CAPTURE_MODE)
                        .setJpegQuality(BURST_JPEG_QUALITY)
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                        .setTargetRotation(initialRotation)
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
        activeBoundSelector = cameraSelector
        isCameraBound = true
        boundHasImageCapture = activeImageCapture != null
        boundHasVideoCapture = activeVideoCapture != null

        if (isTorchEnabled) {
            try {
                boundCamera.cameraControl.enableTorch(true)
            } catch (e: Exception) {
                isTorchEnabled = false
            }
        }

        val zoomState = boundCamera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 1.0f
        val maxZoom = zoomState?.maxZoomRatio ?: 8.0f

        val discoveredLenses = buildLensOptions(
            provider = provider,
            isFront = isFrontCamera,
            minZoom = minZoom,
            maxZoom = maxZoom
        )

        var targetZoom = initialZoom.coerceIn(minZoom, maxZoom)
        var resolvedLensId = selectedLensId

        // Apply configured Wide (1x) / Ultra-Wide (0.5x) preference in Video mode
        if (applyVideoLensPref && mode == RecorderMode.VIDEO && !isFrontCamera) {
            val preferredOption = resolveLensForPreference(discoveredLenses, preferredVideoLensPreference)
            if (preferredOption.customCameraSelector != null &&
                preferredOption.customCameraSelector != activeCustomSelector
            ) {
                activeCustomSelector = preferredOption.customCameraSelector
                bindCamera(
                    previewView = previewView,
                    mode = mode,
                    initialZoom = preferredOption.targetZoomRatio,
                    selectedLensId = preferredOption.id,
                    applyVideoLensPref = false
                )
                return
            }
            targetZoom = preferredOption.targetZoomRatio.coerceIn(minZoom, maxZoom)
            resolvedLensId = preferredOption.id
        }

        if (abs(targetZoom - 1.0f) > 0.001f || abs(targetZoom - (zoomState?.zoomRatio ?: 1.0f)) > 0.001f) {
            try {
                boundCamera.cameraControl.setZoomRatio(targetZoom)
            } catch (e: Exception) {
                // Ignore
            }
        }

        val actualPhotoLabel = activeImageCapture?.resolutionInfo?.resolution?.let { res ->
            val mp = ((res.width.toLong() * res.height.toLong()) / 1_000_000.0).roundToInt().coerceAtLeast(1)
            "${mp}MP"
        } ?: "12MP"

        _cameraState.value = CameraUiState(
            isFrontCamera = isFrontCamera,
            isTorchEnabled = isTorchEnabled,
            isZslActive = false,
            currentZoomRatio = targetZoom,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            lensOptions = discoveredLenses,
            selectedLensId = resolvedLensId,
            preferredVideoLens = preferredVideoLensPreference,
            photoResolution = actualPhotoLabel,
            videoResolution = effectiveVideoLabel,
            captureOrientation = captureOrientationPreference,
            detectedAutoRotation = lastAutoDetectedRotation,
            cameraError = null
        )
    }

    /**
     * Starts VideoCapture headlessly (without needing a PreviewView) for background widget launches,
     * applying the configured Wide (default) or Ultra-Wide lens with automatic widest-lens fallback.
     */
    @SuppressLint("MissingPermission")
    fun startBackgroundVideoRecording(
        outputFile: File,
        enableAudio: Boolean,
        lensPreference: VideoLensPreference,
        onVideoStarted: () -> Unit = {},
        onVideoSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRecordingActiveOrFinalizing) {
            onError("Previous video recording is still finalizing")
            return
        }

        preferredVideoLensPreference = lensPreference
        val existingProvider = cameraProvider
        if (existingProvider != null) {
            bindHeadlessVideoAndStart(
                provider = existingProvider,
                outputFile = outputFile,
                enableAudio = enableAudio,
                lensPreference = lensPreference,
                onVideoStarted = onVideoStarted,
                onVideoSaved = onVideoSaved,
                onError = onError
            )
            return
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindHeadlessVideoAndStart(
                    provider = provider,
                    outputFile = outputFile,
                    enableAudio = enableAudio,
                    lensPreference = lensPreference,
                    onVideoStarted = onVideoStarted,
                    onVideoSaved = onVideoSaved,
                    onError = onError
                )
            } catch (e: Exception) {
                onError(e.message ?: "Failed to initialize camera provider")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindHeadlessVideoAndStart(
        provider: ProcessCameraProvider,
        outputFile: File,
        enableAudio: Boolean,
        lensPreference: VideoLensPreference,
        onVideoStarted: () -> Unit,
        onVideoSaved: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRecordingActiveOrFinalizing) {
            onError("Previous video recording is still finalizing")
            return
        }

        backgroundLifecycleOwner.moveToResumed()
        isFrontCamera = false
        activeCustomSelector = null

        val initialRotation = resolveCurrentRotation(lastPreviewView)
        var effectiveVideoLabel = "4K 30fps"
        var boundVideoUseCase = buildVideoUseCase(initialRotation, highQuality4k = true)

        var selectedSelector = CameraSelector.DEFAULT_BACK_CAMERA
        var boundCamera = tryBindUseCases(provider, selectedSelector, boundVideoUseCase)
        if (boundCamera == null) {
            boundVideoUseCase = buildVideoUseCase(initialRotation, highQuality4k = false)
            boundCamera = tryBindUseCases(provider, selectedSelector, boundVideoUseCase)
            effectiveVideoLabel = "4K/HD Auto"
        }

        if (boundCamera == null) {
            onError("Unable to bind back camera for background video")
            return
        }

        val zoomState = boundCamera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 1.0f
        val maxZoom = zoomState?.maxZoomRatio ?: 8.0f
        val discoveredLenses = buildLensOptions(
            provider = provider,
            isFront = false,
            minZoom = minZoom,
            maxZoom = maxZoom
        )

        val chosenLens = resolveLensForPreference(discoveredLenses, lensPreference)
        var targetZoom = chosenLens.targetZoomRatio.coerceIn(minZoom, maxZoom)

        // If Ultra-Wide requires a standalone physical camera selector on this device, rebind to it
        if (chosenLens.customCameraSelector != null) {
            val customVideoUseCase = buildVideoUseCase(initialRotation, highQuality4k = true)
            val customCam = tryBindUseCases(provider, chosenLens.customCameraSelector, customVideoUseCase)
            if (customCam != null) {
                boundCamera = customCam
                boundVideoUseCase = customVideoUseCase
                selectedSelector = chosenLens.customCameraSelector
                activeCustomSelector = chosenLens.customCameraSelector
                targetZoom = chosenLens.targetZoomRatio
            }
        }

        if (abs(targetZoom - 1.0f) > 0.001f) {
            try {
                boundCamera.cameraControl.setZoomRatio(targetZoom)
            } catch (e: Exception) {
                // Ignore if unsupported
            }
        }

        preview = null
        imageCapture = null
        videoCapture = boundVideoUseCase
        camera = boundCamera
        boundPreviewView = null
        activeBoundSelector = selectedSelector
        isCameraBound = true
        boundHasImageCapture = false
        boundHasVideoCapture = true
        activeMode = RecorderMode.VIDEO

        _cameraState.value = _cameraState.value.copy(
            isFrontCamera = false,
            currentZoomRatio = targetZoom,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            lensOptions = discoveredLenses,
            selectedLensId = chosenLens.id,
            preferredVideoLens = lensPreference,
            videoResolution = effectiveVideoLabel,
            captureOrientation = captureOrientationPreference,
            cameraError = null
        )

        startVideoRecording(
            outputFile = outputFile,
            enableAudio = enableAudio,
            onVideoStarted = onVideoStarted,
            onVideoSaved = onVideoSaved,
            onError = onError
        )
    }

    fun resolveLensForPreference(
        lenses: List<LensOption>,
        preference: VideoLensPreference
    ): LensOption {
        return when (preference) {
            VideoLensPreference.ULTRAWIDE -> {
                lenses.find { it.id == "uw" }
                    ?: lenses.minByOrNull { it.targetZoomRatio }
                    ?: LensOption(id = "1x", label = "1x", name = "Wide", targetZoomRatio = 1.0f)
            }
            VideoLensPreference.WIDE -> {
                lenses.find { it.id == "1x" }
                    ?: LensOption(id = "1x", label = "1x", name = "Wide", targetZoomRatio = 1.0f)
            }
        }
    }

    private fun buildPhotoUseCase(
        captureMode: Int,
        selector: ResolutionSelector,
        targetRotation: Int
    ): ImageCapture {
        return ImageCapture.Builder()
            .setCaptureMode(captureMode)
            .setJpegQuality(BURST_JPEG_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(selector)
            .setTargetRotation(targetRotation)
            .build()
    }

    private fun resolutionPixelCount(imageCapture: ImageCapture?): Long {
        val res = imageCapture?.resolutionInfo?.resolution ?: return 0L
        return res.width.toLong() * res.height.toLong()
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

    fun canEnqueueBurstShot(): Boolean {
        return isCameraBound &&
            imageCapture != null &&
            inFlightImageCaptures.get() < MAX_IN_FLIGHT_CAPTURE_PIPELINE
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

        if (inFlightImageCaptures.get() >= MAX_IN_FLIGHT_CAPTURE_PIPELINE) {
            onError("Camera pipeline busy, buffering previous shots…")
            return
        }

        inFlightImageCaptures.incrementAndGet()
        try {
            capture.targetRotation = resolveCurrentRotation(lastPreviewView)
            outputFile.parentFile?.mkdirs()
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            capture.takePicture(
                outputOptions,
                captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        inFlightImageCaptures.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
                        onSuccess(outputFile)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        inFlightImageCaptures.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
                        onError(exception.message ?: "Photo capture failed")
                    }
                }
            )
        } catch (e: Exception) {
            inFlightImageCaptures.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            onError(e.message ?: "Photo capture failed")
        }
    }

    /**
     * Captures a burst-tuned (88% JPEG) frame directly into an in-memory ByteArrayOutputStream
     * without synchronous disk writes, freeing CameraX ImageReader slots immediately for up to
     * 100 consecutive burst shots while a background drainer saves frames asynchronously.
     */
    fun takeBurstPhotoToMemory(
        onSuccess: (ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null || !isCameraBound) {
            onError("Camera is still initializing, please try again")
            return
        }

        if (inFlightImageCaptures.get() >= MAX_IN_FLIGHT_CAPTURE_PIPELINE) {
            onError("Pipeline busy")
            return
        }

        inFlightImageCaptures.incrementAndGet()
        try {
            capture.targetRotation = resolveCurrentRotation(lastPreviewView)
            val baos = ByteArrayOutputStream(1536 * 1024)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(baos).build()
            capture.takePicture(
                outputOptions,
                captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        inFlightImageCaptures.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
                        onSuccess(baos.toByteArray())
                    }

                    override fun onError(exception: ImageCaptureException) {
                        inFlightImageCaptures.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
                        onError(exception.message ?: "Burst photo capture failed")
                    }
                }
            )
        } catch (e: Exception) {
            inFlightImageCaptures.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            onError(e.message ?: "Burst photo capture failed")
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
            capture.targetRotation = resolveCurrentRotation(lastPreviewView)
            outputFile.parentFile?.mkdirs()
            backgroundLifecycleOwner.moveToResumed()
            val outputOptions = FileOutputOptions.Builder(outputFile).build()

            var pendingRecording = capture.output.prepareRecording(context, outputOptions)
            if (enableAudio) {
                try {
                    pendingRecording = pendingRecording.withAudioEnabled()
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
                            stopOrientationTracking()
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
        stopOrientationTracking()
        stopVideoRecording()
        unbindCameraInternal()
        backgroundLifecycleOwner.destroy()
        try {
            captureExecutor.shutdownNow()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
