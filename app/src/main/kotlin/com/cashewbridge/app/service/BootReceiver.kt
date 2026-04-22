package com.cashewbridge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives BOOT_COMPLETED to ensure the notification listener stays active after reboot.
 * The NotificationListenerService is automatically re-bound by Android if permission is granted;
 * this receiver is here to ensure any foreground service or initialization needed on boot runs.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // The NotificationListenerService is managed by Android automatically.
            // Nothing extra needed unless a foreground service is desired on boot.
        }
    }
}
