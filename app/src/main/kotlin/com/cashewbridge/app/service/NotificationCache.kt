package com.cashewbridge.app.service

import android.content.Context
import android.util.Log
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.model.CachedNotification
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * In-memory cache of recent notifications, updated by [NotificationListenerService]
 * and observed by [com.cashewbridge.app.ui.NotificationsActivity].
 *
 * Also persists to Room DB so the list survives process death (max [MAX_PERSISTED] entries).
 */
object NotificationCache {

    private const val TAG = "NotificationCache"

    const val MAX_SIZE = 100
    private const val MAX_PERSISTED = 100

    private val _notifications = MutableStateFlow<List<CachedNotification>>(emptyList())
    val notifications: StateFlow<List<CachedNotification>> = _notifications.asStateFlow()

    // SupervisorJob so a single DB failure (e.g. transient SQLite disk I/O error)
    // cannot cancel the shared scope and silently no-op every subsequent launch.
    // The handler logs failures instead of letting them vanish.
    private val errorHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Background DB task failed", t)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)

    /** Call once on app start / service start to restore persisted notifications. */
    fun restoreFromDb(context: Context) {
        scope.launch {
            val db = AppDatabase.getInstance(context)
            val persisted = db.cachedNotificationDao().getAll()
            if (persisted.isNotEmpty()) {
                _notifications.value = persisted
            }
        }
    }

    @Synchronized
    fun add(notification: CachedNotification, context: Context? = null) {
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

        // Persist asynchronously
        context?.let { ctx ->
            scope.launch {
                val db = AppDatabase.getInstance(ctx)
                db.cachedNotificationDao().insert(notification)
                db.cachedNotificationDao().trimToSize(MAX_PERSISTED)
            }
        }
    }

    @Synchronized
    fun remove(key: String, context: Context? = null) {
        _notifications.value = _notifications.value.filter { it.key != key }
        context?.let { ctx ->
            scope.launch {
                AppDatabase.getInstance(ctx).cachedNotificationDao().deleteByKey(key)
            }
        }
    }

    @Synchronized
    fun clear(context: Context? = null) {
        _notifications.value = emptyList()
        context?.let { ctx ->
            scope.launch {
                AppDatabase.getInstance(ctx).cachedNotificationDao().deleteAll()
            }
        }
    }

    fun getByKey(key: String): CachedNotification? =
        _notifications.value.firstOrNull { it.key == key }
}
