package com.cashewbridge.app.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.MainThread
import com.cashewbridge.app.R
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.BatchedTransaction
import com.cashewbridge.app.model.CachedNotification
import com.cashewbridge.app.model.NotificationRule
import com.cashewbridge.app.model.ProcessedLog
import com.cashewbridge.app.parser.CashewLinkBuilder
import com.cashewbridge.app.parser.NotificationParser
import com.cashewbridge.app.prefs.AppPreferences
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NotificationListenerService : android.service.notification.NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: AppPreferences
    private lateinit var db: AppDatabase

    // Global dedup: key -> timestamp
    private val recentEvents = LinkedHashMap<String, Long>(16, 0.75f, true)

    // Per-rule cooldown tracking: ruleId -> last fire timestamp
    private val ruleCooldowns = HashMap<Long, Long>()

    // Whether a batch alarm is already scheduled
    private var batchAlarmPending = false

    private val confirmIdCounter = AtomicInteger(1000)
    private val undoIdCounter = AtomicInteger(2000)
    private val alarmIdCounter = AtomicInteger(3000)

    // Fire-once flags so persistent integration problems don't spam the tray.
    private val rulesLoadFailureReported = AtomicBoolean(false)
    private val cashewMissingReported = AtomicBoolean(false)

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
        // #8 — sender / sub-text extraction (Google Pay, Venmo, Zelle, etc.)
        val senderText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
            ?: ""

        if (title.isBlank() && body.isBlank()) return
        if (packageName == applicationContext.packageName) return

        val appLabel = try {
            applicationContext.packageManager.getApplicationLabel(
                applicationContext.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (e: PackageManager.NameNotFoundException) { packageName }

        val sbnKey = sbn.key

        serviceScope.launch {
            // ── #9 App blocklist ──────────────────────────────────────────────
            val isBlocked = try { db.appBlocklistDao().isBlocked(packageName) > 0 } catch (e: Exception) { false }
            if (isBlocked) {
                Log.d(TAG, "App $packageName is in blocklist — skipped")
                return@launch
            }

            val cacheKey = "${packageName}_${sbn.id}_${sbn.tag ?: "notag"}"
            val rules: List<NotificationRule> = try {
                db.ruleDao().getEnabledRules()
            } catch (e: Exception) {
                // Previously this swallowed the exception and returned an empty
                // list, so a corrupted DB silently disabled every user rule
                // with zero feedback. Log loudly and, once per service
                // lifetime, tell the user what happened.
                Log.e(TAG, "Rule DAO failed — falling back to heuristics only", e)
                if (rulesLoadFailureReported.compareAndSet(false, true)) {
                    NotificationHelper.postIntegrationWarning(
                        applicationContext,
                        NotificationHelper.ID_RULES_LOAD_FAILED,
                        applicationContext.getString(R.string.err_rules_load_failed_title),
                        applicationContext.getString(R.string.err_rules_load_failed_body)
                    )
                }
                emptyList()
            }

            // Pass senderText into parse() for #8
            val (transaction, matchedRule) = NotificationParser.parse(
                packageName, title, body, senderText, rules
            )

            val walletName = matchedRule?.defaultWalletName?.takeIf { it.isNotBlank() }
                ?: prefs.defaultWalletName

            // Always cache the notification
            NotificationCache.add(
                CachedNotification(
                    key = cacheKey,
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
                logEvent(packageName, title, body, null, null, null, false, "NO_AMOUNT", "", "USD")
                return@launch
            }
            if (prefs.minAmount > 0 && transaction.amount < prefs.minAmount) {
                logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                    transaction.category, transaction.isIncome, "SKIPPED_MIN_AMOUNT",
                    matchedRule?.name ?: "", transaction.currency)
                return@launch
            }

            // ── #12 Fuzzy duplicate detection ─────────────────────────────────
            val now = System.currentTimeMillis()
            val dedupKey = if (prefs.fuzzyDedupEnabled) {
                "$packageName|${transaction.amount}"  // fuzzy: app + amount only
            } else {
                "$packageName|${transaction.amount}|${body.take(20)}"  // exact (original)
            }
            val lastSeen = recentEvents[dedupKey]
            if (lastSeen != null && (now - lastSeen) < prefs.skipDuplicateWindowMs) {
                Log.d(TAG, "Dedup skipped${if (prefs.fuzzyDedupEnabled) " (fuzzy)" else ""}: $dedupKey")
                return@launch
            }
            recentEvents[dedupKey] = now
            if (recentEvents.size > 100) {
                recentEvents.entries.minByOrNull { it.value }?.let { recentEvents.remove(it.key) }
            }

            // ── Per-rule cooldown ─────────────────────────────────────────────
            if (matchedRule != null && matchedRule.cooldownMinutes > 0) {
                val cooldownMs = matchedRule.cooldownMinutes * 60_000L
                val lastFired = ruleCooldowns[matchedRule.id]
                if (lastFired != null && (now - lastFired) < cooldownMs) {
                    Log.d(TAG, "Rule '${matchedRule.name}' in cooldown — skipped")
                    logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                        transaction.category, transaction.isIncome, "RULE_COOLDOWN",
                        matchedRule.name, transaction.currency)
                    return@launch
                }
                ruleCooldowns[matchedRule.id] = now
            }

            // ── Large transaction alarm ───────────────────────────────────────
            val threshold = prefs.largeTransactionThreshold
            if (threshold > 0 && transaction.amount >= threshold) {
                NotificationHelper.postAlarmNotification(
                    context = applicationContext,
                    notifId = alarmIdCounter.incrementAndGet(),
                    amountLabel = formatAmount(transaction.amount, transaction.currency),
                    merchant = transaction.merchant,
                    appLabel = appLabel
                )
            }

            val cashewUri = CashewLinkBuilder.buildUri(transaction, walletName).toString()

            when {
                // ── Batch mode ────────────────────────────────────────────────
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
                        matchedRule?.name ?: "heuristic", transaction.currency)
                }

                // ── Confirm before adding ─────────────────────────────────────
                prefs.confirmBeforeAdding -> {
                    val typeLabel = if (transaction.isIncome) "Income" else "Expense"
                    val merchantPart = transaction.merchant?.let { " · $it" } ?: ""
                    val summary = "$typeLabel ${formatAmount(transaction.amount, transaction.currency)}$merchantPart\n" +
                            "From $appLabel\n\nTap 'Send to Cashew' to confirm or 'Skip' to ignore."
                    NotificationHelper.postConfirmNotification(
                        context = applicationContext,
                        notifId = confirmIdCounter.incrementAndGet(),
                        summary = summary,
                        cashewUri = cashewUri,
                        privacyMode = prefs.privacyMode
                    )
                    logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                        transaction.category, transaction.isIncome, "PENDING_CONFIRM",
                        matchedRule?.name ?: "heuristic", transaction.currency)
                }

                // ── Undo countdown ────────────────────────────────────────────
                prefs.undoEnabled -> {
                    val undoId = undoIdCounter.incrementAndGet()
                    val countdown = AppPreferences.UNDO_COUNTDOWN_SECONDS
                    val typeLabel = if (transaction.isIncome) "Income" else "Expense"
                    val merchantPart = transaction.merchant?.let { " · $it" } ?: ""
                    val summary = "$typeLabel ${formatAmount(transaction.amount, transaction.currency)}$merchantPart from $appLabel"
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
                        matchedRule?.name ?: "heuristic", transaction.currency)
                }

                // ── Auto-forward ──────────────────────────────────────────────
                else -> {
                    // Proactively detect a missing Cashew install so the user
                    // gets a real explanation the first time, instead of just
                    // seeing notifications quietly stop flowing through.
                    if (!CashewLinkBuilder.isCashewInstalled(applicationContext)) {
                        if (cashewMissingReported.compareAndSet(false, true)) {
                            NotificationHelper.postIntegrationWarning(
                                applicationContext,
                                NotificationHelper.ID_CASHEW_MISSING,
                                applicationContext.getString(R.string.err_cashew_missing_title),
                                applicationContext.getString(R.string.err_cashew_missing_body)
                            )
                        }
                        logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                            transaction.category, transaction.isIncome, "FAILED: cashew_not_installed",
                            matchedRule?.name ?: "heuristic", transaction.currency)
                        return@launch
                    }

                    val intent = CashewLinkBuilder.buildIntent(transaction, walletName)
                    try {
                        applicationContext.startActivity(intent)
                        if (prefs.autoDismissSource) {
                            try { cancelNotification(sbnKey) } catch (e: Exception) {
                                Log.w(TAG, "Could not cancel source notification: ${e.message}")
                            }
                        }
                        // #11 — Tasker / Automation broadcast
                        broadcastTransactionForwarded(packageName, transaction.amount,
                            transaction.merchant, transaction.category,
                            transaction.isIncome, transaction.currency)

                        logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                            transaction.category, transaction.isIncome, "LAUNCHED",
                            matchedRule?.name ?: "heuristic", transaction.currency)

                        // Update home screen widget (#10)
                        BridgeWidget.requestUpdate(applicationContext)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch Cashew", e)
                        logEvent(packageName, title, body, transaction.amount, transaction.merchant,
                            transaction.category, transaction.isIncome, "FAILED: ${e.message}",
                            matchedRule?.name ?: "", transaction.currency)
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { /* keep in cache */ }

    // ── #11 Tasker / Automation broadcast ─────────────────────────────────────

    private fun broadcastTransactionForwarded(
        packageName: String, amount: Double, merchant: String?,
        category: String?, isIncome: Boolean, currency: String
    ) {
        val broadcast = Intent(ACTION_TRANSACTION_FORWARDED).apply {
            putExtra("amount", amount)
            putExtra("merchant", merchant ?: "")
            putExtra("category", category ?: "")
            putExtra("isIncome", isIncome)
            putExtra("currency", currency)
            putExtra("sourcePackage", packageName)
        }
        applicationContext.sendBroadcast(broadcast)
    }

    // ── Batch alarm scheduling ────────────────────────────────────────────────

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
        setExactOrFallback(triggerAt, pi)
    }

    // ── Undo countdown alarm ──────────────────────────────────────────────────

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
        setExactOrFallback(triggerAt, pi)
    }

    /**
     * Android 12+ can revoke SCHEDULE_EXACT_ALARM at any time, and
     * [AlarmManager.setExactAndAllowWhileIdle] throws [SecurityException] when
     * that happens. Fall back to the inexact variant so batches / undo windows
     * still fire (a little later) instead of the entire flow going dark.
     */
    private fun setExactOrFallback(triggerAt: Long, pi: PendingIntent) {
        val am = applicationContext.getSystemService(ALARM_SERVICE) as AlarmManager
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (se: SecurityException) {
            Log.w(TAG, "Exact alarm denied; falling back to inexact", se)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatAmount(amount: Double, currency: String): String {
        val symbol = when (currency) {
            "USD" -> "$"
            "GBP" -> "£"
            "EUR" -> "€"
            "INR" -> "₹"
            else -> "$currency "
        }
        return if (amount == amount.toLong().toDouble())
            "$symbol${amount.toLong()}"
        else
            "$symbol${"%.2f".format(amount)}"
    }

    private suspend fun logEvent(
        packageName: String, title: String, body: String,
        amount: Double?, merchant: String?, category: String?,
        isIncome: Boolean, action: String, ruleName: String, currency: String
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
                    matchedRuleName = ruleName,
                    currency = currency
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log event", e)
        }
    }

    companion object {
        private const val TAG = "CashewBridge"
        private const val BATCH_ALARM_REQUEST = 6001

        /** #11 — Broadcast sent after every successful Cashew launch. External apps (Tasker, Automate) can listen. */
        const val ACTION_TRANSACTION_FORWARDED = "com.cashewbridge.app.TRANSACTION_FORWARDED"
    }
}
