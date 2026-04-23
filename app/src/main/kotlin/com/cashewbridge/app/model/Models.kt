package com.cashewbridge.app.model

import android.os.Parcel
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A fully parsed transaction ready to be forwarded to Cashew.
 * [currency] is a 3-letter ISO-4217 code detected from the notification.
 * [note]     is an extracted memo / reference number (optional).
 * [confidence] is a 0-100 score for heuristic parsing quality.
 */
data class ParsedTransaction(
    val amount: Double,
    val merchant: String?,
    val category: String?,
    val note: String?,
    val isIncome: Boolean,
    val currency: String = "USD",
    val confidence: Int = 100,
    val sourcePackage: String,
    val sourceTitle: String,
    val rawText: String
)

/**
 * A user-defined rule for matching notifications and mapping to Cashew parameters.
 *
 * New in v4:
 *  [noteRegex]        — regex to extract a memo / reference number → forwarded as Cashew note
 *  [senderContains]   — matches notification sub-text (sender name) for P2P payment apps
 *  [minAmountFilter]  — rule only fires if parsed amount >= this (0 = no lower bound)
 *  [maxAmountFilter]  — rule only fires if parsed amount <= this (0 = no upper bound)
 *  [currencyOverride] — if set, overrides the auto-detected currency (e.g. "INR")
 */
@Entity(tableName = "rules")
data class NotificationRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val packageName: String,
    val titleContains: String = "",
    val bodyContains: String = "",
    val amountRegex: String = "",
    val merchantRegex: String = "",
    val defaultCategory: String = "",
    val defaultWalletName: String = "",
    val isIncome: Boolean = false,
    val isEnabled: Boolean = true,
    val priority: Int = 0,
    val autoDetectType: Boolean = false,
    val conditionLogic: Int = 0,
    val cooldownMinutes: Int = 0,
    val activeStartHour: Int = -1,
    val activeEndHour: Int = -1,
    val activeDaysOfWeek: Int = 0,
    // ── v4 additions ────────────────────────────────────────────────────────
    /** Regex capturing group 1 → note/memo forwarded to Cashew */
    val noteRegex: String = "",
    /** Substring match against notification sender / sub-text (#8) */
    val senderContains: String = "",
    /** Rule only fires when amount >= this value (0 = no lower bound) (#2) */
    val minAmountFilter: Double = 0.0,
    /** Rule only fires when amount <= this value (0 = no upper bound) (#2) */
    val maxAmountFilter: Double = 0.0,
    /** If non-blank, overrides auto-detected currency (#1) */
    val currencyOverride: String = ""
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readLong(),
        name = parcel.readString() ?: "",
        packageName = parcel.readString() ?: "",
        titleContains = parcel.readString() ?: "",
        bodyContains = parcel.readString() ?: "",
        amountRegex = parcel.readString() ?: "",
        merchantRegex = parcel.readString() ?: "",
        defaultCategory = parcel.readString() ?: "",
        defaultWalletName = parcel.readString() ?: "",
        isIncome = parcel.readByte() != 0.toByte(),
        isEnabled = parcel.readByte() != 0.toByte(),
        priority = parcel.readInt(),
        autoDetectType = parcel.readByte() != 0.toByte(),
        conditionLogic = parcel.readInt(),
        cooldownMinutes = parcel.readInt(),
        activeStartHour = parcel.readInt(),
        activeEndHour = parcel.readInt(),
        activeDaysOfWeek = parcel.readInt(),
        noteRegex = parcel.readString() ?: "",
        senderContains = parcel.readString() ?: "",
        minAmountFilter = parcel.readDouble(),
        maxAmountFilter = parcel.readDouble(),
        currencyOverride = parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(name)
        parcel.writeString(packageName)
        parcel.writeString(titleContains)
        parcel.writeString(bodyContains)
        parcel.writeString(amountRegex)
        parcel.writeString(merchantRegex)
        parcel.writeString(defaultCategory)
        parcel.writeString(defaultWalletName)
        parcel.writeByte(if (isIncome) 1 else 0)
        parcel.writeByte(if (isEnabled) 1 else 0)
        parcel.writeInt(priority)
        parcel.writeByte(if (autoDetectType) 1 else 0)
        parcel.writeInt(conditionLogic)
        parcel.writeInt(cooldownMinutes)
        parcel.writeInt(activeStartHour)
        parcel.writeInt(activeEndHour)
        parcel.writeInt(activeDaysOfWeek)
        parcel.writeString(noteRegex)
        parcel.writeString(senderContains)
        parcel.writeDouble(minAmountFilter)
        parcel.writeDouble(maxAmountFilter)
        parcel.writeString(currencyOverride)
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<NotificationRule> {
        override fun createFromParcel(parcel: Parcel) = NotificationRule(parcel)
        override fun newArray(size: Int) = arrayOfNulls<NotificationRule>(size)
    }
}

@Entity(
    tableName = "logs",
    indices = [
        // `timestamp` is filtered on every insights / summary / stats query.
        Index("timestamp"),
        // Most of those queries also filter by `actionTaken IN (...)`; the
        // composite lets SQLite seek directly to matching rows instead of
        // scanning the table.
        Index("actionTaken", "timestamp")
    ]
)
data class ProcessedLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val sourcePackage: String,
    val sourceTitle: String,
    val rawText: String,
    val parsedAmount: Double?,
    val parsedMerchant: String?,
    val parsedCategory: String?,
    val isIncome: Boolean,
    val actionTaken: String,
    val matchedRuleName: String = "",
    val currency: String = "USD"
)

/**
 * A transaction held in the batch queue waiting for user review.
 */
@Entity(tableName = "batch_queue")
data class BatchedTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourcePackage: String,
    val appLabel: String,
    val sourceTitle: String,
    val cashewUri: String,
    val amount: Double,
    val merchant: String?,
    val category: String?,
    val isIncome: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * An app whose notifications should be silently ignored (#9 — App Blocklist).
 */
@Entity(tableName = "app_blocklist", primaryKeys = ["packageName"])
data class AppBlocklistEntry(
    val packageName: String,
    val appLabel: String,
    val addedAt: Long = System.currentTimeMillis()
)
