package com.paudinc.komastream.ui.viewmodel

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.MangaChapter
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.data.repository.LibraryActionInteractor
import com.paudinc.komastream.ui.navigation.LibraryTab
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.DownloadChapterWorker
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.buildChapterPath
import com.paudinc.komastream.utils.chapterValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
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
) {
    var uiState by mutableStateOf(
        LibraryUiState(
            state = libraryStore.read(),
            downloadedChapterPaths = offlineStore.getDownloadedChapterPaths(),
        )
    )
        private set

    val downloadProgress = mutableStateMapOf<String, Int>()

    private var downloadTrackingStarted = false
    private var lastTrackedWorkSignature: String = ""

    fun refreshState(filterBySelectedProvider: Boolean = true) {
        uiState = uiState.copy(
            state = libraryStore.read(filterBySelectedProvider = filterBySelectedProvider)
        )
    }

    fun refreshOfflineDownloads() {
        uiState = uiState.copy(
            downloadedChapterPaths = offlineStore.getDownloadedChapterPaths()
        )
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
                    infos.forEach { info ->
                        val path = info.progress.getString(DownloadChapterWorker.KEY_CHAPTER_PATH)
                            ?: info.outputData.getString(DownloadChapterWorker.KEY_CHAPTER_PATH)
                        val progress = info.progress.getInt(DownloadChapterWorker.KEY_PROGRESS, -1)
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
                }
        }
    }

    fun downloadChapter(providerId: String, path: String) {
        val data = Data.Builder()
            .putString(DownloadChapterWorker.KEY_PROVIDER_ID, providerId)
            .putString(DownloadChapterWorker.KEY_CHAPTER_PATH, path)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadChapterWorker>()
            .setInputData(data)
            .addTag(DownloadChapterWorker.TAG)
            .build()
        workManager.enqueueUniqueWork("download:$providerId:$path", ExistingWorkPolicy.KEEP, request)
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
                Log.e("KomaStream", "Could not remove downloaded chapter", it)
                onError(it.message ?: strings.couldNotRemoveDownload)
            }
        }
    }

    fun toggleFavorite(manga: SavedManga) {
        val favorite = libraryActionInteractor.buildFavoriteCandidate(uiState.state, manga)
        val wasFavorite = libraryStore.isFavorite(favorite.providerId, favorite.detailPath)
        libraryStore.toggleFavorite(favorite)
        refreshState()
        Toast.makeText(
            context,
            if (wasFavorite) strings.removedFromFavorites else strings.addedToFavorites,
            Toast.LENGTH_SHORT
        ).show()
    }

    fun selectTab(tab: LibraryTab) {
        uiState = uiState.copy(selectedTab = tab)
    }

    fun toggleChapterRead(providerId: String, path: String) {
        libraryStore.toggleChapterRead(providerId, path)
        refreshState()
        Toast.makeText(context, strings.updatedReadStatus, Toast.LENGTH_SHORT).show()
    }

    fun setAllChaptersRead(providerId: String, detailPath: String, chapters: List<MangaChapter>, read: Boolean) {
        uiState = uiState.copy(isBulkUpdatingChapters = true)
        scope.launch {
            withContext(Dispatchers.Default) {
                libraryStore.setChaptersRead(providerId, chapters.map { buildChapterPath(detailPath, it) }, read)
            }
            refreshState()
            uiState = uiState.copy(isBulkUpdatingChapters = false)
            Toast.makeText(
                context,
                if (read) strings.allChaptersRead else strings.allChaptersUnread,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun setUntilChapterRead(providerId: String, detailPath: String, chapters: List<MangaChapter>, targetValue: Double, read: Boolean) {
        uiState = uiState.copy(isBulkUpdatingChapters = true)
        scope.launch {
            val paths = withContext(Dispatchers.Default) {
                chapters.filter { chapterValue(it) <= targetValue }.map { buildChapterPath(detailPath, it) }
            }
            withContext(Dispatchers.Default) {
                libraryStore.setChaptersRead(providerId, paths, read)
            }
            refreshState()
            uiState = uiState.copy(isBulkUpdatingChapters = false)
            Toast.makeText(context, strings.markedUntilChapter(targetValue, read), Toast.LENGTH_SHORT).show()
        }
    }

    fun removeReading(manga: SavedManga) {
        libraryStore.removeReading(manga.providerId, manga.detailPath)
        refreshState()
        Toast.makeText(context, strings.removedFromContinueReading, Toast.LENGTH_SHORT).show()
    }

    fun addToReading(manga: SavedManga) {
        libraryStore.upsertReading(manga)
        refreshState()
        Toast.makeText(context, strings.addedToContinueReading, Toast.LENGTH_SHORT).show()
    }

    fun changeLanguage(language: AppLanguage) {
        libraryStore.setAppLanguage(language)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.toLanguageTag()))
        refreshState()
    }

    fun changeTheme(dark: Boolean) {
        libraryStore.setDarkTheme(dark)
        refreshState()
    }

    fun changeAutoJumpToUnread(enabled: Boolean) {
        libraryStore.setAutoJumpToUnread(enabled)
        refreshState()
    }

    fun changeMangaBallAdultContent(enabled: Boolean) {
        libraryStore.setMangaBallAdultContentEnabled(enabled)
        refreshState()
    }

    fun selectProvider(providerId: String) {
        android.util.Log.d("LibraryController", "selectProvider: $providerId")
        libraryStore.setSelectedProviderId(providerId)
        libraryStore.setHasSeenProviderPicker(true)
        refreshState(filterBySelectedProvider = true)
    }

    fun currentState(): LibraryState = uiState.state

    private fun downloadWorkName(providerId: String, path: String): String =
        "download:$providerId:$path"
}
