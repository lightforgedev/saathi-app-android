package dev.lightforge.saathi.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the AEGIS Saathi backend API.
 *
 * Base URL: BuildConfig.AEGIS_API_BASE_URL + "/api/v1/saathi/"
 * Auth: Bearer device_token (JWT) added by [AuthInterceptor]
 *
 * All endpoints are suspend functions for coroutine integration.
 */
interface AegisApiClient {

    /**
     * Step 1 of device pairing: send phone number, receive OTP via SMS.
     *
     * Request: { "phone_number": "+91XXXXXXXXXX", "device_name": "Samsung Galaxy A14" }
     * Response: { "pairing_id": "uuid", "otp_sent": true, "expires_in": 300 }
     */
    @POST("devices/pair")
    suspend fun startPairing(@Body request: PairRequest): Response<PairResponse>

    /**
     * Step 2 of device pairing: verify OTP, receive device token (JWT).
     *
     * Request: { "pairing_id": "uuid", "otp": "123456" }
     * Response: { "device_token": "jwt...", "org_id": "uuid", "restaurant_name": "..." }
     */
    @POST("devices/verify")
    suspend fun verifyPairing(@Body request: VerifyRequest): Response<VerifyResponse>

    /**
     * Creates a voice session for an incoming/outgoing call.
     * Returns an ephemeral Gemini token and session configuration.
     *
     * Request: { "caller_number": "+91...", "direction": "incoming" }
     * Response: { "session_id": "uuid", "gemini_token": "...", "system_instruction": "...",
     *             "tools": [...], "config": { "language": "hi", ... } }
     */
    @POST("session")
    suspend fun createSession(@Body request: SessionRequest): Response<SessionResponse>

    /**
     * Executes a tool call during an active voice session.
     * The backend runs the tool against the restaurant's data and returns the result.
     *
     * Request: { "tool_name": "lookup_menu", "arguments": { "query": "paneer" } }
     * Response: raw JSON result to pass back to Gemini
     */
    @POST("session/{sessionId}/tool")
    suspend fun executeTool(
        @Path("sessionId") sessionId: String,
        @Body request: RequestBody
    ): Response<ResponseBody>

    /**
     * Reports session end with telemetry data.
     *
     * Request: { "duration_ms": 45000, "tool_calls": 3, "disconnect_reason": "caller_hangup" }
     */
    @POST("session/{sessionId}/end")
    suspend fun endSession(
        @Path("sessionId") sessionId: String,
        @Body request: SessionEndRequest
    ): Response<Unit>

    /**
     * Full config sync: menu items, hours, reservation slots, restaurant metadata.
     * Called at startup and periodically to refresh local cache.
     */
    @GET("config")
    suspend fun getConfig(): Response<ConfigResponse>

    /**
     * Home screen stats: today's calls handled, reservations made, last call duration.
     */
    @GET("stats")
    suspend fun getStats(): Response<StatsResponse>

    /**
     * Paginated call history. Cursor-based pagination via `before` (ISO8601 timestamp).
     */
    @GET("calls")
    suspend fun getCalls(
        @Query("limit") limit: Int = 20,
        @Query("before") before: String? = null,
        @Query("date") date: String? = null
    ): Response<CallsResponse>

    /**
     * Partial update of call handling mode, business hours, language, or notifications.
     */
    @PATCH("settings")
    suspend fun updateSettings(@Body request: UpdateSettingsRequest): Response<UpdateSettingsResponse>
}

/**
 * Extension: builds request body from tool name + arguments and calls executeTool.
 */
suspend fun AegisApiClient.executeTool(
    sessionId: String,
    toolName: String,
    arguments: Map<String, Any?>
): Response<ResponseBody> {
    val json = buildString {
        append("""{"tool_name":"$toolName","arguments":""")
        append(com.google.gson.Gson().toJson(arguments))
        append("}")
    }
    val body = json.toRequestBody("application/json".toMediaType())
    return executeTool(sessionId, body)
}

// --- Request/Response DTOs ---

