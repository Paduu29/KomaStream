package com.paudinc.komastream.data.repository

import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.utils.toProgressChapterNumber
import com.paudinc.komastream.utils.normalizeStoredPath
import com.paudinc.komastream.utils.sameMangaPath

class ReaderActionInteractor {
    fun resolveCurrentManga(
        providerId: String,
        readerData: ReaderData,
        reading: List<SavedManga>,
        favorites: List<SavedManga>,
        selectedDetail: MangaDetail?,
        homeFeed: HomeFeed?,
    ): SavedManga? {
        return reading.find { it.providerId == providerId && sameMangaPath(providerId, it.detailPath, readerData.mangaDetailPath) }
            ?: favorites.find { it.providerId == providerId && sameMangaPath(providerId, it.detailPath, readerData.mangaDetailPath) }
            ?: selectedDetail?.takeIf { it.providerId == providerId && sameMangaPath(providerId, it.detailPath, readerData.mangaDetailPath) }?.let {
                SavedManga(it.providerId, it.title, it.detailPath, it.coverUrl)
            }
            ?: homeFeed?.latestUpdates?.find { it.providerId == providerId && sameMangaPath(providerId, it.mangaPath, readerData.mangaDetailPath) }?.let {
                SavedManga(it.providerId, it.mangaTitle, it.mangaPath, it.coverUrl)
            }
            ?: homeFeed?.popularChapters?.find { it.providerId == providerId && sameMangaPath(providerId, it.mangaPath, readerData.mangaDetailPath) }?.let {
                SavedManga(it.providerId, it.mangaTitle, it.mangaPath, it.coverUrl)
            }
            ?: homeFeed?.chapterSections
                ?.asSequence()
                ?.flatMap { it.chapters.asSequence() }
                ?.firstOrNull { it.providerId == providerId && sameMangaPath(providerId, it.mangaPath, readerData.mangaDetailPath) }
                ?.let { SavedManga(it.providerId, it.mangaTitle, it.mangaPath, it.coverUrl) }
    }

    fun buildReadingEntry(
        providerId: String,
        readerData: ReaderData,
        currentManga: SavedManga?,
    ): SavedManga {
        val canonicalDetailPath = chooseCanonicalDetailPath(
            providerId = providerId,
            readerDetailPath = readerData.mangaDetailPath,
            currentDetailPath = currentManga?.detailPath.orEmpty(),
        )
        return SavedManga(
            providerId = providerId,
            title = currentManga?.title?.ifBlank { readerData.mangaTitle } ?: readerData.mangaTitle,
            detailPath = canonicalDetailPath,
            coverUrl = currentManga?.coverUrl ?: "",
            lastChapterTitle = readerData.chapterTitle,
            lastChapterPath = readerData.chapterPath,
            lastProgressChapterNumber = readerData.chapterTitle.toProgressChapterNumber()
                ?: readerData.chapterPath.toProgressChapterNumber(),
            lastReadChapterNumber = currentManga?.lastReadChapterNumber,
        )
    }

    fun chooseCanonicalDetailPath(
        providerId: String,
        readerDetailPath: String,
        currentDetailPath: String,
    ): String {
        return when {
            isBetterDetailPath(providerId, readerDetailPath, currentDetailPath) -> normalizeStoredPath(readerDetailPath)
            currentDetailPath.isNotBlank() -> normalizeStoredPath(currentDetailPath)
            else -> normalizeStoredPath(readerDetailPath)
        }
    }

    private fun isBetterDetailPath(providerId: String, candidate: String, existing: String): Boolean {
        if (candidate.isBlank()) return false
        if (existing.isBlank()) return true
        return when (providerId) {
            "inmanga-es" -> normalizeStoredPath(candidate).count { it == '/' } > normalizeStoredPath(existing).count { it == '/' }
            else -> normalizeStoredPath(candidate).length >= normalizeStoredPath(existing).length
        }
    }

}
