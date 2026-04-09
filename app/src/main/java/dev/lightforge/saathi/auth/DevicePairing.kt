package dev.lightforge.saathi.auth

import android.util.Log
import dev.lightforge.saathi.network.AegisApiClient
import dev.lightforge.saathi.network.PairRequest
import dev.lightforge.saathi.network.VerifyRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles the device pairing flow (OTP-based).
 *
 * Flow:
 * 1. Restaurant owner enters phone number in SetupScreen
 * 2. [startPairing] sends number to backend, which sends OTP via SMS
 * 3. Owner enters OTP received on the restaurant's phone
 * 4. [verifyOtp] sends OTP to backend, receives JWT device token
 * 5. Token stored in Android Keystore via [TokenManager]
 *
 * No API keys are exposed to the restaurant owner.
 * The device token is the only credential stored on the device.
 */
@Singleton
class DevicePairing @Inject constructor(
    private val api: AegisApiClient,
    private val tokenManager: TokenManager
) {

    companion object {
        private const val TAG = "DevicePairing"
    }

    sealed class PairingResult {
        data class OtpSent(val pairingId: String, val expiresIn: Int) : PairingResult()
        data class Paired(val restaurantName: String) : PairingResult()
        data class Error(val message: String) : PairingResult()
    }

    private var currentPairingId: String? = null

    /**
     * Step 1: Send phone number, request OTP.
     */
    suspend fun startPairing(phoneNumber: String, deviceName: String): PairingResult {
        return try {
            val response = api.startPairing(PairRequest(phoneNumber, deviceName))
            if (response.isSuccessful) {
                val body = response.body()!!
                currentPairingId = body.pairing_id
                Log.i(TAG, "OTP sent for pairing (expires in ${body.expires_in}s)")
                PairingResult.OtpSent(body.pairing_id, body.expires_in)
            } else {
                Log.e(TAG, "Pairing request failed: ${response.code()}")
                PairingResult.Error("Failed to send OTP (${response.code()})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pairing request exception", e)
            PairingResult.Error(e.message ?: "Network error")
        }
    }

    /**
     * Step 2: Verify OTP, receive and store device token.
     */
    suspend fun verifyOtp(otp: String): PairingResult {
        val pairingId = currentPairingId
            ?: return PairingResult.Error("No active pairing session")

        return try {
            val response = api.verifyPairing(VerifyRequest(pairingId, otp))
            if (response.isSuccessful) {
                val body = response.body()!!
                // Store token securely in Android Keystore
                tokenManager.storeDeviceToken(body.device_token)
                tokenManager.storeOrgId(body.org_id)
                currentPairingId = null
                Log.i(TAG, "Device paired with ${body.restaurant.name}")
                PairingResult.Paired(body.restaurant.name)
            } else {
                Log.e(TAG, "OTP verification failed: ${response.code()}")
                PairingResult.Error(
                    if (response.code() == 401) "Invalid OTP" else "Verification failed"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "OTP verification exception", e)
            PairingResult.Error(e.message ?: "Network error")
        }
    }

    /**
     * Checks if the device is already paired (has a valid token).
     */
    fun isPaired(): Boolean = tokenManager.getDeviceToken() != null

    /**
     * Unpairs the device: clears stored token and org ID.
     */
    fun unpair() {
        tokenManager.clearAll()
        Log.i(TAG, "Device unpaired")
    }
}
