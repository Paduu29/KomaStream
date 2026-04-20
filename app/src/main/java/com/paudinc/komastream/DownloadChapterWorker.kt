package com.paudinc.komastream

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.paudinc.komastream.R

class DownloadChapterWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val providerId = inputData.getString(KEY_PROVIDER_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val chapterPath = inputData.getString(KEY_CHAPTER_PATH)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val provider = createDefaultProviderRegistry(applicationContext).get(providerId)
        val offlineStore = OfflineChapterStore(applicationContext)

        return runCatching {
            createChannel()
            setProgress(progressData(providerId, chapterPath, 0))
            setForeground(createForegroundInfo(0, applicationContext.getString(R.string.downloading)))

            val reader = provider.fetchReaderData(chapterPath)
            val total = reader.pages.size.coerceAtLeast(1)
            val pageBytes = buildList(reader.pages.size) {
                reader.pages.forEachIndexed { index, page ->
                    ensureStopped()
                    add(provider.downloadBytes(page.imageUrl, referer = chapterPath))
                    val progress = (((index + 1) * 100f) / total).toInt().coerceIn(0, 100)
                    setProgress(progressData(providerId, chapterPath, progress))
                    setForeground(createForegroundInfo(progress, reader.chapterTitle.ifBlank { applicationContext.getString(R.string.downloading) }))
                }
            }

            ensureStopped()
            offlineStore.saveChapter(reader, pageBytes)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { if (isStopped) Result.failure() else Result.retry() }
        )
    }

    private fun createForegroundInfo(progress: Int, title: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText("${applicationContext.getString(R.string.downloading)} $progress%")
            .setOnlyAlertOnce(true)
            .setOngoing(progress in 0..99)
            .setProgress(100, progress, false)
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

    private fun ensureStopped() {
        if (isStopped) error("Worker stopped")
    }

    companion object {
        const val KEY_PROVIDER_ID = "provider_id"
        const val KEY_CHAPTER_PATH = "chapter_path"
        const val KEY_PROGRESS = "progress"
        private const val CHANNEL_ID = "chapter_downloads"
        private const val NOTIFICATION_ID_BASE = 7000

        fun progressData(providerId: String, chapterPath: String, progress: Int): Data {
            return Data.Builder()
                .putString(KEY_PROVIDER_ID, providerId)
                .putString(KEY_CHAPTER_PATH, chapterPath)
                .putInt(KEY_PROGRESS, progress)
                .build()
        }
    }
}
