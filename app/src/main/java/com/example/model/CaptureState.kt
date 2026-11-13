package com.example.model

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

data class RecordingStatus(
    val state: CaptureState = CaptureState.IDLE,
    val durationSeconds: Int = 0,
    val amplitudes: List<Float> = emptyList(),
    val currentDecibels: Float = 0f,
    val currentOutputFilePath: String? = null
)

data class PhotoCaptureFeedbackState(
    val triggerCounter: Int = 0,
    val savedCounter: Int = 0,
    val inFlightCounter: Int = 0,
    val isBurstActive: Boolean = false,
    val lastFlashTriggerId: Long = 0L,
    val targetFolderPath: String = "",
    val summaryMessage: String? = null,
    val isVisible: Boolean = false
)
