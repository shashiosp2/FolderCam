package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.example.model.CaptureOrientation
import com.example.model.MediaFolder
import com.example.model.RecorderMode
import com.example.model.VideoLensPreference
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

    private val _videoLensPreference = MutableStateFlow(VideoLensPreference.WIDE)
    val videoLensPreference: StateFlow<VideoLensPreference> = _videoLensPreference.asStateFlow()

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
                    ensureAllFolderDirectoriesExist(_folders.value)
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
                    ensureAllFolderDirectoriesExist(_folders.value)
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
                ensureAllFolderDirectoriesExist(_folders.value)
                return
            }
        }

        // Legacy fallback if folders_v2_json was saved previously
        val legacyFoldersJson = prefs.getString("folders_v2_json", null)
        if (!legacyFoldersJson.isNullOrBlank()) {
            try {
                val loadedList = ensureInboxFolderPresent(parseFoldersArray(JSONArray(legacyFoldersJson)))
                _folders.value = loadedList
                val savedSelectedId = prefs.getString("selected_folder_v2_id", null)
                _selectedFolderId.value = when {
                    savedSelectedId != null && loadedList.any { it.id == savedSelectedId } -> savedSelectedId
                    else -> loadedList.first().id
                }
                _isDarkTheme.value = prefs.getBoolean("is_dark_theme", true)
                _videoLensPreference.value = VideoLensPreference.fromStorageKey(
                    prefs.getString("video_lens_preference", VideoLensPreference.WIDE.storageKey)
                )
                ensureAllFolderDirectoriesExist(loadedList)
                saveAllSettings()
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fresh default: Mandatory Inbox folder (/sdcard/inbox) enabled for Audio, Photos, & Video
        val defaultFolders = ensureInboxFolderPresent(emptyList())
        _folders.value = defaultFolders
        _selectedFolderId.value = defaultFolders.first().id
        _isDarkTheme.value = true
        _captureOrientation.value = CaptureOrientation.fromStorageKey(
            prefs.getString("capture_orientation", CaptureOrientation.AUTO.storageKey)
        )
        _videoLensPreference.value = VideoLensPreference.fromStorageKey(
            prefs.getString("video_lens_preference", VideoLensPreference.WIDE.storageKey)
        )
        ensureAllFolderDirectoriesExist(defaultFolders)
        saveAllSettings()
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

    fun setVideoLensPreference(preference: VideoLensPreference) {
        if (_videoLensPreference.value == preference) return
        _videoLensPreference.value = preference
        saveAllSettings()
    }

    fun selectFolder(folderId: String) {
        if (_folders.value.any { it.id == folderId }) {
            _selectedFolderId.value = folderId
            saveAllSettings()
        }
    }

    fun getInboxFolder(): MediaFolder {
        val current = _folders.value
        val existing = current.find { it.isInbox }
        if (existing != null) return existing

        val ensured = ensureInboxFolderPresent(current)
        _folders.value = ensured
        val inbox = ensured.first { it.isInbox }
        ensureFolderDirectoryExists(inbox)
        saveAllSettings()
        return inbox
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
        val cleanName = name.trim().ifEmpty { "Folder" }
        val normalizedPath = normalizeSdcardPath(sdcardPath, cleanName)
        val id = "folder_${System.currentTimeMillis()}_${folderIdSeq.incrementAndGet()}"
        val newFolder = MediaFolder(
            id = id,
            name = cleanName,
            sdcardPath = normalizedPath,
            treeUri = treeUri,
            enableAudio = enableAudio,
            enablePhoto = enablePhoto,
            enableVideo = enableVideo,
            isDefaultInbox = false,
            colorHex = colorHex,
            iconName = "Folder"
        )
        val updatedList = ensureInboxFolderPresent(_folders.value + newFolder)
        _folders.value = updatedList
        _selectedFolderId.value = newFolder.id
        ensureFolderDirectoryExists(newFolder)
        saveAllSettings()
        return newFolder
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
        val updatedList = _folders.value.map { existing ->
            if (existing.id == folderId) {
                val cleanName = if (existing.isInbox) {
                    name.trim().ifEmpty { MediaFolder.INBOX_DEFAULT_NAME }
                } else {
                    name.trim().ifEmpty { "Folder" }
                }
                val normalizedPath = normalizeSdcardPath(sdcardPath, cleanName)
                val pathChanged = existing.sdcardPath != normalizedPath
                val resolvedTreeUri = when {
                    treeUri != null -> treeUri
                    pathChanged -> null
                    else -> existing.treeUri
                }
                val updated = existing.copy(
                    name = cleanName,
                    sdcardPath = normalizedPath,
                    colorHex = colorHex,
                    treeUri = resolvedTreeUri,
                    // Mandatory Inbox stays permanently locked ON for all three modes
                    enableAudio = if (existing.isInbox) true else (enableAudio ?: existing.enableAudio),
                    enablePhoto = if (existing.isInbox) true else (enablePhoto ?: existing.enablePhoto),
                    enableVideo = if (existing.isInbox) true else (enableVideo ?: existing.enableVideo),
                    isDefaultInbox = existing.isInbox
                )
                ensureFolderDirectoryExists(updated)
                updated
            } else {
                existing
            }
        }
        val normalizedList = ensureInboxFolderPresent(updatedList)
        _folders.value = normalizedList
        saveAllSettings()
    }

    fun setFolderModeEnabled(folderId: String, mode: RecorderMode, enabled: Boolean) {
        val updatedList = _folders.value.map { existing ->
            if (existing.id == folderId) {
                if (existing.isInbox) {
                    // Inbox is mandatory for Audio, Photo, and Video and cannot disable any mode
                    existing.copy(enableAudio = true, enablePhoto = true, enableVideo = true, isDefaultInbox = true)
                } else {
                    when (mode) {
                        RecorderMode.AUDIO -> existing.copy(enableAudio = enabled)
                        RecorderMode.PHOTO -> existing.copy(enablePhoto = enabled)
                        RecorderMode.VIDEO -> existing.copy(enableVideo = enabled)
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
        val target = currentList.find { it.id == folderId } ?: return false
        if (target.isInbox) {
            return false
        }
        val updatedList = ensureInboxFolderPresent(currentList.filter { it.id != folderId })
        _folders.value = updatedList

        if (_selectedFolderId.value == folderId || updatedList.none { it.id == _selectedFolderId.value }) {
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
        } else {
            ensureAllFolderDirectoriesExist(_folders.value)
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
                val loadedList = ensureInboxFolderPresent(parseFoldersArray(JSONArray(trimmed)))
                _folders.value = loadedList
                _selectedFolderId.value = loadedList.firstOrNull()?.id
                ensureAllFolderDirectoriesExist(loadedList)
            } else {
                val root = JSONObject(trimmed)
                val isDark = root.optBoolean("isDarkTheme", true)
                val orientationKey = root.optString("captureOrientation", CaptureOrientation.AUTO.storageKey)
                val lensKey = root.optString("videoLensPreference", VideoLensPreference.WIDE.storageKey)
                val foldersArray = root.optJSONArray("folders") ?: JSONArray()
                val loadedList = ensureInboxFolderPresent(parseFoldersArray(foldersArray))
                val savedSelectedId = root.optString("selectedFolderId", "").ifEmpty { null }

                _isDarkTheme.value = isDark
                _captureOrientation.value = CaptureOrientation.fromStorageKey(orientationKey)
                _videoLensPreference.value = VideoLensPreference.fromStorageKey(lensKey)
                _folders.value = loadedList
                _selectedFolderId.value = when {
                    savedSelectedId != null && loadedList.any { it.id == savedSelectedId } -> savedSelectedId
                    else -> loadedList.first().id
                }
                ensureAllFolderDirectoriesExist(loadedList)
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
            val id = obj.optString("id", "folder_${System.currentTimeMillis()}_$i")
            val isInboxFlag = obj.optBoolean("isDefaultInbox", false) || id == MediaFolder.INBOX_FOLDER_ID
            val defaultFallbackName = if (isInboxFlag) MediaFolder.INBOX_DEFAULT_NAME else "Folder"
            val name = obj.optString("name", defaultFallbackName).ifBlank { defaultFallbackName }
            val defaultRawPath = if (isInboxFlag) MediaFolder.INBOX_DEFAULT_PATH else "/sdcard/$name"
            val rawSdcardPath = obj.optString("sdcardPath", defaultRawPath).ifBlank { defaultRawPath }
            val basePath = normalizeSdcardPath(rawSdcardPath, name)
            val baseTreeUri = obj.optString("treeUri", "").ifEmpty { null }
            loadedList.add(
                MediaFolder(
                    id = if (isInboxFlag) MediaFolder.INBOX_FOLDER_ID else id,
                    name = name,
                    sdcardPath = basePath,
                    treeUri = baseTreeUri,
                    enableAudio = if (isInboxFlag) true else obj.optBoolean("enableAudio", true),
                    enablePhoto = if (isInboxFlag) true else obj.optBoolean("enablePhoto", true),
                    enableVideo = if (isInboxFlag) true else obj.optBoolean("enableVideo", true),
                    isDefaultInbox = isInboxFlag,
                    colorHex = obj.optString("colorHex", "#38BDF8"),
                    iconName = obj.optString("iconName", if (isInboxFlag) "Inbox" else "Folder"),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
            )
        }
        return loadedList
    }

    private fun ensureInboxFolderPresent(list: List<MediaFolder>): List<MediaFolder> {
        val existingInbox = list.firstOrNull { it.isInbox }
        val normalizedInbox = if (existingInbox != null) {
            existingInbox.copy(
                id = MediaFolder.INBOX_FOLDER_ID,
                name = existingInbox.name.ifBlank { MediaFolder.INBOX_DEFAULT_NAME },
                sdcardPath = existingInbox.sdcardPath.ifBlank { MediaFolder.INBOX_DEFAULT_PATH },
                enableAudio = true,
                enablePhoto = true,
                enableVideo = true,
                isDefaultInbox = true
            )
        } else {
            MediaFolder.createDefaultInbox()
        }

        val others = list.filterNot { it.isInbox }
        // Preserve existing position if Inbox was already in the list, otherwise place Inbox first
        return if (existingInbox != null) {
            list.map { if (it.isInbox) normalizedInbox else it }
                .distinctBy { it.id }
        } else {
            listOf(normalizedInbox) + others
        }
    }

    fun ensureAllFolderDirectoriesExist(folders: List<MediaFolder> = _folders.value) {
        folders.forEach { folder ->
            ensureFolderDirectoryExists(folder)
        }
    }

    fun ensureFolderDirectoryExists(folder: MediaFolder) {
        try {
            val relative = folder.sdcardPath.removePrefix("/sdcard").trimStart('/')
            val root = try {
                Environment.getExternalStorageDirectory()
            } catch (e: Exception) {
                File("/sdcard")
            }
            val realDir = if (relative.isEmpty()) root else File(root, relative)
            if (!realDir.exists()) {
                realDir.mkdirs()
            }
        } catch (e: Exception) {
            // Ignore if OS blocks direct /sdcard creation before All Files Access
        }

        try {
            val base = context.getExternalFilesDir(null)?.let { File(it, "FolderCam") }
                ?: File(context.filesDir, "FolderCam")
            val relative = folder.sdcardPath.removePrefix("/sdcard").trimStart('/')
            val safeRelative = relative.ifEmpty {
                folder.name.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            }
            val mirrorDir = File(base, safeRelative)
            if (!mirrorDir.exists()) {
                mirrorDir.mkdirs()
            }
        } catch (e: Exception) {
            // Ignore
        }

        folder.treeUri?.let { uriStr ->
            try {
                val treeUri = Uri.parse(uriStr)
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val parts = docId.split(":")
                val treeRel = if (parts.size > 1) parts[1].trim('/') else docId.trim('/')
                val treeBasePath = if (treeRel.isEmpty()) "/sdcard" else "/sdcard/$treeRel"
                val cleanTarget = normalizeSdcardPath(folder.sdcardPath, folder.name).trimEnd('/')
                if (cleanTarget.startsWith("$treeBasePath/")) {
                    var currentDocId = docId
                    var currentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
                    val subSegments = cleanTarget.removePrefix("$treeBasePath/").split('/').filter { it.isNotEmpty() }
                    for (segment in subSegments) {
                        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
                        var childId: String? = null
                        context.contentResolver.query(
                            childrenUri,
                            arrayOf(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                DocumentsContract.Document.COLUMN_MIME_TYPE
                            ),
                            null,
                            null,
                            null
                        )?.use { cursor ->
                            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            while (cursor.moveToNext()) {
                                val n = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                                val m = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                                if (n == segment && m == DocumentsContract.Document.MIME_TYPE_DIR && idIdx >= 0) {
                                    childId = cursor.getString(idIdx)
                                    break
                                }
                            }
                        }
                        if (childId != null) {
                            currentDocId = childId!!
                            currentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
                        } else {
                            val created = DocumentsContract.createDocument(
                                context.contentResolver,
                                currentDocUri,
                                DocumentsContract.Document.MIME_TYPE_DIR,
                                segment
                            ) ?: break
                            currentDocId = DocumentsContract.getDocumentId(created)
                            currentDocUri = created
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore if SAF permission revoked or unavailable in unit tests
            }
        }
    }

    fun toJsonString(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("configPath", "/sdcard/.foldercam")
        root.put("isDarkTheme", _isDarkTheme.value)
        root.put("captureOrientation", _captureOrientation.value.storageKey)
        root.put("videoLensPreference", _videoLensPreference.value.storageKey)
        root.put("photoResolution", "12MP")
        root.put("videoResolution", "4K 30fps")
        root.put("selectedFolderId", _selectedFolderId.value ?: "")

        val array = JSONArray()
        for (item in _folders.value) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("sdcardPath", item.sdcardPath)
                put("treeUri", item.treeUri ?: "")
                put("enableAudio", if (item.isInbox) true else item.enableAudio)
                put("enablePhoto", if (item.isInbox) true else item.enablePhoto)
                put("enableVideo", if (item.isInbox) true else item.enableVideo)
                put("isDefaultInbox", item.isInbox)
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
            .putString("video_lens_preference", _videoLensPreference.value.storageKey)
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
