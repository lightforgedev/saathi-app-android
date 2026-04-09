package dev.lightforge.saathi.ui.spike

import android.app.Application
import android.content.Context
import android.telecom.TelecomManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lightforge.saathi.telecom.PhoneAccountManager
import dev.lightforge.saathi.telecom.SaathiConnectionService
import dev.lightforge.saathi.voice.AudioPipeline
import dev.lightforge.saathi.voice.GeminiLiveClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ViewModel for [SpikeTestActivity].
 *
 * Manages the spike test state machine:
 *   IDLE → report fake incoming call → Telecom notifies → user answers → ACTIVE
 *   OR (fallback): IDLE → direct AudioPipeline loopback → ACTIVE
 *   ACTIVE → end call → IDLE
 *
 * Does NOT use Hilt — standalone so it works without a paired device.
 */
class SpikeTestViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SpikeTestViewModel"
        private const val FAKE_CALLER_NUMBER = "+919876500001"
        private const val FAKE_CALLER_NAME = "Spike Test Caller"
    }

    data class UiState(
        val echoMode: Boolean = true,
        val callActive: Boolean = false,
        val phoneAccountReady: Boolean = false,
        val audioRecordState: String = "IDLE",
        val audioTrackState: String = "IDLE",
        val webSocketState: String = "DISCONNECTED",
        val dbLevel: String = "-∞",
        val dbProgress: Float = 0f,
        val frameCount: Long = 0L,
        val callLog: List<String> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val phoneAccountManager = PhoneAccountManager(application)

    // Direct loopback path (bypasses Telecom)
    private var directPipeline: AudioPipeline? = null
    private var directGeminiClient: GeminiLiveClient? = null
    private var frameCount = 0L

    init {
        registerPhoneAccount()
    }

    fun setEchoMode(enabled: Boolean) {
        _state.update { it.copy(echoMode = enabled) }
    }

    /**
     * Reports a simulated incoming call to the Telecom framework.
     *
     * On success: SaathiConnectionService.onCreateIncomingConnection() fires and the
     * system shows an incoming call notification. The user taps Answer → onAnswer() → voice starts.
     *
     * Fallback (if PhoneAccount isn't ready): runs AudioPipeline loopback directly,
     * bypassing Telecom entirely. Useful on emulators or with missing permissions.
     */
    fun simulateIncomingCall() {
        if (_state.value.callActive) return
        log("Simulating incoming call from $FAKE_CALLER_NUMBER")

        if (_state.value.phoneAccountReady && !_state.value.echoMode) {
            // Production path: route through Telecom for real call interception
            phoneAccountManager.reportIncomingCall(
                callerNumber = FAKE_CALLER_NUMBER,
                callerName = FAKE_CALLER_NAME,
                sessionId = "spike-${System.currentTimeMillis()}",
                echoMode = false
            )
            _state.update { it.copy(callActive = true) }
            log("Reported to Telecom — answer via system call notification")
        } else {
            // Echo mode: bypass Telecom entirely — tests audio pipeline directly
            log(if (_state.value.echoMode) "Echo mode — running direct loopback" else "PhoneAccount not ready — running direct loopback")
            startDirectLoopback()
        }
    }

    /**
     * Directly tests AudioPipeline + GeminiLiveClient echo mode without Telecom framework.
     * Proves end-to-end: mic capture → echo → AudioTrack playback.
     */
    private fun startDirectLoopback() {
        val context = getApplication<Application>()
        val pipeline = AudioPipeline(context)
        val client = GeminiLiveClient()

        directPipeline = pipeline
        directGeminiClient = client

        // Connect echo loopback first — subscriber must be registered before pipeline emits
        client.connectEchoMode(pipeline)
        _state.update { it.copy(webSocketState = "ECHO") }

        // Collect echo audio → playback
        viewModelScope.launch(Dispatchers.IO) {
            client.audioResponseFlow.collect { pcmData ->
                pipeline.playAudio(pcmData)
                _state.update { it.copy(audioTrackState = "PLAYING") }
            }
        }

        // Start mic capture
        val started = pipeline.start(
            onAudioCaptured = { pcmData, size ->
                frameCount++
                updateDbMeter(pcmData, size)
            }
        )

        if (started) {
            _state.update { it.copy(callActive = true, audioRecordState = "RECORDING") }
            log("Direct loopback active — speak to hear echo")
        } else {
            log("ERROR: AudioPipeline failed to start (RECORD_AUDIO permission?)")
            client.disconnect()
            directPipeline = null
            directGeminiClient = null
        }
    }

    /** Ends the direct loopback test or disconnects a Telecom-managed call. */
    fun endCall() {
        directPipeline?.stop()
        directGeminiClient?.disconnect()
        directPipeline = null
        directGeminiClient = null

        // If a real Telecom connection is alive, disconnect it
        SaathiConnectionService.activeConnections[FAKE_CALLER_NUMBER]?.onDisconnect()

        frameCount = 0L
        _state.update {
            it.copy(
                callActive = false,
                audioRecordState = "IDLE",
                audioTrackState = "IDLE",
                webSocketState = "DISCONNECTED",
                dbLevel = "-∞",
                dbProgress = 0f,
                frameCount = 0L
            )
        }
        log("Call ended")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun registerPhoneAccount() {
        viewModelScope.launch {
            try {
                phoneAccountManager.registerPhoneAccount()
            } catch (e: Exception) {
                Log.e(TAG, "PhoneAccount registration error", e)
                log("PhoneAccount error: ${e.message}")
                return@launch
            }
            // Verification requires READ_PHONE_NUMBERS — treat failure as registered
            // since registerPhoneAccount() above already succeeded without throwing.
            val ready = try {
                val context = getApplication<Application>()
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                telecomManager.getPhoneAccount(phoneAccountManager.phoneAccountHandle) != null
            } catch (e: Exception) {
                Log.w(TAG, "getPhoneAccount verification failed (READ_PHONE_NUMBERS?), assuming registered", e)
                true
            }
            _state.update { it.copy(phoneAccountReady = ready) }
            log(if (ready) "PhoneAccount registered" else "PhoneAccount not registered (MANAGE_OWN_CALLS needed)")
        }
    }

    private fun updateDbMeter(pcmData: ByteArray, size: Int) {
        val rms = computeRms(pcmData, size)
        val db = if (rms > 0) 20 * log10(rms / 32768.0) else Double.NEGATIVE_INFINITY
        val dbStr = if (db.isInfinite()) "-∞" else "%.1f".format(db)
        val progress = ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
        _state.update { s ->
            s.copy(
                dbLevel = dbStr,
                dbProgress = progress,
                frameCount = frameCount
            )
        }
    }

    private fun computeRms(pcmData: ByteArray, size: Int): Double {
        if (size < 2) return 0.0
        var sumSq = 0.0
        val samples = size / 2
        for (i in 0 until samples) {
            val lo = pcmData[i * 2].toInt() and 0xFF
            val hi = pcmData[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo
            sumSq += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumSq / samples)
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _state.update { s -> s.copy(callLog = s.callLog + "[$ts] $message") }
    }
}
