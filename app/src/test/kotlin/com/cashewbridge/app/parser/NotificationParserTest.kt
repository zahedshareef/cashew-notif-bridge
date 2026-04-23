package com.cashewbridge.app.parser

import com.cashewbridge.app.model.NotificationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for NotificationParser. These exercise the hot path
 * (amount + currency + merchant detection, rule priority, range filters,
 * active-time gates) so regressions surface without needing a device.
 */
class NotificationParserTest {

    private fun rule(
        id: Long = 1,
        name: String = "test",
        pkg: String = "com.bank.app",
        amountRegex: String = "",
        merchantRegex: String = "",
        priority: Int = 0,
        isIncome: Boolean = false,
        autoDetect: Boolean = false,
        minAmount: Double = 0.0,
        maxAmount: Double = 0.0,
        currencyOverride: String = "",
        titleContains: String = "",
        bodyContains: String = "",
        senderContains: String = "",
        conditionLogic: Int = 0,
        noteRegex: String = "",
        enabled: Boolean = true
    ) = NotificationRule(
        id = id,
        name = name,
        packageName = pkg,
        titleContains = titleContains,
        bodyContains = bodyContains,
        amountRegex = amountRegex,
        merchantRegex = merchantRegex,
        isIncome = isIncome,
        isEnabled = enabled,
        priority = priority,
        autoDetectType = autoDetect,
        conditionLogic = conditionLogic,
        senderContains = senderContains,
        minAmountFilter = minAmount,
        maxAmountFilter = maxAmount,
        noteRegex = noteRegex,
        currencyOverride = currencyOverride
    )

    // ── Heuristic amount + currency detection ────────────────────────────────

    @Test fun heuristic_detects_usd_dollar_sign() {
        val (tx, matched) = NotificationParser.parse(
            packageName = "com.bank",
            title = "Card charged",
            body = "You spent \$25.50 at Starbucks",
            enabledRules = emptyList()
        )
        assertNull("heuristic path should not return a rule", matched)
        assertNotNull(tx)
        assertEquals(25.50, tx!!.amount, 0.001)
        assertEquals("USD", tx.currency)
    }

    @Test fun heuristic_detects_inr_rupee_symbol() {
        val (tx, _) = NotificationParser.parse(
            packageName = "com.hdfc",
            title = "HDFC Bank",
            body = "Rs. 1,250.00 debited from A/c XX1234 on 01-01",
            enabledRules = emptyList()
        )
        assertNotNull(tx)
        assertEquals(1250.00, tx!!.amount, 0.001)
        assertEquals("INR", tx.currency)
        assertFalse("debited is an expense keyword", tx.isIncome)
    }

    @Test fun heuristic_detects_gbp_and_eur() {
        val gbp = NotificationParser.parse("x", "", "Payment of £12.34 to Tesco", enabledRules = emptyList()).first
        assertEquals("GBP", gbp?.currency)
        assertEquals(12.34, gbp!!.amount, 0.001)

        val eur = NotificationParser.parse("x", "", "€9.99 charged", enabledRules = emptyList()).first
        assertEquals("EUR", eur?.currency)
    }

    @Test fun heuristic_returns_null_for_non_transactional_text() {
        val (tx, _) = NotificationParser.parse(
            packageName = "com.news", title = "Breaking news",
            body = "Weather: 72 degrees today", enabledRules = emptyList()
        )
        assertNull(tx)
    }

    @Test fun heuristic_income_vs_expense_keywords() {
        val income = NotificationParser.parse(
            "x", "", "\$500 credited to your account as salary", enabledRules = emptyList()
        ).first
        assertTrue(income!!.isIncome)

        val expense = NotificationParser.parse(
            "x", "", "\$40 debited for purchase at Amazon", enabledRules = emptyList()
        ).first
        assertFalse(expense!!.isIncome)
    }

    // ── Rule matching ────────────────────────────────────────────────────────

    @Test fun rule_match_overrides_heuristic() {
        val r = rule(
            pkg = "com.chase",
            amountRegex = """\$(\d+\.?\d*)""",
            merchantRegex = """at ([A-Za-z ]+)""",
            priority = 10
        )
        val (tx, matched) = NotificationParser.parse(
            packageName = "com.chase",
            title = "Chase", body = "Transaction \$42.00 at Whole Foods",
            enabledRules = listOf(r)
        )
        assertNotNull(matched)
        assertEquals(1L, matched!!.id)
        assertEquals(42.0, tx!!.amount, 0.001)
        assertEquals(100, tx.confidence)
        // Rule-based alphabetic merchant capture previously returned null
        // because the extractor coerced the captured string to Double. Ensure
        // the fix produces the normalized merchant name.
        assertEquals("Whole Foods", tx.merchant)
    }

