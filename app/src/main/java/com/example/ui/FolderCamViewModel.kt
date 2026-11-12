package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioRecorderManager
import com.example.data.FolderRepository
import com.example.data.MediaStorageManager
import com.example.model.CaptureState
import com.example.model.MediaFolder
import com.example.model.MediaItem
import com.example.model.MediaType
import com.example.model.RecorderMode
import com.example.model.RecordingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class FolderCamViewModel(application: Application) : AndroidViewModel(application) {

    val folderRepository = FolderRepository(application)
    val mediaStorageManager = MediaStorageManager(application)
    val audioRecorderManager = AudioRecorderManager(application)

    val folders: StateFlow<List<MediaFolder>> = folderRepository.folders
    val selectedFolderId: StateFlow<String?> = folderRepository.selectedFolderId

    val selectedFolder: StateFlow<MediaFolder?> = combine(folders, selectedFolderId) { list, id ->
        if (list.isEmpty()) {
            null
        } else {
            list.find { it.id == id } ?: list.first()
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        folderRepository.getSelectedFolder()
    )

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

    init {
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

    fun setRecorderMode(mode: RecorderMode) {
        if (_recorderMode.value == mode) return
        if (_captureState.value == CaptureState.RECORDING_AUDIO && mode != RecorderMode.AUDIO) {
            stopAudioRecording()
        }
        _recorderMode.value = mode
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
        val folder = selectedFolder.value ?: run {
            showToast("Please specify at least 1 folder in Settings first")
            _isSettingsOpen.value = true
            return null
        }
        return mediaStorageManager.prepareOutputFile(folder, type)
    }

    fun onPhotoCaptured(file: File) {
        val folder = selectedFolder.value ?: return
        viewModelScope.launch {
            mediaStorageManager.finalizeCapturedFile(folder, file, MediaType.PHOTO)
            showToast("Photo saved to ${folder.sdcardPath}")
            refreshFolderMedia()
            refreshAllFolderCounts()
        }
    }

    fun onVideoRecordingStarted() {
        _recorderMode.value = RecorderMode.VIDEO
        _captureState.value = CaptureState.RECORDING_VIDEO
        _videoDurationSeconds.value = 0
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
        val folder = selectedFolder.value ?: return
        viewModelScope.launch {
            mediaStorageManager.finalizeCapturedFile(folder, file, MediaType.VIDEO)
            showToast("Video saved to ${folder.sdcardPath}")
            refreshFolderMedia()
            refreshAllFolderCounts()
        }
    }

    fun onVideoError(message: String) {
        videoTimerJob?.cancel()
        videoTimerJob = null
        _captureState.value = CaptureState.IDLE
        showToast("Video error: $message")
    }

    fun startAudioRecording(): Boolean {
        if (_captureState.value != CaptureState.IDLE) return false
        _recorderMode.value = RecorderMode.AUDIO
        val outputFile = prepareOutputFile(MediaType.AUDIO) ?: return false
        val started = audioRecorderManager.startRecording(outputFile, viewModelScope)
        if (started) {
            _captureState.value = CaptureState.RECORDING_AUDIO
        } else {
            showToast("Failed to start audio recording")
        }
        return started
    }

    fun stopAudioRecording() {
        val file = audioRecorderManager.stopRecording()
        _captureState.value = CaptureState.IDLE
        val folder = selectedFolder.value
        if (file != null && file.exists() && folder != null) {
            viewModelScope.launch {
                mediaStorageManager.finalizeCapturedFile(folder, file, MediaType.AUDIO)
                showToast("Audio saved to ${folder.sdcardPath}")
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
        videoTimerJob?.cancel()
        audioRecorderManager.cancelRecording()
    }
}
