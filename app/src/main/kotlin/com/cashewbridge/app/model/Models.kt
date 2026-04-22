package com.cashewbridge.app.model

import android.os.Parcel
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A parsed transaction extracted from a notification.
 */
data class ParsedTransaction(
    val amount: Double,
    val merchant: String?,
    val category: String?,
    val note: String?,
    val isIncome: Boolean,
    val currency: String = "USD",
    val sourcePackage: String,
    val sourceTitle: String,
    val rawText: String
)

/**
 * A user-defined rule for matching notifications and mapping to Cashew parameters.
 *
 * [autoDetectType] — when true, income/expense is inferred from notification keywords
 *                   instead of always using [isIncome].
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
    /** When true, use keyword heuristics to detect income vs expense instead of [isIncome]. */
    val autoDetectType: Boolean = false
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
        autoDetectType = parcel.readByte() != 0.toByte()
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
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<NotificationRule> {
        override fun createFromParcel(parcel: Parcel) = NotificationRule(parcel)
        override fun newArray(size: Int) = arrayOfNulls<NotificationRule>(size)
    }
}

/**
 * Log entry for each processed notification.
 */
@Entity(tableName = "logs")
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
    val matchedRuleName: String = ""
)
