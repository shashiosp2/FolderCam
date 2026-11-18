package com.example.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.R
import com.example.model.CaptureState
import com.example.model.VideoLensPreference
import com.example.service.RecordingCoordinator
import com.example.service.RecordingForegroundService

class FolderCamQuickWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val coordinator = RecordingCoordinator.getInstance(context.applicationContext)
        provideContent {
            val captureState by coordinator.captureState.collectAsState()
            val videoLens by coordinator.folderRepository.videoLensPreference.collectAsState()
            val isDndActive by coordinator.isDndActive.collectAsState()

            GlanceTheme {
                QuickRecordWidgetContent(
                    context = context,
                    captureState = captureState,
                    videoLens = videoLens,
                    isDndActive = isDndActive
                )
            }
        }
    }

    @Composable
    private fun QuickRecordWidgetContent(
        context: Context,
        captureState: CaptureState,
        videoLens: VideoLensPreference,
        isDndActive: Boolean
    ) {
        val isVideoActive = captureState == CaptureState.RECORDING_VIDEO
        val isAudioActive = captureState == CaptureState.RECORDING_AUDIO

        val rootModifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .let { mod ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    mod.cornerRadius(android.R.dimen.system_app_widget_background_radius)
                } else {
                    mod.cornerRadius(16.dp)
                }
            }
            .padding(4.dp)

        Column(
            modifier = rootModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // TOP BUTTON: Background Video (Wide 1x or Ultra-Wide 0.5x -> Inbox)
            val videoBg = if (isVideoActive) {
                GlanceTheme.colors.error
            } else {
                GlanceTheme.colors.primaryContainer
            }
            val videoFg = if (isVideoActive) {
                GlanceTheme.colors.onError
            } else {
                GlanceTheme.colors.onPrimaryContainer
            }

            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .defaultWeight()
                    .cornerRadius(12.dp)
                    .background(videoBg)
                    .clickable(
                        onClick = actionStartService(
                            intent = RecordingForegroundService.createWidgetVideoIntent(context),
                            isForegroundService = true
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        provider = ImageProvider(
                            if (isVideoActive) R.drawable.ic_widget_stop else R.drawable.ic_widget_videocam
                        ),
                        contentDescription = if (isVideoActive) {
                            "Stop background video recording"
                        } else {
                            "Start background video recording (${videoLens.displayLabel})"
                        },
                        colorFilter = ColorFilter.tint(videoFg),
                        modifier = GlanceModifier.size(16.dp)
                    )
                    Spacer(modifier = GlanceModifier.width(3.dp))
                    Text(
                        text = if (isVideoActive) "REC" else videoLens.shortBadge,
                        style = TextStyle(
                            color = videoFg,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(3.dp))

            // BOTTOM BUTTON: Background Audio + DND + Call-Resilient -> Inbox
            val audioBg = if (isAudioActive) {
                GlanceTheme.colors.error
            } else {
                GlanceTheme.colors.secondaryContainer
            }
            val audioFg = if (isAudioActive) {
                GlanceTheme.colors.onError
            } else {
                GlanceTheme.colors.onSecondaryContainer
            }

            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .defaultWeight()
                    .cornerRadius(12.dp)
                    .background(audioBg)
                    .clickable(
                        onClick = actionStartService(
                            intent = RecordingForegroundService.createWidgetAudioIntent(context),
                            isForegroundService = true
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        provider = ImageProvider(
                            if (isAudioActive) R.drawable.ic_widget_stop else R.drawable.ic_widget_mic
                        ),
                        contentDescription = if (isAudioActive) {
                            "Stop background audio recording"
                        } else {
                            "Start background audio recording with Do Not Disturb"
                        },
                        colorFilter = ColorFilter.tint(audioFg),
                        modifier = GlanceModifier.size(16.dp)
                    )
                    if (isAudioActive || isDndActive) {
                        Spacer(modifier = GlanceModifier.width(3.dp))
                        Image(
                            provider = ImageProvider(R.drawable.ic_widget_dnd),
                            contentDescription = "Do Not Disturb active",
                            colorFilter = ColorFilter.tint(audioFg),
                            modifier = GlanceModifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }

    companion object {
        suspend fun updateAllWidgets(context: Context) {
            try {
                FolderCamQuickWidget().updateAll(context)
            } catch (e: Exception) {
                // Ignore in Robolectric/headless environments without launcher widget host
            }
        }

        suspend fun requestPinToHomeScreen(context: Context): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    GlanceAppWidgetManager(context).requestPinGlanceAppWidget(
                        receiver = FolderCamQuickWidgetReceiver::class.java,
                        preview = FolderCamQuickWidget()
                    )
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
