package dev.lightforge.saathi.voice

import android.util.Log
import dev.lightforge.saathi.network.AegisApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intercepts tool call requests from Gemini and relays them to the AEGIS backend.
 *
 * Flow:
 * 1. Gemini sends a tool_call message via WebSocket
 * 2. GeminiLiveClient emits ToolCall to toolCallFlow
 * 3. ToolCallRelay picks it up, sends to backend POST /tool
 * 4. Backend executes the tool (e.g., look up menu, make reservation)
 * 5. Result returned to Gemini via sendToolResult()
 *
 * All tool execution happens server-side. The app is a transparent relay.
 * This ensures the restaurant's data stays secure and consistent.
 */
@Singleton
class ToolCallRelay @Inject constructor(
    private val aegisApi: AegisApiClient
) {

    companion object {
        private const val TAG = "ToolCallRelay"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Starts listening for tool calls from a Gemini session and relaying them.
     *
     * @param geminiClient The active Gemini WebSocket client
     * @param sessionId The backend session ID for this call
     */
    fun startRelaying(geminiClient: GeminiLiveClient, sessionId: String) {
        scope.launch {
            geminiClient.toolCallFlow.collect { toolCall ->
                Log.i(TAG, "Relaying tool call: ${toolCall.functionName} (id=${toolCall.id})")
                try {
                    val response = aegisApi.executeTool(
                        sessionId = sessionId,
                        toolName = toolCall.functionName,
                        arguments = toolCall.arguments
                    )

                    if (response.isSuccessful) {
                        val resultJson = response.body()?.string() ?: "{}"
                        geminiClient.sendToolResult(toolCall.id, resultJson)
                        Log.d(TAG, "Tool result sent for ${toolCall.functionName}")
                    } else {
                        val errorResult = """{"error":"Tool execution failed: ${response.code()}"}"""
                        geminiClient.sendToolResult(toolCall.id, errorResult)
                        Log.e(TAG, "Tool execution failed: ${response.code()} ${response.message()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error relaying tool call ${toolCall.functionName}", e)
                    val errorResult = """{"error":"${e.message?.replace("\"", "'")}"}"""
                    geminiClient.sendToolResult(toolCall.id, errorResult)
                }
            }
        }
    }
}
