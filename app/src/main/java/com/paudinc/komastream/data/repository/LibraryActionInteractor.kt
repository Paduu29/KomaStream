package com.paudinc.komastream.data.repository

import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.SavedManga

class LibraryActionInteractor {
    fun buildFavoriteCandidate(
        libraryState: LibraryState,
        manga: SavedManga,
    ): SavedManga {
        val existing = libraryState.reading.find { it.providerId == manga.providerId && it.detailPath == manga.detailPath }
            ?: libraryState.favorites.find { it.providerId == manga.providerId && it.detailPath == manga.detailPath }
        return manga.copy(
            lastChapterTitle = manga.lastChapterTitle.ifBlank { existing?.lastChapterTitle.orEmpty() },
            lastChapterPath = manga.lastChapterPath.ifBlank { existing?.lastChapterPath.orEmpty() },
        )
    }
}
