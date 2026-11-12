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

    fun startRecording(outputFile: File, scope: CoroutineScope): Boolean {
        try {
            stopRecording() // Clean up any lingering recording

            currentFile = outputFile
            amplitudeHistory.clear()

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            _status.value = RecordingStatus(
                state = CaptureState.RECORDING_AUDIO,
                durationSeconds = 0,
                amplitudes = emptyList(),
                currentDecibels = 0f,
                currentOutputFilePath = outputFile.absolutePath
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
                        currentOutputFilePath = outputFile.absolutePath
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
}
