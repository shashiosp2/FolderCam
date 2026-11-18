package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioRecorderManager
import com.example.camera.CameraManager
import com.example.data.FolderRepository
import com.example.data.MediaStorageManager
import com.example.model.CaptureOrientation
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
    val captureOrientation: StateFlow<CaptureOrientation> = folderRepository.captureOrientation

    // Default mode is AUDIO (index 0), then PHOTO (index 1), then VIDEO (index 2)
    private val _recorderMode = MutableStateFlow(RecorderMode.AUDIO)
    val recorderMode: StateFlow<RecorderMode> = _recorderMode.asStateFlow()

    private val _selectedFolderIdByMode = MutableStateFlow<Map<RecorderMode, String>>(emptyMap())
    private val _galleryFolderId = MutableStateFlow<String?>(null)

    private val _foldersForCurrentMode = MutableStateFlow<List<MediaFolder>>(emptyList())
    val foldersForCurrentMode: StateFlow<List<MediaFolder>> = _foldersForCurrentMode.asStateFlow()

    private val _selectedFolder = MutableStateFlow<MediaFolder?>(null)
    val selectedFolder: StateFlow<MediaFolder?> = _selectedFolder.asStateFlow()

    private val _galleryFolder = MutableStateFlow<MediaFolder?>(null)
    val galleryFolder: StateFlow<MediaFolder?> = _galleryFolder.asStateFlow()

    private fun recomputeModeFolderState() {
        val list = folders.value
        val mode = _recorderMode.value
        val globalId = selectedFolderId.value
        val byModeMap = _selectedFolderIdByMode.value
        val modeFolders = list.filter { it.isEnabledFor(mode) }
        val activeCaptureFolder = if (modeFolders.isEmpty()) {
            null
        } else {
            val preferredForMode = byModeMap[mode]
            modeFolders.find { it.id == preferredForMode }
                ?: modeFolders.find { it.id == globalId }
                ?: modeFolders.first()
        }
        val explicitGallery = _galleryFolderId.value?.let { gid -> list.find { it.id == gid } }
        val activeGalleryFolder = explicitGallery
            ?: activeCaptureFolder
            ?: list.find { it.id == globalId }
            ?: list.firstOrNull()

        _foldersForCurrentMode.value = modeFolders
        _selectedFolder.value = activeCaptureFolder
        _galleryFolder.value = activeGalleryFolder
    }

    private fun activeFolder(): MediaFolder? = selectedFolder.value

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
        recomputeModeFolderState()
        cameraManager.setCaptureOrientation(folderRepository.captureOrientation.value)
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
            galleryFolder.collect {
                refreshFolderMedia()
            }
        }
        viewModelScope.launch {
            folders.collect {
                recomputeModeFolderState()
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
            cameraManager.setCaptureOrientation(folderRepository.captureOrientation.value)
        }
        recomputeModeFolderState()
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

    fun setCaptureOrientation(orientation: CaptureOrientation) {
        if (_captureState.value == CaptureState.RECORDING_VIDEO) return
        folderRepository.setCaptureOrientation(orientation)
        cameraManager.setCaptureOrientation(orientation)
    }

    fun restoreSettingsFromSdcard(): Boolean {
        val restored = folderRepository.restoreFromSdcardConfigFile()
        if (restored) {
            cameraManager.setCaptureOrientation(folderRepository.captureOrientation.value)
            recomputeModeFolderState()
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
            cameraManager.setCaptureOrientation(folderRepository.captureOrientation.value)
            recomputeModeFolderState()
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
        recomputeModeFolderState()
        if (mode == RecorderMode.AUDIO) {
            cameraManager.unbindCameraIfIdle()
        }
    }

    fun selectFolder(folder: MediaFolder) {
        val currentMode = _recorderMode.value
        if (folder.isEnabledFor(currentMode)) {
            _selectedFolderIdByMode.update { it + (currentMode to folder.id) }
        }
        folderRepository.selectFolder(folder.id)
        recomputeModeFolderState()
    }

    fun selectGalleryFolder(folder: MediaFolder) {
        _galleryFolderId.value = folder.id
        recomputeModeFolderState()
    }

    fun addFolder(
        name: String,
        sdcardPath: String,
        colorHex: String = "#38BDF8",
        treeUri: String? = null,
        enableAudio: Boolean = true,
        enablePhoto: Boolean = true,
        enableVideo: Boolean = true
    ): MediaFolder {
        val created = folderRepository.addFolder(
            name = name,
            sdcardPath = sdcardPath,
            colorHex = colorHex,
            treeUri = treeUri,
            enableAudio = enableAudio,
            enablePhoto = enablePhoto,
            enableVideo = enableVideo
        )
        _selectedFolderIdByMode.update { map ->
            val next = map.toMutableMap()
            RecorderMode.entries.forEach { mode ->
                if (created.isEnabledFor(mode) && (!next.containsKey(mode) || mode == _recorderMode.value)) {
                    next[mode] = created.id
                }
            }
            next
        }
        recomputeModeFolderState()
        showToast("Added folder '${created.name}' -> ${created.sdcardPath}")
        refreshAllFolderCounts()
        return created
    }

    fun updateFolder(
        folderId: String,
        name: String,
        sdcardPath: String,
        colorHex: String,
        treeUri: String? = null,
        enableAudio: Boolean? = null,
        enablePhoto: Boolean? = null,
        enableVideo: Boolean? = null
    ) {
        folderRepository.updateFolder(
            folderId = folderId,
            name = name,
            sdcardPath = sdcardPath,
            colorHex = colorHex,
            treeUri = treeUri,
            enableAudio = enableAudio,
            enablePhoto = enablePhoto,
            enableVideo = enableVideo
        )
        recomputeModeFolderState()
        showToast("Updated folder '$name'")
        refreshAllFolderCounts()
        refreshFolderMedia()
    }

    fun setFolderModeEnabled(folderId: String, mode: RecorderMode, enabled: Boolean) {
        folderRepository.setFolderModeEnabled(folderId, mode, enabled)
        recomputeModeFolderState()
    }

    fun deleteFolder(folderId: String) {
        val folder = folders.value.find { it.id == folderId }
        val name = folder?.name ?: "Folder"
        if (folderRepository.deleteFolder(folderId)) {
            _selectedFolderIdByMode.update { map -> map.filterValues { it != folderId } }
            if (_galleryFolderId.value == folderId) {
                _galleryFolderId.value = null
            }
            recomputeModeFolderState()
            showToast("Removed folder '$name'")
            refreshAllFolderCounts()
            refreshFolderMedia()
        }
    }

    fun moveFolder(fromIndex: Int, toIndex: Int) {
        folderRepository.moveFolder(fromIndex, toIndex)
        recomputeModeFolderState()
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
        _galleryFolderId.value = selectedFolder.value?.id
        recomputeModeFolderState()
        _isGalleryOpen.value = true
        refreshFolderMedia()
    }

    fun closeGallery() {
        _isGalleryOpen.value = false
        _galleryFolderId.value = null
        recomputeModeFolderState()
    }

    private fun promptMissingFolderForMode(mode: RecorderMode) {
        val msg = if (folders.value.isEmpty()) {
            "Please specify at least 1 folder in Settings first"
        } else {
            val modeLabel = when (mode) {
                RecorderMode.AUDIO -> "Audio"
                RecorderMode.PHOTO -> "Photos"
                RecorderMode.VIDEO -> "Video"
            }
            "Enable at least 1 folder for $modeLabel in Settings"
        }
        showToast(msg)
        _isSettingsOpen.value = true
    }

    fun prepareOutputFile(type: MediaType): File? {
        val folder = activeFolder() ?: run {
            val mode = when (type) {
                MediaType.AUDIO -> RecorderMode.AUDIO
                MediaType.PHOTO -> RecorderMode.PHOTO
                MediaType.VIDEO -> RecorderMode.VIDEO
            }
            promptMissingFolderForMode(mode)
            return null
        }
        return mediaStorageManager.prepareOutputFile(folder, type)
    }

    fun captureSinglePhoto(fromBurstLoop: Boolean = false) {
        val folder = activeFolder() ?: run {
            stopPhotoBurst()
            promptMissingFolderForMode(RecorderMode.PHOTO)
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
            promptMissingFolderForMode(RecorderMode.PHOTO)
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
            promptMissingFolderForMode(RecorderMode.AUDIO)
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
        val folder = galleryFolder.value ?: selectedFolder.value
        viewModelScope.launch(Dispatchers.IO) {
            if (mediaStorageManager.deleteMediaItem(item, folder)) {
                refreshFolderMedia()
                refreshAllFolderCounts()
            }
        }
    }

    fun refreshFolderMedia() {
        val folder = galleryFolder.value
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
