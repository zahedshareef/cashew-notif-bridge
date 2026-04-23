package com.cashewbridge.app.parser

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cashewbridge.app.model.ParsedTransaction

/**
 * Builds the Cashew App Link URI from a parsed transaction and opens it.
 *
 * Cashew App Link format (from https://cashewapp.web.app/faq.html#app-links):
 *   cashewapp://addTransaction
 *     ?amount=<double>
 *     &title=<string>
 *     &note=<string>
 *     &categoryName=<string>
 *     &walletName=<string>
 *     &income=<true|false>
 *     &updateData=<true|false>
 *     &currency=<ISO-4217 code>   ← #1 multi-currency
 */
object CashewLinkBuilder {

    fun buildIntent(
        transaction: ParsedTransaction,
        walletName: String = "",
        updateData: Boolean = true
    ): Intent {
        val uri = buildUri(transaction, walletName, updateData)
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildUri(
        transaction: ParsedTransaction,
        walletName: String = "",
        updateData: Boolean = true
    ): Uri {
        return Uri.Builder()
            .scheme("cashewapp")
            .authority("addTransaction")
            .appendQueryParameter("amount", transaction.amount.toString())
            .appendQueryParameter("income", transaction.isIncome.toString())
            .appendQueryParameter("updateData", updateData.toString())
            .apply {
                // #1 — currency forwarding
                if (transaction.currency.isNotBlank() && transaction.currency != "USD") {
                    appendQueryParameter("currency", transaction.currency)
                }
                transaction.merchant?.takeIf { it.isNotBlank() }?.let {
                    appendQueryParameter("title", it)
                }
                transaction.note?.takeIf { it.isNotBlank() }?.let {
                    appendQueryParameter("note", it)
                }
                transaction.category?.takeIf { it.isNotBlank() }?.let {
                    appendQueryParameter("categoryName", it)
                }
                walletName.takeIf { it.isNotBlank() }?.let {
                    appendQueryParameter("walletName", it)
                }
            }
            .build()
    }

    /**
     * Returns true when an installed app declares a handler for the
     * `cashewapp://addTransaction` deep link. Used by the listener service to
     * detect silently-failing auto-forwards when Cashew is not installed.
     */
    fun isCashewInstalled(context: Context): Boolean {
        val probe = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("cashewapp://addTransaction")
        )
        return probe.resolveActivity(context.packageManager) != null
    }
}
