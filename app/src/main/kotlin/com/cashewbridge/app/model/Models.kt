package com.cashewbridge.app.model

import android.os.Parcel
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey

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
 * [conditionLogic]     — 0 = ALL conditions must match (AND), 1 = ANY condition matches (OR)
 * [cooldownMinutes]    — per-rule cooldown; rule won't fire again within N minutes (0 = no limit)
 * [activeStartHour]    — rule is only active from this hour (0-23); -1 = no restriction
 * [activeEndHour]      — rule is only active until this hour (0-23); -1 = no restriction
 * [activeDaysOfWeek]   — bitmask: bit0=Sun bit1=Mon … bit6=Sat; 0 = all days
 * [autoDetectType]     — when true, income/expense is inferred from notification keywords
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
    /** 0 = AND (all must match), 1 = OR (any must match) */
    val conditionLogic: Int = 0,
    /** Minutes before this rule can fire again for the same source (0 = no cooldown) */
    val cooldownMinutes: Int = 0,
    /** Hour of day (0-23) rule becomes active; -1 = unrestricted */
    val activeStartHour: Int = -1,
    /** Hour of day (0-23) rule stops being active; -1 = unrestricted */
    val activeEndHour: Int = -1,
    /** Bitmask of active days: bit0=Sun, bit1=Mon, …, bit6=Sat; 0 = all days */
    val activeDaysOfWeek: Int = 0
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
        activeDaysOfWeek = parcel.readInt()
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
    }

    override fun describeContents() = 0

    companion object CREATOR : Parcelable.Creator<NotificationRule> {
        override fun createFromParcel(parcel: Parcel) = NotificationRule(parcel)
        override fun newArray(size: Int) = arrayOfNulls<NotificationRule>(size)
    }
}

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