    @Test fun rule_merchant_extraction_handles_lowercase_single_word() {
        val r = rule(pkg = "com.sbux",
            amountRegex = """\$(\d+\.?\d*)""",
            merchantRegex = """at (\w+)""")
        val (tx, _) = NotificationParser.parse(
            "com.sbux", "", "Paid \$5 at starbucks", enabledRules = listOf(r)
        )
        assertEquals("Starbucks", tx!!.merchant)
    }

    @Test fun rule_blank_merchant_capture_falls_back_to_category() {
        val r = rule(pkg = "com.x",
            amountRegex = """(\d+)""",
            merchantRegex = """(\s*)""",
            defaultCategory = "Shopping")
        val (tx, _) = NotificationParser.parse(
            "com.x", "", "Paid 10", enabledRules = listOf(r)
        )
        // Blank merchant is suppressed; defaultCategory fallback kicks in.
        assertEquals("Shopping", tx!!.merchant)
    }

    @Test fun higher_priority_rule_wins() {
        val low = rule(id = 1, name = "low", pkg = "com.x",
            amountRegex = """(\d+)""", priority = 1, currencyOverride = "USD")
        val high = rule(id = 2, name = "high", pkg = "com.x",
            amountRegex = """(\d+)""", priority = 10, currencyOverride = "INR")
        val (tx, matched) = NotificationParser.parse(
            "com.x", "", "Amount 100", enabledRules = listOf(low, high)
        )
        assertEquals(2L, matched!!.id)
        assertEquals("INR", tx!!.currency)
    }

    @Test fun package_mismatch_falls_through_to_next_rule() {
        val wrongPkg = rule(id = 1, pkg = "com.other", amountRegex = """(\d+)""", priority = 10)
        val rightPkg = rule(id = 2, pkg = "com.x", amountRegex = """(\d+)""", priority = 1)
        val (_, matched) = NotificationParser.parse(
            "com.x", "", "55", enabledRules = listOf(wrongPkg, rightPkg)
        )
        assertEquals(2L, matched!!.id)
    }

    @Test fun disabled_rule_is_skipped() {
        val disabled = rule(id = 1, pkg = "com.x",
            amountRegex = """(\d+)""", priority = 10, enabled = false)
        val (_, matched) = NotificationParser.parse(
            "com.x", "", "55", enabledRules = listOf(disabled)
        )
        // disabled rule falls through, heuristic also won't match bare "55"
        assertNull(matched)
    }

    @Test fun amount_range_filter_excludes_below_min() {
        val r = rule(pkg = "com.x", amountRegex = """(\d+)""", minAmount = 100.0)
        val (_, matched) = NotificationParser.parse(
            "com.x", "", "Spent 50", enabledRules = listOf(r)
        )
        assertNull("amount 50 < min 100 should skip rule", matched)
    }

    @Test fun amount_range_filter_excludes_above_max() {
        val r = rule(pkg = "com.x", amountRegex = """(\d+)""", maxAmount = 100.0)
        val (_, matched) = NotificationParser.parse(
            "com.x", "", "Spent 500", enabledRules = listOf(r)
        )
        assertNull(matched)
    }

    @Test fun amount_range_filter_includes_in_range() {
        val r = rule(pkg = "com.x", amountRegex = """(\d+)""",
            minAmount = 10.0, maxAmount = 100.0)
        val (tx, matched) = NotificationParser.parse(
            "com.x", "", "Spent 50", enabledRules = listOf(r)
        )
        assertNotNull(matched)
        assertEquals(50.0, tx!!.amount, 0.001)
    }

    @Test fun currency_override_wins_over_detection() {
        val r = rule(pkg = "com.x",
            amountRegex = """\$(\d+)""", currencyOverride = "AUD")
        val (tx, _) = NotificationParser.parse(
            "com.x", "", "\$100 charged", enabledRules = listOf(r)
        )
        assertEquals("AUD", tx!!.currency)
    }

    @Test fun sender_contains_filters_p2p_apps() {
        val r = rule(pkg = "com.venmo",
            amountRegex = """\$(\d+)""", senderContains = "Alice")
        val (_, matchedA) = NotificationParser.parse(
            "com.venmo", "", "\$20 sent", senderText = "From Alice",
            enabledRules = listOf(r)
        )
        assertNotNull(matchedA)

        val (_, matchedB) = NotificationParser.parse(
            "com.venmo", "", "\$20 sent", senderText = "From Bob",
            enabledRules = listOf(r)
        )
        assertNull(matchedB)
    }

    @Test fun condition_logic_or_matches_any() {
        val r = rule(pkg = "com.x",
            amountRegex = """(\d+)""",
            titleContains = "NEVER",
            bodyContains = "debited",
            conditionLogic = 1)
        val (_, matched) = NotificationParser.parse(
            "com.x", title = "Something else",
            body = "100 debited from account",
            enabledRules = listOf(r)
        )
        assertNotNull("OR logic should match since body matches", matched)
    }

