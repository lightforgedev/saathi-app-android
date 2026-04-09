package dev.lightforge.saathi.telecom

import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * ConnectionService implementation for Saathi.
 *
 * Android's Telecom framework binds to this service when:
 *   - An incoming call is reported via TelecomManager.addNewIncomingCall()
 *   - An outgoing call is placed via TelecomManager.placeCall()
 *
 * All ConnectionService callbacks run on the main thread — never block them.
 * Audio work is performed on Dispatchers.IO inside [SaathiConnection].
 *
 * The [activeConnections] map lets other components (e.g. [SpikeTestActivity])
 * route audio or inspect call state without coupling through Hilt.
 */
class SaathiConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "SaathiConnService"

        const val EXTRA_CALLER_NUMBER = "extra_caller_number"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_ECHO_MODE = "extra_echo_mode"

        /**
         * Live connections keyed by caller number.
         * Written by [onCreateIncomingConnection] / [onCreateOutgoingConnection].
         * Read by any component that needs to inspect or control an active call.
         */
        val activeConnections: ConcurrentHashMap<String, SaathiConnection> = ConcurrentHashMap()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ConnectionService created")
    }

    /**
     * Called by Telecom when an incoming call is reported via addNewIncomingCall().
     * Returns a [SaathiConnection] in RINGING state.
     */
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "onCreateIncomingConnection: ${request?.address}")

        val extras = request?.extras ?: Bundle()
        val callerNumber = request?.address?.schemeSpecificPart
            ?: extras.getString(EXTRA_CALLER_NUMBER, "Unknown")!!
        val callerName = extras.getString(EXTRA_CALLER_NAME, callerNumber)!!
        val sessionId = extras.getString(EXTRA_SESSION_ID)
        val echoMode = extras.getBoolean(EXTRA_ECHO_MODE, false)

        val connection = SaathiConnection(
            context = applicationContext,
            callerNumber = callerNumber,
            callerName = callerName,
            sessionId = sessionId,
            isIncoming = true,
            echoMode = echoMode
        )

        connection.setAddress(
            Uri.fromParts("tel", callerNumber, null),
            TelecomManager.PRESENTATION_ALLOWED
        )
        connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        connection.setRinging()

        activeConnections[callerNumber] = connection
        Log.i(TAG, "Incoming connection created for $callerNumber (echoMode=$echoMode)")
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed: ${request?.address}")
    }

    /**
     * Called by Telecom when an outgoing call is placed via placeCall().
     * Returns a [SaathiConnection] in DIALING state.
     */
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "onCreateOutgoingConnection: ${request?.address}")

        val extras = request?.extras ?: Bundle()
        val targetNumber = request?.address?.schemeSpecificPart ?: "Unknown"
        val sessionId = extras.getString(EXTRA_SESSION_ID)
        val echoMode = extras.getBoolean(EXTRA_ECHO_MODE, false)

        val connection = SaathiConnection(
            context = applicationContext,
            callerNumber = targetNumber,
            callerName = targetNumber,
            sessionId = sessionId,
            isIncoming = false,
            echoMode = echoMode
        )

        connection.setAddress(
            Uri.fromParts("tel", targetNumber, null),
            TelecomManager.PRESENTATION_ALLOWED
        )
        connection.setDialing()

        activeConnections[targetNumber] = connection
        Log.i(TAG, "Outgoing connection created for $targetNumber (echoMode=$echoMode)")
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed: ${request?.address}")
    }

    override fun onDestroy() {
        Log.d(TAG, "ConnectionService destroyed")
        activeConnections.clear()
        super.onDestroy()
    }
}
