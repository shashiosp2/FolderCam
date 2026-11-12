package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SdStorage
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.FolderRepository
import com.example.model.MediaFolder

@Composable
fun FolderSettingsDialog(
    folders: List<MediaFolder>,
    selectedFolderId: String?,
    folderCounts: Map<String, Int>,
    isDarkTheme: Boolean,
    onToggleDarkTheme: (Boolean) -> Unit,
    onRestoreFromSdcardFile: () -> Unit,
    onRestoreFromJsonContent: (String) -> Unit,
    onSelectFolder: (MediaFolder) -> Unit,
    onAddFolder: (name: String, sdcardPath: String, treeUri: String?) -> Unit,
    onUpdateFolder: (folderId: String, name: String, sdcardPath: String, treeUri: String?) -> Unit,
    onDeleteFolder: (folderId: String) -> Unit,
    onListSdcardDirs: (String) -> List<String>,
    onCreateSdcardDir: (String, String) -> String,
    onResolveTreeUri: (Uri) -> String,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val requiresAtLeastOneFolder = folders.isEmpty()

    var isEditingOrAdding by remember(requiresAtLeastOneFolder) {
        mutableStateOf(requiresAtLeastOneFolder)
    }
    var editingFolderId by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf("") }
    var sdcardLocation by remember { mutableStateOf("/sdcard/") }
    var selectedTreeUri by remember { mutableStateOf<String?>(null) }
    var showSdcardBrowser by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val documentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if persistable permission not supported
            }
            selectedTreeUri = uri.toString()
            val resolvedPath = onResolveTreeUri(uri)
            sdcardLocation = resolvedPath
            if (folderName.isBlank()) {
                val suggestedName = resolvedPath.substringAfterLast('/').ifEmpty { "MyFolder" }
                folderName = suggestedName
            }
            validationError = null
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
                    .verticalScroll(rememberScrollState()),
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
                                text = if (editingFolderId != null) "Edit Folder" else "New Destination Folder",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.primary
                            )

                            OutlinedTextField(
                                value = folderName,
                                onValueChange = {
                                    folderName = it
                                    validationError = null
                                    if (sdcardLocation == "/sdcard/" || sdcardLocation == "/sdcard") {
                                        val safe = it.trim().replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                                        if (safe.isNotEmpty()) {
                                            sdcardLocation = "/sdcard/$safe"
                                        }
                                    }
                                },
                                label = { Text("Folder Name (e.g. Work, Notes)") },
                                placeholder = { Text("Enter folder button name") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("new_folder_name_input")
                            )

                            OutlinedTextField(
                                value = sdcardLocation,
                                onValueChange = {
                                    sdcardLocation = it
                                    validationError = null
                                },
                                label = { Text("Location on /sdcard") },
                                placeholder = { Text("/sdcard/MyFolder") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = null
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("new_folder_location_input")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { showSdcardBrowser = true },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("browse_sdcard_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SdStorage,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Browse /sdcard")
                                }

                                OutlinedButton(
                                    onClick = { documentTreeLauncher.launch(null) },
                                    modifier = Modifier.testTag("system_folder_picker_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("System")
                                }
                            }

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
                                        val cleanPath = sdcardLocation.trim()
                                        if (cleanName.isEmpty()) {
                                            validationError = "Please enter a folder name."
                                        } else {
                                            val normalized = FolderRepository.normalizeSdcardPath(cleanPath, cleanName)
                                            saveOrUpdate(
                                                editingFolderId = editingFolderId,
                                                name = cleanName,
                                                path = normalized,
                                                treeUri = selectedTreeUri,
                                                onAddFolder = onAddFolder,
                                                onUpdateFolder = onUpdateFolder
                                            )
                                            folderName = ""
                                            sdcardLocation = "/sdcard/"
                                            selectedTreeUri = null
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
                            sdcardLocation = "/sdcard/"
                            selectedTreeUri = null
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
                        Text("Add Folder (Name & /sdcard Location)")
                    }
                }

                // Existing Folders List
                if (folders.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = "Configured Folders (${folders.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurfaceVariant
                    )

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        folders.forEach { folder ->
                            val isSelected = folder.id == selectedFolderId
                            val count = folderCounts[folder.id] ?: 0

                            OutlinedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectFolder(folder) },
                                border = BorderStroke(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) colorScheme.primary else colorScheme.outlineVariant
                                )
                            ) {
                                ListItem(
                                    headlineContent = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = folder.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
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
                                        Column {
                                            Text(
                                                text = folder.sdcardPath,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "$count saved item(s)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = null,
                                            tint = if (isSelected) colorScheme.primary else colorScheme.onSurfaceVariant
                                        )
                                    },
                                    trailingContent = {
                                        Row {
                                            IconButton(
                                                onClick = {
                                                    editingFolderId = folder.id
                                                    folderName = folder.name
                                                    sdcardLocation = folder.sdcardPath
                                                    selectedTreeUri = folder.treeUri
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
                                        containerColor = if (isSelected) {
                                            colorScheme.primaryContainer.copy(alpha = 0.25f)
                                        } else {
                                            colorScheme.surface
                                        }
                                    )
                                )
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
        SdcardFolderPickerDialog(
            initialSdcardPath = sdcardLocation,
            onListDirectories = onListSdcardDirs,
            onCreateDirectory = onCreateSdcardDir,
            onFolderPicked = { pickedSdcardPath ->
                sdcardLocation = pickedSdcardPath
                if (folderName.isBlank() && pickedSdcardPath != "/sdcard") {
                    folderName = pickedSdcardPath.substringAfterLast('/')
                }
                showSdcardBrowser = false
                validationError = null
            },
            onDismiss = { showSdcardBrowser = false }
        )
    }
}

private fun saveOrUpdate(
    editingFolderId: String?,
    name: String,
    path: String,
    treeUri: String?,
    onAddFolder: (String, String, String?) -> Unit,
    onUpdateFolder: (String, String, String, String?) -> Unit
) {
    if (editingFolderId != null) {
        onUpdateFolder(editingFolderId, name, path, treeUri)
    } else {
        onAddFolder(name, path, treeUri)
    }
}

@Composable
fun SdcardFolderPickerDialog(
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
                text = "Select Folder on /sdcard",
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
