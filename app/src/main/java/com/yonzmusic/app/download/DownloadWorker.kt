package com.yonzmusic.app.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.yonzmusic.app.MainActivity
import com.yonzmusic.app.R
import com.yonzmusic.app.YonzApp
import com.yonzmusic.app.data.DownloadJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app get() = applicationContext as YonzApp
    private val batch get() = inputData.getString("batch").orEmpty()
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            while (!isStopped) {
                val job = app.repository.dao.nextTransfer() ?: return@withContext Result.success()
                setForeground(foreground(job.title, 0, job.expectedSize, job.batchId))
                DownloadEngine(app).run(job) { current ->
                    setForeground(foreground(current.title, current.received, current.total, current.batchId))
                }
            }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else {
                app.repository.dao.nextTransfer()?.let { pending -> app.repository.dao.jobs(listOf(pending.copy(status = "Failed", error = "Android could not run this transfer. Open Import and retry."))) }
                Result.failure()
            }
        }
    }
    private fun foreground(title: String, received: Long, total: Long, activeBatch: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("audio-downloads", "Unduhan musik", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 4, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancelIntent = Intent(applicationContext, DownloadCancelReceiver::class.java).setData(android.net.Uri.parse("yono-download://cancel/$activeBatch")).putExtra("batch", activeBatch)
        val cancel = PendingIntent.getBroadcast(applicationContext, activeBatch.hashCode(), cancelIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, "audio-downloads").setSmallIcon(R.drawable.brand_y)
            .setContentTitle("Mengunduh musik").setContentText(title.take(100)).setContentIntent(open).setOngoing(true)
            .setProgress(100, if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else 0, total <= 0)
            .addAction(0, "Batal", cancel).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(4100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(4100, notification)
    }
    companion object {
        suspend fun cancel(app: YonzApp, batch: String) {
            app.repository.dao.cancelBatch(batch)
            // The coordinator continues other batches. Cancelling a WorkManager chain would poison its dependents.
        }
        fun enqueue(context: Context, batch: String) {
            val work = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("batch" to batch))
                .addTag("music-download").addTag(batch).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS).build()
            // One transfer at a time across batches. Persistent work is recovered by WorkManager.
            WorkManager.getInstance(context).enqueueUniqueWork("music-downloads", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}

data class AcquisitionRow(val input: String, val status: String, val detail: String, val trackId: String = "", val candidate: AudioCandidate? = null)
