package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class RecordingForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var startTimeMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coordinator = RecordingCoordinator.getInstance(applicationContext)
        when (intent?.action) {
            ACTION_WIDGET_TOGGLE_VIDEO -> {
                val hasCamPerm = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (hasCamPerm) {
                    val inbox = coordinator.folderRepository.getInboxFolder()
                    startTimeMs = System.currentTimeMillis()
                    val notif = buildNotification(
                        recordingType = TYPE_VIDEO,
                        folderName = inbox.name,
                        sdcardPath = inbox.sdcardPath,
                        elapsedSeconds = 0
                    )
                    startForegroundSafely(TYPE_VIDEO, notif)
                }
                coordinator.handleWidgetVideoToggle()
                return START_STICKY
            }
            ACTION_WIDGET_TOGGLE_AUDIO -> {
                val hasAudioPerm = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasAudioPerm) {
                    val inbox = coordinator.folderRepository.getInboxFolder()
                    startTimeMs = System.currentTimeMillis()
                    val notif = buildNotification(
                        recordingType = TYPE_AUDIO,
                        folderName = inbox.name,
                        sdcardPath = inbox.sdcardPath,
                        elapsedSeconds = 0
                    )
                    startForegroundSafely(TYPE_AUDIO, notif)
                }
                coordinator.handleWidgetAudioToggle()
                return START_STICKY
            }
            ACTION_STOP_FROM_NOTIFICATION -> {
                coordinator.stopActiveRecordingFromNotification()
                stopForegroundServiceCleanly()
                return START_NOT_STICKY
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundServiceCleanly()
                return START_NOT_STICKY
            }
            ACTION_START_RECORDING -> {
                val recordingType = intent.getStringExtra(EXTRA_RECORDING_TYPE) ?: TYPE_AUDIO
                val folderName = intent.getStringExtra(EXTRA_FOLDER_NAME) ?: "Inbox"
                val sdcardPath = intent.getStringExtra(EXTRA_SDCARD_PATH) ?: "/sdcard/inbox"

                startTimeMs = System.currentTimeMillis()
                val initialNotification = buildNotification(
                    recordingType = recordingType,
                    folderName = folderName,
                    sdcardPath = sdcardPath,
                    elapsedSeconds = 0
                )

                startForegroundSafely(recordingType, initialNotification)
                startLiveNotificationTicker(recordingType, folderName, sdcardPath)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely(recordingType: String, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val fgsType = if (recordingType == TYPE_VIDEO) {
                    val hasMic = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasMic) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    }
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ignored: Exception) {
                // Ignore if restricted in headless test environment
            }
        }
    }

    private fun startLiveNotificationTicker(
        recordingType: String,
        folderName: String,
        sdcardPath: String
    ) {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            while (isActive) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
                val updated = buildNotification(
                    recordingType = recordingType,
                    folderName = folderName,
                    sdcardPath = sdcardPath,
                    elapsedSeconds = elapsed
                )
                try {
                    nm.notify(NOTIFICATION_ID, updated)
                } catch (e: SecurityException) {
                    // Ignore if POST_NOTIFICATIONS is not granted
                }
            }
        }
    }

    private fun buildNotification(
        recordingType: String,
        folderName: String,
        sdcardPath: String,
        elapsedSeconds: Int
    ): Notification {
        val mins = elapsedSeconds / 60
        val secs = elapsedSeconds % 60
        val formattedDuration = String.format(Locale.US, "%02d:%02d", mins, secs)

        val title = if (recordingType == TYPE_VIDEO) {
            "Recording Video ($formattedDuration)"
        } else {
            "Recording Audio ($formattedDuration)"
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordingForegroundService::class.java).apply {
            action = ACTION_STOP_FROM_NOTIFICATION
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smallIcon = if (recordingType == TYPE_VIDEO) {
            android.R.drawable.presence_video_online
        } else {
            android.R.drawable.ic_btn_speak_now
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(title)
            .setContentText("Saving to $folderName ($sdcardPath)")
            .setSubText("DND & Background capture active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setWhen(startTimeMs)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Recording",
                stopPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Media Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live notification while audio or video is recording in background"
                setShowBadge(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundServiceCleanly() {
        tickerJob?.cancel()
        tickerJob = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // Ignore
        }
        stopSelf()
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "foldercam_recording_channel"
        const val NOTIFICATION_ID = 4001

        const val ACTION_START_RECORDING = "com.example.action.START_RECORDING"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"
        const val ACTION_STOP_FROM_NOTIFICATION = "com.example.action.STOP_FROM_NOTIFICATION"
        const val ACTION_WIDGET_TOGGLE_VIDEO = "com.example.action.WIDGET_TOGGLE_VIDEO"
        const val ACTION_WIDGET_TOGGLE_AUDIO = "com.example.action.WIDGET_TOGGLE_AUDIO"

        const val EXTRA_RECORDING_TYPE = "extra_recording_type"
        const val EXTRA_FOLDER_NAME = "extra_folder_name"
        const val EXTRA_SDCARD_PATH = "extra_sdcard_path"

        const val TYPE_AUDIO = "AUDIO"
        const val TYPE_VIDEO = "VIDEO"

        fun createWidgetVideoIntent(context: Context): Intent {
            return Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_WIDGET_TOGGLE_VIDEO
            }
        }

        fun createWidgetAudioIntent(context: Context): Intent {
            return Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_WIDGET_TOGGLE_AUDIO
            }
        }

        fun start(
            context: Context,
            recordingType: String,
            folderName: String,
            sdcardPath: String
        ) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_START_RECORDING
                putExtra(EXTRA_RECORDING_TYPE, recordingType)
                putExtra(EXTRA_FOLDER_NAME, folderName)
                putExtra(EXTRA_SDCARD_PATH, sdcardPath)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.stopService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
