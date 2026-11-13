package com.example.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
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
import java.util.concurrent.atomic.AtomicInteger

data class MediaSaveResult(
    val savedToPublicSdcard: Boolean,
    val destinationPath: String,
    val finalFile: File,
    val requiresAllFilesAccess: Boolean = false,
    val errorMessage: String? = null
)

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

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
    private val fileSequence = AtomicInteger(0)

    private fun buildUniqueFileName(type: MediaType): String {
        val timestamp = synchronized(dateFormat) {
            dateFormat.format(Date())
        }
        val seq = fileSequence.incrementAndGet() % 1000
        val seqStr = String.format(Locale.US, "%03d", seq)
        val (prefix, ext) = when (type) {
            MediaType.PHOTO -> "IMG_" to ".jpg"
            MediaType.VIDEO -> "VID_" to ".mp4"
            MediaType.AUDIO -> "AUD_" to ".m4a"
        }
        return "$prefix${timestamp}_$seqStr$ext"
    }

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

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (e: Exception) {
                false
            }
        } else {
            true
        }
    }

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
        } else if (normalized == "/sdcard/DCIM") {
            // Ensure Camera is visible even if OS hides DCIM children before All Files Access
            val cameraDir = toRealFile("/sdcard/DCIM/Camera")
            if (cameraDir.exists() || !hasAllFilesAccess()) {
                dirSet.add("/sdcard/DCIM/Camera")
            }
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
            // Ignore if direct mkdirs requires All Files Access / MediaStore
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
            // Example docId: "primary:DCIM/Camera" or "primary:Recordings/MyFolder"
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

    private fun canWriteDirectlyToSdcardDir(realSdcardDir: File): Boolean {
        return try {
            if (!realSdcardDir.exists()) {
                realSdcardDir.mkdirs()
            }
            if (!realSdcardDir.exists() || !realSdcardDir.isDirectory || !realSdcardDir.canWrite()) {
                return false
            }
            // On Android 11+, canWrite() can occasionally return true on FUSE directories even when file creation fails
            // unless All Files Access is granted or running in JVM/test environment. Verify cleanly:
            val probe = File(realSdcardDir, ".foldercam_probe_${System.nanoTime()}")
            if (probe.createNewFile()) {
                probe.delete()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun prepareStagingFallbackFile(folder: MediaFolder, desiredFileName: String): File {
        val mirrorDir = getMirrorDirectory(folder)
        return File(mirrorDir, desiredFileName)
    }

    fun prepareFastPhotoFile(folder: MediaFolder): File {
        val mirrorDir = getMirrorDirectory(folder)
        return File(mirrorDir, buildUniqueFileName(MediaType.PHOTO))
    }

    fun prepareOutputFile(folder: MediaFolder, type: MediaType): File {
        val fileName = buildUniqueFileName(type)

        try {
            val realSdcardDir = toRealFile(folder.sdcardPath)
            if (canWriteDirectlyToSdcardDir(realSdcardDir)) {
                return File(realSdcardDir, fileName)
            }
        } catch (e: Exception) {
            // Fallback to mirror staging file
        }

        val mirrorDir = getMirrorDirectory(folder)
        return File(mirrorDir, fileName)
    }

    suspend fun finalizeCapturedFile(
        folder: MediaFolder,
        capturedFile: File,
        type: MediaType
    ): MediaSaveResult = withContext(Dispatchers.IO) {
        if (!capturedFile.exists()) {
            return@withContext MediaSaveResult(
                savedToPublicSdcard = false,
                destinationPath = folder.sdcardPath,
                finalFile = capturedFile,
                errorMessage = "Captured file not found"
            )
        }

        val mimeType = mimeTypeFor(type)
        val realSdcardDir = toRealFile(folder.sdcardPath)
        val alreadyInTargetSdcardDir = try {
            capturedFile.parentFile?.canonicalPath == realSdcardDir.canonicalPath
        } catch (e: Exception) {
            capturedFile.parentFile?.absolutePath == realSdcardDir.absolutePath
        }

        // 1. If captured directly into the target /sdcard directory, scan into MediaStore and return success
        if (alreadyInTargetSdcardDir) {
            scanMediaFile(capturedFile.absolutePath, mimeType)
            return@withContext MediaSaveResult(
                savedToPublicSdcard = true,
                destinationPath = "${folder.sdcardPath}/${capturedFile.name}",
                finalFile = capturedFile
            )
        }

        // 2. Try copying from staging mirror directory directly into the exact /sdcard target folder
        try {
            if (canWriteDirectlyToSdcardDir(realSdcardDir)) {
                val destFile = File(realSdcardDir, capturedFile.name)
                capturedFile.copyTo(destFile, overwrite = true)
                if (destFile.exists() && destFile.length() > 0) {
                    capturedFile.delete()
                    scanMediaFile(destFile.absolutePath, mimeType)
                    return@withContext MediaSaveResult(
                        savedToPublicSdcard = true,
                        destinationPath = "${folder.sdcardPath}/${destFile.name}",
                        finalFile = destFile
                    )
                }
            }
        } catch (e: Exception) {
            // Fall through to SAF / MediaStore
        }

        // 3. If folder has a SAF treeUri, export to the exact folder (including nested subfolders under treeUri)
        folder.treeUri?.let { uriStr ->
            try {
                val treeUri = Uri.parse(uriStr)
                val targetDocUri = resolveOrCreateSubfolderDocUri(
                    treeUri = treeUri,
                    targetSdcardPath = folder.sdcardPath
                )
                if (targetDocUri != null) {
                    val newDocUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        targetDocUri,
                        mimeType,
                        capturedFile.name
                    )
                    if (newDocUri != null) {
                        context.contentResolver.openOutputStream(newDocUri)?.use { out ->
                            capturedFile.inputStream().use { input ->
                                input.copyTo(out)
                            }
                        }
                        return@withContext MediaSaveResult(
                            savedToPublicSdcard = true,
                            destinationPath = "${folder.sdcardPath}/${capturedFile.name}",
                            finalFile = capturedFile
                        )
                    }
                }
            } catch (e: Exception) {
                // Continue to MediaStore check
            }
        }

        // 4. Scoped Storage MediaStore fallback ONLY when Android allows this MediaType in the exact target folder.
        //    Never silently move Audio out of DCIM/Camera into Music/ or Photos out of Recordings/ into DCIM/!
        val relativeFromSdcard = folder.sdcardPath.removePrefix("/sdcard").trim('/')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isMediaStoreCompatible(type, relativeFromSdcard)) {
            try {
                val collectionUri = when (type) {
                    MediaType.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, capturedFile.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFromSdcard)
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
                    return@withContext MediaSaveResult(
                        savedToPublicSdcard = true,
                        destinationPath = "${folder.sdcardPath}/${capturedFile.name}",
                        finalFile = capturedFile
                    )
                }
            } catch (e: Exception) {
                // Fall through to requiresAllFilesAccess result
            }
        }

        // 5. File remains safely stored in app mirror so nothing is lost, and inform caller All Files Access is needed
        //    to place cross-media types (e.g. Audio in DCIM/Camera or Photos in Recordings/...) into /sdcard.
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()
        return@withContext MediaSaveResult(
            savedToPublicSdcard = false,
            destinationPath = folder.sdcardPath,
            finalFile = capturedFile,
            requiresAllFilesAccess = needsPermission,
            errorMessage = if (needsPermission) {
                "Grant 'All Files Access' in Settings to save ${type.name.lowercase()} directly to ${folder.sdcardPath}"
            } else {
                "Could not write directly to ${folder.sdcardPath}; kept in app storage"
            }
        )
    }

    fun isMediaStoreCompatible(type: MediaType, relativeFromSdcard: String): Boolean {
        if (relativeFromSdcard.isEmpty()) return false
        val primaryDir = relativeFromSdcard.substringBefore('/').lowercase(Locale.US)
        return when (type) {
            MediaType.PHOTO -> primaryDir in setOf("dcim", "pictures")
            MediaType.VIDEO -> primaryDir in setOf("dcim", "movies", "pictures")
            MediaType.AUDIO -> primaryDir in setOf(
                "music",
                "recordings",
                "podcasts",
                "audiobooks",
                "alarms",
                "notifications",
                "ringtones"
            )
        }
    }

    private fun resolveOrCreateSubfolderDocUri(treeUri: Uri, targetSdcardPath: String): Uri? {
        val treeBasePath = resolveSdcardPathFromTreeUri(treeUri).trimEnd('/')
        val cleanTarget = FolderRepository.normalizeSdcardPath(targetSdcardPath, "").trimEnd('/')
        var currentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        var currentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)

        if (cleanTarget == treeBasePath) {
            return currentDocUri
        }
        if (!cleanTarget.startsWith("$treeBasePath/")) {
            return null
        }

        val subSegments = cleanTarget.removePrefix("$treeBasePath/").split('/').filter { it.isNotEmpty() }
        for (segment in subSegments) {
            val childId = findChildDirectoryDocId(treeUri, currentDocId, segment)
            if (childId != null) {
                currentDocId = childId
                currentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
            } else {
                val createdUri = DocumentsContract.createDocument(
                    context.contentResolver,
                    currentDocUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    segment
                ) ?: return null
                currentDocId = DocumentsContract.getDocumentId(createdUri)
                currentDocUri = createdUri
            }
        }
        return currentDocUri
    }

    private fun findChildDirectoryDocId(treeUri: Uri, parentDocId: String, childName: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        return try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                    if (name == childName && mime == DocumentsContract.Document.MIME_TYPE_DIR && idIdx >= 0) {
                        return@use cursor.getString(idIdx)
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun scanMediaFile(path: String, mimeType: String) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(path),
                arrayOf(mimeType),
                null
            )
        } catch (e: Exception) {
            // Ignore in test environments
        }
    }

    private fun mimeTypeFor(type: MediaType): String = when (type) {
        MediaType.PHOTO -> "image/jpeg"
        MediaType.VIDEO -> "video/mp4"
        MediaType.AUDIO -> "audio/mp4"
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
                scanMediaFile(item.file.absolutePath, mimeTypeFor(item.type))
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
                    scanMediaFile(realFile.absolutePath, mimeTypeFor(item.type))
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
