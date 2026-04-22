package com.cashewbridge.app.parser

import com.cashewbridge.app.model.NotificationRule
import com.cashewbridge.app.model.ParsedTransaction
import java.util.Calendar

/**
 * Parses notification text using user-defined rules first,
 * then falls back to built-in heuristics for common bank formats.
 *
 * v4 additions:
 *  #1  Multi-currency detection — each pattern yields a (amount, currency) pair
 *  #2  Amount-range rule conditions — minAmountFilter / maxAmountFilter per rule
 *  #3  Memo / reference number extraction — noteRegex per rule
 *  #4  Confidence scoring — heuristic paths score each match 0-100
 *  #8  Sender-based rule condition — senderContains matched against sub-text
 */
object NotificationParser {

    // ── Amount + currency patterns (highest confidence first) ──────────────────
    // Each entry: (regex, currency code, confidence bonus)
    private data class CurrencyPattern(val regex: Regex, val currency: String, val bonus: Int)

    private val CURRENCY_PATTERNS = listOf(
        CurrencyPattern(Regex("""(?:USD|usd)\s*([\d,]+\.?\d*)"""), "USD", 3),
        CurrencyPattern(Regex("""(?:INR|Rs\.?|₹)\s*([\d,]+\.?\d*)"""), "INR", 3),
        CurrencyPattern(Regex("""(?:GBP|£)\s*([\d,]+\.?\d*)"""), "GBP", 3),
        CurrencyPattern(Regex("""(?:EUR|€)\s*([\d,]+\.?\d*)"""), "EUR", 3),
        CurrencyPattern(Regex("""\$\s*([\d,]+\.?\d*)"""), "USD", 3),
        CurrencyPattern(Regex("""(?:AED)\s*([\d,]+\.?\d*)"""), "AED", 3),
        CurrencyPattern(Regex("""(?:SGD)\s*([\d,]+\.?\d*)"""), "SGD", 3),
        CurrencyPattern(Regex("""(?:AUD)\s*([\d,]+\.?\d*)"""), "AUD", 3),
        CurrencyPattern(Regex("""(?:CAD)\s*([\d,]+\.?\d*)"""), "CAD", 3),
        CurrencyPattern(Regex("""¥\s*([\d,]+\.?\d*)"""), "JPY", 3),
        CurrencyPattern(Regex("""(?:JPY)\s*([\d,]+\.?\d*)"""), "JPY", 3),
        CurrencyPattern(Regex("""([\d,]+\.?\d*)\s*(?:USD|usd)"""), "USD", 2),
        CurrencyPattern(Regex("""(?i)amount[:\s]+(?:USD|Rs\.?|₹|€|£|\$)?\s*([\d,]+\.?\d*)"""), "USD", 2),
        CurrencyPattern(Regex("""(?i)debited.*?([\d,]+\.\d{2})"""), "USD", 2),
        CurrencyPattern(Regex("""(?i)credited.*?([\d,]+\.\d{2})"""), "USD", 2),
        CurrencyPattern(Regex("""(?i)charged.*?([\d,]+\.\d{2})"""), "USD", 2),
        CurrencyPattern(Regex("""(?i)payment.*?([\d,]+\.\d{2})"""), "USD", 1),
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

    // Common memo/reference patterns (#3)
    private val MEMO_PATTERNS = listOf(
        Regex("""(?i)ref(?:erence)?[:\s#]*([A-Z0-9]{6,20})"""),
        Regex("""(?i)txn[:\s#]*([A-Z0-9]{6,20})"""),
        Regex("""(?i)transaction\s*id[:\s#]*([A-Z0-9]{6,20})"""),
        Regex("""(?i)utr[:\s#]*([A-Z0-9]{6,20})"""),
        Regex("""(?i)order[:\s#]*([A-Z0-9-]{6,20})"""),
    )

    // ─────────────────────────────────────────────────────────────────────────

    fun parse(
        packageName: String,
        title: String,
        body: String,
        senderText: String = "",          // #8 — EXTRA_SUB_TEXT / EXTRA_INFO_TEXT
        enabledRules: List<NotificationRule>
    ): Pair<ParsedTransaction?, NotificationRule?> {
        val fullText = "$title\n$body"
        val now = Calendar.getInstance()

        for (rule in enabledRules.sortedByDescending { it.priority }) {
            if (!rule.isEnabled) continue
            if (!isRuleActiveNow(rule, now)) continue

            // Package always required
            if (rule.packageName.isNotBlank() && rule.packageName != packageName) continue

            val titleMatch = rule.titleContains.isBlank() ||
                    title.contains(rule.titleContains, ignoreCase = true)
            val bodyMatch = rule.bodyContains.isBlank() ||
                    body.contains(rule.bodyContains, ignoreCase = true)
            // #8 sender condition
            val senderMatch = rule.senderContains.isBlank() ||
                    senderText.contains(rule.senderContains, ignoreCase = true)

            val conditionsPass = if (rule.conditionLogic == 1) {
                // OR — at least one condition matches
                val noConditions = rule.titleContains.isBlank() &&
                        rule.bodyContains.isBlank() && rule.senderContains.isBlank()
                noConditions || titleMatch || bodyMatch || senderMatch
            } else {
                // AND (default)
                titleMatch && bodyMatch && senderMatch
            }
            if (!conditionsPass) continue

            val amount = extractWithRegex(rule.amountRegex, fullText) ?: continue

            // #2 amount-range filter
            if (rule.minAmountFilter > 0 && amount < rule.minAmountFilter) continue
            if (rule.maxAmountFilter > 0 && amount > rule.maxAmountFilter) continue

            val rawMerchant = extractWithRegex(rule.merchantRegex, fullText)?.toString()
            val merchant = rawMerchant?.let { normalizeMerchant(it) }
            val isIncome = if (rule.autoDetectType) detectIncome(fullText) else rule.isIncome

            // #3 — memo/note extraction (rule's noteRegex wins; else heuristic fallback)
            val note = extractNote(rule.noteRegex, fullText)
                ?: if (rule.noteRegex.isBlank()) extractNoteHeuristic(fullText) else null

            // #1 — currency override or auto-detect
            val currency = rule.currencyOverride.takeIf { it.isNotBlank() }
                ?: detectCurrency(fullText)

            return ParsedTransaction(
                amount = amount,
                merchant = merchant ?: rule.defaultCategory.takeIf { it.isNotBlank() },
                category = rule.defaultCategory.takeIf { it.isNotBlank() },
                note = note,
                isIncome = isIncome,
                currency = currency,
                confidence = 100,
                sourcePackage = packageName,
                sourceTitle = title,
                rawText = fullText
            ) to rule
        }

        // ── #4 Confidence-scored heuristic parsing ────────────────────────────
        val bestMatch = scoredHeuristicParse(fullText) ?: return null to null
        val merchant = extractMerchantHeuristic(fullText)?.let { normalizeMerchant(it) }
        val isIncome = detectIncome(fullText)
        val note = extractNoteHeuristic(fullText)

        return ParsedTransaction(
            amount = bestMatch.amount,
            merchant = merchant,
            category = null,
            note = note,
            isIncome = isIncome,
            currency = bestMatch.currency,
            confidence = bestMatch.score,
            sourcePackage = packageName,
            sourceTitle = title,
            rawText = fullText
        ) to null
    }

    // ── #4 Confidence scoring ─────────────────────────────────────────────────

    private data class ScoredAmount(val amount: Double, val currency: String, val score: Int)

    private fun scoredHeuristicParse(text: String): ScoredAmount? {
        val lower = text.lowercase()
        val incomeScore = INCOME_KEYWORDS.count { lower.contains(it) }
        val expenseScore = EXPENSE_KEYWORDS.count { lower.contains(it) }
        val hasKeyword = incomeScore + expenseScore > 0

        var best: ScoredAmount? = null
        for (cp in CURRENCY_PATTERNS) {
            val match = cp.regex.find(text) ?: continue
            val raw = (match.groupValues.getOrNull(1) ?: match.value).replace(",", "").trim()
            val amount = raw.toDoubleOrNull() ?: continue
            if (amount <= 0) continue

            var score = cp.bonus * 10
            if (hasKeyword) score += 20
            if (amount == Math.floor(amount) && amount < 1_000_000) score += 5
            if ("." in raw && raw.substringAfter(".").length == 2) score += 5  // cents precision
            if (best == null || score > best.score) {
                best = ScoredAmount(amount, cp.currency, score.coerceAtMost(100))
            }
        }
        return best
    }

    // ── #1 Currency-only detection (for rule matches) ─────────────────────────

    fun detectCurrency(text: String): String {
        for (cp in CURRENCY_PATTERNS) {
            if (cp.regex.containsMatchIn(text)) return cp.currency
        }
        return "USD"
    }

    // ── Time-range + day-of-week gate ─────────────────────────────────────────

    private fun isRuleActiveNow(rule: NotificationRule, now: Calendar): Boolean {
        if (rule.activeStartHour >= 0 && rule.activeEndHour >= 0) {
            val h = now.get(Calendar.HOUR_OF_DAY)
            val active = if (rule.activeStartHour <= rule.activeEndHour) {
                h in rule.activeStartHour until rule.activeEndHour
            } else {
                h >= rule.activeStartHour || h < rule.activeEndHour
            }
            if (!active) return false
        }
        if (rule.activeDaysOfWeek != 0) {
            val bit = now.get(Calendar.DAY_OF_WEEK) - 1
            if ((rule.activeDaysOfWeek shr bit) and 1 == 0) return false
        }
        return true
    }

    // ── Regex helpers ─────────────────────────────────────────────────────────

    fun testAmountRegex(pattern: String, text: String): Double? = extractWithRegex(pattern, text)

    fun testMerchantRegex(pattern: String, text: String): String? {
        if (pattern.isBlank()) return null
        return try {
            Regex(pattern).find(text)?.groupValues?.getOrNull(1)?.trim()
                ?.let { normalizeMerchant(it) }
        } catch (e: Exception) { null }
    }

    private fun extractWithRegex(pattern: String, text: String): Double? {
        if (pattern.isBlank()) return null
        return try {
            Regex(pattern).find(text)?.groupValues?.getOrNull(1)
                ?.replace(",", "")?.toDoubleOrNull()
        } catch (e: Exception) { null }
    }

    // #3 note extraction
    private fun extractNote(pattern: String, text: String): String? {
        if (pattern.isBlank()) return null
        return try {
            Regex(pattern).find(text)?.groupValues?.getOrNull(1)?.trim()
        } catch (e: Exception) { null }
    }

    private fun extractNoteHeuristic(text: String): String? {
        for (p in MEMO_PATTERNS) {
            val v = p.find(text)?.groupValues?.getOrNull(1)?.trim()
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun extractMerchantHeuristic(text: String): String? {
        for (pattern in MERCHANT_PATTERNS) {
            val merchant = pattern.find(text)?.groupValues?.getOrNull(1)?.trim()
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
