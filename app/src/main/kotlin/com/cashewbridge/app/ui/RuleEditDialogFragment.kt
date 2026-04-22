package com.cashewbridge.app.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.cashewbridge.app.R
import com.cashewbridge.app.databinding.DialogEditRuleBinding
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.NotificationRule
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RuleEditDialogFragment : DialogFragment() {

    private var _binding: DialogEditRuleBinding? = null
    private val binding get() = _binding!!
    private var existingRule: NotificationRule? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        existingRule = arguments?.getParcelable(ARG_RULE)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogEditRuleBinding.inflate(layoutInflater)
        populateFields()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existingRule == null) R.string.add_rule else R.string.edit_rule)
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ -> saveRule() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun populateFields() {
        val rule = existingRule ?: return
        binding.etRuleName.setText(rule.name)
        binding.etPackageName.setText(rule.packageName)
        binding.etTitleContains.setText(rule.titleContains)
        binding.etBodyContains.setText(rule.bodyContains)
        binding.etAmountRegex.setText(rule.amountRegex)
        binding.etMerchantRegex.setText(rule.merchantRegex)
        binding.etDefaultCategory.setText(rule.defaultCategory)
        binding.etWalletName.setText(rule.defaultWalletName)
        binding.switchIsIncome.isChecked = rule.isIncome
        binding.etPriority.setText(rule.priority.toString())
    }

    private fun saveRule() {
        val name = binding.etRuleName.text.toString().trim()
        if (name.isBlank()) {
            binding.etRuleName.error = getString(R.string.required)
            return
        }

        val rule = NotificationRule(
            id = existingRule?.id ?: 0,
            name = name,
            packageName = binding.etPackageName.text.toString().trim(),
            titleContains = binding.etTitleContains.text.toString().trim(),
            bodyContains = binding.etBodyContains.text.toString().trim(),
            amountRegex = binding.etAmountRegex.text.toString().trim(),
            merchantRegex = binding.etMerchantRegex.text.toString().trim(),
            defaultCategory = binding.etDefaultCategory.text.toString().trim(),
            defaultWalletName = binding.etWalletName.text.toString().trim(),
            isIncome = binding.switchIsIncome.isChecked,
            priority = binding.etPriority.text.toString().toIntOrNull() ?: 0,
            isEnabled = existingRule?.isEnabled ?: true
        )

        val db = AppDatabase.getInstance(requireContext())
        lifecycleScope.launch(Dispatchers.IO) {
            db.ruleDao().insertRule(rule)
        }
    }

    companion object {
        private const val ARG_RULE = "rule"

        fun newInstance(rule: NotificationRule?): RuleEditDialogFragment {
            val fragment = RuleEditDialogFragment()
            if (rule != null) {
                val args = Bundle()
                args.putParcelable(ARG_RULE, rule)
                fragment.arguments = args
            }
            return fragment
        }
    }
}
