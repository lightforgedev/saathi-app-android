package dev.lightforge.saathi.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages audio capture (mic) and playback (speaker) for Gemini voice sessions.
 *
 * Audio configuration (matching Gemini Live requirements):
 * - Sample rate: 16000 Hz (Gemini Live native rate)
 * - Channel: Mono
 * - Encoding: PCM 16-bit signed little-endian
 * - Frame size: 2 bytes per sample
 *
 * Design decisions:
 * - Audio is NEVER stored on device — streamed directly between mic/speaker and Gemini
 * - Uses VOICE_COMMUNICATION AudioSource for echo cancellation
 * - Uses USAGE_VOICE_COMMUNICATION for proper audio routing during calls
 * - Pause/resume supported for call hold
 */
class AudioPipeline(private val context: Context) {

    companion object {
        private const val TAG = "AudioPipeline"

        // Mic → Gemini: 16kHz 16-bit mono PCM
        const val CAPTURE_SAMPLE_RATE = 16000
        // Gemini → Speaker: 24kHz 16-bit mono PCM (Gemini Live output rate)
        const val PLAYBACK_SAMPLE_RATE = 24000

        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2

        // Buffer = 4x min buffer for stability
        private const val BUFFER_MULTIPLIER = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private val minCaptureBuffer = AudioRecord.getMinBufferSize(CAPTURE_SAMPLE_RATE, CHANNEL_IN, ENCODING)
    private val minPlaybackBuffer = AudioTrack.getMinBufferSize(PLAYBACK_SAMPLE_RATE, CHANNEL_OUT, ENCODING)
    private val captureBufferSize = minCaptureBuffer * BUFFER_MULTIPLIER
    private val playbackBufferSize = minPlaybackBuffer * BUFFER_MULTIPLIER

    // Raw PCM from mic — used by GeminiLiveClient.connectEchoMode() for loopback testing
    private val _captureFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val captureFlow: SharedFlow<ByteArray> = _captureFlow.asSharedFlow()

    /**
     * Starts the audio pipeline.
     *
     * @param onAudioCaptured Callback invoked with PCM data from the microphone.
     *   Called on the IO dispatcher. The ByteArray may be reused — copy if needed.
     * @return true if started successfully, false if permission missing or init failed
     */
    @SuppressLint("MissingPermission")
    fun start(onAudioCaptured: (pcmData: ByteArray, size: Int) -> Unit): Boolean {
        if (!hasRecordPermission()) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return false
        }

        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Pipeline already running")
            return false
        }

        try {
            // Set AudioManager mode for VoIP routing (earpiece/speakerphone)
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Initialize AudioRecord for mic capture at 16kHz
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(CAPTURE_SAMPLE_RATE)
                        .setChannelMask(CHANNEL_IN)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(captureBufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                releaseResources()
                return false
            }

            // Initialize AudioTrack for Gemini audio playback at 24kHz
            // (Gemini Live returns 24kHz PCM — must match or audio plays at wrong speed)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PLAYBACK_SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(playbackBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize")
                releaseResources()
                return false
            }

            // Start capture + playback
            audioRecord?.startRecording()
            audioTrack?.play()

            // Launch mic capture loop
            val buffer = ByteArray(captureBufferSize)
            captureJob = scope.launch {
                Log.i(TAG, "Mic capture started (${CAPTURE_SAMPLE_RATE}Hz, mono, 16-bit)")
                while (isActive && isRunning.get()) {
                    if (isPaused.get()) {
                        // When paused (call on hold), skip reading but keep resources alive
                        kotlinx.coroutines.delay(100)
                        continue
                    }

                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        onAudioCaptured(buffer, bytesRead)
                        // Also emit to captureFlow for echo-mode loopback
                        _captureFlow.tryEmit(buffer.copyOf(bytesRead))
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read error: $bytesRead")
                        break
                    }
                }
                Log.i(TAG, "Mic capture stopped")
            }

            Log.i(TAG, "Audio pipeline started (capture=$captureBufferSize, playback=$playbackBufferSize)")
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during audio init", e)
            releaseResources()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio pipeline", e)
            releaseResources()
            return false
        }
    }

    /**
     * Plays audio data received from Gemini through the AudioTrack.
     * Called from the Gemini WebSocket message handler.
     *
     * @param pcmData Raw PCM audio bytes from Gemini
     */
    fun playAudio(pcmData: ByteArray) {
        if (!isRunning.get() || isPaused.get()) return
        audioTrack?.write(pcmData, 0, pcmData.size)
    }

    /**
     * Pauses audio capture and playback (for call hold).
     * Resources remain allocated for quick resume.
     */
    fun pause() {
        if (isPaused.compareAndSet(false, true)) {
            Log.i(TAG, "Audio pipeline paused")
            audioTrack?.pause()
        }
    }

    /**
     * Resumes audio capture and playback (from call hold).
     */
    fun resume() {
        if (isPaused.compareAndSet(true, false)) {
            Log.i(TAG, "Audio pipeline resumed")
            audioTrack?.play()
        }
    }

    /**
     * Stops the audio pipeline and releases all resources.
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping audio pipeline")
            captureJob?.cancel()
            captureJob = null
            releaseResources()
        }
    }

    private fun releaseResources() {
        isRunning.set(false)

        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
        audioRecord?.release()
        audioRecord = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (_: IllegalStateException) {}
        audioTrack?.release()
        audioTrack = null

        Log.d(TAG, "Audio resources released")
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
