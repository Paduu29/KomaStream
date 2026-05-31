package com.paudinc.komastream.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.paudinc.komastream.R
import com.paudinc.komastream.KomaStreamApp
import java.util.Locale

class DownloadChapterWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val providerId = inputData.getString(KEY_PROVIDER_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val chapterPath = inputData.getString(KEY_CHAPTER_PATH)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val appGraph = (applicationContext as KomaStreamApp).appGraph
        val provider = appGraph.providerRegistry.get(providerId)
        val offlineStore = appGraph.offlineStore

        return runCatching {
            createChannel()
            setProgress(progressData(providerId, chapterPath, 0))
            setForeground(
                createForegroundInfo(
                    title = applicationContext.getString(R.string.downloading),
                    progress = 0,
                    downloadedPages = 0,
                    totalPages = 0,
                    bytesPerSecond = 0.0,
                )
            )

            val reader = provider.fetchReaderData(chapterPath)
            val total = reader.pages.size.coerceAtLeast(1)
            val startedAt = SystemClock.elapsedRealtime()
            var downloadedBytes = 0L
            val session = offlineStore.openChapterWriteSession(reader)
            try {
                reader.pages.forEachIndexed { index, page ->
                    ensureStopped()
                    val pageBytes = provider.downloadBytes(page.imageUrl, referer = chapterPath)
                    downloadedBytes += pageBytes.size.toLong()
                    session.writePage(index, page, pageBytes)
                    val downloadedPages = index + 1
                    val progress = (((index + 1) * 100f) / total).toInt().coerceIn(0, 100)
                    val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                    val bytesPerSecond = downloadedBytes * 1000.0 / elapsedMs.toDouble()
                    setProgress(progressData(providerId, chapterPath, progress))
                    setForeground(
                        createForegroundInfo(
                            title = reader.chapterTitle.ifBlank { applicationContext.getString(R.string.downloading) },
                            progress = progress,
                            downloadedPages = downloadedPages,
                            totalPages = total,
                            bytesPerSecond = bytesPerSecond,
                        )
                    )
                }

                ensureStopped()
                session.commit()
            } catch (error: Throwable) {
                session.abort()
                throw error
            }
            postCompletionNotification(
                title = reader.chapterTitle.ifBlank { applicationContext.getString(R.string.downloading) },
                message = applicationContext.getString(R.string.chapter_downloaded),
                success = true,
            )
            Result.success(
                Data.Builder()
                    .putString(KEY_PROVIDER_ID, providerId)
                    .putString(KEY_CHAPTER_PATH, chapterPath)
                    .putInt(KEY_PROGRESS, 100)
                    .build()
            )
        }.fold(
            onSuccess = { it },
            onFailure = {
                if (isStopped) {
                    postCompletionNotification(
                        title = applicationContext.getString(R.string.downloading),
                        message = applicationContext.getString(R.string.download_cancelled),
                        success = false,
                    )
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        )
    }

    private fun createForegroundInfo(
        title: String,
        progress: Int,
        downloadedPages: Int,
        totalPages: Int,
        bytesPerSecond: Double,
    ): ForegroundInfo {
        val isComplete = progress >= 100
        val pagesLeft = (totalPages - downloadedPages).coerceAtLeast(0)
        val statusText = applicationContext.getString(
            R.string.download_notification_status,
            progress,
            formatSpeed(bytesPerSecond),
        )
        val pageText = if (totalPages > 0) {
            applicationContext.getString(
                R.string.download_notification_pages,
                downloadedPages,
                totalPages,
                pagesLeft,
            )
        } else {
            applicationContext.getString(R.string.downloading)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(
                if (isComplete) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_sys_download
            )
            .setContentTitle(title)
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$statusText\n$pageText"))
            .setOnlyAlertOnce(true)
            .setOngoing(progress in 0..99)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, progress, false)
            .addAction(
                0,
                applicationContext.getString(R.string.cancel),
                createCancelPendingIntent(),
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID_BASE + id.hashCode(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID_BASE + id.hashCode(), notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "KomaStream downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Chapter download progress"
        }
        manager.createNotificationChannel(channel)
    }

    private fun postCompletionNotification(title: String, message: String, success: Boolean) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(applicationContext)
                .notify(COMPLETION_NOTIFICATION_ID_BASE + id.hashCode(), notification)
        }
    }

    private fun createCancelPendingIntent(): PendingIntent =
        WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

    private fun formatSpeed(bytesPerSecond: Double): String {
        if (bytesPerSecond <= 0.0) return "0 KB/s"
        val kib = bytesPerSecond / 1024.0
        if (kib < 1024.0) {
            return String.format(Locale.US, "%.1f KB/s", kib)
        }
        return String.format(Locale.US, "%.2f MB/s", kib / 1024.0)
    }

    private fun ensureStopped() {
        if (isStopped) error("Worker stopped")
    }

    companion object {
        const val TAG = "DownloadChapterWorker"
        const val KEY_PROVIDER_ID = "provider_id"
        const val KEY_CHAPTER_PATH = "chapter_path"
        const val KEY_PROGRESS = "progress"
        private const val CHANNEL_ID = "chapter_downloads"
        private const val NOTIFICATION_ID_BASE = 7000
        private const val COMPLETION_NOTIFICATION_ID_BASE = 17000

        fun progressData(providerId: String, chapterPath: String, progress: Int): Data {
            return Data.Builder()
                .putString(KEY_PROVIDER_ID, providerId)
                .putString(KEY_CHAPTER_PATH, chapterPath)
                .putInt(KEY_PROGRESS, progress)
                .build()
        }
    }
}
