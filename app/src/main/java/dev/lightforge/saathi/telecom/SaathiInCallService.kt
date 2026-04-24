package dev.lightforge.saathi.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import dev.lightforge.saathi.auth.TokenManager
import dev.lightforge.saathi.network.AegisApiClient
import dev.lightforge.saathi.network.SessionEndRequest
import dev.lightforge.saathi.network.SessionRequest
import dev.lightforge.saathi.voice.AudioPipeline
import dev.lightforge.saathi.voice.GeminiLiveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Default dialer InCallService — intercepts ALL incoming cellular calls.
 *
 * Requires the user to grant Saathi the default dialer role:
 *   - API 29+: [android.app.role.RoleManager.ROLE_DIALER]
 *   - API 26-28: [android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER]
 *
 * Once Saathi is the default dialer, Android binds this service for every call:
 *   - [onCallAdded]: fires when a call arrives (RINGING state) → we auto-answer + start Gemini
 *   - [onCallRemoved]: fires when call ends → cleanup session
 *
 * Audio architecture (inbound cellular):
 *   - [setMuted](true): silences the telephony uplink so caller doesn't hear room noise
 *   - [AudioPipeline] captures mic at 16kHz → [GeminiLiveClient] processes caller intent
 *   - Gemini 24kHz PCM → [AudioPipeline.playAudio] → local speaker (owner monitoring)
 *   - Backend is the SIP bridge: caller hears Gemini's voice via the backend, not this device
 *
 * For outbound/test calls where device IS the audio bridge, [SaathiConnection] handles
 * full bidirectional audio directly (no InCallService involvement).
 */
@AndroidEntryPoint
class SaathiInCallService : InCallService() {

    companion object {
        private const val TAG = "SaathiInCallSvc"
    }

    @Inject lateinit var apiClient: AegisApiClient
    @Inject lateinit var tokenManager: TokenManager
    @Inject lateinit var phoneAccountManager: PhoneAccountManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeSessions = ConcurrentHashMap<String, CellularCallSession>()

    // ------------------------------------------------------------------
    // InCallService callbacks
    // ------------------------------------------------------------------

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callerNumber = call.details?.handle?.schemeSpecificPart ?: "unknown"
        Log.i(TAG, "onCallAdded: state=${call.state} from=$callerNumber")

        @Suppress("DEPRECATION") val state = call.state
        if (state == Call.STATE_RINGING) {
            if (!tokenManager.hasToken()) {
                // Not paired — let call ring; owner can answer manually
                Log.i(TAG, "Device not paired — not intercepting call from $callerNumber")
                return
            }
            if (!tokenManager.isSaathiActive()) {
                // Saathi is deactivated — let the call ring normally (owner answers manually)
                Log.i(TAG, "Saathi inactive — not intercepting call from $callerNumber")
                return
            }
            handleIncomingCall(call, callerNumber)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        val callerNumber = call.details?.handle?.schemeSpecificPart ?: return
        Log.i(TAG, "onCallRemoved: $callerNumber")
        teardownSession(callerNumber)
    }

    override fun onDestroy() {
        scope.cancel()
        activeSessions.values.forEach { session ->
            session.pipeline?.stop()
            session.geminiClient?.disconnect()
        }
        activeSessions.clear()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Call handling
    // ------------------------------------------------------------------

    private fun handleIncomingCall(call: Call, callerNumber: String) {
        scope.launch(Dispatchers.Main) {
            try {
                // Auto-answer the cellular call
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                Log.i(TAG, "Answered call from $callerNumber")

                // Mute telephony uplink: caller hears silence from this device.
                // Gemini's voice reaches the caller via the backend SIP bridge.
                setMuted(true)

                // device_id may be absent if backend didn't return it during pairing;
                // send empty string — backend can derive it from the Bearer JWT.
                val deviceId = tokenManager.getDeviceId() ?: ""

                // Create backend session → ephemeral Gemini token + system instruction
                val sessionResp = try {
                    apiClient.createSession(
                        SessionRequest(
                            caller_number = callerNumber,
                            direction = "incoming",
                            device_id = deviceId
                        )
                    ).body()
                } catch (e: Exception) {
                    Log.e(TAG, "createSession failed for $callerNumber", e)
                    null
                }

                if (sessionResp == null) {
                    Log.e(TAG, "No session response — call will proceed without Gemini")
                    // Store minimal session so teardown still reports call end
                    activeSessions[callerNumber] = CellularCallSession(
                        call = call,
                        sessionId = null,
                        startTimeMs = System.currentTimeMillis()
                    )
                    return@launch
                }

                Log.i(TAG, "Session created: ${sessionResp.session_id}")
                Log.d(TAG, "gemini_ws_url host: ${try { java.net.URI(sessionResp.gemini_ws_url).host } catch (e: Exception) { "parse-error: ${e.message}" }}")

                // Start AudioPipeline + GeminiLiveClient
                val pipeline = AudioPipeline(applicationContext)
                val geminiClient = GeminiLiveClient()

                geminiClient.connect(
                    geminiWsUrl = sessionResp.gemini_ws_url,
                    systemInstruction = sessionResp.system_instruction,
                    onEvent = { event -> Log.i(TAG, "Gemini: $event") }
                )

                // Collect Gemini audio → local speaker (owner can monitor; caller hears via backend)
                val audioJob = launch(Dispatchers.IO) {
                    geminiClient.audioResponseFlow.collect { pcmData ->
                        pipeline.playAudio(pcmData)
                    }
                }

                // Capture mic → Gemini (16kHz PCM)
                val started = pipeline.start { pcmData, size ->
                    geminiClient.sendAudio(pcmData, size)
                }

                if (!started) {
                    Log.e(TAG, "AudioPipeline failed to start for $callerNumber")
                    audioJob.cancel()
                    geminiClient.disconnect()
                    activeSessions[callerNumber] = CellularCallSession(
                        call = call,
                        sessionId = sessionResp.session_id,
                        startTimeMs = System.currentTimeMillis()
                    )
                    return@launch
                }

                activeSessions[callerNumber] = CellularCallSession(
                    call = call,
                    sessionId = sessionResp.session_id,
                    pipeline = pipeline,
                    geminiClient = geminiClient,
                    audioJob = audioJob,
                    startTimeMs = System.currentTimeMillis()
                )

                Log.i(TAG, "Gemini session active for $callerNumber")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle incoming call from $callerNumber", e)
                try { call.disconnect() } catch (_: Exception) {}
            }
        }
    }

    private fun teardownSession(callerNumber: String) {
        val session = activeSessions.remove(callerNumber) ?: return
        scope.launch {
            session.audioJob?.cancel()
            session.pipeline?.stop()
            session.geminiClient?.disconnect()

            val sessionId = session.sessionId ?: return@launch
            try {
                val durationMs = System.currentTimeMillis() - session.startTimeMs
                apiClient.endSession(
                    sessionId,
                    SessionEndRequest(
                        duration_ms = durationMs,
                        tool_calls = 0,
                        disconnect_reason = "call_ended",
                        caller_number = callerNumber,
                        outcome = "unclear"
                    )
                )
                Log.i(TAG, "Session ended: $sessionId (${durationMs}ms)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report session end for $callerNumber", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Data
    // ------------------------------------------------------------------

    private data class CellularCallSession(
        val call: Call,
        val sessionId: String?,
        val startTimeMs: Long,
        val pipeline: AudioPipeline? = null,
        val geminiClient: GeminiLiveClient? = null,
        val audioJob: Job? = null
    )
}
