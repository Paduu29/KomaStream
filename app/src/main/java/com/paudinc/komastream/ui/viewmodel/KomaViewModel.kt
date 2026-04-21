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
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.ProviderRegistry
import java.io.File

class KomaViewModel(
    private val context: Context,
    private val providerRegistry: ProviderRegistry,
    private val libraryStore: LibraryStore,
    offlineStore: OfflineChapterStore,
    workManager: WorkManager,
    updater: GitHubReleaseUpdater,
    private val strings: AppStrings,
    backupFileInteractor: BackupFileInteractor = BackupFileInteractor(context.contentResolver),
    libraryActionInteractor: LibraryActionInteractor = LibraryActionInteractor(),
    catalogStateInteractor: CatalogStateInteractor = CatalogStateInteractor(),
    readerActionInteractor: ReaderActionInteractor = ReaderActionInteractor(),
) : ViewModel() {

    val navigationController = NavigationController(
        initialScreen = if (libraryStore.hasSeenProviderPicker()) {
            Screen.Root(RootTab.Home)
        } else {
            Screen.ProviderPicker
        }
    )

    val homeController = HomeController(viewModelScope)
    val catalogController = CatalogController(viewModelScope, catalogStateInteractor)
    val libraryController = LibraryController(
        context = context,
        scope = viewModelScope,
        libraryStore = libraryStore,
        offlineStore = offlineStore,
        workManager = workManager,
        strings = strings,
        libraryActionInteractor = libraryActionInteractor,
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
        catalogController.refreshFilterOptions(currentProvider)
        homeController.refreshHome(currentProvider, ::showError)
        libraryController.refreshOfflineDownloads()
        libraryController.startDownloadProgressTracking()
        updateController.checkForUpdates()
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

    fun openReader(providerId: String, path: String, replace: Boolean = false) {
        readerController.openReader(
            providerId = providerId,
            path = path,
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
        libraryController.toggleFavorite(manga)
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

    fun toggleChapterRead(providerId: String, path: String) {
        libraryController.toggleChapterRead(providerId, path)
    }

    fun setAllChaptersRead(providerId: String, detailPath: String, chapters: List<MangaChapter>, read: Boolean) {
        libraryController.setAllChaptersRead(providerId, detailPath, chapters, read)
    }

    fun setUntilChapterRead(providerId: String, detailPath: String, chapters: List<MangaChapter>, targetValue: Double, read: Boolean) {
        libraryController.setUntilChapterRead(providerId, detailPath, chapters, targetValue, read)
    }

    fun removeReading(manga: SavedManga) {
        libraryController.removeReading(manga)
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
        libraryController.selectProvider(providerId)
        homeController.clearFeed()
        catalogController.resetForProviderChange()
        navigationController.replaceRoot(RootTab.Home)
        catalogController.refreshFilterOptions(currentProvider)
        homeController.refreshHome(currentProvider, ::showError)
    }

    fun updatePageProgress(providerId: String, path: String, index: Int) {
        readerController.updatePageProgress(providerId, path, index)
    }

    fun openAdjacentChapter(providerId: String, currentPath: String, targetPath: String, markCurrentRead: Boolean) {
        readerController.updateChapterReadState(providerId, currentPath, markCurrentRead)
        libraryController.refreshState()
        openReader(providerId, targetPath, replace = true)
    }

    private fun updateLoadingState(isLoading: Boolean) {
        loading = isLoading
    }

    var loading by mutableStateOf(false)
        private set

    private fun showError(message: String) {
        Log.e("KomaStream", message)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
