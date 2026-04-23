package com.cashewbridge.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cashewbridge.app.R
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * BroadcastReceiver for the scheduled daily/weekly spending summary (#5).
 * Reads from the local logs DB and posts a summary notification.
 */
class SummaryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SUMMARY) return
        val prefs = AppPreferences(context)
        if (!prefs.summaryEnabled) return

        val db = AppDatabase.getInstance(context)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val isWeekly = prefs.summaryFrequency == 1
                val windowStart = if (isWeekly) {
                    // Last 7 days
                    System.currentTimeMillis() - 7 * 24 * 3600_000L
                } else {
                    // Start of today
                    Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                }

                val totalExpense = db.logDao().sumExpensesSince(windowStart) ?: 0.0
                val count = db.logDao().countForwardedAllSince(windowStart)
                val topMerchantList = db.logDao().getTopMerchantSince(windowStart)
                val topMerchant = topMerchantList.firstOrNull()?.day

                val period = if (isWeekly) "This week" else "Today"
                val amountStr = "%.2f".format(totalExpense)
                val title = "$period: $count transactions · $$amountStr spent"
                val body = topMerchant?.let { "Top merchant: $it" } ?: "Open Cashew Bridge for details"

                if (!NotificationHelper.canPostNotifications(context)) {
                    Log.w(TAG, "Skipping summary: POST_NOTIFICATIONS not granted")
                    return@launch
                }
                val n = NotificationCompat.Builder(context, NotificationHelper.CHANNEL_SUMMARY)
                    .setSmallIcon(R.drawable.ic_status_active)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()
                try {
                    NotificationManagerCompat.from(context).notify(NOTIF_ID_SUMMARY, n)
                } catch (se: SecurityException) {
                    Log.w(TAG, "Summary notify denied", se)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Summary generation failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SUMMARY = "com.cashewbridge.app.ACTION_SUMMARY"
        const val ALARM_REQUEST_CODE = 9001
        private const val NOTIF_ID_SUMMARY = 9100
        private const val TAG = "SummaryReceiver"

        fun schedule(context: Context, prefs: AppPreferences) {
            val intent = Intent(ACTION_SUMMARY).apply { setPackage(context.packageName) }
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val triggerAt = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, prefs.summaryHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis

            val intervalMs = if (prefs.summaryFrequency == 1) {
                AlarmManager.INTERVAL_DAY * 7
            } else {
                AlarmManager.INTERVAL_DAY
            }
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAt, intervalMs, pi)
            Log.d(TAG, "Summary alarm scheduled at hour=${prefs.summaryHour}")
        }

        fun cancel(context: Context) {
            val intent = Intent(ACTION_SUMMARY).apply { setPackage(context.packageName) }
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pi?.let {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(it)
            }
        }
    }
}
