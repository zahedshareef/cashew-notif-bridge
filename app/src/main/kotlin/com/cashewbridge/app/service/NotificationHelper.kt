package com.cashewbridge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cashewbridge.app.R

object NotificationHelper {

    const val CHANNEL_CONFIRM = "cashew_bridge_confirm"
    const val ACTION_CONFIRM_SEND = "com.cashewbridge.app.ACTION_CONFIRM_SEND"
    const val ACTION_CONFIRM_SKIP = "com.cashewbridge.app.ACTION_CONFIRM_SKIP"
    const val EXTRA_CASHEW_URI = "cashew_uri"
    const val EXTRA_NOTIF_ID = "confirm_notif_id"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_CONFIRM,
            "Confirm Transactions",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Review transactions before they are sent to Cashew"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Post a sticky notification asking the user to confirm a detected transaction.
     *
     * @param notifId  Unique ID for this notification (so each transaction gets its own card)
     * @param summary  Short description e.g. "Expense $24.99 · Amazon"
     * @param cashewUri The fully-built cashewapp:// URI string
     */
    fun postConfirmNotification(
        context: Context,
        notifId: Int,
        summary: String,
        cashewUri: String
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val sendIntent = Intent(ACTION_CONFIRM_SEND).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_CASHEW_URI, cashewUri)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }
        val skipIntent = Intent(ACTION_CONFIRM_SKIP).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }

        val sendPending = PendingIntent.getBroadcast(
            context, notifId * 2,
            sendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val skipPending = PendingIntent.getBroadcast(
            context, notifId * 2 + 1,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
            .setSmallIcon(R.drawable.ic_status_active)
            .setContentTitle("Transaction Detected")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .addAction(0, "Send to Cashew", sendPending)
            .addAction(0, "Skip", skipPending)
            .build()

        manager.notify(notifId, notification)
    }
}
