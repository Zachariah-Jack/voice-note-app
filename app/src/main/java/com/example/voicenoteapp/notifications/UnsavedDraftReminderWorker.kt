package com.example.voicenoteapp.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.voicenoteapp.AppDeepLinks
import com.example.voicenoteapp.MainActivity
import com.example.voicenoteapp.R
import com.example.voicenoteapp.data.db.AppDatabase
import java.time.LocalTime
import java.util.concurrent.TimeUnit

class UnsavedDraftReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (isQuietHours()) return Result.success()

        val db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            AppDatabase.DB_NAME
        )
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

        val count = try {
            db.draftDao().getVoiceDraftCount()
        } finally {
            db.close()
        }

        if (count <= 0) return Result.success()
        postReminder(count)
        return Result.success()
    }

    private fun postReminder(count: Int) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = AppDeepLinks.ACTION_OPEN_UNSAVED_DRAFTS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, DraftReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_note)
            .setContentTitle("Unsaved notes waiting")
            .setContentText("$count unsaved draft${if (count == 1) "" else "s"}. Tap to continue.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(DraftReminderScheduler.NOTIFICATION_ID, notification)
    }

    private fun isQuietHours(): Boolean {
        val now = LocalTime.now()
        val quietStart = LocalTime.of(22, 0)
        val quietEnd = LocalTime.of(6, 0)
        return now >= quietStart || now < quietEnd
    }
}

object DraftReminderScheduler {
    private const val UNIQUE_WORK_NAME = "unsaved_draft_reminders"
    const val CHANNEL_ID = "unsaved_drafts_channel"
    const val NOTIFICATION_ID = 4201

    fun schedule(context: Context) {
        createChannel(context)
        val request = PeriodicWorkRequestBuilder<UnsavedDraftReminderWorker>(30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Unsaved Draft Reminders",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Reminders for unsaved voice notes."
        }
        manager.createNotificationChannel(channel)
    }
}
