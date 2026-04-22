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
import com.cashewbridge.app.model.DayTotal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #6 — Insights screen with a Canvas-drawn spending bar chart (past 30 days)
 * and a category breakdown list.
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
            val dailyExpenses = withContext(Dispatchers.IO) { db.logDao().getDailyExpenses(since30d) }
            val categories = withContext(Dispatchers.IO) { db.logDao().getCategoryBreakdown(since30d) }
            val totalExpense = dailyExpenses.sumOf { it.total }
            val totalIncome = withContext(Dispatchers.IO) {
                db.logDao().getDailyIncome(since30d).sumOf { it.total }
            }

            binding.tvTotalExpense.text = "Expenses: $${"%.2f".format(totalExpense)} (30d)"
            binding.tvTotalIncome.text  = "Income: $${"%.2f".format(totalIncome)} (30d)"

            binding.barChart.setData(dailyExpenses)

            val adapter = CategoryAdapter(categories)
            binding.rvCategories.layoutManager = LinearLayoutManager(this@InsightsActivity)
            binding.rvCategories.adapter = adapter
        }
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
            // Day label (show every 5th)
            if (i % 5 == 0 && day.day.length >= 10) {
                val label = day.day.substring(8) // DD portion
                canvas.drawText(label, left + barW / 2, bottom + 26f, textPaint)
            }
        }
        // Baseline
        canvas.drawLine(padL, padT + chartH, width - padR, padT + chartH, linePaint)
    }
}

// ── Category breakdown adapter ────────────────────────────────────────────────

class CategoryAdapter(private val items: List<DayTotal>) :
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
        val max = items.maxOf { it.total }.takeIf { it > 0 } ?: 1.0
        holder.label.text = item.day
        holder.amount.text = "$${"%.2f".format(item.total)}"
        val fraction = (item.total / max).toFloat()
        holder.bar.layoutParams = (holder.bar.layoutParams as ViewGroup.MarginLayoutParams).also {
            // Scale the bar using post-layout scaleX
        }
        holder.bar.scaleX = fraction
        holder.bar.pivotX = 0f
    }
}
