package com.paudinc.komastream.utils

import android.content.Context
import java.io.File
import kotlin.math.max

object AppCacheMaintenance {
    fun trimAll(context: Context) {
        trimMangaFirePageCache(context)
        trimReaderPreviewCache(context)
        trimUpdateCache(context)
    }

    fun trimMangaFirePageCache(context: Context) {
        trimDirectory(
            directory = File(context.cacheDir, MANGAFIRE_PAGE_CACHE_DIR),
            maxBytes = MAX_MANGAFIRE_PAGE_CACHE_BYTES,
            maxFileAgeMillis = MANGAFIRE_PAGE_CACHE_MAX_AGE_MILLIS,
        )
    }

    fun trimUpdateCache(context: Context) {
        trimDirectory(
            directory = File(context.cacheDir, UPDATE_CACHE_DIR),
            maxBytes = MAX_UPDATE_CACHE_BYTES,
            maxFileAgeMillis = UPDATE_CACHE_MAX_AGE_MILLIS,
        )
    }

    fun trimReaderPreviewCache(context: Context) {
        trimDirectory(
            directory = File(context.cacheDir, READER_PREVIEW_CACHE_DIR),
            maxBytes = MAX_READER_PREVIEW_CACHE_BYTES,
            maxFileAgeMillis = READER_PREVIEW_CACHE_MAX_AGE_MILLIS,
        )
    }

    private fun trimDirectory(
        directory: File,
        maxBytes: Long,
        maxFileAgeMillis: Long,
    ) {
        if (!directory.exists() || !directory.isDirectory) return
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        if (files.isEmpty()) return

        val now = System.currentTimeMillis()
        files.forEach { file ->
            if (now - file.lastModified() > maxFileAgeMillis) {
                file.delete()
            }
        }

        val remainingFiles = directory.listFiles()?.filter(File::isFile).orEmpty()
            .sortedBy { it.lastModified() }
        var totalBytes = remainingFiles.sumOf(File::length)
        if (totalBytes <= maxBytes) return

        val targetBytes = max(maxBytes - EVICTION_HEADROOM_BYTES, 0L)
        for (file in remainingFiles) {
            if (totalBytes <= targetBytes) break
            val fileSize = file.length()
            if (file.delete()) {
                totalBytes -= fileSize
            }
        }
    }

    private const val MANGAFIRE_PAGE_CACHE_DIR = "mangafire-pages"
    private const val READER_PREVIEW_CACHE_DIR = "offline_reader_preview_pages"
    private const val UPDATE_CACHE_DIR = "updates"
    private const val EVICTION_HEADROOM_BYTES = 8L * 1024L * 1024L
    private const val MAX_MANGAFIRE_PAGE_CACHE_BYTES = 96L * 1024L * 1024L
    private const val MAX_READER_PREVIEW_CACHE_BYTES = 256L * 1024L * 1024L
    private const val MAX_UPDATE_CACHE_BYTES = 24L * 1024L * 1024L
    private const val MANGAFIRE_PAGE_CACHE_MAX_AGE_MILLIS = 2L * 24L * 60L * 60L * 1000L
    private const val READER_PREVIEW_CACHE_MAX_AGE_MILLIS = 3L * 24L * 60L * 60L * 1000L
    private const val UPDATE_CACHE_MAX_AGE_MILLIS = 7L * 24L * 60L * 60L * 1000L
}
