package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.MediaFolder
import com.example.model.MediaItem
import com.example.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryBottomSheet(
    folders: List<MediaFolder>,
    selectedFolder: MediaFolder,
    mediaItems: List<MediaItem>,
    onFolderSelected: (MediaFolder) -> Unit,
    onDeleteItem: (MediaItem) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedMediaTypeFilter by remember { mutableStateOf<MediaType?>(null) }
    var viewingPhotoItem by remember { mutableStateOf<MediaItem?>(null) }
    var activeAudioItem by remember { mutableStateOf<MediaItem?>(null) }
    val context = LocalContext.current

    val filteredItems = remember(mediaItems, selectedMediaTypeFilter) {
        if (selectedMediaTypeFilter == null) {
            mediaItems
        } else {
            mediaItems.filter { it.type == selectedMediaTypeFilter }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF161B22),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 36.dp, height = 4.dp),
                shape = CircleShape,
                color = Color(0xFF30363D)
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header: Title and folder indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Captured Media",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Folder: ${selectedFolder.name} (${filteredItems.size} items)",
                        color = Color(0xFF8B949E),
                        fontSize = 13.sp
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF8B949E)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Folder quick-filter strip
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(folders) { folder ->
                    val isSelected = folder.id == selectedFolder.id
                    val folderColor = try {
                        Color(android.graphics.Color.parseColor(folder.colorHex))
                    } catch (e: Exception) {
                        Color(0xFF38BDF8)
                    }

                    Surface(
                        color = if (isSelected) folderColor.copy(alpha = 0.2f) else Color(0xFF21262D),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) folderColor else Color(0xFF30363D)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onFolderSelected(folder) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = folderColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = folder.name,
                                color = if (isSelected) Color.White else Color(0xFFC9D1D9),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Media Type Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedMediaTypeFilter == null,
                    onClick = { selectedMediaTypeFilter = null },
                    label = { Text("All (${mediaItems.size})", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF38BDF8),
                        selectedLabelColor = Color.Black,
                        containerColor = Color(0xFF21262D),
                        labelColor = Color(0xFF8B949E)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF30363D))
                )

                FilterChip(
                    selected = selectedMediaTypeFilter == MediaType.PHOTO,
                    onClick = {
                        selectedMediaTypeFilter = if (selectedMediaTypeFilter == MediaType.PHOTO) null else MediaType.PHOTO
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = { Text("Photos", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF38BDF8),
                        selectedLabelColor = Color.Black,
                        containerColor = Color(0xFF21262D),
                        labelColor = Color(0xFF8B949E)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF30363D))
                )

                FilterChip(
                    selected = selectedMediaTypeFilter == MediaType.VIDEO,
                    onClick = {
                        selectedMediaTypeFilter = if (selectedMediaTypeFilter == MediaType.VIDEO) null else MediaType.VIDEO
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = { Text("Videos", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFEF4444),
                        selectedLabelColor = Color.White,
                        containerColor = Color(0xFF21262D),
                        labelColor = Color(0xFF8B949E)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF30363D))
                )

                FilterChip(
                    selected = selectedMediaTypeFilter == MediaType.AUDIO,
                    onClick = {
                        selectedMediaTypeFilter = if (selectedMediaTypeFilter == MediaType.AUDIO) null else MediaType.AUDIO
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Audiotrack,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    label = { Text("Audio", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF10B981),
                        selectedLabelColor = Color.Black,
                        containerColor = Color(0xFF21262D),
                        labelColor = Color(0xFF8B949E)
                    ),
                    border = BorderStroke(1.dp, Color(0xFF30363D))
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Media Grid / List
            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = Color(0xFF30363D),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No media files in this folder yet",
                            color = Color(0xFF8B949E),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Use the capture buttons to take photos, videos, or audio",
                            color = Color(0xFF6E7681),
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredItems, key = { it.id }) { item ->
                        MediaItemCard(
                            item = item,
                            onClick = {
                                when (item.type) {
                                    MediaType.PHOTO -> viewingPhotoItem = item
                                    MediaType.AUDIO -> activeAudioItem = item
                                    MediaType.VIDEO -> openMediaWithIntent(context, item)
                                }
                            },
                            onDelete = { onDeleteItem(item) }
                        )
                    }
                }
            }
        }
    }

    // Full Photo Viewer Dialog
    viewingPhotoItem?.let { photoItem ->
        Dialog(
            onDismissRequest = { viewingPhotoItem = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(photoItem.file)
                        .crossfade(true)
                        .build(),
                    contentDescription = photoItem.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                // Top bar with close and share
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewingPhotoItem = null },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0x80000000))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }

                    Row {
                        IconButton(
                            onClick = { shareMediaItem(context, photoItem) },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0x80000000))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share",
                                tint = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                onDeleteItem(photoItem)
                                viewingPhotoItem = null
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0x80000000))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = Color(0xFFEF4444)
                            )
                        }
                    }
                }

                // Bottom File Info
                Surface(
                    color = Color(0xCC161B22),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = photoItem.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Saved in ${photoItem.folderName} • ${formatDate(photoItem.timestamp)} • ${formatSize(photoItem.sizeBytes)}",
                            color = Color(0xFF8B949E),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }

    // Audio Playback Player Dialog
    activeAudioItem?.let { audioItem ->
        AudioPlayerDialog(
            item = audioItem,
            onDismiss = { activeAudioItem = null },
            onDelete = {
                onDeleteItem(audioItem)
                activeAudioItem = null
            }
        )
    }
}

