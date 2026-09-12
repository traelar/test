package com.baylee.billnest

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.baylee.billnest.data.BankApi
import com.baylee.billnest.data.BillRepository
import com.baylee.billnest.data.HouseholdSyncRepository
import com.baylee.billnest.data.LocalSyncDb
import com.baylee.billnest.data.SecureSessionStore
import com.baylee.billnest.notifications.BillReminderWorker
import com.baylee.billnest.notifications.HouseholdSyncWorker
import java.util.concurrent.TimeUnit

class BillNestApp : Application() {
    lateinit var repo: BillRepository
    lateinit var sessionStore: SecureSessionStore
    lateinit var syncDb: LocalSyncDb
    lateinit var householdSync: HouseholdSyncRepository

    override fun onCreate() {
        super.onCreate()

        sessionStore = SecureSessionStore(this)
        syncDb = LocalSyncDb(this)
        repo = BillRepository(this, syncDb) { enqueueImmediateSync() }
        householdSync = HouseholdSyncRepository(repo, syncDb, sessionStore)
        BankApi.setSessionToken(sessionStore.load()?.sessionToken)

        val reminderRequest = PeriodicWorkRequestBuilder<BillReminderWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "bill-reminders",
            ExistingPeriodicWorkPolicy.UPDATE,
            reminderRequest
        )

        val syncRequest = PeriodicWorkRequestBuilder<HouseholdSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "household-sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
    }

    fun enqueueImmediateSync() {
        val request = OneTimeWorkRequestBuilder<HouseholdSyncWorker>()
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "household-sync-now",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
