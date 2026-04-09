package dev.lightforge.saathi.voice

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * WebSocket client for Gemini Live API.
 *
 * Handles bidirectional audio streaming:
 * - Sends PCM audio frames from the device microphone to Gemini
 * - Receives audio response frames from Gemini for playback
 * - Receives tool call requests from Gemini for backend relay
 *
 * Protocol:
 * - Binary frames: raw PCM audio (16-bit, 16kHz mono)
 * - Text frames: JSON messages (setup, tool calls, tool results, session control)
 *
 * The ephemeral token is single-use and expires in 5 minutes.
 * Audio is never stored — streamed directly between mic/speaker and WebSocket.
 */
class GeminiLiveClient @Inject constructor() {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val GEMINI_LIVE_WS_URL = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 0L // No timeout for streaming
        private const val PING_INTERVAL_SEC = 15L
    }

    private var webSocket: WebSocket? = null
    private var isConnected = false

    // Audio response frames from Gemini -> AudioPipeline for playback
    private val _audioResponseFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioResponseFlow: SharedFlow<ByteArray> = _audioResponseFlow.asSharedFlow()

    // Tool call requests from Gemini -> ToolCallRelay for backend execution
    private val _toolCallFlow = MutableSharedFlow<ToolCall>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val toolCallFlow: SharedFlow<ToolCall> = _toolCallFlow.asSharedFlow()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Connects to the Gemini Live API with an ephemeral token.
     *
     * @param token Ephemeral API key from backend (POST /session)
     * @param modelId Gemini model to use (e.g., "gemini-2.0-flash-live")
     * @param systemInstruction System prompt for the voice agent
     * @param tools List of tool declarations available to Gemini
     */
    fun connect(
        token: String,
        modelId: String = "gemini-2.0-flash-live",
        systemInstruction: String? = null,
        tools: List<ToolDeclaration> = emptyList()
    ) {
        if (isConnected) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        val url = "$GEMINI_LIVE_WS_URL?key=$token"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to Gemini Live")
                isConnected = true
                sendSetupMessage(webSocket, modelId, systemInstruction, tools)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary frame = audio response from Gemini
                _audioResponseFlow.tryEmit(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
                isConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                isConnected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                isConnected = false
                // TODO: Notify connection failure -> trigger reconnect or end call
            }
        })
    }

    /**
     * Sends PCM audio data to Gemini.
     * Called from AudioPipeline's mic capture callback on the IO thread.
     */
    fun sendAudio(pcmData: ByteArray, size: Int) {
        if (!isConnected) return
        val data = if (size < pcmData.size) pcmData.copyOf(size) else pcmData
        webSocket?.send(data.toByteString(0, data.size))
    }

    /**
     * Sends a tool call result back to Gemini after backend execution.
     */
    fun sendToolResult(callId: String, result: String) {
        if (!isConnected) return

        val json = """
            {
                "tool_response": {
                    "function_responses": [{
                        "id": "$callId",
                        "response": $result
                    }]
                }
            }
        """.trimIndent()

        webSocket?.send(json)
        Log.d(TAG, "Sent tool result for call $callId")
    }

    /**
     * Gracefully disconnects the WebSocket.
     */
    fun disconnect() {
        isConnected = false
        webSocket?.close(1000, "Session ended")
        webSocket = null
        Log.i(TAG, "Disconnected from Gemini Live")
    }

    /**
     * Sends the initial setup message after WebSocket connection.
     * Configures the model, system instruction, voice, and available tools.
     */
    private fun sendSetupMessage(
        ws: WebSocket,
        modelId: String,
        systemInstruction: String?,
        tools: List<ToolDeclaration>
    ) {
        // TODO: Build proper JSON setup message with:
        //  - model selection
        //  - generation_config (response_modalities: ["AUDIO"], speech_config)
        //  - system_instruction
        //  - tools declarations
        val setupJson = buildString {
            append("""{"setup":{"model":"models/$modelId"""")
            append(""","generation_config":{"response_modalities":["AUDIO"]""")
            append(""","speech_config":{"voice_config":{"prebuilt_voice_config":{"voice_name":"Aoede"}}}}""")
            if (systemInstruction != null) {
                append(""","system_instruction":{"parts":[{"text":"${systemInstruction.replace("\"", "\\\"")}"}]}""")
            }
            if (tools.isNotEmpty()) {
                append(""","tools":[{"function_declarations":[""")
                append(tools.joinToString(",") { it.toJson() })
                append("]}]")
            }
            append("}}")
        }

        ws.send(setupJson)
        Log.d(TAG, "Setup message sent (model=$modelId, tools=${tools.size})")
    }

    /**
     * Handles JSON text messages from Gemini.
     * Could be: setupComplete, toolCall, serverContent, sessionUpdate
     */
    private fun handleTextMessage(text: String) {
        // TODO: Parse JSON properly with Gson
        when {
            text.contains("\"toolCall\"") || text.contains("\"function_call\"") -> {
                // TODO: Parse tool call and emit to flow
                Log.d(TAG, "Received tool call: ${text.take(200)}")
                // val toolCall = parseToolCall(text)
                // _toolCallFlow.tryEmit(toolCall)
            }
            text.contains("\"setupComplete\"") -> {
                Log.i(TAG, "Gemini session setup complete")
            }
            text.contains("\"serverContent\"") -> {
                // Server content (text transcript, etc.) — log for debugging
                Log.v(TAG, "Server content: ${text.take(100)}")
            }
            else -> {
                Log.d(TAG, "Unhandled message: ${text.take(100)}")
            }
        }
    }
}

/**
 * Represents a tool call request from Gemini.
 */
data class ToolCall(
    val id: String,
    val functionName: String,
    val arguments: Map<String, Any?>
)

/**
 * Represents a tool declaration to register with Gemini.
 */
data class ToolDeclaration(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameter>
) {
    fun toJson(): String {
        val paramsJson = parameters.entries.joinToString(",") { (key, param) ->
            """"$key":{"type":"${param.type}","description":"${param.description}"}"""
        }
        return """{"name":"$name","description":"$description","parameters":{"type":"object","properties":{$paramsJson}}}"""
    }
}

data class ToolParameter(
    val type: String,
    val description: String
)
