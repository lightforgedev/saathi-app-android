package dev.lightforge.saathi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Initializes Hilt DI and creates notification channels.
 */
@HiltAndroidApp
class SaathiApplication : Application() {

    companion object {
        const val CHANNEL_CALL = "saathi_call_channel"
        const val CHANNEL_SERVICE = "saathi_service_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // High-priority channel for incoming call alerts
            val callChannel = NotificationChannel(
                CHANNEL_CALL,
                getString(R.string.channel_calls),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_calls_description)
                setShowBadge(true)
            }

            // Low-priority channel for persistent foreground service
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_service_description)
                setShowBadge(false)
            }

            notificationManager.createNotificationChannels(listOf(callChannel, serviceChannel))
        }
    }
}
