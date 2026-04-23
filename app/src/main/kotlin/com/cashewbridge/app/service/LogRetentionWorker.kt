package com.cashewbridge.app.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cashewbridge.app.model.AppDatabase
import java.util.concurrent.TimeUnit

/**
 * Deletes `logs` rows older than [RETENTION_DAYS] so the activity log doesn't
 * grow unbounded over months of use. Scheduled once at service start via
 * [schedule]; WorkManager handles re-running it daily, skipping executions
 * when the device is idle / the process is dead, and resuming after reboot.
 */
class LogRetentionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * MILLIS_PER_DAY
        return try {
            AppDatabase.getInstance(applicationContext).logDao().deleteOlderThan(cutoff)
            Log.i(TAG, "Pruned logs older than ${RETENTION_DAYS} days (cutoff=$cutoff)")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Log retention pass failed", e)
            // Retry on next scheduled run rather than burning a backoff cycle;
            // the table will self-heal the next time WorkManager fires.
            Result.success()
        }
    }

    companion object {
        private const val TAG = "LogRetentionWorker"
        private const val WORK_NAME = "log_retention"
        private const val RETENTION_DAYS = 90L
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LogRetentionWorker>(
                1, TimeUnit.DAYS
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP so we don't reset the 24h timer every time NLS starts.
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
