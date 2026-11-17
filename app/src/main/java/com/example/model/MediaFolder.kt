package com.example.model

data class MediaFolder(
    val id: String,
    val name: String,
    val sdcardPath: String,
    val audioSdcardPath: String = sdcardPath,
    val photoSdcardPath: String = sdcardPath,
    val videoSdcardPath: String = sdcardPath,
    val treeUri: String? = null,
    val audioTreeUri: String? = treeUri,
    val photoTreeUri: String? = treeUri,
    val videoTreeUri: String? = treeUri,
    val colorHex: String = "#38BDF8",
    val iconName: String = "Folder",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun pathFor(type: MediaType): String = when (type) {
        MediaType.AUDIO -> audioSdcardPath.ifBlank { sdcardPath }
        MediaType.PHOTO -> photoSdcardPath.ifBlank { sdcardPath }
        MediaType.VIDEO -> videoSdcardPath.ifBlank { sdcardPath }
    }

    fun treeUriFor(type: MediaType): String? = when (type) {
        MediaType.AUDIO -> audioTreeUri ?: treeUri
        MediaType.PHOTO -> photoTreeUri ?: treeUri
        MediaType.VIDEO -> videoTreeUri ?: treeUri
    }

    fun distinctPaths(): List<String> = listOf(
        pathFor(MediaType.AUDIO),
        pathFor(MediaType.PHOTO),
        pathFor(MediaType.VIDEO)
    ).distinct()

    fun hasSeparateMediaPaths(): Boolean = distinctPaths().size > 1
}
