package com.example.ui.components

import android.view.Surface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.CaptureOrientation

@Composable
fun OrientationTopBar(
    selectedOrientation: CaptureOrientation,
    detectedAutoRotation: Int,
    isLocked: Boolean,
    onSelectOrientation: (CaptureOrientation) -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current

    Surface(
        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        contentColor = colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .alpha(if (isLocked) 0.72f else 1f)
            .testTag("orientation_top_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            CaptureOrientation.entries.forEach { option ->
                val isSelected = option == selectedOrientation
                val containerColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        colorScheme.primaryContainer
                    } else {
                        colorScheme.surface.copy(alpha = 0f)
                    },
                    label = "orientation_chip_bg_${option.storageKey}"
                )
                val contentColor by animateColorAsState(
                    targetValue = if (isSelected) {
                        colorScheme.onPrimaryContainer
                    } else {
                        colorScheme.onSurfaceVariant
                    },
                    label = "orientation_chip_fg_${option.storageKey}"
                )

                val targetIconAngle = when (option) {
                    CaptureOrientation.AUTO -> when (detectedAutoRotation) {
                        Surface.ROTATION_90 -> -90f
                        Surface.ROTATION_180 -> 180f
                        Surface.ROTATION_270 -> 90f
                        else -> 0f
                    }
                    CaptureOrientation.PORTRAIT -> 0f
                    CaptureOrientation.LANDSCAPE_LEFT -> -90f
                    CaptureOrientation.PORTRAIT_UPSIDE_DOWN -> 180f
                    CaptureOrientation.LANDSCAPE_RIGHT -> 90f
                }
                val iconAngle by animateFloatAsState(
                    targetValue = targetIconAngle,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "orientation_icon_angle_${option.storageKey}"
                )

                val displayLabel = if (option == CaptureOrientation.AUTO && isSelected) {
                    val autoDir = when (detectedAutoRotation) {
                        Surface.ROTATION_90 -> "L"
                        Surface.ROTATION_180 -> "F"
                        Surface.ROTATION_270 -> "R"
                        else -> "P"
                    }
                    "Auto·$autoDir"
                } else {
                    option.shortLabel
                }

                val interactionSource = remember(option) { MutableInteractionSource() }
                Surface(
                    color = containerColor,
                    contentColor = contentColor,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .heightIn(min = 48.dp)
                        .clip(MaterialTheme.shapes.large)
                        .semantics {
                            selected = isSelected
                        }
                        .clickable(
                            enabled = !isLocked,
                            role = Role.RadioButton,
                            interactionSource = interactionSource,
                            indication = ripple(bounded = true)
                        ) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectOrientation(option)
                        }
                        .testTag("orientation_option_${option.storageKey.lowercase()}")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (option == CaptureOrientation.AUTO) {
                                Icons.Default.ScreenRotation
                            } else {
                                Icons.Default.Smartphone
                            },
                            contentDescription = option.contentDescription,
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer {
                                    if (option != CaptureOrientation.AUTO) {
                                        rotationZ = iconAngle
                                    }
                                }
                        )
                        Text(
                            text = displayLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }

            if (isLocked) {
                Box(
                    modifier = Modifier.padding(end = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Orientation locked during video recording",
                        tint = colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
