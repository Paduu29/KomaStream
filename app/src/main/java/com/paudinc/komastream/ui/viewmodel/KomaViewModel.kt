package com.paudinc.komastream.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.paudinc.komastream.data.model.BackupFormat
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.FavoriteMangaStatus
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
import com.paudinc.komastream.provider.providers.ManhwaLatinoProvider
import com.paudinc.komastream.provider.providers.MangadotProvider
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.MyAnimeListApi
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.ProviderRegistry
import com.paudinc.komastream.utils.buildChapterPath
import com.paudinc.komastream.utils.canonicalChapterKey
import com.paudinc.komastream.utils.canonicalChapterKeys
import com.paudinc.komastream.utils.sameMangaPath
import com.paudinc.komastream.utils.resolveMalReadCountForReadChapters
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class KomaViewModel(
    private val context: Context,
    private val providerRegistry: ProviderRegistry,
    private val libraryStore: LibraryStore,
    offlineStore: OfflineChapterStore,
    workManager: WorkManager,
    updater: GitHubReleaseUpdater,
    myAnimeListApi: MyAnimeListApi,
    private val strings: AppStrings,
    initialNavigationStack: List<Screen>? = null,
    backupFileInteractor: BackupFileInteractor = BackupFileInteractor(context.contentResolver),
    libraryActionInteractor: LibraryActionInteractor = LibraryActionInteractor(),
    catalogStateInteractor: CatalogStateInteractor = CatalogStateInteractor(),
    readerActionInteractor: ReaderActionInteractor = ReaderActionInteractor(),
) : ViewModel() {
    private var backgroundWorkStarted = false
    private var pendingBrowserBootstrapProviderId by mutableStateOf<String?>(null)
    private var cloudflareBootstrapRetryBlockedUntilMs: Long = 0L

    val isAwaitingBrowserBootstrap: Boolean
        get() = pendingBrowserBootstrapProviderId != null

    val navigationController = NavigationController(
        initialStack = initialNavigationStack ?: listOf(
            if (libraryStore.selectedProviderIdFast().isNotBlank()) {
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
        api = myAnimeListApi,
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
        offlineStore = offlineStore,
        readerActionInteractor = readerActionInteractor,
        strings = strings,
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
        get() = providerRegistry.get(libraryController.uiState.value.state.selectedProviderId)

    fun startBackgroundWork() {
        if (backgroundWorkStarted) return
        backgroundWorkStarted = true
        libraryController.initialize()
        libraryController.startDownloadProgressTracking()
        updateController.checkForUpdates(openDialogOnUpdate = true)
    }

    fun pushScreen(next: Screen) {
        navigationController.pushScreen(next)
    }

    fun replaceRoot(tab: RootTab) {
        navigationController.replaceRoot(tab)
    }

    fun goBack(): Boolean = navigationController.goBack()

    fun restoreScreenStateIfNeeded(screen: Screen = navigationController.screen) {
        when (screen) {
            is Screen.Detail -> readerController.restoreDetail(
                providerId = screen.providerId,
                path = screen.detailPath,
                navigationController = navigationController,
                screen = screen,
                onLoadingChange = ::updateLoadingState,
                onError = { showError(it.ifBlank { strings.couldNotOpenManga }) },
            )
            is Screen.Reader -> readerController.restoreReader(
                providerId = screen.providerId,
                path = screen.chapterPath,
                libraryState = libraryController.currentState(),
                homeFeed = homeController.uiState.value.feed,
                onLibraryChanged = { libraryController.refreshState() },
                onLoadingChange = ::updateLoadingState,
                onError = { showError(it.ifBlank { strings.couldNotOpenChapter }) },
            )
            is Screen.Community -> readerController.restoreCommunity(
                providerId = screen.providerId,
                path = screen.communityPath,
                navigationController = navigationController,
                screen = screen,
                onLoadingChange = ::updateLoadingState,
                onError = { showError(it.ifBlank { strings.couldNotOpenManga }) },
            )
            else -> Unit
        }
    }

    fun checkForUpdates(notifyIfCurrent: Boolean = false, openDialogOnUpdate: Boolean = false) {
        updateController.checkForUpdates(notifyIfCurrent, openDialogOnUpdate)
    }

    fun downloadUpdate(release: GitHubRelease) {
        updateController.downloadUpdate(release)
    }

    fun installDownloadedUpdate(file: File) {
        updateController.installDownloadedUpdate(file)
    }

    fun refreshHome(providerId: String = currentProvider.id, force: Boolean = false) {
        providerAccessError(providerId)?.let {
            showError(it)
            return
        }
        val provider = providerRegistry.get(providerId)
        if (requiresCloudflareBootstrap(providerId) && !cloudflareReady(providerId)) {
            requestBrowserBootstrap(providerId)
            return
        }
        homeController.refreshHome(provider, ::showError, force = force)
    }

    fun refreshCatalogFilterOptions() {
        val provider = currentProvider
        providerAccessError(provider.id)?.let {
            showError(it)
            return
        }
        if (requiresCloudflareBootstrap(provider.id) && !cloudflareReady(provider.id)) {
            requestBrowserBootstrap(provider.id)
            return
        }
        catalogController.refreshFilterOptions(provider)
    }

    fun searchCatalog(loadMore: Boolean = false) {
        providerAccessError(currentProvider.id)?.let {
            showError(it)
            return
        }
        catalogController.search(
            provider = currentProvider,
            loadMore = loadMore,
            onLoadingChange = ::updateLoadingState,
            onError = { showError(it.ifBlank { strings.couldNotSearchCatalog }) },
        )
    }

    fun openDetail(providerId: String, path: String) {
        providerAccessError(providerId)?.let {
            showError(it)
            return
        }
        readerController.openDetail(
            providerId = providerId,
            path = path,
            navigationController = navigationController,
            onLoadingChange = ::updateLoadingState,
            onError = { showError(it.ifBlank { strings.couldNotOpenManga }) },
        )
    }

    fun openReader(providerId: String, path: String, replace: Boolean = false, resumeProgress: Boolean = true) {
        providerAccessError(providerId)?.let {
            showError(it)
            return
        }
        readerController.openReader(
            providerId = providerId,
            path = path,
            resumeProgress = resumeProgress,
            replace = replace,
            navigationController = navigationController,
            libraryState = libraryController.currentState(),
            homeFeed = homeController.uiState.value.feed,
            onLibraryChanged = { libraryController.refreshState() },
            onLoadingChange = ::updateLoadingState,
            onError = { showError(it.ifBlank { strings.couldNotOpenChapter }) },
        )
    }

    fun openCommunity(providerId: String, path: String) {
        providerAccessError(providerId)?.let {
            showError(it)
            return
        }
        readerController.openCommunity(
            providerId = providerId,
            path = path,
            navigationController = navigationController,
            onLoadingChange = ::updateLoadingState,
            onError = { showError(it.ifBlank { strings.couldNotOpenManga }) },
        )
    }

    fun selectChapterSource(providerId: String, detailPath: String, sourceId: String) {
        readerController.selectChapterSource(providerId, detailPath, sourceId)
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
        viewModelScope.launch {
            syncMalFavoriteState(manga, isFavorite = !wasFavorite)
        }
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
        libraryController.toggleChapterRead(providerId, path, detail) {
            viewModelScope.launch {
                detail?.let {
                    libraryController.updateProgressSnapshot(
                        providerId = providerId,
                        detailPath = it.detailPath,
                        mangaTitle = it.title,
                        coverUrl = it.coverUrl,
                        chapters = it.chapters,
                    )
                    libraryController.refreshStateAsync()
                }
                syncMalChapterReadState(providerId)
            }
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

    fun setFavoriteStatus(manga: SavedManga, status: FavoriteMangaStatus) {
        libraryController.setFavoriteStatus(manga, status)
    }

    fun beginMalConnect(): String =
        malSyncController.beginConnect()

    fun handleMalCallback(uri: Uri?): Boolean =
        malSyncController.handleAuthorizationCallback(uri)

    fun syncMalLibrary() {
        malSyncController.syncLocalLibraryToRemote(currentProvider.id)
    }

    fun syncMalLibraryFromRemote() {
        malSyncController.syncFromRemote(currentProvider.id)
    }

    fun syncMalLibraryBothWays() {
        malSyncController.syncLocalLibraryToRemote(currentProvider.id) {
            malSyncController.syncFromRemote(currentProvider.id)
        }
    }

    fun setMangaMalId(providerId: String, detailPath: String, malMangaId: Long?) {
        malSyncController.setMangaMalId(providerId, detailPath, malMangaId)
    }

    fun getMangaMalId(providerId: String, detailPath: String): Long? {
        return malSyncController.getMangaMalId(providerId, detailPath)
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
        changeAdultContentEnabled(enabled)
    }

    fun changeManhwaLatinoAdultContent(enabled: Boolean) {
        changeAdultContentEnabled(enabled)
    }

    fun changePreferredChapterLanguage(language: AppLanguage) {
        libraryController.changePreferredChapterLanguage(language)
    }

    fun changeAdultContentEnabled(enabled: Boolean) {
        libraryController.changeAdultContentEnabled(enabled)
        providerRegistry.get(MangaBallProvider.PROVIDER_ID).invalidateCaches()
        providerRegistry.get(ManhwaLatinoProvider.PROVIDER_ID).invalidateCaches()
        homeController.clearFeed()
        catalogController.resetForProviderChange()
        if (currentProvider.isAdultContent) {
            refreshCurrentProviderContent(clearVisibleData = true)
        }
    }

    fun changeAdultOnlyProvidersEnabled(enabled: Boolean) {
        libraryController.changeAdultOnlyProvidersEnabled(enabled)
        if (!enabled && currentProvider.isAdultOnly) {
            homeController.clearFeed()
            catalogController.resetForProviderChange()
        }
    }

    fun adultContentPinIsConfigured(): Boolean = libraryStore.adultContentPinIsConfigured()

    fun setAdultContentPin(pin: String) {
        runBlocking(Dispatchers.IO) {
            libraryStore.setAdultContentPin(pin)
        }
    }

    fun verifyAdultContentPin(pin: String): Boolean = libraryStore.verifyAdultContentPin(pin)

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        libraryController.setProviderEnabled(providerId, enabled)
    }

    fun invalidateCloudflareClearance(providerId: String) {
        when (val provider = providerRegistry.get(providerId)) {
            is MangadotProvider -> provider.invalidateCaches()
            is ManhwaLatinoProvider -> provider.invalidateCaches()
            else -> Unit
        }
    }

    fun invalidateCloudflareClearanceAndRetry(providerId: String) {
        if (!requiresCloudflareBootstrap(providerId)) return
        invalidateCloudflareClearance(providerId)
        resetCloudflareBootstrapRetryBlock()
        requestBrowserBootstrap(providerId)
    }

    fun exportBackup(uri: Uri) {
        backupController.exportBackup(uri)
    }

    fun exportDatabaseBackup(uri: Uri) {
        backupController.exportBackup(uri, BackupFormat.DATABASE)
    }

    fun importBackup(uri: Uri) {
        backupController.importBackup(
            uri = uri,
            selectedProviderIdFallback = libraryController.uiState.value.state.selectedProviderId,
        ) {
            libraryController.refreshState()
            libraryController.changeLanguage(libraryController.currentState().appLanguage)
            refreshHome(providerId = libraryController.uiState.value.state.selectedProviderId.ifBlank { currentProvider.id }, force = true)
        }
    }

    fun importDatabaseBackup(uri: Uri) {
        backupController.importBackup(
            uri = uri,
            format = BackupFormat.DATABASE,
            selectedProviderIdFallback = libraryController.uiState.value.state.selectedProviderId,
        ) {
            libraryController.refreshState()
            libraryController.changeLanguage(libraryController.currentState().appLanguage)
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
        if (requiresCloudflareBootstrap(providerId)) {
            if (cloudflareReady(providerId)) {
                pendingBrowserBootstrapProviderId = null
            } else {
                pendingBrowserBootstrapProviderId = providerId
            }
        } else {
            pendingBrowserBootstrapProviderId = null
        }
    }

    fun requestBrowserBootstrap(providerId: String) {
        if (!requiresCloudflareBootstrap(providerId)) return
        if (cloudflareReady(providerId)) {
            pendingBrowserBootstrapProviderId = null
        } else {
            pendingBrowserBootstrapProviderId = providerId
        }
    }

    fun resumePendingBrowserBootstrap(): Boolean {
        val providerId = pendingBrowserBootstrapProviderId ?: return false
        if (!requiresCloudflareBootstrap(providerId)) {
            pendingBrowserBootstrapProviderId = null
            return false
        }
        if (markCloudflareReadyIfCookiesPresent(providerId)) {
            pendingBrowserBootstrapProviderId = null
            blockCloudflareBootstrapRetry()
            viewModelScope.launch {
                delay(1500)
                refreshHome(providerId = providerId, force = true)
            }
            return true
        }
        pendingBrowserBootstrapProviderId = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    waitForCloudflareCookie(providerId)
                }
            }.onSuccess {
                blockCloudflareBootstrapRetry()
                delay(1500)
                refreshHome(providerId = providerId, force = true)
            }.onFailure { throwable ->
                homeController.showEmptyFeed()
                showError(throwable.message ?: "Cloudflare challenge was not fully solved")
            }
        }
        return true
    }

    fun refreshCurrentProviderContent(clearVisibleData: Boolean = false) {
        val provider = currentProvider
        providerAccessError(provider.id)?.let {
            if (clearVisibleData) {
                homeController.clearFeed()
                catalogController.clearResults()
            }
            showError(it)
            return
        }
        if (requiresCloudflareBootstrap(provider.id) && !cloudflareReady(provider.id)) {
            requestBrowserBootstrap(provider.id)
            return
        }
        if (clearVisibleData) {
            homeController.clearFeed()
            catalogController.clearResults()
        }
        refreshCatalogFilterOptions()
        refreshHome(providerId = libraryController.uiState.value.state.selectedProviderId.ifBlank { provider.id })
    }

    fun updatePageProgress(providerId: String, path: String, index: Int, allowAutoReadMark: Boolean = true) {
        viewModelScope.launch {
            readerController.updatePageProgress(
                providerId = providerId,
                path = path,
                index = index,
                allowAutoReadMark = allowAutoReadMark,
                onChapterMarkedRead = { libraryController.refreshState() },
            )
        }
    }

    fun openAdjacentChapter(providerId: String, currentPath: String, targetPath: String, markCurrentRead: Boolean) {
        val activeChapterPath = readerController.uiState.value.readerData
            ?.takeIf { it.providerId == providerId }
            ?.chapterPath
            ?.takeIf { it.isNotBlank() }
            ?: currentPath
        val activeDetail = readerController.uiState.value.selectedDetail?.takeIf {
            it.providerId == providerId && sameMangaPath(providerId, it.detailPath, readerController.uiState.value.readerData?.mangaDetailPath.orEmpty())
        }
        viewModelScope.launch {
            readerController.updateChapterReadState(providerId, activeChapterPath, markCurrentRead)
            if (markCurrentRead && activeDetail != null) {
                readerController.syncReadingSnapshot(
                    providerId = providerId,
                    detail = activeDetail,
                    chapterPath = activeChapterPath,
                    chapterTitle = readerController.uiState.value.readerData?.chapterTitle.orEmpty(),
                )
            }
            libraryController.refreshState()
            openReader(providerId, targetPath, replace = true, resumeProgress = false)
        }
    }

    private fun updateLoadingState(isLoading: Boolean) {
        loading = isLoading
    }

    private fun refreshLibraryUi() {
        libraryController.refreshState()
    }

    private suspend fun syncMalFavoriteState(manga: SavedManga, isFavorite: Boolean) {
        if (!malSyncController.uiState.isConnected) return
        val state = libraryController.currentState()
        val detail = readerController.uiState.value.selectedDetail?.takeIf {
            it.providerId == manga.providerId && it.detailPath == manga.detailPath
        }
        val existingEntry = (state.reading + state.favorites).firstOrNull {
            it.providerId == manga.providerId && it.detailPath == manga.detailPath
        }
        val providerReadChapters = libraryStore.readChaptersForProvider(manga.providerId)
        val readCount = existingEntry?.lastReadChapterNumber ?: detail?.let {
            resolveMalReadCountForReadChapters(it.providerId, it.detailPath, it.chapters, providerReadChapters)
        } ?: 0
        val target = manga.copy(
            title = manga.title.ifBlank { detail?.title.orEmpty() },
            coverUrl = manga.coverUrl.ifBlank { detail?.coverUrl.orEmpty() },
            lastReadChapterNumber = readCount,
        )
        when {
            isFavorite && readCount > 0 -> malSyncController.pushReadProgress(target, readCount)
            isFavorite -> malSyncController.pushFavoriteEntry(target)
            readCount > 0 -> malSyncController.pushReadProgress(target, readCount)
            else -> malSyncController.pushFavoriteEntry(target, isRemoved = true)
        }
    }

    private suspend fun syncMalChapterReadState(providerId: String) {
        if (!malSyncController.uiState.isConnected) return
        val detail = readerController.uiState.value.selectedDetail?.takeIf { it.providerId == providerId } ?: return
        val providerReadChapters = libraryStore.readChaptersForProvider(providerId)
        val state = libraryController.currentState()
        val existingEntry = (state.reading + state.favorites).firstOrNull {
            it.providerId == providerId && it.detailPath == detail.detailPath
        }
        val readCount = existingEntry?.lastReadChapterNumber
            ?: resolveMalReadCountForReadChapters(providerId, detail.detailPath, detail.chapters, providerReadChapters)
        val target = SavedManga(
            providerId = providerId,
            title = detail.title,
            detailPath = detail.detailPath,
            coverUrl = detail.coverUrl,
            lastReadChapterNumber = readCount,
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

    var loading by mutableStateOf(false)
        private set

    private fun showError(message: String) {
        maybeRequestCloudflareBootstrap(message)
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun maybeRequestCloudflareBootstrap(message: String) {
        if (
            requiresCloudflareBootstrap(currentProvider.id) &&
            message.contains("Cloudflare challenge still active", ignoreCase = true) &&
            !isCloudflareBootstrapRetryBlocked()
        ) {
            requestBrowserBootstrap(currentProvider.id)
        }
    }

    private fun requiresCloudflareBootstrap(providerId: String): Boolean {
        return providerId == MangadotProvider.PROVIDER_ID || providerId == ManhwaLatinoProvider.PROVIDER_ID
    }

    private fun cloudflareReady(providerId: String): Boolean {
        return when (val provider = providerRegistry.get(providerId)) {
            is MangadotProvider -> provider.markCloudflareReadyIfCookiesPresent()
            is ManhwaLatinoProvider -> provider.markCloudflareReadyIfCookiesPresent()
            else -> true
        }
    }

    private fun markCloudflareReadyIfCookiesPresent(providerId: String): Boolean {
        return when (val provider = providerRegistry.get(providerId)) {
            is MangadotProvider -> provider.markCloudflareReadyIfCookiesPresent()
            is ManhwaLatinoProvider -> provider.markCloudflareReadyIfCookiesPresent()
            else -> false
        }
    }

    private fun waitForCloudflareCookie(providerId: String): Boolean {
        return when (val provider = providerRegistry.get(providerId)) {
            is MangadotProvider -> provider.waitForCloudflareCookie()
            is ManhwaLatinoProvider -> provider.waitForCloudflareCookie()
            else -> false
        }
    }

    private fun blockCloudflareBootstrapRetry(durationMs: Long = 10_000L) {
        cloudflareBootstrapRetryBlockedUntilMs = System.currentTimeMillis() + durationMs
    }

    private fun resetCloudflareBootstrapRetryBlock() {
        cloudflareBootstrapRetryBlockedUntilMs = 0L
    }

    private fun isCloudflareBootstrapRetryBlocked(): Boolean {
        return System.currentTimeMillis() < cloudflareBootstrapRetryBlockedUntilMs
    }

    private fun providerAccessError(providerId: String): String? {
        val state = libraryController.currentState()
        val provider = providerRegistry.all().firstOrNull { it.id == providerId } ?: return strings.couldNotOpenManga
        if (providerId in state.disabledProviderIds) return strings.couldNotOpenManga
        if (provider.isAdultOnly && !state.adultOnlyProvidersEnabled) return strings.adultOnlyProviderLocked
        return null
    }
}
