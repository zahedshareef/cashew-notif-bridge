package com.cashewbridge.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cashewbridge.app.R
import com.cashewbridge.app.databinding.ActivityLogsBinding
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.ProcessedLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private lateinit var db: AppDatabase
    private val adapter = LogsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        db = AppDatabase.getInstance(this)

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnClearLogs.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_logs)
                .setMessage(R.string.clear_logs_confirm)
                .setPositiveButton(R.string.clear) { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.logDao().clearAll()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        lifecycleScope.launch {
            db.logDao().getRecentLogs().collectLatest { logs ->
                adapter.submitList(logs)
                binding.tvEmpty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

class LogsAdapter : RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

    private var logs = listOf<ProcessedLog>()
    private val dateFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())

    fun submitList(list: List<ProcessedLog>) {
        logs = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount() = logs.size

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tv_timestamp)
        private val tvSource: TextView = itemView.findViewById(R.id.tv_source)
        private val tvAmount: TextView = itemView.findViewById(R.id.tv_amount)
        private val tvAction: TextView = itemView.findViewById(R.id.tv_action)
        private val tvRaw: TextView = itemView.findViewById(R.id.tv_raw)

        fun bind(log: ProcessedLog) {
            tvTimestamp.text = dateFormat.format(Date(log.timestamp))
            tvSource.text = log.sourceTitle.ifBlank { log.sourcePackage }

            if (log.parsedAmount != null) {
                tvAmount.visibility = View.VISIBLE
                val type = if (log.isIncome) "+" else "-"
                val merchant = log.parsedMerchant?.let { " ($it)" } ?: ""
                tvAmount.text = "$type${log.parsedAmount}$merchant"
            } else {
                tvAmount.visibility = View.GONE
            }

            tvAction.text = log.actionTaken
            val actionColor = when {
                log.actionTaken == "LAUNCHED" -> R.color.status_active
                log.actionTaken.startsWith("FAILED") -> R.color.status_error
                else -> R.color.status_paused
            }
            tvAction.setTextColor(ContextCompat.getColor(itemView.context, actionColor))

            tvRaw.text = log.rawText.take(120)

            itemView.setOnClickListener {
                // Show full details
                val detail = buildString {
                    appendLine("Time: ${dateFormat.format(Date(log.timestamp))}")
                    appendLine("App: ${log.sourcePackage}")
                    appendLine("Title: ${log.sourceTitle}")
                    appendLine("Amount: ${log.parsedAmount}")
                    appendLine("Merchant: ${log.parsedMerchant}")
                    appendLine("Category: ${log.parsedCategory}")
                    appendLine("Income: ${log.isIncome}")
                    appendLine("Rule: ${log.matchedRuleName.ifBlank { "heuristic" }}")
                    appendLine("Action: ${log.actionTaken}")
                    appendLine()
                    appendLine("Raw text:")
                    appendLine(log.rawText)
                }
                MaterialAlertDialogBuilder(itemView.context)
                    .setTitle("Log Details")
                    .setMessage(detail)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }
}
