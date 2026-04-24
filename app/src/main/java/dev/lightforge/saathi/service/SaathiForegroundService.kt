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
import dev.lightforge.saathi.MainActivity
import dev.lightforge.saathi.R
import dev.lightforge.saathi.SaathiApplication
import dev.lightforge.saathi.auth.TokenManager
import dev.lightforge.saathi.data.sync.DataSyncManager
import dev.lightforge.saathi.telecom.PhoneAccountManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Persistent foreground service that keeps Saathi ready to handle calls.
 *
 * Responsibilities:
 * 1. Registers PhoneAccount with Telecom framework
 * 2. Polls GET /config every 60s for config updates (WebSocket not implemented in v1 backend)
 * 3. Triggers initial data sync
 * 4. Shows persistent notification ("Saathi is ready")
 * 5. Stops itself on 401 (device revoked / token expired)
 *
 * Started at boot by [BootReceiver] and when the app launches.
 * Runs indefinitely until explicitly stopped or device unpaired.
 */
@AndroidEntryPoint
class SaathiForegroundService : Service() {

    companion object {
        private const val TAG = "SaathiFgService"
        private const val NOTIFICATION_ID = 1001
        private const val CONFIG_POLL_INTERVAL_MS = 60_000L
    }

    @Inject lateinit var phoneAccountManager: PhoneAccountManager
    @Inject lateinit var dataSyncManager: DataSyncManager
    @Inject lateinit var tokenManager: TokenManager

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

        // Initial data sync
        serviceScope.launch {
            dataSyncManager.fullSync()
        }

        // Poll config every 60s. Stop on 401 (device revoked).
        serviceScope.launch {
            while (isActive) {
                delay(CONFIG_POLL_INTERVAL_MS)
                val ok = dataSyncManager.fullSync()
                if (!ok) {
                    Log.w(TAG, "Config poll returned error — checking for 401")
                    // fullSync() returns false on network error AND 401.
                    // The AuthInterceptor will clear the token on 401; check it.
                    // If token is gone, this device was revoked — stop service.
                    if (!tokenManager.hasToken()) {
                        Log.w(TAG, "Token cleared (device revoked) — stopping service")
                        stopSelf()
                        break
                    }
                }
            }
        }

        return START_STICKY // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Foreground service destroyed")
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
