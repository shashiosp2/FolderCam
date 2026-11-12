package com.example.model

data class MediaFolder(
    val id: String,
    val name: String,
    val colorHex: String = "#38BDF8",
    val iconName: String = "Folder",
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        val PredefinedFolders = listOf(
            MediaFolder(
                id = "general",
                name = "General",
                colorHex = "#38BDF8",
                iconName = "Folder",
                isDefault = true
            ),
            MediaFolder(
                id = "work",
                name = "Work",
                colorHex = "#F59E0B",
                iconName = "Work"
            ),
            MediaFolder(
                id = "personal",
                name = "Personal",
                colorHex = "#EC4899",
                iconName = "Person"
            ),
            MediaFolder(
                id = "field_notes",
                name = "Field Notes",
                colorHex = "#10B981",
                iconName = "Note"
            ),
            MediaFolder(
                id = "receipts",
                name = "Receipts",
                colorHex = "#8B5CF6",
                iconName = "Receipt"
            ),
            MediaFolder(
                id = "lectures",
                name = "Lectures",
                colorHex = "#06B6D4",
                iconName = "School"
            )
        )
    }
}
