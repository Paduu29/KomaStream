package com.paudinc.komastream.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.paudinc.komastream.KomaStreamApp

class ReaderPagePrefetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val providerId = inputData.getString(KEY_PROVIDER_ID)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val chapterPath = inputData.getString(KEY_CHAPTER_PATH)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val startIndex = inputData.getInt(KEY_START_INDEX, DEFAULT_START_INDEX).coerceAtLeast(0)
        val appGraph = (applicationContext as KomaStreamApp).appGraph
        val provider = appGraph.providerRegistry.get(providerId)
        val offlineStore = appGraph.offlineStore

        return runCatching {
            val reader = provider.fetchReaderData(chapterPath)
            reader.pages.drop(startIndex).forEach { page ->
                ensureActive()
                if (offlineStore.getPreviewPageFile(providerId, chapterPath, page) != null) return@forEach
                val pageBytes = provider.downloadBytes(page.imageUrl, referer = chapterPath)
                offlineStore.cachePreviewPage(providerId, chapterPath, page, pageBytes)
            }
            Result.success(
                Data.Builder()
                    .putString(KEY_PROVIDER_ID, providerId)
                    .putString(KEY_CHAPTER_PATH, chapterPath)
                    .putInt(KEY_START_INDEX, startIndex)
                    .build()
            )
        }.getOrElse { error ->
            if (isStopped) Result.failure() else Result.retry()
        }
    }

    private fun ensureActive() {
        if (isStopped) error("Worker stopped")
    }

    companion object {
        const val TAG = "ReaderPagePrefetchWorker"
        const val KEY_PROVIDER_ID = "provider_id"
        const val KEY_CHAPTER_PATH = "chapter_path"
        const val KEY_START_INDEX = "start_index"
        private const val DEFAULT_START_INDEX = 3
    }
}