    // ── Note extraction ──────────────────────────────────────────────────────

    @Test fun note_heuristic_extracts_utr() {
        val (tx, _) = NotificationParser.parse(
            "com.bank", "", "Rs. 100 debited. UTR: ABC12345XYZ",
            enabledRules = emptyList()
        )
        assertEquals("ABC12345XYZ", tx?.note)
    }

    @Test fun note_rule_regex_overrides_heuristic() {
        val r = rule(pkg = "com.x",
            amountRegex = """(\d+)""",
            noteRegex = """order #(\w+)""")
        val (tx, _) = NotificationParser.parse(
            "com.x", "", "Paid 100 for order #Z999 UTR: ABC123",
            enabledRules = listOf(r)
        )
        assertEquals("Z999", tx?.note)
    }

    // ── Negative amounts (#13) ───────────────────────────────────────────────

    @Test fun heuristic_negative_amount_flips_to_income() {
        // "-$100 reversed" has no income keyword but has a minus sign — should
        // be treated as money coming back in (refund).
        val (tx, _) = NotificationParser.parse(
            "com.bank", "",
            "\$-100.00 reversed on your card",
            enabledRules = emptyList()
        )
        assertNotNull(tx)
        assertEquals(100.00, tx!!.amount, 0.001)
        assertTrue("negative amount should be treated as income", tx.isIncome)
    }

    @Test fun heuristic_absolute_value_always_returned() {
        // Cashew expects a positive amount; the sign is conveyed via isIncome.
        val (tx, _) = NotificationParser.parse(
            "com.bank", "", "-\$42.50 charged", enabledRules = emptyList()
        )
        assertEquals(42.50, tx!!.amount, 0.001)
    }

    @Test fun rule_negative_amount_flips_to_income_when_autoDetect_off() {
        val r = rule(pkg = "com.x",
            amountRegex = """\$(-?\d+\.?\d*)""",
            isIncome = false)
        val (tx, _) = NotificationParser.parse(
            "com.x", "", "\$-25.00 reversed", enabledRules = listOf(r)
        )
        assertNotNull(tx)
        assertEquals(25.00, tx!!.amount, 0.001)
        assertTrue("rule amount with minus sign flips isIncome", tx.isIncome)
    }

    @Test fun rule_positive_amount_respects_isIncome_flag() {
        // Regression: positive amount must still honour the rule's isIncome.
        val r = rule(pkg = "com.x",
            amountRegex = """\$(-?\d+\.?\d*)""",
            isIncome = false)
        val (tx, _) = NotificationParser.parse(
            "com.x", "", "\$25.00 charged", enabledRules = listOf(r)
        )
        assertFalse(tx!!.isIncome)
    }

    // ── Consolidated currency regex (#12) ────────────────────────────────────

    @Test fun case_insensitive_usd_prefix_matches() {
        // Previously required two patterns (USD|usd); consolidation with (?i)
        // should preserve behaviour.
        val (lower, _) = NotificationParser.parse(
            "x", "", "usd 42.00 debited", enabledRules = emptyList()
        )
        assertEquals("USD", lower!!.currency)
        assertEquals(42.00, lower.amount, 0.001)
    }

    @Test fun case_insensitive_jpy_prefix_matches() {
        val (tx, _) = NotificationParser.parse(
            "x", "", "jpy 1000 received", enabledRules = emptyList()
        )
        assertEquals("JPY", tx!!.currency)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Test fun normalizeMerchant_strips_trailing_punctuation_and_titlecases() {
        assertEquals("Starbucks", NotificationParser.normalizeMerchant("starbucks."))
        assertEquals("Whole Foods Market",
            NotificationParser.normalizeMerchant("WHOLE FOODS MARKET***"))
    }

    @Test fun detectCurrency_defaults_to_usd() {
        assertEquals("USD", NotificationParser.detectCurrency("no currency here"))
    }

    @Test fun detectIncome_basic_balance() {
        assertTrue(NotificationParser.detectIncome("amount credited to account"))
        assertFalse(NotificationParser.detectIncome("amount debited from account"))
    }

    @Test fun testAmountRegex_returns_null_on_invalid_pattern() {
        // An unbalanced group should be caught and returned as null rather than
        // crashing the parser.
        assertNull(NotificationParser.testAmountRegex("(unclosed", "anything"))
    }

    @Test fun testMerchantRegex_returns_null_on_invalid_pattern() {
        assertNull(NotificationParser.testMerchantRegex("[bad", "anything"))
    }

    @Test fun testMerchantRegex_blank_returns_null() {
        assertNull(NotificationParser.testMerchantRegex("", "some text"))
    }
}
