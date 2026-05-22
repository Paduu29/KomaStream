package com.paudinc.komastream.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paudinc.komastream.data.model.CommunityPage
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.data.repository.ReaderActionInteractor
import com.paudinc.komastream.ui.navigation.Screen
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.CachedMangaDetailSnapshot
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.ProviderRegistry
import com.paudinc.komastream.provider.providers.ManhwaLatinoProvider
import com.paudinc.komastream.provider.providers.MangaBallProvider
import com.paudinc.komastream.utils.buildChapterPath
import com.paudinc.komastream.utils.canonicalChapterKey
import com.paudinc.komastream.utils.chapterPathProgressNumber
import com.paudinc.komastream.utils.resolveMalReadCountForReadChapters
import com.paudinc.komastream.utils.resolveReadThroughChapterPaths
import com.paudinc.komastream.utils.toProgressChapterNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

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

    private val readerDataCache = ConcurrentHashMap<String, CachedReaderData>()

    fun openDetail(
        providerId: String,
        path: String,
        navigationController: NavigationController,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            val resolvedPath = resolvePreferredDetailPath(providerId, path)
            uiState = uiState.copy(
                selectedDetail = null,
                requestedDetailPath = resolvedPath,
                isChapterLoading = false,
            )
            navigationController.pushScreen(Screen.Detail(providerId, resolvedPath))
            loadDetail(
                providerId = providerId,
                path = resolvedPath,
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
        navigationController: NavigationController,
        screen: Screen.Detail,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        val resolvedPath = resolvePreferredDetailPath(providerId, path)
        if (screen.detailPath != resolvedPath) {
            navigationController.replaceTop(screen.copy(detailPath = resolvedPath))
        }
        if (uiState.selectedDetail?.let { it.providerId == providerId && it.detailPath == resolvedPath } == true) return
        if (uiState.requestedDetailPath == resolvedPath && uiState.selectedDetail?.detailPath != resolvedPath) return
        scope.launch {
            uiState = uiState.copy(
                selectedDetail = null,
                requestedDetailPath = resolvedPath,
                isChapterLoading = false,
            )
            loadDetail(
                providerId = providerId,
                path = resolvedPath,
                onSuccess = {},
                onError = onError,
            )
        }
    }

    fun openCommunity(
        providerId: String,
        path: String,
        navigationController: NavigationController,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            val resolvedPath = resolvePreferredCommunityPath(path)
            uiState = uiState.copy(
                selectedCommunityPage = null,
                requestedCommunityPath = resolvedPath,
                isCommunityLoading = true,
            )
            onLoadingChange(true)
            navigationController.pushScreen(Screen.Community(providerId, resolvedPath))
            loadCommunity(
                providerId = providerId,
                path = resolvedPath,
                onSuccess = { onLoadingChange(false) },
                onError = {
                    onLoadingChange(false)
                    navigationController.goBack()
                    onError(it)
                },
            )
        }
    }

    fun restoreCommunity(
        providerId: String,
        path: String,
        navigationController: NavigationController,
        screen: Screen.Community,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        val resolvedPath = resolvePreferredCommunityPath(path)
        if (screen.communityPath != resolvedPath) {
            navigationController.replaceTop(screen.copy(communityPath = resolvedPath))
        }
        if (uiState.selectedCommunityPage?.providerId == providerId && uiState.requestedCommunityPath == resolvedPath) return
        if (uiState.requestedCommunityPath == resolvedPath && uiState.selectedCommunityPage?.providerId == providerId) return
        scope.launch {
            uiState = uiState.copy(
                selectedCommunityPage = null,
                requestedCommunityPath = resolvedPath,
                isCommunityLoading = true,
            )
            onLoadingChange(true)
            loadCommunity(
                providerId = providerId,
                path = resolvedPath,
                onSuccess = { onLoadingChange(false) },
                onError = {
                    onLoadingChange(false)
                    onError(it)
                },
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
                val currentSourceId = uiState.selectedDetail
                    ?.takeIf { it.providerId == providerId && it.detailPath == path }
                    ?.selectedChapterSourceId
                val resolvedDetail = detail.withSelectedChapterSource(
                    preferredSourceId = preferredChapterSourceId(providerId),
                    currentSourceId = currentSourceId,
                )
                val changed = withContext(Dispatchers.IO) {
                    libraryStore.cacheMangaDetail(resolvedDetail)
                }
                if (changed || uiState.selectedDetail == null || uiState.selectedDetail?.detailPath == cachedDetail.detailPath) {
                    uiState = uiState.copy(selectedDetail = resolvedDetail)
                }
            }
            .onFailure {
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

    fun selectChapterSource(
        providerId: String,
        detailPath: String,
        sourceId: String,
    ) {
        val selectedDetail = uiState.selectedDetail ?: return
        if (selectedDetail.providerId != providerId || selectedDetail.detailPath != detailPath) return
        if (selectedDetail.selectedChapterSourceId == sourceId) return
        if (selectedDetail.chapterSources.isNotEmpty() && selectedDetail.chapterSources.none { it.id == sourceId }) return
        uiState = uiState.copy(
            selectedDetail = selectedDetail.copy(selectedChapterSourceId = sourceId),
        )
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
        allowAutoReadMark: Boolean,
        onChapterMarkedRead: () -> Unit,
    ) {
        uiState = uiState.copy(currentPageIndex = index.coerceAtLeast(0))
        libraryStore.saveChapterProgress(providerId, path, index)
        val totalPages = uiState.readerData
            ?.takeIf { it.providerId == providerId && it.chapterPath == path }
            ?.pages
            ?.size
            ?: 0
        if (allowAutoReadMark && totalPages > 0 && index >= totalPages - 1 && !libraryStore.isChapterRead(providerId, path)) {
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
            detail?.let { syncReadingSnapshot(providerId, it, path, uiState.readerData?.chapterTitle.orEmpty()) }
            onChapterMarkedRead()
        }
    }

    fun syncReadingSnapshot(
        providerId: String,
        detail: com.paudinc.komastream.data.model.MangaDetail,
        chapterPath: String,
        chapterTitle: String,
    ) {
        val readChapters = libraryStore.readChaptersForProvider(providerId)
        val progressChapterPath = detail.chapters.firstOrNull { chapter ->
            canonicalChapterKey(providerId, buildChapterPath(detail.detailPath, chapter)) ==
                canonicalChapterKey(providerId, chapterPath)
        }?.let { buildChapterPath(detail.detailPath, it) } ?: return
        val progressChapter = detail.chapters.firstOrNull { chapter ->
            buildChapterPath(detail.detailPath, chapter) == progressChapterPath
        } ?: return
        val lastReadChapterNumber = resolveMalReadCountForReadChapters(
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
                lastChapterPath = progressChapterPath,
                lastProgressChapterNumber = progressChapter.chapterNumberUrl.toProgressChapterNumber()
                    ?: progressChapter.chapterLabel.toProgressChapterNumber()
                    ?: chapterPathProgressNumber(providerId, progressChapterPath)
                    ?: chapterTitle.toProgressChapterNumber(),
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
        val detailPath = resolvePreferredDetailPath(providerId, path)
        val preferredSourceId = preferredChapterSourceId(providerId)
        val currentSourceId = uiState.selectedDetail
            ?.takeIf { it.providerId == providerId && it.detailPath == detailPath }
            ?.selectedChapterSourceId
        uiState = uiState.copy(
            requestedDetailPath = detailPath,
            isChapterLoading = false,
        )
        val cachedSnapshot = withContext(Dispatchers.IO) {
            libraryStore.getCachedMangaDetailSnapshot(providerId, detailPath)
        }
        if (cachedSnapshot != null) {
            if (providerId == ManhwaLatinoProvider.PROVIDER_ID && cachedSnapshot.detail.chapters.isEmpty()) {
                refreshDetailCache(providerId, detailPath, cachedSnapshot.detail)
                return
            }
            val detail = cachedSnapshot.detail.withSelectedChapterSource(
                preferredSourceId = preferredSourceId,
                currentSourceId = currentSourceId,
            )
            uiState = uiState.copy(
                selectedDetail = detail,
                requestedDetailPath = detailPath,
            )
            onSuccess()
            if (isDetailSnapshotStale(cachedSnapshot)) {
                scope.launch {
                    refreshDetailCache(providerId, detailPath, detail)
                }
            }
            return
        }

        val provider = providerRegistry.get(providerId)
        runCatching { withContext(Dispatchers.IO) { provider.fetchMangaDetail(detailPath) } }
            .onSuccess { detail ->
                val resolvedDetail = detail.withSelectedChapterSource(
                    preferredSourceId = preferredSourceId,
                    currentSourceId = currentSourceId,
                )
                withContext(Dispatchers.IO) {
                    libraryStore.cacheMangaDetail(resolvedDetail)
                }
                uiState = uiState.copy(
                    selectedDetail = resolvedDetail,
                    requestedDetailPath = detailPath,
                )
                onSuccess()
            }
            .onFailure {
                uiState = uiState.copy(
                    requestedDetailPath = detailPath,
                    isChapterLoading = false,
                )
                onError(it.message ?: "Could not open manga")
            }
    }

    private suspend fun loadCommunity(
        providerId: String,
        path: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val provider = providerRegistry.get(providerId)
        runCatching { withContext(Dispatchers.IO) { provider.fetchCommunityPage(path) } }
            .onSuccess { page ->
                if (page == null) {
                    uiState = uiState.copy(
                        requestedCommunityPath = path,
                        isCommunityLoading = false,
                    )
                    onError("Could not open community page")
                    return@onSuccess
                }
                uiState = uiState.copy(
                    selectedCommunityPage = page,
                    requestedCommunityPath = path,
                    isCommunityLoading = false,
                )
                onSuccess()
            }
            .onFailure {
                uiState = uiState.copy(
                    requestedCommunityPath = path,
                    isCommunityLoading = false,
                )
                onError(it.message ?: "Could not open community page")
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
        val cacheKey = readerCacheKey(providerId, path)
        val offlineReader = withContext(Dispatchers.IO) {
            offlineStore.loadChapter(providerId, path)
        }
        val cachedReader = readerDataCache[cacheKey]
            ?.takeIf { System.currentTimeMillis() - it.cachedAtMillis <= READER_DATA_CACHE_TTL_MS }
            ?.readerData
        val readerResult = offlineReader ?: cachedReader ?: runCatching {
            val provider = providerRegistry.get(providerId)
            withContext(Dispatchers.IO) { provider.fetchReaderData(path) }
        }.getOrElse {
            uiState = uiState.copy(isChapterLoading = false)
            onError(it.message ?: "Could not open chapter")
            return
        }
        readerDataCache[cacheKey] = CachedReaderData(
            readerData = readerResult,
            cachedAtMillis = System.currentTimeMillis(),
        )

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

    private fun resolvePreferredDetailPath(providerId: String, path: String): String {
        return path.trim()
    }

    private fun resolvePreferredCommunityPath(path: String): String {
        return path.trim().substringBefore('?')
    }

    private fun preferredChapterSourceId(providerId: String): String {
        return when (providerId) {
            MangaBallProvider.PROVIDER_ID -> libraryStore.preferredChapterLanguage().toChapterLanguageCode().orEmpty()
            else -> ""
        }
    }

    private fun MangaDetail.withSelectedChapterSource(
        preferredSourceId: String,
        currentSourceId: String?,
    ): MangaDetail {
        if (chapterSources.isEmpty()) return this
        val currentSelection = currentSourceId?.takeIf { sourceId ->
            chapterSources.any { it.id == sourceId }
        }
        val preferredSelection = preferredSourceId.takeIf { sourceId ->
            chapterSources.any { it.id == sourceId }
        }
        val fallbackSelection = chapterSources.firstOrNull { it.id != "all" }?.id
            ?: chapterSources.firstOrNull()?.id
            ?: selectedChapterSourceId
        val resolvedSelection = currentSelection ?: preferredSelection ?: fallbackSelection
        if (resolvedSelection.isBlank() || resolvedSelection == selectedChapterSourceId) return this
        return copy(selectedChapterSourceId = resolvedSelection)
    }

    private fun isDetailSnapshotStale(snapshot: CachedMangaDetailSnapshot): Boolean {
        return System.currentTimeMillis() - snapshot.updatedAt >= DETAIL_REVALIDATE_AFTER_MS
    }

    private fun readerCacheKey(providerId: String, path: String): String {
        return "$providerId::${canonicalChapterKey(providerId, path)}"
    }

    private data class CachedReaderData(
        val readerData: com.paudinc.komastream.data.model.ReaderData,
        val cachedAtMillis: Long,
    )

    private companion object {
        private const val DETAIL_REVALIDATE_AFTER_MS = 30L * 60L * 1000L
        private const val READER_DATA_CACHE_TTL_MS = 2L * 60L * 1000L
    }
}
