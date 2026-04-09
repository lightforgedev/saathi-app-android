package dev.lightforge.saathi.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts [SaathiForegroundService] automatically when the device boots.
 *
 * This ensures Saathi is always ready to handle incoming calls without
 * the restaurant owner needing to manually open the app.
 *
 * Registered in AndroidManifest.xml with BOOT_COMPLETED intent filter.
 * Requires RECEIVE_BOOT_COMPLETED permission.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        Log.i(TAG, "Boot completed — starting SaathiForegroundService")

        val serviceIntent = Intent(context, SaathiForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service on boot", e)
        }
    }
}
