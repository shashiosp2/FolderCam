package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
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

class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var recordingJob: Job? = null
    private var currentFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    private var activeScope: CoroutineScope? = null
    private var activeFallbackProvider: ((String) -> File?)? = null
    private var activeNextFileProvider: (() -> File?)? = null
    private var onSegmentFinalizedCallback: ((File) -> Unit)? = null
    private var onCallInterruptionNoticeCallback: ((String) -> Unit)? = null

    @Volatile
    private var isStoppingIntentionally: Boolean = false

    @Volatile
    private var isRestartingAfterPreemption: Boolean = false

    @Volatile
    private var isMicSilencedByCall: Boolean = false

    @Volatile
    private var sessionStartTimeMs: Long = 0L

    private val _status = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    private val amplitudeHistory = mutableListOf<Float>()
    private val maxBars = 45

    private val resilientFocusListener = AudioManager.OnAudioFocusChangeListener {
        // Intentionally ignore AUDIOFOCUS_LOSS / AUDIOFOCUS_LOSS_TRANSIENT so incoming
        // notifications or calls never trigger our app to turn off audio recording.
    }

    fun startRecording(
        outputFile: File,
        scope: CoroutineScope,
        fallbackFile: File? = null,
        nextSegmentFileProvider: (() -> File?)? = null,
        fallbackProvider: ((String) -> File?)? = null,
        onSegmentFinalized: ((File) -> Unit)? = null,
        onCallInterruptionNotice: ((String) -> Unit)? = null
    ): Boolean {
        try {
            stopRecordingInternal(releaseResources = true)
            isStoppingIntentionally = false
            isRestartingAfterPreemption = false
            isMicSilencedByCall = false

            activeScope = scope
            activeNextFileProvider = nextSegmentFileProvider
            activeFallbackProvider = fallbackProvider
            onSegmentFinalizedCallback = onSegmentFinalized
            onCallInterruptionNoticeCallback = onCallInterruptionNotice

            sessionStartTimeMs = System.currentTimeMillis()
            amplitudeHistory.clear()

            acquireWakeLockSafely()
            requestResilientAudioFocus()
            registerAudioRecordingMonitor()

            val started = startSegmentInternal(outputFile, fallbackFile, scope)
            if (!started) {
                releaseAudioGuards()
            }
            return started
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            return false
        }
    }

    private fun startSegmentInternal(
        outputFile: File,
        fallbackFile: File?,
        scope: CoroutineScope
    ): Boolean {
        val targetFile = tryCreateRecorderFile(outputFile, fallbackFile) ?: return false
        currentFile = targetFile

        val recorder = createMediaRecorder(targetFile) ?: run {
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
        } ?: return false

        val activeFile = currentFile ?: targetFile
        mediaRecorder = recorder
        _status.value = RecordingStatus(
            state = CaptureState.RECORDING_AUDIO,
            durationSeconds = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt(),
            amplitudes = amplitudeHistory.toList(),
            currentDecibels = 0f,
            currentOutputFilePath = activeFile.absolutePath,
            isMicPreemptedByCall = isMicSilencedByCall
        )

        recordingJob?.cancel()
        recordingJob = scope.launch(Dispatchers.Default) {
            while (isActive && mediaRecorder != null && !isStoppingIntentionally) {
                delay(80)
                val rawAmp = try {
                    mediaRecorder?.maxAmplitude ?: 0
                } catch (e: Exception) {
                    0
                }

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

                val elapsedSec = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt()
                _status.value = RecordingStatus(
                    state = CaptureState.RECORDING_AUDIO,
                    durationSeconds = elapsedSec,
                    amplitudes = amplitudeHistory.toList(),
                    currentDecibels = db,
                    currentOutputFilePath = activeFile.absolutePath,
                    isMicPreemptedByCall = isMicSilencedByCall
                )
            }
        }
        return true
    }

    fun stopRecording(): File? {
        isStoppingIntentionally = true
        return stopRecordingInternal(releaseResources = true)
    }

    private fun stopRecordingInternal(releaseResources: Boolean): File? {
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
            if (releaseResources) {
                releaseAudioGuards()
                _status.value = RecordingStatus(state = CaptureState.IDLE)
            }
        }

        currentFile = null
        return if (file != null && file.exists() && file.length() > 0) file else null
    }

    fun cancelRecording() {
        isStoppingIntentionally = true
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
            releaseAudioGuards()
            _status.value = RecordingStatus(state = CaptureState.IDLE)
        }
    }

    private fun handleRecorderPreemptedOrErrored() {
        if (isStoppingIntentionally || isRestartingAfterPreemption) return
        val scope = activeScope ?: return
        val nextFile = activeNextFileProvider?.invoke() ?: return

        isRestartingAfterPreemption = true
        val previousSegment = stopRecordingInternal(releaseResources = false)
        if (previousSegment != null) {
            onSegmentFinalizedCallback?.invoke(previousSegment)
        }

        scope.launch(Dispatchers.Main) {
            onCallInterruptionNoticeCallback?.invoke("Call interrupted mic; preserving file & resuming audio…")
            var attempts = 0
            while (isActive && !isStoppingIntentionally && attempts < 60) {
                delay(1000)
                if (!isMicSilencedByCall) {
                    val fallback = activeFallbackProvider?.invoke(nextFile.name)
                    if (startSegmentInternal(nextFile, fallback, scope)) {
                        isRestartingAfterPreemption = false
                        onCallInterruptionNoticeCallback?.invoke("Audio recording resumed")
                        return@launch
                    }
                }
                attempts++
            }
            isRestartingAfterPreemption = false
        }
    }

    private fun acquireWakeLockSafely() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "FolderCam:BackgroundAudioWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            // Ignore in test environments
        }
    }

    private fun requestResilientAudioFocus() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(resilientFocusListener)
                    .build()
                focusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    resilientFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun registerAudioRecordingMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (recordingCallback != null) return
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    val silenced = configs?.any { config ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            config.isClientSilenced
                        } else {
                            false
                        }
                    } ?: false
                    isMicSilencedByCall = silenced
                    if (_status.value.state == CaptureState.RECORDING_AUDIO) {
                        _status.value = _status.value.copy(isMicPreemptedByCall = silenced)
                    }
                }
            }
            recordingCallback = callback
            am.registerAudioRecordingCallback(callback, null)
        } catch (e: Exception) {
            // Ignore in headless/test environments
        }
    }

    private fun releaseAudioGuards() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am?.abandonAudioFocus(resilientFocusListener)
            }
            focusRequest = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                recordingCallback?.let { am?.unregisterAudioRecordingCallback(it) }
            }
            recordingCallback = null
        } catch (e: Exception) {
            // Ignore
        }

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
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
        releaseAudioGuards()
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
                setOnErrorListener { _, _, _ ->
                    handleRecorderPreemptedOrErrored()
                }
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
