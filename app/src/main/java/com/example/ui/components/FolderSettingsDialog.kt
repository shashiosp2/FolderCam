package com.example.ui.components

import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.data.FolderRepository
import com.example.model.MediaFolder
import com.example.model.MediaType

@Composable
fun FolderSettingsDialog(
    folders: List<MediaFolder>,
    selectedFolderId: String?,
    folderCounts: Map<String, Int>,
    isDarkTheme: Boolean,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onRestoreFromSdcardFile: () -> Unit,
    onRestoreFromJsonContent: (String) -> Unit,
    onSelectFolder: (MediaFolder) -> Unit,
    onAddFolder: (
        name: String,
        audioSdcardPath: String,
        photoSdcardPath: String,
        videoSdcardPath: String,
        audioTreeUri: String?,
        photoTreeUri: String?,
        videoTreeUri: String?
    ) -> Unit,
    onUpdateFolder: (
        folderId: String,
        name: String,
        audioSdcardPath: String,
        photoSdcardPath: String,
        videoSdcardPath: String,
        audioTreeUri: String?,
        photoTreeUri: String?,
        videoTreeUri: String?
    ) -> Unit,
    onUpdateFolderMediaPath: (
        folderId: String,
        mediaType: MediaType,
        sdcardPath: String,
        treeUri: String?
    ) -> Unit,
    onDeleteFolder: (folderId: String) -> Unit,
    onMoveFolder: (fromIndex: Int, toIndex: Int) -> Unit,
    onListSdcardDirs: (String) -> List<String>,
    onCreateSdcardDir: (String, String) -> String,
    onResolveTreeUri: (Uri) -> String,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val requiresAtLeastOneFolder = folders.isEmpty()

    var isEditingOrAdding by remember(requiresAtLeastOneFolder) {
        mutableStateOf(requiresAtLeastOneFolder)
    }
    var editingFolderId by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf("") }
    var audioLocation by remember { mutableStateOf("/sdcard/") }
    var photoLocation by remember { mutableStateOf("/sdcard/") }
    var videoLocation by remember { mutableStateOf("/sdcard/") }
    var audioTreeUri by remember { mutableStateOf<String?>(null) }
    var photoTreeUri by remember { mutableStateOf<String?>(null) }
    var videoTreeUri by remember { mutableStateOf<String?>(null) }
    var audioPathCustomized by remember { mutableStateOf(false) }
    var photoPathCustomized by remember { mutableStateOf(false) }
    var videoPathCustomized by remember { mutableStateOf(false) }

    var pickingMediaType by remember { mutableStateOf<MediaType?>(null) }
    var inlineTargetFolderId by remember { mutableStateOf<String?>(null) }
    var showSdcardBrowser by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var draggingFolderId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<String, Float>() }
    val latestFolders by rememberUpdatedState(folders)

    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val targetType = pickingMediaType ?: MediaType.PHOTO
        val inlineFolderId = inlineTargetFolderId
        pickingMediaType = null
        inlineTargetFolderId = null

        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if persistable permission not supported
            }
            val resolvedPath = onResolveTreeUri(uri)
            if (inlineFolderId != null) {
                onUpdateFolderMediaPath(inlineFolderId, targetType, resolvedPath, uri.toString())
            } else {
                when (targetType) {
                    MediaType.AUDIO -> {
                        audioLocation = resolvedPath
                        audioTreeUri = uri.toString()
                        audioPathCustomized = true
                    }
                    MediaType.PHOTO -> {
                        photoLocation = resolvedPath
                        photoTreeUri = uri.toString()
                        photoPathCustomized = true
                    }
                    MediaType.VIDEO -> {
                        videoLocation = resolvedPath
                        videoTreeUri = uri.toString()
                        videoPathCustomized = true
                    }
                }
                if (folderName.isBlank()) {
                    val suggestedName = resolvedPath.substringAfterLast('/').ifEmpty { "MyFolder" }
                    folderName = suggestedName
                }
                validationError = null
            }
        }
    }

    val openConfigFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val jsonText = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                if (!jsonText.isNullOrBlank()) {
                    onRestoreFromJsonContent(jsonText)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!requiresAtLeastOneFolder) {
                onDismiss()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("folder_settings_dialog"),
        icon = {
            Icon(
                imageVector = if (requiresAtLeastOneFolder) Icons.Default.WarningAmber else Icons.Default.SdStorage,
                contentDescription = null,
                tint = if (requiresAtLeastOneFolder) colorScheme.error else colorScheme.primary
            )
        },
        title = {
            Text(
                text = if (requiresAtLeastOneFolder) {
                    "Specify At Least 1 Folder"
                } else {
                    "Settings & Folders"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState(), enabled = draggingFolderId == null),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Theme Toggle Row (Material 3)
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                                contentDescription = null,
                                tint = colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isDarkTheme) "Dark Mode" else "Light Mode",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Material 3 Theme",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = isDarkTheme,
                            onCheckedChange = { onToggleDarkTheme(it) },
                            modifier = Modifier.testTag("settings_theme_switch")
                        )
                    }
                }

                // All Files Access (/sdcard) Status Card for Android 11+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (hasAllFilesAccess) colorScheme.outlineVariant else colorScheme.primary
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (hasAllFilesAccess) {
                                        "/sdcard Full Access Granted"
                                    } else {
                                        "Grant /sdcard All Files Access"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (hasAllFilesAccess) colorScheme.primary else colorScheme.onSurface
                                )
                                Text(
                                    text = if (hasAllFilesAccess) {
                                        "Any media type can save to any /sdcard folder & .foldercam."
                                    } else {
                                        "Needed to save audio to DCIM/Camera, photos to Recordings, & sync /sdcard/.foldercam."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                            if (!hasAllFilesAccess) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = onRequestAllFilesAccess,
                                    modifier = Modifier.testTag("grant_all_files_access_button")
                                ) {
                                    Text("Allow")
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "All files access granted",
                                    tint = colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // /sdcard/.foldercam JSON Config Sync & Restore Card
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SdStorage,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "JSON Config: /sdcard/.foldercam",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "All settings and folders are automatically saved in JSON format at /sdcard/.foldercam and can be restored anytime.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = onRestoreFromSdcardFile,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("restore_sdcard_foldercam_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Restore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Restore .foldercam", maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = { openConfigFileLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.testTag("pick_foldercam_file_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FileOpen,
                                    contentDescription = "Pick .foldercam file",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Banner if zero folders exist
                if (requiresAtLeastOneFolder) {
                    Surface(
                        color = colorScheme.errorContainer,
                        contentColor = colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("first_time_folder_required_banner")
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "No folders are configured by default. Please specify at least 1 folder (Name and /sdcard Location) or restore from /sdcard/.foldercam.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Quick Per-Media-Type Folder Configuration for the currently active folder
                val activeFolder = folders.find { it.id == selectedFolderId } ?: folders.firstOrNull()
                if (activeFolder != null && !isEditingOrAdding) {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("active_media_folders_card"),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.6f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Folders by Media Type (${activeFolder.name})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.primary
                            )
                            Text(
                                text = "Configure separate /sdcard folders for Audio, Photos, and Videos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )

                            ActiveMediaFolderRow(
                                label = "Audio",
                                path = activeFolder.pathFor(MediaType.AUDIO),
                                icon = Icons.Default.Mic,
                                browseTag = "configure_active_audio_folder",
                                onBrowseClick = {
                                    inlineTargetFolderId = activeFolder.id
                                    pickingMediaType = MediaType.AUDIO
                                    showSdcardBrowser = true
                                },
                                onSystemPickerClick = {
                                    inlineTargetFolderId = activeFolder.id
                                    pickingMediaType = MediaType.AUDIO
                                    documentTreeLauncher.launch(null)
                                }
                            )

                            ActiveMediaFolderRow(
                                label = "Photos",
                                path = activeFolder.pathFor(MediaType.PHOTO),
                                icon = Icons.Default.CameraAlt,
                                browseTag = "configure_active_photo_folder",
                                onBrowseClick = {
                                    inlineTargetFolderId = activeFolder.id
                                    pickingMediaType = MediaType.PHOTO
                                    showSdcardBrowser = true
                                },
                                onSystemPickerClick = {
                                    inlineTargetFolderId = activeFolder.id
                                    pickingMediaType = MediaType.PHOTO
                                    documentTreeLauncher.launch(null)
                                }
                            )

                            ActiveMediaFolderRow(
                                label = "Videos",
                                path = activeFolder.pathFor(MediaType.VIDEO),
                                icon = Icons.Default.Videocam,
                                browseTag = "configure_active_video_folder",
                                onBrowseClick = {
                                    inlineTargetFolderId = activeFolder.id
                                    pickingMediaType = MediaType.VIDEO
                                    showSdcardBrowser = true
                                },
                                onSystemPickerClick = {
                                    inlineTargetFolderId = activeFolder.id
                                    pickingMediaType = MediaType.VIDEO
                                    documentTreeLauncher.launch(null)
                                }
                            )
                        }
                    }
                }

                // Add / Edit Folder Form
                if (isEditingOrAdding) {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, colorScheme.primary)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = if (editingFolderId != null) "Edit Folder & Media Paths" else "New Folder Preset",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.primary
                            )

                            OutlinedTextField(
                                value = folderName,
                                onValueChange = {
                                    folderName = it
                                    validationError = null
                                    val safe = it.trim().replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                                    if (safe.isNotEmpty()) {
                                        if (!audioPathCustomized) audioLocation = "/sdcard/Recordings/$safe"
                                        if (!photoPathCustomized) photoLocation = "/sdcard/DCIM/$safe"
                                        if (!videoPathCustomized) videoLocation = "/sdcard/Movies/$safe"
                                    }
                                },
                                label = { Text("Folder Button Name (e.g. Default, Work)") },
                                placeholder = { Text("Enter folder button name") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("new_folder_name_input")
                            )

                            MediaTypeFolderField(
                                title = "Audio Folder (/sdcard)",
                                placeholder = "/sdcard/Recordings/MyAudio",
                                icon = Icons.Default.Mic,
                                value = audioLocation,
                                inputTestTag = "new_folder_audio_location_input",
                                browseTestTag = "browse_audio_sdcard_button",
                                onValueChange = {
                                    audioLocation = it
                                    audioPathCustomized = true
                                    validationError = null
                                },
                                onBrowseClick = {
                                    inlineTargetFolderId = null
                                    pickingMediaType = MediaType.AUDIO
                                    showSdcardBrowser = true
                                },
                                onSystemPickerClick = {
                                    inlineTargetFolderId = null
                                    pickingMediaType = MediaType.AUDIO
                                    documentTreeLauncher.launch(null)
                                }
                            )

                            MediaTypeFolderField(
                                title = "Photos Folder (/sdcard)",
                                placeholder = "/sdcard/DCIM/MyPhotos",
                                icon = Icons.Default.CameraAlt,
                                value = photoLocation,
                                inputTestTag = "new_folder_photo_location_input",
                                browseTestTag = "browse_photo_sdcard_button",
                                onValueChange = {
                                    photoLocation = it
                                    photoPathCustomized = true
                                    validationError = null
                                },
                                onBrowseClick = {
                                    inlineTargetFolderId = null
                                    pickingMediaType = MediaType.PHOTO
                                    showSdcardBrowser = true
                                },
                                onSystemPickerClick = {
                                    inlineTargetFolderId = null
                                    pickingMediaType = MediaType.PHOTO
                                    documentTreeLauncher.launch(null)
                                }
                            )

                            MediaTypeFolderField(
                                title = "Videos Folder (/sdcard)",
                                placeholder = "/sdcard/Movies/MyVideos",
                                icon = Icons.Default.Videocam,
                                value = videoLocation,
                                inputTestTag = "new_folder_video_location_input",
                                browseTestTag = "browse_video_sdcard_button",
                                onValueChange = {
                                    videoLocation = it
                                    videoPathCustomized = true
                                    validationError = null
                                },
                                onBrowseClick = {
                                    inlineTargetFolderId = null
                                    pickingMediaType = MediaType.VIDEO
                                    showSdcardBrowser = true
                                },
                                onSystemPickerClick = {
                                    inlineTargetFolderId = null
                                    pickingMediaType = MediaType.VIDEO
                                    documentTreeLauncher.launch(null)
                                }
                            )

                            validationError?.let { err ->
                                Text(
                                    text = err,
                                    color = colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (!requiresAtLeastOneFolder) {
                                    TextButton(
                                        onClick = {
                                            isEditingOrAdding = false
                                            editingFolderId = null
                                            validationError = null
                                        }
                                    ) {
                                        Text("Cancel")
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                }

                                Button(
                                    onClick = {
                                        val cleanName = folderName.trim()
                                        if (cleanName.isEmpty()) {
                                            validationError = "Please enter a folder name."
                                        } else {
                                            val safe = cleanName.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                                                .ifEmpty { "Folder" }
                                            val resolvedAudio = resolvePathOrDefault(
                                                raw = audioLocation,
                                                customized = audioPathCustomized,
                                                defaultPath = "/sdcard/Recordings/$safe",
                                                fallbackName = cleanName
                                            )
                                            val resolvedPhoto = resolvePathOrDefault(
                                                raw = photoLocation,
                                                customized = photoPathCustomized,
                                                defaultPath = "/sdcard/DCIM/$safe",
                                                fallbackName = cleanName
                                            )
                                            val resolvedVideo = resolvePathOrDefault(
                                                raw = videoLocation,
                                                customized = videoPathCustomized,
                                                defaultPath = "/sdcard/Movies/$safe",
                                                fallbackName = cleanName
                                            )
                                            saveOrUpdate(
                                                editingFolderId = editingFolderId,
                                                name = cleanName,
                                                audioPath = resolvedAudio,
                                                photoPath = resolvedPhoto,
                                                videoPath = resolvedVideo,
                                                audioTreeUri = audioTreeUri,
                                                photoTreeUri = photoTreeUri,
                                                videoTreeUri = videoTreeUri,
                                                onAddFolder = onAddFolder,
                                                onUpdateFolder = onUpdateFolder
                                            )
                                            folderName = ""
                                            audioLocation = "/sdcard/"
                                            photoLocation = "/sdcard/"
                                            videoLocation = "/sdcard/"
                                            audioTreeUri = null
                                            photoTreeUri = null
                                            videoTreeUri = null
                                            audioPathCustomized = false
                                            photoPathCustomized = false
                                            videoPathCustomized = false
                                            editingFolderId = null
                                            isEditingOrAdding = false
                                        }
                                    },
                                    modifier = Modifier.testTag("save_new_folder_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (editingFolderId != null) "Update Folder" else "Save Folder")
                                }
                            }
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = {
                            editingFolderId = null
                            folderName = ""
                            audioLocation = "/sdcard/"
                            photoLocation = "/sdcard/"
                            videoLocation = "/sdcard/"
                            audioTreeUri = null
                            photoTreeUri = null
                            videoTreeUri = null
                            audioPathCustomized = false
                            photoPathCustomized = false
                            videoPathCustomized = false
                            isEditingOrAdding = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_add_folder_form")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Folder Preset")
                    }
                }

                // Existing Folders List (supports drag-and-drop & up/down reordering)
                if (folders.isNotEmpty()) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Configured Folders (${folders.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.onSurfaceVariant
                            )
                            if (folders.size > 1) {
                                Text(
                                    text = "Drag handle or use arrows to reorder bottom bar",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }

                    val itemSpacingPx = with(density) { 8.dp.toPx() }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        folders.forEachIndexed { index, folder ->
                            key(folder.id) {
                                val isSelected = folder.id == selectedFolderId
                                val isDragging = draggingFolderId == folder.id
                                val count = folderCounts[folder.id] ?: 0
                                val canMoveUp = index > 0
                                val canMoveDown = index < folders.lastIndex

                                OutlinedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .onSizeChanged { size ->
                                            itemHeights[folder.id] = size.height.toFloat()
                                        }
                                        .graphicsLayer {
                                            if (isDragging) {
                                                translationY = dragOffsetY
                                                scaleX = 1.02f
                                                scaleY = 1.02f
                                            }
                                        }
                                        .clickable { onSelectFolder(folder) },
                                    border = BorderStroke(
                                        width = when {
                                            isDragging -> 2.dp
                                            isSelected -> 2.dp
                                            else -> 1.dp
                                        },
                                        color = when {
                                            isDragging -> colorScheme.tertiary
                                            isSelected -> colorScheme.primary
                                            else -> colorScheme.outlineVariant
                                        }
                                    )
                                ) {
                                    Column {
                                        ListItem(
                                            headlineContent = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (folders.size > 1) {
                                                        Icon(
                                                            imageVector = Icons.Default.Folder,
                                                            contentDescription = null,
                                                            tint = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                    }
                                                    Text(
                                                        text = folder.name,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                    if (isSelected) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Badge(
                                                            containerColor = colorScheme.primaryContainer,
                                                            contentColor = colorScheme.onPrimaryContainer
                                                        ) {
                                                            Text("Active")
                                                        }
                                                    }
                                                }
                                            },
                                            supportingContent = {
                                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    if (folder.hasSeparateMediaPaths()) {
                                                        Text(
                                                            text = "Audio: ${folder.pathFor(MediaType.AUDIO)}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = colorScheme.primary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = "Photos: ${folder.pathFor(MediaType.PHOTO)}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = colorScheme.primary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = "Videos: ${folder.pathFor(MediaType.VIDEO)}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = colorScheme.primary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    } else {
                                                        Text(
                                                            text = folder.sdcardPath,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = colorScheme.primary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Text(
                                                        text = "$count saved item(s)",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            leadingContent = {
                                                if (folders.size > 1) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(48.dp)
                                                            .pointerInput(folder.id) {
                                                                detectDragGestures(
                                                                    onDragStart = {
                                                                        draggingFolderId = folder.id
                                                                        dragOffsetY = 0f
                                                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                    },
                                                                    onDragEnd = {
                                                                        draggingFolderId = null
                                                                        dragOffsetY = 0f
                                                                    },
                                                                    onDragCancel = {
                                                                        draggingFolderId = null
                                                                        dragOffsetY = 0f
                                                                    },
                                                                    onDrag = { change, dragAmount ->
                                                                        change.consume()
                                                                        dragOffsetY += dragAmount.y
                                                                        val currentList = latestFolders
                                                                        val currentIdx = currentList.indexOfFirst { it.id == folder.id }
                                                                        if (currentIdx == -1) return@detectDragGestures

                                                                        if (dragOffsetY > 0f && currentIdx < currentList.lastIndex) {
                                                                            val nextFolder = currentList[currentIdx + 1]
                                                                            val stepHeight = (itemHeights[nextFolder.id] ?: 180f) + itemSpacingPx
                                                                            if (dragOffsetY >= stepHeight * 0.5f) {
                                                                                onMoveFolder(currentIdx, currentIdx + 1)
                                                                                dragOffsetY -= stepHeight
                                                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                            }
                                                                        } else if (dragOffsetY < 0f && currentIdx > 0) {
                                                                            val prevFolder = currentList[currentIdx - 1]
                                                                            val stepHeight = (itemHeights[prevFolder.id] ?: 180f) + itemSpacingPx
                                                                            if (dragOffsetY <= -stepHeight * 0.5f) {
                                                                                onMoveFolder(currentIdx, currentIdx - 1)
                                                                                dragOffsetY += stepHeight
                                                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                            }
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                            .testTag("drag_folder_handle_${folder.id}"),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.DragHandle,
                                                            contentDescription = "Drag to reorder ${folder.name}",
                                                            tint = if (isDragging) colorScheme.tertiary else colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.Default.Folder,
                                                        contentDescription = null,
                                                        tint = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            trailingContent = {
                                                Row {
                                                    IconButton(
                                                        onClick = {
                                                            editingFolderId = folder.id
                                                            folderName = folder.name
                                                            audioLocation = folder.pathFor(MediaType.AUDIO)
                                                            photoLocation = folder.pathFor(MediaType.PHOTO)
                                                            videoLocation = folder.pathFor(MediaType.VIDEO)
                                                            audioTreeUri = folder.treeUriFor(MediaType.AUDIO)
                                                            photoTreeUri = folder.treeUriFor(MediaType.PHOTO)
                                                            videoTreeUri = folder.treeUriFor(MediaType.VIDEO)
                                                            audioPathCustomized = true
                                                            photoPathCustomized = true
                                                            videoPathCustomized = true
                                                            isEditingOrAdding = true
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Edit,
                                                            contentDescription = "Edit Folder",
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = { onDeleteFolder(folder.id) }
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.DeleteOutline,
                                                            contentDescription = "Delete Folder",
                                                            tint = colorScheme.error,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            },
                                            colors = ListItemDefaults.colors(
                                                containerColor = when {
                                                    isDragging -> colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                                                    isSelected -> colorScheme.primaryContainer.copy(alpha = 0.25f)
                                                    else -> colorScheme.surface
                                                }
                                            )
                                        )

                                        if (folders.size > 1) {
                                            HorizontalDivider(
                                                color = colorScheme.outlineVariant.copy(alpha = 0.6f)
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Position #${index + 1}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = colorScheme.onSurfaceVariant
                                                )
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IconButton(
                                                        onClick = {
                                                            if (canMoveUp) {
                                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                onMoveFolder(index, index - 1)
                                                            }
                                                        },
                                                        enabled = canMoveUp,
                                                        modifier = Modifier.testTag("move_folder_up_${folder.id}")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.ArrowUpward,
                                                            contentDescription = "Move ${folder.name} up",
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            if (canMoveDown) {
                                                                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                onMoveFolder(index, index + 1)
                                                            }
                                                        },
                                                        enabled = canMoveDown,
                                                        modifier = Modifier.testTag("move_folder_down_${folder.id}")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.ArrowDownward,
                                                            contentDescription = "Move ${folder.name} down",
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!requiresAtLeastOneFolder) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_settings_button")
                ) {
                    Text("Done")
                }
            }
        }
    )

    // Interactive /sdcard Directory Browser Modal
    if (showSdcardBrowser) {
        val targetType = pickingMediaType ?: MediaType.PHOTO
        val inlineFolder = inlineTargetFolderId?.let { id -> folders.find { it.id == id } }
        val initialPath = if (inlineFolder != null) {
            inlineFolder.pathFor(targetType)
        } else {
            when (targetType) {
                MediaType.AUDIO -> audioLocation
                MediaType.PHOTO -> photoLocation
                MediaType.VIDEO -> videoLocation
            }
        }
        val pickerTitle = when (targetType) {
            MediaType.AUDIO -> "Select Audio Folder on /sdcard"
            MediaType.PHOTO -> "Select Photos Folder on /sdcard"
            MediaType.VIDEO -> "Select Videos Folder on /sdcard"
        }

        SdcardFolderPickerDialog(
            title = pickerTitle,
            initialSdcardPath = initialPath,
            onListDirectories = onListSdcardDirs,
            onCreateDirectory = onCreateSdcardDir,
            onFolderPicked = { pickedSdcardPath ->
                if (inlineFolder != null) {
                    onUpdateFolderMediaPath(inlineFolder.id, targetType, pickedSdcardPath, null)
                } else {
                    when (targetType) {
                        MediaType.AUDIO -> {
                            audioLocation = pickedSdcardPath
                            audioTreeUri = null
                            audioPathCustomized = true
                        }
                        MediaType.PHOTO -> {
                            photoLocation = pickedSdcardPath
                            photoTreeUri = null
                            photoPathCustomized = true
                        }
                        MediaType.VIDEO -> {
                            videoLocation = pickedSdcardPath
                            videoTreeUri = null
                            videoPathCustomized = true
                        }
                    }
                    if (folderName.isBlank() && pickedSdcardPath != "/sdcard") {
                        folderName = pickedSdcardPath.substringAfterLast('/')
                    }
                }
                showSdcardBrowser = false
                pickingMediaType = null
                inlineTargetFolderId = null
                validationError = null
            },
            onDismiss = {
                showSdcardBrowser = false
                pickingMediaType = null
                inlineTargetFolderId = null
            }
        )
    }
}

@Composable
private fun ActiveMediaFolderRow(
    label: String,
    path: String,
    icon: ImageVector,
    browseTag: String,
    onBrowseClick: () -> Unit,
    onSystemPickerClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(
                onClick = onBrowseClick,
                modifier = Modifier.testTag(browseTag)
            ) {
                Text("Browse")
            }
            IconButton(onClick = onSystemPickerClick) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "System picker for $label",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaTypeFolderField(
    title: String,
    placeholder: String,
    icon: ImageVector,
    value: String,
    inputTestTag: String,
    browseTestTag: String,
    onValueChange: (String) -> Unit,
    onBrowseClick: () -> Unit,
    onSystemPickerClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(title) },
            placeholder = { Text(placeholder) },
            leadingIcon = {
                Icon(
                    imageVector = icon,
                    contentDescription = null
                )
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(inputTestTag)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onBrowseClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag(browseTestTag)
            ) {
                Icon(
                    imageVector = Icons.Default.SdStorage,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Browse /sdcard")
            }
            OutlinedButton(onClick = onSystemPickerClick) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("System")
            }
        }
    }
}

private fun resolvePathOrDefault(
    raw: String,
    customized: Boolean,
    defaultPath: String,
    fallbackName: String
): String {
    val trimmed = raw.trim()
    val isBlankPlaceholder = trimmed.isEmpty() || (!customized && (trimmed == "/sdcard/" || trimmed == "/sdcard"))
    val candidate = if (isBlankPlaceholder) defaultPath else trimmed
    return FolderRepository.normalizeSdcardPath(candidate, fallbackName)
}

private fun saveOrUpdate(
    editingFolderId: String?,
    name: String,
    audioPath: String,
    photoPath: String,
    videoPath: String,
    audioTreeUri: String?,
    photoTreeUri: String?,
    videoTreeUri: String?,
    onAddFolder: (String, String, String, String, String?, String?, String?) -> Unit,
    onUpdateFolder: (String, String, String, String, String, String?, String?, String?) -> Unit
) {
    if (editingFolderId != null) {
        onUpdateFolder(
            editingFolderId,
            name,
            audioPath,
            photoPath,
            videoPath,
            audioTreeUri,
            photoTreeUri,
            videoTreeUri
        )
    } else {
        onAddFolder(
            name,
            audioPath,
            photoPath,
            videoPath,
            audioTreeUri,
            photoTreeUri,
            videoTreeUri
        )
    }
}

@Composable
fun SdcardFolderPickerDialog(
    title: String = "Select Folder on /sdcard",
    initialSdcardPath: String,
    onListDirectories: (String) -> List<String>,
    onCreateDirectory: (String, String) -> String,
    onFolderPicked: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var currentPath by remember {
        val clean = FolderRepository.normalizeSdcardPath(initialSdcardPath, "")
        mutableStateOf(if (clean.startsWith("/sdcard")) clean else "/sdcard")
    }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val directories = remember(currentPath, refreshTrigger) {
        onListDirectories(currentPath)
    }
    var isCreatingSubfolder by remember { mutableStateOf(false) }
    var newSubfolderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.SdStorage,
                contentDescription = null,
                tint = colorScheme.primary
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    color = colorScheme.secondaryContainer,
                    contentColor = colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (currentPath != "/sdcard") {
                                IconButton(
                                    onClick = {
                                        val parent = currentPath.substringBeforeLast('/').ifEmpty { "/sdcard" }
                                        currentPath = if (parent.startsWith("/sdcard")) parent else "/sdcard"
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Up directory"
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = currentPath,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { isCreatingSubfolder = !isCreatingSubfolder },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = "Create subfolder on /sdcard"
                            )
                        }
                    }
                }

                if (isCreatingSubfolder) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newSubfolderName,
                                onValueChange = { newSubfolderName = it },
                                label = { Text("New folder in $currentPath") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { isCreatingSubfolder = false }) {
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        if (newSubfolderName.isNotBlank()) {
                                            val createdPath = onCreateDirectory(currentPath, newSubfolderName)
                                            currentPath = createdPath
                                            newSubfolderName = ""
                                            isCreatingSubfolder = false
                                            refreshTrigger++
                                        }
                                    },
                                    enabled = newSubfolderName.isNotBlank()
                                ) {
                                    Text("Create & Open")
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Tap a folder below to browse inside, or tap 'Select $currentPath'",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (directories.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No subfolders in $currentPath",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(directories) { dirPath ->
                            val folderTitle = dirPath.substringAfterLast('/')
                            OutlinedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPath = dirPath }
                                    .testTag("sdcard_dir_item_$folderTitle")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = folderTitle,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = dirPath,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    TextButton(onClick = { onFolderPicked(dirPath) }) {
                                        Text("Use")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = { onFolderPicked(currentPath) },
                modifier = Modifier.testTag("confirm_sdcard_folder_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Select $currentPath")
            }
        }
    )
}
