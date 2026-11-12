package com.example.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
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

    private val baseMirrorDirectory: File by lazy {
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

    private val defaultSdcardRoots = listOf(
        "DCIM",
        "Pictures",
        "Movies",
        "Music",
        "Recordings",
        "Download",
        "Documents",
        "Podcasts"
    )

    fun toRealFile(sdcardPath: String): File {
        val normalized = FolderRepository.normalizeSdcardPath(sdcardPath, "")
        val relative = normalized.removePrefix("/sdcard").trimStart('/')
        val root = try {
            Environment.getExternalStorageDirectory()
        } catch (e: Exception) {
            File("/sdcard")
        }
        return if (relative.isEmpty()) root else File(root, relative)
    }

    fun listSdcardDirectories(currentSdcardPath: String): List<String> {
        val normalized = FolderRepository.normalizeSdcardPath(currentSdcardPath, "")
        val dirSet = linkedSetOf<String>()

        if (normalized == "/sdcard") {
            defaultSdcardRoots.forEach { dirSet.add("/sdcard/$it") }
        }

        // Check real /sdcard directory if readable
        try {
            val realDir = toRealFile(normalized)
            realDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { sub ->
                    dirSet.add("$normalized/${sub.name}")
                }
        } catch (e: Exception) {
            // Ignore security exception if restricted by OS
        }

        // Also check our mirror root for custom created directories under this path
        try {
            val relative = normalized.removePrefix("/sdcard").trimStart('/')
            val mirrorDir = if (relative.isEmpty()) baseMirrorDirectory else File(baseMirrorDirectory, relative)
            mirrorDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach { sub ->
                    dirSet.add("$normalized/${sub.name}")
                }
        } catch (e: Exception) {
            // Ignore
        }

        return dirSet.toList().sorted()
    }

    fun createSdcardDirectory(parentSdcardPath: String, newFolderName: String): String {
        val cleanName = newFolderName.trim().replace("[^a-zA-Z0-9 _-]".toRegex(), "_")
            .ifEmpty { "NewFolder" }
        val normalizedParent = FolderRepository.normalizeSdcardPath(parentSdcardPath, "")
        val targetSdcardPath = "$normalizedParent/$cleanName"

        try {
            val realTarget = toRealFile(targetSdcardPath)
            realTarget.mkdirs()
        } catch (e: Exception) {
            // Ignore if direct mkdirs requires MediaStore
        }

        try {
            val relative = targetSdcardPath.removePrefix("/sdcard").trimStart('/')
            File(baseMirrorDirectory, relative).mkdirs()
        } catch (e: Exception) {
            // Ignore
        }

        return targetSdcardPath
    }

    fun resolveSdcardPathFromTreeUri(treeUri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            // Example docId: "primary:DCIM/MyFolder" or "primary:Music"
            val parts = docId.split(":")
            val rel = if (parts.size > 1) parts[1].trim('/') else docId.trim('/')
            if (rel.isEmpty()) "/sdcard" else "/sdcard/$rel"
        } catch (e: Exception) {
            "/sdcard/FolderCam"
        }
    }

    fun getMirrorDirectory(folder: MediaFolder): File {
        val relative = folder.sdcardPath.removePrefix("/sdcard").trimStart('/')
        val safeRelative = if (relative.isEmpty()) {
            folder.name.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        } else {
            relative
        }
        val dir = File(baseMirrorDirectory, safeRelative)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun prepareOutputFile(folder: MediaFolder, type: MediaType): File {
        val timestamp = dateFormat.format(Date())
        val (prefix, ext) = when (type) {
            MediaType.PHOTO -> "IMG_" to ".jpg"
            MediaType.VIDEO -> "VID_" to ".mp4"
            MediaType.AUDIO -> "AUD_" to ".m4a"
        }
        val fileName = "$prefix$timestamp$ext"

        // Try direct /sdcard path first if writable
        try {
            val realSdcardDir = toRealFile(folder.sdcardPath)
            if (!realSdcardDir.exists()) {
                realSdcardDir.mkdirs()
            }
            if (realSdcardDir.exists() && realSdcardDir.canWrite()) {
                return File(realSdcardDir, fileName)
            }
        } catch (e: Exception) {
            // Fallback to mirror dir + MediaStore/SAF export
        }

        val mirrorDir = getMirrorDirectory(folder)
        return File(mirrorDir, fileName)
    }

    suspend fun finalizeCapturedFile(
        folder: MediaFolder,
        capturedFile: File,
        type: MediaType
    ) = withContext(Dispatchers.IO) {
        if (!capturedFile.exists()) return@withContext

        // Ensure a copy is also in the mirror directory if it was written directly to /sdcard
        try {
            val mirrorDir = getMirrorDirectory(folder)
            val mirrorFile = File(mirrorDir, capturedFile.name)
            if (capturedFile.absolutePath != mirrorFile.absolutePath && !mirrorFile.exists()) {
                capturedFile.copyTo(mirrorFile, overwrite = true)
            }
        } catch (e: Exception) {
            // Ignore
        }

        // 1. If folder has a SAF treeUri, export to the treeUri
        folder.treeUri?.let { uriStr ->
            try {
                val treeUri = Uri.parse(uriStr)
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val mimeType = when (type) {
                    MediaType.PHOTO -> "image/jpeg"
                    MediaType.VIDEO -> "video/mp4"
                    MediaType.AUDIO -> "audio/mp4"
                }
                val newDocUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    parentDocUri,
                    mimeType,
                    capturedFile.name
                )
                if (newDocUri != null) {
                    context.contentResolver.openOutputStream(newDocUri)?.use { out ->
                        capturedFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    return@withContext
                }
            } catch (e: Exception) {
                // Continue to MediaStore fallback
            }
        }

        // 2. Export to /sdcard via MediaStore on Android 10+ so it appears in public /sdcard folder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val relativeFromSdcard = folder.sdcardPath.removePrefix("/sdcard").trim('/')
                val mimeType = when (type) {
                    MediaType.PHOTO -> "image/jpeg"
                    MediaType.VIDEO -> "video/mp4"
                    MediaType.AUDIO -> "audio/mp4"
                }

                val (collectionUri, validRelativePath) = when (type) {
                    MediaType.PHOTO -> {
                        val rel = if (relativeFromSdcard.startsWith("DCIM") || relativeFromSdcard.startsWith("Pictures")) {
                            relativeFromSdcard
                        } else {
                            "DCIM/${relativeFromSdcard.ifEmpty { folder.name }}"
                        }
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to rel
                    }
                    MediaType.VIDEO -> {
                        val rel = if (relativeFromSdcard.startsWith("Movies") || relativeFromSdcard.startsWith("DCIM")) {
                            relativeFromSdcard
                        } else {
                            "Movies/${relativeFromSdcard.ifEmpty { folder.name }}"
                        }
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI to rel
                    }
                    MediaType.AUDIO -> {
                        val rel = if (
                            relativeFromSdcard.startsWith("Music") ||
                            relativeFromSdcard.startsWith("Recordings") ||
                            relativeFromSdcard.startsWith("Podcasts")
                        ) {
                            relativeFromSdcard
                        } else {
                            "Music/${relativeFromSdcard.ifEmpty { folder.name }}"
                        }
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to rel
                    }
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, capturedFile.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, validRelativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val itemUri = resolver.insert(collectionUri, values)
                if (itemUri != null) {
                    resolver.openOutputStream(itemUri)?.use { out ->
                        capturedFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                }
            } catch (e: Exception) {
                // Ignore if MediaStore insert fails in unit test environment
            }
        }
    }

    suspend fun getMediaItemsForFolder(folder: MediaFolder): List<MediaItem> = withContext(Dispatchers.IO) {
        val validExts = setOf("jpg", "jpeg", "png", "mp4", "m4a", "aac", "wav")
        val filesByName = linkedMapOf<String, File>()

        // Check real /sdcard directory first
        try {
            val realDir = toRealFile(folder.sdcardPath)
            realDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in validExts }
                ?.forEach { file ->
                    filesByName[file.name] = file
                }
        } catch (e: Exception) {
            // Ignore
        }

        // Check mirror directory
        try {
            val mirrorDir = getMirrorDirectory(folder)
            mirrorDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in validExts }
                ?.forEach { file ->
                    filesByName.putIfAbsent(file.name, file)
                }
        } catch (e: Exception) {
            // Ignore
        }

        filesByName.values
            .sortedByDescending { it.lastModified() }
            .map { file ->
                parseMediaItem(file, folder.id, "${folder.name} (${folder.sdcardPath})")
            }
    }

    suspend fun getFolderMediaCount(folder: MediaFolder): Int = withContext(Dispatchers.IO) {
        val validExts = setOf("jpg", "jpeg", "png", "mp4", "m4a", "aac", "wav")
        val names = mutableSetOf<String>()
        try {
            toRealFile(folder.sdcardPath).listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in validExts }
                ?.forEach { names.add(it.name) }
        } catch (e: Exception) {
            // Ignore
        }
        try {
            getMirrorDirectory(folder).listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in validExts }
                ?.forEach { names.add(it.name) }
        } catch (e: Exception) {
            // Ignore
        }
        names.size
    }

    fun deleteMediaItem(item: MediaItem, folder: MediaFolder?): Boolean {
        var deleted = false
        try {
            if (item.file.exists() && item.file.delete()) {
                deleted = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (folder != null) {
            try {
                val mirrorFile = File(getMirrorDirectory(folder), item.name)
                if (mirrorFile.exists() && mirrorFile.delete()) {
                    deleted = true
                }
                val realFile = File(toRealFile(folder.sdcardPath), item.name)
                if (realFile.exists() && realFile.delete()) {
                    deleted = true
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        return deleted
    }

    private fun parseMediaItem(file: File, folderId: String, folderDisplay: String): MediaItem {
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
                // Ignore
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
            folderName = folderDisplay,
            timestamp = file.lastModified(),
            durationSeconds = durationSec,
            sizeBytes = file.length()
        )
    }
}
