package com.example.model

data class MediaFolder(
    val id: String,
    val name: String,
    val sdcardPath: String,
    val treeUri: String? = null,
    val colorHex: String = "#38BDF8",
    val iconName: String = "Folder",
    val createdAt: Long = System.currentTimeMillis()
)
