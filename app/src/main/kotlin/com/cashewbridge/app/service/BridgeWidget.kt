package com.cashewbridge.app.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cashewbridge.app.R
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.prefs.AppPreferences
import com.cashewbridge.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * #10 — Home Screen Widget (2×1 minimum)
 *
 * Shows:
 *  • Today's forwarded transaction count + total expense
 *  • Bridge enable/disable toggle button
 *  • "Open App" tap target
 */
class BridgeWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            val prefs = AppPreferences(context)
            prefs.isEnabled = !prefs.isEnabled
            requestUpdate(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.cashewbridge.app.WIDGET_TOGGLE"

        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, BridgeWidget::class.java))
            if (ids.isEmpty()) return
            for (id in ids) updateWidget(context, mgr, id)
        }

        private fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val prefs = AppPreferences(context)
            val views = RemoteViews(context.packageName, R.layout.widget_bridge)

            // Toggle button
            val toggleIntent = Intent(context, BridgeWidget::class.java).apply {
                action = ACTION_TOGGLE
            }
            val togglePi = PendingIntent.getBroadcast(
                context, widgetId,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_toggle, togglePi)
            views.setTextViewText(
                R.id.widget_toggle,
                if (prefs.isEnabled) "● ON" else "○ OFF"
            )
            views.setTextColor(
                R.id.widget_toggle,
                if (prefs.isEnabled) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
            )

            // Open app on title tap
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPi = PendingIntent.getActivity(
                context, widgetId + 10000, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title, openPi)

            // Load stats in background
            val db = AppDatabase.getInstance(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val startOfDay = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                    }.timeInMillis
                    val count = db.logDao().countForwardedSince(startOfDay)
                    val total = db.logDao().sumExpensesSince(startOfDay) ?: 0.0
                    val statsText = if (count == 0) "No transactions today"
                    else "$count tx · ${"%.2f".format(total)} spent"
                    views.setTextViewText(R.id.widget_stats, statsText)
                } catch (e: Exception) {
                    views.setTextViewText(R.id.widget_stats, "Tap to open app")
                }
                mgr.updateAppWidget(widgetId, views)
            }

            // Set a placeholder immediately so the widget isn't blank
            views.setTextViewText(R.id.widget_stats, "Loading…")
            mgr.updateAppWidget(widgetId, views)
        }
    }
}
