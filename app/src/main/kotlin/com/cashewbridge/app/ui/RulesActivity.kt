package com.cashewbridge.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cashewbridge.app.R
import com.cashewbridge.app.databinding.ActivityRulesBinding
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.NotificationRule
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class RulesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRulesBinding
    private lateinit var db: AppDatabase
    private val adapter = RulesAdapter(
        onEdit = { showEditDialog(it) },
        onDelete = { deleteRule(it) },
        onToggle = { rule, enabled -> toggleRule(rule, enabled) }
    )

    // Export: create a JSON file
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri?.let { exportRulesToUri(it) }
    }

    // Import: pick a JSON file
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importRulesFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db = AppDatabase.getInstance(this)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fab.setOnClickListener { showEditDialog(null) }

        lifecycleScope.launch {
            db.ruleDao().getAllRules().collectLatest { rules ->
                adapter.submitList(rules)
                binding.tvEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_rules, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.action_export_rules -> {
                exportLauncher.launch("cashew_bridge_rules.json")
                true
            }
            R.id.action_import_rules -> {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showEditDialog(rule: NotificationRule?) {
        RuleEditDialogFragment.newInstance(rule).show(supportFragmentManager, "edit_rule")
    }

    private fun deleteRule(rule: NotificationRule) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_rule)
            .setMessage(getString(R.string.delete_rule_confirm, rule.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { db.ruleDao().deleteRule(rule) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleRule(rule: NotificationRule, enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.ruleDao().updateRule(rule.copy(isEnabled = enabled))
        }
    }

    // ---- Export / Import ----

    private fun exportRulesToUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rules = db.ruleDao().getAllRulesSync()
                val jsonArray = JSONArray()
                rules.forEach { rule ->
                    jsonArray.put(JSONObject().apply {
                        put("name", rule.name)
                        put("packageName", rule.packageName)
                        put("titleContains", rule.titleContains)
                        put("bodyContains", rule.bodyContains)
                        put("amountRegex", rule.amountRegex)
                        put("merchantRegex", rule.merchantRegex)
                        put("defaultCategory", rule.defaultCategory)
                        put("defaultWalletName", rule.defaultWalletName)
                        put("isIncome", rule.isIncome)
                        put("autoDetectType", rule.autoDetectType)
                        put("isEnabled", rule.isEnabled)
                        put("priority", rule.priority)
                    })
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonArray.toString(2).toByteArray())
                }
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root,
                        getString(R.string.export_success, rules.size), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root,
                        getString(R.string.export_failed, e.message), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importRulesFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val json = contentResolver.openInputStream(uri)?.use { it.reader().readText() }
                    ?: throw Exception("Cannot read file")
                val jsonArray = JSONArray(json)
                var imported = 0
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val rule = NotificationRule(
                        name = obj.optString("name", "Imported Rule"),
                        packageName = obj.optString("packageName", ""),
                        titleContains = obj.optString("titleContains", ""),
                        bodyContains = obj.optString("bodyContains", ""),
                        amountRegex = obj.optString("amountRegex", ""),
                        merchantRegex = obj.optString("merchantRegex", ""),
                        defaultCategory = obj.optString("defaultCategory", ""),
                        defaultWalletName = obj.optString("defaultWalletName", ""),
                        isIncome = obj.optBoolean("isIncome", false),
                        autoDetectType = obj.optBoolean("autoDetectType", false),
                        isEnabled = obj.optBoolean("isEnabled", true),
                        priority = obj.optInt("priority", 0)
                    )
                    db.ruleDao().insertRule(rule)
                    imported++
                }
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root,
                        getString(R.string.import_success, imported), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root,
                        getString(R.string.import_failed, e.message), Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }
}

class RulesAdapter(
    private val onEdit: (NotificationRule) -> Unit,
    private val onDelete: (NotificationRule) -> Unit,
    private val onToggle: (NotificationRule, Boolean) -> Unit
) : RecyclerView.Adapter<RulesAdapter.RuleViewHolder>() {

    private var rules = listOf<NotificationRule>()

    fun submitList(list: List<NotificationRule>) {
        rules = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_rule, parent, false)
        return RuleViewHolder(view)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) = holder.bind(rules[position])
    override fun getItemCount() = rules.size

    inner class RuleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_rule_name)
        private val tvPackage: TextView = itemView.findViewById(R.id.tv_rule_package)
        private val tvDetails: TextView = itemView.findViewById(R.id.tv_rule_details)
        private val chipEnabled: Chip = itemView.findViewById(R.id.chip_enabled)
        private val chipIncome: Chip = itemView.findViewById(R.id.chip_income)

        fun bind(rule: NotificationRule) {
            tvName.text = rule.name
            tvPackage.text = rule.packageName.ifBlank { "All apps" }
            val details = buildString {
                if (rule.titleContains.isNotBlank()) append("Title: \"${rule.titleContains}\" ")
                if (rule.bodyContains.isNotBlank()) append("Body: \"${rule.bodyContains}\" ")
                if (rule.defaultCategory.isNotBlank()) append("Category: ${rule.defaultCategory}")
                if (rule.autoDetectType) append(" [Auto type]")
            }
            tvDetails.text = details.ifBlank { "Match all notifications" }
            chipEnabled.isChecked = rule.isEnabled
            chipEnabled.text = if (rule.isEnabled) "Enabled" else "Disabled"
            chipIncome.visibility = when {
                rule.autoDetectType -> View.VISIBLE.also { chipIncome.text = "Auto type" }
                rule.isIncome -> View.VISIBLE.also { chipIncome.text = "Income" }
                else -> View.GONE
            }

            chipEnabled.setOnCheckedChangeListener { _, checked -> onToggle(rule, checked) }
            itemView.setOnClickListener { onEdit(rule) }
            itemView.setOnLongClickListener { onDelete(rule); true }
        }
    }
}
