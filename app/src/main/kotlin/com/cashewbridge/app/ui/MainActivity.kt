package com.cashewbridge.app.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cashewbridge.app.R
import com.cashewbridge.app.databinding.ActivityMainBinding
import com.cashewbridge.app.databinding.ViewSettingRowBinding
import com.cashewbridge.app.databinding.ViewStatTileBinding
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

        configureSettingRows()
        configureStatTiles()
        setupBottomNav()

        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.isEnabled = isChecked
            updateStatusCard()
        }
        binding.switchEnabled.isChecked = prefs.isEnabled

        binding.rowBatch.rowSwitch.setOnCheckedChangeListener { _, checked ->
            binding.layoutBatchWindow.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.rowReminder.rowSwitch.setOnCheckedChangeListener { _, checked ->
            binding.layoutReminderInterval.visibility = if (checked) View.VISIBLE else View.GONE
        }
        binding.rowSummary.rowSwitch.setOnCheckedChangeListener { _, checked ->
            binding.layoutSummaryOptions.visibility = if (checked) View.VISIBLE else View.GONE
        }

        binding.btnSaveSettings.setOnClickListener { saveSettings() }

        binding.btnThemeAuto.setOnClickListener { applyThemeMode(0) }
        binding.btnThemeLight.setOnClickListener { applyThemeMode(1) }
        binding.btnThemeDark.setOnClickListener { applyThemeMode(2) }
        updateThemeButtons()

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
        binding.bottomNav.root.menu.findItem(R.id.nav_home)?.isChecked = true
    }

    // ── UI scaffolding ────────────────────────────────────────────────────────

    private fun configureStatTiles() {
        bindStat(binding.statForwarded, getString(R.string.stat_label_forwarded),
            R.drawable.bg_stat_dot_forwarded)
        bindStat(binding.statSkipped, getString(R.string.stat_label_skipped),
            R.drawable.bg_stat_dot_skipped)
        bindStat(binding.statPending, getString(R.string.stat_label_pending),
            R.drawable.bg_stat_dot_pending)
    }

    private fun bindStat(tile: ViewStatTileBinding, label: String, dotRes: Int) {
        tile.statLabel.text = label
        tile.statDot.setBackgroundResource(dotRes)
        tile.statValue.text = "0"
    }

    private fun configureSettingRows() {
        bindRow(binding.rowConfirm, R.string.confirm_before_adding, R.string.confirm_before_adding_detail)
        bindRow(binding.rowAutoDismiss, R.string.auto_dismiss_source, R.string.auto_dismiss_source_detail)
        bindRow(binding.rowUndo, R.string.undo_send, R.string.undo_send_detail)
        bindRow(binding.rowPrivacy, R.string.privacy_mode, R.string.privacy_mode_detail)
        bindRow(binding.rowBatch, R.string.batch_mode, R.string.batch_mode_detail)
        bindRow(binding.rowReminder, R.string.reminder_enabled, R.string.reminder_enabled_detail)
        bindRow(binding.rowSummary, R.string.summary_enabled, R.string.summary_enabled_detail)
        bindRow(binding.rowFuzzy, R.string.fuzzy_dedup, R.string.fuzzy_dedup_detail)
    }

    private fun bindRow(row: ViewSettingRowBinding, titleRes: Int, subtitleRes: Int) {
        row.rowTitle.setText(titleRes)
        row.rowSubtitle.setText(subtitleRes)
    }

    private fun setupBottomNav() {
        BottomNavHelper.setup(this, binding.bottomNav.root, R.id.nav_home)
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
        val id = when (prefs.themeMode) {
            1 -> R.id.btn_theme_light
            2 -> R.id.btn_theme_dark
            else -> R.id.btn_theme_auto
        }
        binding.toggleTheme.check(id)
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
                binding.statusIcon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_error))
                binding.statusIconBg.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_error_bg))
                binding.statusTitle.text = getString(R.string.status_no_permission)
                binding.statusSubtitle.text = getString(R.string.status_no_permission_detail)
                binding.btnGrantPermission.isEnabled = true
                binding.btnGrantPermission.visibility = View.VISIBLE
            }
            !serviceEnabled -> {
                binding.statusIcon.setImageResource(R.drawable.ic_status_paused)
                binding.statusIcon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_paused))
                binding.statusIconBg.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_paused_bg))
                binding.statusTitle.text = getString(R.string.status_paused)
                binding.statusSubtitle.text = getString(R.string.status_paused_detail)
                binding.btnGrantPermission.isEnabled = false
                binding.btnGrantPermission.visibility = View.GONE
            }
            else -> {
                binding.statusIcon.setImageResource(R.drawable.ic_status_active)
                binding.statusIcon.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_active))
                binding.statusIconBg.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_active_bg))
                binding.statusTitle.text = getString(R.string.status_active)
                binding.statusSubtitle.text = getString(R.string.status_active_detail)
                binding.btnGrantPermission.isEnabled = false
                binding.btnGrantPermission.visibility = View.GONE
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
            binding.statForwarded.statValue.text = forwarded.toString()
            binding.statSkipped.statValue.text = skipped.toString()
            binding.statPending.statValue.text = pending.toString()
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
        binding.rowConfirm.rowSwitch.isChecked = prefs.confirmBeforeAdding
        val dedupSec = prefs.skipDuplicateWindowMs / 1000
        binding.etDedupWindow.setText(if (dedupSec > 0) dedupSec.toString() else "5")

        binding.rowAutoDismiss.rowSwitch.isChecked = prefs.autoDismissSource
        binding.rowUndo.rowSwitch.isChecked = prefs.undoEnabled
        binding.rowPrivacy.rowSwitch.isChecked = prefs.privacyMode
        val threshold = prefs.largeTransactionThreshold
        binding.etLargeTxThreshold.setText(if (threshold > 0) threshold.toString() else "")

        binding.rowBatch.rowSwitch.isChecked = prefs.batchMode
        binding.etBatchWindow.setText(prefs.batchWindowMinutes.toString())
        binding.layoutBatchWindow.visibility = if (prefs.batchMode) View.VISIBLE else View.GONE

        binding.rowReminder.rowSwitch.isChecked = prefs.reminderEnabled
        binding.etReminderInterval.setText(prefs.reminderIntervalMinutes.toString())
        binding.layoutReminderInterval.visibility = if (prefs.reminderEnabled) View.VISIBLE else View.GONE

        binding.rowSummary.rowSwitch.isChecked = prefs.summaryEnabled
        binding.etSummaryHour.setText(prefs.summaryHour.toString())
        binding.toggleSummaryFrequency.check(
            if (prefs.summaryFrequency == 1) R.id.btn_summary_weekly else R.id.btn_summary_daily
        )
        binding.layoutSummaryOptions.visibility = if (prefs.summaryEnabled) View.VISIBLE else View.GONE

        binding.rowFuzzy.rowSwitch.isChecked = prefs.fuzzyDedupEnabled
    }

    private fun saveSettings() {
        prefs.defaultWalletName = binding.etWalletName.text.toString().trim()
        prefs.minAmount = binding.etMinAmount.text.toString().toDoubleOrNull() ?: 0.0
        prefs.confirmBeforeAdding = binding.rowConfirm.rowSwitch.isChecked
        val dedupSec = binding.etDedupWindow.text.toString().toLongOrNull() ?: 5L
        prefs.skipDuplicateWindowMs = dedupSec.coerceAtLeast(1L) * 1000L

        prefs.autoDismissSource = binding.rowAutoDismiss.rowSwitch.isChecked
        prefs.undoEnabled = binding.rowUndo.rowSwitch.isChecked
        prefs.privacyMode = binding.rowPrivacy.rowSwitch.isChecked
        prefs.largeTransactionThreshold = binding.etLargeTxThreshold.text.toString().toDoubleOrNull() ?: 0.0
        prefs.batchMode = binding.rowBatch.rowSwitch.isChecked
        prefs.batchWindowMinutes = binding.etBatchWindow.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 15

        val reminderWasEnabled = prefs.reminderEnabled
        prefs.reminderEnabled = binding.rowReminder.rowSwitch.isChecked
        prefs.reminderIntervalMinutes = binding.etReminderInterval.text.toString().toIntOrNull()?.coerceAtLeast(5) ?: 30
        if (prefs.reminderEnabled && !reminderWasEnabled) scheduleReminderAlarm()
        else if (!prefs.reminderEnabled) cancelReminderAlarm()

        prefs.summaryEnabled = binding.rowSummary.rowSwitch.isChecked
        prefs.summaryHour = binding.etSummaryHour.text.toString().toIntOrNull()?.coerceIn(0, 23) ?: 21
        prefs.summaryFrequency = if (binding.toggleSummaryFrequency.checkedButtonId == R.id.btn_summary_weekly) 1 else 0
        if (prefs.summaryEnabled) {
            SummaryReceiver.schedule(this, prefs)
        } else {
            SummaryReceiver.cancel(this)
        }

        prefs.fuzzyDedupEnabled = binding.rowFuzzy.rowSwitch.isChecked

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
