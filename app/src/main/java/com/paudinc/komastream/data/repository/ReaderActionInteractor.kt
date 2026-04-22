package com.paudinc.komastream.data.repository

import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.SavedManga

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
        )
    }

    fun chooseCanonicalDetailPath(
        providerId: String,
        readerDetailPath: String,
        currentDetailPath: String,
    ): String {
        return when {
            isBetterDetailPath(providerId, readerDetailPath, currentDetailPath) -> readerDetailPath
            currentDetailPath.isNotBlank() -> currentDetailPath
            else -> readerDetailPath
        }
    }

    private fun isBetterDetailPath(providerId: String, candidate: String, existing: String): Boolean {
        if (candidate.isBlank()) return false
        if (existing.isBlank()) return true
        return when (providerId) {
            "inmanga-es" -> candidate.count { it == '/' } > existing.count { it == '/' }
            else -> candidate.length >= existing.length
        }
    }

    private fun sameMangaPath(providerId: String, left: String, right: String): Boolean {
        if (left == right) return true
        if (left.isBlank() || right.isBlank()) return false
        return when (providerId) {
            "inmanga-es" -> inmangaKey(left) == inmangaKey(right)
            else -> false
        }
    }

    private fun inmangaKey(path: String): String {
        val parts = path.trim('/').split("/").filter { it.isNotBlank() }
        return parts.take(3).joinToString("/")
    }
}
