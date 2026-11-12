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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.camera.CameraManager
import com.example.model.CaptureState
import com.example.model.MediaType
import com.example.ui.components.DualBottomBars
import com.example.ui.components.FolderSettingsDialog
import com.example.ui.components.GalleryBottomSheet
import com.example.ui.components.WaveformVisualizer
import kotlinx.coroutines.delay

@Composable
fun FolderCamScreen(
    viewModel: FolderCamViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val folders by viewModel.folders.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
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
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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

    // Camera manager
    val cameraManager = remember { CameraManager(context) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }

    // Auto-dismiss toast
    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2800)
            viewModel.clearToast()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090C10))
    ) {
        // ==========================================
        // MAIN VIEWFINDER OR BLANK AUDIO WAVEFORM
        // ==========================================
        if (captureState == CaptureState.RECORDING_AUDIO) {
            // When audio recording starts, viewfinder goes blank and shows waveform
            WaveformVisualizer(
                durationSeconds = audioStatus.durationSeconds,
                amplitudes = audioStatus.amplitudes,
                currentDecibels = audioStatus.currentDecibels,
                targetFolder = selectedFolder,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Normal Camera Viewfinder
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            previewViewRef = this
                            cameraManager.initialize(lifecycleOwner, this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Permission placeholder
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF10141B)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Camera Permission Required",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Please grant camera and microphone access to record photos, video, and audio directly to your folders.",
                            color = Color(0xFF8B949E),
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                        ) {
                            Text("Grant Permissions", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ==========================================
        // TOP HUD BAR: Brand, Active Folder, Quick Controls
        // ==========================================
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xCC090C10),
                            Color(0x80090C10),
                            Color.Transparent
                        )
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App Branding + Active Destination Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { viewModel.openSettings() }
                        .padding(end = 8.dp)
                ) {
                    val folderColor = try {
                        Color(android.graphics.Color.parseColor(selectedFolder.colorHex))
                    } catch (e: Exception) {
                        Color(0xFF38BDF8)
                    }

                    Surface(
                        color = Color(0xCC161B22),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, folderColor.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(folderColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = selectedFolder.name,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Camera & Utility Action Icons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Flash Toggle
                    IconButton(
                        onClick = {
                            isFlashOn = !isFlashOn
                            cameraManager.toggleTorch(isFlashOn)
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x80161B22))
                            .testTag("flash_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Toggle Flash",
                            tint = if (isFlashOn) Color(0xFFF59E0B) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Camera Switch (Front/Back)
                    IconButton(
                        onClick = {
                            previewViewRef?.let { cameraManager.switchCamera(lifecycleOwner, it) }
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x80161B22))
                            .testTag("switch_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Switch Camera",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Gallery Sheet Icon
                    val mediaCount = currentFolderMedia.size
                    IconButton(
                        onClick = { viewModel.openGallery() },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x80161B22))
                            .testTag("gallery_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "Gallery",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                            if (mediaCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                            }
                        }
                    }

                    // Settings Icon
                    IconButton(
                        onClick = { viewModel.openSettings() },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x80161B22))
                            .testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Folder Settings",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // ==========================================
        // DUAL BOTTOM BARS (Captured Actions + Folders)
        // ==========================================
        DualBottomBars(
            folders = folders,
            selectedFolderId = selectedFolder.id,
            folderItemCounts = folderCounts,
            captureState = captureState,
            videoDurationSeconds = videoDuration,
            onFolderSelected = { folder ->
                viewModel.selectFolder(folder)
            },
            onAddFolderClick = {
                viewModel.openSettings()
            },
            onTakePhotoClick = {
                val file = viewModel.prepareOutputFile(MediaType.PHOTO)
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
                    val file = viewModel.prepareOutputFile(MediaType.VIDEO)
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
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Toast Feedback Popup
        AnimatedVisibility(
            visible = toastMessage != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp)
        ) {
            toastMessage?.let { msg ->
                Surface(
                    color = Color(0xF0161B22),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = msg,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }

        // Folder Settings Dialog
        if (isSettingsOpen) {
            FolderSettingsDialog(
                folders = folders,
                selectedFolderId = selectedFolder.id,
                folderCounts = folderCounts,
                onSelectFolder = { folder ->
                    viewModel.selectFolder(folder)
                },
                onAddFolder = { name, colorHex ->
                    viewModel.addFolder(name, colorHex)
                },
                onDeleteFolder = { folderId ->
                    viewModel.deleteFolder(folderId)
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
}
