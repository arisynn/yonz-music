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
            val requestId=inputData.getString("request")!!
            val job=app.repository.dao.state("stream-download:$requestId")?.value?.let {org.json.JSONObject(it)}
            if(job!=null && job.optString("work_id")!=id.toString())return Result.success() // Superseded retry; never re-run the same row from an older work node.
            if(job?.optBoolean("cancel_requested")==true){app.acquisition.execute(requestId);return Result.success()} // Drain a cancelled node without starting a foreground transfer or cancelling its successors.
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("yono-engine", "Unduh musik", NotificationManager.IMPORTANCE_LOW))
            val notification = NotificationCompat.Builder(app, "yono-engine").setSmallIcon(R.drawable.brand_y).setContentTitle("Memproses daftar musik")
                .setContentIntent(PendingIntent.getActivity(app, 4300, android.content.Intent(app, com.yonzmusic.app.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setContentText("Progres dan pembatalan tersedia di Unduh musik").setOngoing(true).build()
            setForeground(if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(4300, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(4300, notification))
            app.acquisition.execute(inputData.getString("request")!!); Result.success()
        } catch(e:kotlinx.coroutines.CancellationException){throw e} catch(e:Exception) { inputData.getString("request")?.let {app.acquisition.failQueued(it,"Pekerjaan belum dapat dijalankan. Coba lagi.")};app.acquisition.message.value=e.message?.take(200) ?: "Pekerjaan gagal dimulai";Result.failure() }
        finally { app.acquisition.busy.value = false }
    }
}
