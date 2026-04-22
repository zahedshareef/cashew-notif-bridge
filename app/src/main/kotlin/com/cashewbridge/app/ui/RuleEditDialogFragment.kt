package com.cashewbridge.app.ui

import android.app.Dialog
import android.os.Bundle
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
            // Editing an existing rule — fill all fields
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
        } else {
            // New rule — check for pre-filled values from a notification
            val prefillPackage = arguments?.getString(ARG_PREFILL_PACKAGE)
            val prefillAppLabel = arguments?.getString(ARG_PREFILL_APP_LABEL)

            if (!prefillPackage.isNullOrBlank()) {
                binding.etPackageName.setText(prefillPackage)
                // Make the field visually indicate it was auto-detected
                binding.etPackageName.isEnabled = false
                binding.layoutPackageName.helperText =
                    getString(R.string.package_auto_detected)
            }

            if (!prefillAppLabel.isNullOrBlank()) {
                // Pre-suggest a rule name based on the app label
                binding.etRuleName.setText(prefillAppLabel)
                binding.etRuleName.selectAll()
            }
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

        /** Open to edit an existing rule. */
        fun newInstance(rule: NotificationRule?): RuleEditDialogFragment {
            val fragment = RuleEditDialogFragment()
            if (rule != null) {
                fragment.arguments = Bundle().apply { putParcelable(ARG_RULE, rule) }
            }
            return fragment
        }

        /**
         * Open to create a new rule pre-filled from a notification.
         * The package name is auto-populated from [packageName] and shown as read-only.
         * The rule name is pre-suggested from [appLabel].
         */
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
