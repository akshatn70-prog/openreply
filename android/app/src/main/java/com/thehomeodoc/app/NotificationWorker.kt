package com.thehomeodoc.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class NotificationWorker(appContext: Context, params: WorkerParameters): CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val store = SessionStore(applicationContext)
        if (store.token == null) return Result.success()
        return try {
            val data = ApiClient(store).get("/api/dashboard/stats").getAsJsonObject("data")
            val failed = data.get("dmsFailedMonth")?.asInt ?: 0
            val prefs = applicationContext.getSharedPreferences("notifications", Context.MODE_PRIVATE)
            val previous = prefs.getInt("failed", 0)
            if (failed > previous && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(NotificationChannel("thehomeodoc", "thehomeodoc", NotificationManager.IMPORTANCE_DEFAULT))
                manager.notify(1001, NotificationCompat.Builder(applicationContext, "thehomeodoc")
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle("thehomeodoc")
                    .setContentText("${failed - previous} new DM failure(s) need attention.")
                    .setAutoCancel(true).build())
            }
            prefs.edit().putInt("failed", failed).apply()
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}
