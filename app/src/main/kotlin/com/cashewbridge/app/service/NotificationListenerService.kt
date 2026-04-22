package com.cashewbridge.app.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import android.util.Log
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.CachedNotification
import com.cashewbridge.app.model.NotificationRule
import com.cashewbridge.app.model.ProcessedLog
import com.cashewbridge.app.parser.CashewLinkBuilder
import com.cashewbridge.app.parser.NotificationParser
import com.cashewbridge.app.prefs.AppPreferences
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class NotificationListenerService : android.service.notification.NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: AppPreferences
    private lateinit var db: AppDatabase

    // Dedup: key -> timestamp
    private val recentEvents = LinkedHashMap<String, Long>(16, 0.75f, true)

    // Counter for unique confirm notification IDs
    private val confirmIdCounter = AtomicInteger(1000)

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(applicationContext)
        db = AppDatabase.getInstance(applicationContext)
        NotificationHelper.createChannels(applicationContext)
        // Restore persisted notifications on service start
        NotificationCache.restoreFromDb(applicationContext)
        Log.i(TAG, "NotificationListenerService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: ""

        if (title.isBlank() && body.isBlank()) return

        // Skip our own confirm notifications to avoid infinite loop
        if (packageName == applicationContext.packageName) return

        val appLabel = try {
            applicationContext.packageManager
                .getApplicationLabel(
                    applicationContext.packageManager.getApplicationInfo(packageName, 0)
                ).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

        serviceScope.launch {
            val key = "${packageName}_${sbn.id}_${sbn.tag ?: "notag"}"
            val rules: List<NotificationRule> = try {
                db.ruleDao().getEnabledRules()
            } catch (e: Exception) {
                emptyList()
            }

            val (transaction, matchedRule) = NotificationParser.parse(packageName, title, body, rules)

            val walletName = matchedRule?.defaultWalletName?.takeIf { it.isNotBlank() }
                ?: prefs.defaultWalletName

            // Always add to the cache (persists to DB via context)
            NotificationCache.add(
                CachedNotification(
                    key = key,
                    packageName = packageName,
                    appLabel = appLabel,
                    title = title,
                    body = body,
                    timestamp = System.currentTimeMillis(),
                    parsedAmount = transaction?.amount,
                    parsedMerchant = transaction?.merchant,
                    parsedCategory = transaction?.category,
                    parsedWallet = walletName.takeIf { it.isNotBlank() },
                    isIncome = transaction?.isIncome ?: false,
                    matchedRuleName = matchedRule?.name ?: ""
                ),
                context = applicationContext
            )

            if (!prefs.isEnabled) return@launch
            if (transaction == null) {
                logEvent(packageName, title, body, null, null, null, false, "NO_AMOUNT", "")
                return@launch
            }
            if (transaction.amount < prefs.minAmount && prefs.minAmount > 0) {
                logEvent(
                    packageName, title, body, transaction.amount, transaction.merchant,
                    transaction.category, transaction.isIncome, "SKIPPED_MIN_AMOUNT",
                    matchedRule?.name ?: ""
                )
                return@launch
            }

            // Deduplication
            val dedupKey = "$packageName|${transaction.amount}|${body.take(20)}"
            val now = System.currentTimeMillis()
            val lastSeen = recentEvents[dedupKey]
            if (lastSeen != null && (now - lastSeen) < prefs.skipDuplicateWindowMs) {
                Log.d(TAG, "Duplicate skipped: $dedupKey")
                return@launch
            }
            recentEvents[dedupKey] = now
            if (recentEvents.size > 50) {
                recentEvents.entries.minByOrNull { it.value }?.let { recentEvents.remove(it.key) }
            }

            if (prefs.confirmBeforeAdding) {
                // Post a confirm notification instead of launching Cashew immediately
                val uri = CashewLinkBuilder.buildUri(transaction, walletName).toString()
                val typeLabel = if (transaction.isIncome) "Income" else "Expense"
                val merchantPart = transaction.merchant?.let { " · $it" } ?: ""
                val summary = "$typeLabel ${formatAmount(transaction.amount)}$merchantPart\nFrom $appLabel\n\nTap 'Send to Cashew' to confirm or 'Skip' to ignore."
                NotificationHelper.postConfirmNotification(
                    context = applicationContext,
                    notifId = confirmIdCounter.incrementAndGet(),
                    summary = summary,
                    cashewUri = uri
                )
                logEvent(
                    packageName, title, body, transaction.amount, transaction.merchant,
                    transaction.category, transaction.isIncome, "PENDING_CONFIRM",
                    matchedRule?.name ?: "heuristic"
                )
            } else {
                // Auto-launch Cashew
                val intent = CashewLinkBuilder.buildIntent(transaction, walletName)
                try {
                    applicationContext.startActivity(intent)
                    Log.i(TAG, "Cashew launched: ${transaction.amount}")
                    logEvent(
                        packageName, title, body, transaction.amount, transaction.merchant,
                        transaction.category, transaction.isIncome, "LAUNCHED",
                        matchedRule?.name ?: "heuristic"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch Cashew", e)
                    logEvent(
                        packageName, title, body, transaction.amount, transaction.merchant,
                        transaction.category, transaction.isIncome, "FAILED: ${e.message}",
                        matchedRule?.name ?: ""
                    )
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Keep in cache on dismiss — available for manual review
    }

    private fun formatAmount(amount: Double): String {
        return if (amount == amount.toLong().toDouble()) {
            amount.toLong().toString()
        } else {
            "%.2f".format(amount)
        }
    }

    private suspend fun logEvent(
        packageName: String,
        title: String,
        body: String,
        amount: Double?,
        merchant: String?,
        category: String?,
        isIncome: Boolean,
        action: String,
        ruleName: String
    ) {
        try {
            db.logDao().insertLog(
                ProcessedLog(
                    sourcePackage = packageName,
                    sourceTitle = title,
                    rawText = "$title\n$body".take(500),
                    parsedAmount = amount,
                    parsedMerchant = merchant,
                    parsedCategory = category,
                    isIncome = isIncome,
                    actionTaken = action,
                    matchedRuleName = ruleName
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log event", e)
        }
    }

    companion object {
        private const val TAG = "CashewBridge"
    }
}
