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

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_DEFAULT_WALLET = "default_wallet"
        private const val KEY_CONFIRM = "confirm_before_adding"
        private const val KEY_MIN_AMOUNT = "min_amount"
        private const val KEY_DEDUP_WINDOW = "dedup_window_ms"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_BATTERY_OPT_DISMISSED = "battery_opt_dismissed"
    }
}
