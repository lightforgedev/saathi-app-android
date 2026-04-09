package dev.lightforge.saathi.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit interface for the AEGIS Saathi backend API.
 *
 * Base URL: BuildConfig.AEGIS_API_BASE_URL + "/api/v1/saathi/"
 * Auth: Bearer device_token (JWT) added by [AuthInterceptor] for all endpoints
 *       except pair/verify (no auth required for those).
 *
 * Contract: docs/api/SAATHI_DEVICE_API.md
 */
interface AegisApiClient {

    /**
     * Step 1 of device pairing: send phone number, backend sends OTP via SMS.
     *
     * No auth required.
     * Request:  { "phone_number": "+91XXXXXXXXXX", "device_name": "Samsung Galaxy A14" }
     * Response: { "pairing_id": "uuid", "expires_in": 300 }
     */
    @POST("devices/pair")
    suspend fun startPairing(@Body request: PairRequest): Response<PairResponse>

    /**
     * Step 2 of device pairing: verify OTP, receive 90-day device token.
     *
     * No auth required.
     * Request:  { "pairing_id": "uuid", "otp": "123456" }
     * Response: { "device_token": "jwt...", "device_id": "uuid", "org_id": "uuid", ... }
     */
    @POST("devices/verify")
    suspend fun verifyPairing(@Body request: VerifyRequest): Response<VerifyResponse>

    /**
     * Refresh device token before expiry (when < 7 days remain).
     *
     * Auth: Bearer device_token (the one being refreshed)
     */
    @POST("devices/refresh")
    suspend fun refreshToken(): Response<RefreshResponse>

    /**
     * Create a voice session at the start of a call.
     * Returns ephemeral Gemini token, full WebSocket URL, and all session config.
     *
     * Request:  { "caller_number": "+91...", "direction": "incoming", "device_id": "uuid" }
     * Response: { "session_id", "session_token", "gemini_token", "gemini_ws_url",
     *             "system_instruction", "tools", "config" }
     */
    @POST("session")
    suspend fun createSession(@Body request: SessionRequest): Response<SessionResponse>

    /**
     * Execute a tool call during an active voice session.
     * Requires TWO auth tokens: device_token (via AuthInterceptor) + session_token (header).
     *
     * Request:  { "tool_name": "...", "tool_call_id": "fc-abc123", "arguments": {...} }
     * Response: { "tool_call_id": "fc-abc123", "result": {...} }
     */
    @POST("session/{sessionId}/tool")
    suspend fun executeTool(
        @Path("sessionId") sessionId: String,
        @Header("X-Session-Token") sessionToken: String,
        @Body request: ToolCallRequest
    ): Response<ToolCallResponse>

    /**
     * Report session end for usage tracking and owner notification.
     *
     * Request:  { "duration_ms": 45000, "tool_calls": 3, "disconnect_reason": "...",
     *             "outcome": "...", "summary": "..." }
     */
    @POST("session/{sessionId}/end")
    suspend fun endSession(
        @Path("sessionId") sessionId: String,
        @Body request: SessionEndRequest
    ): Response<ResponseBody>

    /**
     * Full config sync: restaurant metadata, menu items, upcoming reservations.
     * Refresh on startup and when config.sync is received on the device WebSocket.
     */
    @GET("config")
    suspend fun getConfig(): Response<ConfigResponse>
}

// --- Request DTOs ---

data class PairRequest(
    val phone_number: String,
    val device_name: String
)

data class VerifyRequest(
    val pairing_id: String,
    val otp: String
)

data class SessionRequest(
    val caller_number: String,
    val direction: String,  // "incoming" | "outgoing"
    val device_id: String
)

data class ToolCallRequest(
    val tool_name: String,
    val tool_call_id: String,    // id from Gemini's functionCalls[].id — echo back in tool_response
    val arguments: Map<String, Any?>
)

data class SessionEndRequest(
    val duration_ms: Long,
    val tool_calls: Int,
    val disconnect_reason: String,  // "end_call_tool" | "caller_hangup" | "timeout" | "error"
    val outcome: String,            // "resolved" | "escalated" | "abandoned" | "unclear"
    val summary: String? = null
)

// --- Response DTOs ---

data class PairResponse(
    val pairing_id: String,
    val expires_in: Int
)

data class VerifyResponse(
    val device_token: String,
    val device_id: String,
    val org_id: String,
    val restaurant: RestaurantSummaryDto,
    val token_expires_at: String
)

data class RefreshResponse(
    val device_token: String,
    val token_expires_at: String
)

data class RestaurantSummaryDto(
    val name: String,
    val phone: String,
    val city: String
)

data class SessionResponse(
    val session_id: String,
    val session_token: String,    // short-lived (15 min) — send as X-Session-Token on /tool calls
    val gemini_token: String,     // ephemeral, single-use, 5-min expiry (not used directly — use gemini_ws_url)
    val gemini_ws_url: String,    // full WSS URL with token embedded — connect directly, do NOT proxy
    val system_instruction: String,
    val tools: List<ToolDeclarationDto>,
    val config: SessionConfig
)

data class ToolDeclarationDto(
    val name: String,
    val description: String,
    val parameters: ToolParametersDto
)

data class ToolParametersDto(
    val type: String,
    val properties: Map<String, ToolPropertyDto>,
    val required: List<String>? = null
)

data class ToolPropertyDto(
    val type: String,
    val description: String
)

data class SessionConfig(
    val language: String,
    val voice_name: String,
    val max_duration_seconds: Int,
    val vad_enabled: Boolean
)

data class ToolCallResponse(
    val tool_call_id: String,
    val result: Any?    // Pass directly to Gemini as tool_response
)

data class ConfigResponse(
    val restaurant: RestaurantConfigDto,
    val menu_items: List<MenuItemDto>,
    val upcoming_reservations: List<ReservationDto>,
    val synced_at: String
)

data class RestaurantConfigDto(
    val id: String,
    val name: String,
    val phone: String,
    val address: String,
    val city: String,
    val greeting: String,
    val primary_language: String,
    val languages: List<String>,
    val call_mode: String,          // "full_auto" | "busy_no_answer" | "safety_net" | "off"
    val business_hours: Map<String, BusinessHoursDto>,   // day (monday…sunday) → hours
    val updated_at: String
)

data class BusinessHoursDto(
    val open: String,       // "HH:MM"
    val close: String,      // "HH:MM"
    val closed: Boolean
)

data class MenuItemDto(
    val id: String,
    val name: String,
    val name_hi: String?,
    val description: String?,
    val price: Long,        // paise — divide by 100 for display
    val category: String,
    val is_available: Boolean,
    val is_veg: Boolean,
    val updated_at: String
)

data class ReservationDto(
    val id: String,
    val customer_name: String,
    val customer_phone: String,
    val party_size: Int,
    val date: String,
    val time: String,
    val status: String,
    val notes: String?
)
