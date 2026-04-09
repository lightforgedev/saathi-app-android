package dev.lightforge.saathi.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
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
 * Audio configuration:
 * - Capture:  16kHz, mono, PCM_16BIT  (Gemini Live input requirement)
 * - Playback: 24kHz, mono, PCM_16BIT  (Gemini Live output rate)
 *
 * Audio routing:
 * - Requests AudioFocus with AUDIOFOCUS_GAIN_TRANSIENT before starting
 * - Sets AudioManager.MODE_IN_COMMUNICATION for proper VoIP routing
 * - Releases focus and restores mode on stop()
 *
 * Echo cancellation via VOICE_COMMUNICATION AudioSource is applied automatically
 * by Android's audio HAL.
 *
 * Audio is never stored on device — streamed directly between mic/WebSocket/speaker.
 * Pause/resume supported for call hold (resources remain allocated, capture suspends).
 *
 * [captureFlow] is a SharedFlow of raw PCM chunks. Collect it for loopback / echo testing.
 */
class AudioPipeline(private val context: Context) {

    companion object {
        private const val TAG = "AudioPipeline"

        // Gemini Live expects 16kHz 16-bit mono PCM for input
        const val CAPTURE_SAMPLE_RATE = 16000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2

        // Gemini Live outputs 24kHz 16-bit mono PCM
        const val PLAYBACK_SAMPLE_RATE = 24000
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO

        // Buffer = 4x min for stability
        private const val BUFFER_MULTIPLIER = 4
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private val minCaptureBuffer =
        AudioRecord.getMinBufferSize(CAPTURE_SAMPLE_RATE, CHANNEL_IN, ENCODING)
    private val minPlaybackBuffer =
        AudioTrack.getMinBufferSize(PLAYBACK_SAMPLE_RATE, CHANNEL_OUT, ENCODING)
    private val captureBufferSize = maxOf(minCaptureBuffer, 1) * BUFFER_MULTIPLIER
    private val playbackBufferSize = maxOf(minPlaybackBuffer, 1) * BUFFER_MULTIPLIER

    // Exposed for echo/loopback mode — GeminiLiveClient.connectEchoMode() subscribes here
    private val _captureFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val captureFlow: SharedFlow<ByteArray> = _captureFlow.asSharedFlow()

    /**
     * Starts the audio pipeline.
     *
     * Requests audio focus, sets MODE_IN_COMMUNICATION, initialises AudioRecord and
     * AudioTrack, then launches the mic capture coroutine.
     *
     * @param onAudioCaptured Callback with raw PCM bytes. Called on Dispatchers.IO.
     *   The ByteArray reference is stable — no need to copy unless retaining across calls.
     * @return true if started successfully, false if permission missing or init failed.
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

        // Request audio focus before touching AudioManager mode
        if (!requestAudioFocus()) {
            Log.e(TAG, "Failed to acquire audio focus")
            isRunning.set(false)
            return false
        }

        // Switch to voice communication mode (enables AEC, NS, AGC on device HAL)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        try {
            // --- AudioRecord (mic capture at 16kHz) ---
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
                Log.e(TAG, "AudioRecord failed to initialize (minBuf=$minCaptureBuffer)")
                releaseResources()
                return false
            }

            // --- AudioTrack (Gemini playback at 24kHz) ---
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
                Log.e(TAG, "AudioTrack failed to initialize (minBuf=$minPlaybackBuffer)")
                releaseResources()
                return false
            }

            audioRecord?.startRecording()
            audioTrack?.play()

            // Mic capture loop — runs on Dispatchers.IO until stop() or error
            val buffer = ByteArray(captureBufferSize)
            captureJob = scope.launch {
                Log.i(TAG, "Mic capture started (${CAPTURE_SAMPLE_RATE}Hz mono 16-bit)")
                while (isActive && isRunning.get()) {
                    if (isPaused.get()) {
                        kotlinx.coroutines.delay(100)
                        continue
                    }

                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    when {
                        bytesRead > 0 -> {
                            // Copy before emitting so buffer can be reused immediately
                            val chunk = buffer.copyOf(bytesRead)
                            _captureFlow.tryEmit(chunk)
                            onAudioCaptured(chunk, bytesRead)
                        }
                        bytesRead < 0 -> {
                            Log.e(TAG, "AudioRecord read error: $bytesRead")
                            break
                        }
                        // bytesRead == 0 → underrun, continue
                    }
                }
                Log.i(TAG, "Mic capture stopped")
            }

            Log.i(TAG, "Audio pipeline started (capture=${captureBufferSize}B @${CAPTURE_SAMPLE_RATE}Hz, playback=${playbackBufferSize}B @${PLAYBACK_SAMPLE_RATE}Hz)")
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
     * Plays PCM audio data received from Gemini through the AudioTrack.
     * Thread-safe — called from GeminiLiveClient's WebSocket message handler.
     *
     * @param pcmData Raw 24kHz 16-bit mono PCM bytes from Gemini
     */
    fun playAudio(pcmData: ByteArray) {
        if (!isRunning.get() || isPaused.get()) return
        val result = audioTrack?.write(pcmData, 0, pcmData.size) ?: -1
        if (result < 0) {
            Log.w(TAG, "AudioTrack write error: $result")
        }
    }

    /**
     * Pauses audio capture and playback (call on hold).
     * Resources remain allocated for fast resume.
     */
    fun pause() {
        if (isPaused.compareAndSet(false, true)) {
            Log.i(TAG, "Audio pipeline paused")
            audioTrack?.pause()
        }
    }

    /**
     * Resumes audio capture and playback (off hold).
     */
    fun resume() {
        if (isPaused.compareAndSet(true, false)) {
            Log.i(TAG, "Audio pipeline resumed")
            audioTrack?.play()
        }
    }

    /**
     * Stops the audio pipeline and releases all resources.
     * Restores audio mode and abandons audio focus.
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping audio pipeline")
            captureJob?.cancel()
            captureJob = null
            releaseResources()
        }
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .build()
            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun releaseResources() {
        // Stop AudioRecord
        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
        audioRecord?.release()
        audioRecord = null

        // Stop AudioTrack
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
        } catch (_: IllegalStateException) {}
        audioTrack?.release()
        audioTrack = null

        // Restore audio mode and release focus
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore audio mode", e)
        }
        abandonAudioFocus()

        Log.d(TAG, "Audio resources released")
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
}
