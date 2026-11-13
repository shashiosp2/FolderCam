package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.camera.CameraUiState
import com.example.camera.LensOption
import com.example.model.CaptureState
import com.example.model.MediaFolder
import com.example.model.PhotoCaptureFeedbackState
import com.example.model.RecorderMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DualBottomBars(
    folders: List<MediaFolder>,
    selectedFolderId: String?,
    folderItemCounts: Map<String, Int>,
    currentMode: RecorderMode,
    captureState: CaptureState,
    videoDurationSeconds: Int,
    cameraUiState: CameraUiState,
    photoFeedback: PhotoCaptureFeedbackState = PhotoCaptureFeedbackState(),
    onLensSelected: (LensOption) -> Unit,
    onModeSelected: (RecorderMode) -> Unit,
    onFolderSelected: (MediaFolder) -> Unit,
    onAddFolderClick: () -> Unit,
    onTakePhotoClick: () -> Unit,
    onStartPhotoBurst: () -> Unit = {},
    onStopPhotoBurst: () -> Unit = {},
    onRecordVideoClick: () -> Unit,
    onRecordAudioClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val transition = rememberInfiniteTransition(label = "pulse_recording")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Active video recording duration badge
        if (captureState == CaptureState.RECORDING_VIDEO) {
            val mins = videoDurationSeconds / 60
            val secs = videoDurationSeconds % 60
            Surface(
                color = colorScheme.errorContainer,
                contentColor = colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 4.dp,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = "Recording video",
                        tint = colorScheme.error,
                        modifier = Modifier
                            .size(14.dp)
                            .scale(pulseScale)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "REC VIDEO  " + String.format(Locale.US, "%02d:%02d", mins, secs),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // =========================================================================
        // MULTI-LENS SELECTOR (Ultrawide 0.5x -> Wide 1x -> Zoom 2x/3x/5x/10x)
        // + Default Resolution Badge (12MP for Photo, 4K 30fps for Video)
        // =========================================================================
        if (currentMode == RecorderMode.PHOTO || currentMode == RecorderMode.VIDEO) {
            Surface(
                color = colorScheme.surface.copy(alpha = 0.88f),
                contentColor = colorScheme.onSurface,
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 3.dp,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .testTag("lens_selector_bar")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    cameraUiState.lensOptions.forEach { lens ->
                        val isSelected = cameraUiState.selectedLensId == lens.id
                        FilterChip(
                            selected = isSelected,
                            onClick = { onLensSelected(lens) },
                            label = {
                                Text(
                                    text = if (isSelected) {
                                        String.format(Locale.US, "%.1fx", cameraUiState.currentZoomRatio)
                                            .removeSuffix(".0x") + (if (cameraUiState.currentZoomRatio % 1f == 0f) "x" else "")
                                    } else {
                                        lens.label
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colorScheme.primaryContainer,
                                selectedLabelColor = colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.testTag("lens_button_${lens.id}")
                        )
                    }

                    // Active Resolution Pill (12MP for Photo, 4K 30fps for Video)
                    Badge(
                        containerColor = colorScheme.secondaryContainer,
                        contentColor = colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(start = 4.dp)
                    ) {
                        Text(
                            text = if (currentMode == RecorderMode.PHOTO) {
                                cameraUiState.photoResolution
                            } else {
                                cameraUiState.videoResolution
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // =========================================================================
        // UPPER BOTTOM BAR (On top of the folder bar):
        // Ordered: 1. Record Audio (Default)  ->  2. Take Photo  ->  3. Record Video
        // Supports scrolling/swiping left & right to switch modes as well as tapping
        // =========================================================================
        Surface(
            color = colorScheme.surface.copy(alpha = 0.94f),
            contentColor = colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .pointerInput(currentMode, captureState) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccumulator = 0f },
                        onDragEnd = {
                            if (captureState == CaptureState.IDLE) {
                                if (dragAccumulator < -60f) {
                                    val nextIndex = (currentMode.index + 1).coerceAtMost(2)
                                    onModeSelected(RecorderMode.fromIndex(nextIndex))
                                } else if (dragAccumulator > 60f) {
                                    val prevIndex = (currentMode.index - 1).coerceAtLeast(0)
                                    onModeSelected(RecorderMode.fromIndex(prevIndex))
                                }
                            }
                            dragAccumulator = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            dragAccumulator += dragAmount
                        }
                    )
                }
                .testTag("upper_capture_bottom_bar")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. AUDIO RECORDING BUTTON (First & selected by default)
                val isAudioMode = currentMode == RecorderMode.AUDIO
                val isAudioRecording = captureState == CaptureState.RECORDING_AUDIO
                val isAudioEnabled = captureState == CaptureState.IDLE || isAudioRecording

                ModeCaptureButton(
                    label = if (isAudioRecording) "Stop Audio" else "Audio",
                    isSelectedMode = isAudioMode,
                    isActivelyRecording = isAudioRecording,
                    enabled = isAudioEnabled,
                    activeIcon = if (isAudioRecording) Icons.Default.Stop else Icons.Default.Mic,
                    inactiveIcon = Icons.Outlined.Mic,
                    pulseScale = pulseScale,
                    testTag = "audio_record_button",
                    onClick = {
                        if (!isAudioMode && captureState == CaptureState.IDLE) {
                            onModeSelected(RecorderMode.AUDIO)
                        } else {
                            onRecordAudioClick()
                        }
                    }
                )

                // 2. PHOTO CAPTURE BUTTON (Second — rapid taps + press-and-hold continuous burst)
                val isPhotoMode = currentMode == RecorderMode.PHOTO
                val isPhotoEnabled = captureState == CaptureState.IDLE

                PhotoShutterButton(
                    isSelectedMode = isPhotoMode,
                    isBurstActive = photoFeedback.isBurstActive,
                    burstShotCount = photoFeedback.triggerCounter,
                    enabled = isPhotoEnabled,
                    pulseScale = pulseScale,
                    onSelectMode = { onModeSelected(RecorderMode.PHOTO) },
                    onTapPhoto = onTakePhotoClick,
                    onStartBurst = onStartPhotoBurst,
                    onStopBurst = onStopPhotoBurst
                )

                // 3. VIDEO RECORDING BUTTON (Third — scroll left from Photo)
                val isVideoMode = currentMode == RecorderMode.VIDEO
                val isVideoRecording = captureState == CaptureState.RECORDING_VIDEO
                val isVideoEnabled = captureState == CaptureState.IDLE || isVideoRecording

                ModeCaptureButton(
                    label = if (isVideoRecording) "Stop Video" else "Video",
                    isSelectedMode = isVideoMode,
                    isActivelyRecording = isVideoRecording,
                    enabled = isVideoEnabled,
                    activeIcon = if (isVideoRecording) Icons.Default.Stop else Icons.Default.Videocam,
                    inactiveIcon = Icons.Outlined.Videocam,
                    pulseScale = pulseScale,
                    testTag = "video_record_button",
                    onClick = {
                        if (!isVideoMode && captureState == CaptureState.IDLE) {
                            onModeSelected(RecorderMode.VIDEO)
                        } else {
                            onRecordVideoClick()
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // =========================================================================
        // LOWER BOTTOM BAR (All the way at the bottom):
        // Scrollable Material 3 Folder Buttons
        // =========================================================================
        Surface(
            color = colorScheme.surface,
            contentColor = colorScheme.onSurface,
            tonalElevation = 2.dp,
            border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("lower_folder_bottom_bar")
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (folders.isEmpty()) {
                    item {
                        AssistChip(
                            onClick = onAddFolderClick,
                            label = {
                                Text(
                                    text = "Specify at least 1 folder (Name & /sdcard Location)",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Specify Folder",
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = colorScheme.errorContainer,
                                labelColor = colorScheme.onErrorContainer,
                                leadingIconContentColor = colorScheme.onErrorContainer
                            ),
                            modifier = Modifier.testTag("empty_folder_prompt_chip")
                        )
                    }
                } else {
                    items(folders, key = { it.id }) { folder ->
                        val isSelected = folder.id == selectedFolderId
                        val count = folderItemCounts[folder.id] ?: 0

                        ElevatedFilterChip(
                            selected = isSelected,
                            onClick = { onFolderSelected(folder) },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = folder.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (count > 0) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Badge(
                                            containerColor = if (isSelected) {
                                                colorScheme.primary
                                            } else {
                                                colorScheme.surfaceVariant
                                            },
                                            contentColor = if (isSelected) {
                                                colorScheme.onPrimary
                                            } else {
                                                colorScheme.onSurfaceVariant
                                            }
                                        ) {
                                            Text(text = "$count")
                                        }
                                    }
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Folder,
                                    contentDescription = folder.name,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            colors = FilterChipDefaults.elevatedFilterChipColors(
                                selectedContainerColor = colorScheme.primaryContainer,
                                selectedLabelColor = colorScheme.onPrimaryContainer,
                                selectedLeadingIconColor = colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.testTag("folder_item_button_${folder.id}")
                        )
                    }

                    item {
                        AssistChip(
                            onClick = onAddFolderClick,
                            label = {
                                Text(
                                    text = "Add Folder",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add Folder",
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            modifier = Modifier.testTag("add_folder_button")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCaptureButton(
    label: String,
    isSelectedMode: Boolean,
    isActivelyRecording: Boolean,
    enabled: Boolean,
    activeIcon: androidx.compose.ui.graphics.vector.ImageVector,
    inactiveIcon: androidx.compose.ui.graphics.vector.ImageVector,
    pulseScale: Float,
    testTag: String,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val buttonSize by animateDpAsState(
        targetValue = if (isSelectedMode) 56.dp else 48.dp,
        label = "mode_button_size"
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            isActivelyRecording -> colorScheme.error
            isSelectedMode -> colorScheme.primary
            else -> colorScheme.secondaryContainer
        },
        label = "mode_container_color"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isActivelyRecording -> colorScheme.onError
            isSelectedMode -> colorScheme.onPrimary
            else -> colorScheme.onSecondaryContainer
        },
        label = "mode_content_color"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        if (isSelectedMode || isActivelyRecording) {
            FilledIconButton(
                onClick = onClick,
                enabled = enabled,
                shape = if (isActivelyRecording) MaterialTheme.shapes.large else CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = containerColor,
                    contentColor = contentColor
                ),
                modifier = Modifier
                    .size(buttonSize)
                    .scale(if (isActivelyRecording) pulseScale else 1f)
                    .testTag(testTag)
            ) {
                Icon(
                    imageVector = activeIcon,
                    contentDescription = label,
                    modifier = Modifier.size(26.dp)
                )
            }
        } else {
            FilledTonalIconButton(
                onClick = onClick,
                enabled = enabled,
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = containerColor,
                    contentColor = contentColor
                ),
                modifier = Modifier
                    .size(buttonSize)
                    .testTag(testTag)
            ) {
                Icon(
                    imageVector = inactiveIcon,
                    contentDescription = label,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            color = if (isSelectedMode) colorScheme.primaryContainer else colorScheme.surface.copy(alpha = 0f),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelectedMode) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isActivelyRecording -> colorScheme.error
                    isSelectedMode -> colorScheme.onPrimaryContainer
                    else -> colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun PhotoShutterButton(
    isSelectedMode: Boolean,
    isBurstActive: Boolean,
    burstShotCount: Int,
    enabled: Boolean,
    pulseScale: Float,
    onSelectMode: () -> Unit,
    onTapPhoto: () -> Unit,
    onStartBurst: () -> Unit,
    onStopBurst: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    var isPressed by remember { mutableStateOf(false) }

    val buttonSize by animateDpAsState(
        targetValue = if (isSelectedMode) 56.dp else 48.dp,
        label = "photo_button_size"
    )

    val recoilScale by animateFloatAsState(
        targetValue = when {
            isBurstActive -> 0.90f * pulseScale
            isPressed -> 0.88f
            else -> 1.0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "photo_shutter_recoil"
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            isBurstActive -> colorScheme.tertiary
            isSelectedMode -> colorScheme.primary
            else -> colorScheme.secondaryContainer
        },
        label = "photo_container_color"
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isBurstActive -> colorScheme.onTertiary
            isSelectedMode -> colorScheme.onPrimary
            else -> colorScheme.onSecondaryContainer
        },
        label = "photo_content_color"
    )

    val label = when {
        isBurstActive && burstShotCount > 0 -> "Burst $burstShotCount"
        isBurstActive -> "Burst"
        else -> "Photo"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        if (isSelectedMode) {
            Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = CircleShape,
                tonalElevation = if (isBurstActive) 6.dp else 2.dp,
                border = if (isBurstActive) {
                    BorderStroke(2.dp, colorScheme.onTertiary)
                } else null,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .size(buttonSize)
                    .scale(recoilScale)
                    .semantics {
                        role = Role.Button
                        onClick(label = "Take Photo") {
                            if (enabled) onTapPhoto()
                            true
                        }
                    }
                    .pointerInput(enabled) {
                        coroutineScope {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!enabled) return@awaitEachGesture
                                isPressed = true
                                var burstStarted = false
                                var cancelledOrDragged = false
                                val downPosition = down.position
                                val touchSlop = viewConfiguration.touchSlop

                                val burstTimerJob = launch {
                                    delay(240L)
                                    if (!cancelledOrDragged) {
                                        burstStarted = true
                                        onStartBurst()
                                    }
                                }

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) {
                                        break
                                    }
                                    val dragDistance = (change.position - downPosition).getDistance()
                                    if (!burstStarted && dragDistance > touchSlop) {
                                        cancelledOrDragged = true
                                        burstTimerJob.cancel()
                                        break
                                    }
                                }

                                burstTimerJob.cancel()
                                isPressed = false
                                if (burstStarted) {
                                    onStopBurst()
                                } else if (!cancelledOrDragged) {
                                    onTapPhoto()
                                }
                            }
                        }
                    }
                    .testTag("photo_capture_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isBurstActive) Icons.Default.BurstMode else Icons.Default.CameraAlt,
                        contentDescription = if (isBurstActive) "Burst capturing" else "Take Photo (hold for burst)",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        } else {
            FilledTonalIconButton(
                onClick = onSelectMode,
                enabled = enabled,
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = containerColor,
                    contentColor = contentColor
                ),
                modifier = Modifier
                    .size(buttonSize)
                    .testTag("photo_capture_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.CameraAlt,
                    contentDescription = "Photo",
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            color = when {
                isBurstActive -> colorScheme.tertiaryContainer
                isSelectedMode -> colorScheme.primaryContainer
                else -> colorScheme.surface.copy(alpha = 0f)
            },
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelectedMode) FontWeight.Bold else FontWeight.Medium,
                color = when {
                    isBurstActive -> colorScheme.onTertiaryContainer
                    isSelectedMode -> colorScheme.onPrimaryContainer
                    else -> colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}
