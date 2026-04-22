package com.cashewbridge.app.prefs

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cashew_bridge_prefs", Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var defaultWalletName: String
        get() = prefs.getString(KEY_DEFAULT_WALLET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEFAULT_WALLET, value).apply()

    var confirmBeforeAdding: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM, false)
        set(value) = prefs.edit().putBoolean(KEY_CONFIRM, value).apply()

    var minAmount: Double
        get() = prefs.getFloat(KEY_MIN_AMOUNT, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MIN_AMOUNT, value.toFloat()).apply()

    var skipDuplicateWindowMs: Long
        get() = prefs.getLong(KEY_DEDUP_WINDOW, 5000L)
        set(value) = prefs.edit().putLong(KEY_DEDUP_WINDOW, value).apply()

    /** 0 = follow system, 1 = light, 2 = dark */
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME, 0)
        set(value) = prefs.edit().putInt(KEY_THEME, value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    var batteryOptDismissed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_OPT_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_OPT_DISMISSED, value).apply()

    // ── Auto-dismiss source notification ──────────────────────────────────────
    var autoDismissSource: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DISMISS_SOURCE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DISMISS_SOURCE, value).apply()

    // ── Batch review mode ─────────────────────────────────────────────────────
    var batchMode: Boolean
        get() = prefs.getBoolean(KEY_BATCH_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_BATCH_MODE, value).apply()

    var batchWindowMinutes: Int
        get() = prefs.getInt(KEY_BATCH_WINDOW, 15)
        set(value) = prefs.edit().putInt(KEY_BATCH_WINDOW, value).apply()

    // ── Unreviewed transaction reminders ──────────────────────────────────────
    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    var reminderIntervalMinutes: Int
        get() = prefs.getInt(KEY_REMINDER_INTERVAL, 30)
        set(value) = prefs.edit().putInt(KEY_REMINDER_INTERVAL, value).apply()

    // ── Privacy mode ──────────────────────────────────────────────────────────
    var privacyMode: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PRIVACY_MODE, value).apply()

    // ── Large transaction alarm ───────────────────────────────────────────────
    var largeTransactionThreshold: Double
        get() = prefs.getFloat(KEY_LARGE_TX_THRESHOLD, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_LARGE_TX_THRESHOLD, value.toFloat()).apply()

    // ── Undo send ─────────────────────────────────────────────────────────────
    var undoEnabled: Boolean
        get() = prefs.getBoolean(KEY_UNDO_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_UNDO_ENABLED, value).apply()

    // ── #5 Daily / weekly summary notification ────────────────────────────────
    /** Whether the scheduled summary notification is enabled. */
    var summaryEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUMMARY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SUMMARY_ENABLED, value).apply()

    /** Hour of day (0-23) to fire the summary (default 21 = 9 PM). */
    var summaryHour: Int
        get() = prefs.getInt(KEY_SUMMARY_HOUR, 21)
        set(value) = prefs.edit().putInt(KEY_SUMMARY_HOUR, value).apply()

    /** 0 = daily, 1 = weekly (Monday morning). */
    var summaryFrequency: Int
        get() = prefs.getInt(KEY_SUMMARY_FREQUENCY, 0)
        set(value) = prefs.edit().putInt(KEY_SUMMARY_FREQUENCY, value).apply()

    // ── #12 Fuzzy duplicate detection ────────────────────────────────────────
    /**
     * When true, dedup key is `packageName + amount` (ignores body text).
     * This catches banks that send the same transaction in two different wordings.
     */
    var fuzzyDedupEnabled: Boolean
        get() = prefs.getBoolean(KEY_FUZZY_DEDUP, false)
        set(value) = prefs.edit().putBoolean(KEY_FUZZY_DEDUP, value).apply()

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DEFAULT_WALLET = "default_wallet"
        private const val KEY_CONFIRM = "confirm_before_adding"
        private const val KEY_MIN_AMOUNT = "min_amount"
        private const val KEY_DEDUP_WINDOW = "dedup_window_ms"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_BATTERY_OPT_DISMISSED = "battery_opt_dismissed"
        private const val KEY_AUTO_DISMISS_SOURCE = "auto_dismiss_source"
        private const val KEY_BATCH_MODE = "batch_mode"
        private const val KEY_BATCH_WINDOW = "batch_window_minutes"
        private const val KEY_REMINDER_ENABLED = "reminder_enabled"
        private const val KEY_REMINDER_INTERVAL = "reminder_interval_minutes"
        private const val KEY_PRIVACY_MODE = "privacy_mode"
        private const val KEY_LARGE_TX_THRESHOLD = "large_tx_threshold"
        private const val KEY_UNDO_ENABLED = "undo_enabled"
        private const val KEY_SUMMARY_ENABLED = "summary_enabled"
        private const val KEY_SUMMARY_HOUR = "summary_hour"
        private const val KEY_SUMMARY_FREQUENCY = "summary_frequency"
        private const val KEY_FUZZY_DEDUP = "fuzzy_dedup_enabled"

        const val UNDO_COUNTDOWN_SECONDS = 10L
    }
}
