package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.MediaFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class FolderRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("folder_cam_v2_prefs", Context.MODE_PRIVATE)

    private val _folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    val folders: StateFlow<List<MediaFolder>> = _folders.asStateFlow()

    private val _selectedFolderId = MutableStateFlow<String?>(null)
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    init {
        loadFolders()
    }

    private fun loadFolders() {
        val jsonString = prefs.getString("folders_v2_json", null)
        val loadedList = mutableListOf<MediaFolder>()

        if (!jsonString.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    loadedList.add(
                        MediaFolder(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            sdcardPath = obj.optString("sdcardPath", "/sdcard/FolderCam"),
                            treeUri = obj.optString("treeUri", "").ifEmpty { null },
                            colorHex = obj.optString("colorHex", "#38BDF8"),
                            iconName = obj.optString("iconName", "Folder"),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // By default, no folders are present until the user specifies at least 1 folder.
        _folders.value = loadedList

        val savedSelectedId = prefs.getString("selected_folder_v2_id", null)
        val resolvedSelectedId = when {
            loadedList.isEmpty() -> null
            savedSelectedId != null && loadedList.any { it.id == savedSelectedId } -> savedSelectedId
            else -> loadedList.first().id
        }
        _selectedFolderId.value = resolvedSelectedId
    }

    fun selectFolder(folderId: String) {
        if (_folders.value.any { it.id == folderId }) {
            _selectedFolderId.value = folderId
            prefs.edit().putString("selected_folder_v2_id", folderId).apply()
        }
    }

    fun addFolder(
        name: String,
        sdcardPath: String,
        colorHex: String = "#38BDF8",
        treeUri: String? = null
    ): MediaFolder {
        val cleanName = name.trim().ifEmpty { "Folder" }
        val normalizedPath = normalizeSdcardPath(sdcardPath, cleanName)
        val id = "folder_" + System.currentTimeMillis()
        val newFolder = MediaFolder(
            id = id,
            name = cleanName,
            sdcardPath = normalizedPath,
            treeUri = treeUri,
            colorHex = colorHex,
            iconName = "Folder"
        )
        val updatedList = _folders.value + newFolder
        saveFoldersInternal(updatedList)
        _folders.value = updatedList
        selectFolder(newFolder.id)
        return newFolder
    }

    fun updateFolder(
        folderId: String,
        name: String,
        sdcardPath: String,
        colorHex: String,
        treeUri: String? = null
    ) {
        val cleanName = name.trim().ifEmpty { "Folder" }
        val normalizedPath = normalizeSdcardPath(sdcardPath, cleanName)
        val updatedList = _folders.value.map { existing ->
            if (existing.id == folderId) {
                existing.copy(
                    name = cleanName,
                    sdcardPath = normalizedPath,
                    colorHex = colorHex,
                    treeUri = treeUri ?: existing.treeUri
                )
            } else {
                existing
            }
        }
        saveFoldersInternal(updatedList)
        _folders.value = updatedList
    }

    fun deleteFolder(folderId: String): Boolean {
        val currentList = _folders.value
        val updatedList = currentList.filter { it.id != folderId }
        saveFoldersInternal(updatedList)
        _folders.value = updatedList

        if (_selectedFolderId.value == folderId) {
            val fallback = updatedList.firstOrNull()?.id
            _selectedFolderId.value = fallback
            prefs.edit().putString("selected_folder_v2_id", fallback).apply()
        }
        return true
    }

    fun getSelectedFolder(): MediaFolder? {
        val currentId = _selectedFolderId.value
        return _folders.value.find { it.id == currentId } ?: _folders.value.firstOrNull()
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

    private fun saveFoldersInternal(list: List<MediaFolder>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("sdcardPath", item.sdcardPath)
                put("treeUri", item.treeUri ?: "")
                put("colorHex", item.colorHex)
                put("iconName", item.iconName)
                put("createdAt", item.createdAt)
            }
            array.put(obj)
        }
        prefs.edit().putString("folders_v2_json", array.toString()).apply()
    }
}
