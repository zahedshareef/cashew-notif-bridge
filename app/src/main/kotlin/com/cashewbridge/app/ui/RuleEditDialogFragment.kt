package com.cashewbridge.app.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.cashewbridge.app.R
import com.cashewbridge.app.databinding.DialogEditRuleBinding
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.NotificationRule
import com.cashewbridge.app.parser.NotificationParser
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
        setupTestRule()

        val title = when {
            existingRule != null -> R.string.edit_rule
            arguments?.getString(ARG_PREFILL_PACKAGE) != null -> R.string.create_rule_for_app
            else -> R.string.add_rule
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
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
        val rule = existingRule
        if (rule != null) {
            binding.etRuleName.setText(rule.name)
            binding.etPackageName.setText(rule.packageName)
            binding.etTitleContains.setText(rule.titleContains)
            binding.etBodyContains.setText(rule.bodyContains)
            binding.etAmountRegex.setText(rule.amountRegex)
            binding.etMerchantRegex.setText(rule.merchantRegex)
            binding.etDefaultCategory.setText(rule.defaultCategory)
            binding.etWalletName.setText(rule.defaultWalletName)
            binding.switchIsIncome.isChecked = rule.isIncome
            binding.switchAutoDetect.isChecked = rule.autoDetectType
            binding.etPriority.setText(rule.priority.toString())
            // Show/hide income switch based on auto-detect
            binding.layoutIncomeSwitch.visibility =
                if (rule.autoDetectType) View.GONE else View.VISIBLE
        } else {
            val prefillPackage = arguments?.getString(ARG_PREFILL_PACKAGE)
            val prefillAppLabel = arguments?.getString(ARG_PREFILL_APP_LABEL)

            if (!prefillPackage.isNullOrBlank()) {
                binding.etPackageName.setText(prefillPackage)
                binding.etPackageName.isEnabled = false
                binding.layoutPackageName.helperText = getString(R.string.package_auto_detected)
            }

            if (!prefillAppLabel.isNullOrBlank()) {
                binding.etRuleName.setText(prefillAppLabel)
                binding.etRuleName.selectAll()
            }
        }

        // When auto-detect is toggled, show/hide the income switch
        binding.switchAutoDetect.setOnCheckedChangeListener { _, checked ->
            binding.layoutIncomeSwitch.visibility = if (checked) View.GONE else View.VISIBLE
        }
    }

    /** Wires up the "Test Rule" section: paste text → see extracted amount/merchant. */
    private fun setupTestRule() {
        binding.btnTestRule.setOnClickListener {
            val sampleText = binding.etSampleText.text.toString().trim()
            if (sampleText.isBlank()) {
                binding.tvTestResult.text = getString(R.string.test_rule_empty)
                return@setOnClickListener
            }

            val amountPattern = binding.etAmountRegex.text.toString().trim()
            val merchantPattern = binding.etMerchantRegex.text.toString().trim()

            val amount = NotificationParser.testAmountRegex(amountPattern, sampleText)
            val merchant = NotificationParser.testMerchantRegex(merchantPattern, sampleText)
            val isIncome = NotificationParser.detectIncome(sampleText)

            val result = buildString {
                append("Amount: ${if (amount != null) amount.toString() else getString(R.string.test_no_match)}\n")
                append("Merchant: ${merchant ?: getString(R.string.test_no_match)}\n")
                append("Type: ${if (isIncome) "Income" else "Expense"} (keyword detection)")
            }
            binding.tvTestResult.text = result
        }
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
            autoDetectType = binding.switchAutoDetect.isChecked,
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
        private const val ARG_PREFILL_PACKAGE = "prefill_package"
        private const val ARG_PREFILL_APP_LABEL = "prefill_app_label"

        fun newInstance(rule: NotificationRule?): RuleEditDialogFragment {
            val fragment = RuleEditDialogFragment()
            if (rule != null) {
                fragment.arguments = Bundle().apply { putParcelable(ARG_RULE, rule) }
            }
            return fragment
        }

        fun newInstanceForNotification(
            packageName: String,
            appLabel: String
        ): RuleEditDialogFragment {
            val fragment = RuleEditDialogFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_PREFILL_PACKAGE, packageName)
                putString(ARG_PREFILL_APP_LABEL, appLabel)
            }
            return fragment
        }
    }
}
