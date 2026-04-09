package dev.lightforge.saathi.ui.spike

import android.app.Application
import android.content.Context
import android.telecom.TelecomManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lightforge.saathi.BuildConfig
import dev.lightforge.saathi.network.AegisApiClient
import dev.lightforge.saathi.network.SessionRequest
import dev.lightforge.saathi.telecom.PhoneAccountManager
import dev.lightforge.saathi.telecom.SaathiConnectionService
import dev.lightforge.saathi.voice.AudioPipeline
import dev.lightforge.saathi.voice.GeminiLiveClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

        // Hardcoded spike device token — generated via POST /devices/pair + /devices/verify
        // during local dev setup. Valid 90 days. Replace if server secret key changes.
        // Device: bfd544d0-9403-49ac-8e7e-71e5a6dab24f  Org: 152408 3f-5954-4bd8-bef2-1081139629de
        private const val SPIKE_DEVICE_TOKEN =
            "SFMyNTY.g2gDdAAAAAN3Bm9yZ19pZG0AAAAkMTUyNDA4M2YtNTk1NC00YmQ4LWJlZjItMTA4MTEzOTYyOWRldwRyb2xlbQAAAA1zYWF0aGlfZGV2aWNldwlkZXZpY2VfaWRtAAAAJGJmZDU0NGQwLTk0MDMtNDlhYy04ZTdlLTcxZTVhNmRhYjI0Zm4GAOHXfXCdAWIAAVGA.vosHwSOPQk4dZvubVw9B_HWT-jJVw1tOI5sKT1xLrNw"
        private const val SPIKE_DEVICE_ID = "bfd544d0-9403-49ac-8e7e-71e5a6dab24f"
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

        if (_state.value.echoMode) {
            log("Echo mode — running direct loopback")
            startDirectLoopback()
        } else {
            log("Live mode — connecting to backend at ${BuildConfig.AEGIS_API_BASE_URL}")
            startLiveSession()
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

    /**
     * Live mode: calls POST /session, gets gemini_ws_url, connects AudioPipeline + Gemini.
     * Bypasses Telecom entirely — works on Samsung One UI where self-managed calls are rejected.
     */
    private fun startLiveSession() {
        val context = getApplication<Application>()
        val pipeline = AudioPipeline(context)
        val client = GeminiLiveClient()

        directPipeline = pipeline
        directGeminiClient = client

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Build a minimal Retrofit client with spike device token auth
                val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
                val okHttp = OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .addHeader("Authorization", "Bearer $SPIKE_DEVICE_TOKEN")
                            .build()
                        chain.proceed(request)
                    }
                    .build()
                val api = Retrofit.Builder()
                    .baseUrl("${BuildConfig.AEGIS_API_BASE_URL}/api/v1/saathi/")
                    .client(okHttp)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(AegisApiClient::class.java)

                log("POST /session → ${BuildConfig.AEGIS_API_BASE_URL}")
                val response = api.createSession(
                    SessionRequest(
                        caller_number = FAKE_CALLER_NUMBER,
                        direction = "incoming",
                        device_id = SPIKE_DEVICE_ID
                    )
                )

                if (!response.isSuccessful) {
                    log("ERROR: /session returned ${response.code()} ${response.message()}")
                    return@launch
                }

                val session = response.body()!!
                log("Session created: ${session.session_id}")
                log("Connecting to Gemini WebSocket…")
                _state.update { it.copy(webSocketState = "CONNECTING", callActive = true) }

                client.connect(
                    geminiWsUrl = session.gemini_ws_url,
                    systemInstruction = session.system_instruction,
                    onEvent = { msg ->
                        log(msg)
                        when {
                            msg.startsWith("WS connected") -> _state.update { it.copy(webSocketState = "CONNECTED") }
                            msg.startsWith("Gemini ready") -> _state.update { it.copy(webSocketState = "CONNECTED") }
                            msg.startsWith("WS FAILED") -> _state.update { it.copy(webSocketState = "FAILED") }
                            msg.startsWith("WS closing") || msg.startsWith("WS closed") ->
                                _state.update { it.copy(webSocketState = "CLOSED") }
                        }
                    }
                )

                // Collect Gemini audio responses → speaker
                launch {
                    client.audioResponseFlow.collect { pcmData ->
                        pipeline.playAudio(pcmData)
                        _state.update { it.copy(audioTrackState = "PLAYING") }
                    }
                }

                // Start mic capture → send to Gemini
                val started = pipeline.start(
                    onAudioCaptured = { pcmData, size ->
                        frameCount++
                        updateDbMeter(pcmData, size)
                        client.sendAudio(pcmData, size)
                    }
                )

                if (started) {
                    _state.update { it.copy(audioRecordState = "RECORDING") }
                    log("Live session active — speak to talk to Gemini")

                    // Call timer: warn at (maxDuration - 30)s, hard-end at maxDuration
                    val maxDuration = session.config.max_duration_seconds.toLong()
                    val warnAfter = (maxDuration - 30).coerceAtLeast(30)
                    launch {
                        delay(warnAfter * 1000)
                        if (_state.value.callActive) {
                            client.sendSystemMessage(
                                "[System: Only 30 seconds remaining in this call. " +
                                "Please wrap up the conversation politely and say goodbye to the caller.]"
                            )
                            log("30s warning sent to Gemini")
                        }
                        delay(30_000)
                        if (_state.value.callActive) {
                            log("Max call duration reached — ending call")
                            endCall()
                        }
                    }
                } else {
                    log("ERROR: AudioPipeline failed to start")
                    client.disconnect()
                    directPipeline = null
                    directGeminiClient = null
                }
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
                Log.e(TAG, "Live session failed", e)
                directPipeline = null
                directGeminiClient = null
            }
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
