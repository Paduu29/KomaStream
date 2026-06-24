package com.paudinc.komastream.ui.viewmodel

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.os.LocaleListCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.MangaChapter
import com.paudinc.komastream.data.model.FavoriteMangaStatus
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.data.repository.LibraryActionInteractor
import com.paudinc.komastream.ui.viewmodel.MalSyncController
import com.paudinc.komastream.ui.navigation.LibraryTab
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.BatchDownloadWorker
import com.paudinc.komastream.utils.DownloadChapterWorker
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.buildChapterPath
import com.paudinc.komastream.utils.canonicalChapterPathKey
import com.paudinc.komastream.utils.canonicalMangaPathKey
import com.paudinc.komastream.utils.chapterValue
import com.paudinc.komastream.utils.qualifyProviderValue
import com.paudinc.komastream.utils.resolveMalReadCountForReadChapters
import com.paudinc.komastream.utils.resolveReadThroughChapterPaths
import com.paudinc.komastream.utils.resolveLatestReadChapterPath
import com.paudinc.komastream.utils.sameMangaPath
import com.paudinc.komastream.utils.toProgressChapterNumber
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val libraryStore: LibraryStore,
    private val offlineStore: OfflineChapterStore,
    private val workManager: WorkManager,
    private val strings: AppStrings,
    private val libraryActionInteractor: LibraryActionInteractor,
    private val malSyncController: MalSyncController? = null,
) {
    private val _uiState = MutableStateFlow(
        LibraryUiState(
            state = emptyLibraryState(),
        )
    )
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    val downloadProgress = mutableStateMapOf<String, Int>()

    private var downloadTrackingStarted = false
    private var lastTrackedWorkSignature: String = ""

    fun refreshState(filterBySelectedProvider: Boolean = true) {
        refreshStateAsync(filterBySelectedProvider)
    }

    fun refreshStateAsync(filterBySelectedProvider: Boolean = true) {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                libraryStore.readSnapshot(filterBySelectedProvider = filterBySelectedProvider)
            }
            _uiState.update {
                it.copy(
                    state = snapshot.state,
                    allProvidersState = snapshot.allProvidersState,
                    lookup = LibraryLookupState(
                        favoriteKeys = snapshot.favoriteKeys,
                        chapterProgressByKey = snapshot.chapterProgressByKey,
                        cachedDetailByKey = snapshot.cachedDetailByKey,
                        readChaptersByProvider = snapshot.readChaptersByProvider,
                    ),
                    filterBySelectedProvider = filterBySelectedProvider,
                )
            }
        }
    }

    fun refreshOfflineDownloads() {
        scope.launch {
            val downloadedChapterPaths = withContext(Dispatchers.IO) {
                offlineStore.getDownloadedChapterPaths()
            }
            _uiState.update { it.copy(downloadedChapterPaths = downloadedChapterPaths) }
        }
    }

    fun initialize() {
        refreshStateAsync()
        refreshOfflineDownloads()
    }

    fun startDownloadProgressTracking() {
        if (downloadTrackingStarted) return
        downloadTrackingStarted = true
        scope.launch {
            workManager.getWorkInfosByTagFlow(DownloadChapterWorker.TAG)
                .collect { infos ->
                    val signature = infos.joinToString("|") { info ->
                        val path = info.progress.getString(DownloadChapterWorker.KEY_CHAPTER_PATH)
                            ?: info.outputData.getString(DownloadChapterWorker.KEY_CHAPTER_PATH)
                            ?: ""
                        "${info.id}:${info.state}:${path}:${info.progress.getInt(DownloadChapterWorker.KEY_PROGRESS, -1)}"
                    }
                    if (signature != lastTrackedWorkSignature) {
                        lastTrackedWorkSignature = signature
                        refreshOfflineDownloads()
                    }
                    val seenPaths = mutableSetOf<String>()
                    var batchActive = false
                    var batchCurrent = 0
                    var batchTotal = 0
                    infos.forEach { info ->
                        val path = info.progress.getString(DownloadChapterWorker.KEY_CHAPTER_PATH)
                            ?: info.outputData.getString(DownloadChapterWorker.KEY_CHAPTER_PATH)
                        val progress = info.progress.getInt(DownloadChapterWorker.KEY_PROGRESS, -1)
                        val batchCur = info.progress.getInt(BatchDownloadWorker.KEY_BATCH_CURRENT, 0)
                        val batchTot = info.progress.getInt(BatchDownloadWorker.KEY_BATCH_TOTAL, 0)
                        if (batchTot > 0 && info.state == WorkInfo.State.RUNNING) {
                            batchActive = true
                            batchCurrent = batchCur
                            batchTotal = batchTot
                        }
                        if (path != null) {
                            seenPaths += path
                            if (info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED || info.state == WorkInfo.State.CANCELLED) {
                                downloadProgress.remove(path)
                            } else if (progress >= 0) {
                                downloadProgress[path] = progress
                            }
                        }
                    }
                    downloadProgress.keys
                        .filterNot { it in seenPaths }
                        .toList()
                        .forEach(downloadProgress::remove)
                    _uiState.update {
                        it.copy(
                            isBatchDownloading = batchActive,
                            batchCurrentChapter = batchCurrent,
                            batchTotalChapters = batchTotal,
                        )
                    }
                }
        }
        scope.launch {
            workManager.getWorkInfosByTagFlow(BatchDownloadWorker.TAG)
                .collect { infos ->
                    val anyActive = infos.any { info ->
                        info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                    }
                    if (!anyActive) {
                        _uiState.update {
                            it.copy(isBatchDownloading = false)
                        }
                    }
                }
        }
    }

    fun downloadChapter(providerId: String, path: String) {
        enqueueDownload(providerId, path)
    }

    fun downloadChapters(providerId: String, detailPath: String, mangaTitle: String, chapterPaths: Collection<String>) {
        scope.launch {
            val queuedPaths = withContext(Dispatchers.IO) {
                val downloaded = offlineStore.getDownloadedChapterPaths()
                chapterPaths
                    .asSequence()
                    .map { canonicalChapterPathKey(providerId, it) }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .filterNot { path ->
                        qualifyProviderValue(providerId, path) in downloaded || downloadProgress.containsKey(path)
                    }
                    .toList()
            }
            if (queuedPaths.isEmpty()) {
                Toast.makeText(context, strings.noChaptersLeftToDownload, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val orderedPaths = queuedPaths.reversed()
            val batchWorkName = batchWorkName(providerId, detailPath)
            val safeFileName = batchWorkName.replace(Regex("[:/]"), "_")
            val batchFile = File(context.cacheDir, "batch_${safeFileName}.txt")
            withContext(Dispatchers.IO) {
                batchFile.writeText(orderedPaths.joinToString("\n"))
            }
            val data = Data.Builder()
                .putString(BatchDownloadWorker.KEY_BATCH_FILE, batchFile.absolutePath)
                .putString(BatchDownloadWorker.KEY_PROVIDER_ID, providerId)
                .putString(BatchDownloadWorker.KEY_MANGA_TITLE, mangaTitle)
                .build()
            val request = OneTimeWorkRequestBuilder<BatchDownloadWorker>()
                .setInputData(data)
                .addTag(BatchDownloadWorker.TAG)
                .addTag(DownloadChapterWorker.TAG)
                .build()
            workManager.enqueueUniqueWork(batchWorkName, ExistingWorkPolicy.KEEP, request)
            _uiState.update {
                it.copy(
                    isBatchDownloading = true,
                    batchTotalChapters = orderedPaths.size,
                    batchCurrentChapter = 0,
                )
            }
            Toast.makeText(
                context,
                strings.chapterDownloadsQueued(orderedPaths.size),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun cancelAllDownloads() {
        workManager.cancelAllWorkByTag(BatchDownloadWorker.TAG)
        _uiState.update { it.copy(isBatchDownloading = false) }
        Toast.makeText(context, strings.downloadAllCancelled, Toast.LENGTH_SHORT).show()
    }

    private fun enqueueDownload(providerId: String, path: String) {
        val data = Data.Builder()
            .putString(DownloadChapterWorker.KEY_PROVIDER_ID, providerId)
            .putString(DownloadChapterWorker.KEY_CHAPTER_PATH, path)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadChapterWorker>()
            .setInputData(data)
            .addTag(DownloadChapterWorker.TAG)
            .build()
        workManager.enqueueUniqueWork(downloadWorkName(providerId, path), ExistingWorkPolicy.KEEP, request)
    }

    fun removeDownloadedChapter(providerId: String, path: String, onError: (String) -> Unit) {
        scope.launch {
            runCatching {
                workManager.cancelUniqueWork(downloadWorkName(providerId, path))
                withContext(Dispatchers.IO) {
                    offlineStore.removeChapter(providerId, path)
                }
            }.onSuccess {
                downloadProgress.remove(path)
                refreshOfflineDownloads()
                Toast.makeText(context, strings.chapterRemoved, Toast.LENGTH_SHORT).show()
            }.onFailure {
                onError(it.message ?: strings.couldNotRemoveDownload)
            }
        }
    }

    fun toggleFavorite(manga: SavedManga) {
        scope.launch {
            val favorite = libraryActionInteractor.buildFavoriteCandidate(_uiState.value.state, manga)
            val wasFavorite = withContext(Dispatchers.IO) {
                libraryStore.isFavorite(favorite.providerId, favorite.detailPath)
            }
            withContext(Dispatchers.IO) { libraryStore.toggleFavorite(favorite) }
            if (wasFavorite) {
                removeFavoriteFromUi(favorite.providerId, favorite.detailPath)
            } else {
                upsertFavoriteInUi(favorite)
            }
            Toast.makeText(
                context,
                if (wasFavorite) strings.removedFromFavorites else strings.addedToFavorites,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun selectTab(tab: LibraryTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun toggleChapterRead(
        providerId: String,
        path: String,
        detail: com.paudinc.komastream.data.model.MangaDetail? = null,
        onCompleted: (() -> Unit)? = null,
    ) {
        _uiState.update { it.copy(isBulkUpdatingChapters = true) }
        scope.launch {
            var readPaths: Set<String> = emptySet()
            var updatedReading: SavedManga? = null
            var markAsRead = false
            withContext(Dispatchers.IO) {
                val wasRead = libraryStore.isChapterRead(providerId, path)
                if (wasRead) {
                    readPaths = setOf(canonicalChapterPathKey(providerId, path))
                    libraryStore.setChaptersRead(providerId, readPaths, false)
                } else {
                    markAsRead = true
                    val pathsToMark = detail?.takeIf { it.providerId == providerId }?.let {
                        resolveReadThroughChapterPaths(
                            providerId = providerId,
                            detailPath = it.detailPath,
                            chapters = it.chapters,
                            currentChapterPath = path,
                        )
                    } ?: listOf(path)
                    readPaths = pathsToMark.mapTo(linkedSetOf()) { canonicalChapterPathKey(providerId, it) }
                    libraryStore.setChaptersRead(providerId, readPaths, true)
                    updatedReading = detail?.let {
                        updateProgressSnapshot(
                            providerId = providerId,
                            detailPath = it.detailPath,
                            mangaTitle = it.title,
                            coverUrl = it.coverUrl,
                            chapters = it.chapters,
                        )
                    }
                }
            }
            applyReadChapterMutation(providerId = providerId, chapterPaths = readPaths, read = markAsRead)
            updatedReading?.let(::upsertReadingInUi)
            _uiState.update { it.copy(isBulkUpdatingChapters = false) }
            onCompleted?.invoke()
            Toast.makeText(context, strings.updatedReadStatus, Toast.LENGTH_SHORT).show()
        }
    }

    fun setAllChaptersRead(
        providerId: String,
        detailPath: String,
        mangaTitle: String,
        coverUrl: String,
        chapters: List<MangaChapter>,
        read: Boolean,
    ) {
        _uiState.update { it.copy(isBulkUpdatingChapters = true) }
        scope.launch {
            val chapterPaths = chapters.mapTo(linkedSetOf()) { canonicalChapterPathKey(providerId, buildChapterPath(detailPath, it)) }
            withContext(Dispatchers.IO) {
                libraryStore.setChaptersRead(providerId, chapterPaths, read)
            }
            if (malSyncController != null) {
                withContext(Dispatchers.IO) {
                    malSyncController.pushBulkReadProgress(
                        providerId = providerId,
                        detailPath = detailPath,
                        title = mangaTitle,
                        coverUrl = coverUrl,
                        chapters = chapters,
                        read = read,
                    )
                }
            }
            val updatedReading = updateProgressSnapshot(
                providerId = providerId,
                detailPath = detailPath,
                mangaTitle = mangaTitle,
                coverUrl = coverUrl,
                chapters = chapters,
            )
            applyReadChapterMutation(providerId = providerId, chapterPaths = chapterPaths, read = read)
            updatedReading?.let(::upsertReadingInUi)
            _uiState.update { it.copy(isBulkUpdatingChapters = false) }
            Toast.makeText(
                context,
                if (read) strings.allChaptersRead else strings.allChaptersUnread,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun setUntilChapterRead(
        providerId: String,
        detailPath: String,
        mangaTitle: String,
        coverUrl: String,
        chapters: List<MangaChapter>,
        targetValue: Double,
        read: Boolean,
    ) {
        _uiState.update { it.copy(isBulkUpdatingChapters = true) }
        scope.launch {
            val paths = withContext(Dispatchers.Default) {
                chapters.filter { chapterValue(it) <= targetValue }.map { buildChapterPath(detailPath, it) }
            }
            val canonicalPaths = paths.mapTo(linkedSetOf()) { canonicalChapterPathKey(providerId, it) }
            withContext(Dispatchers.IO) {
                libraryStore.setChaptersRead(providerId, canonicalPaths, read)
            }
            if (malSyncController != null) {
                val syncedChapters = chapters.filter { chapterValue(it) <= targetValue }
                withContext(Dispatchers.IO) {
                    malSyncController.pushBulkReadProgress(
                        providerId = providerId,
                        detailPath = detailPath,
                        title = mangaTitle,
                        coverUrl = coverUrl,
                        chapters = syncedChapters,
                        read = read,
                    )
                }
            }
            val updatedReading = updateProgressSnapshot(
                providerId = providerId,
                detailPath = detailPath,
                mangaTitle = mangaTitle,
                coverUrl = coverUrl,
                chapters = chapters,
            )
            applyReadChapterMutation(providerId = providerId, chapterPaths = canonicalPaths, read = read)
            updatedReading?.let(::upsertReadingInUi)
            _uiState.update { it.copy(isBulkUpdatingChapters = false) }
            Toast.makeText(context, strings.markedUntilChapter(targetValue, read), Toast.LENGTH_SHORT).show()
        }
    }

    fun removeReading(manga: SavedManga) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.removeReading(manga.providerId, manga.detailPath) }
            malSyncController?.pushReadingEntry(manga, isRemoved = true)
            removeReadingFromUi(manga.providerId, manga.detailPath)
            Toast.makeText(context, strings.removedFromContinueReading, Toast.LENGTH_SHORT).show()
        }
    }

    fun addToReading(manga: SavedManga) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.upsertReading(manga) }
            malSyncController?.pushReadingEntry(manga)
            upsertReadingInUi(manga)
            Toast.makeText(context, strings.addedToContinueReading, Toast.LENGTH_SHORT).show()
        }
    }

    fun setFavoriteStatus(manga: SavedManga, status: FavoriteMangaStatus) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.setFavoriteStatus(manga.providerId, manga.detailPath, status) }
            updateSavedMangaInUi(manga.providerId, manga.detailPath) { it.copy(favoriteStatus = status) }
            Toast.makeText(context, strings.favoritesUpdated, Toast.LENGTH_SHORT).show()
        }
    }

    fun changeLanguage(language: AppLanguage) {
        scope.launch {
            withContext(Dispatchers.IO) {
                libraryStore.setAppLanguage(language)
            }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.toLanguageTag()))
            updateLibraryUi(transformState = { it.copy(appLanguage = language) })
        }
    }

    fun changeTheme(dark: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.setDarkTheme(dark) }
            updateLibraryUi(transformState = { it.copy(useDarkTheme = dark) })
        }
    }

    fun changeAutoJumpToUnread(enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.setAutoJumpToUnread(enabled) }
            updateLibraryUi(transformState = { it.copy(autoJumpToUnread = enabled) })
        }
    }

    fun changeMangaBallAdultContent(enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.setAdultContentEnabled(enabled) }
            updateLibraryUi(transformState = {
                it.copy(
                    adultContentEnabled = enabled,
                    mangaBallAdultContentEnabled = enabled,
                    manhwaLatinoAdultContentEnabled = enabled,
                )
            })
        }
    }

    fun changeManhwaLatinoAdultContent(enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.setAdultContentEnabled(enabled) }
            updateLibraryUi(transformState = {
                it.copy(
                    adultContentEnabled = enabled,
                    mangaBallAdultContentEnabled = enabled,
                    manhwaLatinoAdultContentEnabled = enabled,
                )
            })
        }
    }

    fun changeAdultContentEnabled(enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.setAdultContentEnabled(enabled) }
            updateLibraryUi(transformState = {
                it.copy(
                    adultContentEnabled = enabled,
                    mangaBallAdultContentEnabled = enabled,
                    manhwaLatinoAdultContentEnabled = enabled,
                )
            })
        }
    }

    fun changeAdultOnlyProvidersEnabled(enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.setAdultOnlyProvidersEnabled(enabled) }
            refreshState()
        }
    }

    fun changePreferredChapterLanguage(language: com.paudinc.komastream.data.model.AppLanguage) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.setPreferredChapterLanguage(language) }
            updateLibraryUi(transformState = { it.copy(preferredChapterLanguage = language) })
        }
    }

    suspend fun updateProgressSnapshot(
        providerId: String,
        detailPath: String,
        mangaTitle: String,
        coverUrl: String,
        chapters: List<MangaChapter>,
    ): SavedManga? {
        val readChapters = libraryStore.readChaptersForProvider(providerId)
        val progressChapterPath = resolveLatestReadChapterPath(providerId, detailPath, chapters, readChapters) ?: return null
        val progressChapter = chapters.firstOrNull {
            buildChapterPath(detailPath, it) == progressChapterPath
        } ?: return null
        val lastReadChapterNumber = resolveMalReadCountForReadChapters(
            providerId = providerId,
            detailPath = detailPath,
            chapters = chapters,
            readChapters = readChapters,
        )
        val readingEntry = SavedManga(
            providerId = providerId,
            title = mangaTitle,
            detailPath = detailPath,
            coverUrl = coverUrl,
            lastChapterTitle = strings.chapterLabelWithNumber(progressChapter),
            lastChapterPath = progressChapterPath,
            lastProgressChapterNumber = progressChapter.chapterNumberUrl.toProgressChapterNumber()
                ?: progressChapter.chapterLabel.toProgressChapterNumber(),
            lastReadChapterNumber = lastReadChapterNumber,
            lastReadAt = System.currentTimeMillis(),
        )
        libraryStore.upsertReading(readingEntry)
        return readingEntry
    }

    fun selectProvider(providerId: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                libraryStore.setSelectedProviderId(providerId)
                libraryStore.setHasSeenProviderPicker(true)
            }
            refreshState(filterBySelectedProvider = true)
        }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { libraryStore.setProviderEnabled(providerId, enabled) }
            refreshState(filterBySelectedProvider = true)
        }
    }

    fun currentState(): LibraryState = _uiState.value.state

    private fun downloadWorkName(providerId: String, path: String): String =
        "download:$providerId:$path"

    private fun batchWorkName(providerId: String, detailPath: String): String =
        "batch_download:$providerId:${canonicalMangaPathKey(providerId, detailPath)}"

    private fun qualifyMangaLookupKey(providerId: String, detailPath: String): String =
        "$providerId::${canonicalMangaPathKey(providerId, detailPath)}"

    private fun updateLibraryUi(
        transformState: (LibraryState) -> LibraryState,
        transformLookup: (LibraryLookupState) -> LibraryLookupState = { it },
    ) {
        _uiState.update { current ->
            val updatedAllProvidersState = transformState(current.allProvidersState)
            current.copy(
                allProvidersState = updatedAllProvidersState,
                state = filterVisibleState(updatedAllProvidersState, current.filterBySelectedProvider),
                lookup = transformLookup(current.lookup),
            )
        }
    }

    private fun filterVisibleState(state: LibraryState, filterBySelectedProvider: Boolean): LibraryState {
        if (!filterBySelectedProvider) return state
        val selectedProviderId = state.selectedProviderId
        return state.copy(
            favorites = state.favorites.filter { it.providerId == selectedProviderId },
            reading = state.reading.filter { it.providerId == selectedProviderId },
            readChapters = state.readChapters,
        )
    }

    private fun upsertFavoriteInUi(manga: SavedManga) {
        updateLibraryUi(
            transformState = { state ->
                state.copy(favorites = upsertSavedManga(state.favorites, manga))
            },
            transformLookup = { lookup ->
                lookup.copy(favoriteKeys = lookup.favoriteKeys + qualifyMangaLookupKey(manga.providerId, manga.detailPath))
            },
        )
    }

    private fun removeFavoriteFromUi(providerId: String, detailPath: String) {
        updateLibraryUi(
            transformState = { state ->
                state.copy(favorites = removeSavedManga(state.favorites, providerId, detailPath))
            },
            transformLookup = { lookup ->
                lookup.copy(favoriteKeys = lookup.favoriteKeys - qualifyMangaLookupKey(providerId, detailPath))
            },
        )
    }

    private fun upsertReadingInUi(manga: SavedManga) {
        updateLibraryUi(transformState = { state ->
            state.copy(reading = upsertSavedManga(state.reading, manga))
        })
    }

    private fun removeReadingFromUi(providerId: String, detailPath: String) {
        updateLibraryUi(transformState = { state ->
            state.copy(reading = removeSavedManga(state.reading, providerId, detailPath))
        })
    }

    private fun updateSavedMangaInUi(
        providerId: String,
        detailPath: String,
        transform: (SavedManga) -> SavedManga,
    ) {
        updateLibraryUi(transformState = { state ->
            state.copy(
                favorites = state.favorites.map { item ->
                    if (item.providerId == providerId && sameMangaPath(providerId, item.detailPath, detailPath)) transform(item) else item
                },
                reading = state.reading.map { item ->
                    if (item.providerId == providerId && sameMangaPath(providerId, item.detailPath, detailPath)) transform(item) else item
                },
            )
        })
    }

    private fun applyReadChapterMutation(providerId: String, chapterPaths: Set<String>, read: Boolean) {
        if (chapterPaths.isEmpty()) return
        updateLibraryUi(
            transformState = { state ->
                if (state.selectedProviderId != providerId) {
                    state
                } else {
                    state.copy(
                        readChapters = if (read) state.readChapters + chapterPaths else state.readChapters - chapterPaths
                    )
                }
            },
            transformLookup = { lookup ->
                val currentPaths = lookup.readChaptersByProvider[providerId].orEmpty()
                lookup.copy(
                    readChaptersByProvider = lookup.readChaptersByProvider + (
                        providerId to if (read) currentPaths + chapterPaths else currentPaths - chapterPaths
                    )
                )
            },
        )
    }

    private fun upsertSavedManga(items: List<SavedManga>, manga: SavedManga): List<SavedManga> {
        val index = items.indexOfFirst { it.providerId == manga.providerId && sameMangaPath(manga.providerId, it.detailPath, manga.detailPath) }
        if (index >= 0) {
            return items.toMutableList().also { it[index] = mergeSavedManga(it[index], manga) }
        }
        return buildList(items.size + 1) {
            add(manga)
            addAll(items)
        }
    }

    private fun removeSavedManga(items: List<SavedManga>, providerId: String, detailPath: String): List<SavedManga> =
        items.filterNot { it.providerId == providerId && sameMangaPath(providerId, it.detailPath, detailPath) }

    private fun mergeSavedManga(current: SavedManga, incoming: SavedManga): SavedManga =
        current.copy(
            title = incoming.title.ifBlank { current.title },
            detailPath = incoming.detailPath.ifBlank { current.detailPath },
            coverUrl = incoming.coverUrl.ifBlank { current.coverUrl },
            favoriteStatus = incoming.favoriteStatus,
            lastChapterTitle = incoming.lastChapterTitle.ifBlank { current.lastChapterTitle },
            lastChapterPath = incoming.lastChapterPath.ifBlank { current.lastChapterPath },
            lastProgressChapterNumber = incoming.lastProgressChapterNumber ?: current.lastProgressChapterNumber,
            malMangaId = incoming.malMangaId ?: current.malMangaId,
            lastReadChapterNumber = incoming.lastReadChapterNumber ?: current.lastReadChapterNumber,
            lastReadAt = incoming.lastReadAt ?: current.lastReadAt,
        )
}
