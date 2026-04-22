package com.cashewbridge.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Handles the "Send to Cashew" and "Skip" actions on confirm notifications.
 */
class ConfirmActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(NotificationHelper.EXTRA_NOTIF_ID, -1)

        // Always dismiss the confirmation notification first
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notifId != -1) manager.cancel(notifId)

        when (intent.action) {
            NotificationHelper.ACTION_CONFIRM_SEND -> {
                val uriString = intent.getStringExtra(NotificationHelper.EXTRA_CASHEW_URI) ?: return
                try {
                    val launchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(launchIntent)
                    Log.i(TAG, "Confirmed: launched Cashew with $uriString")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch Cashew after confirm", e)
                }
            }
            NotificationHelper.ACTION_CONFIRM_SKIP -> {
                Log.i(TAG, "User skipped transaction (notifId=$notifId)")
            }
        }
    }

    companion object {
        private const val TAG = "ConfirmActionReceiver"
    }
}
