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
import com.example.model.RecordingOrigin
import com.example.model.RecordingStatus
import com.example.model.VideoLensPreference
import com.example.service.RecordingCoordinator
import com.example.widget.FolderCamQuickWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class FolderCamViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val coordinator: RecordingCoordinator = RecordingCoordinator.getInstance(appContext)
    val folderRepository: FolderRepository = coordinator.folderRepository
    val mediaStorageManager: MediaStorageManager = coordinator.mediaStorageManager
    val audioRecorderManager: AudioRecorderManager = coordinator.audioRecorderManager
    val cameraManager: CameraManager = coordinator.cameraManager

    val folders: StateFlow<List<MediaFolder>> = folderRepository.folders
    val selectedFolderId: StateFlow<String?> = folderRepository.selectedFolderId
    val isDarkTheme: StateFlow<Boolean> = folderRepository.isDarkTheme
    val captureOrientation: StateFlow<CaptureOrientation> = folderRepository.captureOrientation
    val videoLensPreference: StateFlow<VideoLensPreference> = folderRepository.videoLensPreference

    val captureState: StateFlow<CaptureState> = coordinator.captureState
    val recordingOrigin: StateFlow<RecordingOrigin?> = coordinator.recordingOrigin
    val activeRecordingFolder: StateFlow<MediaFolder?> = coordinator.activeRecordingFolder
    val audioStatus: StateFlow<RecordingStatus> = coordinator.audioStatus
    val videoDurationSeconds: StateFlow<Int> = coordinator.videoDurationSeconds
    val isDndActive: StateFlow<Boolean> = coordinator.isDndActive
    val dndPolicyGranted: StateFlow<Boolean> = coordinator.dndPolicyGranted
    val hasAllFilesAccess: StateFlow<Boolean> = coordinator.hasAllFilesAccess
    val needsAllFilesAccessBanner: StateFlow<Boolean> = coordinator.needsAllFilesAccessBanner
    val toastMessage: StateFlow<String?> = coordinator.toastMessage
    val photoFeedback: StateFlow<PhotoCaptureFeedbackState> = coordinator.photoFeedback

    // Default mode is AUDIO (index 0), then PHOTO (index 1), then VIDEO (index 2)
    private val _recorderMode = MutableStateFlow(
        when (coordinator.captureState.value) {
            CaptureState.RECORDING_VIDEO -> RecorderMode.VIDEO
            CaptureState.RECORDING_AUDIO -> RecorderMode.AUDIO
            CaptureState.IDLE -> RecorderMode.AUDIO
        }
    )
    val recorderMode: StateFlow<RecorderMode> = _recorderMode.asStateFlow()

    private val _selectedFolderIdByMode = MutableStateFlow<Map<RecorderMode, String>>(emptyMap())
    private val _galleryFolderId = MutableStateFlow<String?>(null)

    private val _foldersForCurrentMode = MutableStateFlow<List<MediaFolder>>(emptyList())
    val foldersForCurrentMode: StateFlow<List<MediaFolder>> = _foldersForCurrentMode.asStateFlow()

    private val _selectedFolder = MutableStateFlow<MediaFolder?>(null)
    val selectedFolder: StateFlow<MediaFolder?> = _selectedFolder.asStateFlow()

    private val _galleryFolder = MutableStateFlow<MediaFolder?>(null)
    val galleryFolder: StateFlow<MediaFolder?> = _galleryFolder.asStateFlow()

    private val _folderCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val folderCounts: StateFlow<Map<String, Int>> = _folderCounts.asStateFlow()

    private val _currentFolderMedia = MutableStateFlow<List<MediaItem>>(emptyList())
    val currentFolderMedia: StateFlow<List<MediaItem>> = _currentFolderMedia.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _isGalleryOpen = MutableStateFlow(false)
    val isGalleryOpen: StateFlow<Boolean> = _isGalleryOpen.asStateFlow()

    init {
        recomputeModeFolderState()

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
        viewModelScope.launch {
            coordinator.mediaChangeCounter.collect {
                refreshFolderMedia()
                refreshAllFolderCounts()
            }
        }
        viewModelScope.launch {
            coordinator.captureState.collect { state ->
                when (state) {
                    CaptureState.RECORDING_VIDEO -> {
                        if (_recorderMode.value != RecorderMode.VIDEO) {
                            _recorderMode.value = RecorderMode.VIDEO
                            recomputeModeFolderState()
                        }
                    }
                    CaptureState.RECORDING_AUDIO -> {
                        if (_recorderMode.value != RecorderMode.AUDIO) {
                            _recorderMode.value = RecorderMode.AUDIO
                            recomputeModeFolderState()
                        }
                    }
                    CaptureState.IDLE -> Unit
                }
            }
        }
    }

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

    fun onHostResume() {
        coordinator.refreshSystemPermissions()
        if (hasAllFilesAccess.value) {
            _needsAllFilesAccessBanner_dismiss_if_granted()
            folderRepository.syncWithSdcardOnResume()
            cameraManager.setCaptureOrientation(folderRepository.captureOrientation.value)
            cameraManager.setPreferredVideoLens(folderRepository.videoLensPreference.value)
        } else {
            mediaStorageManager.ensureAllFolderDirectoriesExist(folders.value)
        }
        when (captureState.value) {
            CaptureState.RECORDING_VIDEO -> _recorderMode.value = RecorderMode.VIDEO
            CaptureState.RECORDING_AUDIO -> _recorderMode.value = RecorderMode.AUDIO
            CaptureState.IDLE -> Unit
        }
        recomputeModeFolderState()
        refreshAllFolderCounts()
        refreshFolderMedia()
    }

    private fun _needsAllFilesAccessBanner_dismiss_if_granted() {
        coordinator.dismissAllFilesAccessBanner()
    }

    fun dismissAllFilesAccessBanner() {
        coordinator.dismissAllFilesAccessBanner()
    }

    fun toggleDarkTheme() {
        folderRepository.toggleDarkTheme()
    }

    fun setDarkTheme(isDark: Boolean) {
        folderRepository.setDarkTheme(isDark)
    }

    fun setCaptureOrientation(orientation: CaptureOrientation) {
        if (captureState.value == CaptureState.RECORDING_VIDEO) return
        folderRepository.setCaptureOrientation(orientation)
        cameraManager.setCaptureOrientation(orientation)
    }

    fun setVideoLensPreference(preference: VideoLensPreference) {
        folderRepository.setVideoLensPreference(preference)
        cameraManager.setPreferredVideoLens(preference)
        viewModelScope.launch(Dispatchers.IO) {
            FolderCamQuickWidget.updateAllWidgets(appContext)
        }
    }

    fun pinQuickWidgetToHomeScreen() {
        viewModelScope.launch {
            val pinned = FolderCamQuickWidget.requestPinToHomeScreen(appContext)
            if (pinned) {
                showToast("1x1 widget pin requested")
            } else {
                showToast("Long-press home screen -> Widgets -> FolderCam Quick (1x1)")
            }
        }
    }

    fun restoreSettingsFromSdcard(): Boolean {
        val restored = folderRepository.restoreFromSdcardConfigFile()
        if (restored) {
            cameraManager.setCaptureOrientation(folderRepository.captureOrientation.value)
            cameraManager.setPreferredVideoLens(folderRepository.videoLensPreference.value)
            mediaStorageManager.ensureAllFolderDirectoriesExist(folderRepository.folders.value)
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
            cameraManager.setPreferredVideoLens(folderRepository.videoLensPreference.value)
            mediaStorageManager.ensureAllFolderDirectoriesExist(folderRepository.folders.value)
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
        if (captureState.value != CaptureState.IDLE) return
        stopPhotoBurst()
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
        mediaStorageManager.ensureFolderDirectoryExists(created)
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
        folders.value.find { it.id == folderId }?.let {
            mediaStorageManager.ensureFolderDirectoryExists(it)
        }
        recomputeModeFolderState()
        showToast("Updated folder '$name'")
        refreshAllFolderCounts()
        refreshFolderMedia()
    }

    fun setFolderModeEnabled(folderId: String, mode: RecorderMode, enabled: Boolean) {
        val target = folders.value.find { it.id == folderId }
        if (target?.isInbox == true) {
            showToast("Inbox is mandatory for Audio, Photos, and Video")
            return
        }
        folderRepository.setFolderModeEnabled(folderId, mode, enabled)
        recomputeModeFolderState()
    }

    fun deleteFolder(folderId: String) {
        val folder = folders.value.find { it.id == folderId }
        if (folder?.isInbox == true) {
            showToast("Default Inbox folder cannot be removed")
            return
        }
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
        coordinator.refreshSystemPermissions()
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
        val modeLabel = when (mode) {
            RecorderMode.AUDIO -> "Audio"
            RecorderMode.PHOTO -> "Photos"
            RecorderMode.VIDEO -> "Video"
        }
        showToast("Enable at least 1 folder for $modeLabel in Settings")
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
        return coordinator.prepareOutputFile(folder, type)
    }

    fun captureSinglePhoto(fromBurstLoop: Boolean = false) {
        val folder = activeFolder() ?: run {
            stopPhotoBurst()
            promptMissingFolderForMode(RecorderMode.PHOTO)
            return
        }
        coordinator.captureSinglePhoto(folder = folder, fromBurstLoop = fromBurstLoop)
    }

    fun startPhotoBurst() {
        val folder = activeFolder() ?: run {
            promptMissingFolderForMode(RecorderMode.PHOTO)
            return
        }
        coordinator.startPhotoBurst(folder = folder)
    }

    fun stopPhotoBurst() {
        coordinator.stopPhotoBurst()
    }

    fun onPhotoCaptured(file: File) {
        val folder = activeFolder() ?: return
        coordinator.onPhotoCapturedToFolder(folder, file)
    }

    fun startVideoRecording(enableAudio: Boolean) {
        val folder = activeFolder() ?: run {
            promptMissingFolderForMode(RecorderMode.VIDEO)
            return
        }
        _recorderMode.value = RecorderMode.VIDEO
        coordinator.startInAppVideoRecording(folder = folder, enableAudio = enableAudio)
    }

    fun stopVideoRecording() {
        coordinator.stopVideoRecording()
    }

    fun startAudioRecording(): Boolean {
        if (captureState.value != CaptureState.IDLE) return false
        _recorderMode.value = RecorderMode.AUDIO
        val folder = activeFolder() ?: run {
            promptMissingFolderForMode(RecorderMode.AUDIO)
            return false
        }
        coordinator.startInAppAudioRecording(folder = folder)
        return true
    }

    fun stopAudioRecording() {
        coordinator.stopAudioRecording()
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
        coordinator.showToast(message)
    }

    fun clearToast() {
        coordinator.clearToast()
    }
}
