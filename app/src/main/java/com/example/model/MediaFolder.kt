package com.example.model

data class MediaFolder(
    val id: String,
    val name: String,
    val sdcardPath: String,
    val treeUri: String? = null,
    val enableAudio: Boolean = true,
    val enablePhoto: Boolean = true,
    val enableVideo: Boolean = true,
    val isDefaultInbox: Boolean = false,
    val colorHex: String = "#38BDF8",
    val iconName: String = "Folder",
    val createdAt: Long = System.currentTimeMillis()
) {
    val isInbox: Boolean
        get() = isDefaultInbox || id == INBOX_FOLDER_ID

    fun isEnabledFor(mode: RecorderMode): Boolean = when {
        isInbox -> true
        mode == RecorderMode.AUDIO -> enableAudio
        mode == RecorderMode.PHOTO -> enablePhoto
        mode == RecorderMode.VIDEO -> enableVideo
        else -> true
    }

    fun isEnabledFor(type: MediaType): Boolean = when {
        isInbox -> true
        type == MediaType.AUDIO -> enableAudio
        type == MediaType.PHOTO -> enablePhoto
        type == MediaType.VIDEO -> enableVideo
        else -> true
    }

    fun hasAnyModeEnabled(): Boolean = isInbox || enableAudio || enablePhoto || enableVideo

    companion object {
        const val INBOX_FOLDER_ID = "inbox"
        const val INBOX_DEFAULT_NAME = "Inbox"
        const val INBOX_DEFAULT_PATH = "/sdcard/inbox"

        fun createDefaultInbox(
            sdcardPath: String = INBOX_DEFAULT_PATH,
            treeUri: String? = null,
            colorHex: String = "#38BDF8",
            createdAt: Long = System.currentTimeMillis()
        ): MediaFolder = MediaFolder(
            id = INBOX_FOLDER_ID,
            name = INBOX_DEFAULT_NAME,
            sdcardPath = sdcardPath.ifBlank { INBOX_DEFAULT_PATH },
            treeUri = treeUri,
            enableAudio = true,
            enablePhoto = true,
            enableVideo = true,
            isDefaultInbox = true,
            colorHex = colorHex,
            iconName = "Inbox",
            createdAt = createdAt
        )
    }
}
