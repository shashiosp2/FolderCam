package com.example.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.model.MediaFolder
import com.example.model.MediaItem
import com.example.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStorageManager(private val context: Context) {

    private val baseDirectory: File by lazy {
        val externalDir = context.getExternalFilesDir(null)
        val dir = if (externalDir != null) {
            File(externalDir, "FolderCam")
        } else {
            File(context.filesDir, "FolderCam")
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun getFolderDirectory(folder: MediaFolder): File {
        val safeName = folder.name.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val dir = File(baseDirectory, safeName)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun prepareOutputFile(folder: MediaFolder, type: MediaType): File {
        val folderDir = getFolderDirectory(folder)
        val timestamp = dateFormat.format(Date())
        val (prefix, ext) = when (type) {
            MediaType.PHOTO -> "IMG_" to ".jpg"
            MediaType.VIDEO -> "VID_" to ".mp4"
            MediaType.AUDIO -> "AUD_" to ".m4a"
        }
        return File(folderDir, "$prefix$timestamp$ext")
    }

    suspend fun getMediaItemsForFolder(folder: MediaFolder): List<MediaItem> = withContext(Dispatchers.IO) {
        val folderDir = getFolderDirectory(folder)
        val files = folderDir.listFiles() ?: return@withContext emptyList()

        files.filter { it.isFile && (it.extension.lowercase() in listOf("jpg", "jpeg", "png", "mp4", "m4a", "aac", "wav")) }
            .sortedByDescending { it.lastModified() }
            .map { file ->
                parseMediaItem(file, folder.id, folder.name)
            }
    }

    suspend fun getAllMediaItems(folders: List<MediaFolder>): List<MediaItem> = withContext(Dispatchers.IO) {
        val folderMap = folders.associateBy { it.name.replace("[^a-zA-Z0-9_-]".toRegex(), "_") }
        val allFolders = baseDirectory.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val allItems = mutableListOf<MediaItem>()

        for (folderDir in allFolders) {
            val matchingFolder = folderMap[folderDir.name]
            val folderId = matchingFolder?.id ?: folderDir.name
            val folderName = matchingFolder?.name ?: folderDir.name

            val files = folderDir.listFiles()?.filter {
                it.isFile && (it.extension.lowercase() in listOf("jpg", "jpeg", "png", "mp4", "m4a", "aac", "wav"))
            } ?: emptyList()

            for (file in files) {
                allItems.add(parseMediaItem(file, folderId, folderName))
            }
        }

        allItems.sortedByDescending { it.timestamp }
    }

    suspend fun getFolderMediaCount(folder: MediaFolder): Int = withContext(Dispatchers.IO) {
        val folderDir = getFolderDirectory(folder)
        folderDir.listFiles()?.count {
            it.isFile && (it.extension.lowercase() in listOf("jpg", "jpeg", "png", "mp4", "m4a", "aac", "wav"))
        } ?: 0
    }

    fun deleteMediaItem(item: MediaItem): Boolean {
        return try {
            if (item.file.exists()) {
                item.file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun parseMediaItem(file: File, folderId: String, folderName: String): MediaItem {
        val ext = file.extension.lowercase()
        val type = when (ext) {
            "jpg", "jpeg", "png" -> MediaType.PHOTO
            "mp4" -> MediaType.VIDEO
            else -> MediaType.AUDIO
        }

        var durationSec = 0L
        if (type == MediaType.VIDEO || type == MediaType.AUDIO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val timeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                timeStr?.toLongOrNull()?.let { ms ->
                    durationSec = ms / 1000
                }
                retriever.release()
            } catch (e: Exception) {
                // Ignore metadata extraction failure
            }
        }

        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Uri.fromFile(file)
        }

        return MediaItem(
            id = file.name,
            name = file.name,
            file = file,
            uri = uri,
            type = type,
            folderId = folderId,
            folderName = folderName,
            timestamp = file.lastModified(),
            durationSeconds = durationSec,
            sizeBytes = file.length()
        )
    }
}
