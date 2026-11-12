package com.example.model

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
