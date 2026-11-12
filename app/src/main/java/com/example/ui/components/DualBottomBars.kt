package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CaptureState
import com.example.model.MediaFolder

@Composable
fun DualBottomBars(
    folders: List<MediaFolder>,
    selectedFolderId: String,
    folderItemCounts: Map<String, Int>,
    captureState: CaptureState,
    videoDurationSeconds: Int,
    onFolderSelected: (MediaFolder) -> Unit,
    onAddFolderClick: () -> Unit,
    onTakePhotoClick: () -> Unit,
    onRecordVideoClick: () -> Unit,
    onRecordAudioClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Pulse animation for recording indicators
    val transition = rememberInfiniteTransition(label = "pulse_recording")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color(0xCC090C10),
                        Color(0xF0090C10),
                        Color(0xFF090C10)
                    )
                )
            )
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Active recording status strip if video or audio is currently recording
        if (captureState == CaptureState.RECORDING_VIDEO) {
            val mins = videoDurationSeconds / 60
            val secs = videoDurationSeconds % 60
            Surface(
                color = Color(0xFFEF4444).copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFEF4444)),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = "Recording",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(12.dp).scale(pulseScale)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "REC VIDEO  " + String.format("%02d:%02d", mins, secs),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ==========================================
        // UPPER BOTTOM BAR: 3 Capture Action Buttons
        // (Take Photo, Record Video, Record Audio)
        // ==========================================
        Surface(
            color = Color(0xFF161B22).copy(alpha = 0.92f),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, Color(0xFF30363D)),
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. TAKE PHOTO BUTTON
                val isPhotoEnabled = captureState == CaptureState.IDLE
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = isPhotoEnabled) { onTakePhotoClick() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("photo_capture_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPhotoEnabled) Color(0xFF21262D) else Color(0xFF161B22)
                            )
                            .border(
                                2.dp,
                                if (isPhotoEnabled) Color(0xFF38BDF8) else Color(0xFF30363D),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Take Photo",
                            tint = if (isPhotoEnabled) Color(0xFF38BDF8) else Color(0xFF6E7681),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Photo",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPhotoEnabled) Color(0xFFF0F6FC) else Color(0xFF6E7681)
                    )
                }

                // 2. RECORD VIDEO BUTTON (Toggles Record / Stop)
                val isVideoRecording = captureState == CaptureState.RECORDING_VIDEO
                val isVideoEnabled = captureState == CaptureState.IDLE || isVideoRecording
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = isVideoEnabled) { onRecordVideoClick() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("video_record_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                if (isVideoRecording) Color(0xFFEF4444)
                                else if (isVideoEnabled) Color(0xFF21262D)
                                else Color(0xFF161B22)
                            )
                            .border(
                                2.dp,
                                if (isVideoRecording) Color.White
                                else if (isVideoEnabled) Color(0xFFEF4444)
                                else Color(0xFF30363D),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isVideoRecording) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Video Recording",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp).scale(pulseScale)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = "Record Video",
                                tint = if (isVideoEnabled) Color(0xFFEF4444) else Color(0xFF6E7681),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isVideoRecording) "Stop" else "Video",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isVideoRecording) Color(0xFFEF4444) else if (isVideoEnabled) Color(0xFFF0F6FC) else Color(0xFF6E7681)
                    )
                }

                // 3. RECORD AUDIO BUTTON (Toggles Record / Stop)
                val isAudioRecording = captureState == CaptureState.RECORDING_AUDIO
                val isAudioEnabled = captureState == CaptureState.IDLE || isAudioRecording
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = isAudioEnabled) { onRecordAudioClick() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("audio_record_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(
                                if (isAudioRecording) Color(0xFF10B981)
                                else if (isAudioEnabled) Color(0xFF21262D)
                                else Color(0xFF161B22)
                            )
                            .border(
                                2.dp,
                                if (isAudioRecording) Color.White
                                else if (isAudioEnabled) Color(0xFF10B981)
                                else Color(0xFF30363D),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isAudioRecording) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Audio Recording",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp).scale(pulseScale)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Record Audio",
                                tint = if (isAudioEnabled) Color(0xFF10B981) else Color(0xFF6E7681),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isAudioRecording) "Stop" else "Audio",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAudioRecording) Color(0xFF10B981) else if (isAudioEnabled) Color(0xFFF0F6FC) else Color(0xFF6E7681)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ==========================================
        // LOWER BOTTOM BAR: Scrollable Folder Buttons
        // (All the way at the bottom)
        // ==========================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(folders, key = { it.id }) { folder ->
                    val isSelected = folder.id == selectedFolderId
                    val count = folderItemCounts[folder.id] ?: 0
                    val folderColor = try {
                        Color(android.graphics.Color.parseColor(folder.colorHex))
                    } catch (e: Exception) {
                        Color(0xFF38BDF8)
                    }

                    Surface(
                        color = if (isSelected) folderColor.copy(alpha = 0.22f) else Color(0xFF161B22),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) folderColor else Color(0xFF30363D)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onFolderSelected(folder) }
                            .testTag("folder_item_button_${folder.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.Check else Icons.Default.Folder,
                                contentDescription = folder.name,
                                tint = folderColor,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = folder.name,
                                color = if (isSelected) Color.White else Color(0xFFC9D1D9),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (count > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) folderColor.copy(alpha = 0.4f)
                                            else Color(0xFF21262D)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "$count",
                                        color = if (isSelected) Color.White else Color(0xFF8B949E),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // Add Folder Button
                item {
                    Surface(
                        color = Color(0xFF21262D),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFF30363D)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onAddFolderClick() }
                            .testTag("add_folder_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Folder",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "New",
                                color = Color(0xFF38BDF8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
