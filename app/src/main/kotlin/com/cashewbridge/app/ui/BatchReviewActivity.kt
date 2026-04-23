package com.cashewbridge.app.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cashewbridge.app.R
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.BatchedTransaction
import com.cashewbridge.app.service.NotificationHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

class BatchReviewActivity : AppCompatActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnSendAll: MaterialButton
    private lateinit var btnDismissAll: MaterialButton
    private val adapter = BatchAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_batch_review)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        BottomNavHelper.setup(
            this,
            findViewById(R.id.bottom_nav),
            R.id.nav_more
        )

        recycler = findViewById(R.id.recycler)
        tvEmpty = findViewById(R.id.tv_empty)
        btnSendAll = findViewById(R.id.btn_send_all)
        btnDismissAll = findViewById(R.id.btn_dismiss_all)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        adapter.onSendClick = { tx ->
            launchCashew(tx.cashewUri)
            lifecycleScope.launch {
                AppDatabase.getInstance(applicationContext).batchedTransactionDao().deleteById(tx.id)
                loadBatch()
            }
        }
        adapter.onDismissClick = { tx ->
            lifecycleScope.launch {
                AppDatabase.getInstance(applicationContext).batchedTransactionDao().deleteById(tx.id)
                loadBatch()
            }
        }

        btnSendAll.setOnClickListener {
            lifecycleScope.launch {
                val all = AppDatabase.getInstance(applicationContext).batchedTransactionDao().getAll()
                all.forEach { launchCashew(it.cashewUri) }
                AppDatabase.getInstance(applicationContext).batchedTransactionDao().deleteAll()
                Toast.makeText(this@BatchReviewActivity, "Sent ${all.size} transactions to Cashew", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        btnDismissAll.setOnClickListener {
            lifecycleScope.launch {
                AppDatabase.getInstance(applicationContext).batchedTransactionDao().deleteAll()
                Toast.makeText(this@BatchReviewActivity, "Batch cleared", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // Dismiss the batch-ready notification when the review screen opens
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NotificationHelper.ID_BATCH_READY)

        loadBatch()
    }

    private fun loadBatch() {
        lifecycleScope.launch {
            val items = AppDatabase.getInstance(applicationContext).batchedTransactionDao().getAll()
            runOnUiThread {
                adapter.submitList(items.toMutableList())
                val hasItems = items.isNotEmpty()
                recycler.visibility = if (hasItems) View.VISIBLE else View.GONE
                tvEmpty.visibility = if (hasItems) View.GONE else View.VISIBLE
                btnSendAll.isEnabled = hasItems
                btnDismissAll.isEnabled = hasItems
                if (!hasItems) {
                    // Auto-close when batch becomes empty after individual dismissals
                    if (adapter.itemCount == 0) finish()
                }
            }
        }
    }

    private fun launchCashew(uriString: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open Cashew — is it installed?", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ── RecyclerView adapter ──────────────────────────────────────────────────

    inner class BatchAdapter : RecyclerView.Adapter<BatchAdapter.VH>() {

        var onSendClick: ((BatchedTransaction) -> Unit)? = null
        var onDismissClick: ((BatchedTransaction) -> Unit)? = null

        private val items = mutableListOf<BatchedTransaction>()

        fun submitList(list: MutableList<BatchedTransaction>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_batch_transaction, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val tvApp: TextView = v.findViewById(R.id.tv_app_label)
            private val tvAmount: TextView = v.findViewById(R.id.tv_amount)
            private val tvMerchant: TextView = v.findViewById(R.id.tv_merchant)
            private val chipType: Chip = v.findViewById(R.id.chip_type)
            private val btnSend: MaterialButton = v.findViewById(R.id.btn_send)
            private val btnDismiss: MaterialButton = v.findViewById(R.id.btn_dismiss)

            fun bind(tx: BatchedTransaction) {
                tvApp.text = tx.appLabel
                tvAmount.text = formatAmount(tx.amount)
                tvMerchant.text = tx.merchant?.takeIf { it.isNotBlank() } ?: tx.sourceTitle
                chipType.text = if (tx.isIncome) "Income" else "Expense"
                btnSend.setOnClickListener { onSendClick?.invoke(tx) }
                btnDismiss.setOnClickListener { onDismissClick?.invoke(tx) }
            }

            private fun formatAmount(amount: Double): String =
                if (amount == amount.toLong().toDouble()) amount.toLong().toString()
                else "%.2f".format(amount)
        }
    }
}
