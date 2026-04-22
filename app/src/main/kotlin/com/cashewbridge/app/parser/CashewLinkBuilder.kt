package com.cashewbridge.app.parser

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
 */
object CashewLinkBuilder {

    fun buildIntent(
        transaction: ParsedTransaction,
        walletName: String = "",
        updateData: Boolean = true
    ): Intent {
        val uriBuilder = Uri.Builder()
            .scheme("cashewapp")
            .authority("addTransaction")
            .appendQueryParameter("amount", transaction.amount.toString())
            .appendQueryParameter("income", transaction.isIncome.toString())
            .appendQueryParameter("updateData", updateData.toString())

        transaction.merchant?.takeIf { it.isNotBlank() }?.let {
            uriBuilder.appendQueryParameter("title", it)
        }

        transaction.note?.takeIf { it.isNotBlank() }?.let {
            uriBuilder.appendQueryParameter("note", it)
        }

        transaction.category?.takeIf { it.isNotBlank() }?.let {
            uriBuilder.appendQueryParameter("categoryName", it)
        }

        walletName.takeIf { it.isNotBlank() }?.let {
            uriBuilder.appendQueryParameter("walletName", it)
        }

        val uri = uriBuilder.build()
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
}
