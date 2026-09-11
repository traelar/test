package com.baylee.billnest.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.baylee.billnest.data.EncryptedStore
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class BillReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val channelId = "bill_reminders"
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "Bill reminders", NotificationManager.IMPORTANCE_HIGH))
        val data = EncryptedStore(applicationContext).load()
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val today = LocalDate.now()
        data.bills.forEach { bill ->
            val days = ChronoUnit.DAYS.between(today, bill.dueDate()).toInt()
            if (days in data.reminderDays && !bill.isPaidFor()) {
                val whenText = when (days) { 0 -> "due today"; 1 -> "due tomorrow"; else -> "due in $days days" }
                val n = NotificationCompat.Builder(applicationContext, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("${bill.name} • $whenText")
                    .setContentText("$${"%.2f".format(bill.amount)}${if (bill.autopay) " • Autopay" else ""}")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true).build()
                NotificationManagerCompat.from(applicationContext).notify(bill.id.hashCode(), n)
            }
        }
        return Result.success()
    }
}
