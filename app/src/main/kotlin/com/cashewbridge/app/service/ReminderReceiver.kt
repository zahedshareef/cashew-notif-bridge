package com.cashewbridge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires on a repeating alarm to remind the user about uncategorised/unreviewed
 * transactions sitting in the notification cache (#3).
 *
 * "Unreviewed" means the cached notification has a parsedAmount but has not
 * yet been forwarded (i.e. it still exists in the cache and was not dismissed).
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = AppPreferences(context)
        if (!prefs.reminderEnabled || !prefs.isEnabled) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val pending = db.cachedNotificationDao()
                    .getAll()
                    .count { it.parsedAmount != null && it.parsedAmount > 0 }

                if (pending > 0) {
                    Log.i(TAG, "$pending unreviewed transaction(s) — posting reminder")
                    NotificationHelper.postReminderNotification(context, pending)
                } else {
                    Log.d(TAG, "No unreviewed transactions — skipping reminder")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking cache for reminder", e)
            }
        }
    }

    companion object {
        const val ACTION_REMINDER = "com.cashewbridge.app.ACTION_REMINDER"
        const val ALARM_REQUEST_CODE = 8001
        private const val TAG = "ReminderReceiver"
    }
}
