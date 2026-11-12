package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.camera.CameraManager
import com.example.model.CaptureState
import com.example.model.MediaType
import com.example.model.RecorderMode
import com.example.ui.components.DualBottomBars
import com.example.ui.components.FolderSettingsDialog
import com.example.ui.components.GalleryBottomSheet
import com.example.ui.components.WaveformVisualizer
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderCamScreen(
    viewModel: FolderCamViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val colorScheme = MaterialTheme.colorScheme

    val folders by viewModel.folders.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    val recorderMode by viewModel.recorderMode.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val audioStatus by viewModel.audioStatus.collectAsState()
    val videoDuration by viewModel.videoDurationSeconds.collectAsState()
    val folderCounts by viewModel.folderCounts.collectAsState()
    val currentFolderMedia by viewModel.currentFolderMedia.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()
    val isGalleryOpen by viewModel.isGalleryOpen.collectAsState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[Manifest.permission.CAMERA] ?: hasCameraPermission
        hasAudioPermission = permissions[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission || !hasAudioPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    // HorizontalPager for Audio (0, default) -> Photo (1) -> Video (2)
    val pagerState = rememberPagerState(
        initialPage = RecorderMode.AUDIO.index,
        pageCount = { RecorderMode.entries.size }
    )

    // Sync pager swipe -> ViewModel recorderMode
    LaunchedEffect(pagerState.currentPage) {
        val swipedMode = RecorderMode.fromIndex(pagerState.currentPage)
        if (swipedMode != recorderMode) {
            viewModel.setRecorderMode(swipedMode)
        }
    }

    // Sync ViewModel recorderMode -> pager position
    LaunchedEffect(recorderMode) {
        if (pagerState.currentPage != recorderMode.index) {
            pagerState.animateScrollToPage(recorderMode.index)
        }
    }

    val cameraManager = remember { CameraManager(context) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2800)
            viewModel.clearToast()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.surface.copy(alpha = 0.9f))
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "FolderCam",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val activeFolder = selectedFolder
                            Text(
                                text = if (activeFolder != null) {
                                    "${activeFolder.name} (${activeFolder.sdcardPath})"
                                } else {
                                    "Specify at least 1 folder in Settings"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (activeFolder != null) {
                                    colorScheme.primary
                                } else {
                                    colorScheme.error
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        // Only show camera flash & flip when in Photo or Video mode
                        if (recorderMode != RecorderMode.AUDIO) {
                            FilledTonalIconButton(
                                onClick = {
                                    isFlashOn = !isFlashOn
                                    cameraManager.toggleTorch(isFlashOn)
                                },
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .testTag("flash_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                    contentDescription = "Toggle Flash"
                                )
                            }

                            FilledTonalIconButton(
                                onClick = {
                                    previewViewRef?.let { cameraManager.switchCamera(lifecycleOwner, it) }
                                },
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .testTag("switch_camera_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FlipCameraAndroid,
                                    contentDescription = "Switch Camera"
                                )
                            }
                        }

                        // Gallery Icon Button with M3 Badge
                        val mediaCount = currentFolderMedia.size
                        FilledTonalIconButton(
                            onClick = { viewModel.openGallery() },
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .testTag("gallery_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (mediaCount > 0) {
                                        Badge { Text("$mediaCount") }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = "Gallery"
                                )
                            }
                        }

                        // Folder Settings Icon Button
                        FilledTonalIconButton(
                            onClick = { viewModel.openSettings() },
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Folder Settings"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colorScheme.surface.copy(alpha = 0.85f)
                    )
                )

                // Material 3 Segmented Mode Indicator (Audio -> Photo -> Video)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 6.dp)
                        .testTag("mode_segmented_row")
                ) {
                    RecorderMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = recorderMode == mode,
                            onClick = {
                                if (captureState == CaptureState.IDLE) {
                                    viewModel.setRecorderMode(mode)
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = RecorderMode.entries.size
                            ),
                            icon = {
                                SegmentedButtonDefaults.Icon(active = recorderMode == mode) {
                                    Icon(
                                        imageVector = when (mode) {
                                            RecorderMode.AUDIO -> Icons.Default.Mic
                                            RecorderMode.PHOTO -> Icons.Default.CameraAlt
                                            RecorderMode.VIDEO -> Icons.Default.Videocam
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            label = {
                                Text(
                                    text = mode.title,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            modifier = Modifier.testTag("mode_tab_${mode.name.lowercase()}")
                        )
                    }
                }
            }
        },
        bottomBar = {
            DualBottomBars(
                folders = folders,
                selectedFolderId = selectedFolder?.id,
                folderItemCounts = folderCounts,
                currentMode = recorderMode,
                captureState = captureState,
                videoDurationSeconds = videoDuration,
                onModeSelected = { mode ->
                    viewModel.setRecorderMode(mode)
                },
                onFolderSelected = { folder ->
                    viewModel.selectFolder(folder)
                },
                onAddFolderClick = {
                    viewModel.openSettings()
                },
                onTakePhotoClick = {
                    val file = viewModel.prepareOutputFile(MediaType.PHOTO) ?: return@DualBottomBars
                    cameraManager.takePhoto(
                        outputFile = file,
                        onSuccess = { savedFile ->
                            viewModel.onPhotoCaptured(savedFile)
                        },
                        onError = { err ->
                            viewModel.showToast(err)
                        }
                    )
                },
                onRecordVideoClick = {
                    if (captureState == CaptureState.RECORDING_VIDEO) {
                        cameraManager.stopVideoRecording()
                    } else if (captureState == CaptureState.IDLE) {
                        val file = viewModel.prepareOutputFile(MediaType.VIDEO) ?: return@DualBottomBars
                        cameraManager.startVideoRecording(
                            outputFile = file,
                            enableAudio = hasAudioPermission,
                            onVideoStarted = {
                                viewModel.onVideoRecordingStarted()
                            },
                            onVideoSaved = { savedFile ->
                                viewModel.onVideoSaved(savedFile)
                            },
                            onError = { err ->
                                viewModel.onVideoError(err)
                            }
                        )
                    }
                },
                onRecordAudioClick = {
                    if (captureState == CaptureState.RECORDING_AUDIO) {
                        viewModel.stopAudioRecording()
                    } else if (captureState == CaptureState.IDLE) {
                        if (!hasAudioPermission) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                        } else {
                            viewModel.startAudioRecording()
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(colorScheme.background)
        ) {
            // Shared CameraX Viewfinder layer for Photo & Video modes
            if (recorderMode != RecorderMode.AUDIO && captureState != CaptureState.RECORDING_AUDIO) {
                if (hasCameraPermission) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                previewViewRef = this
                                cameraManager.initialize(lifecycleOwner, this)
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("camera_preview_view")
                    )
                } else {
                    CameraPermissionCard(
                        onRequestPermission = {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                            )
                        }
                    )
                }
            }

            // HorizontalPager enabling swipe left/right between:
            // Page 0: Audio (Blank viewfinder + live waveform, selected by default)
            // Page 1: Photo (Camera viewfinder + Photo HUD)
            // Page 2: Video (Camera viewfinder + Video HUD)
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = captureState == CaptureState.IDLE,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("mode_horizontal_pager")
            ) { pageIndex ->
                when (RecorderMode.fromIndex(pageIndex)) {
                    RecorderMode.AUDIO -> {
                        WaveformVisualizer(
                            isRecording = captureState == CaptureState.RECORDING_AUDIO,
                            durationSeconds = audioStatus.durationSeconds,
                            amplitudes = audioStatus.amplitudes,
                            currentDecibels = audioStatus.currentDecibels,
                            targetFolder = selectedFolder,
                            onOpenFolderSettings = { viewModel.openSettings() },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    RecorderMode.PHOTO -> {
                        CameraModeOverlay(
                            modeTitle = "PHOTO MODE",
                            subtitle = "Swipe left for Video mode or right for Audio mode",
                            targetFolder = selectedFolder,
                            onOpenFolderSettings = { viewModel.openSettings() }
                        )
                    }
                    RecorderMode.VIDEO -> {
                        CameraModeOverlay(
                            modeTitle = "VIDEO MODE",
                            subtitle = "Swipe right for Photo or Audio mode",
                            targetFolder = selectedFolder,
                            onOpenFolderSettings = { viewModel.openSettings() }
                        )
                    }
                }
            }

            // If no folders are configured yet, show a clear Material 3 onboarding card in the center as well
            if (folders.isEmpty() && !isSettingsOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ElevatedCard(
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("no_folders_empty_state_card")
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SdStorage,
                                contentDescription = null,
                                tint = colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "At Least 1 Folder Required",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "By default, no folders are configured. Please specify at least 1 folder (Name and /sdcard Location) where your recorded audio, photos, and videos will be stored.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { viewModel.openSettings() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Specify First Folder (/sdcard)")
                            }
                        }
                    }
                }
            }

            // Material 3 Toast / Snackbar Banner
            AnimatedVisibility(
                visible = toastMessage != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            ) {
                toastMessage?.let { msg ->
                    Surface(
                        color = colorScheme.inverseSurface,
                        contentColor = colorScheme.inverseOnSurface,
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }

    // Automatically show FolderSettingsDialog on first launch when folders.isEmpty() or when Settings is opened
    if (isSettingsOpen || folders.isEmpty()) {
        FolderSettingsDialog(
            folders = folders,
            selectedFolderId = selectedFolder?.id,
            folderCounts = folderCounts,
            onSelectFolder = { folder ->
                viewModel.selectFolder(folder)
            },
            onAddFolder = { name, sdcardPath, treeUri ->
                viewModel.addFolder(
                    name = name,
                    sdcardPath = sdcardPath,
                    treeUri = treeUri
                )
            },
            onUpdateFolder = { folderId, name, sdcardPath, treeUri ->
                viewModel.updateFolder(
                    folderId = folderId,
                    name = name,
                    sdcardPath = sdcardPath,
                    colorHex = "#38BDF8",
                    treeUri = treeUri
                )
            },
            onDeleteFolder = { folderId ->
                viewModel.deleteFolder(folderId)
            },
            onListSdcardDirs = { path ->
                viewModel.listSdcardDirectories(path)
            },
            onCreateSdcardDir = { parent, name ->
                viewModel.createSdcardDirectory(parent, name)
            },
            onResolveTreeUri = { uri ->
                viewModel.resolveSdcardPathFromTreeUri(uri)
            },
            onDismiss = {
                viewModel.closeSettings()
            }
        )
    }

    // Gallery Bottom Sheet
    if (isGalleryOpen) {
        GalleryBottomSheet(
            folders = folders,
            selectedFolder = selectedFolder,
            mediaItems = currentFolderMedia,
            onFolderSelected = { folder ->
                viewModel.selectFolder(folder)
            },
            onDeleteItem = { item ->
                viewModel.deleteMediaItem(item)
            },
            onDismiss = {
                viewModel.closeGallery()
            }
        )
    }
}

@Composable
private fun CameraModeOverlay(
    modeTitle: String,
    subtitle: String,
    targetFolder: com.example.model.MediaFolder?,
    onOpenFolderSettings: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Surface(
            color = colorScheme.surface.copy(alpha = 0.78f),
            contentColor = colorScheme.onSurface,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = modeTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Swipe,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                AssistChip(
                    onClick = onOpenFolderSettings,
                    label = {
                        Text(
                            text = if (targetFolder != null) {
                                "${targetFolder.name} -> ${targetFolder.sdcardPath}"
                            } else {
                                "Specify /sdcard folder"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CameraPermissionCard(
    onRequestPermission: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Camera & Microphone Access",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Grant camera and microphone permissions to capture photos, videos, and audio directly into your selected /sdcard folders.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onRequestPermission) {
                    Text("Grant Permissions")
                }
            }
        }
    }
}
