package com.yonzmusic.engine

import android.app.*
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.R

class EngineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as YonzApp
        return try {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("yono-engine", "Unduh musik", NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(app, "yono-engine").setSmallIcon(R.drawable.brand_y).setContentTitle("Memproses daftar musik")
                .setContentIntent(PendingIntent.getActivity(app, 4300, android.content.Intent(app, com.yonzmusic.app.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setContentText("Progres dan pembatalan tersedia di Unduh musik").setOngoing(true).build()
            setForeground(if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(4300, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(4300, notification))
            app.acquisition.execute(inputData.getString("request")!!); Result.success()
        } catch(e:Exception) { app.acquisition.message.value=e.message?.take(200) ?: "Pekerjaan gagal dimulai";Result.failure() }
        finally { app.acquisition.busy.value = false }
    }
}
