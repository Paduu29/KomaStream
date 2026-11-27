package com.paudinc.komastream.utils

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.paudinc.komastream.R
import com.paudinc.komastream.KomaStreamApp
import com.paudinc.komastream.provider.MangaProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

class BatchDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val batchFile = inputData.getString(KEY_BATCH_FILE)
            ?.takeIf { it.isNotBlank() }?.let(::File) ?: return Result.failure()
        var total = 0
        var successCount = 0

        try {
            val providerId = inputData.getString(KEY_PROVIDER_ID)
                ?.takeIf { it.isNotBlank() } ?: return Result.failure()
            val mangaTitle = inputData.getString(KEY_MANGA_TITLE).orEmpty()
            val chapterPaths = try {
                batchFile.readLines().filter { it.isNotBlank() }
            } catch (e: IOException) {
                return Result.failure()
            }
            if (chapterPaths.isEmpty()) return Result.success()

            total = chapterPaths.size
            val appGraph = (applicationContext as KomaStreamApp).appGraph
            val provider = appGraph.providerRegistry.get(providerId)
            val offlineStore = appGraph.offlineStore

            createChannel()
            setForeground(
                createForegroundInfo(
                    title = mangaTitle.ifBlank { applicationContext.getString(R.string.downloading_chapters) },
                    chapterIndex = 0,
                    totalChapters = total,
                    chapterProgress = 0,
                )
            )

            for ((index, chapterPath) in chapterPaths.withIndex()) {
                ensureRunning()
                try {
                    downloadChapter(provider, offlineStore, providerId, chapterPath, index + 1, total)
                    successCount++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ensureRunning()
                }
            }

            ensureRunning()
            val success = successCount > 0
            postCompletionNotification(
                title = applicationContext.getString(if (success) R.string.download_complete else R.string.download_failed),
                message = applicationContext.getString(R.string.batch_download_complete_message, successCount, total),
                success = success,
            )
            return if (success) Result.success() else Result.failure()
        } catch (e: CancellationException) {
            if (cancelledByApp()) {
                postCompletionNotification(
                    title = applicationContext.getString(R.string.download_all_cancelled),
                    message = applicationContext.getString(R.string.batch_download_cancelled_message, successCount, total),
                    success = false,
                )
            }
            throw e
        } finally {
            if (!isStopped || cancelledByApp()) batchFile.delete()
        }
    }

    private suspend fun downloadChapter(
        provider: MangaProvider,
        offlineStore: OfflineChapterStore,
        providerId: String,
        chapterPath: String,
        chapterIndex: Int,
        total: Int,
    ) {
        val reader = provider.fetchReaderData(chapterPath)
        val totalPages = reader.pages.size.coerceAtLeast(1)
        val startedAt = SystemClock.elapsedRealtime()
        var downloadedBytes = 0L
        val session = offlineStore.openChapterWriteSession(reader)

        try {
            reader.pages.forEachIndexed { index, page ->
                ensureRunning()
                val pageBytes = provider.downloadBytes(page.imageUrl, referer = chapterPath)
                downloadedBytes += pageBytes.size.toLong()
                session.writePage(index, page, pageBytes)
                val chapterProgress = (((index + 1) * 100f) / totalPages).toInt().coerceIn(0, 100)
                val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                val bytesPerSecond = downloadedBytes * 1000.0 / elapsedMs.toDouble()

                setProgress(
                    batchProgressData(
                        providerId = providerId,
                        chapterPath = chapterPath,
                        chapterProgress = chapterProgress,
                        batchCurrent = chapterIndex,
                        batchTotal = total,
                    )
                )
                setForeground(
                    createForegroundInfo(
                        title = reader.chapterTitle.ifBlank { applicationContext.getString(R.string.downloading) },
                        chapterIndex = chapterIndex,
                        totalChapters = total,
                        chapterProgress = chapterProgress,
                        chapterTitle = reader.chapterTitle,
                        speed = bytesPerSecond,
                    )
                )
            }

            ensureRunning()
            session.commit()
        } catch (e: Throwable) {
            session.abort()
            throw e
        }
    }

    private fun createForegroundInfo(
        title: String,
        chapterIndex: Int,
        totalChapters: Int,
        chapterProgress: Int,
        chapterTitle: String = "",
        speed: Double = 0.0,
    ): ForegroundInfo {
        val isComplete = chapterProgress >= 100 && chapterIndex >= totalChapters
        val statusText = applicationContext.getString(
            R.string.batch_download_notification,
            chapterIndex,
            totalChapters,
            chapterProgress,
        )
        val contentText = if (chapterTitle.isNotBlank()) {
            "$chapterTitle — $statusText"
        } else {
            statusText
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(
                if (isComplete) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_sys_download
            )
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOnlyAlertOnce(true)
            .setOngoing(!isComplete)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, chapterProgress, false)
            .addAction(
                0,
                applicationContext.getString(R.string.cancel),
                createCancelPendingIntent(),
            )
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "KomaStream batch downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Batch chapter download progress"
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
                .notify(COMPLETION_NOTIFICATION_ID, notification)
        }
    }

    private fun createCancelPendingIntent(): PendingIntent =
        WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

    private fun ensureRunning() {
        if (isStopped) throw CancellationException("Worker stopped")
    }

    // WorkManager records app cancellation on all API levels and stores the reason in memory.
    @SuppressLint("NewApi")
    private fun cancelledByApp() = stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

    companion object {
        const val TAG = "BatchDownloadWorker"
        const val KEY_BATCH_FILE = "batch_file"
        const val KEY_PROVIDER_ID = "provider_id"
        const val KEY_MANGA_TITLE = "manga_title"
        const val KEY_CHAPTER_PATH = "chapter_path"
        const val KEY_PROGRESS = "progress"
        const val KEY_BATCH_CURRENT = "batch_current"
        const val KEY_BATCH_TOTAL = "batch_total"
        private const val CHANNEL_ID = "batch_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 8000
        private const val COMPLETION_NOTIFICATION_ID = 18000

        fun batchProgressData(
            providerId: String,
            chapterPath: String,
            chapterProgress: Int,
            batchCurrent: Int,
            batchTotal: Int,
        ): Data {
            return Data.Builder()
                .putString(KEY_PROVIDER_ID, providerId)
                .putString(KEY_CHAPTER_PATH, chapterPath)
                .putInt(KEY_PROGRESS, chapterProgress)
                .putInt(KEY_BATCH_CURRENT, batchCurrent)
                .putInt(KEY_BATCH_TOTAL, batchTotal)
                .build()
        }
    }
}
