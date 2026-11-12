package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.SwipeLeft
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.MediaFolder
import java.util.Locale

@Composable
fun WaveformVisualizer(
    isRecording: Boolean,
    durationSeconds: Int,
    amplitudes: List<Float>,
    currentDecibels: Float,
    targetFolder: MediaFolder?,
    onOpenFolderSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val mins = durationSeconds / 60
    val secs = durationSeconds % 60
    val formattedTime = String.format(Locale.US, "%02d:%02d", mins, secs)

    // Blank viewfinder surface with Material 3 styling and live waveform
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("audio_waveform_view"),
        color = colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Status Pill (Material 3 Tonal Surface)
            Surface(
                color = if (isRecording) colorScheme.errorContainer else colorScheme.secondaryContainer,
                contentColor = if (isRecording) colorScheme.onErrorContainer else colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.FiberManualRecord else Icons.Default.Mic,
                        contentDescription = if (isRecording) "Recording audio" else "Audio mode ready",
                        tint = if (isRecording) {
                            colorScheme.error.copy(alpha = pulseAlpha)
                        } else {
                            colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRecording) "RECORDING AUDIO" else "AUDIO RECORDER READY",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (isRecording) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colorScheme.surface.copy(alpha = 0.35f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${currentDecibels.toInt()} dB",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Digital Timer Readout
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light
                ),
                color = if (isRecording) colorScheme.primary else colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isRecording) {
                    "Capturing live microphone waveform (AAC 44.1 kHz)"
                } else {
                    "Tap the Audio Record button below to start recording"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Material 3 Elevated Card for Waveform Canvas
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(156.dp),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val primaryColor = colorScheme.primary
                    val tertiaryColor = colorScheme.tertiary
                    val outlineVariantColor = colorScheme.outlineVariant

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height
                        val centerY = canvasHeight / 2f

                        // Center reference baseline
                        drawLine(
                            color = outlineVariantColor,
                            start = Offset(0f, centerY),
                            end = Offset(canvasWidth, centerY),
                            strokeWidth = 1.dp.toPx()
                        )

                        val barCount = 36
                        val barSpacing = 4.dp.toPx()
                        val totalSpacing = barSpacing * (barCount - 1)
                        val barWidth = ((canvasWidth - totalSpacing) / barCount).coerceAtLeast(2.dp.toPx())

                        val displayAmps = if (!isRecording || amplitudes.isEmpty()) {
                            List(barCount) { index ->
                                // Subtle idle wave pattern when waiting to record
                                val wave = kotlin.math.sin(index * 0.4f) * 0.04f + 0.08f
                                wave.coerceIn(0.05f, 0.14f)
                            }
                        } else {
                            val recent = amplitudes.takeLast(barCount)
                            if (recent.size < barCount) {
                                List(barCount - recent.size) { 0.06f } + recent
                            } else {
                                recent
                            }
                        }

                        for (i in 0 until barCount) {
                            val amp = displayAmps.getOrElse(i) { 0.08f }.coerceIn(0.05f, 1.0f)
                            val x = i * (barWidth + barSpacing)
                            val barHeight = (amp * (canvasHeight * 0.86f)).coerceAtLeast(6.dp.toPx())
                            val top = centerY - (barHeight / 2f)

                            val gradientBrush = Brush.verticalGradient(
                                colors = if (isRecording) {
                                    listOf(primaryColor, tertiaryColor)
                                } else {
                                    listOf(
                                        primaryColor.copy(alpha = 0.45f),
                                        tertiaryColor.copy(alpha = 0.45f)
                                    )
                                },
                                startY = top,
                                endY = top + barHeight
                            )

                            drawRoundRect(
                                brush = gradientBrush,
                                topLeft = Offset(x, top),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Destination Folder Chip (M3 AssistChip)
            AssistChip(
                onClick = onOpenFolderSettings,
                label = {
                    if (targetFolder != null) {
                        Text(
                            text = "${targetFolder.name} • ${targetFolder.sdcardPath}",
                            style = MaterialTheme.typography.labelLarge
                        )
                    } else {
                        Text(
                            text = "No folder specified — Tap to set /sdcard folder",
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.error
                        )
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = if (targetFolder != null) colorScheme.primary else colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            )

            if (!isRecording) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwipeLeft,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Swipe left for Photo mode, then Video mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
