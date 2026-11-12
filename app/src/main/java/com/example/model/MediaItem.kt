package com.example.model

import android.net.Uri
import java.io.File

enum class MediaType {
    PHOTO,
    VIDEO,
    AUDIO
}

data class MediaItem(
    val id: String,
    val name: String,
    val file: File,
    val uri: Uri,
    val type: MediaType,
    val folderId: String,
    val folderName: String,
    val timestamp: Long,
    val durationSeconds: Long = 0L,
    val sizeBytes: Long = 0L
)
