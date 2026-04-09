package dev.lightforge.saathi.telecom

import android.content.Context
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import dev.lightforge.saathi.voice.AudioPipeline
import dev.lightforge.saathi.voice.GeminiLiveClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Represents a single phone call connection managed by Saathi.
 *
 * Lifecycle:
 *   RINGING → ACTIVE   (onAnswer):     start AudioPipeline + GeminiLiveClient (or echo mode)
 *   ACTIVE  → ON_HOLD  (onHold):       pause AudioPipeline
 *   ON_HOLD → ACTIVE   (onUnhold):     resume AudioPipeline
 *   *       → DISCONNECTED (onDisconnect/onReject/onAbort): stop pipeline, close WebSocket
 *
 * Audio flow (production):
 *   Mic → AudioRecord → GeminiLiveClient.sendAudio() → Gemini WebSocket
 *   Gemini WebSocket → GeminiLiveClient.audioResponseFlow → AudioPipeline.playAudio() → speaker
 *
 * Audio flow (echo mode — spike test):
 *   Mic → AudioRecord → AudioPipeline.captureFlow → GeminiLiveClient echo loopback
 *   → audioResponseFlow → AudioPipeline.playAudio() → speaker
 *
 * Key configuration (set in init block, required by Telecom framework):
 *   - audioModeIsVoip = true         (audio routing hint)
 *   - PROPERTY_SELF_MANAGED          (we own the UI and audio routing)
 *   - CAPABILITY_HOLD / SUPPORT_HOLD
 */
class SaathiConnection(
    private val context: Context,
    val callerNumber: String,
    val callerName: String,
    private val sessionId: String?,
    private val isIncoming: Boolean,
    private val echoMode: Boolean = false
) : Connection() {

    companion object {
        private const val TAG = "SaathiConnection"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioPipeline: AudioPipeline? = null
    private var geminiClient: GeminiLiveClient? = null
    private var callStartTimeMs: Long = 0L

    init {
        // Required for self-managed VoIP calls
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE

        // Hint to Telecom that we want VoIP audio routing (Bluetooth headset, etc.)
        audioModeIsVoip = true
    }

    // ------------------------------------------------------------------
    // Telecom callbacks
    // ------------------------------------------------------------------

    override fun onAnswer() {
        Log.i(TAG, "[$callerNumber] Call answered (echoMode=$echoMode)")
        setActive()
        callStartTimeMs = System.currentTimeMillis()
        startVoiceSession()
    }

    override fun onAnswer(videoState: Int) = onAnswer()

    override fun onReject() {
        Log.i(TAG, "[$callerNumber] Call rejected")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        cleanup()
        destroy()
    }

    override fun onDisconnect() {
        Log.i(TAG, "[$callerNumber] Call disconnected")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        cleanup()
        destroy()
    }

    override fun onHold() {
        Log.i(TAG, "[$callerNumber] Call held")
        setOnHold()
        audioPipeline?.pause()
    }

    override fun onUnhold() {
        Log.i(TAG, "[$callerNumber] Call unheld")
        setActive()
        audioPipeline?.resume()
    }

    override fun onAbort() {
        Log.w(TAG, "[$callerNumber] Call aborted by system")
        setDisconnected(DisconnectCause(DisconnectCause.ERROR))
        cleanup()
        destroy()
    }

    // ------------------------------------------------------------------
    // Voice session lifecycle
    // ------------------------------------------------------------------

    /**
     * Starts the audio pipeline and Gemini (or echo) voice session.
     *
     * Production path:
     *   1. (TODO) Fetch ephemeral Gemini token from backend
     *   2. Connect GeminiLiveClient WebSocket
     *   3. Start AudioPipeline mic capture → stream to Gemini
     *   4. Collect audioResponseFlow → play through AudioTrack
     *
     * Echo/spike path (echoMode=true):
     *   1. Connect GeminiLiveClient in echo mode (no WebSocket)
     *   2. Start AudioPipeline — captureFlow feeds back into audioResponseFlow
     *   3. Caller hears their own voice echoed
     */
    private fun startVoiceSession() {
        scope.launch {
            try {
                val pipeline = AudioPipeline(context)
                audioPipeline = pipeline

                val client = GeminiLiveClient()
                geminiClient = client

                if (echoMode) {
                    // Echo mode: connect loopback BEFORE starting pipeline so the
                    // captureFlow subscriber is registered before first emit
                    client.connectEchoMode(pipeline)
                } else {
                    // Production: connect to Gemini Live WebSocket
                    // TODO: fetch ephemeral token from backend
                    //   val token = aegisApi.createSession(callerNumber).geminiToken
                    //   client.connect(token, systemInstruction = restaurantSystemPrompt)
                    Log.w(TAG, "[$callerNumber] Production Gemini connect not yet wired — use echo mode for spike")
                    client.connectEchoMode(pipeline) // fallback for spike
                }

                // Collect Gemini audio responses → play through speaker
                val audioJob = launch(Dispatchers.IO) {
                    client.audioResponseFlow.collect { pcmData ->
                        pipeline.playAudio(pcmData)
                    }
                }

                // Collect tool calls → relay to backend (TODO: wire ToolCallRelay)
                val toolJob = launch(Dispatchers.IO) {
                    client.toolCallFlow.collect { toolCall ->
                        Log.d(TAG, "[$callerNumber] Tool call: ${toolCall.functionName} (id=${toolCall.id})")
                        // TODO: route through ToolCallRelay → AegisApiClient
                    }
                }

                // Start mic capture — sends audio to Gemini (or echo flow)
                val started = pipeline.start(
                    onAudioCaptured = { pcmData, size ->
                        if (!echoMode) {
                            client.sendAudio(pcmData, size)
                        }
                        // In echo mode, captureFlow emission (done inside AudioPipeline) drives loopback
                    }
                )

                if (!started) {
                    Log.e(TAG, "[$callerNumber] AudioPipeline failed to start — ending call")
                    audioJob.cancel()
                    toolJob.cancel()
                    cleanup()
                    setDisconnected(DisconnectCause(DisconnectCause.ERROR))
                    destroy()
                    return@launch
                }

                Log.i(TAG, "[$callerNumber] Voice session active (echoMode=$echoMode)")
            } catch (e: Exception) {
                Log.e(TAG, "[$callerNumber] Failed to start voice session", e)
                cleanup()
                setDisconnected(DisconnectCause(DisconnectCause.ERROR))
                destroy()
            }
        }
    }

    /**
     * Releases all audio resources and closes the Gemini connection.
     */
    private fun cleanup() {
        val durationMs = if (callStartTimeMs > 0) System.currentTimeMillis() - callStartTimeMs else 0L
        Log.i(TAG, "[$callerNumber] Cleanup (duration=${durationMs}ms)")

        scope.launch {
            try {
                audioPipeline?.stop()
                audioPipeline = null

                geminiClient?.disconnect()
                geminiClient = null

                // TODO: POST session end telemetry to backend (durationMs, etc.)
            } catch (e: Exception) {
                Log.e(TAG, "[$callerNumber] Error during cleanup", e)
            } finally {
                scope.cancel()
            }
        }
    }
}
