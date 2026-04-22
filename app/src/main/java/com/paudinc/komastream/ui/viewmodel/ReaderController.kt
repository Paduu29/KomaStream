package com.paudinc.komastream.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.repository.ReaderActionInteractor
import com.paudinc.komastream.ui.navigation.Screen
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.ProviderRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderController(
    private val scope: CoroutineScope,
    private val providerRegistry: ProviderRegistry,
    private val libraryStore: LibraryStore,
    private val readerActionInteractor: ReaderActionInteractor,
) {
    var uiState by mutableStateOf(ReaderUiState())
        private set

    fun openDetail(
        providerId: String,
        path: String,
        navigationController: NavigationController,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            onLoadingChange(true)
            val provider = providerRegistry.get(providerId)
            runCatching { withContext(Dispatchers.IO) { provider.fetchMangaDetail(path) } }
                .onSuccess {
                    uiState = uiState.copy(selectedDetail = it)
                    navigationController.pushScreen(Screen.Detail(providerId, path))
                }
                .onFailure {
                    Log.e("KomaStream", "Could not fetch manga detail", it)
                    onError(it.message ?: "Could not open manga")
                }
                .also { onLoadingChange(false) }
        }
    }

    fun openReader(
        providerId: String,
        path: String,
        replace: Boolean,
        navigationController: NavigationController,
        libraryState: LibraryState,
        homeFeed: HomeFeed?,
        onLibraryChanged: () -> Unit,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            onLoadingChange(true)
            val provider = providerRegistry.get(providerId)
            runCatching { withContext(Dispatchers.IO) { provider.fetchReaderData(path) } }
                .onSuccess { data ->
                    val currentManga = readerActionInteractor.resolveCurrentManga(
                        providerId = providerId,
                        readerData = data,
                        reading = libraryState.reading,
                        favorites = libraryState.favorites,
                        selectedDetail = uiState.selectedDetail,
                        homeFeed = homeFeed,
                    )
                    val resolvedDetailPath = readerActionInteractor.chooseCanonicalDetailPath(
                        providerId = providerId,
                        readerDetailPath = data.mangaDetailPath,
                        currentDetailPath = currentManga?.detailPath.orEmpty(),
                    )
                    val resolvedData = data.copy(mangaDetailPath = resolvedDetailPath)
                    val initialPageIndex = libraryStore.getChapterProgress(providerId, path)
                    uiState = uiState.copy(
                        readerData = resolvedData,
                        initialPageIndex = initialPageIndex,
                        currentPageIndex = initialPageIndex,
                    )
                    libraryStore.upsertReading(
                        readerActionInteractor.buildReadingEntry(
                            providerId = providerId,
                            readerData = resolvedData,
                            currentManga = currentManga,
                        )
                    )
                    onLibraryChanged()
                    val next = Screen.Reader(providerId, path)
                    if (replace && navigationController.screen is Screen.Reader) {
                        navigationController.replaceTop(next)
                    } else {
                        navigationController.pushScreen(next)
                    }
                }
                .onFailure {
                    Log.e("KomaStream", "Could not open chapter $providerId:$path", it)
                    onError(it.message ?: "Could not open chapter")
                }
                .also { onLoadingChange(false) }
        }
    }

    fun updateChapterReadState(providerId: String, chapterPath: String, read: Boolean) {
        libraryStore.setChaptersRead(providerId, listOf(chapterPath), read)
    }

    fun updatePageProgress(
        providerId: String,
        path: String,
        index: Int,
        onChapterMarkedRead: () -> Unit,
    ) {
        uiState = uiState.copy(currentPageIndex = index.coerceAtLeast(0))
        libraryStore.saveChapterProgress(providerId, path, index)
        val totalPages = uiState.readerData
            ?.takeIf { it.providerId == providerId && it.chapterPath == path }
            ?.pages
            ?.size
            ?: 0
        if (totalPages > 0 && index >= totalPages - 1 && !libraryStore.isChapterRead(providerId, path)) {
            libraryStore.markChapterRead(providerId, path)
            onChapterMarkedRead()
        }
    }
}
