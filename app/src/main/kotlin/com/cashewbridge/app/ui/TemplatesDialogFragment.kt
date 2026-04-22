package com.cashewbridge.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cashewbridge.app.R
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.NotificationRule
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * #7 — Rule templates library.
 * Reads templates.json from assets and lets the user apply one with a single tap.
 */
class TemplatesDialogFragment : BottomSheetDialogFragment() {

    interface Listener {
        fun onTemplateApplied()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_templates, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val rv = view.findViewById<RecyclerView>(R.id.rv_templates)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))

        lifecycleScope.launch {
            val templates = loadTemplates()
            rv.adapter = TemplateAdapter(templates) { rule ->
                applyTemplate(rule)
            }
        }
    }

    private suspend fun loadTemplates(): List<TemplateItem> = withContext(Dispatchers.IO) {
        try {
            val json = requireContext().assets.open("templates.json")
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TemplateItem(
                    label = obj.getString("label"),
                    description = obj.getString("description"),
                    rule = NotificationRule(
                        name = obj.getString("label"),
                        packageName = obj.optString("packageName", ""),
                        titleContains = obj.optString("titleContains", ""),
                        bodyContains = obj.optString("bodyContains", ""),
                        amountRegex = obj.optString("amountRegex", ""),
                        merchantRegex = obj.optString("merchantRegex", ""),
                        defaultCategory = obj.optString("defaultCategory", ""),
                        isIncome = obj.optBoolean("isIncome", false),
                        autoDetectType = obj.optBoolean("autoDetectType", true),
                        noteRegex = obj.optString("noteRegex", ""),
                        senderContains = obj.optString("senderContains", ""),
                        currencyOverride = obj.optString("currencyOverride", "")
                    )
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun applyTemplate(rule: NotificationRule) {
        val db = AppDatabase.getInstance(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            db.ruleDao().insertRule(rule)
            withContext(Dispatchers.Main) {
                (parentFragment as? Listener ?: activity as? Listener)?.onTemplateApplied()
                dismiss()
            }
        }
    }

    data class TemplateItem(val label: String, val description: String, val rule: NotificationRule)

    class TemplateAdapter(
        private val items: List<TemplateItem>,
        private val onClick: (NotificationRule) -> Unit
    ) : RecyclerView.Adapter<TemplateAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val label: TextView = v.findViewById(R.id.tv_template_label)
            val desc: TextView = v.findViewById(R.id.tv_template_desc)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_template, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.label.text = item.label
            holder.desc.text = item.description
            holder.itemView.setOnClickListener { onClick(item.rule) }
        }
    }

    companion object {
        fun newInstance() = TemplatesDialogFragment()
    }
}