@Composable
fun MediaItemCard(
    item: MediaItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .testTag("media_item_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D)),
        border = BorderStroke(1.dp, Color(0xFF30363D))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (item.type) {
                MediaType.PHOTO -> {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(item.file)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                MediaType.VIDEO -> {
                    // Video thumbnail / indicator
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1E2530)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Video",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                MediaType.AUDIO -> {
                    // Audio waveform card
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF102A24)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Audio",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Audio Note",
                                color = Color(0xFF34D399),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Top Type Badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = when (item.type) {
                        MediaType.PHOTO -> "PHOTO"
                        MediaType.VIDEO -> "VIDEO"
                        MediaType.AUDIO -> "AUDIO"
                    },
                    color = when (item.type) {
                        MediaType.PHOTO -> Color(0xFF38BDF8)
                        MediaType.VIDEO -> Color(0xFFEF4444)
                        MediaType.AUDIO -> Color(0xFF10B981)
                    },
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Bottom Name Strip
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC161B22))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun AudioPlayerDialog(
    item: MediaItem,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableFloatStateOf(0f) }
    var totalDurationMs by remember { mutableFloatStateOf(1f) }
    val context = LocalContext.current

    val mediaPlayer = remember {
        MediaPlayer().apply {
            try {
                setDataSource(item.file.absolutePath)
                prepare()
                totalDurationMs = duration.toFloat().coerceAtLeast(1f)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    DisposableEffect(Unit) {
        mediaPlayer.setOnCompletionListener {
            isPlaying = false
            currentPositionMs = 0f
        }
        onDispose {
            try {
                if (mediaPlayer.isPlaying) mediaPlayer.stop()
                mediaPlayer.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying && isActive) {
            currentPositionMs = mediaPlayer.currentPosition.toFloat()
            delay(100)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF161B22),
            border = BorderStroke(1.dp, Color(0xFF30363D)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Audio Playback",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF8B949E)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF102A24))
                        .border(2.dp, Color(0xFF10B981), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = item.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = "Saved in ${item.folderName}",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Progress Slider
                Slider(
                    value = currentPositionMs.coerceIn(0f, totalDurationMs),
                    onValueChange = { pos ->
                        currentPositionMs = pos
                        mediaPlayer.seekTo(pos.toInt())
                    },
                    valueRange = 0f..totalDurationMs,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF10B981),
                        activeTrackColor = Color(0xFF10B981),
                        inactiveTrackColor = Color(0xFF30363D)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatMs(currentPositionMs.toLong()),
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = formatMs(totalDurationMs.toLong()),
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Play / Pause Button
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { shareMediaItem(context, item) },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF21262D))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color(0xFF38BDF8)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981))
                            .clickable {
                                if (isPlaying) {
                                    mediaPlayer.pause()
                                    isPlaying = false
                                } else {
                                    mediaPlayer.start()
                                    isPlaying = true
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF21262D))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = Color(0xFFEF4444)
                        )
                    }
                }
            }
        }
    }
}

private fun openMediaWithIntent(context: Context, item: MediaItem) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, if (item.type == MediaType.VIDEO) "video/*" else "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback
    }
}

private fun shareMediaItem(context: Context, item: MediaItem) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = when (item.type) {
                MediaType.PHOTO -> "image/*"
                MediaType.VIDEO -> "video/*"
                MediaType.AUDIO -> "audio/*"
            }
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Media"))
    } catch (e: Exception) {
        // Fallback
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val mins = totalSec / 60
    val secs = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
