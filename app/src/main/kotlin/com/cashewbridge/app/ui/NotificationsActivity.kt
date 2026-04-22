package com.cashewbridge.app.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cashewbridge.app.R
import com.cashewbridge.app.databinding.ActivityNotificationsBinding
import com.cashewbridge.app.model.CachedNotification
import com.cashewbridge.app.parser.CashewLinkBuilder
import com.cashewbridge.app.parser.NotificationParser
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.NotificationRule
import com.cashewbridge.app.prefs.AppPreferences
import com.cashewbridge.app.service.NotificationCache
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding
    private lateinit var prefs: AppPreferences
    private lateinit var db: AppDatabase
    private val adapter = NotificationsAdapter(
        onSendToCashew = { notif -> showSendDialog(notif) },
        onDismiss = { notif -> NotificationCache.remove(notif.key) },
        onCreateRule = { notif -> showCreateRuleDialog(notif) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = AppPreferences(this)
        db = AppDatabase.getInstance(this)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnClearAll.setOnClickListener {
            NotificationCache.clear()
            Snackbar.make(binding.root, R.string.notifications_cleared, Snackbar.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            NotificationCache.notifications.collectLatest { list ->
                adapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.tvCount.text = getString(R.string.notification_count, list.size)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun showSendDialog(notif: CachedNotification) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_send_transaction, null)

        val etAmount = dialogView.findViewById<TextInputEditText>(R.id.et_amount)
        val etMerchant = dialogView.findViewById<TextInputEditText>(R.id.et_merchant)
        val etCategory = dialogView.findViewById<TextInputEditText>(R.id.et_category)
        val etWallet = dialogView.findViewById<TextInputEditText>(R.id.et_wallet)
        val etNote = dialogView.findViewById<TextInputEditText>(R.id.et_note)
        val switchIncome = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_income)
        val tvRaw = dialogView.findViewById<TextView>(R.id.tv_raw_notification)
        val tvNoAmount = dialogView.findViewById<TextView>(R.id.tv_no_amount_warning)

        // Pre-fill with parsed values
        etAmount.setText(notif.parsedAmount?.toString() ?: "")
        etMerchant.setText(notif.parsedMerchant ?: "")
        etCategory.setText(notif.parsedCategory ?: "")
        etWallet.setText(notif.parsedWallet ?: prefs.defaultWalletName)
        switchIncome.isChecked = notif.isIncome
        tvRaw.text = "${notif.title}\n${notif.body}"
        tvNoAmount.visibility = if (notif.parsedAmount == null) View.VISIBLE else View.GONE

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.send_to_cashew_title, notif.appLabel))
            .setView(dialogView)
            .setPositiveButton(R.string.send_to_cashew) { _, _ ->
                val amount = etAmount.text.toString().toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    Snackbar.make(binding.root, R.string.error_invalid_amount, Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val merchant = etMerchant.text.toString().trim()
                val category = etCategory.text.toString().trim()
                val wallet = etWallet.text.toString().trim()
                val note = etNote.text.toString().trim()
                val isIncome = switchIncome.isChecked

                // Build and fire the Cashew deep link
                val uri = Uri.Builder()
                    .scheme("cashewapp")
                    .authority("addTransaction")
                    .appendQueryParameter("amount", amount.toString())
                    .appendQueryParameter("income", isIncome.toString())
                    .appendQueryParameter("updateData", "true")
                    .apply {
                        if (merchant.isNotBlank()) appendQueryParameter("title", merchant)
                        if (note.isNotBlank()) appendQueryParameter("note", note)
                        if (category.isNotBlank()) appendQueryParameter("categoryName", category)
                        if (wallet.isNotBlank()) appendQueryParameter("walletName", wallet)
                    }
                    .build()

                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    startActivity(intent)
                    NotificationCache.remove(notif.key)
                    Snackbar.make(binding.root, R.string.sent_to_cashew, Snackbar.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Snackbar.make(binding.root,
                        getString(R.string.error_cashew_not_found),
                        Snackbar.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCreateRuleDialog(notif: CachedNotification) {
        // Package name is auto-populated from the notification — user just fills in parse rules
        val dialog = RuleEditDialogFragment.newInstanceForNotification(
            packageName = notif.packageName,
            appLabel = notif.appLabel
        )
        dialog.show(supportFragmentManager, "create_rule")
        Snackbar.make(binding.root, R.string.rule_saved, Snackbar.LENGTH_SHORT).show()
    }
}

class NotificationsAdapter(
    private val onSendToCashew: (CachedNotification) -> Unit,
    private val onDismiss: (CachedNotification) -> Unit,
    private val onCreateRule: (CachedNotification) -> Unit
) : RecyclerView.Adapter<NotificationsAdapter.NotifViewHolder>() {

    private var items = listOf<CachedNotification>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

    fun submitList(list: List<CachedNotification>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotifViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return NotifViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotifViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class NotifViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAppLabel: TextView = itemView.findViewById(R.id.tv_app_label)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_notif_title)
        private val tvBody: TextView = itemView.findViewById(R.id.tv_notif_body)
        private val chipAmount: Chip = itemView.findViewById(R.id.chip_amount)
        private val chipType: Chip = itemView.findViewById(R.id.chip_type)
        private val chipRule: Chip = itemView.findViewById(R.id.chip_rule)
        private val btnSend: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.btn_send)
        private val btnDismiss: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.btn_dismiss)
        private val btnCreateRule: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.btn_create_rule)
        private val ivAppIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)

        fun bind(notif: CachedNotification) {
            tvAppLabel.text = notif.appLabel
            tvTitle.text = notif.title
            tvBody.text = notif.body

            // Timestamp: show date if not today
            val cal = Calendar.getInstance()
            val todayDay = cal.get(Calendar.DAY_OF_YEAR)
            cal.timeInMillis = notif.timestamp
            val notifDay = cal.get(Calendar.DAY_OF_YEAR)
            tvTime.text = if (notifDay == todayDay) {
                timeFormat.format(Date(notif.timestamp))
            } else {
                "${dateFormat.format(Date(notif.timestamp))} ${timeFormat.format(Date(notif.timestamp))}"
            }

            // Amount chip
            if (notif.parsedAmount != null) {
                chipAmount.visibility = View.VISIBLE
                val prefix = if (notif.isIncome) "+" else "-"
                val merchant = notif.parsedMerchant?.let { " · $it" } ?: ""
                chipAmount.text = "$prefix${notif.parsedAmount}$merchant"
                chipAmount.setChipBackgroundColorResource(
                    if (notif.isIncome) R.color.chip_income_bg else R.color.chip_expense_bg
                )
                chipAmount.setTextColor(
                    ContextCompat.getColor(
                        itemView.context,
                        if (notif.isIncome) R.color.status_active else R.color.status_error
                    )
                )
            } else {
                chipAmount.visibility = View.VISIBLE
                chipAmount.text = "No amount detected"
                chipAmount.setChipBackgroundColorResource(R.color.chip_neutral_bg)
                chipAmount.setTextColor(
                    ContextCompat.getColor(itemView.context, R.color.chip_neutral_text)
                )
            }

            // Income/Expense type chip (only if amount found)
            if (notif.parsedAmount != null) {
                chipType.visibility = View.VISIBLE
                chipType.text = if (notif.isIncome) "Income" else "Expense"
            } else {
                chipType.visibility = View.GONE
            }

            // Rule chip
            if (notif.matchedRuleName.isNotBlank()) {
                chipRule.visibility = View.VISIBLE
                chipRule.text = notif.matchedRuleName
            } else {
                chipRule.visibility = View.GONE
            }

            // App icon
            try {
                val icon: Drawable = itemView.context.packageManager
                    .getApplicationIcon(notif.packageName)
                ivAppIcon.setImageDrawable(icon)
            } catch (e: Exception) {
                ivAppIcon.setImageResource(R.drawable.ic_status_paused)
            }

            btnSend.setOnClickListener { onSendToCashew(notif) }
            btnDismiss.setOnClickListener { onDismiss(notif) }
            btnCreateRule.setOnClickListener { onCreateRule(notif) }

            // Tap card to send
            itemView.setOnClickListener { onSendToCashew(notif) }
        }
    }
}
