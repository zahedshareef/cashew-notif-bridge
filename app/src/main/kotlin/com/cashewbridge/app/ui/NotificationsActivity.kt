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
import com.google.android.material.chip.ChipGroup
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

    private var activeFilter: String? = null  // null = show all
    private var allNotifications: List<CachedNotification> = emptyList()

    private val adapter = NotificationsAdapter(
        onSendToCashew = { notif -> showSendDialog(notif) },
        onDismiss = { notif -> NotificationCache.remove(notif.key, applicationContext) },
        onCreateRule = { notif -> showCreateRuleDialog(notif) }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        BottomNavHelper.setup(this, binding.bottomNav.root, R.id.nav_notifications)

        prefs = AppPreferences(this)
        db = AppDatabase.getInstance(this)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnClearAll.setOnClickListener {
            NotificationCache.clear(applicationContext)
            Snackbar.make(binding.root, R.string.notifications_cleared, Snackbar.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            NotificationCache.notifications.collectLatest { notifications ->
                allNotifications = notifications
                updateFilterChips(notifications)
                applyFilter()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun updateFilterChips(notifications: List<CachedNotification>) {
        val chipGroup: ChipGroup = binding.filterChipGroup
        chipGroup.removeAllViews()

        // "All" chip
        val allChip = Chip(this).apply {
            text = getString(R.string.filter_all)
            isCheckable = true
            isChecked = activeFilter == null
            setOnClickListener {
                activeFilter = null
                updateChipSelection(chipGroup, this)
                applyFilter()
            }
        }
        chipGroup.addView(allChip)

        // One chip per unique app
        val apps = notifications.map { it.appLabel to it.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first }

        apps.forEach { (label, pkg) ->
            val count = notifications.count { it.packageName == pkg }
            val chip = Chip(this).apply {
                text = "$label ($count)"
                isCheckable = true
                isChecked = activeFilter == pkg
                setOnClickListener {
                    activeFilter = pkg
                    updateChipSelection(chipGroup, this)
                    applyFilter()
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun updateChipSelection(group: ChipGroup, selected: Chip) {
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            chip.isChecked = chip == selected
        }
    }

    private fun applyFilter() {
        val filtered = if (activeFilter == null) allNotifications
        else allNotifications.filter { it.packageName == activeFilter }

        // Build display list: group by app (insert headers between app groups)
        val displayItems = mutableListOf<NotifListItem>()
        var lastApp: String? = null
        for (notif in filtered) {
            if (notif.appLabel != lastApp) {
                displayItems.add(NotifListItem.Header(notif.appLabel))
                lastApp = notif.appLabel
            }
            displayItems.add(NotifListItem.Item(notif))
        }

        adapter.submitItems(displayItems)

        val count = filtered.size
        binding.tvCount.text = if (count == 0) getString(R.string.no_notifications)
        else resources.getQuantityString(R.plurals.notification_count_plural, count, count)

        binding.tvEmpty.visibility = if (filtered.isEmpty()) android.view.View.VISIBLE
        else android.view.View.GONE
    }

    private fun showSendDialog(notif: CachedNotification) {
        val view = layoutInflater.inflate(R.layout.dialog_send_transaction, null)
        val etAmount = view.findViewById<TextInputEditText>(R.id.et_amount)
        val etMerchant = view.findViewById<TextInputEditText>(R.id.et_merchant)
        val etCategory = view.findViewById<TextInputEditText>(R.id.et_category)
        val etNote = view.findViewById<TextInputEditText>(R.id.et_note)
        val switchIncome = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switch_income)
        val tvOriginal = view.findViewById<TextView>(R.id.tv_raw_notification)
        val tvNoAmount = view.findViewById<TextView>(R.id.tv_no_amount_warning)

        tvOriginal.text = "${notif.title}\n${notif.body}"
        etAmount.setText(notif.parsedAmount?.toString() ?: "")
        etMerchant.setText(notif.parsedMerchant ?: "")
        etCategory.setText(notif.parsedCategory ?: "")
        switchIncome.isChecked = notif.isIncome
        tvNoAmount.visibility =
            if (notif.parsedAmount == null) android.view.View.VISIBLE else android.view.View.GONE

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.send_to_cashew_title, notif.appLabel))
            .setView(view)
            .setPositiveButton(R.string.send_to_cashew) { _, _ ->
                val amountStr = etAmount.text.toString().trim()
                val amount = amountStr.toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    Snackbar.make(binding.root, R.string.error_invalid_amount, Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val walletName = prefs.defaultWalletName
                val uriBuilder = Uri.Builder()
                    .scheme("cashewapp").authority("addTransaction")
                    .appendQueryParameter("amount", amount.toString())
                    .appendQueryParameter("income", switchIncome.isChecked.toString())
                    .appendQueryParameter("updateData", "true")

                etMerchant.text.toString().trim().takeIf { it.isNotBlank() }?.let {
                    uriBuilder.appendQueryParameter("title", it)
                }
                etNote.text.toString().trim().takeIf { it.isNotBlank() }?.let {
                    uriBuilder.appendQueryParameter("note", it)
                }
                etCategory.text.toString().trim().takeIf { it.isNotBlank() }?.let {
                    uriBuilder.appendQueryParameter("categoryName", it)
                }
                walletName.takeIf { it.isNotBlank() }?.let {
                    uriBuilder.appendQueryParameter("walletName", it)
                }

                val uri = uriBuilder.build()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    startActivity(intent)
                    NotificationCache.remove(notif.key, applicationContext)
                    Snackbar.make(binding.root, R.string.sent_to_cashew, Snackbar.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Snackbar.make(binding.root,
                        getString(R.string.error_cashew_not_found), Snackbar.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCreateRuleDialog(notif: CachedNotification) {
        val dialog = RuleEditDialogFragment.newInstanceForNotification(
            packageName = notif.packageName,
            appLabel = notif.appLabel
        )
        dialog.show(supportFragmentManager, "create_rule")
    }
}

/** Sealed type for the notification list — supports both group headers and notification items. */
sealed class NotifListItem {
    data class Header(val appLabel: String) : NotifListItem()
    data class Item(val notif: CachedNotification) : NotifListItem()
}

class NotificationsAdapter(
    private val onSendToCashew: (CachedNotification) -> Unit,
    private val onDismiss: (CachedNotification) -> Unit,
    private val onCreateRule: (CachedNotification) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items = listOf<NotifListItem>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    fun submitItems(list: List<NotifListItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is NotifListItem.Header -> TYPE_HEADER
        is NotifListItem.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_HEADER) {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notif_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notification, parent, false)
            NotifViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is NotifListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is NotifListItem.Item -> (holder as NotifViewHolder).bind(item.notif)
        }
    }

    override fun getItemCount() = items.size

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLabel: TextView = itemView.findViewById(R.id.tv_header_label)
        fun bind(header: NotifListItem.Header) { tvLabel.text = header.appLabel }
    }

    inner class NotifViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvAppLabel: TextView = itemView.findViewById(R.id.tv_app_label)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_time)
        private val tvTitle: TextView = itemView.findViewById(R.id.tv_notif_title)
        private val tvBody: TextView = itemView.findViewById(R.id.tv_notif_body)
        private val chipAmount: Chip = itemView.findViewById(R.id.chip_amount)
        private val chipType: Chip = itemView.findViewById(R.id.chip_type)
        private val chipRule: Chip = itemView.findViewById(R.id.chip_rule)
        private val btnSend: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.btn_send)
        private val btnDismiss: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.btn_dismiss)
        private val btnCreateRule: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.btn_create_rule)
        private val ivAppIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)

        fun bind(notif: CachedNotification) {
            tvAppLabel.text = notif.appLabel
            tvTitle.text = notif.title
            tvBody.text = notif.body

            val cal = Calendar.getInstance()
            val todayDay = cal.get(Calendar.DAY_OF_YEAR)
            cal.timeInMillis = notif.timestamp
            val notifDay = cal.get(Calendar.DAY_OF_YEAR)
            tvTime.text = if (notifDay == todayDay) {
                timeFormat.format(Date(notif.timestamp))
            } else {
                "${dateFormat.format(Date(notif.timestamp))} ${timeFormat.format(Date(notif.timestamp))}"
            }

            if (notif.parsedAmount != null) {
                chipAmount.visibility = android.view.View.VISIBLE
                val prefix = if (notif.isIncome) "+" else "-"
                val merchant = notif.parsedMerchant?.let { " · $it" } ?: ""
                chipAmount.text = "$prefix${notif.parsedAmount}$merchant"
                chipAmount.setChipBackgroundColorResource(
                    if (notif.isIncome) R.color.chip_income_bg else R.color.chip_expense_bg
                )
                chipAmount.setTextColor(
                    ContextCompat.getColor(itemView.context,
                        if (notif.isIncome) R.color.status_active else R.color.status_error)
                )
            } else {
                chipAmount.visibility = android.view.View.VISIBLE
                chipAmount.text = "No amount detected"
                chipAmount.setChipBackgroundColorResource(R.color.chip_neutral_bg)
                chipAmount.setTextColor(ContextCompat.getColor(itemView.context, R.color.chip_neutral_text))
            }

            chipType.visibility = if (notif.parsedAmount != null) {
                chipType.text = if (notif.isIncome) "Income" else "Expense"
                android.view.View.VISIBLE
            } else android.view.View.GONE

            chipRule.visibility = if (notif.matchedRuleName.isNotBlank()) {
                chipRule.text = notif.matchedRuleName
                android.view.View.VISIBLE
            } else android.view.View.GONE

            try {
                val icon: Drawable = itemView.context.packageManager.getApplicationIcon(notif.packageName)
                ivAppIcon.setImageDrawable(icon)
            } catch (e: Exception) {
                ivAppIcon.setImageResource(R.drawable.ic_status_paused)
            }

            btnSend.setOnClickListener { onSendToCashew(notif) }
            btnDismiss.setOnClickListener { onDismiss(notif) }
            btnCreateRule.setOnClickListener { onCreateRule(notif) }
            itemView.setOnClickListener { onSendToCashew(notif) }
        }
    }
}
