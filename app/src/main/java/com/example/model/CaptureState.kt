package com.example.model

import android.view.Surface

/**
 * Modes ordered as requested:
 * 0 -> AUDIO (default when app opens)
 * 1 -> PHOTO (scroll left from Audio)
 * 2 -> VIDEO (scroll left from Photo)
 */
enum class RecorderMode(val index: Int, val title: String) {
    AUDIO(0, "Audio"),
    PHOTO(1, "Photo"),
    VIDEO(2, "Video");

    companion object {
        fun fromIndex(index: Int): RecorderMode {
            return entries.find { it.index == index } ?: AUDIO
        }
    }
}

enum class CaptureState {
    IDLE,
    RECORDING_VIDEO,
    RECORDING_AUDIO
}

enum class RecordingOrigin {
    IN_APP,
    WIDGET
}

enum class VideoLensPreference(
    val storageKey: String,
    val displayLabel: String,
    val shortBadge: String
) {
    WIDE(
        storageKey = "WIDE",
        displayLabel = "Wide (1x)",
        shortBadge = "1x"
    ),
    ULTRAWIDE(
        storageKey = "ULTRAWIDE",
        displayLabel = "Ultra-Wide (0.5x)",
        shortBadge = "0.5x"
    );

    companion object {
        fun fromStorageKey(key: String?): VideoLensPreference {
            if (key.isNullOrBlank()) return WIDE
            return entries.find { it.storageKey.equals(key.trim(), ignoreCase = true) } ?: WIDE
        }
    }
}

data class RecordingStatus(
    val state: CaptureState = CaptureState.IDLE,
    val durationSeconds: Int = 0,
    val amplitudes: List<Float> = emptyList(),
    val currentDecibels: Float = 0f,
    val currentOutputFilePath: String? = null,
    val isMicPreemptedByCall: Boolean = false
)

enum class CaptureOrientation(
    val storageKey: String,
    val shortLabel: String,
    val contentDescription: String,
    val surfaceRotation: Int?
) {
    AUTO(
        storageKey = "AUTO",
        shortLabel = "Auto",
        contentDescription = "Auto orientation",
        surfaceRotation = null
    ),
    PORTRAIT(
        storageKey = "PORTRAIT",
        shortLabel = "Port",
        contentDescription = "Portrait upright orientation",
        surfaceRotation = Surface.ROTATION_0
    ),
    LANDSCAPE_LEFT(
        storageKey = "LEFT",
        shortLabel = "Left",
        contentDescription = "Landscape left orientation",
        surfaceRotation = Surface.ROTATION_90
    ),
    PORTRAIT_UPSIDE_DOWN(
        storageKey = "FLIP",
        shortLabel = "Flip",
        contentDescription = "Upside down portrait orientation",
        surfaceRotation = Surface.ROTATION_180
    ),
    LANDSCAPE_RIGHT(
        storageKey = "RIGHT",
        shortLabel = "Right",
        contentDescription = "Landscape right orientation",
        surfaceRotation = Surface.ROTATION_270
    );

    companion object {
        fun fromStorageKey(key: String?): CaptureOrientation {
            if (key.isNullOrBlank()) return AUTO
            return entries.find { it.storageKey.equals(key.trim(), ignoreCase = true) } ?: AUTO
        }
    }
}

data class PhotoCaptureFeedbackState(
    val triggerCounter: Int = 0,
    val savedCounter: Int = 0,
    val inFlightCounter: Int = 0,
    val bufferedRamCount: Int = 0,
    val isBurstActive: Boolean = false,
    val isDraining: Boolean = false,
    val lastFlashTriggerId: Long = 0L,
    val targetFolderPath: String = "",
    val summaryMessage: String? = null,
    val isVisible: Boolean = false
)
