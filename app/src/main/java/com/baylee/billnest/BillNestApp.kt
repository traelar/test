package com.baylee.billnest

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
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
        repo = BillRepository(this, syncDb)
        householdSync = HouseholdSyncRepository(repo, syncDb, sessionStore)
        BankApi.setSessionToken(sessionStore.load()?.sessionToken)

        val reminderRequest = PeriodicWorkRequestBuilder<BillReminderWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "bill-reminders",
            ExistingPeriodicWorkPolicy.UPDATE,
            reminderRequest
        )

        val syncConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val syncRequest = PeriodicWorkRequestBuilder<HouseholdSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(syncConstraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "household-sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
    }
}
