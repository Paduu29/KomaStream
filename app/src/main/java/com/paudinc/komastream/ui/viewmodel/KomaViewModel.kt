package com.paudinc.komastream.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.MangaChapter
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.data.repository.BackupFileInteractor
import com.paudinc.komastream.data.repository.CatalogStateInteractor
import com.paudinc.komastream.data.repository.LibraryActionInteractor
import com.paudinc.komastream.data.repository.ReaderActionInteractor
import com.paudinc.komastream.ui.navigation.LibraryTab
import com.paudinc.komastream.ui.navigation.RootTab
import com.paudinc.komastream.ui.navigation.Screen
import com.paudinc.komastream.updater.GitHubRelease
import com.paudinc.komastream.updater.GitHubReleaseUpdater
import com.paudinc.komastream.provider.providers.MangaBallProvider
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.ProviderRegistry
import com.paudinc.komastream.utils.buildChapterPath
import com.paudinc.komastream.utils.canonicalChapterKey
import com.paudinc.komastream.utils.canonicalChapterKeys
import java.io.File

class KomaViewModel(
    private val context: Context,
    private val providerRegistry: ProviderRegistry,
    private val libraryStore: LibraryStore,
    offlineStore: OfflineChapterStore,
    workManager: WorkManager,
    updater: GitHubReleaseUpdater,
    private val strings: AppStrings,
    initialNavigationStack: List<Screen>? = null,
    backupFileInteractor: BackupFileInteractor = BackupFileInteractor(context.contentResolver),
    libraryActionInteractor: LibraryActionInteractor = LibraryActionInteractor(),
    catalogStateInteractor: CatalogStateInteractor = CatalogStateInteractor(),
    readerActionInteractor: ReaderActionInteractor = ReaderActionInteractor(),
) : ViewModel() {

    val navigationController = NavigationController(
        initialStack = initialNavigationStack ?: listOf(
            if (libraryStore.hasSeenProviderPicker()) {
                Screen.Root(RootTab.Home)
            } else {
                Screen.ProviderPicker
            }
        )
    )

    val homeController = HomeController(viewModelScope)
    val catalogController = CatalogController(viewModelScope, catalogStateInteractor)
    val malSyncController = MalSyncController(
        context = context,
        scope = viewModelScope,
        providerRegistry = providerRegistry,
        libraryStore = libraryStore,
        strings = strings,
        onLocalLibraryChanged = ::refreshLibraryUi,
    )
    val libraryController = LibraryController(
        context = context,
        scope = viewModelScope,
        libraryStore = libraryStore,
        offlineStore = offlineStore,
        workManager = workManager,
        strings = strings,
        libraryActionInteractor = libraryActionInteractor,
        malSyncController = malSyncController,
    )
    val readerController = ReaderController(
        scope = viewModelScope,
        providerRegistry = providerRegistry,
        libraryStore = libraryStore,
        readerActionInteractor = readerActionInteractor,
    )
    val backupController = BackupController(
        scope = viewModelScope,
        libraryStore = libraryStore,
        backupFileInteractor = backupFileInteractor,
        strings = strings,
    )
    val updateController = UpdateController(
        scope = viewModelScope,
        updater = updater,
        strings = strings,
        onError = ::showError,
    )

    val screen: Screen
        get() = navigationController.screen

val currentProvider
        get() = providerRegistry.get(libraryController.uiState.state.selectedProviderId)

    init {
        libraryController.refreshOfflineDownloads()
        libraryController.startDownloadProgressTracking()
        updateController.checkForUpdates(openDialogOnUpdate = true)
        val providerId = libraryController.uiState.state.selectedProviderId
        if (providerId.isNotBlank()) {
            refreshHome()
        }
    }

    fun pushScreen(next: Screen) {
        navigationController.pushScreen(next)
    }

    fun replaceRoot(tab: RootTab) {
        navigationController.replaceRoot(tab)
    }

    fun goBack(): Boolean = navigationController.goBack()

    fun checkForUpdates(notifyIfCurrent: Boolean = false, openDialogOnUpdate: Boolean = false) {
        updateController.checkForUpdates(notifyIfCurrent, openDialogOnUpdate)
    }

    fun downloadUpdate(release: GitHubRelease) {
        updateController.downloadUpdate(release)
    }

    fun installDownloadedUpdate(file: File) {
        updateController.installDownloadedUpdate(file)
    }

    fun refreshHome() {
        homeController.refreshHome(currentProvider, ::showError)
    }

    fun refreshCatalogFilterOptions() {
        catalogController.refreshFilterOptions(currentProvider)
    }

    fun searchCatalog(loadMore: Boolean = false) {
        catalogController.search(
            provider = currentProvider,
            loadMore = loadMore,
            onLoadingChange = ::updateLoadingState,
            onError = { showError(it.ifBlank { strings.couldNotSearchCatalog }) },
        )
    }

    fun openDetail(providerId: String, path: String) {
        readerController.openDetail(
            providerId = providerId,
            path = path,
            navigationController = navigationController,
            onLoadingChange = ::updateLoadingState,
            onError = { showError(it.ifBlank { strings.couldNotOpenManga }) },
        )
    }

    fun openReader(providerId: String, path: String, replace: Boolean = false, resumeProgress: Boolean = false) {
        readerController.openReader(
            providerId = providerId,
            path = path,
            resumeProgress = resumeProgress,
            replace = replace,
            navigationController = navigationController,
            libraryState = libraryController.currentState(),
            homeFeed = homeController.uiState.feed,
            onLibraryChanged = { libraryController.refreshState() },
            onLoadingChange = ::updateLoadingState,
            onError = { showError(it.ifBlank { strings.couldNotOpenChapter }) },
        )
    }

    fun downloadChapter(providerId: String, path: String) {
        libraryController.downloadChapter(providerId, path)
    }

    fun removeDownloadedChapter(providerId: String, path: String) {
        libraryController.removeDownloadedChapter(providerId, path, ::showError)
    }

    fun toggleFavorite(manga: SavedManga) {
        val previousState = libraryController.currentState()
        val wasFavorite = previousState.favorites.any {
            it.providerId == manga.providerId && it.detailPath == manga.detailPath
        }
        libraryController.toggleFavorite(manga)
        syncMalFavoriteState(manga, isFavorite = !wasFavorite)
    }

    fun selectLibraryTab(tab: LibraryTab) {
        libraryController.selectTab(tab)
    }

    fun updateCatalogQuery(query: String) {
        catalogController.updateQuery(query)
    }

    fun toggleCatalogCategory(categoryId: String) {
        catalogController.toggleCategory(categoryId)
    }

    fun selectCatalogSort(sortOptionId: String) {
        catalogController.selectSort(sortOptionId)
    }

    fun selectCatalogStatus(statusOptionId: String) {
        catalogController.selectStatus(statusOptionId)
    }

    fun setCatalogOnlyFavorites(onlyFavorites: Boolean) {
        catalogController.setOnlyFavorites(onlyFavorites)
    }

    fun clearCatalogFilters() {
        catalogController.clearFilters()
    }

    fun toggleChapterRead(providerId: String, path: String, detail: MangaDetail? = null) {
        libraryController.toggleChapterRead(providerId, path, detail)
        detail?.let {
            libraryController.updateProgressSnapshot(
                providerId = providerId,
                detailPath = it.detailPath,
                mangaTitle = it.title,
                coverUrl = it.coverUrl,
                chapters = it.chapters,
            )
            libraryController.refreshState()
        }
        syncMalChapterReadState(providerId)
    }

    fun setAllChaptersRead(
        providerId: String,
        detailPath: String,
        mangaTitle: String,
        coverUrl: String,
        chapters: List<MangaChapter>,
        read: Boolean,
    ) {
        libraryController.setAllChaptersRead(providerId, detailPath, mangaTitle, coverUrl, chapters, read)
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
        libraryController.setUntilChapterRead(providerId, detailPath, mangaTitle, coverUrl, chapters, targetValue, read)
    }

    fun removeReading(manga: SavedManga) {
        libraryController.removeReading(manga)
    }

    fun addToReading(manga: SavedManga) {
        libraryController.addToReading(manga)
    }

    fun beginMalConnect(): String =
        malSyncController.beginConnect()

    fun handleMalCallback(uri: Uri?): Boolean =
        malSyncController.handleAuthorizationCallback(uri)

    fun syncMalLibrary() {
        malSyncController.syncLocalLibraryToRemote(currentProvider.id)
    }

    fun disconnectMal() {
        malSyncController.disconnect()
    }

    fun changeLanguage(language: AppLanguage) {
        libraryController.changeLanguage(language)
    }

    fun changeTheme(dark: Boolean) {
        libraryController.changeTheme(dark)
    }

    fun changeAutoJumpToUnread(enabled: Boolean) {
        libraryController.changeAutoJumpToUnread(enabled)
    }

    fun changeMangaBallAdultContent(enabled: Boolean) {
        libraryController.changeMangaBallAdultContent(enabled)
        providerRegistry.get(MangaBallProvider.PROVIDER_ID).invalidateCaches()
        homeController.clearFeed()
        catalogController.resetForProviderChange()
        if (currentProvider.id == MangaBallProvider.PROVIDER_ID) {
            homeController.refreshHome(currentProvider, ::showError)
            catalogController.refreshFilterOptions(currentProvider)
        }
    }

    fun exportBackup(uri: Uri) {
        backupController.exportBackup(uri)
    }

    fun importBackup(uri: Uri) {
        backupController.importBackup(
            uri = uri,
            selectedProviderIdFallback = libraryController.uiState.state.selectedProviderId,
        ) {
            libraryController.refreshState()
            homeController.refreshHome(currentProvider, ::showError)
        }
    }

    fun dismissBackupOperationDialog() {
        backupController.dismissDialog()
    }

    fun selectProvider(providerId: String) {
        val previousProviderId = libraryController.uiState.state.selectedProviderId
        libraryController.selectProvider(providerId)
        homeController.clearFeed()
        catalogController.resetForProviderChange()
        navigationController.replaceRoot(RootTab.Home)
        if (previousProviderId != providerId) {
            refreshHome()
        }
    }

    fun refreshCurrentProviderContent(clearVisibleData: Boolean = false) {
        if (clearVisibleData) {
            homeController.clearFeed()
            catalogController.clearResults()
        }
        refreshCatalogFilterOptions()
        refreshHome()
    }

    fun updatePageProgress(providerId: String, path: String, index: Int) {
        readerController.updatePageProgress(
            providerId = providerId,
            path = path,
            index = index,
            onChapterMarkedRead = { libraryController.refreshState() },
        )
    }

    fun openAdjacentChapter(providerId: String, currentPath: String, targetPath: String, markCurrentRead: Boolean) {
        readerController.updateChapterReadState(providerId, currentPath, markCurrentRead)
        libraryController.refreshState()
        openReader(providerId, targetPath, replace = true, resumeProgress = false)
    }

    private fun updateLoadingState(isLoading: Boolean) {
        loading = isLoading
    }

    private fun refreshLibraryUi() {
        libraryController.refreshState()
    }

    private fun syncMalFavoriteState(manga: SavedManga, isFavorite: Boolean) {
        if (!malSyncController.uiState.isConnected) return
        val state = libraryController.currentState()
        val detail = readerController.uiState.selectedDetail?.takeIf {
            it.providerId == manga.providerId && it.detailPath == manga.detailPath
        }
        val readCount = detail?.let { countReadChapters(it.providerId, it.detailPath, it.chapters, state.readChapters) } ?: 0
        val target = manga.copy(
            title = manga.title.ifBlank { detail?.title.orEmpty() },
            coverUrl = manga.coverUrl.ifBlank { detail?.coverUrl.orEmpty() },
        )
        when {
            isFavorite && readCount > 0 -> malSyncController.pushReadProgress(target, readCount)
            isFavorite -> malSyncController.pushFavoriteEntry(target)
            readCount > 0 -> malSyncController.pushReadProgress(target, readCount)
            else -> malSyncController.pushFavoriteEntry(target, isRemoved = true)
        }
    }

    private fun syncMalChapterReadState(providerId: String) {
        if (!malSyncController.uiState.isConnected) return
        val detail = readerController.uiState.selectedDetail?.takeIf { it.providerId == providerId } ?: return
        val state = libraryController.currentState()
        val readCount = countReadChapters(providerId, detail.detailPath, detail.chapters, state.readChapters)
        val target = SavedManga(
            providerId = providerId,
            title = detail.title,
            detailPath = detail.detailPath,
            coverUrl = detail.coverUrl,
        )
        val isFavorite = state.favorites.any {
            it.providerId == providerId && it.detailPath == detail.detailPath
        }
        when {
            readCount > 0 -> malSyncController.pushReadProgress(target, readCount)
            isFavorite -> malSyncController.pushFavoriteEntry(target)
            else -> malSyncController.pushFavoriteEntry(target, isRemoved = true)
        }
    }

    private fun countReadChapters(
        providerId: String,
        detailPath: String,
        chapters: List<MangaChapter>,
        readChapters: Set<String>,
    ): Int {
        val canonicalReadKeys = canonicalChapterKeys(providerId, readChapters)
        return chapters.count { chapter ->
            canonicalChapterKey(providerId, buildChapterPath(detailPath, chapter)) in canonicalReadKeys
        }
    }

    var loading by mutableStateOf(false)
        private set

    private fun showError(message: String) {
        Log.e("KomaStream", message)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
