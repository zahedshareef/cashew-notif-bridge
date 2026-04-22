package com.cashewbridge.app.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Handles the Undo Send flow (#7):
 *
 *  - ACTION_UNDO_SEND_NOW  — user tapped "Send Now"; cancel countdown alarm and launch Cashew immediately.
 *  - ACTION_UNDO_CANCEL    — user tapped "Undo"; cancel countdown alarm and do nothing.
 *  - ACTION_UNDO_ALARM_FIRE— the countdown alarm fired; launch Cashew automatically.
 */
class UndoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(NotificationHelper.EXTRA_UNDO_ID, -1)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {
            NotificationHelper.ACTION_UNDO_SEND_NOW -> {
                cancelCountdownAlarm(context, notifId)
                manager.cancel(notifId)
                launchCashew(context, intent.getStringExtra(NotificationHelper.EXTRA_CASHEW_URI))
                Log.i(TAG, "User tapped Send Now — launched Cashew immediately (id=$notifId)")
            }
            NotificationHelper.ACTION_UNDO_CANCEL -> {
                cancelCountdownAlarm(context, notifId)
                manager.cancel(notifId)
                Log.i(TAG, "User cancelled send (id=$notifId)")
            }
            NotificationHelper.ACTION_UNDO_ALARM_FIRE -> {
                manager.cancel(notifId)
                launchCashew(context, intent.getStringExtra(NotificationHelper.EXTRA_CASHEW_URI))
                Log.i(TAG, "Countdown elapsed — launched Cashew automatically (id=$notifId)")
            }
        }
    }

    private fun launchCashew(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Cashew", e)
        }
    }

    /**
     * Cancel the AlarmManager alarm that would auto-fire the send.
     * Must match exactly the PendingIntent used when scheduling.
     */
    private fun cancelCountdownAlarm(context: Context, notifId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_BASE + notifId,
            Intent(NotificationHelper.ACTION_UNDO_ALARM_FIRE).apply {
                setPackage(context.packageName)
                putExtra(NotificationHelper.EXTRA_UNDO_ID, notifId)
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pi?.let { alarmManager.cancel(it) }
    }

    companion object {
        private const val TAG = "UndoActionReceiver"
        const val ALARM_REQUEST_BASE = 7000
    }
}
