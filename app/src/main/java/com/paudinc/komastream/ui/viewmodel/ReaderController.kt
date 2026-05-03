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
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.ProviderRegistry
import com.paudinc.komastream.utils.buildChapterPath
import com.paudinc.komastream.utils.resolveMalReadCountForReadChapters
import com.paudinc.komastream.utils.resolveMalReadCountFromProgressPointer
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
    private val offlineStore: OfflineChapterStore,
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
            uiState = uiState.copy(selectedDetail = null)
            navigationController.pushScreen(Screen.Detail(providerId, path))
            loadDetail(
                providerId = providerId,
                path = path,
                onSuccess = {},
                onError = {
                    navigationController.goBack()
                    onError(it)
                },
            )
        }
    }

    fun restoreDetail(
        providerId: String,
        path: String,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (uiState.selectedDetail?.let { it.providerId == providerId && it.detailPath == path } == true) return
        scope.launch {
            uiState = uiState.copy(selectedDetail = null)
            loadDetail(
                providerId = providerId,
                path = path,
                onSuccess = {},
                onError = onError,
            )
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
            loadReader(
                providerId = providerId,
                path = path,
                resumeProgress = resumeProgress,
                libraryState = libraryState,
                homeFeed = homeFeed,
                onLibraryChanged = onLibraryChanged,
                onSuccess = {
                    val next = Screen.Reader(providerId, path)
                    if (replace && navigationController.screen is Screen.Reader) {
                        navigationController.replaceTop(next)
                    } else {
                        navigationController.pushScreen(next)
                    }
                },
                onError = onError,
            )
            onLoadingChange(false)
        }
    }

    fun restoreReader(
        providerId: String,
        path: String,
        libraryState: LibraryState,
        homeFeed: HomeFeed?,
        onLibraryChanged: () -> Unit,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        val currentReader = uiState.readerData
        if (currentReader?.providerId == providerId && currentReader.chapterPath == path) return
        scope.launch {
            uiState = uiState.copy(isChapterLoading = true)
            onLoadingChange(true)
            loadReader(
                providerId = providerId,
                path = path,
                resumeProgress = true,
                libraryState = libraryState,
                homeFeed = homeFeed,
                onLibraryChanged = onLibraryChanged,
                onSuccess = {},
                onError = onError,
            )
            onLoadingChange(false)
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
        val lastReadChapterNumber = resolveMalReadCountFromProgressPointer(
            providerId = providerId,
            detailPath = detail.detailPath,
            chapters = detail.chapters,
            progressChapterPath = progressPath,
            readChapters = readChapters,
        ) ?: resolveMalReadCountForReadChapters(
            providerId = providerId,
            detailPath = detail.detailPath,
            chapters = detail.chapters,
            readChapters = readChapters,
        )
        libraryStore.upsertReading(
            SavedManga(
                providerId = providerId,
                title = detail.title,
                detailPath = detail.detailPath,
                coverUrl = detail.coverUrl,
                lastChapterTitle = strings.chapterLabelWithNumber(progressChapter),
                lastChapterPath = progressPath,
                lastReadChapterNumber = lastReadChapterNumber,
            )
        )
    }

    private suspend fun loadDetail(
        providerId: String,
        path: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val cachedDetail = withContext(Dispatchers.IO) {
            libraryStore.getCachedMangaDetail(providerId, path)
        }
        if (cachedDetail != null) {
            uiState = uiState.copy(selectedDetail = cachedDetail)
            onSuccess()
            scope.launch {
                refreshDetailCache(providerId, path, cachedDetail)
            }
            return
        }

        val provider = providerRegistry.get(providerId)
        runCatching { withContext(Dispatchers.IO) { provider.fetchMangaDetail(path) } }
            .onSuccess { detail ->
                withContext(Dispatchers.IO) {
                    libraryStore.cacheMangaDetail(detail)
                }
                uiState = uiState.copy(selectedDetail = detail)
                onSuccess()
            }
            .onFailure {
                Log.e("KomaStream", "Could not fetch manga detail", it)
                onError(it.message ?: "Could not open manga")
            }
    }

    private suspend fun loadReader(
        providerId: String,
        path: String,
        resumeProgress: Boolean,
        libraryState: LibraryState,
        homeFeed: HomeFeed?,
        onLibraryChanged: () -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val offlineReader = withContext(Dispatchers.IO) {
            offlineStore.loadChapter(providerId, path)
        }
        val readerResult = offlineReader ?: runCatching {
            val provider = providerRegistry.get(providerId)
            withContext(Dispatchers.IO) { provider.fetchReaderData(path) }
        }.getOrElse {
            uiState = uiState.copy(isChapterLoading = false)
            Log.e("KomaStream", "Could not open chapter $providerId:$path", it)
            onError(it.message ?: "Could not open chapter")
            return
        }

        val currentManga = readerActionInteractor.resolveCurrentManga(
            providerId = providerId,
            readerData = readerResult,
            reading = libraryState.reading,
            favorites = libraryState.favorites,
            selectedDetail = uiState.selectedDetail,
            homeFeed = homeFeed,
        )
        val resolvedDetailPath = readerActionInteractor.chooseCanonicalDetailPath(
            providerId = providerId,
            readerDetailPath = readerResult.mangaDetailPath,
            currentDetailPath = currentManga?.detailPath.orEmpty(),
        )
        val resolvedData = readerResult.copy(mangaDetailPath = resolvedDetailPath)

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
        onSuccess()
    }
}
