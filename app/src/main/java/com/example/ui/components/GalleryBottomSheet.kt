package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Badge
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    selectedFolder: MediaFolder?,
    mediaItems: List<MediaItem>,
    onFolderSelected: (MediaFolder) -> Unit,
    onDeleteItem: (MediaItem) -> Unit,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
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
        containerColor = colorScheme.surface,
        contentColor = colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header: Title and /sdcard folder indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Captured Media",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (selectedFolder != null) {
                            "${selectedFolder.name} • ${selectedFolder.sdcardPath} (${filteredItems.size} items)"
                        } else {
                            "No folder selected"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Folder quick-filter strip (Material 3 ElevatedFilterChip)
            if (folders.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(folders, key = { it.id }) { folder ->
                        val isSelected = folder.id == selectedFolder?.id
                        ElevatedFilterChip(
                            selected = isSelected,
                            onClick = { onFolderSelected(folder) },
                            label = {
                                Text(
                                    text = folder.name,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Media Type Filter Chips (Ordered Audio, Photo, Video)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedMediaTypeFilter == null,
                    onClick = { selectedMediaTypeFilter = null },
                    label = { Text("All (${mediaItems.size})") }
                )

                FilterChip(
                    selected = selectedMediaTypeFilter == MediaType.AUDIO,
                    onClick = {
                        selectedMediaTypeFilter =
                            if (selectedMediaTypeFilter == MediaType.AUDIO) null else MediaType.AUDIO
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Audiotrack,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("Audio") }
                )

                FilterChip(
                    selected = selectedMediaTypeFilter == MediaType.PHOTO,
                    onClick = {
                        selectedMediaTypeFilter =
                            if (selectedMediaTypeFilter == MediaType.PHOTO) null else MediaType.PHOTO
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("Photos") }
                )

                FilterChip(
                    selected = selectedMediaTypeFilter == MediaType.VIDEO,
                    onClick = {
                        selectedMediaTypeFilter =
                            if (selectedMediaTypeFilter == MediaType.VIDEO) null else MediaType.VIDEO
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("Videos") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Media Grid
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
                            tint = colorScheme.outlineVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No media files in this folder yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = selectedFolder?.sdcardPath ?: "Specify a folder in Settings first",
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant
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
                            }
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
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colorScheme.surface
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(photoItem.file)
                            .crossfade(true)
                            .build(),
                        contentDescription = photoItem.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 32.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(onClick = { viewingPhotoItem = null }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close"
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalIconButton(
                                onClick = { shareMediaItem(context, photoItem) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share"
                                )
                            }

                            FilledTonalIconButton(
                                onClick = {
                                    onDeleteItem(photoItem)
                                    viewingPhotoItem = null
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = colorScheme.error
                                )
                            }
                        }
                    }

                    Surface(
                        color = colorScheme.surface.copy(alpha = 0.9f),
                        tonalElevation = 3.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = photoItem.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${photoItem.folderName} • ${formatDate(photoItem.timestamp)} • ${formatSize(photoItem.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
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
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable { onClick() }
            .testTag("media_item_${item.id}"),
        shape = MaterialTheme.shapes.medium
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Video",
                            tint = colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                MediaType.AUDIO -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Audio",
                                tint = colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Audio",
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onTertiaryContainer,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Badge(
                containerColor = colorScheme.surface.copy(alpha = 0.85f),
                contentColor = colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            ) {
                Text(
                    text = when (item.type) {
                        MediaType.PHOTO -> "PHOTO"
                        MediaType.VIDEO -> "VIDEO"
                        MediaType.AUDIO -> "AUDIO"
                    },
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }

            Surface(
                color = colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
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
    val colorScheme = MaterialTheme.colorScheme
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
            shape = MaterialTheme.shapes.extraLarge,
            color = colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, colorScheme.outlineVariant),
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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = colorScheme.tertiaryContainer,
                    contentColor = colorScheme.onTertiaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = item.folderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(16.dp))

                Slider(
                    value = currentPositionMs.coerceIn(0f, totalDurationMs),
                    onValueChange = { pos ->
                        currentPositionMs = pos
                        mediaPlayer.seekTo(pos.toInt())
                    },
                    valueRange = 0f..totalDurationMs,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatMs(currentPositionMs.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatMs(totalDurationMs.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { shareMediaItem(context, item) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share"
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            if (isPlaying) {
                                mediaPlayer.pause()
                                isPlaying = false
                            } else {
                                mediaPlayer.start()
                                isPlaying = true
                            }
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onDelete
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = colorScheme.error
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
