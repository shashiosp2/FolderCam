package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioRecorderManager
import com.example.camera.CameraManager
import com.example.data.FolderRepository
import com.example.data.MediaStorageManager
import com.example.model.CaptureState
import com.example.model.MediaFolder
import com.example.model.MediaItem
import com.example.model.MediaType
import com.example.model.PhotoCaptureFeedbackState
import com.example.model.RecorderMode
import com.example.model.RecordingStatus
import com.example.service.RecordingForegroundService
import com.example.service.RecordingServiceBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class FolderCamViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val folderRepository = FolderRepository(appContext)
    val mediaStorageManager = MediaStorageManager(appContext)
    val audioRecorderManager = AudioRecorderManager(appContext)
    val cameraManager = CameraManager(appContext)

    val folders: StateFlow<List<MediaFolder>> = folderRepository.folders
    val selectedFolderId: StateFlow<String?> = folderRepository.selectedFolderId
    val isDarkTheme: StateFlow<Boolean> = folderRepository.isDarkTheme

    val selectedFolder: StateFlow<MediaFolder?> = combine(folders, selectedFolderId) { list, id ->
        if (list.isEmpty()) {
            null
        } else {
            list.find { it.id == id } ?: list.first()
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        folderRepository.getSelectedFolder()
    )

    private fun activeFolder(): MediaFolder? = selectedFolder.value ?: folderRepository.getSelectedFolder()

    // Default mode is AUDIO (index 0), then PHOTO (index 1), then VIDEO (index 2)
    private val _recorderMode = MutableStateFlow(RecorderMode.AUDIO)
    val recorderMode: StateFlow<RecorderMode> = _recorderMode.asStateFlow()

    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    val audioStatus: StateFlow<RecordingStatus> = audioRecorderManager.status

    private val _videoDurationSeconds = MutableStateFlow(0)
    val videoDurationSeconds: StateFlow<Int> = _videoDurationSeconds.asStateFlow()

    private var videoTimerJob: Job? = null

    private val _folderCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val folderCounts: StateFlow<Map<String, Int>> = _folderCounts.asStateFlow()

    private val _currentFolderMedia = MutableStateFlow<List<MediaItem>>(emptyList())
    val currentFolderMedia: StateFlow<List<MediaItem>> = _currentFolderMedia.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _isGalleryOpen = MutableStateFlow(false)
    val isGalleryOpen: StateFlow<Boolean> = _isGalleryOpen.asStateFlow()

    private val _hasAllFilesAccess = MutableStateFlow(mediaStorageManager.hasAllFilesAccess())
    val hasAllFilesAccess: StateFlow<Boolean> = _hasAllFilesAccess.asStateFlow()

    private val _needsAllFilesAccessBanner = MutableStateFlow(false)
    val needsAllFilesAccessBanner: StateFlow<Boolean> = _needsAllFilesAccessBanner.asStateFlow()

    private val _photoFeedback = MutableStateFlow(PhotoCaptureFeedbackState())
    val photoFeedback: StateFlow<PhotoCaptureFeedbackState> = _photoFeedback.asStateFlow()

    private var burstJob: Job? = null
    private var photoFeedbackResetJob: Job? = null

    init {
        RecordingServiceBridge.onStopRequested = {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                when (_captureState.value) {
                    CaptureState.RECORDING_AUDIO -> stopAudioRecording()
                    CaptureState.RECORDING_VIDEO -> cameraManager.stopVideoRecording()
                    CaptureState.IDLE -> RecordingForegroundService.stop(appContext)
                }
            }
        }

        viewModelScope.launch {
            selectedFolder.collect {
                refreshFolderMedia()
            }
        }
        viewModelScope.launch {
            folders.collect {
                refreshAllFolderCounts()
            }
        }
    }

    fun onHostResume() {
        val granted = mediaStorageManager.hasAllFilesAccess()
        _hasAllFilesAccess.value = granted
        if (granted) {
            _needsAllFilesAccessBanner.value = false
            folderRepository.syncWithSdcardOnResume()
        }
        refreshAllFolderCounts()
        refreshFolderMedia()
    }

    fun dismissAllFilesAccessBanner() {
        _needsAllFilesAccessBanner.value = false
    }

    fun toggleDarkTheme() {
        folderRepository.toggleDarkTheme()
    }

    fun setDarkTheme(isDark: Boolean) {
        folderRepository.setDarkTheme(isDark)
    }

    fun restoreSettingsFromSdcard(): Boolean {
        val restored = folderRepository.restoreFromSdcardConfigFile()
        if (restored) {
            showToast("Restored settings from /sdcard/.foldercam")
            refreshAllFolderCounts()
            refreshFolderMedia()
        } else {
            showToast("Could not find or read /sdcard/.foldercam")
        }
        return restored
    }

    fun restoreSettingsFromJson(jsonString: String): Boolean {
        val restored = folderRepository.restoreFromJsonString(jsonString)
        if (restored) {
            showToast("Settings restored and synced to /sdcard/.foldercam")
            refreshAllFolderCounts()
            refreshFolderMedia()
        } else {
            showToast("Invalid .foldercam JSON format")
        }
        return restored
    }

    fun exportSettingsJson(): String {
        return folderRepository.toJsonString()
    }

    fun setRecorderMode(mode: RecorderMode) {
        if (_recorderMode.value == mode) return
        stopPhotoBurst()
        if (_captureState.value == CaptureState.RECORDING_AUDIO && mode != RecorderMode.AUDIO) {
            stopAudioRecording()
        }
        _recorderMode.value = mode
        if (mode == RecorderMode.AUDIO) {
            cameraManager.unbindCameraIfIdle()
        }
    }

    fun selectFolder(folder: MediaFolder) {
        folderRepository.selectFolder(folder.id)
    }

    fun addFolder(
        name: String,
        sdcardPath: String,
        colorHex: String = "#38BDF8",
        treeUri: String? = null
    ): MediaFolder {
        val created = folderRepository.addFolder(
            name = name,
            sdcardPath = sdcardPath,
            colorHex = colorHex,
            treeUri = treeUri
        )
        showToast("Added folder '${created.name}' -> ${created.sdcardPath}")
        refreshAllFolderCounts()
        return created
    }

    fun updateFolder(
        folderId: String,
        name: String,
        sdcardPath: String,
        colorHex: String,
        treeUri: String? = null
    ) {
        folderRepository.updateFolder(folderId, name, sdcardPath, colorHex, treeUri)
        showToast("Updated folder '$name'")
        refreshAllFolderCounts()
        refreshFolderMedia()
    }

    fun deleteFolder(folderId: String) {
        val folder = folders.value.find { it.id == folderId }
        val name = folder?.name ?: "Folder"
        if (folderRepository.deleteFolder(folderId)) {
            showToast("Removed folder '$name'")
            refreshAllFolderCounts()
            refreshFolderMedia()
        }
    }

    fun listSdcardDirectories(currentSdcardPath: String): List<String> {
        return mediaStorageManager.listSdcardDirectories(currentSdcardPath)
    }

    fun createSdcardDirectory(parentSdcardPath: String, newFolderName: String): String {
        return mediaStorageManager.createSdcardDirectory(parentSdcardPath, newFolderName)
    }

    fun resolveSdcardPathFromTreeUri(treeUri: Uri): String {
        return mediaStorageManager.resolveSdcardPathFromTreeUri(treeUri)
    }

    fun openSettings() {
        _isSettingsOpen.value = true
    }

    fun closeSettings() {
        _isSettingsOpen.value = false
    }

    fun openGallery() {
        _isGalleryOpen.value = true
        refreshFolderMedia()
    }

    fun closeGallery() {
        _isGalleryOpen.value = false
    }

    fun prepareOutputFile(type: MediaType): File? {
        val folder = activeFolder() ?: run {
            showToast("Please specify at least 1 folder in Settings first")
            _isSettingsOpen.value = true
            return null
        }
        return mediaStorageManager.prepareOutputFile(folder, type)
    }

    fun captureSinglePhoto(fromBurstLoop: Boolean = false) {
        val folder = activeFolder() ?: run {
            stopPhotoBurst()
            showToast("Please specify at least 1 folder in Settings first")
            _isSettingsOpen.value = true
            return
        }

        if (!cameraManager.canEnqueueBurstShot()) {
            if (!fromBurstLoop) {
                showToast("Finishing previous shots…")
            }
            return
        }

        photoFeedbackResetJob?.cancel()
        val stagedFile = mediaStorageManager.prepareFastPhotoFile(folder)

        _photoFeedback.update { current ->
            current.copy(
                triggerCounter = current.triggerCounter + 1,
                inFlightCounter = current.inFlightCounter + 1,
                isBurstActive = fromBurstLoop || current.isBurstActive,
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
                onPhotoCaptureFailed(err, silentIfBurst = fromBurstLoop)
            }
        )
    }

    fun startPhotoBurst() {
        val folder = activeFolder() ?: run {
            showToast("Please specify at least 1 folder in Settings first")
            _isSettingsOpen.value = true
            return
        }
        if (_captureState.value != CaptureState.IDLE) return

        burstJob?.cancel()
        photoFeedbackResetJob?.cancel()
        _photoFeedback.update { current ->
            current.copy(
                isBurstActive = true,
                targetFolderPath = folder.sdcardPath,
                summaryMessage = null,
                isVisible = true
            )
        }

        burstJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            captureSinglePhoto(fromBurstLoop = true)
            while (isActive) {
                delay(130)
                captureSinglePhoto(fromBurstLoop = true)
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

    fun onPhotoCaptured(file: File) {
        val folder = activeFolder() ?: return
        onPhotoCapturedToFolder(folder, file)
    }

    private fun onPhotoCapturedToFolder(folder: MediaFolder, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = mediaStorageManager.finalizeCapturedFile(folder, file, MediaType.PHOTO)
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
                    targetFolderPath = folder.sdcardPath,
                    summaryMessage = summary,
                    isVisible = true
                )
            }

            if (shouldRefreshAndReset) {
                refreshFolderMedia()
                refreshAllFolderCounts()
                schedulePhotoFeedbackResetIfIdle()
            }
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
                summaryMessage = summary,
                isVisible = current.savedCounter > 0 || nextInFlight > 0 || current.isBurstActive
            )
        }
        if (!silentIfBurst) {
            showToast(message)
        }
        if (settled) {
            refreshFolderMedia()
            refreshAllFolderCounts()
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
        photoFeedbackResetJob = viewModelScope.launch {
            delay(2400)
            val latest = _photoFeedback.value
            if (!latest.isBurstActive && latest.inFlightCounter == 0) {
                _photoFeedback.value = PhotoCaptureFeedbackState()
            }
        }
    }

    fun onVideoRecordingStarted() {
        val folder = selectedFolder.value
        _recorderMode.value = RecorderMode.VIDEO
        _captureState.value = CaptureState.RECORDING_VIDEO
        _videoDurationSeconds.value = 0

        RecordingForegroundService.start(
            context = appContext,
            recordingType = RecordingForegroundService.TYPE_VIDEO,
            folderName = folder?.name ?: "Folder",
            sdcardPath = folder?.sdcardPath ?: "/sdcard"
        )

        videoTimerJob?.cancel()
        videoTimerJob = viewModelScope.launch(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                delay(500)
                _videoDurationSeconds.value = ((System.currentTimeMillis() - startTime) / 1000).toInt()
            }
        }
    }

    fun onVideoSaved(file: File) {
        videoTimerJob?.cancel()
        videoTimerJob = null
        _captureState.value = CaptureState.IDLE
        RecordingForegroundService.stop(appContext)

        val folder = selectedFolder.value ?: return
        viewModelScope.launch {
            val result = mediaStorageManager.finalizeCapturedFile(folder, file, MediaType.VIDEO)
            if (result.savedToPublicSdcard) {
                showToast("Video saved to ${folder.sdcardPath}")
            } else {
                if (result.requiresAllFilesAccess) {
                    _needsAllFilesAccessBanner.value = true
                }
                showToast(result.errorMessage ?: "Saved to app storage (${folder.sdcardPath})")
            }
            refreshFolderMedia()
            refreshAllFolderCounts()
        }
    }

    fun onVideoError(message: String) {
        videoTimerJob?.cancel()
        videoTimerJob = null
        _captureState.value = CaptureState.IDLE
        RecordingForegroundService.stop(appContext)
        showToast("Video error: $message")
    }

    fun startAudioRecording(): Boolean {
        if (_captureState.value != CaptureState.IDLE) return false
        _recorderMode.value = RecorderMode.AUDIO
        cameraManager.unbindCameraIfIdle()
        val folder = selectedFolder.value ?: run {
            showToast("Please specify at least 1 folder in Settings first")
            _isSettingsOpen.value = true
            return false
        }
        val outputFile = prepareOutputFile(MediaType.AUDIO) ?: return false
        val fallbackFile = mediaStorageManager.prepareStagingFallbackFile(folder, outputFile.name)
        val started = audioRecorderManager.startRecording(
            outputFile = outputFile,
            scope = viewModelScope,
            fallbackFile = fallbackFile
        )
        if (started) {
            _captureState.value = CaptureState.RECORDING_AUDIO
            RecordingForegroundService.start(
                context = appContext,
                recordingType = RecordingForegroundService.TYPE_AUDIO,
                folderName = folder.name,
                sdcardPath = folder.sdcardPath
            )
        } else {
            showToast("Failed to start audio recording")
        }
        return started
    }

    fun stopAudioRecording() {
        val file = audioRecorderManager.stopRecording()
        _captureState.value = CaptureState.IDLE
        RecordingForegroundService.stop(appContext)

        val folder = selectedFolder.value
        if (file != null && file.exists() && folder != null) {
            viewModelScope.launch {
                val result = mediaStorageManager.finalizeCapturedFile(folder, file, MediaType.AUDIO)
                if (result.savedToPublicSdcard) {
                    showToast("Audio saved to ${folder.sdcardPath}")
                } else {
                    if (result.requiresAllFilesAccess) {
                        _needsAllFilesAccessBanner.value = true
                    }
                    showToast(result.errorMessage ?: "Saved to app storage (${folder.sdcardPath})")
                }
                refreshFolderMedia()
                refreshAllFolderCounts()
            }
        }
    }

    fun deleteMediaItem(item: MediaItem) {
        val folder = selectedFolder.value
        viewModelScope.launch(Dispatchers.IO) {
            if (mediaStorageManager.deleteMediaItem(item, folder)) {
                refreshFolderMedia()
                refreshAllFolderCounts()
            }
        }
    }

    fun refreshFolderMedia() {
        val folder = selectedFolder.value
        if (folder == null) {
            _currentFolderMedia.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val items = mediaStorageManager.getMediaItemsForFolder(folder)
            _currentFolderMedia.value = items
        }
    }

    fun refreshAllFolderCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            val countMap = mutableMapOf<String, Int>()
            for (folder in folders.value) {
                countMap[folder.id] = mediaStorageManager.getFolderMediaCount(folder)
            }
            _folderCounts.value = countMap
        }
    }

    fun showToast(message: String) {
        _toastMessage.value = message
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        burstJob?.cancel()
        photoFeedbackResetJob?.cancel()
        videoTimerJob?.cancel()
        audioRecorderManager.cancelRecording()
        cameraManager.release()
        RecordingForegroundService.stop(appContext)
    }
}
