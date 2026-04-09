package dev.lightforge.saathi.voice

import android.util.Log
import com.google.gson.Gson
import dev.lightforge.saathi.network.AegisApiClient
import dev.lightforge.saathi.network.ToolCallRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intercepts tool call requests from Gemini and relays them to the AEGIS backend.
 *
 * Protocol:
 *   1. Gemini emits toolCall { functionCalls: [{ id, name, args }] }
 *   2. GeminiLiveClient emits ToolCall to toolCallFlow
 *   3. ToolCallRelay calls POST /session/:id/tool with { tool_name, tool_call_id, arguments }
 *      — requires BOTH device_token (AuthInterceptor) and X-Session-Token header
 *   4. Backend executes the tool (e.g., look up menu, make reservation)
 *   5. Response.result passed back to Gemini via sendToolResult()
 *
 * All tool execution happens server-side — the app is a transparent relay.
 * Restaurant data never flows through the client.
 */
@Singleton
class ToolCallRelay @Inject constructor(
    private val aegisApi: AegisApiClient
) {

    companion object {
        private const val TAG = "ToolCallRelay"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    /**
     * Starts listening for tool calls from a Gemini session and relaying them.
     *
     * @param geminiClient   Active Gemini WebSocket client
     * @param sessionId      Session ID from POST /session response
     * @param sessionToken   Session token from POST /session response (X-Session-Token header)
     */
    fun startRelaying(
        geminiClient: GeminiLiveClient,
        sessionId: String,
        sessionToken: String
    ) {
        scope.launch {
            geminiClient.toolCallFlow.collect { toolCall ->
                Log.i(TAG, "Relaying tool: ${toolCall.functionName} (id=${toolCall.id})")
                try {
                    val request = ToolCallRequest(
                        tool_name = toolCall.functionName,
                        tool_call_id = toolCall.id,
                        arguments = toolCall.arguments
                    )

                    val response = aegisApi.executeTool(
                        sessionId = sessionId,
                        sessionToken = sessionToken,
                        request = request
                    )

                    if (response.isSuccessful) {
                        val body = response.body()
                        // Serialize the result back to JSON for Gemini
                        val resultJson = if (body?.result != null) {
                            gson.toJson(body.result)
                        } else {
                            "{}"
                        }
                        geminiClient.sendToolResult(toolCall.id, resultJson)
                        Log.d(TAG, "Tool result sent for ${toolCall.functionName}")
                    } else {
                        val errorResult = """{"error":"Tool execution failed: ${response.code()} ${response.message()}"}"""
                        geminiClient.sendToolResult(toolCall.id, errorResult)
                        Log.e(TAG, "Tool failed: ${response.code()} ${response.message()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error relaying ${toolCall.functionName}", e)
                    val errorResult = """{"error":"${e.message?.replace("\"","'")}"}"""
                    geminiClient.sendToolResult(toolCall.id, errorResult)
                }
            }
        }
    }
}
