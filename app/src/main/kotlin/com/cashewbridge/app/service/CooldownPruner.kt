package com.cashewbridge.app.service

/**
 * Pure helper for pruning the per-rule cooldown map kept by
 * [NotificationListenerService]. Lives on its own so unit tests can exercise
 * the logic without having to class-load an Android service subclass.
 */
internal object CooldownPruner {

    /** Only start pruning once the map is this large — avoids churn on small installs. */
    const val PRUNE_AT_SIZE = 32

    /**
     * Cooldowns longer than a week are not plausible; any entry older than
     * this is stale (deleted rule, long-disabled rule, etc.).
     */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    fun prune(map: MutableMap<Long, Long>, now: Long) {
        if (map.size < PRUNE_AT_SIZE) return
        val cutoff = now - MAX_AGE_MS
        val it = map.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value < cutoff) it.remove()
        }
    }
}
