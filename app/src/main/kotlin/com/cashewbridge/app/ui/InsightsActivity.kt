package com.cashewbridge.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cashewbridge.app.R
import com.cashewbridge.app.databinding.ActivityInsightsBinding
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.CategoryCurrencyTotal
import com.cashewbridge.app.model.CurrencyTotal
import com.cashewbridge.app.model.DayTotal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Insights screen — totals of transactions the bridge forwarded in the last 30 days.
 * Totals are split by ISO-4217 currency since the bridge supports 9 currencies
 * (USD, INR, GBP, EUR, AED, SGD, AUD, CAD, JPY) and summing across them would lie.
 */
class InsightsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInsightsBinding
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        BottomNavHelper.setup(this, binding.bottomNav.root, R.id.nav_insights)

        db = AppDatabase.getInstance(this)
        loadInsights()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadInsights() {
        lifecycleScope.launch {
            val since30d = System.currentTimeMillis() - 30L * 24 * 3600_000

            val snapshot = withContext(Dispatchers.IO) {
                val dao = db.logDao()
                InsightsSnapshot(
                    dailyExpenses = dao.getDailyExpenses(since30d),
                    expenseTotals = dao.getExpenseTotalsByCurrency(since30d),
                    incomeTotals = dao.getIncomeTotalsByCurrency(since30d),
                    categories = dao.getCategoryBreakdownByCurrency(since30d)
                )
            }

            binding.tvTotalExpense.text = formatTotals(snapshot.expenseTotals)
            binding.tvTotalIncome.text = formatTotals(snapshot.incomeTotals)

            binding.barChart.setData(snapshot.dailyExpenses)

            binding.rvCategories.layoutManager = LinearLayoutManager(this@InsightsActivity)
            binding.rvCategories.adapter = CategoryAdapter(snapshot.categories)
        }
    }

    private data class InsightsSnapshot(
        val dailyExpenses: List<DayTotal>,
        val expenseTotals: List<CurrencyTotal>,
        val incomeTotals: List<CurrencyTotal>,
        val categories: List<CategoryCurrencyTotal>
    )

    private fun formatTotals(totals: List<CurrencyTotal>): String {
        if (totals.isEmpty()) return getString(R.string.insights_none_yet)
        return totals.joinToString(" · ") { CurrencyFormatter.format(it.total, it.currency) }
    }
}

// ── ISO-4217 currency formatter ───────────────────────────────────────────────

object CurrencyFormatter {
    fun symbol(currency: String): String = when (currency) {
        "USD", "AUD", "CAD", "SGD" -> "$"
        "GBP" -> "£"
        "EUR" -> "€"
        "INR" -> "₹"
        "JPY" -> "¥"
        "AED" -> "AED "
        else -> "$currency "
    }

    fun format(amount: Double, currency: String): String {
        val symbol = symbol(currency)
        return if (amount == amount.toLong().toDouble())
            "$symbol${amount.toLong()}"
        else
            "$symbol${"%.2f".format(amount)}"
    }
}

// ── Canvas bar chart view ─────────────────────────────────────────────────────

class SpendingBarChart @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var data: List<DayTotal> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6750A4.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        strokeWidth = 1f
    }

    fun setData(days: List<DayTotal>) {
        data = days
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) {
            textPaint.textSize = 36f
            canvas.drawText("No data yet", width / 2f, height / 2f, textPaint)
            textPaint.textSize = 24f
            return
        }

        val maxVal = data.maxOf { it.total }.takeIf { it > 0 } ?: return
        val padL = 16f; val padR = 16f; val padT = 20f; val padB = 40f
        val chartW = width - padL - padR
        val chartH = height - padT - padB
        val barW = (chartW / data.size) - 4f
        val displayData = if (data.size > 30) data.takeLast(30) else data

        displayData.forEachIndexed { i, day ->
            val barH = (day.total / maxVal * chartH).toFloat().coerceAtLeast(2f)
            val left = padL + i * (chartW / displayData.size) + 2f
            val top = padT + chartH - barH
            val right = left + barW
            val bottom = padT + chartH
            barPaint.alpha = 200
            canvas.drawRoundRect(RectF(left, top, right, bottom), 4f, 4f, barPaint)
            if (i % 5 == 0 && day.day.length >= 10) {
                val label = day.day.substring(8)
                canvas.drawText(label, left + barW / 2, bottom + 26f, textPaint)
            }
        }
        canvas.drawLine(padL, padT + chartH, width - padR, padT + chartH, linePaint)
    }
}

// ── Category breakdown adapter ────────────────────────────────────────────────

class CategoryAdapter(private val items: List<CategoryCurrencyTotal>) :
    RecyclerView.Adapter<CategoryAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.tv_category_label)
        val amount: TextView = v.findViewById(R.id.tv_category_amount)
        val bar: View = v.findViewById(R.id.view_category_bar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        // Bar length is relative within the same currency — comparing amounts
        // across currencies visually is misleading.
        val max = items.filter { it.currency == item.currency }
            .maxOf { it.total }
            .takeIf { it > 0 } ?: 1.0
        holder.label.text = item.category
        holder.amount.text = CurrencyFormatter.format(item.total, item.currency)
        holder.bar.scaleX = (item.total / max).toFloat()
        holder.bar.pivotX = 0f
    }
}
