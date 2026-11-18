package com.example.model

data class MediaFolder(
    val id: String,
    val name: String,
    val sdcardPath: String,
    val treeUri: String? = null,
    val enableAudio: Boolean = true,
    val enablePhoto: Boolean = true,
    val enableVideo: Boolean = true,
    val colorHex: String = "#38BDF8",
    val iconName: String = "Folder",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun isEnabledFor(mode: RecorderMode): Boolean = when (mode) {
        RecorderMode.AUDIO -> enableAudio
        RecorderMode.PHOTO -> enablePhoto
        RecorderMode.VIDEO -> enableVideo
    }

    fun isEnabledFor(type: MediaType): Boolean = when (type) {
        MediaType.AUDIO -> enableAudio
        MediaType.PHOTO -> enablePhoto
        MediaType.VIDEO -> enableVideo
    }

    fun hasAnyModeEnabled(): Boolean = enableAudio || enablePhoto || enableVideo
}
