package com.example.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.example.model.CaptureState
import com.example.model.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10
import kotlin.math.max

class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var recordingJob: Job? = null
    private var currentFile: File? = null

    private val _status = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    private val amplitudeHistory = mutableListOf<Float>()
    private val maxBars = 45

    fun startRecording(
        outputFile: File,
        scope: CoroutineScope,
        fallbackFile: File? = null
    ): Boolean {
        try {
            stopRecording() // Clean up any lingering recording

            val targetFile = tryCreateRecorderFile(outputFile, fallbackFile) ?: run {
                cleanup()
                return false
            }

            currentFile = targetFile
            amplitudeHistory.clear()

            val recorder = createMediaRecorder(targetFile) ?: run {
                // Retry with fallbackFile if primary target failed on /sdcard FUSE
                if (fallbackFile != null && fallbackFile.absolutePath != targetFile.absolutePath) {
                    if (targetFile.exists() && targetFile.length() == 0L) {
                        targetFile.delete()
                    }
                    fallbackFile.parentFile?.mkdirs()
                    currentFile = fallbackFile
                    createMediaRecorder(fallbackFile)
                } else {
                    null
                }
            } ?: run {
                cleanup()
                return false
            }

            val activeFile = currentFile ?: targetFile
            mediaRecorder = recorder
            _status.value = RecordingStatus(
                state = CaptureState.RECORDING_AUDIO,
                durationSeconds = 0,
                amplitudes = emptyList(),
                currentDecibels = 0f,
                currentOutputFilePath = activeFile.absolutePath
            )

            val startTime = System.currentTimeMillis()

            recordingJob = scope.launch(Dispatchers.Default) {
                while (isActive && mediaRecorder != null) {
                    delay(80)
                    val rawAmp = try {
                        mediaRecorder?.maxAmplitude ?: 0
                    } catch (e: Exception) {
                        0
                    }

                    // Normalize amplitude: maxAmplitude is 0..32767
                    val normalized = (rawAmp.toFloat() / 25000f).coerceIn(0.05f, 1.0f)
                    val db = if (rawAmp > 0) {
                        (20 * log10(rawAmp.toDouble() / 32767.0) + 90).toFloat().coerceIn(0f, 100f)
                    } else 0f

                    synchronized(amplitudeHistory) {
                        amplitudeHistory.add(normalized)
                        if (amplitudeHistory.size > maxBars) {
                            amplitudeHistory.removeAt(0)
                        }
                    }

                    val elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).toInt()

                    _status.value = RecordingStatus(
                        state = CaptureState.RECORDING_AUDIO,
                        durationSeconds = elapsedSec,
                        amplitudes = amplitudeHistory.toList(),
                        currentDecibels = db,
                        currentOutputFilePath = activeFile.absolutePath
                    )
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            return false
        }
    }

    fun stopRecording(): File? {
        recordingJob?.cancel()
        recordingJob = null

        val file = currentFile
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
            _status.value = RecordingStatus(state = CaptureState.IDLE)
        }

        currentFile = null
        return if (file != null && file.exists() && file.length() > 0) file else null
    }

    fun cancelRecording() {
        recordingJob?.cancel()
        recordingJob = null
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaRecorder = null
            currentFile?.delete()
            currentFile = null
            _status.value = RecordingStatus(state = CaptureState.IDLE)
        }
    }

    private fun cleanup() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Ignore
        }
        mediaRecorder = null
        currentFile = null
        _status.value = RecordingStatus(state = CaptureState.IDLE)
    }

    private fun tryCreateRecorderFile(primary: File, fallback: File?): File? {
        return try {
            primary.parentFile?.mkdirs()
            primary
        } catch (e: Exception) {
            if (fallback != null) {
                fallback.parentFile?.mkdirs()
                fallback
            } else {
                null
            }
        }
    }

    private fun createMediaRecorder(targetFile: File): MediaRecorder? {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(targetFile.absolutePath)
                prepare()
                start()
            }
            recorder
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                recorder.reset()
                recorder.release()
            } catch (ignored: Exception) {
            }
            null
        }
    }
}
