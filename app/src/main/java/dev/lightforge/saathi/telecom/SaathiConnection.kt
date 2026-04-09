package dev.lightforge.saathi.telecom

import android.content.Context
import android.os.Build
import android.telecom.Connection
import android.telecom.ConnectionService
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
 * 1. Created by [SaathiConnectionService] in RINGING (incoming) or DIALING (outgoing) state
 * 2. On answer/connect: transitions to ACTIVE, starts Gemini voice session + audio pipeline
 * 3. On disconnect: tears down Gemini session, reports telemetry, releases audio resources
 *
 * Audio flow:
 * - Mic (AudioRecord) -> PCM frames -> GeminiLiveClient WebSocket -> Gemini API
 * - Gemini API -> audio response frames -> AudioTrack -> call audio output
 */
class SaathiConnection(
    private val context: Context,
    val callerNumber: String,
    val callerName: String,
    private val sessionId: String?,
    private val isIncoming: Boolean
) : Connection() {

    companion object {
        private const val TAG = "SaathiConnection"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var audioPipeline: AudioPipeline? = null
    private var geminiClient: GeminiLiveClient? = null
    private var callStartTimeMs: Long = 0L

    init {
        // Configure connection properties
        connectionProperties = PROPERTY_SELF_MANAGED
        connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE

        // Set audio mode hint for VoIP
        audioModeIsVoip = true
    }

    /**
     * Called when the user answers an incoming call.
     * Transitions to ACTIVE and starts the Gemini voice session.
     */
    override fun onAnswer() {
        Log.i(TAG, "[$callerNumber] Call answered")
        setActive()
        callStartTimeMs = System.currentTimeMillis()
        startVoiceSession()
    }

    /**
     * Called when the user answers with a specific video state.
     * We only support audio calls, so delegate to onAnswer().
     */
    override fun onAnswer(videoState: Int) {
        onAnswer()
    }

    /**
     * Called when the user rejects an incoming call.
     */
    override fun onReject() {
        Log.i(TAG, "[$callerNumber] Call rejected")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        cleanup()
        destroy()
    }

    /**
     * Called when the call is disconnected (user hangs up).
     */
    override fun onDisconnect() {
        Log.i(TAG, "[$callerNumber] Call disconnected by user")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        cleanup()
        destroy()
    }

    /**
     * Called when the call is placed on hold.
     */
    override fun onHold() {
        Log.i(TAG, "[$callerNumber] Call held")
        setOnHold()
        audioPipeline?.pause()
    }

    /**
     * Called when the call is taken off hold.
     */
    override fun onUnhold() {
        Log.i(TAG, "[$callerNumber] Call unheld")
        setActive()
        audioPipeline?.resume()
    }

    /**
     * Called when the call is aborted by the system.
     */
    override fun onAbort() {
        Log.w(TAG, "[$callerNumber] Call aborted by system")
        setDisconnected(DisconnectCause(DisconnectCause.ERROR))
        cleanup()
        destroy()
    }

    /**
     * Starts the Gemini Live voice session and audio pipeline.
     *
     * Flow:
     * 1. Request ephemeral Gemini token from backend (POST /session)
     * 2. Open WebSocket to Gemini Live API with token
     * 3. Start AudioRecord (mic capture) -> stream PCM to Gemini
     * 4. Receive Gemini audio response -> play via AudioTrack
     */
    private fun startVoiceSession() {
        scope.launch {
            try {
                // TODO: Request session from backend via AegisApiClient
                //   val sessionResponse = aegisApi.createSession(callerNumber)
                //   val geminiToken = sessionResponse.geminiToken
                //   val config = sessionResponse.config

                Log.i(TAG, "[$callerNumber] Starting Gemini voice session (sessionId=$sessionId)")

                // Initialize Gemini WebSocket client
                geminiClient = GeminiLiveClient().also { client ->
                    // TODO: Connect with ephemeral token
                    // client.connect(geminiToken, config)
                }

                // Initialize audio pipeline: mic -> Gemini, Gemini -> speaker
                audioPipeline = AudioPipeline(context).also { pipeline ->
                    pipeline.start(
                        onAudioCaptured = { pcmData, size ->
                            // Stream mic audio to Gemini
                            geminiClient?.sendAudio(pcmData, size)
                        }
                    )
                }

                // Listen for Gemini audio responses
                geminiClient?.let { client ->
                    launch {
                        client.audioResponseFlow.collect { audioData ->
                            audioPipeline?.playAudio(audioData)
                        }
                    }

                    // Listen for tool calls from Gemini
                    launch {
                        client.toolCallFlow.collect { toolCall ->
                            // TODO: Relay tool call to backend via AegisApiClient
                            //   val result = aegisApi.executeTool(sessionId, toolCall)
                            //   client.sendToolResult(result)
                            Log.d(TAG, "[$callerNumber] Tool call: ${toolCall.functionName}")
                        }
                    }
                }

                Log.i(TAG, "[$callerNumber] Voice session active")
            } catch (e: Exception) {
                Log.e(TAG, "[$callerNumber] Failed to start voice session", e)
                // TODO: Play error audio to caller, report to backend
            }
        }
    }

    /**
     * Cleans up all resources when the call ends.
     */
    private fun cleanup() {
        val durationMs = if (callStartTimeMs > 0) {
            System.currentTimeMillis() - callStartTimeMs
        } else {
            0L
        }

        Log.i(TAG, "[$callerNumber] Cleanup (duration=${durationMs}ms)")

        scope.launch {
            try {
                // Stop audio pipeline
                audioPipeline?.stop()
                audioPipeline = null

                // Close Gemini connection
                geminiClient?.disconnect()
                geminiClient = null

                // TODO: Report session end to backend
                //   aegisApi.endSession(sessionId, durationMs, telemetry)
            } catch (e: Exception) {
                Log.e(TAG, "[$callerNumber] Error during cleanup", e)
            } finally {
                scope.cancel()
            }
        }
    }
}
