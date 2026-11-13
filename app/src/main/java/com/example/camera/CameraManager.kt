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
import com.example.model.RecorderMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.camera.core.ExperimentalZeroShutterLag
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
        private const val TARGET_PHOTO_12MP_PIXELS = 12_000_000L
        // Threshold to catch devices where ZSL caps ImageCapture down to 1920x1440 (~2.76MP / 3MP)
        private const val MIN_FULL_RES_PHOTO_PIXELS = 8_000_000L
        // Default 30 FPS range for 4K video recording
        val DEFAULT_VIDEO_30FPS_RANGE = Range(30, 30)
        const val MAX_IN_FLIGHT_BURST = 6

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
    private val captureExecutor: ExecutorService = Executors.newFixedThreadPool(2)
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
    private var isCameraBound = false
    private var boundHasImageCapture = false
    private var boundHasVideoCapture = false

    private var isHostForeground = true
    private var activeMode: RecorderMode = RecorderMode.AUDIO
    private var isProviderRequestInFlight = false

    @Volatile
    private var currentTargetRotation: Int = Surface.ROTATION_0

    @Volatile
    private var hasSensorRotation: Boolean = false

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
                    applyTargetRotation(displayRot)
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
                    applyTargetRotation(rotation)
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

    private fun applyTargetRotation(rotation: Int) {
        currentTargetRotation = rotation
        imageCapture?.targetRotation = rotation
        if (!isRecordingVideo()) {
            videoCapture?.targetRotation = rotation
        }
    }

    private fun resolveCurrentRotation(previewView: PreviewView?): Int {
        if (hasSensorRotation) {
            return currentTargetRotation
        }
        val displayRot = previewView?.display?.rotation
            ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: currentTargetRotation
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

    @SuppressLint("UnsafeOptInUsageError")
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

        if (isHostForeground) {
            startOrientationTracking()
        }
        val initialRotation = resolveCurrentRotation(previewView)

        val previewUseCase = Preview.Builder()
            .setTargetRotation(initialRotation)
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
        var usedZslBuilder = false

        if (mode == RecorderMode.VIDEO) {
            // Video Mode: Bind Preview + 4K 30fps VideoCapture with graceful quality fallbacks
            val video4kQualitySelector = QualitySelector.fromOrderedList(
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                FallbackStrategy.higherQualityOrLowerThan(Quality.UHD)
            )
            val recorder4k = Recorder.Builder()
                .setQualitySelector(video4kQualitySelector)
                .build()
            val video4k30UseCase = VideoCapture.Builder(recorder4k)
                .setTargetFrameRate(DEFAULT_VIDEO_30FPS_RANGE)
                .setTargetRotation(initialRotation)
                .build()

            boundCamera = tryBindUseCases(
                provider = provider,
                selector = cameraSelector,
                useCases = arrayOf(previewUseCase, video4k30UseCase)
            )
            if (boundCamera != null) {
                activeVideoCapture = video4k30UseCase
            } else {
                val fallbackRecorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.UHD)
                        )
                    )
                    .build()
                val adaptiveVideoUseCase = VideoCapture.Builder(fallbackRecorder)
                    .setTargetRotation(initialRotation)
                    .build()
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
            // Photo Mode: Try ZSL at 12MP first; if hardware ZSL caps resolution below 8MP (e.g. 1920x1440 / 3MP),
            // automatically upgrade to MINIMIZE_LATENCY / MAXIMIZE_QUALITY with full 12MP resolution.
            val zslCandidate = buildPhotoUseCase(
                captureMode = ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG,
                selector = photo12MpResolutionSelector,
                targetRotation = initialRotation
            )
            val zslCamera = tryBindUseCases(
                provider = provider,
                selector = cameraSelector,
                useCases = arrayOf(previewUseCase, zslCandidate)
            )
            val zslPixels = resolutionPixelCount(zslCandidate)
            val zslMeets12Mp = zslCamera != null && (zslPixels == 0L || zslPixels >= MIN_FULL_RES_PHOTO_PIXELS)

            if (zslMeets12Mp) {
                boundCamera = zslCamera
                activeImageCapture = zslCandidate
                usedZslBuilder = true
            } else {
                val minLatencyCandidate = buildPhotoUseCase(
                    captureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
                    selector = photo12MpResolutionSelector,
                    targetRotation = initialRotation
                )
                val minLatencyCamera = tryBindUseCases(
                    provider = provider,
                    selector = cameraSelector,
                    useCases = arrayOf(previewUseCase, minLatencyCandidate)
                )
                val minLatencyPixels = resolutionPixelCount(minLatencyCandidate)

                if (minLatencyCamera != null && (minLatencyPixels >= MIN_FULL_RES_PHOTO_PIXELS || zslCamera == null)) {
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
                        maxQualityCamera != null && maxQualityPixels >= maxOf(zslPixels, minLatencyPixels) -> {
                            boundCamera = maxQualityCamera
                            activeImageCapture = maxQualityCandidate
                        }
                        zslCamera != null && zslPixels >= minLatencyPixels -> {
                            val reboundZsl = buildPhotoUseCase(
                                captureMode = ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG,
                                selector = photo12MpResolutionSelector,
                                targetRotation = initialRotation
                            )
                            boundCamera = tryBindUseCases(
                                provider = provider,
                                selector = cameraSelector,
                                useCases = arrayOf(previewUseCase, reboundZsl)
                            )
                            if (boundCamera != null) {
                                activeImageCapture = reboundZsl
                                usedZslBuilder = true
                            }
                        }
                        minLatencyCamera != null -> {
                            val reboundMinLatency = buildPhotoUseCase(
                                captureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
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
                }

                if (boundCamera == null) {
                    val autoImageUseCase = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
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

        val hardwareZslSupported = try {
            boundCamera.cameraInfo.isZslSupported
        } catch (e: Exception) {
            false
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

        val actualPhotoLabel = activeImageCapture?.resolutionInfo?.resolution?.let { res ->
            val mp = ((res.width.toLong() * res.height.toLong()) / 1_000_000.0).roundToInt().coerceAtLeast(1)
            "${mp}MP"
        } ?: "12MP"

        _cameraState.value = CameraUiState(
            isFrontCamera = isFrontCamera,
            isTorchEnabled = isTorchEnabled,
            isZslActive = usedZslBuilder && hardwareZslSupported && !isTorchEnabled,
            currentZoomRatio = targetZoom,
            minZoomRatio = minZoom,
            maxZoomRatio = maxZoom,
            lensOptions = discoveredLenses,
            selectedLensId = selectedLensId,
            photoResolution = actualPhotoLabel,
            videoResolution = effectiveVideoLabel,
            cameraError = null
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun buildPhotoUseCase(
        captureMode: Int,
        selector: ResolutionSelector,
        targetRotation: Int
    ): ImageCapture {
        return ImageCapture.Builder()
            .setCaptureMode(captureMode)
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
        return isCameraBound && imageCapture != null && inFlightImageCaptures.get() < MAX_IN_FLIGHT_BURST
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

        if (inFlightImageCaptures.get() >= MAX_IN_FLIGHT_BURST) {
            onError("Burst buffer full, saving photos...")
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
