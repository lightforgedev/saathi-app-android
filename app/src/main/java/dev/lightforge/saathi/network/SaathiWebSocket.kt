package dev.lightforge.saathi.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.lightforge.saathi.auth.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent WebSocket connection to the AEGIS backend for:
 * - Heartbeat ping/pong (30s interval)
 * - config.sync — backend pushes config updates (menu changes, hours, etc.)
 * - call.outbound — backend instructs app to place an outbound call
 * - device.revoke — force deauthentication (lost/stolen device)
 *
 * Reconnects automatically on disconnection with exponential backoff.
 * The connection is kept alive by [SaathiForegroundService].
 */
@Singleton
class SaathiWebSocket @Inject constructor(
    private val tokenManager: TokenManager,
    private val okHttpClient: OkHttpClient
) {

    companion object {
        private const val TAG = "SaathiWebSocket"
        private const val WS_PATH = "/ws/device"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val shouldReconnect = AtomicBoolean(true)
    private var reconnectAttempt = 0

    sealed class ServerMessage {
        data class ConfigSync(val data: String) : ServerMessage()
        data class OutboundCall(val targetNumber: String, val sessionId: String?) : ServerMessage()
        data object DeviceRevoked : ServerMessage()
    }

    private val _messages = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<ServerMessage> = _messages.asSharedFlow()

    /**
     * Connects to the backend WebSocket with the device token.
     * Call from SaathiForegroundService. Idempotent — no-op if already connected.
     */
    fun connect(baseWsUrl: String) {
        // Guard: don't create a second connection if one is already live or connecting.
        // SaathiForegroundService.onStartCommand() is called every time the service
        // is started (MainActivity launch + boot), so this must be idempotent.
        if (isConnected.get() || webSocket != null) {
            Log.d(TAG, "Already connected/connecting — skipping duplicate connect()")
            return
        }

        val token = tokenManager.getDeviceToken() ?: run {
            Log.w(TAG, "No device token — cannot connect WebSocket")
            return
        }

        shouldReconnect.set(true)
        reconnectAttempt = 0

        val url = "$baseWsUrl$WS_PATH?token=$token"
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected to backend")
                isConnected.set(true)
                reconnectAttempt = 0
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                isConnected.set(false)
                this@SaathiWebSocket.webSocket = null
                scheduleReconnect(baseWsUrl)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected.set(false)
                this@SaathiWebSocket.webSocket = null
                scheduleReconnect(baseWsUrl)
            }
        })
    }

    /**
     * Gracefully disconnects. Call when foreground service stops.
     */
    fun disconnect() {
        shouldReconnect.set(false)
        isConnected.set(false)
        webSocket?.close(1000, "App closing")
        webSocket = null
    }

    private fun startHeartbeat() {
        scope.launch {
            while (isConnected.get()) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (isConnected.get()) {
                    webSocket?.send("""{"type":"ping"}""")
                }
            }
        }
    }

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type")?.asString

            when (type) {
                "config.sync" -> {
                    _messages.tryEmit(ServerMessage.ConfigSync(text))
                    Log.d(TAG, "Config sync received")
                }
                "call.outbound" -> {
                    val target = json.get("target_number")?.asString ?: return
                    val sessionId = json.get("session_id")?.asString
                    _messages.tryEmit(ServerMessage.OutboundCall(target, sessionId))
                    Log.i(TAG, "Outbound call requested to $target")
                }
                "device.revoke" -> {
                    _messages.tryEmit(ServerMessage.DeviceRevoked)
                    Log.w(TAG, "Device revocation received")
                    disconnect()
                }
                "pong" -> {
                    // Heartbeat acknowledged
                }
                else -> {
                    Log.d(TAG, "Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WebSocket message", e)
        }
    }

    private fun scheduleReconnect(baseWsUrl: String) {
        if (!shouldReconnect.get()) return

        reconnectAttempt++
        val delayMs = (INITIAL_RECONNECT_DELAY_MS * (1 shl minOf(reconnectAttempt, 6)))
            .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        Log.i(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempt)")
        scope.launch {
            delay(delayMs)
            if (shouldReconnect.get()) {
                connect(baseWsUrl)
            }
        }
    }
}
