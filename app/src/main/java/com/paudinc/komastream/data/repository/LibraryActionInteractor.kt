package com.paudinc.komastream.data.repository

import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.data.model.FavoriteMangaStatus
import com.paudinc.komastream.utils.sameMangaPath

class LibraryActionInteractor {
    fun buildFavoriteCandidate(
        libraryState: LibraryState,
        manga: SavedManga,
    ): SavedManga {
        val existingFavorite = libraryState.favorites.find { it.providerId == manga.providerId && sameMangaPath(manga.providerId, it.detailPath, manga.detailPath) }
        val existingReading = libraryState.reading.find { it.providerId == manga.providerId && sameMangaPath(manga.providerId, it.detailPath, manga.detailPath) }
        return manga.copy(
            favoriteStatus = existingFavorite?.favoriteStatus
                ?: existingReading?.favoriteStatus
                ?: manga.favoriteStatus,
            lastChapterTitle = manga.lastChapterTitle.ifBlank { existingFavorite?.lastChapterTitle ?: existingReading?.lastChapterTitle.orEmpty() },
            lastChapterPath = manga.lastChapterPath.ifBlank { existingFavorite?.lastChapterPath ?: existingReading?.lastChapterPath.orEmpty() },
            lastProgressChapterNumber = manga.lastProgressChapterNumber ?: existingFavorite?.lastProgressChapterNumber ?: existingReading?.lastProgressChapterNumber,
            lastReadChapterNumber = manga.lastReadChapterNumber ?: existingFavorite?.lastReadChapterNumber ?: existingReading?.lastReadChapterNumber,
        )
    }
}
