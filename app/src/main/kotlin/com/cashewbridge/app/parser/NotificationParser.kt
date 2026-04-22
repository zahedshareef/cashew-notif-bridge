package com.cashewbridge.app.parser

import com.cashewbridge.app.model.NotificationRule
import com.cashewbridge.app.model.ParsedTransaction
import java.util.Calendar

/**
 * Parses notification text using user-defined rules first,
 * then falls back to built-in heuristics for common bank formats.
 *
 * Supports:
 *  - Condition logic: AND (all must match) or OR (any must match) per rule
 *  - Time-range conditions per rule
 *  - Day-of-week conditions per rule (bitmask: bit0=Sun, bit1=Mon … bit6=Sat)
 */
object NotificationParser {

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

    private val INCOME_KEYWORDS = listOf(
        "received", "credited", "refund", "cashback", "credit", "salary", "deposited",
        "payment received", "money received", "added", "topup", "top-up"
    )
    private val EXPENSE_KEYWORDS = listOf(
        "debited", "charged", "payment", "purchase", "spent", "paid", "deducted",
        "withdrawn", "withdrawal", "debit", "transaction"
    )

    private val MERCHANT_PATTERNS = listOf(
        Regex("""(?i)(?:at|to|from|merchant[:\s]*)\s*([A-Za-z][A-Za-z0-9\s&'.,-]{2,30})"""),
        Regex("""(?i)(?:purchase at|used at|payment to)\s+([A-Za-z][A-Za-z0-9\s&'.,-]{2,30})"""),
    )

    fun parse(
        packageName: String,
        title: String,
        body: String,
        enabledRules: List<NotificationRule>
    ): Pair<ParsedTransaction?, NotificationRule?> {
        val fullText = "$title\n$body"
        val now = Calendar.getInstance()

        for (rule in enabledRules) {
            if (!rule.isEnabled) continue

            // ── Time-range check (#5) ──────────────────────────────────────────
            if (!isRuleActiveNow(rule, now)) continue

            // ── Gather which text conditions are configured ───────────────────
            val packageMatch = rule.packageName.isBlank() || rule.packageName == packageName
            val titleMatch = rule.titleContains.isBlank() ||
                    title.contains(rule.titleContains, ignoreCase = true)
            val bodyMatch = rule.bodyContains.isBlank() ||
                    body.contains(rule.bodyContains, ignoreCase = true)

            // ── Condition logic: AND vs OR (#10) ──────────────────────────────
            val conditionsPass = if (rule.conditionLogic == 1) {
                // OR — package is always required; at least one text condition must match
                packageMatch && (titleMatch || bodyMatch ||
                        (rule.titleContains.isBlank() && rule.bodyContains.isBlank()))
            } else {
                // AND (default)
                packageMatch && titleMatch && bodyMatch
            }
            if (!conditionsPass) continue

            val amount = extractWithRegex(rule.amountRegex, fullText) ?: continue
            val rawMerchant = extractWithRegex(rule.merchantRegex, fullText)
            val merchant = rawMerchant?.let { normalizeMerchant(it) }
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

        // Fallback: heuristic parsing
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
     * Returns true if the rule should be evaluated at the given calendar time.
     * Checks both time-of-day range and day-of-week bitmask.
     */
    private fun isRuleActiveNow(rule: NotificationRule, now: Calendar): Boolean {
        // Time-of-day range
        if (rule.activeStartHour >= 0 && rule.activeEndHour >= 0) {
            val currentHour = now.get(Calendar.HOUR_OF_DAY)
            val active = if (rule.activeStartHour <= rule.activeEndHour) {
                currentHour in rule.activeStartHour until rule.activeEndHour
            } else {
                // wraps midnight, e.g. 22 – 06
                currentHour >= rule.activeStartHour || currentHour < rule.activeEndHour
            }
            if (!active) return false
        }

        // Day-of-week (Calendar.SUNDAY=1, MONDAY=2 … SATURDAY=7 → bit0=Sun bit1=Mon…bit6=Sat)
        if (rule.activeDaysOfWeek != 0) {
            val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)   // 1=Sun … 7=Sat
            val bit = dayOfWeek - 1                          // 0=Sun … 6=Sat
            if ((rule.activeDaysOfWeek shr bit) and 1 == 0) return false
        }

        return true
    }

    fun testAmountRegex(pattern: String, text: String): Double? = extractWithRegex(pattern, text)

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
                .replace(",", "").trim()
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

    fun normalizeMerchant(raw: String): String {
        val cleaned = raw.trim().trimEnd('.', ',', '-', '/', '\\', ':', ';', '*', '#')
        return cleaned.split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercaseChar() } }
    }

    fun detectIncome(text: String): Boolean {
        val lower = text.lowercase()
        val incomeScore = INCOME_KEYWORDS.count { lower.contains(it) }
        val expenseScore = EXPENSE_KEYWORDS.count { lower.contains(it) }
        return incomeScore > expenseScore
    }
}
