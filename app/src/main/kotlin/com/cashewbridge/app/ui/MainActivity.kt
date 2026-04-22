package com.cashewbridge.app.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.cashewbridge.app.R
import com.cashewbridge.app.databinding.ActivityMainBinding
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.prefs.AppPreferences
import com.cashewbridge.app.service.ReminderReceiver
import com.cashewbridge.app.service.SummaryReceiver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        applyTheme()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        db = AppDatabase.getInstance(this)

        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationsActivity::class.java))
        }
        binding.btnRules.setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
        binding.btnLogs.setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        binding.btnInsights.setOnClickListener {
            startActivity(Intent(this, InsightsActivity::class.java))
        }
        binding.btnBlocklist.setOnClickListener {
            startActivity(Intent(this, AppBlocklistActivity::class.java))
        }

        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.isEnabled = isChecked
            updateStatusCard()
        }
        binding.switchEnabled.isChecked = prefs.isEnabled

        // Batch mode
        binding.switchBatch.setOnCheckedChangeListener { _, checked ->
            binding.layoutBatchWindow.visibility = if (checked) View.VISIBLE else View.GONE
        }
        // Reminder
        binding.switchReminder.setOnCheckedChangeListener { _, checked ->
            binding.layoutReminderInterval.visibility = if (checked) View.VISIBLE else View.GONE
        }
        // Summary
        binding.switchSummary.setOnCheckedChangeListener { _, checked ->
            binding.layoutSummaryOptions.visibility = if (checked) View.VISIBLE else View.GONE
        }

        binding.btnSaveSettings.setOnClickListener { saveSettings() }

        // Theme
        binding.btnThemeAuto.setOnClickListener { applyThemeMode(0) }
        binding.btnThemeLight.setOnClickListener { applyThemeMode(1) }
        binding.btnThemeDark.setOnClickListener { applyThemeMode(2) }
        updateThemeButtons()

        // Battery
        binding.btnFixBattery.setOnClickListener { openBatteryOptimizationSettings() }
        binding.btnDismissBattery.setOnClickListener {
            prefs.batteryOptDismissed = true
            binding.cardBatteryOpt.visibility = View.GONE
        }

        loadSettings()
        updateStatusCard()
        loadStats()
        checkOnboarding()
    }

    override fun onResume() {
        super.onResume()
        updateStatusCard()
        checkBatteryOptimization()
        loadStats()
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    private fun applyTheme() {
        val mode = when (prefs.themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun applyThemeMode(mode: Int) { prefs.themeMode = mode; applyTheme(); updateThemeButtons() }

    private fun updateThemeButtons() {
        val mode = prefs.themeMode
        binding.btnThemeAuto.isSelected = mode == 0
        binding.btnThemeLight.isSelected = mode == 1
        binding.btnThemeDark.isSelected = mode == 2
    }

    // ── Status card ───────────────────────────────────────────────────────────

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return enabled.contains(packageName)
    }

    private fun updateStatusCard() {
        val hasPermission = isNotificationListenerEnabled()
        val serviceEnabled = prefs.isEnabled
        when {
            !hasPermission -> {
                binding.statusIcon.setImageResource(R.drawable.ic_status_error)
                binding.statusTitle.text = getString(R.string.status_no_permission)
                binding.statusSubtitle.text = getString(R.string.status_no_permission_detail)
                binding.btnGrantPermission.isEnabled = true
            }
            !serviceEnabled -> {
                binding.statusIcon.setImageResource(R.drawable.ic_status_paused)
                binding.statusTitle.text = getString(R.string.status_paused)
                binding.statusSubtitle.text = getString(R.string.status_paused_detail)
                binding.btnGrantPermission.isEnabled = false
            }
            else -> {
                binding.statusIcon.setImageResource(R.drawable.ic_status_active)
                binding.statusTitle.text = getString(R.string.status_active)
                binding.statusSubtitle.text = getString(R.string.status_active_detail)
                binding.btnGrantPermission.isEnabled = false
            }
        }
    }

    private fun loadStats() {
        lifecycleScope.launch {
            val startOfDay = run {
                val cal = java.util.Calendar.getInstance()
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.timeInMillis
            }
            val forwarded = withContext(Dispatchers.IO) { db.logDao().countForwardedSince(startOfDay) }
            val skipped = withContext(Dispatchers.IO) { db.logDao().countSkippedSince(startOfDay) }
            val pending = withContext(Dispatchers.IO) { db.logDao().countPendingSince(startOfDay) }
            binding.tvStats.text = when {
                forwarded + skipped + pending == 0 -> getString(R.string.stats_none_today)
                else -> getString(R.string.stats_today, forwarded, skipped, pending)
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (prefs.batteryOptDismissed) { binding.cardBatteryOpt.visibility = View.GONE; return }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        binding.cardBatteryOpt.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun checkOnboarding() {
        if (!prefs.onboardingDone) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.onboarding_title)
                .setMessage(R.string.onboarding_message)
                .setPositiveButton(R.string.onboarding_grant) { _, _ ->
                    prefs.onboardingDone = true
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton(R.string.onboarding_later) { _, _ -> prefs.onboardingDone = true }
                .setCancelable(false)
                .show()
        }
    }

    // ── Settings load / save ──────────────────────────────────────────────────

    private fun loadSettings() {
        binding.etWalletName.setText(prefs.defaultWalletName)
        binding.etMinAmount.setText(if (prefs.minAmount > 0) prefs.minAmount.toString() else "")
        binding.switchConfirm.isChecked = prefs.confirmBeforeAdding
        val dedupSec = prefs.skipDuplicateWindowMs / 1000
        binding.etDedupWindow.setText(if (dedupSec > 0) dedupSec.toString() else "5")

        binding.switchAutoDismiss.isChecked = prefs.autoDismissSource
        binding.switchUndo.isChecked = prefs.undoEnabled
        binding.switchPrivacy.isChecked = prefs.privacyMode
        val threshold = prefs.largeTransactionThreshold
        binding.etLargeTxThreshold.setText(if (threshold > 0) threshold.toString() else "")

        binding.switchBatch.isChecked = prefs.batchMode
        binding.etBatchWindow.setText(prefs.batchWindowMinutes.toString())
        binding.layoutBatchWindow.visibility = if (prefs.batchMode) View.VISIBLE else View.GONE

        binding.switchReminder.isChecked = prefs.reminderEnabled
        binding.etReminderInterval.setText(prefs.reminderIntervalMinutes.toString())
        binding.layoutReminderInterval.visibility = if (prefs.reminderEnabled) View.VISIBLE else View.GONE

        // #5 Summary
        binding.switchSummary.isChecked = prefs.summaryEnabled
        binding.etSummaryHour.setText(prefs.summaryHour.toString())
        binding.toggleSummaryFrequency.check(
            if (prefs.summaryFrequency == 1) R.id.btn_summary_weekly else R.id.btn_summary_daily
        )
        binding.layoutSummaryOptions.visibility = if (prefs.summaryEnabled) View.VISIBLE else View.GONE

        // #12 Fuzzy dedup
        binding.switchFuzzyDedup.isChecked = prefs.fuzzyDedupEnabled
    }

    private fun saveSettings() {
        prefs.defaultWalletName = binding.etWalletName.text.toString().trim()
        prefs.minAmount = binding.etMinAmount.text.toString().toDoubleOrNull() ?: 0.0
        prefs.confirmBeforeAdding = binding.switchConfirm.isChecked
        val dedupSec = binding.etDedupWindow.text.toString().toLongOrNull() ?: 5L
        prefs.skipDuplicateWindowMs = dedupSec.coerceAtLeast(1L) * 1000L

        prefs.autoDismissSource = binding.switchAutoDismiss.isChecked
        prefs.undoEnabled = binding.switchUndo.isChecked
        prefs.privacyMode = binding.switchPrivacy.isChecked
        prefs.largeTransactionThreshold = binding.etLargeTxThreshold.text.toString().toDoubleOrNull() ?: 0.0
        prefs.batchMode = binding.switchBatch.isChecked
        prefs.batchWindowMinutes = binding.etBatchWindow.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 15

        // Reminder
        val reminderWasEnabled = prefs.reminderEnabled
        prefs.reminderEnabled = binding.switchReminder.isChecked
        prefs.reminderIntervalMinutes = binding.etReminderInterval.text.toString().toIntOrNull()?.coerceAtLeast(5) ?: 30
        if (prefs.reminderEnabled && !reminderWasEnabled) scheduleReminderAlarm()
        else if (!prefs.reminderEnabled) cancelReminderAlarm()

        // #5 Summary
        val summaryWasEnabled = prefs.summaryEnabled
        prefs.summaryEnabled = binding.switchSummary.isChecked
        prefs.summaryHour = binding.etSummaryHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 21
        prefs.summaryFrequency = if (binding.toggleSummaryFrequency.checkedButtonId == R.id.btn_summary_weekly) 1 else 0
        if (prefs.summaryEnabled) {
            SummaryReceiver.schedule(this, prefs)
        } else {
            SummaryReceiver.cancel(this)
        }

        // #12 Fuzzy dedup
        prefs.fuzzyDedupEnabled = binding.switchFuzzyDedup.isChecked

        Snackbar.make(binding.root, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
    }

    // ── Reminder alarm ────────────────────────────────────────────────────────

    private fun scheduleReminderAlarm() {
        val intervalMs = prefs.reminderIntervalMinutes * 60_000L
        val intent = Intent(ReminderReceiver.ACTION_REMINDER).apply { setPackage(packageName) }
        val pi = PendingIntent.getBroadcast(
            this, ReminderReceiver.ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(ALARM_SERVICE) as AlarmManager).setInexactRepeating(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMs, intervalMs, pi
        )
    }

    private fun cancelReminderAlarm() {
        val intent = Intent(ReminderReceiver.ACTION_REMINDER).apply { setPackage(packageName) }
        val pi = PendingIntent.getBroadcast(
            this, ReminderReceiver.ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pi?.let { (getSystemService(ALARM_SERVICE) as AlarmManager).cancel(it) }
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_about -> { showAboutDialog(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
