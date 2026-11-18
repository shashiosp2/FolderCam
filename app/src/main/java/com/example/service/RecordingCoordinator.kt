package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.audio.AudioRecorderManager
import com.example.camera.CameraManager
import com.example.data.FolderRepository
import com.example.data.MediaSaveResult
import com.example.data.MediaStorageManager
import com.example.model.CaptureOrientation
import com.example.model.CaptureState
import com.example.model.MediaFolder
import com.example.model.MediaType
import com.example.model.PhotoCaptureFeedbackState
import com.example.model.RecorderMode
import com.example.model.RecordingOrigin
import com.example.model.RecordingStatus
import com.example.model.VideoLensPreference
import com.example.system.DndController
import com.example.widget.FolderCamQuickWidget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Application-scoped singleton coordinating background Audio, background Video,
 * Do Not Disturb (DND), 100-shot RAM-buffered Photo Bursts, and 1x1 Glance Widget sync.
 */
class RecordingCoordinator private constructor(private val appContext: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recordingMutex = Mutex()

    val folderRepository = FolderRepository(appContext)
    val mediaStorageManager = MediaStorageManager(appContext)
    val audioRecorderManager = AudioRecorderManager(appContext)
    val cameraManager = CameraManager(appContext)
    val dndController = DndController(appContext)

    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _recordingOrigin = MutableStateFlow<RecordingOrigin?>(null)
    val recordingOrigin: StateFlow<RecordingOrigin?> = _recordingOrigin.asStateFlow()

    private val _activeRecordingFolder = MutableStateFlow<MediaFolder?>(null)
    val activeRecordingFolder: StateFlow<MediaFolder?> = _activeRecordingFolder.asStateFlow()

    val audioStatus: StateFlow<RecordingStatus> = audioRecorderManager.status

    private val _videoDurationSeconds = MutableStateFlow(0)
    val videoDurationSeconds: StateFlow<Int> = _videoDurationSeconds.asStateFlow()

    private val _isDndActive = MutableStateFlow(false)
    val isDndActive: StateFlow<Boolean> = _isDndActive.asStateFlow()

    private val _dndPolicyGranted = MutableStateFlow(dndController.hasPolicyAccess())
    val dndPolicyGranted: StateFlow<Boolean> = _dndPolicyGranted.asStateFlow()

    private val _hasAllFilesAccess = MutableStateFlow(mediaStorageManager.hasAllFilesAccess())
    val hasAllFilesAccess: StateFlow<Boolean> = _hasAllFilesAccess.asStateFlow()

    private val _needsAllFilesAccessBanner = MutableStateFlow(false)
    val needsAllFilesAccessBanner: StateFlow<Boolean> = _needsAllFilesAccessBanner.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _photoFeedback = MutableStateFlow(PhotoCaptureFeedbackState())
    val photoFeedback: StateFlow<PhotoCaptureFeedbackState> = _photoFeedback.asStateFlow()

    private val _mediaChangeCounter = MutableStateFlow(0L)
    val mediaChangeCounter: StateFlow<Long> = _mediaChangeCounter.asStateFlow()

    private var videoTimerJob: Job? = null
    private var burstJob: Job? = null
    private var photoFeedbackResetJob: Job? = null
    private var activeVideoFinalizeDeferred: CompletableDeferred<File?>? = null
    private var pendingAudioFolder: MediaFolder? = null
    private var pendingVideoFolder: MediaFolder? = null
    private val burstSessionCount = AtomicInteger(0)

    val burstBufferManager = BurstBufferManager(
        mediaStorageManager = mediaStorageManager,
        scope = scope,
        onFrameDrained = { folder, result ->
            onBurstFrameSaved(folder, result)
        },
        onFrameFailed = { err ->
            onPhotoCaptureFailed(err, silentIfBurst = true)
        }
    )

    init {
        cameraManager.setCaptureOrientation(folderRepository.captureOrientation.value)
        cameraManager.setPreferredVideoLens(folderRepository.videoLensPreference.value)
        mediaStorageManager.ensureAllFolderDirectoriesExist(folderRepository.folders.value)

        scope.launch {
            folderRepository.folders.collect { folders ->
                mediaStorageManager.ensureAllFolderDirectoriesExist(folders)
            }
        }
        scope.launch {
            folderRepository.videoLensPreference.collect { pref ->
                cameraManager.setPreferredVideoLens(pref)
                notifyWidgetUpdated()
            }
        }
        scope.launch {
            folderRepository.captureOrientation.collect { orientation ->
                cameraManager.setCaptureOrientation(orientation)
            }
        }
    }

    fun refreshSystemPermissions() {
        _dndPolicyGranted.value = dndController.hasPolicyAccess()
        _hasAllFilesAccess.value = mediaStorageManager.hasAllFilesAccess()
    }

    fun dismissAllFilesAccessBanner() {
        _needsAllFilesAccessBanner.value = false
    }

    fun showToast(message: String) {
        _toastMessage.value = message
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun notifyMediaChanged() {
        _mediaChangeCounter.value = _mediaChangeCounter.value + 1L
    }

    fun prepareOutputFile(folder: MediaFolder, type: MediaType): File {
        mediaStorageManager.ensureFolderDirectoryExists(folder)
        return mediaStorageManager.prepareOutputFile(folder, type)
    }

    // =========================================================================
    // WIDGET ENTRY POINTS (Always -> Inbox, Switch Modes Atomically, Global DND)
    // =========================================================================

    fun handleWidgetVideoToggle() {
        scope.launch(Dispatchers.Main.immediate) {
            recordingMutex.withLock {
                when (_captureState.value) {
                    CaptureState.RECORDING_VIDEO -> {
                        stopVideoInternalAndAwait(restoreDnd = true, stopService = true)
                    }
                    CaptureState.RECORDING_AUDIO -> {
                        if (!hasPermission(Manifest.permission.CAMERA)) {
                            postActionablePermissionNotification(
                                title = "Camera permission needed",
                                message = "Tap to open FolderCam and grant Camera permission for background video.",
                                requestDndAccess = false
                            )
                            return@withLock
                        }
                        stopAudioInternal(restoreDnd = false, stopService = false)
                        startBackgroundWidgetVideoInternal()
                    }
                    CaptureState.IDLE -> {
                        if (!hasPermission(Manifest.permission.CAMERA)) {
                            postActionablePermissionNotification(
                                title = "Camera permission needed",
                                message = "Tap to open FolderCam and grant Camera permission for background video.",
                                requestDndAccess = false
                            )
                            RecordingForegroundService.stop(appContext)
                            return@withLock
                        }
                        startBackgroundWidgetVideoInternal()
                    }
                }
            }
        }
    }

    fun handleWidgetAudioToggle() {
        scope.launch(Dispatchers.Main.immediate) {
            recordingMutex.withLock {
                when (_captureState.value) {
                    CaptureState.RECORDING_AUDIO -> {
                        stopAudioInternal(restoreDnd = true, stopService = true)
                    }
                    CaptureState.RECORDING_VIDEO -> {
                        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                            postActionablePermissionNotification(
                                title = "Microphone permission needed",
                                message = "Tap to open FolderCam and grant Microphone permission for background audio.",
                                requestDndAccess = false
                            )
                            return@withLock
                        }
                        stopVideoInternalAndAwait(restoreDnd = false, stopService = false)
                        val inbox = folderRepository.getInboxFolder()
                        startAudioInternal(targetFolder = inbox, origin = RecordingOrigin.WIDGET)
                    }
                    CaptureState.IDLE -> {
                        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                            postActionablePermissionNotification(
                                title = "Microphone permission needed",
                                message = "Tap to open FolderCam and grant Microphone permission for background audio.",
                                requestDndAccess = false
                            )
                            RecordingForegroundService.stop(appContext)
                            return@withLock
                        }
                        val inbox = folderRepository.getInboxFolder()
                        startAudioInternal(targetFolder = inbox, origin = RecordingOrigin.WIDGET)
                    }
                }
            }
        }
    }

    fun stopActiveRecordingFromNotification() {
        scope.launch(Dispatchers.Main.immediate) {
            recordingMutex.withLock {
                when (_captureState.value) {
                    CaptureState.RECORDING_AUDIO -> stopAudioInternal(restoreDnd = true, stopService = true)
                    CaptureState.RECORDING_VIDEO -> stopVideoInternalAndAwait(restoreDnd = true, stopService = true)
                    CaptureState.IDLE -> {
                        restoreDndSafely()
                        RecordingForegroundService.stop(appContext)
                        notifyWidgetUpdated()
                    }
                }
            }
        }
    }

    // =========================================================================
    // IN-APP AUDIO & VIDEO RECORDING (Global DND + Background Continuity)
    // =========================================================================

    fun startInAppAudioRecording(folder: MediaFolder) {
        scope.launch(Dispatchers.Main.immediate) {
            recordingMutex.withLock {
                if (_captureState.value != CaptureState.IDLE) return@withLock
                cameraManager.unbindCameraIfIdle()
                startAudioInternal(targetFolder = folder, origin = RecordingOrigin.IN_APP)
            }
        }
    }

    fun stopAudioRecording() {
        scope.launch(Dispatchers.Main.immediate) {
            recordingMutex.withLock {
                if (_captureState.value == CaptureState.RECORDING_AUDIO) {
                    stopAudioInternal(restoreDnd = true, stopService = true)
                }
            }
        }
    }

    fun startInAppVideoRecording(folder: MediaFolder, enableAudio: Boolean) {
        scope.launch(Dispatchers.Main.immediate) {
            recordingMutex.withLock {
                if (_captureState.value != CaptureState.IDLE) return@withLock
                val outputFile = prepareOutputFile(folder, MediaType.VIDEO)
                enableDndSafely(notifyIfMissing = false)
                pendingVideoFolder = folder
                val deferred = CompletableDeferred<File?>()
                activeVideoFinalizeDeferred = deferred

                cameraManager.startVideoRecording(
                    outputFile = outputFile,
                    enableAudio = enableAudio,
                    onVideoStarted = {
                        onVideoStartedInternal(folder, RecordingOrigin.IN_APP)
                    },
                    onVideoSaved = { savedFile ->
                        handleVideoSavedCallback(folder, savedFile, deferred)
                    },
                    onError = { err ->
                        handleVideoErrorCallback(err, deferred)
                    }
                )
            }
        }
    }

    fun stopVideoRecording() {
        scope.launch(Dispatchers.Main.immediate) {
            recordingMutex.withLock {
                if (_captureState.value == CaptureState.RECORDING_VIDEO) {
                    stopVideoInternalAndAwait(restoreDnd = true, stopService = true)
                }
            }
        }
    }

    private fun startBackgroundWidgetVideoInternal() {
        val inbox = folderRepository.getInboxFolder()
        val outputFile = prepareOutputFile(inbox, MediaType.VIDEO)
        val hasAudioPerm = hasPermission(Manifest.permission.RECORD_AUDIO)
        val lensPref = folderRepository.videoLensPreference.value

        enableDndSafely(notifyIfMissing = true)
        pendingVideoFolder = inbox
        val deferred = CompletableDeferred<File?>()
        activeVideoFinalizeDeferred = deferred

        cameraManager.startBackgroundVideoRecording(
            outputFile = outputFile,
            enableAudio = hasAudioPerm,
            lensPreference = lensPref,
            onVideoStarted = {
                onVideoStartedInternal(inbox, RecordingOrigin.WIDGET)
            },
            onVideoSaved = { savedFile ->
                handleVideoSavedCallback(inbox, savedFile, deferred)
            },
            onError = { err ->
                handleVideoErrorCallback(err, deferred)
            }
        )
    }

    private fun onVideoStartedInternal(folder: MediaFolder, origin: RecordingOrigin) {
        _captureState.value = CaptureState.RECORDING_VIDEO
        _recordingOrigin.value = origin
        _activeRecordingFolder.value = folder
        _videoDurationSeconds.value = 0

        RecordingForegroundService.start(
            context = appContext,
            recordingType = RecordingForegroundService.TYPE_VIDEO,
            folderName = folder.name,
            sdcardPath = folder.sdcardPath
        )

        videoTimerJob?.cancel()
        videoTimerJob = scope.launch(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                delay(500)
                _videoDurationSeconds.value = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            }
        }
        notifyWidgetUpdated()
    }

    private fun handleVideoSavedCallback(
        folder: MediaFolder,
        savedFile: File,
        deferred: CompletableDeferred<File?>
    ) {
        videoTimerJob?.cancel()
        videoTimerJob = null
        val isSwitching = activeVideoFinalizeDeferred !== deferred
        if (!deferred.isCompleted) {
            deferred.complete(savedFile)
        }
        if (activeVideoFinalizeDeferred === deferred) {
            activeVideoFinalizeDeferred = null
        }

        if (!isSwitching) {
            _captureState.value = CaptureState.IDLE
            _recordingOrigin.value = null
            _activeRecordingFolder.value = null
            pendingVideoFolder = null
        }

        scope.launch {
            val result = mediaStorageManager.finalizeCapturedFile(folder, savedFile, MediaType.VIDEO)
            if (result.savedToPublicSdcard) {
                showToast("Video saved to ${folder.sdcardPath}")
            } else {
                if (result.requiresAllFilesAccess) {
                    _needsAllFilesAccessBanner.value = true
                }
                showToast(result.errorMessage ?: "Saved to app storage (${folder.sdcardPath})")
            }
            notifyMediaChanged()
            notifyWidgetUpdated()
        }
    }

    private fun handleVideoErrorCallback(
        err: String,
        deferred: CompletableDeferred<File?>
    ) {
        videoTimerJob?.cancel()
        videoTimerJob = null
        if (!deferred.isCompleted) {
            deferred.complete(null)
        }
        if (activeVideoFinalizeDeferred === deferred) {
            activeVideoFinalizeDeferred = null
        }
        _captureState.value = CaptureState.IDLE
        _recordingOrigin.value = null
        _activeRecordingFolder.value = null
        pendingVideoFolder = null
        restoreDndSafely()
        RecordingForegroundService.stop(appContext)
        showToast("Video error: $err")
        notifyWidgetUpdated()
    }

    private suspend fun stopVideoInternalAndAwait(restoreDnd: Boolean, stopService: Boolean) {
        val deferred = activeVideoFinalizeDeferred
        cameraManager.stopVideoRecording()
        if (deferred != null) {
            withTimeoutOrNull(4500L) {
                deferred.await()
            }
        }
        videoTimerJob?.cancel()
        videoTimerJob = null
        activeVideoFinalizeDeferred = null
        _captureState.value = CaptureState.IDLE
        _recordingOrigin.value = null
        _activeRecordingFolder.value = null
        pendingVideoFolder = null

        if (restoreDnd) {
            restoreDndSafely()
        }
        if (stopService) {
            RecordingForegroundService.stop(appContext)
        }
        notifyWidgetUpdated()
    }

    private fun startAudioInternal(targetFolder: MediaFolder, origin: RecordingOrigin): Boolean {
        cameraManager.unbindCameraIfIdle()
        mediaStorageManager.ensureFolderDirectoryExists(targetFolder)
        val outputFile = prepareOutputFile(targetFolder, MediaType.AUDIO)
        val fallbackFile = mediaStorageManager.prepareStagingFallbackFile(targetFolder, outputFile.name)

        enableDndSafely(notifyIfMissing = (origin == RecordingOrigin.WIDGET))
        pendingAudioFolder = targetFolder

        val started = audioRecorderManager.startRecording(
            outputFile = outputFile,
            scope = scope,
            fallbackFile = fallbackFile,
            nextSegmentFileProvider = {
                prepareOutputFile(targetFolder, MediaType.AUDIO)
            },
            fallbackProvider = { name ->
                mediaStorageManager.prepareStagingFallbackFile(targetFolder, name)
            },
            onSegmentFinalized = { segmentFile ->
                finalizeAudioSegmentAsync(targetFolder, segmentFile, silent = true)
            },
            onCallInterruptionNotice = { notice ->
                showToast(notice)
            }
        )

        if (started) {
            _captureState.value = CaptureState.RECORDING_AUDIO
            _recordingOrigin.value = origin
            _activeRecordingFolder.value = targetFolder
            RecordingForegroundService.start(
                context = appContext,
                recordingType = RecordingForegroundService.TYPE_AUDIO,
                folderName = targetFolder.name,
                sdcardPath = targetFolder.sdcardPath
            )
            notifyWidgetUpdated()
        } else {
            pendingAudioFolder = null
            restoreDndSafely()
            RecordingForegroundService.stop(appContext)
            showToast("Failed to start audio recording")
            notifyWidgetUpdated()
        }
        return started
    }

    private fun stopAudioInternal(restoreDnd: Boolean, stopService: Boolean) {
        val file = audioRecorderManager.stopRecording()
        val folder = pendingAudioFolder ?: _activeRecordingFolder.value
        pendingAudioFolder = null

        _captureState.value = CaptureState.IDLE
        _recordingOrigin.value = null
        _activeRecordingFolder.value = null

        if (restoreDnd) {
            restoreDndSafely()
        }
        if (stopService) {
            RecordingForegroundService.stop(appContext)
        }
        notifyWidgetUpdated()

        if (file != null && file.exists() && folder != null) {
            finalizeAudioSegmentAsync(folder, file, silent = false)
        }
    }

    private fun finalizeAudioSegmentAsync(folder: MediaFolder, file: File, silent: Boolean) {
        scope.launch {
            val result = mediaStorageManager.finalizeCapturedFile(folder, file, MediaType.AUDIO)
            if (!silent) {
                if (result.savedToPublicSdcard) {
                    showToast("Audio saved to ${folder.sdcardPath}")
                } else {
                    if (result.requiresAllFilesAccess) {
                        _needsAllFilesAccessBanner.value = true
                    }
                    showToast(result.errorMessage ?: "Saved to app storage (${folder.sdcardPath})")
                }
            }
            notifyMediaChanged()
        }
    }

    // =========================================================================
    // 100-PHOTO RAM-BUFFERED BURST & SINGLE PHOTO CAPTURE
    // =========================================================================

    fun captureSinglePhoto(folder: MediaFolder, fromBurstLoop: Boolean = false) {
        if (fromBurstLoop && burstSessionCount.get() >= CameraManager.MAX_BURST_PHOTOS) {
            stopPhotoBurst()
            showToast("Burst complete (${CameraManager.MAX_BURST_PHOTOS} photos)")
            return
        }

        if (!cameraManager.canEnqueueBurstShot()) {
            if (!fromBurstLoop) {
                showToast("Finishing previous shots…")
            }
            return
        }

        mediaStorageManager.ensureFolderDirectoryExists(folder)
        photoFeedbackResetJob?.cancel()

        if (fromBurstLoop) {
            val nextNumber = burstSessionCount.incrementAndGet()
            if (nextNumber > CameraManager.MAX_BURST_PHOTOS) {
                burstSessionCount.decrementAndGet()
                stopPhotoBurst()
                return
            }

            _photoFeedback.update { current ->
                current.copy(
                    triggerCounter = current.triggerCounter + 1,
                    inFlightCounter = current.inFlightCounter + 1,
                    bufferedRamCount = burstBufferManager.getRamFrameCount(),
                    isBurstActive = true,
                    isDraining = true,
                    lastFlashTriggerId = current.lastFlashTriggerId + 1L,
                    targetFolderPath = folder.sdcardPath,
                    summaryMessage = null,
                    isVisible = true
                )
            }

            cameraManager.takeBurstPhotoToMemory(
                onSuccess = { jpegBytes ->
                    burstBufferManager.enqueueCapturedBytes(folder, jpegBytes)
                    _photoFeedback.update { current ->
                        current.copy(
                            bufferedRamCount = burstBufferManager.getRamFrameCount(),
                            isDraining = burstBufferManager.getPendingCount() > 0 || current.inFlightCounter > 0
                        )
                    }
                },
                onError = { err ->
                    onPhotoCaptureFailed(err, silentIfBurst = true)
                }
            )
        } else {
            val stagedFile = mediaStorageManager.prepareFastPhotoFile(folder)
            _photoFeedback.update { current ->
                current.copy(
                    triggerCounter = current.triggerCounter + 1,
                    inFlightCounter = current.inFlightCounter + 1,
                    isBurstActive = current.isBurstActive,
                    lastFlashTriggerId = current.lastFlashTriggerId + 1L,
                    targetFolderPath = folder.sdcardPath,
                    summaryMessage = null,
                    isVisible = true
                )
            }

            cameraManager.takePhoto(
                outputFile = stagedFile,
                onSuccess = { savedFile ->
                    onPhotoCapturedToFolder(folder, savedFile)
                },
                onError = { err ->
                    onPhotoCaptureFailed(err, silentIfBurst = false)
                }
            )
        }
    }

    fun startPhotoBurst(folder: MediaFolder) {
        if (_captureState.value != CaptureState.IDLE) return

        mediaStorageManager.ensureFolderDirectoryExists(folder)
        burstJob?.cancel()
        photoFeedbackResetJob?.cancel()
        burstSessionCount.set(0)

        _photoFeedback.update { current ->
            current.copy(
                isBurstActive = true,
                targetFolderPath = folder.sdcardPath,
                summaryMessage = null,
                isVisible = true
            )
        }

        burstJob = scope.launch(Dispatchers.Main.immediate) {
            captureSinglePhoto(folder = folder, fromBurstLoop = true)
            while (isActive && burstSessionCount.get() < CameraManager.MAX_BURST_PHOTOS) {
                delay(90)
                if (cameraManager.canEnqueueBurstShot()) {
                    captureSinglePhoto(folder = folder, fromBurstLoop = true)
                }
            }
            if (burstSessionCount.get() >= CameraManager.MAX_BURST_PHOTOS) {
                stopPhotoBurst()
            }
        }
    }

    fun stopPhotoBurst() {
        val wasBursting = burstJob != null || _photoFeedback.value.isBurstActive
        burstJob?.cancel()
        burstJob = null
        if (wasBursting) {
            _photoFeedback.update { current ->
                val doneSaving = current.inFlightCounter == 0 && current.savedCounter > 0
                current.copy(
                    isBurstActive = false,
                    isDraining = current.inFlightCounter > 0,
                    summaryMessage = if (doneSaving) {
                        buildSavedSummary(current.savedCounter, current.targetFolderPath)
                    } else {
                        current.summaryMessage
                    }
                )
            }
            schedulePhotoFeedbackResetIfIdle()
        }
    }

    fun onPhotoCapturedToFolder(folder: MediaFolder, file: File) {
        scope.launch(Dispatchers.IO) {
            val result = mediaStorageManager.finalizeCapturedFile(folder, file, MediaType.PHOTO)
            onBurstFrameSaved(folder, result)
        }
    }

    private fun onBurstFrameSaved(folder: MediaFolder, result: MediaSaveResult) {
        if (result.requiresAllFilesAccess) {
            _needsAllFilesAccessBanner.value = true
        }

        var shouldRefreshAndReset = false
        _photoFeedback.update { current ->
            val nextSaved = current.savedCounter + 1
            val nextInFlight = (current.inFlightCounter - 1).coerceAtLeast(0)
            val settled = nextInFlight == 0 && !current.isBurstActive
            if (settled) {
                shouldRefreshAndReset = true
            }
            val summary = if (settled) {
                if (result.savedToPublicSdcard) {
                    buildSavedSummary(nextSaved, folder.sdcardPath)
                } else {
                    result.errorMessage ?: "Saved to app storage (${folder.sdcardPath})"
                }
            } else {
                null
            }
            current.copy(
                triggerCounter = maxOf(current.triggerCounter, nextSaved),
                savedCounter = nextSaved,
                inFlightCounter = nextInFlight,
                bufferedRamCount = burstBufferManager.getRamFrameCount(),
                isDraining = nextInFlight > 0,
                targetFolderPath = folder.sdcardPath,
                summaryMessage = summary,
                isVisible = true
            )
        }

        if (shouldRefreshAndReset) {
            notifyMediaChanged()
            schedulePhotoFeedbackResetIfIdle()
        }
    }

    private fun onPhotoCaptureFailed(message: String, silentIfBurst: Boolean) {
        var settled = false
        _photoFeedback.update { current ->
            val nextInFlight = (current.inFlightCounter - 1).coerceAtLeast(0)
            settled = nextInFlight == 0 && !current.isBurstActive
            val summary = if (settled && current.savedCounter > 0) {
                buildSavedSummary(current.savedCounter, current.targetFolderPath)
            } else {
                current.summaryMessage
            }
            current.copy(
                inFlightCounter = nextInFlight,
                bufferedRamCount = burstBufferManager.getRamFrameCount(),
                isDraining = nextInFlight > 0,
                summaryMessage = summary,
                isVisible = current.savedCounter > 0 || nextInFlight > 0 || current.isBurstActive
            )
        }
        if (!silentIfBurst) {
            showToast(message)
        }
        if (settled) {
            notifyMediaChanged()
            schedulePhotoFeedbackResetIfIdle()
        }
    }

    private fun buildSavedSummary(count: Int, sdcardPath: String): String {
        return if (count > 1) {
            "Saved $count photos to $sdcardPath"
        } else {
            "Saved photo to $sdcardPath"
        }
    }

    private fun schedulePhotoFeedbackResetIfIdle() {
        val snap = _photoFeedback.value
        if (snap.isBurstActive || snap.inFlightCounter > 0) return

        photoFeedbackResetJob?.cancel()
        photoFeedbackResetJob = scope.launch {
            delay(2400)
            val latest = _photoFeedback.value
            if (!latest.isBurstActive && latest.inFlightCounter == 0) {
                _photoFeedback.value = PhotoCaptureFeedbackState()
            }
        }
    }

    // =========================================================================
    // HELPERS: DND, WIDGET SYNC, PERMISSION NOTIFICATIONS
    // =========================================================================

    private fun enableDndSafely(notifyIfMissing: Boolean) {
        val granted = dndController.hasPolicyAccess()
        _dndPolicyGranted.value = granted
        if (granted) {
            val enabled = dndController.enableDndForRecording()
            _isDndActive.value = enabled || dndController.isDndActiveByApp()
        } else {
            _isDndActive.value = false
            if (notifyIfMissing) {
                postDndAccessPromptNotification()
            }
        }
    }

    private fun restoreDndSafely() {
        dndController.restoreDndAfterRecording()
        _isDndActive.value = dndController.isDndActiveByApp()
    }

    private fun notifyWidgetUpdated() {
        scope.launch(Dispatchers.IO) {
            FolderCamQuickWidget.updateAllWidgets(appContext)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun postActionablePermissionNotification(
        title: String,
        message: String,
        requestDndAccess: Boolean
    ) {
        try {
            ensureAlertNotificationChannel()
            val intent = if (requestDndAccess) {
                DndController.createPolicyAccessSettingsIntent()
            } else {
                Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                appContext,
                if (requestDndAccess) 4011 else 4010,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(appContext, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .build()
            nm.notify(if (requestDndAccess) 4011 else 4010, notification)
        } catch (e: Exception) {
            // Ignore in headless tests
        }
    }

    private fun postDndAccessPromptNotification() {
        postActionablePermissionNotification(
            title = "Allow Do Not Disturb Access",
            message = "Recording started in Inbox. Tap to allow DND control so calls don't interrupt recordings.",
            requestDndAccess = true
        )
    }

    private fun ensureAlertNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "FolderCam Alerts & Permissions",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val ALERT_CHANNEL_ID = "foldercam_alerts_channel"

        @Volatile
        private var instance: RecordingCoordinator? = null

        fun getInstance(context: Context): RecordingCoordinator {
            return instance ?: synchronized(this) {
                instance ?: RecordingCoordinator(context.applicationContext).also {
                    instance = it
                }
            }
        }

        internal fun resetForTests() {
            synchronized(this) {
                instance?.cameraManager?.release()
                instance = null
            }
        }
    }
}
