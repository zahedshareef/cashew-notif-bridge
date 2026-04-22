package com.cashewbridge.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cashewbridge.app.model.AppDatabase
import com.cashewbridge.app.ui.BatchReviewActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when the batch collection window expires (#2).
 * Checks if there are any queued transactions and, if so, notifies the user
 * to open BatchReviewActivity.
 */
class BatchAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Batch window expired — checking queue")
        CoroutineScope(Dispatchers.IO).launch {
            val count = AppDatabase.getInstance(context).batchedTransactionDao().count()
            if (count > 0) {
                Log.i(TAG, "Batch has $count pending items — posting notification")
                NotificationHelper.postBatchReadyNotification(
                    context,
                    count,
                    BatchReviewActivity::class.java
                )
            } else {
                Log.d(TAG, "Batch is empty — nothing to show")
            }
        }
    }

    companion object {
        const val ACTION_BATCH_ALARM = "com.cashewbridge.app.ACTION_BATCH_ALARM"
        private const val TAG = "BatchAlarmReceiver"
    }
}
