package com.cashewbridge.app.model

/**
 * A notification captured in real-time by the listener service and held in memory.
 * Not persisted — only the last [NotificationCache.MAX_SIZE] items are kept.
 */
data class CachedNotification(
    val key: String,                // packageName + id, unique dedup key
    val packageName: String,
    val appLabel: String,
    val title: String,
    val body: String,
    val timestamp: Long,
    // Pre-parsed transaction fields (null = could not extract)
    val parsedAmount: Double?,
    val parsedMerchant: String?,
    val parsedCategory: String?,
    val parsedWallet: String?,
    val isIncome: Boolean,
    val matchedRuleName: String
)
