package dev.lightforge.saathi.network

import dev.lightforge.saathi.auth.TokenManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * OkHttp interceptor that adds the device token to all API requests.
 *
 * Header format: Authorization: Bearer <device_token>
 *
 * The device token is a JWT containing org_id, device_id, and role: saathi_device.
 * It is stored securely in Android Keystore via [TokenManager].
 */
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val token = tokenManager.getDeviceToken()
            ?: return chain.proceed(originalRequest) // No token yet (during pairing)

        val authenticatedRequest = originalRequest.newBuilder()
            .header("Authorization", "Bearer $token")
            .header("X-Device-Id", tokenManager.getDeviceId() ?: "")
            .build()

        return chain.proceed(authenticatedRequest)
    }
}
