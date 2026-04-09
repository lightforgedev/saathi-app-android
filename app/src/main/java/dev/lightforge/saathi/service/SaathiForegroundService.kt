package dev.lightforge.saathi.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.lightforge.saathi.BuildConfig
import dev.lightforge.saathi.MainActivity
import dev.lightforge.saathi.R
import dev.lightforge.saathi.SaathiApplication
import dev.lightforge.saathi.data.sync.DataSyncManager
import dev.lightforge.saathi.network.SaathiWebSocket
import dev.lightforge.saathi.telecom.PhoneAccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Persistent foreground service that keeps Saathi ready to handle calls.
 *
 * Responsibilities:
 * 1. Registers PhoneAccount with Telecom framework
 * 2. Maintains WebSocket connection to backend (heartbeat, config pushes, outbound call commands)
 * 3. Triggers initial data sync
 * 4. Shows persistent notification ("Saathi is ready")
 *
 * Started at boot by [BootReceiver] and when the app launches.
 * Runs indefinitely until explicitly stopped or device unpaired.
 */
@AndroidEntryPoint
class SaathiForegroundService : Service() {

    companion object {
        private const val TAG = "SaathiFgService"
        private const val NOTIFICATION_ID = 1001
    }

    @Inject lateinit var phoneAccountManager: PhoneAccountManager
    @Inject lateinit var webSocket: SaathiWebSocket
    @Inject lateinit var dataSyncManager: DataSyncManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Foreground service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Foreground service starting")

        // Start as foreground immediately to avoid ANR
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Register PhoneAccount
        phoneAccountManager.registerPhoneAccount()

        // Connect WebSocket to backend
        webSocket.connect(BuildConfig.AEGIS_WS_BASE_URL)

        // Listen for WebSocket messages
        serviceScope.launch {
            webSocket.messages.collect { message ->
                when (message) {
                    is SaathiWebSocket.ServerMessage.ConfigSync -> {
                        dataSyncManager.applyDeltaSync(message.data)
                    }
                    is SaathiWebSocket.ServerMessage.OutboundCall -> {
                        phoneAccountManager.placeOutgoingCall(
                            message.targetNumber,
                            message.sessionId
                        )
                    }
                    is SaathiWebSocket.ServerMessage.DeviceRevoked -> {
                        Log.w(TAG, "Device revoked — stopping service")
                        stopSelf()
                    }
                }
            }
        }

        // Initial data sync
        serviceScope.launch {
            dataSyncManager.fullSync()
        }

        return START_STICKY // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Foreground service destroyed")
        webSocket.disconnect()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SaathiApplication.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_service_title))
            .setContentText(getString(R.string.notification_service_text))
            .setSmallIcon(R.drawable.ic_saathi)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
