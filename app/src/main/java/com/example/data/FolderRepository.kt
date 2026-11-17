package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.example.model.CaptureOrientation
import com.example.model.MediaFolder
import com.example.model.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class FolderRepository(private val context: Context) {

    private val folderIdSeq = AtomicInteger(0)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)

    private val _folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    val folders: StateFlow<List<MediaFolder>> = _folders.asStateFlow()

    private val _selectedFolderId = MutableStateFlow<String?>(null)
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    private val _captureOrientation = MutableStateFlow(CaptureOrientation.AUTO)
    val captureOrientation: StateFlow<CaptureOrientation> = _captureOrientation.asStateFlow()

    init {
        loadSettings()
    }

    /**
     * Primary config file path requested by user: /sdcard/.foldercam
     */
    fun getSdcardConfigFile(): File {
        return try {
            val extRoot = Environment.getExternalStorageDirectory()
            if (extRoot != null) File(extRoot, ".foldercam") else File("/sdcard/.foldercam")
        } catch (e: Exception) {
            File("/sdcard/.foldercam")
        }
    }

    /**
     * App-scoped mirror of .foldercam so it is always writable even under strict Scoped Storage.
     */
    fun getFallbackConfigFile(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, ".foldercam")
    }

    private fun loadSettings() {
        // 1. Try reading from /sdcard/.foldercam first so any pre-existing or restored file is loaded
        val sdcardFile = getSdcardConfigFile()
        if (sdcardFile.exists() && sdcardFile.canRead()) {
            try {
                val content = sdcardFile.readText()
                if (content.isNotBlank() && applySettingsFromJson(content, syncToDisk = false)) {
                    // Sync into prefs & fallback mirror
                    saveSettingsToInternalAndSdcard(writeSdcard = false)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Try reading from app-scoped .foldercam mirror
        val fallbackFile = getFallbackConfigFile()
        if (fallbackFile.exists() && fallbackFile.canRead()) {
            try {
                val content = fallbackFile.readText()
                if (content.isNotBlank() && applySettingsFromJson(content, syncToDisk = false)) {
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 3. Try reading from SharedPreferences
        val savedJson = prefs.getString("foldercam_settings_json", null)
        if (!savedJson.isNullOrBlank()) {
            if (applySettingsFromJson(savedJson, syncToDisk = false)) {
                return
            }
        }

        // Legacy fallback if folders_v2_json was saved previously
        val legacyFoldersJson = prefs.getString("folders_v2_json", null)
        if (!legacyFoldersJson.isNullOrBlank()) {
            try {
                val loadedList = parseFoldersArray(JSONArray(legacyFoldersJson))
                _folders.value = loadedList
                val savedSelectedId = prefs.getString("selected_folder_v2_id", null)
                _selectedFolderId.value = when {
                    loadedList.isEmpty() -> null
                    savedSelectedId != null && loadedList.any { it.id == savedSelectedId } -> savedSelectedId
                    else -> loadedList.first().id
                }
                _isDarkTheme.value = prefs.getBoolean("is_dark_theme", true)
                saveAllSettings()
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // By default, no folders exist until specified by the user
        _folders.value = emptyList()
        _selectedFolderId.value = null
        _isDarkTheme.value = true
        _captureOrientation.value = CaptureOrientation.fromStorageKey(
            prefs.getString("capture_orientation", CaptureOrientation.AUTO.storageKey)
        )
    }

    fun setDarkTheme(isDark: Boolean) {
        _isDarkTheme.value = isDark
        saveAllSettings()
    }

    fun toggleDarkTheme() {
        _isDarkTheme.value = !_isDarkTheme.value
        saveAllSettings()
    }

    fun setCaptureOrientation(orientation: CaptureOrientation) {
        if (_captureOrientation.value == orientation) return
        _captureOrientation.value = orientation
        saveAllSettings()
    }

    fun selectFolder(folderId: String) {
        if (_folders.value.any { it.id == folderId }) {
            _selectedFolderId.value = folderId
            saveAllSettings()
        }
    }

    fun addFolder(
        name: String,
        sdcardPath: String,
        colorHex: String = "#38BDF8",
        treeUri: String? = null,
        audioSdcardPath: String = sdcardPath,
        photoSdcardPath: String = sdcardPath,
        videoSdcardPath: String = sdcardPath,
        audioTreeUri: String? = treeUri,
        photoTreeUri: String? = treeUri,
        videoTreeUri: String? = treeUri
    ): MediaFolder {
        val cleanName = name.trim().ifEmpty { "Folder" }
        val normalizedPath = normalizeSdcardPath(sdcardPath, cleanName)
        val normalizedAudio = normalizeSdcardPath(audioSdcardPath, cleanName)
        val normalizedPhoto = normalizeSdcardPath(photoSdcardPath, cleanName)
        val normalizedVideo = normalizeSdcardPath(videoSdcardPath, cleanName)
        val id = "folder_${System.currentTimeMillis()}_${folderIdSeq.incrementAndGet()}"
        val newFolder = MediaFolder(
            id = id,
            name = cleanName,
            sdcardPath = normalizedPath,
            audioSdcardPath = normalizedAudio,
            photoSdcardPath = normalizedPhoto,
            videoSdcardPath = normalizedVideo,
            treeUri = treeUri,
            audioTreeUri = audioTreeUri,
            photoTreeUri = photoTreeUri,
            videoTreeUri = videoTreeUri,
            colorHex = colorHex,
            iconName = "Folder"
        )
        val updatedList = _folders.value + newFolder
        _folders.value = updatedList
        _selectedFolderId.value = newFolder.id
        saveAllSettings()
        return newFolder
    }

    fun updateFolder(
        folderId: String,
        name: String,
        sdcardPath: String,
        colorHex: String,
        treeUri: String? = null,
        audioSdcardPath: String = sdcardPath,
        photoSdcardPath: String = sdcardPath,
        videoSdcardPath: String = sdcardPath,
        audioTreeUri: String? = treeUri,
        photoTreeUri: String? = treeUri,
        videoTreeUri: String? = treeUri
    ) {
        val cleanName = name.trim().ifEmpty { "Folder" }
        val normalizedPath = normalizeSdcardPath(sdcardPath, cleanName)
        val normalizedAudio = normalizeSdcardPath(audioSdcardPath, cleanName)
        val normalizedPhoto = normalizeSdcardPath(photoSdcardPath, cleanName)
        val normalizedVideo = normalizeSdcardPath(videoSdcardPath, cleanName)
        val updatedList = _folders.value.map { existing ->
            if (existing.id == folderId) {
                val baseChanged = existing.sdcardPath != normalizedPath
                val audioChanged = existing.audioSdcardPath != normalizedAudio
                val photoChanged = existing.photoSdcardPath != normalizedPhoto
                val videoChanged = existing.videoSdcardPath != normalizedVideo
                existing.copy(
                    name = cleanName,
                    sdcardPath = normalizedPath,
                    audioSdcardPath = normalizedAudio,
                    photoSdcardPath = normalizedPhoto,
                    videoSdcardPath = normalizedVideo,
                    colorHex = colorHex,
                    treeUri = when {
                        treeUri != null -> treeUri
                        baseChanged -> null
                        else -> existing.treeUri
                    },
                    audioTreeUri = when {
                        audioTreeUri != null -> audioTreeUri
                        audioChanged -> null
                        else -> existing.audioTreeUri
                    },
                    photoTreeUri = when {
                        photoTreeUri != null -> photoTreeUri
                        photoChanged -> null
                        else -> existing.photoTreeUri
                    },
                    videoTreeUri = when {
                        videoTreeUri != null -> videoTreeUri
                        videoChanged -> null
                        else -> existing.videoTreeUri
                    }
                )
            } else {
                existing
            }
        }
        _folders.value = updatedList
        saveAllSettings()
    }

    fun updateFolderMediaPath(
        folderId: String,
        mediaType: MediaType,
        sdcardPath: String,
        treeUri: String? = null
    ) {
        val updatedList = _folders.value.map { existing ->
            if (existing.id == folderId) {
                val normalized = normalizeSdcardPath(sdcardPath, existing.name)
                when (mediaType) {
                    MediaType.AUDIO -> {
                        val changed = existing.audioSdcardPath != normalized
                        existing.copy(
                            audioSdcardPath = normalized,
                            audioTreeUri = when {
                                treeUri != null -> treeUri
                                changed -> null
                                else -> existing.audioTreeUri
                            }
                        )
                    }
                    MediaType.PHOTO -> {
                        val changed = existing.photoSdcardPath != normalized
                        existing.copy(
                            sdcardPath = normalized,
                            photoSdcardPath = normalized,
                            treeUri = when {
                                treeUri != null -> treeUri
                                changed -> null
                                else -> existing.treeUri
                            },
                            photoTreeUri = when {
                                treeUri != null -> treeUri
                                changed -> null
                                else -> existing.photoTreeUri
                            }
                        )
                    }
                    MediaType.VIDEO -> {
                        val changed = existing.videoSdcardPath != normalized
                        existing.copy(
                            videoSdcardPath = normalized,
                            videoTreeUri = when {
                                treeUri != null -> treeUri
                                changed -> null
                                else -> existing.videoTreeUri
                            }
                        )
                    }
                }
            } else {
                existing
            }
        }
        _folders.value = updatedList
        saveAllSettings()
    }

    fun deleteFolder(folderId: String): Boolean {
        val currentList = _folders.value
        val updatedList = currentList.filter { it.id != folderId }
        _folders.value = updatedList

        if (_selectedFolderId.value == folderId) {
            _selectedFolderId.value = updatedList.firstOrNull()?.id
        }
        saveAllSettings()
        return true
    }

    fun moveFolder(fromIndex: Int, toIndex: Int): Boolean {
        val currentList = _folders.value
        if (fromIndex !in currentList.indices || toIndex !in currentList.indices || fromIndex == toIndex) {
            return false
        }
        val mutable = currentList.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        _folders.value = mutable
        saveAllSettings()
        return true
    }

    fun getSelectedFolder(): MediaFolder? {
        val currentId = _selectedFolderId.value
        return _folders.value.find { it.id == currentId } ?: _folders.value.firstOrNull()
    }

    /**
     * Restores settings directly from /sdcard/.foldercam (or fallback mirror if /sdcard is restricted).
     */
    fun restoreFromSdcardConfigFile(): Boolean {
        try {
            val sdcardFile = getSdcardConfigFile()
            if (sdcardFile.exists() && sdcardFile.canRead()) {
                val jsonText = sdcardFile.readText()
                if (jsonText.isNotBlank() && applySettingsFromJson(jsonText, syncToDisk = true)) {
                    return true
                }
            }
            val rawSdcardFile = File("/sdcard/.foldercam")
            if (rawSdcardFile.exists() && rawSdcardFile.canRead()) {
                val jsonText = rawSdcardFile.readText()
                if (jsonText.isNotBlank() && applySettingsFromJson(jsonText, syncToDisk = true)) {
                    return true
                }
            }
            val fallbackFile = getFallbackConfigFile()
            if (fallbackFile.exists() && fallbackFile.canRead()) {
                val jsonText = fallbackFile.readText()
                if (jsonText.isNotBlank() && applySettingsFromJson(jsonText, syncToDisk = true)) {
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    fun syncWithSdcardOnResume() {
        val sdcardFile = getSdcardConfigFile()
        if (_folders.value.isEmpty() && sdcardFile.exists() && sdcardFile.canRead()) {
            restoreFromSdcardConfigFile()
        } else if (_folders.value.isNotEmpty()) {
            saveSettingsToInternalAndSdcard(writeSdcard = true)
        }
    }

    /**
     * Restores settings from a JSON string (e.g. read from /sdcard/.foldercam or document picker)
     * and writes them to /sdcard/.foldercam.
     */
    fun restoreFromJsonString(jsonString: String): Boolean {
        return applySettingsFromJson(jsonString, syncToDisk = true)
    }

    private fun applySettingsFromJson(jsonString: String, syncToDisk: Boolean): Boolean {
        return try {
            val trimmed = jsonString.trim()
            if (trimmed.startsWith("[")) {
                // Also support plain JSON array of folders
                val loadedList = parseFoldersArray(JSONArray(trimmed))
                _folders.value = loadedList
                _selectedFolderId.value = loadedList.firstOrNull()?.id
            } else {
                val root = JSONObject(trimmed)
                val isDark = root.optBoolean("isDarkTheme", true)
                val orientationKey = root.optString("captureOrientation", CaptureOrientation.AUTO.storageKey)
                val foldersArray = root.optJSONArray("folders") ?: JSONArray()
                val loadedList = parseFoldersArray(foldersArray)
                val savedSelectedId = root.optString("selectedFolderId", "").ifEmpty { null }

                _isDarkTheme.value = isDark
                _captureOrientation.value = CaptureOrientation.fromStorageKey(orientationKey)
                _folders.value = loadedList
                _selectedFolderId.value = when {
                    loadedList.isEmpty() -> null
                    savedSelectedId != null && loadedList.any { it.id == savedSelectedId } -> savedSelectedId
                    else -> loadedList.first().id
                }
            }
            if (syncToDisk) {
                saveAllSettings()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun parseFoldersArray(array: JSONArray): List<MediaFolder> {
        val loadedList = mutableListOf<MediaFolder>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.optString("name", "Folder")
            val rawSdcardPath = obj.optString("sdcardPath", "/sdcard/$name")
            val basePath = normalizeSdcardPath(rawSdcardPath, name)
            val baseTreeUri = obj.optString("treeUri", "").ifEmpty { null }
            val audioPath = obj.optString("audioSdcardPath", "").ifBlank { basePath }
            val photoPath = obj.optString("photoSdcardPath", "").ifBlank { basePath }
            val videoPath = obj.optString("videoSdcardPath", "").ifBlank { basePath }
            loadedList.add(
                MediaFolder(
                    id = obj.optString("id", "folder_${System.currentTimeMillis()}_$i"),
                    name = name,
                    sdcardPath = basePath,
                    audioSdcardPath = normalizeSdcardPath(audioPath, name),
                    photoSdcardPath = normalizeSdcardPath(photoPath, name),
                    videoSdcardPath = normalizeSdcardPath(videoPath, name),
                    treeUri = baseTreeUri,
                    audioTreeUri = obj.optString("audioTreeUri", "").ifEmpty { baseTreeUri },
                    photoTreeUri = obj.optString("photoTreeUri", "").ifEmpty { baseTreeUri },
                    videoTreeUri = obj.optString("videoTreeUri", "").ifEmpty { baseTreeUri },
                    colorHex = obj.optString("colorHex", "#38BDF8"),
                    iconName = obj.optString("iconName", "Folder"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return loadedList
    }

    fun toJsonString(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("configPath", "/sdcard/.foldercam")
        root.put("isDarkTheme", _isDarkTheme.value)
        root.put("captureOrientation", _captureOrientation.value.storageKey)
        root.put("photoResolution", "12MP")
        root.put("videoResolution", "4K 30fps")
        root.put("selectedFolderId", _selectedFolderId.value ?: "")

        val array = JSONArray()
        for (item in _folders.value) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("sdcardPath", item.sdcardPath)
                put("audioSdcardPath", item.audioSdcardPath)
                put("photoSdcardPath", item.photoSdcardPath)
                put("videoSdcardPath", item.videoSdcardPath)
                put("treeUri", item.treeUri ?: "")
                put("audioTreeUri", item.audioTreeUri ?: "")
                put("photoTreeUri", item.photoTreeUri ?: "")
                put("videoTreeUri", item.videoTreeUri ?: "")
                put("colorHex", item.colorHex)
                put("iconName", item.iconName)
                put("createdAt", item.createdAt)
            }
            array.put(obj)
        }
        root.put("folders", array)
        return root.toString(2).replace("\\/", "/")
    }

    private fun saveAllSettings() {
        saveSettingsToInternalAndSdcard(writeSdcard = true)
    }

    private fun saveSettingsToInternalAndSdcard(writeSdcard: Boolean) {
        val jsonText = toJsonString()

        // 1. Save to SharedPreferences
        prefs.edit()
            .putString("foldercam_settings_json", jsonText)
            .putBoolean("is_dark_theme", _isDarkTheme.value)
            .putString("capture_orientation", _captureOrientation.value.storageKey)
            .putString("selected_folder_v2_id", _selectedFolderId.value)
            .apply()

        // 2. Save to app-scoped .foldercam mirror
        try {
            val fallbackFile = getFallbackConfigFile()
            fallbackFile.parentFile?.mkdirs()
            fallbackFile.writeText(jsonText)
        } catch (e: Exception) {
            // Ignore
        }

        // 3. Save directly to /sdcard/.foldercam
        if (writeSdcard) {
            try {
                val sdcardFile = getSdcardConfigFile()
                sdcardFile.parentFile?.mkdirs()
                sdcardFile.writeText(jsonText)
            } catch (e: Exception) {
                try {
                    File("/sdcard/.foldercam").writeText(jsonText)
                } catch (ignored: Exception) {
                    // Ignore if OS blocks direct root /sdcard write without file picker
                }
            }
        }
    }

    companion object {
        fun normalizeSdcardPath(rawPath: String, fallbackName: String): String {
            val trimmed = rawPath.trim()
            if (trimmed.isEmpty()) {
                val safe = fallbackName.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                return "/sdcard/$safe"
            }
            val cleaned = trimmed.replace("\\", "/").trimEnd('/')
            return when {
                cleaned.startsWith("/sdcard") -> if (cleaned == "/sdcard") "/sdcard" else cleaned
                cleaned.startsWith("/storage/emulated/0") ->
                    cleaned.replaceFirst("/storage/emulated/0", "/sdcard").ifEmpty { "/sdcard" }
                cleaned.startsWith("/") -> "/sdcard$cleaned"
                else -> "/sdcard/$cleaned"
            }
        }
    }
}
