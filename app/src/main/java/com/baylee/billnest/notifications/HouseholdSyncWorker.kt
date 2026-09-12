package com.baylee.billnest.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baylee.billnest.BillNestApp
import com.baylee.billnest.data.HttpApiException

class HouseholdSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BillNestApp
        return runCatching {
            app.householdSync.syncNow()
            Result.success()
        }.getOrElse { failure ->
            when (failure) {
                is HttpApiException -> if (failure.statusCode in 400..499) Result.failure() else Result.retry()
                else -> Result.retry()
            }
        }
    }
}
