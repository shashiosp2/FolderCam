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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MediaFolder
import java.util.Locale

@Composable
fun WaveformVisualizer(
    durationSeconds: Int,
    amplitudes: List<Float>,
    currentDecibels: Float,
    targetFolder: MediaFolder,
    modifier: Modifier = Modifier
) {
    // Pulse animation for recording badge
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.3f,
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

    // Viewfinder goes completely blank (deep studio black) as requested
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090C10))
            .testTag("audio_waveform_view"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Status Pill
            Surface(
                color = Color(0xFF161B22),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.FiberManualRecord,
                        contentDescription = "Recording indicator",
                        tint = Color(0xFFEF4444).copy(alpha = pulseAlpha),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "RECORDING AUDIO",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF21262D))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${currentDecibels.toInt()} dB",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Center Area: Large Timer + Live Dancing Waveform Canvas
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = formattedTime,
                    color = Color.White,
                    fontSize = 52.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "High Fidelity AAC 44.1kHz",
                    color = Color(0xFF8B949E),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(36.dp))

                // Canvas Dynamic Waveform Visualizer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF10141B))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val canvasWidth = size.width
                        val canvasHeight = size.height
                        val centerY = canvasHeight / 2f

                        // Baseline center guide line
                        drawLine(
                            color = Color(0xFF1E2530),
                            start = Offset(0f, centerY),
                            end = Offset(canvasWidth, centerY),
                            strokeWidth = 1.dp.toPx()
                        )

                        val barCount = 36
                        val barSpacing = 4.dp.toPx()
                        val totalSpacing = barSpacing * (barCount - 1)
                        val barWidth = ((canvasWidth - totalSpacing) / barCount).coerceAtLeast(2.dp.toPx())

                        // Take latest amplitudes or pad with idle values
                        val displayAmps = if (amplitudes.isEmpty()) {
                            List(barCount) { 0.08f }
                        } else {
                            val recent = amplitudes.takeLast(barCount)
                            if (recent.size < barCount) {
                                List(barCount - recent.size) { 0.08f } + recent
                            } else {
                                recent
                            }
                        }

                        for (i in 0 until barCount) {
                            val amp = displayAmps.getOrElse(i) { 0.08f }.coerceIn(0.06f, 1.0f)
                            val x = i * (barWidth + barSpacing)
                            val barHeight = (amp * (canvasHeight * 0.85f)).coerceAtLeast(6.dp.toPx())
                            val top = centerY - (barHeight / 2f)

                            val gradientBrush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8),
                                    Color(0xFF10B981)
                                ),
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

            // Bottom Destination Indicator
            Surface(
                color = Color(0xFF161B22),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Target Folder",
                        tint = try {
                            Color(android.graphics.Color.parseColor(targetFolder.colorHex))
                        } catch (e: Exception) {
                            Color(0xFF38BDF8)
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Saving directly to: ",
                        color = Color(0xFF8B949E),
                        fontSize = 13.sp
                    )
                    Text(
                        text = targetFolder.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
