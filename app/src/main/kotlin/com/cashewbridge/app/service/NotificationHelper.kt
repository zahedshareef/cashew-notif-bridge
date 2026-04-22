package com.cashewbridge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.cashewbridge.app.R

object NotificationHelper {

    const val CHANNEL_CONFIRM = "cashew_bridge_confirm"
    const val CHANNEL_ALARM = "cashew_bridge_alarm"
    const val CHANNEL_UNDO = "cashew_bridge_undo"
    const val CHANNEL_REMINDER = "cashew_bridge_reminder"
    const val CHANNEL_BATCH = "cashew_bridge_batch"

    const val ACTION_CONFIRM_SEND = "com.cashewbridge.app.ACTION_CONFIRM_SEND"
    const val ACTION_CONFIRM_SKIP = "com.cashewbridge.app.ACTION_CONFIRM_SKIP"
    const val ACTION_UNDO_SEND_NOW = "com.cashewbridge.app.ACTION_UNDO_SEND_NOW"
    const val ACTION_UNDO_CANCEL = "com.cashewbridge.app.ACTION_UNDO_CANCEL"
    const val ACTION_UNDO_ALARM_FIRE = "com.cashewbridge.app.ACTION_UNDO_ALARM_FIRE"
    const val ACTION_OPEN_BATCH = "com.cashewbridge.app.ACTION_OPEN_BATCH"

    const val EXTRA_CASHEW_URI = "cashew_uri"
    const val EXTRA_NOTIF_ID = "confirm_notif_id"
    const val EXTRA_UNDO_ID = "undo_notif_id"
    const val EXTRA_SOURCE_SBN_KEY = "source_sbn_key"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONFIRM, "Confirm Transactions",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Review transactions before they are sent to Cashew"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALARM, "Large Transaction Alert",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Loud alert for transactions above your threshold"
                setSound(alarmUri, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_UNDO, "Undo Send",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Brief notification to cancel a forwarded transaction"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Unreviewed Transactions",
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders about transactions waiting in the app"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_BATCH, "Batch Review Ready",
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your batch of transactions is ready to review"
            }
        )
    }

    /**
     * Post a confirm notification. When [privacyMode] is true the content shows
     * a generic message instead of the actual amount/merchant.
     */
    fun postConfirmNotification(
        context: Context,
        notifId: Int,
        summary: String,
        cashewUri: String,
        privacyMode: Boolean = false
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val displayText = if (privacyMode) "Transaction detected — tap 'Send' to review" else summary

        val sendPending = pendingBroadcast(context, notifId * 2,
            Intent(ACTION_CONFIRM_SEND).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CASHEW_URI, cashewUri)
                putExtra(EXTRA_NOTIF_ID, notifId)
            })
        val skipPending = pendingBroadcast(context, notifId * 2 + 1,
            Intent(ACTION_CONFIRM_SKIP).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_NOTIF_ID, notifId)
            })

        val notif = NotificationCompat.Builder(context, CHANNEL_CONFIRM)
            .setSmallIcon(R.drawable.ic_status_active)
            .setContentTitle("Transaction Detected")
            .setContentText(displayText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .addAction(0, "Send to Cashew", sendPending)
            .addAction(0, "Skip", skipPending)
            .build()

        manager.notify(notifId, notif)
    }

    /**
     * Post a loud alarm notification for large transactions (#6).
     * Uses CHANNEL_ALARM which is wired to the system alarm sound.
     */
    fun postAlarmNotification(
        context: Context,
        notifId: Int,
        amountLabel: String,
        merchant: String?,
        appLabel: String
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val merchantPart = merchant?.let { " at $it" } ?: ""
        val text = "Large transaction: $amountLabel$merchantPart from $appLabel"

        val notif = NotificationCompat.Builder(context, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_status_error)
            .setContentTitle("⚠ Large Transaction Detected")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        manager.notify(notifId, notif)
    }

    /**
     * Post a brief "Undo" notification with a countdown before the transaction is forwarded (#7).
     * "Send Now" fires immediately; "Undo" cancels the pending alarm.
     */
    fun postUndoNotification(
        context: Context,
        notifId: Int,
        summary: String,
        cashewUri: String,
        countdownSeconds: Long
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val sendNowPending = pendingBroadcast(context, notifId * 10,
            Intent(ACTION_UNDO_SEND_NOW).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CASHEW_URI, cashewUri)
                putExtra(EXTRA_UNDO_ID, notifId)
            })
        val undoPending = pendingBroadcast(context, notifId * 10 + 1,
            Intent(ACTION_UNDO_CANCEL).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_UNDO_ID, notifId)
            })

        val text = "$summary\nSending to Cashew in ${countdownSeconds}s…"
        val notif = NotificationCompat.Builder(context, CHANNEL_UNDO)
            .setSmallIcon(R.drawable.ic_status_active)
            .setContentTitle("Sending Transaction…")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .setTimeoutAfter((countdownSeconds + 2) * 1000)
            .addAction(0, "Send Now", sendNowPending)
            .addAction(0, "Undo", undoPending)
            .build()

        manager.notify(notifId, notif)
    }

    /**
     * Post a reminder about unreviewed transactions sitting in the cache (#3).
     */
    fun postReminderNotification(context: Context, count: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = "You have $count transaction${if (count == 1) "" else "s"} waiting for review in Cashew Bridge."

        val notif = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_status_paused)
            .setContentTitle("Transactions Waiting")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        manager.notify(ID_REMINDER, notif)
    }

    /**
     * Post a notification telling the user their batch is ready to review (#2).
     */
    fun postBatchReadyNotification(context: Context, count: Int, batchActivityClass: Class<*>) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = "$count transaction${if (count == 1) "" else "s"} collected — tap to review and send to Cashew."

        val openPending = PendingIntent.getActivity(
            context, 0,
            Intent(context, batchActivityClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_BATCH)
            .setSmallIcon(R.drawable.ic_status_active)
            .setContentTitle("Batch Ready to Review")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .build()

        manager.notify(ID_BATCH_READY, notif)
    }

    private fun pendingBroadcast(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    const val ID_REMINDER = 9001
    const val ID_BATCH_READY = 9002
}
