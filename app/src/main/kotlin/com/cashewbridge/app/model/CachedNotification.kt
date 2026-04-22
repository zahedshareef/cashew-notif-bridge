package com.cashewbridge.app.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A notification captured by the listener service.
 * Persisted to the Room DB so the list survives process death.
 * Only the most recent MAX_PERSISTED entries are kept.
 */
@Entity(tableName = "notification_cache")
data class CachedNotification(
    @PrimaryKey
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val timestamp: Long,
    val parsedAmount: Double?,
    val parsedMerchant: String?,
    val parsedCategory: String?,
    val parsedWallet: String?,
    val isIncome: Boolean,
    val matchedRuleName: String
)
