package com.cashewbridge.app.ui

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

        binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            prefs.isEnabled = isChecked
            updateStatusCard()
        }
        binding.switchEnabled.isChecked = prefs.isEnabled

        binding.btnSaveSettings.setOnClickListener { saveSettings() }

        // Theme toggle
        binding.btnThemeAuto.setOnClickListener { setTheme(0) }
        binding.btnThemeLight.setOnClickListener { setTheme(1) }
        binding.btnThemeDark.setOnClickListener { setTheme(2) }
        updateThemeButtons()

        // Battery optimization warning
        binding.btnFixBattery.setOnClickListener {
            openBatteryOptimizationSettings()
        }
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

    private fun applyTheme() {
        val mode = when (prefs.themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun setTheme(mode: Int) {
        prefs.themeMode = mode
        applyTheme()
        updateThemeButtons()
    }

    private fun updateThemeButtons() {
        val mode = prefs.themeMode
        binding.btnThemeAuto.isSelected = mode == 0
        binding.btnThemeLight.isSelected = mode == 1
        binding.btnThemeDark.isSelected = mode == 2
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(packageName)
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

            val forwarded = withContext(Dispatchers.IO) {
                db.logDao().countForwardedSince(startOfDay)
            }
            val skipped = withContext(Dispatchers.IO) {
                db.logDao().countSkippedSince(startOfDay)
            }
            val pending = withContext(Dispatchers.IO) {
                db.logDao().countPendingSince(startOfDay)
            }

            binding.tvStats.text = when {
                forwarded + skipped + pending == 0 -> getString(R.string.stats_none_today)
                else -> getString(R.string.stats_today, forwarded, skipped, pending)
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (prefs.batteryOptDismissed) {
            binding.cardBatteryOpt.visibility = View.GONE
            return
        }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoringBatteryOpt = pm.isIgnoringBatteryOptimizations(packageName)
        binding.cardBatteryOpt.visibility = if (ignoringBatteryOpt) View.GONE else View.VISIBLE
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
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
                .setNegativeButton(R.string.onboarding_later) { _, _ ->
                    prefs.onboardingDone = true
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun loadSettings() {
        binding.etWalletName.setText(prefs.defaultWalletName)
        binding.etMinAmount.setText(if (prefs.minAmount > 0) prefs.minAmount.toString() else "")
        binding.switchConfirm.isChecked = prefs.confirmBeforeAdding
        val dedupSec = prefs.skipDuplicateWindowMs / 1000
        binding.etDedupWindow.setText(if (dedupSec > 0) dedupSec.toString() else "5")
    }

    private fun saveSettings() {
        prefs.defaultWalletName = binding.etWalletName.text.toString().trim()
        prefs.minAmount = binding.etMinAmount.text.toString().toDoubleOrNull() ?: 0.0
        prefs.confirmBeforeAdding = binding.switchConfirm.isChecked
        val dedupSec = binding.etDedupWindow.text.toString().toLongOrNull() ?: 5L
        prefs.skipDuplicateWindowMs = dedupSec.coerceAtLeast(1L) * 1000L
        Snackbar.make(binding.root, R.string.settings_saved, Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(R.string.about_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
