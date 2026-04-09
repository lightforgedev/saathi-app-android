package dev.lightforge.saathi.telecom

import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

/**
 * ConnectionService implementation for Saathi.
 *
 * This is the core telecom integration point. Android's Telecom framework calls into this
 * service when:
 * - An incoming call arrives for our registered PhoneAccount
 * - The system requests an outgoing call through our PhoneAccount
 *
 * Flow:
 * 1. PhoneAccount registered at app startup via [PhoneAccountManager]
 * 2. Incoming call: TelecomManager.addNewIncomingCall() -> onCreateIncomingConnection()
 * 3. We create a [SaathiConnection], start Gemini voice session, bridge audio
 * 4. Outgoing call: backend sends command via WebSocket -> we call TelecomManager.placeCall()
 *    -> onCreateOutgoingConnection()
 */
class SaathiConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "SaathiConnService"
        const val EXTRA_CALLER_NUMBER = "extra_caller_number"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_SESSION_ID = "extra_session_id"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ConnectionService created")
    }

    /**
     * Called by the Telecom framework when an incoming call is reported via
     * TelecomManager.addNewIncomingCall().
     *
     * We create a SaathiConnection in RINGING state. The connection handles
     * answer/reject and bridges audio to Gemini Live.
     */
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "onCreateIncomingConnection: ${request?.address}")

        val extras = request?.extras ?: Bundle()
        val callerNumber = request?.address?.schemeSpecificPart
            ?: extras.getString(EXTRA_CALLER_NUMBER, "Unknown")
        val callerName = extras.getString(EXTRA_CALLER_NAME, callerNumber)
        val sessionId = extras.getString(EXTRA_SESSION_ID)

        val connection = SaathiConnection(
            context = applicationContext,
            callerNumber = callerNumber,
            callerName = callerName,
            sessionId = sessionId,
            isIncoming = true
        )

        connection.setAddress(
            Uri.fromParts("tel", callerNumber, null),
            TelecomManager.PRESENTATION_ALLOWED
        )
        connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        connection.setRinging()

        Log.i(TAG, "Incoming connection created for $callerNumber ($callerName)")
        return connection
    }

    /**
     * Called when the framework cannot create an incoming connection.
     * Clean up any pending state.
     */
    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed: ${request?.address}")
        // TODO: Report failure to backend telemetry
    }

    /**
     * Called by the Telecom framework when an outgoing call is placed through our PhoneAccount.
     *
     * Used when the backend instructs the app to make an outbound call (e.g., reservation
     * confirmation callback).
     */
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "onCreateOutgoingConnection: ${request?.address}")

        val extras = request?.extras ?: Bundle()
        val targetNumber = request?.address?.schemeSpecificPart ?: "Unknown"
        val sessionId = extras.getString(EXTRA_SESSION_ID)

        val connection = SaathiConnection(
            context = applicationContext,
            callerNumber = targetNumber,
            callerName = targetNumber,
            sessionId = sessionId,
            isIncoming = false
        )

        connection.setAddress(
            Uri.fromParts("tel", targetNumber, null),
            TelecomManager.PRESENTATION_ALLOWED
        )
        connection.setDialing()

        Log.i(TAG, "Outgoing connection created for $targetNumber")
        return connection
    }

    /**
     * Called when the framework cannot create an outgoing connection.
     */
    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed: ${request?.address}")
        // TODO: Report failure to backend telemetry
    }

    override fun onDestroy() {
        Log.d(TAG, "ConnectionService destroyed")
        super.onDestroy()
    }
}
