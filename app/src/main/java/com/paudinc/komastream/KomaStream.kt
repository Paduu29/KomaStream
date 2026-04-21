package com.paudinc.komastream

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.updater.AppUpdateUiState
import com.paudinc.komastream.updater.GitHubRelease
import com.paudinc.komastream.ui.components.BackupOperationDialog
import com.paudinc.komastream.ui.components.LoadingPlaceholder
import com.paudinc.komastream.ui.components.UpdateAvailableDialog
import com.paudinc.komastream.ui.navigation.RootTab
import com.paudinc.komastream.ui.navigation.Screen
import com.paudinc.komastream.ui.screens.*
import com.paudinc.komastream.ui.viewmodel.KomaViewModel
import com.paudinc.komastream.updater.GitHubReleaseUpdater
import com.paudinc.komastream.utils.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomaStream() {
    val context = LocalContext.current
    val providerRegistry = remember(context) { createDefaultProviderRegistry(context.applicationContext) }
    val libraryStore = remember { LibraryStore(context) }
    val offlineStore = remember { OfflineChapterStore(context) }
    val workManager = remember { WorkManager.getInstance(context) }
    val updater = remember { GitHubReleaseUpdater(context.applicationContext) }
    val strings = appStrings()

    val viewModel = remember {
        KomaViewModel(
            context = context,
            providerRegistry = providerRegistry,
            libraryStore = libraryStore,
            offlineStore = offlineStore,
            workManager = workManager,
            updater = updater,
            strings = strings
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }

    val screen = viewModel.screen
    val libraryController = viewModel.libraryController
    val catalogController = viewModel.catalogController
    val readerController = viewModel.readerController
    val homeController = viewModel.homeController
    val updateController = viewModel.updateController
    val backupController = viewModel.backupController

    val libraryUiState = libraryController.uiState
    val libraryState = libraryUiState.state
    val catalogUiState = catalogController.uiState
    val readerUiState = readerController.uiState
    val homeUiState = homeController.uiState
    val backupOperationState by backupController.operationState.collectAsState()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportBackup(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBackup(it) }
    }

    MaterialTheme(
        colorScheme = if (libraryState.useDarkTheme) darkColorScheme() else lightColorScheme()
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (screen is Screen.Root) {
                        NavigationBar {
                            RootTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = screen.tab == tab,
                                    onClick = { viewModel.replaceRoot(tab) },
                                    label = {
                                        Text(
                                            when (tab) {
                                                RootTab.Home -> strings.home
                                                RootTab.Library -> strings.library
                                                RootTab.Catalog -> strings.catalog
                                            }
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            when (tab) {
                                                RootTab.Home -> Icons.Default.Home
                                                RootTab.Library -> Icons.Default.Favorite
                                                RootTab.Catalog -> Icons.Default.Explore
                                            },
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                },
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                text = when (screen) {
                                    is Screen.Root -> strings.appName
                                    is Screen.Detail -> readerUiState.selectedDetail?.title ?: ""
                                    is Screen.Settings -> strings.settings
                                    is Screen.ProviderPicker -> strings.chooseProvider
                                    is Screen.Reader -> readerUiState.readerData?.let { reader ->
                                        buildReaderTopBarTitle(
                                            mangaTitle = reader.mangaTitle,
                                            chapterTitle = reader.chapterTitle,
                                            currentPageIndex = readerUiState.currentPageIndex,
                                            totalPages = reader.pages.size,
                                        )
                                    }.orEmpty()
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            if (screen !is Screen.Root && screen !is Screen.ProviderPicker) {
                                IconButton(onClick = { viewModel.goBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                                }
                            }
                        },
                        actions = {
                            if (screen is Screen.Root) {
                                IconButton(onClick = { viewModel.pushScreen(Screen.ProviderPicker) }) {
                                    AsyncImage(
                                        model = viewModel.currentProvider.logoUrl,
                                        contentDescription = viewModel.currentProvider.displayName,
                                        modifier = Modifier.size(24.dp).clip(CircleShape)
                                    )
                                }
                                IconButton(onClick = { viewModel.pushScreen(Screen.Settings) }) {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                }
                            }
                        }
                    )
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    when (screen) {
                        is Screen.Root -> {
                            when (screen.tab) {
                                RootTab.Home -> HomeScreen(
                                    feed = homeUiState.feed,
                                    strings = strings,
                                    onOpenManga = { id, path -> viewModel.openDetail(id, path) },
                                    onOpenChapter = { id, path -> viewModel.openReader(id, path) }
                                )
                                RootTab.Library -> LibraryScreen(
                                    libraryState = libraryState,
                                    strings = strings,
                                    selectedTab = libraryUiState.selectedTab,
                                    onSelectTab = { viewModel.selectLibraryTab(it) },
                                    onOpenManga = { id, path -> viewModel.openDetail(id, path) },
                                    onOpenChapter = { id, path -> viewModel.openReader(id, path) },
                                    onRemoveFromContinueReading = { viewModel.removeReading(it) },
                                    onRemoveFromFavorites = { viewModel.toggleFavorite(it) }
                                )
                                RootTab.Catalog -> CatalogScreen(
                                    strings = strings,
                                    query = catalogUiState.query,
                                    categories = catalogUiState.filterOptions.categories,
                                    sortOptions = catalogUiState.filterOptions.sortOptions,
                                    statusOptions = catalogUiState.filterOptions.statusOptions,
                                    selectedCategoryIds = catalogUiState.selectedCategoryIds,
                                    selectedSortOptionId = catalogUiState.selectedSortOptionId,
                                    selectedStatusOptionId = catalogUiState.selectedStatusOptionId,
                                    onlyFavorites = catalogUiState.onlyFavorites,
                                    results = catalogUiState.results,
                                    hasMoreResults = catalogUiState.hasMoreResults,
                                    isLoadingMore = catalogUiState.isLoadingMore,
                                    onQueryChange = { viewModel.updateCatalogQuery(it) },
                                    onToggleCategory = { id -> viewModel.toggleCatalogCategory(id) },
                                    onSelectSort = { viewModel.selectCatalogSort(it) },
                                    onSelectStatus = { viewModel.selectCatalogStatus(it) },
                                    onToggleOnlyFavorites = { viewModel.setCatalogOnlyFavorites(it) },
                                    onClearFilters = { viewModel.clearCatalogFilters() },
                                    onSearch = { viewModel.searchCatalog() },
                                    onLoadMore = { viewModel.searchCatalog(loadMore = true) },
                                    onOpen = { id, path -> viewModel.openDetail(id, path) }
                                )
                            }
                        }
                        is Screen.Detail -> {
                            readerUiState.selectedDetail?.let { detail ->
                                DetailScreen(
                                    strings = strings,
                                    detail = detail,
                                    isFavorite = libraryStore.isFavorite(detail.providerId, detail.detailPath),
                                    autoJumpToUnread = libraryState.autoJumpToUnread,
                                    readChapters = libraryState.readChapters,
                                    lastOpenedChapterPath = libraryStore.read().reading.find { it.providerId == detail.providerId && it.detailPath == detail.detailPath }?.lastChapterPath ?: "",
                                    downloadedChapters = libraryUiState.downloadedChapterPaths,
                                    downloadProgress = libraryController.downloadProgress,
                                    isBulkUpdatingChapters = libraryUiState.isBulkUpdatingChapters,
                                    onToggleFavorite = { viewModel.toggleFavorite(SavedManga(detail.providerId, detail.title, detail.detailPath, detail.coverUrl)) },
                                    onToggleChapterRead = { path -> viewModel.toggleChapterRead(detail.providerId, path) },
                                    onSetAllChaptersRead = { read -> viewModel.setAllChaptersRead(detail.providerId, detail.detailPath, detail.chapters, read) },
                                    onSetUntilChapterRead = { value, read -> viewModel.setUntilChapterRead(detail.providerId, detail.detailPath, detail.chapters, value, read) },
                                    onToggleChapterDownload = { path, isDownloaded ->
                                        if (isDownloaded) viewModel.removeDownloadedChapter(detail.providerId, path)
                                        else viewModel.downloadChapter(detail.providerId, path)
                                    },
                                    onReadChapter = { path -> viewModel.openReader(detail.providerId, path) }
                                )
                            } ?: LoadingPlaceholder()
                        }
                        is Screen.Reader -> {
                            readerUiState.readerData?.let { data ->
                                ReaderScreen(
                                    strings = strings,
                                    reader = data,
                                    offlineStore = offlineStore,
                                    initialPageIndex = readerUiState.initialPageIndex,
                                    isDownloaded = libraryUiState.downloadedChapterPaths.contains(data.chapterPath),
                                    downloadPercent = libraryController.downloadProgress[data.chapterPath],
                                    onPagePositionChanged = { index -> viewModel.updatePageProgress(data.providerId, data.chapterPath, index) },
                                    onToggleDownload = {
                                        if (libraryUiState.downloadedChapterPaths.contains(data.chapterPath)) viewModel.removeDownloadedChapter(data.providerId, data.chapterPath)
                                        else viewModel.downloadChapter(data.providerId, data.chapterPath)
                                    },
                                    onOpenChapter = { currentPath, targetPath, markCurrentRead ->
                                        viewModel.openAdjacentChapter(data.providerId, currentPath, targetPath, markCurrentRead)
                                    },
                                    onOpenManga = { path -> viewModel.openDetail(data.providerId, path) }
                                )
                            } ?: LoadingPlaceholder()
                        }
                        is Screen.Settings -> SettingsScreen(
                            strings = strings,
                            appLanguage = libraryState.appLanguage,
                            useDarkTheme = libraryState.useDarkTheme,
                            autoJumpToUnread = libraryState.autoJumpToUnread,
                            versionName = BuildConfig.VERSION_NAME,
                            updateState = updateController.updateState,
                            onLanguageChange = { viewModel.changeLanguage(it) },
                            onThemeChange = { viewModel.changeTheme(it) },
                            onAutoJumpToUnreadChange = { viewModel.changeAutoJumpToUnread(it) },
                            onExportBackup = { exportLauncher.launch("KomaStream_Backup.json") },
                            onImportBackup = { importLauncher.launch(arrayOf("application/json")) },
                            onCheckForUpdates = { viewModel.checkForUpdates(notifyIfCurrent = true) },
                            onDownloadUpdate = {
                                if (updateController.updateState is AppUpdateUiState.Available) {
                                    viewModel.downloadUpdate((updateController.updateState as AppUpdateUiState.Available).release)
                                }
                            },
                            onInstallUpdate = {
                                if (updateController.updateState is AppUpdateUiState.Downloaded) {
                                    viewModel.installDownloadedUpdate((updateController.updateState as AppUpdateUiState.Downloaded).file)
                                }
                            },
                            onOpenReleasePage = {
                                val release: GitHubRelease? = when (val state = updateController.updateState) {
                                    is AppUpdateUiState.Available -> state.release
                                    is AppUpdateUiState.Downloading -> state.release
                                    is AppUpdateUiState.Downloaded -> state.release
                                    else -> null
                                }
                                release?.let {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it.htmlUrl))
                                    context.startActivity(intent)
                                }
                            }
                        )
                        is Screen.ProviderPicker -> ProviderPickerScreen(
                            strings = strings,
                            selectedProviderId = libraryState.selectedProviderId,
                            providersByLanguage = providerRegistry.groupedByLanguage(),
                            onSelectProvider = { viewModel.selectProvider(it) },
                            onOpenProviderSite = { url ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        )
                    }

                    if (viewModel.loading) {
                        LoadingPlaceholder()
                    }
                }
            }
        }
    }

    if (updateController.isDialogVisible) {
        UpdateAvailableDialog(
            strings = strings,
            updateState = updateController.updateState,
            onDismiss = { updateController.isDialogVisible = false },
            onDownloadUpdate = { /* handled via settings or notification usually */ },
            onInstallUpdate = { /* handled via settings or notification usually */ },
            onOpenReleasePage = { /* handled via settings or notification usually */ }
        )
    }

    BackupOperationDialog(
        strings = strings,
        state = backupOperationState,
        onConfirm = { viewModel.dismissBackupOperationDialog() },
    )

    BackHandler {
        if (!viewModel.goBack()) {
            (context as? Activity)?.finish()
        }
    }
}

private fun buildReaderTopBarTitle(
    mangaTitle: String,
    chapterTitle: String,
    currentPageIndex: Int,
    totalPages: Int,
): String {
    val chapterLabel = chapterTitle
        .removePrefix(mangaTitle)
        .trim()
        .trimStart('-', ':', '|')
        .trim()
        .ifBlank { chapterTitle }
    val safeTotalPages = totalPages.coerceAtLeast(0)
    val currentPage = if (safeTotalPages == 0) 0 else currentPageIndex.coerceIn(0, safeTotalPages - 1) + 1
    return listOf(
        mangaTitle.trim(),
        chapterLabel.trim(),
        "$currentPage/$safeTotalPages",
    ).filter { it.isNotBlank() }.joinToString(" ")
}
