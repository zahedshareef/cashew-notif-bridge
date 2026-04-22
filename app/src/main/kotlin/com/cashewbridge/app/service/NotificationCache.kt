package com.cashewbridge.app.service

import com.cashewbridge.app.model.CachedNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory cache of recent notifications, updated by [NotificationListenerService]
 * and observed by [com.cashewbridge.app.ui.NotificationsActivity].
 *
 * Thread-safe via @Synchronized.
 */
object NotificationCache {

    const val MAX_SIZE = 100

    private val _notifications = MutableStateFlow<List<CachedNotification>>(emptyList())
    val notifications: StateFlow<List<CachedNotification>> = _notifications.asStateFlow()

    @Synchronized
    fun add(notification: CachedNotification) {
        val current = _notifications.value.toMutableList()

        // Remove duplicate if same key already exists
        current.removeAll { it.key == notification.key }

        // Add to front (newest first)
        current.add(0, notification)

        // Trim to max size
        if (current.size > MAX_SIZE) {
            current.subList(MAX_SIZE, current.size).clear()
        }

        _notifications.value = current
    }

    @Synchronized
    fun remove(key: String) {
        _notifications.value = _notifications.value.filter { it.key != key }
    }

    @Synchronized
    fun clear() {
        _notifications.value = emptyList()
    }

    fun getByKey(key: String): CachedNotification? =
        _notifications.value.firstOrNull { it.key == key }
}
