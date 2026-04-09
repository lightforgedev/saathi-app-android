package dev.lightforge.saathi.auth

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Securely stores the device token and related credentials using Android Keystore.
 *
 * Security model:
 * - Master key backed by Android Keystore (hardware-backed on supported devices)
 * - SharedPreferences encrypted with AES-256-GCM (keys) and AES-256-SIV (values)
 * - Token never stored in plain text, not accessible via adb backup
 * - Cleared on factory reset (Keystore is wiped)
 *
 * Stored values:
 * - device_token: JWT from POST /devices/verify (contains org_id, device_id, role)
 * - org_id: organization identifier for this restaurant
 * - device_id: unique device identifier assigned by backend
 */
@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "TokenManager"
        private const val PREFS_FILE = "saathi_secure_prefs"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_ORG_ID = "org_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val PREFS_STATE = "saathi_state"
        private const val KEY_SAATHI_ACTIVE = "saathi_active"
    }

    // Plain (non-encrypted) prefs for non-sensitive operational state
    private val statePrefs by lazy {
        context.getSharedPreferences(PREFS_STATE, android.content.Context.MODE_PRIVATE)
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
            // Fallback: clear corrupted prefs and recreate
            context.deleteSharedPreferences(PREFS_FILE)
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    fun storeDeviceToken(token: String) {
        encryptedPrefs.edit().putString(KEY_DEVICE_TOKEN, token).apply()
        Log.d(TAG, "Device token stored securely")
    }

    fun getDeviceToken(): String? {
        return encryptedPrefs.getString(KEY_DEVICE_TOKEN, null)
    }

    fun storeOrgId(orgId: String) {
        encryptedPrefs.edit().putString(KEY_ORG_ID, orgId).apply()
    }

    fun getOrgId(): String? {
        return encryptedPrefs.getString(KEY_ORG_ID, null)
    }

    fun storeDeviceId(deviceId: String) {
        encryptedPrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    fun getDeviceId(): String? {
        return encryptedPrefs.getString(KEY_DEVICE_ID, null)
    }

    /**
     * Whether Saathi is active (call_mode != "off"). Persisted locally so
     * [SaathiInCallService] can check without a network call during onCallAdded().
     * Defaults to true (active) until a config sync says otherwise.
     */
    fun setSaathiActive(active: Boolean) {
        statePrefs.edit().putBoolean(KEY_SAATHI_ACTIVE, active).apply()
    }

    fun isSaathiActive(): Boolean = statePrefs.getBoolean(KEY_SAATHI_ACTIVE, true)

    /**
     * Clears all stored credentials. Called during device un-pairing.
     */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
        statePrefs.edit().clear().apply()
        Log.i(TAG, "All stored credentials cleared")
    }

    /**
     * Returns true if a device token is present.
     */
    fun hasToken(): Boolean = getDeviceToken() != null
}
