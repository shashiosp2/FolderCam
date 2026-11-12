package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.MediaFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class FolderRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("folder_cam_prefs", Context.MODE_PRIVATE)

    private val _folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    val folders: StateFlow<List<MediaFolder>> = _folders.asStateFlow()

    private val _selectedFolderId = MutableStateFlow<String>("general")
    val selectedFolderId: StateFlow<String> = _selectedFolderId.asStateFlow()

    init {
        loadFolders()
    }

    private fun loadFolders() {
        val jsonString = prefs.getString("custom_folders_json", null)
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
                            colorHex = obj.optString("colorHex", "#38BDF8"),
                            iconName = obj.optString("iconName", "Folder"),
                            isDefault = obj.optBoolean("isDefault", false),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (loadedList.isEmpty()) {
            loadedList.addAll(MediaFolder.PredefinedFolders)
            saveFoldersInternal(loadedList)
        }

        _folders.value = loadedList

        val savedSelectedId = prefs.getString("selected_folder_id", "general") ?: "general"
        val resolvedSelectedId = if (loadedList.any { it.id == savedSelectedId }) {
            savedSelectedId
        } else {
            loadedList.firstOrNull()?.id ?: "general"
        }
        _selectedFolderId.value = resolvedSelectedId
    }

    fun selectFolder(folderId: String) {
        if (_folders.value.any { it.id == folderId }) {
            _selectedFolderId.value = folderId
            prefs.edit().putString("selected_folder_id", folderId).apply()
        }
    }

    fun addFolder(name: String, colorHex: String, iconName: String): MediaFolder {
        val cleanName = name.trim().ifEmpty { "New Folder" }
        val id = "folder_" + System.currentTimeMillis()
        val newFolder = MediaFolder(
            id = id,
            name = cleanName,
            colorHex = colorHex,
            iconName = iconName,
            isDefault = false
        )
        val updatedList = _folders.value + newFolder
        saveFoldersInternal(updatedList)
        _folders.value = updatedList
        selectFolder(newFolder.id)
        return newFolder
    }

    fun updateFolder(updated: MediaFolder) {
        val updatedList = _folders.value.map {
            if (it.id == updated.id) updated else it
        }
        saveFoldersInternal(updatedList)
        _folders.value = updatedList
    }

    fun deleteFolder(folderId: String): Boolean {
        val currentList = _folders.value
        if (currentList.size <= 1) return false // Keep at least one folder
        val target = currentList.find { it.id == folderId } ?: return false
        val updatedList = currentList.filter { it.id != folderId }
        saveFoldersInternal(updatedList)
        _folders.value = updatedList

        if (_selectedFolderId.value == folderId) {
            val fallback = updatedList.first().id
            selectFolder(fallback)
        }
        return true
    }

    fun getSelectedFolder(): MediaFolder {
        val currentId = _selectedFolderId.value
        return _folders.value.find { it.id == currentId }
            ?: _folders.value.firstOrNull()
            ?: MediaFolder.PredefinedFolders.first()
    }

    private fun saveFoldersInternal(list: List<MediaFolder>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("colorHex", item.colorHex)
                put("iconName", item.iconName)
                put("isDefault", item.isDefault)
                put("createdAt", item.createdAt)
            }
            array.put(obj)
        }
        prefs.edit().putString("custom_folders_json", array.toString()).apply()
    }
}
