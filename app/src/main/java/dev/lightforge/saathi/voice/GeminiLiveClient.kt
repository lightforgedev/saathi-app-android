package dev.lightforge.saathi.voice

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * WebSocket client for the Gemini Live API (bidirectional audio streaming).
 *
 * Protocol (text frames only — Gemini Live uses JSON, not raw binary):
 *   Client → server: {"setup":{...}}, {"realtimeInput":{"mediaChunks":[{"mimeType":"audio/pcm;rate=16000","data":"<base64>"}]}}
 *   Server → client: {"setupComplete":{}}, {"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"<base64>"}}]}}},
 *                    {"toolCall":{"functionCalls":[{"id":"...","name":"...","args":{...}}]}}
 *
 * Audio directions:
 *   Mic  → Gemini : 16kHz 16-bit mono PCM, base64-encoded in JSON frames
 *   Gemini → Speaker: 24kHz 16-bit mono PCM, base64-encoded in JSON frames
 *
 * Echo mode:
 *   [connectEchoMode] skips the WebSocket entirely — mic audio is looped back into
 *   [audioResponseFlow] directly. Useful for testing AudioPipeline end-to-end without
 *   network or Gemini credentials.
 */
class GeminiLiveClient @Inject constructor() {

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC = 0L   // no timeout — streaming connection
        private const val PING_INTERVAL_SEC = 15L
        private const val DEFAULT_MODEL = "models/gemini-2.0-flash-live-001"
        private const val DEFAULT_VOICE = "Aoede"
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null

    @Volatile
    private var isConnected = false

    // Echo mode — loopback job instead of WebSocket
    private var echoLoopbackJob: Job? = null
    private var echoScope: CoroutineScope? = null

    // 24kHz PCM chunks from Gemini (or echo loopback) → AudioPipeline.playAudio()
    private val _audioResponseFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val audioResponseFlow: SharedFlow<ByteArray> = _audioResponseFlow.asSharedFlow()

    // Tool call requests from Gemini → ToolCallRelay
    private val _toolCallFlow = MutableSharedFlow<ToolCall>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val toolCallFlow: SharedFlow<ToolCall> = _toolCallFlow.asSharedFlow()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_SEC, TimeUnit.SECONDS)
            .build()
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Connects to the Gemini Live API.
     *
     * @param geminiWsUrl Full WebSocket URL from POST /session response (gemini_ws_url).
     *   The URL already contains the ephemeral token. Do NOT build this URL client-side.
     * @param modelId Gemini model (default: gemini-2.0-flash-live-001)
     * @param systemInstruction System prompt pre-built by backend (from /session response)
     * @param tools   Tool declarations returned by /session
     */
    fun connect(
        geminiWsUrl: String,
        modelId: String = DEFAULT_MODEL,
        systemInstruction: String? = null,
        tools: List<ToolDeclaration> = emptyList()
    ) {
        if (isConnected) {
            Log.w(TAG, "Already connected — disconnecting first")
            disconnect()
        }

        val request = Request.Builder().url(geminiWsUrl).build()

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to Gemini Live")
                isConnected = true
                sendSetupMessage(ws, modelId, systemInstruction, tools)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleTextMessage(text)
            }

            // Gemini Live uses JSON text frames only; binary frames are unexpected
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                Log.w(TAG, "Unexpected binary frame (${bytes.size} bytes) — ignoring")
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                ws.close(1000, null)
                isConnected = false
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                isConnected = false
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message} (http=${response?.code})", t)
                isConnected = false
            }
        })
    }

    /**
     * Echo mode — skips WebSocket entirely.
     *
     * Collects raw PCM from [AudioPipeline.captureFlow] and re-emits into
     * [audioResponseFlow]. Since the capture rate is 16kHz and playback is
     * configured for 24kHz, the echo will play at a slightly lower pitch —
     * acceptable for a loopback correctness test.
     *
     * Call this instead of [connect] when testing without Gemini credentials.
     */
    fun connectEchoMode(pipeline: AudioPipeline) {
        Log.i(TAG, "Starting echo/loopback mode (mic → speaker, no WebSocket)")
        isConnected = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        echoScope = scope
        echoLoopbackJob = scope.launch {
            pipeline.captureFlow.collect { pcmChunk ->
                _audioResponseFlow.emit(pcmChunk)
            }
        }
    }

    /**
     * Sends a PCM audio chunk to Gemini.
     * Audio must be 16kHz 16-bit mono (matches Gemini Live realtime input format).
     *
     * The PCM bytes are base64-encoded and wrapped in the Gemini realtimeInput JSON envelope.
     *
     * @param pcmData Raw PCM bytes
     * @param size    Number of valid bytes in pcmData (may be less than pcmData.size)
     */
    fun sendAudio(pcmData: ByteArray, size: Int) {
        if (!isConnected || webSocket == null) return

        val bytes = if (size < pcmData.size) pcmData.copyOf(size) else pcmData
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val json = buildString {
            append("""{"realtimeInput":{"mediaChunks":[{"mimeType":"audio/pcm;rate=16000","data":""")
            append('"')
            append(b64)
            append('"')
            append("}]}")
            append("}")
        }

        webSocket?.send(json)
    }

    /**
     * Sends a tool result back to Gemini after backend execution.
     *
     * @param callId  The id from the original tool_call message
     * @param result  JSON string with the tool result
     */
    fun sendToolResult(callId: String, result: String) {
        if (!isConnected || webSocket == null) return

        val json = """{"tool_response":{"function_responses":[{"id":"$callId","response":$result}]}}"""
        webSocket?.send(json)
        Log.d(TAG, "Tool result sent for id=$callId")
    }

    /**
     * Closes the WebSocket (or stops echo loopback).
     */
    fun disconnect() {
        isConnected = false

        // Stop echo loopback if active
        echoLoopbackJob?.cancel()
        echoLoopbackJob = null
        echoScope = null

        webSocket?.close(1000, "Session ended")
        webSocket = null
        Log.i(TAG, "Disconnected from Gemini Live")
    }

    // ------------------------------------------------------------------
    // Internal — message handling
    // ------------------------------------------------------------------

    /**
     * Sends the BidiGenerateContent setup message after WebSocket connection.
     * Configures model, generation_config, optional system_instruction, and tools.
     */
    private fun sendSetupMessage(
        ws: WebSocket,
        modelId: String,
        systemInstruction: String?,
        tools: List<ToolDeclaration>
    ) {
        val setupObj = JsonObject().apply {
            val setup = JsonObject()
            setup.addProperty("model", modelId)

            val genConfig = JsonObject()
            val modalities = com.google.gson.JsonArray()
            modalities.add("AUDIO")
            genConfig.add("response_modalities", modalities)

            val speechConfig = JsonObject()
            val voiceConfig = JsonObject()
            val prebuilt = JsonObject()
            prebuilt.addProperty("voice_name", DEFAULT_VOICE)
            voiceConfig.add("prebuilt_voice_config", prebuilt)
            speechConfig.add("voice_config", voiceConfig)
            genConfig.add("speech_config", speechConfig)
            setup.add("generation_config", genConfig)

            // Automatic activity detection (VAD) — let Gemini decide turn boundaries
            val realtimeInputConfig = JsonObject()
            val aad = JsonObject()
            aad.addProperty("disabled", false)
            realtimeInputConfig.add("automatic_activity_detection", aad)
            setup.add("realtime_input_config", realtimeInputConfig)

            if (systemInstruction != null) {
                val sysInstruction = JsonObject()
                val parts = com.google.gson.JsonArray()
                val part = JsonObject()
                part.addProperty("text", systemInstruction)
                parts.add(part)
                sysInstruction.add("parts", parts)
                setup.add("system_instruction", sysInstruction)
            }

            if (tools.isNotEmpty()) {
                val toolsArray = com.google.gson.JsonArray()
                val toolObj = JsonObject()
                val funcDecls = com.google.gson.JsonArray()
                tools.forEach { t ->
                    funcDecls.add(JsonParser.parseString(t.toJson()))
                }
                toolObj.add("function_declarations", funcDecls)
                toolsArray.add(toolObj)
                setup.add("tools", toolsArray)
            }

            add("setup", setup)
        }

        ws.send(gson.toJson(setupObj))
        Log.d(TAG, "Setup message sent (model=$modelId, tools=${tools.size})")
    }

    /**
     * Dispatches Gemini JSON messages to the appropriate handler.
     *
     * Expected top-level keys:
     *   "setupComplete" — session ready
     *   "serverContent" — contains model turn with audio/text parts
     *   "toolCall"      — function call request
     *   "toolCallCancellation" — cancel pending tool call
     */
    private fun handleTextMessage(text: String) {
        try {
            val root = JsonParser.parseString(text).asJsonObject

            when {
                root.has("setupComplete") -> {
                    Log.i(TAG, "Gemini session setup complete")
                }

                root.has("serverContent") -> {
                    handleServerContent(root.getAsJsonObject("serverContent"))
                }

                root.has("toolCall") -> {
                    handleToolCall(root.getAsJsonObject("toolCall"))
                }

                root.has("toolCallCancellation") -> {
                    Log.d(TAG, "Tool call cancelled: ${text.take(200)}")
                }

                else -> {
                    Log.d(TAG, "Unhandled Gemini message key: ${root.keySet()} (${text.take(100)})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Gemini message: ${text.take(200)}", e)
        }
    }

    /**
     * Parses serverContent and extracts audio chunks or text from modelTurn.
     *
     * Audio path:
     *   serverContent.modelTurn.parts[].inlineData.data → base64 decode → emit to audioResponseFlow
     *
     * Also handles:
     *   serverContent.turnComplete → log (VAD turn boundary)
     *   serverContent.interrupted  → log (barge-in detected)
     */
    private fun handleServerContent(serverContent: JsonObject) {
        val turnComplete = serverContent.get("turnComplete")?.asBoolean ?: false
        if (turnComplete) {
            Log.d(TAG, "Gemini turn complete")
            return
        }

        val interrupted = serverContent.get("interrupted")?.asBoolean ?: false
        if (interrupted) {
            Log.d(TAG, "Gemini turn interrupted (barge-in)")
            return
        }

        val modelTurn = serverContent.getAsJsonObject("modelTurn") ?: return
        val parts = modelTurn.getAsJsonArray("parts") ?: return

        for (partElement in parts) {
            val part = partElement.asJsonObject

            val inlineData = part.getAsJsonObject("inlineData")
            if (inlineData != null) {
                val mimeType = inlineData.get("mimeType")?.asString ?: ""
                val b64Data = inlineData.get("data")?.asString ?: continue

                if (mimeType.startsWith("audio/")) {
                    val pcmBytes = Base64.decode(b64Data, Base64.DEFAULT)
                    _audioResponseFlow.tryEmit(pcmBytes)
                    Log.v(TAG, "Audio chunk: ${pcmBytes.size} bytes ($mimeType)")
                }
                continue
            }

            // Text part — log for debugging (transcript, etc.)
            val text = part.get("text")?.asString
            if (text != null) {
                Log.v(TAG, "Model text: ${text.take(120)}")
            }
        }
    }

    /**
     * Parses a toolCall message and emits to [toolCallFlow].
     *
     * Expected structure:
     *   toolCall.functionCalls[].{id, name, args}
     */
    private fun handleToolCall(toolCallObj: JsonObject) {
        val functionCalls = toolCallObj.getAsJsonArray("functionCalls") ?: return

        for (fcElement in functionCalls) {
            try {
                val fc = fcElement.asJsonObject
                val id = fc.get("id")?.asString ?: ""
                val name = fc.get("name")?.asString ?: continue
                val argsObj = fc.getAsJsonObject("args")

                val args: Map<String, Any?> = if (argsObj != null) {
                    argsObj.entrySet().associate { (k, v) ->
                        k to when {
                            v.isJsonPrimitive -> {
                                val prim = v.asJsonPrimitive
                                when {
                                    prim.isBoolean -> prim.asBoolean
                                    prim.isNumber  -> prim.asDouble
                                    else           -> prim.asString
                                }
                            }
                            v.isJsonNull -> null
                            else         -> v.toString()
                        }
                    }
                } else emptyMap()

                val toolCall = ToolCall(id = id, functionName = name, arguments = args)
                val emitted = _toolCallFlow.tryEmit(toolCall)
                Log.i(TAG, "Tool call: $name (id=$id, emitted=$emitted)")
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing function call entry", e)
            }
        }
    }
}

// ------------------------------------------------------------------
// Data classes
// ------------------------------------------------------------------

/** A tool call request from Gemini. */
data class ToolCall(
    val id: String,
    val functionName: String,
    val arguments: Map<String, Any?>
)

/** A tool function declaration to register with Gemini. */
data class ToolDeclaration(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameter>
) {
    fun toJson(): String {
        val paramsJson = parameters.entries.joinToString(",") { (key, param) ->
            """"$key":{"type":"${param.type}","description":"${param.description}"}"""
        }
        return """{"name":"$name","description":"${description.replace("\"","'")}","parameters":{"type":"object","properties":{$paramsJson}}}"""
    }
}

data class ToolParameter(
    val type: String,
    val description: String
)
