package com.paudinc.komastream.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.data.repository.ReaderActionInteractor
import com.paudinc.komastream.ui.navigation.Screen
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.ProviderRegistry
import com.paudinc.komastream.utils.buildChapterPath
import com.paudinc.komastream.utils.resolveProgressChapterPath
import com.paudinc.komastream.utils.resolveReadThroughChapterPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderController(
    private val scope: CoroutineScope,
    private val providerRegistry: ProviderRegistry,
    private val libraryStore: LibraryStore,
    private val readerActionInteractor: ReaderActionInteractor,
    private val strings: AppStrings,
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
            val cachedDetail = withContext(Dispatchers.IO) {
                libraryStore.getCachedMangaDetail(providerId, path)
            }
            if (cachedDetail != null) {
                uiState = uiState.copy(selectedDetail = cachedDetail)
                navigationController.pushScreen(Screen.Detail(providerId, path))
                onLoadingChange(false)
                launch {
                    refreshDetailCache(providerId, path, cachedDetail)
                }
                return@launch
            }

            val provider = providerRegistry.get(providerId)
            runCatching { withContext(Dispatchers.IO) { provider.fetchMangaDetail(path) } }
                .onSuccess { detail ->
                    withContext(Dispatchers.IO) {
                        libraryStore.cacheMangaDetail(detail)
                    }
                    uiState = uiState.copy(selectedDetail = detail)
                    navigationController.pushScreen(Screen.Detail(providerId, path))
                }
                .onFailure {
                    Log.e("KomaStream", "Could not fetch manga detail", it)
                    onError(it.message ?: "Could not open manga")
                }
                .also { onLoadingChange(false) }
        }
    }

    private suspend fun refreshDetailCache(
        providerId: String,
        path: String,
        cachedDetail: MangaDetail,
    ) {
        val provider = providerRegistry.get(providerId)
        runCatching { withContext(Dispatchers.IO) { provider.fetchMangaDetail(path) } }
            .onSuccess { detail ->
                val changed = withContext(Dispatchers.IO) {
                    libraryStore.cacheMangaDetail(detail)
                }
                if (changed || uiState.selectedDetail == null || uiState.selectedDetail?.detailPath == cachedDetail.detailPath) {
                    uiState = uiState.copy(selectedDetail = detail)
                }
            }
            .onFailure {
                Log.d("KomaStream", "Could not refresh cached manga detail", it)
            }
    }

    fun openReader(
        providerId: String,
        path: String,
        resumeProgress: Boolean,
        replace: Boolean,
        navigationController: NavigationController,
        libraryState: LibraryState,
        homeFeed: HomeFeed?,
        onLibraryChanged: () -> Unit,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            uiState = uiState.copy(isChapterLoading = true)
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
                    val initialPageIndex = if (resumeProgress) {
                        libraryStore.getChapterProgress(providerId, path)
                    } else {
                        0
                    }
                    uiState = uiState.copy(
                        readerData = resolvedData,
                        initialPageIndex = initialPageIndex,
                        currentPageIndex = initialPageIndex,
                        isChapterLoading = false,
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
            val detail = uiState.selectedDetail?.takeIf {
                it.providerId == providerId && it.detailPath == uiState.readerData?.mangaDetailPath
            }
            val chaptersToMark = detail?.let {
                resolveReadThroughChapterPaths(
                    providerId = providerId,
                    detailPath = it.detailPath,
                    chapters = it.chapters,
                    currentChapterPath = path,
                )
            } ?: listOf(path)
            libraryStore.setChaptersRead(providerId, chaptersToMark, true)
            detail?.let { syncReadingSnapshot(providerId, it) }
            onChapterMarkedRead()
        }
    }

    private fun syncReadingSnapshot(
        providerId: String,
        detail: com.paudinc.komastream.data.model.MangaDetail,
    ) {
        val readChapters = libraryStore.readChaptersForProvider(providerId)
        val progressPath = resolveProgressChapterPath(
            providerId = providerId,
            detailPath = detail.detailPath,
            chapters = detail.chapters,
            readChapters = readChapters,
        ) ?: return
        val progressChapter = detail.chapters.firstOrNull { chapter ->
            buildChapterPath(detail.detailPath, chapter) == progressPath
        } ?: return
        libraryStore.upsertReading(
            SavedManga(
                providerId = providerId,
                title = detail.title,
                detailPath = detail.detailPath,
                coverUrl = detail.coverUrl,
                lastChapterTitle = strings.chapterLabelWithNumber(progressChapter),
                lastChapterPath = progressPath,
            )
        )
    }
}
