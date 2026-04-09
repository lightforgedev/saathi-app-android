package dev.lightforge.saathi.telecom

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lightforge.saathi.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages PhoneAccount registration with the Android Telecom framework.
 *
 * Saathi registers as a SELF_MANAGED calling app (like WhatsApp/Telegram).
 * This means:
 * - We manage our own call UI and audio routing
 * - We don't appear in the system dialer's account list
 * - We still get system call integration (notification, auto hold, etc.)
 *
 * The PhoneAccount is registered once at app startup and persists across reboots.
 * It is associated with our [SaathiConnectionService].
 */
@Singleton
class PhoneAccountManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "PhoneAccountMgr"
        private const val PHONE_ACCOUNT_ID = "saathi_voice_agent"
    }

    private val telecomManager: TelecomManager by lazy {
        context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    }

    /**
     * Returns the [PhoneAccountHandle] for Saathi's ConnectionService.
     */
    val phoneAccountHandle: PhoneAccountHandle by lazy {
        PhoneAccountHandle(
            ComponentName(context, SaathiConnectionService::class.java),
            PHONE_ACCOUNT_ID
        )
    }

    /**
     * Registers the Saathi PhoneAccount with the Telecom framework.
     * Safe to call multiple times — registration is idempotent.
     *
     * Must be called at app startup (from Application.onCreate or first Activity).
     */
    fun registerPhoneAccount() {
        try {
            val phoneAccount = PhoneAccount.builder(phoneAccountHandle, context.getString(R.string.app_name))
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .setAddress(Uri.parse("tel:saathi"))
                .setShortDescription(context.getString(R.string.phone_account_description))
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setIcon(Icon.createWithResource(context, R.drawable.ic_saathi))
                    }
                }
                .build()

            telecomManager.registerPhoneAccount(phoneAccount)
            Log.i(TAG, "PhoneAccount registered: $PHONE_ACCOUNT_ID")

            // Verify registration
            val registered = telecomManager.getPhoneAccount(phoneAccountHandle)
            if (registered != null) {
                Log.i(TAG, "PhoneAccount verified: enabled=${registered.isEnabled}")
            } else {
                Log.w(TAG, "PhoneAccount registration could not be verified")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException registering PhoneAccount — missing MANAGE_OWN_CALLS?", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register PhoneAccount", e)
        }
    }

    /**
     * Reports a new incoming call to the Telecom framework.
     *
     * This triggers:
     * 1. System shows incoming call notification (if configured)
     * 2. Telecom calls SaathiConnectionService.onCreateIncomingConnection()
     * 3. We create SaathiConnection and start Gemini voice session
     *
     * @param callerNumber The phone number of the incoming caller
     * @param callerName Display name for the caller (if known)
     * @param sessionId Backend session ID for this call
     */
    fun reportIncomingCall(
        callerNumber: String,
        callerName: String?,
        sessionId: String?,
        echoMode: Boolean = false
    ) {
        try {
            val extras = Bundle().apply {
                putString(SaathiConnectionService.EXTRA_CALLER_NUMBER, callerNumber)
                putString(SaathiConnectionService.EXTRA_CALLER_NAME, callerName ?: callerNumber)
                sessionId?.let { putString(SaathiConnectionService.EXTRA_SESSION_ID, it) }
                putBoolean(SaathiConnectionService.EXTRA_ECHO_MODE, echoMode)
            }

            telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
            Log.i(TAG, "Reported incoming call from $callerNumber (echoMode=$echoMode)")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException reporting incoming call", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report incoming call from $callerNumber", e)
        }
    }

    /**
     * Places an outbound call through the Telecom framework.
     *
     * @param targetNumber Number to call
     * @param sessionId Backend session ID
     */
    fun placeOutgoingCall(targetNumber: String, sessionId: String?) {
        try {
            val uri = Uri.fromParts("tel", targetNumber, null)
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                sessionId?.let {
                    putString(SaathiConnectionService.EXTRA_SESSION_ID, it)
                }
            }

            telecomManager.placeCall(uri, extras)
            Log.i(TAG, "Placed outgoing call to $targetNumber")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException placing outgoing call", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place outgoing call to $targetNumber", e)
        }
    }

    /**
     * Unregisters the Saathi PhoneAccount. Called during device un-pairing.
     */
    fun unregisterPhoneAccount() {
        try {
            telecomManager.unregisterPhoneAccount(phoneAccountHandle)
            Log.i(TAG, "PhoneAccount unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister PhoneAccount", e)
        }
    }
}
