package com.baylee.billnest

import android.app.Application
import androidx.work.*
import com.baylee.billnest.data.BillRepository
import com.baylee.billnest.notifications.BillReminderWorker
import java.util.concurrent.TimeUnit

class BillNestApp : Application() {
    lateinit var repo: BillRepository
    override fun onCreate() {
        super.onCreate()
        repo = BillRepository(this)
        val request = PeriodicWorkRequestBuilder<BillReminderWorker>(24, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("bill-reminders", ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
