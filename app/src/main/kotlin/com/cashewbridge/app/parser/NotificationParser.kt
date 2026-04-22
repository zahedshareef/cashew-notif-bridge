package com.cashewbridge.app.parser

import com.cashewbridge.app.model.NotificationRule
import com.cashewbridge.app.model.ParsedTransaction

/**
 * Parses notification text using user-defined rules first,
 * then falls back to built-in heuristics for common bank formats.
 */
object NotificationParser {

    // ---- Built-in patterns used as fallback ----

    // Matches: $1,234.56 / USD 100.00 / Rs.500 / ₹1,000 / £9.99 / €12.00
    private val AMOUNT_PATTERNS = listOf(
        Regex("""(?:USD|usd)\s*([\d,]+\.?\d*)"""),
        Regex("""(?:INR|Rs\.?|₹)\s*([\d,]+\.?\d*)"""),
        Regex("""(?:GBP|£)\s*([\d,]+\.?\d*)"""),
        Regex("""(?:EUR|€)\s*([\d,]+\.?\d*)"""),
        Regex("""\$\s*([\d,]+\.?\d*)"""),
        Regex("""([\d,]+\.?\d*)\s*(?:USD|usd)"""),
        Regex("""(?i)amount[:\s]+(?:USD|Rs\.?|₹|€|£|\$)?\s*([\d,]+\.?\d*)"""),
        Regex("""(?i)debited.*?([\d,]+\.\d{2})"""),
        Regex("""(?i)credited.*?([\d,]+\.\d{2})"""),
        Regex("""(?i)charged.*?([\d,]+\.\d{2})"""),
        Regex("""(?i)payment.*?([\d,]+\.\d{2})"""),
    )

    // Keywords suggesting income vs expense
    private val INCOME_KEYWORDS = listOf(
        "received", "credited", "refund", "cashback", "credit", "salary", "deposited",
        "payment received", "money received", "added", "topup", "top-up"
    )
    private val EXPENSE_KEYWORDS = listOf(
        "debited", "charged", "payment", "purchase", "spent", "paid", "deducted",
        "withdrawn", "withdrawal", "debit", "transaction"
    )

    // Common merchant patterns
    private val MERCHANT_PATTERNS = listOf(
        Regex("""(?i)(?:at|to|from|merchant[:\s]*)\s*([A-Za-z][A-Za-z0-9\s&'.,-]{2,30})"""),
        Regex("""(?i)(?:purchase at|used at|payment to)\s+([A-Za-z][A-Za-z0-9\s&'.,-]{2,30})"""),
    )

    /**
     * Attempts to parse a transaction from a notification using matching rules.
     * Returns null if no amount can be extracted.
     */
    fun parse(
        packageName: String,
        title: String,
        body: String,
        enabledRules: List<NotificationRule>
    ): Pair<ParsedTransaction?, NotificationRule?> {
        val fullText = "$title\n$body"

        // 1. Try user-defined rules in priority order
        for (rule in enabledRules) {
            if (!rule.isEnabled) continue
            if (rule.packageName.isNotBlank() && rule.packageName != packageName) continue
            if (rule.titleContains.isNotBlank() && !title.contains(rule.titleContains, ignoreCase = true)) continue
            if (rule.bodyContains.isNotBlank() && !body.contains(rule.bodyContains, ignoreCase = true)) continue

            val amount = extractWithRegex(rule.amountRegex, fullText) ?: continue
            val rawMerchant = extractWithRegex(rule.merchantRegex, fullText)
            val merchant = rawMerchant?.let { normalizeMerchant(it) }

            // Income/expense: use auto-detect when the rule opts in
            val isIncome = if (rule.autoDetectType) detectIncome(fullText) else rule.isIncome

            return ParsedTransaction(
                amount = amount,
                merchant = merchant ?: rule.defaultCategory.takeIf { it.isNotBlank() },
                category = rule.defaultCategory.takeIf { it.isNotBlank() },
                note = null,
                isIncome = isIncome,
                sourcePackage = packageName,
                sourceTitle = title,
                rawText = fullText
            ) to rule
        }

        // 2. Fallback: heuristic parsing
        val amount = extractAmountHeuristic(fullText) ?: return null to null
        val merchant = extractMerchantHeuristic(fullText)?.let { normalizeMerchant(it) }
        val isIncome = detectIncome(fullText)

        return ParsedTransaction(
            amount = amount,
            merchant = merchant,
            category = null,
            note = null,
            isIncome = isIncome,
            sourcePackage = packageName,
            sourceTitle = title,
            rawText = fullText
        ) to null
    }

    /**
     * Run a regex against sample text and return the extracted amount (for test-rule UI).
     */
    fun testAmountRegex(pattern: String, text: String): Double? = extractWithRegex(pattern, text)

    /**
     * Run a regex against sample text and return the extracted merchant (for test-rule UI).
     */
    fun testMerchantRegex(pattern: String, text: String): String? {
        if (pattern.isBlank()) return null
        return try {
            val match = Regex(pattern).find(text)
            match?.groupValues?.getOrNull(1)?.trim()?.let { normalizeMerchant(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractWithRegex(pattern: String, text: String): Double? {
        if (pattern.isBlank()) return null
        return try {
            val match = Regex(pattern).find(text)
            match?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractAmountHeuristic(text: String): Double? {
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val raw = (match.groupValues.getOrNull(1) ?: match.value)
                .replace(",", "")
                .trim()
            val amount = raw.toDoubleOrNull()
            if (amount != null && amount > 0) return amount
        }
        return null
    }

    private fun extractMerchantHeuristic(text: String): String? {
        for (pattern in MERCHANT_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val merchant = match.groupValues.getOrNull(1)?.trim()
            if (!merchant.isNullOrBlank() && merchant.length >= 3) return merchant
        }
        return null
    }

    /** Normalize a raw merchant string: trim, strip trailing punctuation, convert to Title Case. */
    fun normalizeMerchant(raw: String): String {
        val cleaned = raw.trim().trimEnd('.', ',', '-', '/', '\\', ':', ';', '*', '#')
        return cleaned.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
    }

    /** Public so the rule test dialog and confirm receiver can call it directly. */
    fun detectIncome(text: String): Boolean {
        val lower = text.lowercase()
        val incomeScore = INCOME_KEYWORDS.count { lower.contains(it) }
        val expenseScore = EXPENSE_KEYWORDS.count { lower.contains(it) }
        return incomeScore > expenseScore
    }
}
