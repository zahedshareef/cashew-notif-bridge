package com.cashewbridge.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.cashewbridge.app.R
import com.cashewbridge.app.databinding.ActivityMainBinding
import com.cashewbridge.app.prefs.AppPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = AppPreferences(this)

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

        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }

        loadSettings()
        updateStatusCard()
    }

    override fun onResume() {
        super.onResume()
        updateStatusCard()
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(packageName)
    }

    private fun updateStatusCard() {
        val hasPermission = isNotificationListenerEnabled()
        val serviceEnabled = prefs.isEnabled

        if (!hasPermission) {
            binding.statusIcon.setImageResource(R.drawable.ic_status_error)
            binding.statusTitle.text = getString(R.string.status_no_permission)
            binding.statusSubtitle.text = getString(R.string.status_no_permission_detail)
            binding.btnGrantPermission.isEnabled = true
        } else if (!serviceEnabled) {
            binding.statusIcon.setImageResource(R.drawable.ic_status_paused)
            binding.statusTitle.text = getString(R.string.status_paused)
            binding.statusSubtitle.text = getString(R.string.status_paused_detail)
            binding.btnGrantPermission.isEnabled = false
        } else {
            binding.statusIcon.setImageResource(R.drawable.ic_status_active)
            binding.statusTitle.text = getString(R.string.status_active)
            binding.statusSubtitle.text = getString(R.string.status_active_detail)
            binding.btnGrantPermission.isEnabled = false
        }
    }

    private fun loadSettings() {
        binding.etWalletName.setText(prefs.defaultWalletName)
        binding.etMinAmount.setText(
            if (prefs.minAmount > 0) prefs.minAmount.toString() else ""
        )
        binding.switchConfirm.isChecked = prefs.confirmBeforeAdding
    }

    private fun saveSettings() {
        prefs.defaultWalletName = binding.etWalletName.text.toString().trim()
        prefs.minAmount = binding.etMinAmount.text.toString().toDoubleOrNull() ?: 0.0
        prefs.confirmBeforeAdding = binding.switchConfirm.isChecked
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