data class PairRequest(
    val phone_number: String,
    val device_name: String
)

data class PairResponse(
    val pairing_id: String,
    val otp_sent: Boolean,
    val expires_in: Int
)

data class VerifyRequest(
    val pairing_id: String,
    val otp: String
)

data class VerifyResponse(
    val device_token: String,
    val org_id: String,
    val restaurant: RestaurantSummaryDto,
    val device_id: String? = null,
    val token_expires_at: String? = null
)

data class RestaurantSummaryDto(
    val name: String,
    val phone: String? = null,
    val city: String? = null
)

data class SessionRequest(
    val caller_number: String,
    val direction: String, // "incoming" or "outgoing"
    val device_id: String
)

data class SessionResponse(
    val session_id: String,
    val session_token: String,
    val gemini_token: String,
    val gemini_ws_url: String,
    val system_instruction: String,
    val tools: List<ToolDeclarationDto> = emptyList(),
    val config: SessionConfig
)

data class ToolDeclarationDto(
    val name: String,
    val description: String,
    // Backend sends parameters as a JSON-encoded string or object — use JsonElement to avoid
    // Gson throwing "Expected BEGIN_OBJECT but was STRING" when the shape varies.
    val parameters: com.google.gson.JsonElement? = null
)

data class SessionConfig(
    val language: String,
    val voice_name: String,
    val max_duration_seconds: Int = 180,
    val vad_enabled: Boolean = true
)

data class ToolCallRequest(
    val tool_name: String,
    val tool_call_id: String,
    val arguments: Map<String, Any?>
)

data class ToolCallResponse(
    val tool_call_id: String,
    val result: Any?
)

data class SessionEndRequest(
    val duration_ms: Long,
    val tool_calls: Int,
    val disconnect_reason: String
)

data class ConfigResponse(
    val restaurant: RestaurantConfigDto,
    val menu_items: List<MenuItemDto>,
    val reservation_slots: List<ReservationSlotDto>? = null,
    val upcoming_reservations: List<ReservationSlotDto>? = null,
    val synced_at: String? = null
)

data class RestaurantConfigDto(
    val name: String,
    val phone: String,
    val address: String,
    val city: String? = null,
    val hours: Map<String, String>? = null, // day -> "HH:MM-HH:MM" (legacy field)
    val business_hours: Map<String, BusinessHourDto>? = null,
    val languages: List<String>? = null,
    val primary_language: String? = null,
    val call_mode: String? = null,
    val greeting: String? = null
)

data class MenuItemDto(
    val id: String,
    val name: String,
    val name_hindi: String?,
    val description: String?,
    val price: Double,
    val category: String,
    val is_available: Boolean,
    val is_veg: Boolean
)

data class ReservationSlotDto(
    val date: String,
    val time: String,
    val party_size_max: Int,
    val available: Boolean
)

// --- Stats DTOs ---

data class StatsResponse(
    val today: DayStats,
    val date: String
)

data class DayStats(
    val calls_handled: Int,
    val reservations_made: Int,
    val last_call_duration_seconds: Int?
)

// --- Call Log DTOs ---

data class CallsResponse(
    val calls: List<CallRecord>,
    val next_cursor: String?,
    val has_more: Boolean
)

data class CallRecord(
    val id: String,
    val caller_number: String,
    val caller_name: String?,
    val direction: String,
    val duration_seconds: Int,
    val outcome: String,
    val summary: String?,
    val started_at: String,
    val ended_at: String?
)

// --- Settings DTOs ---

data class UpdateSettingsRequest(
    val call_mode: String? = null,
    val notifications: NotificationSettings? = null,
    val primary_language: String? = null,
    val greeting: String? = null,
    val business_hours: Map<String, BusinessHourDto>? = null
)

data class NotificationSettings(
    val whatsapp_daily_summary: Boolean? = null,
    val per_call_notification: Boolean? = null
)

data class BusinessHourDto(
    val open: String,
    val close: String,
    val closed: Boolean
)

data class UpdateSettingsResponse(
    val ok: Boolean,
    val updated_at: String
)
