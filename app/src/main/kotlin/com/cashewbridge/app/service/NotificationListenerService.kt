package com.cashewbridge.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.MainThread
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.BatchedTransaction
import com.cashewbridge.app.model.CachedNotification
import com.cashewbridge.app.model.NotificationRule
import com.cashewbridge.app.model.ProcessedLog
import com.cashewbridge.app.parser.CashewLinkBuilder
import com.cashewbridge.app.parser.NotificationParser
import com.cashewbridge.app.prefs.AppPreferences
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import android.app.Notification

class NotificationListenerService : android.service.notification.NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: AppPreferences
    private lateinit var db: AppDatabase

    // Global dedup: key -> timestamp
    private val recentEvents = LinkedHashMap<String, Long>(16, 0.75f, true)

    // Per-rule cooldown tracking: ruleId -> last fire timestamp (#9)
    private val ruleCooldowns = HashMap<Long, Long>()

    // Whether a batch alarm is already scheduled (#2)
    private var batchAlarmPending = false

    private val confirmIdCounter = AtomicInteger(1000)
    private val undoIdCounter = AtomicInteger(2000)
    private val alarmIdCounter = AtomicInteger(3000)

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(applicationContext)
        db = AppDatabase.getInstance(applicationContext)
        NotificationHelper.createChannels(applicationContext)
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
        if (packageName == applicationContext.packageName) return   // skip our own notifications

        val appLabel = try {
            applicationContext.packageManager
                .getApplicationLabel(
                    applicationContext.packageManager.getApplicationInfo(packageName, 0)
                ).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

        // Capture the SBN key for auto-dismiss (#1)
        val sbnKey = sbn.key

        serviceScope.launch {
            val key = "${packageName}_${sbn.id}_${sbn.tag ?: "notag"}"
            val rules: List<NotificationRule> = try {
                db.ruleDao().getEnabledRules()
            } catch (e: Exception) { emptyList() }

            val (transaction, matchedRule) = NotificationParser.parse(packageName, title, body, rules)

            val walletName = matchedRule?.defaultWalletName?.takeIf { it.isNotBlank() }
                ?: prefs.defaultWalletName

            // Always cache the notification (persisted to DB)
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
            if (prefs.minAmount > 0 && transaction.amount < prefs.minAmount) {
                logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                    transaction.category, transaction.isIncome, "SKIPPED_MIN_AMOUNT",
                    matchedRule?.name ?: "")
                return@launch
            }

            // ── Global dedup ──────────────────────────────────────────────────
            val dedupKey = "$packageName|${transaction.amount}|${body.take(20)}"
            val now = System.currentTimeMillis()
            val lastSeen = recentEvents[dedupKey]
            if (lastSeen != null && (now - lastSeen) < prefs.skipDuplicateWindowMs) {
                Log.d(TAG, "Global dedup skipped: $dedupKey")
                return@launch
            }
            recentEvents[dedupKey] = now
            if (recentEvents.size > 50) {
                recentEvents.entries.minByOrNull { it.value }?.let { recentEvents.remove(it.key) }
            }

            // ── Per-rule cooldown (#9) ─────────────────────────────────────────
            if (matchedRule != null && matchedRule.cooldownMinutes > 0) {
                val cooldownMs = matchedRule.cooldownMinutes * 60_000L
                val lastFired = ruleCooldowns[matchedRule.id]
                if (lastFired != null && (now - lastFired) < cooldownMs) {
                    Log.d(TAG, "Rule '${matchedRule.name}' in cooldown — skipped")
                    logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                        transaction.category, transaction.isIncome, "RULE_COOLDOWN",
                        matchedRule.name)
                    return@launch
                }
                ruleCooldowns[matchedRule.id] = now
            }

            // ── Large transaction alarm (#6) ──────────────────────────────────
            val threshold = prefs.largeTransactionThreshold
            if (threshold > 0 && transaction.amount >= threshold) {
                val alarmId = alarmIdCounter.incrementAndGet()
                NotificationHelper.postAlarmNotification(
                    context = applicationContext,
                    notifId = alarmId,
                    amountLabel = formatAmount(transaction.amount),
                    merchant = transaction.merchant,
                    appLabel = appLabel
                )
            }

            // Build Cashew URI up front (shared by several branches below)
            val cashewUri = CashewLinkBuilder.buildUri(transaction, walletName).toString()

            when {
                // ── Batch mode (#2) ───────────────────────────────────────────
                prefs.batchMode && !prefs.confirmBeforeAdding -> {
                    db.batchedTransactionDao().insert(
                        BatchedTransaction(
                            sourcePackage = packageName,
                            appLabel = appLabel,
                            sourceTitle = title,
                            cashewUri = cashewUri,
                            amount = transaction.amount,
                            merchant = transaction.merchant,
                            category = transaction.category,
                            isIncome = transaction.isIncome
                        )
                    )
                    scheduleBatchAlarmIfNeeded()
                    logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                        transaction.category, transaction.isIncome, "BATCHED",
                        matchedRule?.name ?: "heuristic")
                }

                // ── Confirm before adding (with privacy mode #4) ──────────────
                prefs.confirmBeforeAdding -> {
                    val typeLabel = if (transaction.isIncome) "Income" else "Expense"
                    val merchantPart = transaction.merchant?.let { " · $it" } ?: ""
                    val summary = "$typeLabel ${formatAmount(transaction.amount)}$merchantPart\n" +
                            "From $appLabel\n\nTap 'Send to Cashew' to confirm or 'Skip' to ignore."
                    NotificationHelper.postConfirmNotification(
                        context = applicationContext,
                        notifId = confirmIdCounter.incrementAndGet(),
                        summary = summary,
                        cashewUri = cashewUri,
                        privacyMode = prefs.privacyMode   // #4
                    )
                    logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                        transaction.category, transaction.isIncome, "PENDING_CONFIRM",
                        matchedRule?.name ?: "heuristic")
                }

                // ── Undo countdown (#7) ───────────────────────────────────────
                prefs.undoEnabled -> {
                    val undoId = undoIdCounter.incrementAndGet()
                    val countdown = AppPreferences.UNDO_COUNTDOWN_SECONDS
                    val typeLabel = if (transaction.isIncome) "Income" else "Expense"
                    val merchantPart = transaction.merchant?.let { " · $it" } ?: ""
                    val summary = "$typeLabel ${formatAmount(transaction.amount)}$merchantPart from $appLabel"
                    NotificationHelper.postUndoNotification(
                        context = applicationContext,
                        notifId = undoId,
                        summary = summary,
                        cashewUri = cashewUri,
                        countdownSeconds = countdown
                    )
                    scheduleUndoAlarm(undoId, cashewUri, countdown)
                    logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                        transaction.category, transaction.isIncome, "PENDING_UNDO",
                        matchedRule?.name ?: "heuristic")
                }

                // ── Auto-forward ──────────────────────────────────────────────
                else -> {
                    val intent = CashewLinkBuilder.buildIntent(transaction, walletName)
                    try {
                        applicationContext.startActivity(intent)
                        // Auto-dismiss source notification (#1)
                        if (prefs.autoDismissSource) {
                            try { cancelNotification(sbnKey) } catch (e: Exception) {
                                Log.w(TAG, "Could not cancel source notification: ${e.message}")
                            }
                        }
                        logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                            transaction.category, transaction.isIncome, "LAUNCHED",
                            matchedRule?.name ?: "heuristic")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch Cashew", e)
                        logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                            transaction.category, transaction.isIncome, "FAILED: ${e.message}",
                            matchedRule?.name ?: "")
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Keep in cache — available for manual review in the app
    }

    // ── Batch alarm scheduling (#2) ───────────────────────────────────────────

    @Synchronized
    private fun scheduleBatchAlarmIfNeeded() {
        if (batchAlarmPending) return
        batchAlarmPending = true
        val triggerAt = System.currentTimeMillis() + prefs.batchWindowMinutes * 60_000L
        val intent = Intent(BatchAlarmReceiver.ACTION_BATCH_ALARM).apply {
            setPackage(applicationContext.packageName)
        }
        val pi = PendingIntent.getBroadcast(
            applicationContext, BATCH_ALARM_REQUEST,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        Log.d(TAG, "Batch alarm scheduled for ${prefs.batchWindowMinutes} min from now")
    }

    // ── Undo countdown alarm (#7) ─────────────────────────────────────────────

    private fun scheduleUndoAlarm(notifId: Int, cashewUri: String, countdownSeconds: Long) {
        val triggerAt = System.currentTimeMillis() + countdownSeconds * 1000L
        val intent = Intent(NotificationHelper.ACTION_UNDO_ALARM_FIRE).apply {
            setPackage(applicationContext.packageName)
            putExtra(NotificationHelper.EXTRA_CASHEW_URI, cashewUri)
            putExtra(NotificationHelper.EXTRA_UNDO_ID, notifId)
        }
        val pi = PendingIntent.getBroadcast(
            applicationContext,
            UndoActionReceiver.ALARM_REQUEST_BASE + notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatAmount(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString()
        else "%.2f".format(amount)

    private suspend fun logEvent(
        packageName: String, title: String, body: String,
        amount: Double?, merchant: String?, category: String?,
        isIncome: Boolean, action: String, ruleName: String
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
        private const val BATCH_ALARM_REQUEST = 6001
    }
}
