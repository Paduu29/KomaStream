package com.paudinc.komastream

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkManager
import coil.compose.AsyncImage
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.updater.AppUpdateUiState
import com.paudinc.komastream.updater.GitHubRelease
import com.paudinc.komastream.ui.components.BackupOperationDialog
import com.paudinc.komastream.ui.components.BrowserBootstrapDialog
import com.paudinc.komastream.ui.components.DetailLoadingPlaceholder
import com.paudinc.komastream.ui.components.LoadingPlaceholder
import com.paudinc.komastream.ui.components.UpdateAvailableDialog
import com.paudinc.komastream.ui.navigation.LibraryTab
import com.paudinc.komastream.ui.navigation.RootTab
import com.paudinc.komastream.ui.navigation.Screen
import com.paudinc.komastream.ui.navigation.ScreenStackSaver
import com.paudinc.komastream.ui.screens.*
import com.paudinc.komastream.ui.viewmodel.KomaViewModel
import com.paudinc.komastream.ui.viewmodel.MyAnimeListPendingImportSource
import com.paudinc.komastream.ui.viewmodel.MyAnimeListPendingImport
import com.paudinc.komastream.updater.GitHubReleaseUpdater
import com.paudinc.komastream.utils.*
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paudinc.komastream.ui.components.MangadotAwareAsyncImage
import com.paudinc.komastream.provider.providers.KaganeProvider
import com.paudinc.komastream.provider.providers.MangaBallProvider
import com.paudinc.komastream.provider.providers.MangadotProvider
import com.paudinc.komastream.provider.providers.ManhwaLatinoProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomaStream() {
    val context = LocalContext.current
    val appGraph = remember(context.applicationContext) {
        (context.applicationContext as KomaStreamApp).appGraph
    }
    val providerRegistry = appGraph.providerRegistry
    val libraryStore = appGraph.libraryStore
    val offlineStore = appGraph.offlineStore
    val updater = appGraph.updater
    val strings = appStrings()
    var savedNavigationStack by rememberSaveable(stateSaver = ScreenStackSaver) {
        mutableStateOf(
            listOf(
                if (libraryStore.selectedProviderIdFast().isNotBlank()) Screen.Root(RootTab.Home) else Screen.ProviderPicker
            )
        )
    }

    val viewModel: KomaViewModel = viewModel(
        factory = KomaViewModelFactory(
            appContext = appGraph.appContext,
            providerRegistry = appGraph.providerRegistry,
            libraryStore = appGraph.libraryStore,
            offlineStore = appGraph.offlineStore,
            workManager = appGraph.workManager,
            updater = appGraph.updater,
            myAnimeListApi = appGraph.myAnimeListApi,
            strings = strings,
            initialNavigationStack = savedNavigationStack,
        )
    )
    val saveableStateHolder = rememberSaveableStateHolder()
    var previousSaveableScreenKeys by remember { mutableStateOf<List<String>>(emptyList()) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var lastRootBackPressAt by rememberSaveable { mutableLongStateOf(0L) }

    val libraryController = viewModel.libraryController
    val catalogController = viewModel.catalogController
    val readerController = viewModel.readerController
    val homeController = viewModel.homeController
    val updateController = viewModel.updateController
    val backupController = viewModel.backupController
    val malSyncController = viewModel.malSyncController

    val navigationStack by viewModel.navigationController.navigationStack.collectAsStateWithLifecycle()
    val screen = navigationStack.last()
    val libraryUiState by libraryController.uiState.collectAsStateWithLifecycle()
    val libraryState = libraryUiState.state
    val allProvidersLibraryState = libraryUiState.allProvidersState
    val libraryLookupState = libraryUiState.lookup
    val catalogUiState by catalogController.uiState.collectAsStateWithLifecycle()
    val readerUiState by readerController.uiState.collectAsStateWithLifecycle()
    val homeUiState by homeController.uiState.collectAsStateWithLifecycle()
    val backupOperationState by backupController.operationState.collectAsStateWithLifecycle()
    val malUiState = malSyncController.uiState
    val malCallbackUri by AppDeepLinkStore.malCallbackUri.collectAsStateWithLifecycle()
    val isMalSyncBlocking = malUiState.isSyncing
    val lifecycleOwner = LocalLifecycleOwner.current

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportBackup(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBackup(it) }
    }
    val exportDatabaseLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { viewModel.exportDatabaseBackup(it) }
    }
    val importDatabaseLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importDatabaseBackup(it) }
    }
    val activity = context as? Activity
    val currentProvider = viewModel.currentProvider
    val favoriteLookup: (String, String) -> Boolean = { providerId, detailPath ->
        libraryLookupState.favoriteKeys.contains("$providerId::${canonicalMangaPathKey(providerId, detailPath)}")
    }
    val chapterProgressLookup: (String, String) -> Int = { providerId, chapterPath ->
        libraryLookupState.chapterProgressByKey[qualifyProviderValue(providerId, chapterPath)] ?: 0
    }
    val cachedDetailLookup: (String, String) -> com.paudinc.komastream.data.model.MangaDetail? = { providerId, detailPath ->
        libraryLookupState.cachedDetailByKey["$providerId::${canonicalMangaPathKey(providerId, detailPath)}"]
    }
    val readChaptersLookup: (String) -> Set<String> = { providerId ->
        libraryLookupState.readChaptersByProvider[providerId].orEmpty()
    }
    val currentRelease: GitHubRelease? = when (val state = updateController.updateState) {
        is AppUpdateUiState.Available -> state.release
        is AppUpdateUiState.Downloading -> state.release
        is AppUpdateUiState.Downloaded -> state.release
        else -> null
    }
    var browserBootstrapUrl by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(malCallbackUri) {
        if (viewModel.handleMalCallback(malCallbackUri)) {
            AppDeepLinkStore.clearMalCallback()
            activity?.intent?.let { currentIntent ->
                activity.intent = Intent(currentIntent).apply { data = null }
            }
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.resumePendingBrowserBootstrap()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val openReleasePage: () -> Unit = {
        val release = currentRelease
        if (release != null) {
            updater.openReleasePage(release)
        } else {
            updater.openReleasePage(BuildConfig.VERSION_NAME)
        }
    }
    val downloadUpdate: () -> Unit = {
        val availableState = updateController.updateState as? AppUpdateUiState.Available
        if (availableState != null) {
            viewModel.downloadUpdate(availableState.release)
        }
    }
    val installUpdate: () -> Unit = {
        val downloadedState = updateController.updateState as? AppUpdateUiState.Downloaded
        if (downloadedState != null) {
            viewModel.installDownloadedUpdate(downloadedState.file)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.startBackgroundWork()
    }

    LaunchedEffect(libraryState.selectedProviderId) {
        if (libraryState.selectedProviderId.isBlank()) {
            if (screen !is Screen.ProviderPicker) {
                viewModel.pushScreen(Screen.ProviderPicker)
            }
        } else {
            if (screen is Screen.ProviderPicker) {
                viewModel.replaceRoot(RootTab.Home)
            }
            if (viewModel.isAwaitingBrowserBootstrap) {
                viewModel.refreshCatalogFilterOptions()
            } else {
                viewModel.refreshCurrentProviderContent(clearVisibleData = true)
            }
        }
    }

    LaunchedEffect(viewModel.isAwaitingBrowserBootstrap, currentProvider.id) {
        if (viewModel.isAwaitingBrowserBootstrap &&
            (currentProvider is MangadotProvider ||
                currentProvider is KaganeProvider ||
                currentProvider is ManhwaLatinoProvider ||
                currentProvider is MangaBallProvider)
        ) {
            val providerUrl = when (val provider = currentProvider) {
                is MangadotProvider -> if (!provider.markCloudflareReadyIfCookiesPresent()) provider.websiteUrl else null
                is KaganeProvider -> if (!provider.markCloudflareReadyIfCookiesPresent()) provider.websiteUrl else null
                is ManhwaLatinoProvider -> if (!provider.markCloudflareReadyIfCookiesPresent()) provider.websiteUrl else null
                is MangaBallProvider -> provider.websiteUrl
                else -> null
            }
            if (providerUrl != null && browserBootstrapUrl != providerUrl) {
                browserBootstrapUrl = providerUrl
            }
        }
    }

    LaunchedEffect(navigationStack) {
        val currentKeys = navigationStack.map(Screen::saveableKey)
        previousSaveableScreenKeys
            .filterNot(currentKeys::contains)
            .forEach(saveableStateHolder::removeState)
        previousSaveableScreenKeys = currentKeys
        savedNavigationStack = navigationStack
    }

    LaunchedEffect(screen, readerUiState.readerData, readerUiState.selectedDetail) {
        when (screen) {
            is Screen.Reader -> {
                val activeReader = readerUiState.readerData
                if (activeReader?.providerId != screen.providerId || activeReader.chapterPath != screen.chapterPath) {
                    viewModel.restoreScreenStateIfNeeded(screen)
                }
            }
            is Screen.Detail -> {
                val activeDetail = readerUiState.selectedDetail
                if (
                    (activeDetail?.providerId != screen.providerId || activeDetail.detailPath != screen.detailPath) &&
                    readerUiState.requestedDetailPath != screen.detailPath
                ) {
                    viewModel.restoreScreenStateIfNeeded(screen)
                }
            }
            else -> Unit
        }
    }

    val colorScheme = if (libraryState.useDarkTheme) komaDarkColorScheme() else komaLightColorScheme()
    val typography = komaTypography()

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize()) {
                Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (screen is Screen.Root) {
                        NavigationBar(
                            modifier = Modifier
                                .navigationBarsPadding()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(26.dp)),
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            tonalElevation = 0.dp,
                        ) {
                            RootTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = screen.tab == tab,
                                    onClick = {
                                        if (tab == RootTab.Home && screen is Screen.Root && screen.tab == RootTab.Home) {
                                            viewModel.refreshCurrentProviderContent(clearVisibleData = true)
                                        } else {
                                            viewModel.replaceRoot(tab)
                                            if (tab == RootTab.Home) {
                                                viewModel.refreshCurrentProviderContent(clearVisibleData = true)
                                            }
                                        }
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    label = {
                                        Text(
                                            when (tab) {
                                                RootTab.Home -> strings.home
                                                RootTab.Library -> strings.library
                                                RootTab.Catalog -> strings.catalog
                                                RootTab.Favorites -> strings.favorites
                                                RootTab.Settings -> strings.settingsTab
                                            },
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center,
                                        )
                                    },
                                    icon = {
                                        Icon(
                                            when (tab) {
                                                RootTab.Home -> Icons.Default.Home
                                                RootTab.Library -> Icons.AutoMirrored.Filled.MenuBook
                                                RootTab.Catalog -> Icons.Default.Search
                                                RootTab.Favorites -> Icons.Default.FavoriteBorder
                                                RootTab.Settings -> Icons.Default.Settings
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
                    if (screen !is Screen.Reader) {
                        CenterAlignedTopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            title = {
                                when {
                                    screen is Screen.Root && screen.tab != RootTab.Settings -> {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Image(
                                                painter = painterResource(R.drawable.app_logo),
                                                contentDescription = strings.appName,
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(RoundedCornerShape(8.dp)),
                                            )
                                            Row {
                                                Text(
                                                    text = "KOMA",
                                                    style = MaterialTheme.typography.titleLarge,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                )
                                                Text(
                                                    text = "STREAM",
                                                    style = MaterialTheme.typography.titleLarge,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        Text(
                                            text = when (screen) {
                                                is Screen.Detail -> readerUiState.selectedDetail?.title ?: ""
                                                is Screen.Settings -> strings.settings
                                                Screen.SettingsLanguage -> strings.languageLabel
                                                Screen.SettingsTheme -> strings.theme
                                                Screen.SettingsChapterLanguage -> strings.preferredChapterLanguage
                                                Screen.SettingsReader -> strings.reader
                                                Screen.SettingsContent -> strings.contentAccess
                                                Screen.SettingsUpdates -> strings.updates
                                                Screen.SettingsMyAnimeList -> strings.myAnimeList
                                                Screen.SettingsBackup -> strings.backup
                                                is Screen.ProviderPicker -> strings.chooseProvider
                                                is Screen.Reader -> readerUiState.readerData?.let { reader ->
                                                    buildReaderTopBarTitle(
                                                        mangaTitle = reader.mangaTitle,
                                                        chapterTitle = reader.chapterTitle,
                                                        currentPageIndex = readerUiState.currentPageIndex,
                                                        totalPages = reader.pages.size,
                                                    )
                                                }.orEmpty()
                                                is Screen.HomeSection -> homeUiState.feed?.sections
                                                    ?.firstOrNull { it.id == screen.sectionId }
                                                    ?.let { homeSectionTitle(it, strings) }
                                                    .orEmpty()
                                                is Screen.Community -> readerUiState.selectedCommunityPage?.title
                                                    ?: screen.communityPath.substringAfterLast('/').ifBlank { strings.home }
                                                is Screen.Root -> when (screen.tab) {
                                                    RootTab.Home -> strings.home
                                                    RootTab.Library -> strings.library
                                                    RootTab.Catalog -> strings.catalog
                                                    RootTab.Favorites -> strings.favorites
                                                    RootTab.Settings -> strings.settings
                                                }
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
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
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            MangadotAwareAsyncImage(
                                                model = viewModel.currentProvider.logoUrl,
                                                contentDescription = viewModel.currentProvider.displayName,
                                                modifier = Modifier.size(24.dp).clip(CircleShape),
                                                placeholder = painterResource(R.drawable.app_logo),
                                                error = painterResource(
                                                    when (viewModel.currentProvider.id) {
                                                        "mangatube-de" -> {
                                                            R.drawable.mt_logo
                                                        }
                                                        "akaicomic-en" -> {
                                                            R.drawable.akai_comic
                                                        }
                                                        else -> {
                                                            R.drawable.app_logo
                                                        }
                                                    }
                                                ),
                                            )
                                        }
                                    }
                                } else if (screen is Screen.ProviderPicker) {
                                    IconButton(onClick = { viewModel.pushScreen(Screen.SettingsContent) }) {
                                        Icon(Icons.Default.Lock, contentDescription = null)
                                    }
                                    IconButton(onClick = { viewModel.pushScreen(Screen.Settings) }) {
                                        Icon(Icons.Default.Settings, contentDescription = null)
                                    }
                                }
                            }
                        )
                    }
                }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        saveableStateHolder.SaveableStateProvider(screen.saveableKey()) {
                            when (screen) {
                            is Screen.Root -> {
                                when (screen.tab) {

                                RootTab.Home -> HomeScreen(
                                    providerId = currentProvider.id,
                                    providerName = currentProvider.displayName,
                                    feed = homeUiState.feed,
                                    reading = libraryState.reading,
                                    readChapters = libraryState.readChapters,
                                    chapterProgress = chapterProgressLookup,
                                    strings = strings,
                                    onOpenManga = { id, path -> viewModel.openDetail(id, path) },
                                    onOpenChapter = { id, path -> viewModel.openReader(id, path) },
                                    onOpenSection = { viewModel.pushScreen(Screen.HomeSection(it)) },
                                    onOpenUrl = { url ->
                                        val communityPath = Uri.parse(url).path?.takeIf { it.isNotBlank() }
                                        if (currentProvider.id == "mangadotnet-en" && communityPath != null) {
                                            viewModel.openCommunity(currentProvider.id, communityPath)
                                        } else {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    },
                                    onAddToReading = { viewModel.addToReading(it) },
                                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                                    isFavorite = favoriteLookup,
                                    isRefreshing = homeUiState.isRefreshing,
                                    onRefresh = {
                                        homeController.refreshHome(
                                            provider = currentProvider,
                                            onError = { message ->
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar(message)
                                                }
                                            }
                                        )
                                    },
                                    onSolveCloudflare = when (currentProvider) {
                                        is MangadotProvider, is ManhwaLatinoProvider -> {
                                            { viewModel.invalidateCloudflareClearanceAndRetry(currentProvider.id) }
                                        }
                                        is KaganeProvider -> {
                                            { viewModel.invalidateCloudflareClearanceAndRetry(currentProvider.id) }
                                        }
                                        else -> null
                                    },
                                )
                                    RootTab.Library -> LibraryScreen(
                                        libraryState = allProvidersLibraryState,
                                        strings = strings,
                                        selectedTab = LibraryTab.ContinueReading,
                                        providerNameForId = { providerId ->
                                            runCatching { providerRegistry.get(providerId).displayName }.getOrDefault(providerId)
                                        },
                                        chapterCountForManga = { providerId, detailPath ->
                                            cachedDetailLookup(providerId, detailPath)?.chapters?.size
                                        },
                                        resolveChapterPathForManga = { providerId, detailPath, progressNumber, fallbackPath ->
                                            val cachedDetail = cachedDetailLookup(providerId, detailPath)
                                            cachedDetail?.chapters?.let { chapters ->
                                                com.paudinc.komastream.utils.resolveChapterPathForProgressReference(
                                                    providerId = providerId,
                                                    detailPath = detailPath,
                                                    chapters = chapters,
                                                    progressChapterNumber = progressNumber,
                                                    fallbackChapterPath = fallbackPath,
                                                )
                                            }
                                        },
                                        onSelectTab = {
                                            viewModel.replaceRoot(
                                                if (it == LibraryTab.ContinueReading) RootTab.Library else RootTab.Favorites
                                            )
                                        },
                                        onOpenManga = { id, path -> viewModel.openDetail(id, path) },
                                        onOpenChapter = { id, path -> viewModel.openReader(id, path, resumeProgress = true) },
                                        onRemoveFromContinueReading = { viewModel.removeReading(it) },
                                        onRemoveFromFavorites = { viewModel.toggleFavorite(it) },
                                        onSetFavoriteStatus = { manga, status -> viewModel.setFavoriteStatus(manga, status) },
                                    )
                                    RootTab.Catalog -> CatalogScreen(
                                        strings = strings,
                                        providerId = currentProvider.id,
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
                                    onOpen = { id, path -> viewModel.openDetail(id, path) },
                                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                                    isFavorite = favoriteLookup,
                                )
                                    RootTab.Favorites -> LibraryScreen(
                                        libraryState = allProvidersLibraryState,
                                        strings = strings,
                                        selectedTab = LibraryTab.Favorites,
                                providerNameForId = { providerId ->
                                    runCatching { providerRegistry.get(providerId).displayName }.getOrDefault(providerId)
                                },
                                chapterCountForManga = { providerId, detailPath ->
                                    cachedDetailLookup(providerId, detailPath)?.chapters?.size
                                },
                                resolveChapterPathForManga = { providerId, detailPath, progressNumber, fallbackPath ->
                                    val cachedDetail = cachedDetailLookup(providerId, detailPath)
                                    cachedDetail?.chapters?.let { chapters ->
                                        com.paudinc.komastream.utils.resolveChapterPathForProgressReference(
                                            providerId = providerId,
                                            detailPath = detailPath,
                                            chapters = chapters,
                                            progressChapterNumber = progressNumber,
                                            fallbackChapterPath = fallbackPath,
                                        )
                                    }
                                },
                                onSelectTab = {
                                    viewModel.replaceRoot(
                                        if (it == LibraryTab.ContinueReading) RootTab.Library else RootTab.Favorites
                                    )
                                },
                                onOpenManga = { id, path -> viewModel.openDetail(id, path) },
                                onOpenChapter = { id, path -> viewModel.openReader(id, path) },
                                onRemoveFromContinueReading = { viewModel.removeReading(it) },
                                onRemoveFromFavorites = { viewModel.toggleFavorite(it) },
                                onSetFavoriteStatus = { manga, status -> viewModel.setFavoriteStatus(manga, status) },
                            )
                                    RootTab.Settings -> SettingsScreen(
                                        strings = strings,
                                        onOpenLanguage = { viewModel.pushScreen(Screen.SettingsLanguage) },
                                        onOpenTheme = { viewModel.pushScreen(Screen.SettingsTheme) },
                                        onOpenChapterLanguage = { viewModel.pushScreen(Screen.SettingsChapterLanguage) },
                                        onOpenReader = { viewModel.pushScreen(Screen.SettingsReader) },
                                        onOpenContent = { viewModel.pushScreen(Screen.SettingsContent) },
                                        onOpenUpdates = { viewModel.pushScreen(Screen.SettingsUpdates) },
                                        onOpenMyAnimeList = { viewModel.pushScreen(Screen.SettingsMyAnimeList) },
                                        onOpenBackup = { viewModel.pushScreen(Screen.SettingsBackup) },
                                    )
                                }
                            }
                            is Screen.Detail -> {
                                readerUiState.selectedDetail?.let { detail ->
                                        val detailReadChapters = readChaptersLookup(detail.providerId)
                                        val detailReading = allProvidersLibraryState.reading.firstOrNull {
                                            it.providerId == detail.providerId && sameMangaPath(detail.providerId, it.detailPath, detail.detailPath)
                                        }
                                        val detailSavedManga = allProvidersLibraryState.reading.firstOrNull {
                                            it.providerId == detail.providerId && sameMangaPath(detail.providerId, it.detailPath, detail.detailPath)
                                        } ?: allProvidersLibraryState.favorites.firstOrNull {
                                            it.providerId == detail.providerId && sameMangaPath(detail.providerId, it.detailPath, detail.detailPath)
                                        }
                                    DetailScreen(
                                        strings = strings,
                                        detail = detail,
                                        isFavorite = allProvidersLibraryState.favorites.any {
                                            it.providerId == detail.providerId && it.detailPath == detail.detailPath
                                        },
                                        autoJumpToUnread = libraryState.autoJumpToUnread,
                                        readChapters = detailReadChapters,
                                        lastOpenedChapterPath = detailReading?.lastChapterPath ?: "",
                                        isChapterDownloaded = { path -> offlineStore.isChapterDownloaded(detail.providerId, path) },
                                        downloadProgress = libraryController.downloadProgress,
                                        isBulkUpdatingChapters = libraryUiState.isBulkUpdatingChapters,
                                        malMangaId = detailSavedManga?.malMangaId,
                                        showMalIdEditor = screen.isMalIdEditorOpen,
                                        onOpenMalIdEditor = {
                                            viewModel.pushScreen(
                                                Screen.Detail(
                                                    providerId = detail.providerId,
                                                    detailPath = detail.detailPath,
                                                    isMalIdEditorOpen = true,
                                                )
                                            )
                                        },
                                        onCloseMalIdEditor = { viewModel.goBack() },
                                        onSetMalMangaId = { malMangaId ->
                                            viewModel.setMangaMalId(detail.providerId, detail.detailPath, malMangaId)
                                        },
                                        onToggleFavorite = { viewModel.toggleFavorite(SavedManga(detail.providerId, detail.title, detail.detailPath, detail.coverUrl)) },
                                        onToggleChapterRead = { path -> viewModel.toggleChapterRead(detail.providerId, path, detail) },
                                        onSetAllChaptersRead = { read -> viewModel.setAllChaptersRead(detail.providerId, detail.detailPath, detail.title, detail.coverUrl, detail.chapters, read) },
                                        onSetUntilChapterRead = { value, read -> viewModel.setUntilChapterRead(detail.providerId, detail.detailPath, detail.title, detail.coverUrl, detail.chapters, value, read) },
                                        onDownloadAllChapters = { chapterPaths ->
                                            viewModel.downloadChapters(detail.providerId, detail.detailPath, detail.title, chapterPaths)
                                        },
                                        onCancelAllDownloads = { viewModel.cancelAllDownloads() },
                                        isBatchDownloading = libraryUiState.isBatchDownloading,
                                        onToggleChapterDownload = { path, isDownloaded ->
                                            if (isDownloaded) viewModel.removeDownloadedChapter(detail.providerId, path)
                                            else viewModel.downloadChapter(detail.providerId, path)
                                        },
                                        onReadChapter = { path -> viewModel.openReader(detail.providerId, path) },
                                        onSelectChapterSource = { sourceId -> viewModel.selectChapterSource(detail.providerId, detail.detailPath, sourceId) },
                                        onSolveCloudflare = when (detail.providerId) {
                                            KaganeProvider.PROVIDER_ID,
                                            MangadotProvider.PROVIDER_ID,
                                            ManhwaLatinoProvider.PROVIDER_ID,
                                            MangaBallProvider.PROVIDER_ID -> {
                                                { viewModel.invalidateCloudflareClearanceAndRetry(detail.providerId) }
                                            }
                                            else -> null
                                        },
                                    )
                                } ?: DetailLoadingPlaceholder(strings)
                            }
                            is Screen.Reader -> {
                                readerUiState.readerData?.let { data ->
                                    ReaderScreen(
                                        strings = strings,
                                        reader = data,
                                        offlineStore = offlineStore,
                                        initialPageIndex = readerUiState.initialPageIndex,
                                        isDownloaded = offlineStore.isChapterDownloaded(data.providerId, data.chapterPath),
                                        downloadPercent = libraryController.downloadProgress[data.chapterPath],
                                        onPagePositionChanged = { index, allowAutoReadMark ->
                                            viewModel.updatePageProgress(
                                                data.providerId,
                                                data.chapterPath,
                                                index,
                                                allowAutoReadMark,
                                            )
                                        },
                                        onToggleDownload = {
                                            if (offlineStore.isChapterDownloaded(data.providerId, data.chapterPath)) {
                                                viewModel.removeDownloadedChapter(data.providerId, data.chapterPath)
                                            }
                                            else viewModel.downloadChapter(data.providerId, data.chapterPath)
                                        },
                                        isRead = canonicalChapterKey(data.providerId, data.chapterPath) in canonicalChapterKeys(
                                            data.providerId,
                                            readChaptersLookup(data.providerId),
                                        ),
                                        onToggleRead = { viewModel.toggleChapterRead(data.providerId, data.chapterPath) },
                                        onOpenChapter = { currentPath, targetPath, markCurrentRead ->
                                            viewModel.openAdjacentChapter(data.providerId, currentPath, targetPath, markCurrentRead)
                                        },
                                        onOpenManga = { path -> viewModel.openDetail(data.providerId, path) },
                                        onBack = { viewModel.goBack() },
                                        isChapterLoading = readerUiState.isChapterLoading,
                                    )
                                } ?: LoadingPlaceholder(strings.loadingChapter)
                            }
                                    is Screen.Community -> {
                                        readerUiState.selectedCommunityPage?.let { page ->
                                            CommunityPageScreen(
                                                strings = strings,
                                                page = page,
                                                readChapters = libraryState.readChapters,
                                                chapterProgress = chapterProgressLookup,
                                                onOpenManga = { id, path -> viewModel.openDetail(id, path) },
                                                onOpenChapter = { id, path -> viewModel.openReader(id, path) },
                                                onAddToReading = { viewModel.addToReading(it) },
                                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                                isFavorite = favoriteLookup,
                                            )
                                } ?: LoadingPlaceholder(strings.loadingProviderHome(currentProvider.displayName))
                            }
                            is Screen.HomeSection -> HomeSectionScreen(
                                sectionId = screen.sectionId,
                                feed = homeUiState.feed,
                                providerId = currentProvider.id,
                                providerName = currentProvider.displayName,
                                sectionState = homeController.sectionState(currentProvider.id, screen.sectionId),
                                reading = libraryState.reading,
                                readChapters = libraryState.readChapters,
                                chapterProgress = chapterProgressLookup,
                                strings = strings,
                                onBindSection = { section ->
                                    homeController.bindSection(
                                        provider = currentProvider,
                                        section = section,
                                        onError = { message ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(message)
                                            }
                                        },
                                    )
                                },
                                onLoadMore = {
                                    homeController.loadNextSectionPage(
                                        provider = currentProvider,
                                        sectionId = screen.sectionId,
                                        onError = { message ->
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar(message)
                                            }
                                        },
                                    )
                                },
                                onOpenManga = { id, path -> viewModel.openDetail(id, path) },
                                onOpenChapter = { id, path -> viewModel.openReader(id, path) },
                                onAddToReading = { viewModel.addToReading(it) },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                isFavorite = favoriteLookup,
                            )
                            is Screen.Settings -> SettingsScreen(
                                strings = strings,
                                onOpenLanguage = { viewModel.pushScreen(Screen.SettingsLanguage) },
                                onOpenTheme = { viewModel.pushScreen(Screen.SettingsTheme) },
                                onOpenChapterLanguage = { viewModel.pushScreen(Screen.SettingsChapterLanguage) },
                                onOpenReader = { viewModel.pushScreen(Screen.SettingsReader) },
                                onOpenContent = { viewModel.pushScreen(Screen.SettingsContent) },
                                onOpenUpdates = { viewModel.pushScreen(Screen.SettingsUpdates) },
                                onOpenMyAnimeList = { viewModel.pushScreen(Screen.SettingsMyAnimeList) },
                                onOpenBackup = { viewModel.pushScreen(Screen.SettingsBackup) },
                            )
                            Screen.SettingsLanguage -> LanguageSettingsScreen(
                                strings = strings,
                                appLanguage = libraryState.appLanguage,
                                onLanguageChange = { viewModel.changeLanguage(it) },
                            )
                            Screen.SettingsTheme -> ThemeSettingsScreen(
                                strings = strings,
                                useDarkTheme = libraryState.useDarkTheme,
                                onThemeChange = { viewModel.changeTheme(it) },
                            )
                            Screen.SettingsChapterLanguage -> ChapterLanguageSettingsScreen(
                                strings = strings,
                                preferredChapterLanguage = libraryState.preferredChapterLanguage,
                                onPreferredChapterLanguageChange = { viewModel.changePreferredChapterLanguage(it) },
                            )
                            Screen.SettingsReader -> ReaderSettingsScreen(
                                strings = strings,
                                autoJumpToUnread = libraryState.autoJumpToUnread,
                                onAutoJumpToUnreadChange = { viewModel.changeAutoJumpToUnread(it) },
                            )
                            Screen.SettingsContent -> ContentSettingsScreen(
                                strings = strings,
                                adultContentEnabled = libraryState.adultContentEnabled,
                                adultOnlyProvidersEnabled = libraryState.adultOnlyProvidersEnabled,
                                adultContentPinIsConfigured = viewModel.adultContentPinIsConfigured(),
                                providersByLanguage = providerRegistry.groupedByLanguage(),
                                disabledProviderIds = libraryState.disabledProviderIds,
                                onAdultContentEnabledChange = { viewModel.changeAdultContentEnabled(it) },
                                onAdultOnlyProvidersEnabledChange = { viewModel.changeAdultOnlyProvidersEnabled(it) },
                                onSetAdultContentPin = { viewModel.setAdultContentPin(it) },
                                onVerifyAdultContentPin = { pin -> viewModel.verifyAdultContentPin(pin) },
                                onProviderEnabledChange = { providerId, enabled -> viewModel.setProviderEnabled(providerId, enabled) },
                            )
                            Screen.SettingsUpdates -> UpdatesSettingsScreen(
                                strings = strings,
                                versionName = BuildConfig.VERSION_NAME,
                                updateState = updateController.updateState,
                                onCheckForUpdates = { viewModel.checkForUpdates(notifyIfCurrent = true) },
                                onDownloadUpdate = downloadUpdate,
                                onInstallUpdate = installUpdate,
                                onOpenReleasePage = openReleasePage,
                            )
                            Screen.SettingsMyAnimeList -> MyAnimeListSettingsScreen(
                                strings = strings,
                                malUiState = malUiState,
                                onMalConnect = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.beginMalConnect()))
                                            .addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                                    )
                                },
                                onMalSync = { viewModel.syncMalLibraryBothWays() },
                                onMalDisconnect = { viewModel.disconnectMal() },
                            )
                            Screen.SettingsBackup -> BackupSettingsScreen(
                                strings = strings,
                                onExportJsonBackup = { exportLauncher.launch("KomaStream_Backup.json") },
                                onImportJsonBackup = { importLauncher.launch(arrayOf("application/json")) },
                                onExportDatabaseBackup = { exportDatabaseLauncher.launch("KomaStream_Backup.db") },
                                onImportDatabaseBackup = { importDatabaseLauncher.launch(arrayOf("application/octet-stream", "application/x-sqlite3", "*/*")) },
                            )
                            is Screen.ProviderPicker -> ProviderPickerScreen(
                                strings = strings,
                                selectedProviderId = libraryState.selectedProviderId,
                                adultOnlyProvidersEnabled = libraryState.adultOnlyProvidersEnabled,
                                adultContentPinIsConfigured = viewModel.adultContentPinIsConfigured(),
                                disabledProviderIds = libraryState.disabledProviderIds,
                                providersByLanguage = providerRegistry.groupedByLanguage(),
                                onSelectProvider = { providerId -> viewModel.selectProvider(providerId) },
                                onToggleProviderEnabled = { providerId, enabled -> viewModel.setProviderEnabled(providerId, enabled) },
                                onVerifyAdultContentPin = { pin -> viewModel.verifyAdultContentPin(pin) },
                            )
                        }
                    }

                        if (viewModel.loading && screen !is Screen.Reader) {
                            LoadingPlaceholder()
                }
            }
        }

        browserBootstrapUrl?.let { url ->
            BrowserBootstrapDialog(
                url = url,
                title = currentProvider.displayName,
                onClose = {
                    browserBootstrapUrl = null
                    viewModel.resumePendingBrowserBootstrap()
                },
            )
        }

        if (isMalSyncBlocking) {
            val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f))
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = {},
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 6.dp,
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    if (malUiState.syncItemsTotal > 0) {
                                        CircularProgressIndicator(
                                            progress = {
                                                (malUiState.syncItemsProcessed.toFloat() / malUiState.syncItemsTotal.toFloat())
                                                    .coerceIn(0f, 1f)
                                            },
                                        )
                                    } else {
                                        CircularProgressIndicator()
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = strings.malSyncInProgress,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (malUiState.syncStageMessage.isNotBlank()) {
                                            Text(
                                                text = malUiState.syncStageMessage,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (malUiState.syncItemsTotal > 0) {
                                            Text(
                                                text = buildMalSyncOverlayDetail(
                                                    processed = malUiState.syncItemsProcessed,
                                                    total = malUiState.syncItemsTotal,
                                                    etaSeconds = malUiState.syncEtaSeconds,
                                                ),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                if (malUiState.syncItemsTotal > 0) {
                                    LinearProgressIndicator(
                                        progress = {
                                            (malUiState.syncItemsProcessed.toFloat() / malUiState.syncItemsTotal.toFloat())
                                                .coerceIn(0f, 1f)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }

                if (malUiState.pendingImports.isNotEmpty()) {
                    MalSyncPendingImportsDialog(
                        strings = strings,
                        pendingImports = malUiState.pendingImports,
                        onConfirm = { selections ->
                            viewModel.malSyncController.applyPendingMangaImports(
                                providerId = currentProvider.id,
                                selections = selections,
                            )
                        },
                        onDismiss = {
                            viewModel.malSyncController.clearPendingMangaImports()
                        },
                    )
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
    ) {
        if (updateController.isDialogVisible) {
            UpdateAvailableDialog(
                strings = strings,
                updateState = updateController.updateState,
                onDismiss = { updateController.isDialogVisible = false },
                onDownloadUpdate = downloadUpdate,
                onInstallUpdate = installUpdate,
                onOpenReleasePage = openReleasePage,
            )
        }

        BackupOperationDialog(
            strings = strings,
            state = backupOperationState,
            onConfirm = { viewModel.dismissBackupOperationDialog() },
        )
    }

    BackHandler {
        if (isMalSyncBlocking) return@BackHandler
        if (screen is Screen.Root) {
            if (screen.tab != RootTab.Home) {
                lastRootBackPressAt = 0L
                snackbarHostState.currentSnackbarData?.dismiss()
                viewModel.replaceRoot(RootTab.Home)
            } else {
                val now = System.currentTimeMillis()
                if (now - lastRootBackPressAt < 2_000L) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    activity?.finish()
                } else {
                    lastRootBackPressAt = now
                    coroutineScope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(strings.pressBackAgainToExit)
                    }
                }
            }
        } else if (!viewModel.goBack()) {
            activity?.finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MalSyncPendingImportsDialog(
    strings: AppStrings,
    pendingImports: List<MyAnimeListPendingImport>,
    onConfirm: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (pendingImports.isEmpty()) return

    val selections = remember(pendingImports) { mutableStateMapOf<String, String>() }
    val selectionsToApply = pendingImports.associate { pending ->
        pending.pendingKey to selections[pending.pendingKey].orEmpty()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.malSyncReviewImports,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = strings.malSyncReviewImportsDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pendingImports, key = { it.pendingKey }) { pending ->
                        val selectedKey = selections[pending.pendingKey].orEmpty()
                        val noneSelected = selectedKey.isBlank()
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = pending.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = when (pending.source) {
                                        MyAnimeListPendingImportSource.FROM_REMOTE -> "Match from MyAnimeList"
                                        MyAnimeListPendingImportSource.TO_REMOTE -> "Match from local library"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = buildString {
                                        append(pending.status)
                                        if (pending.numChaptersRead > 0) {
                                            append(" · ")
                                            append(pending.numChaptersRead)
                                            append(" ")
                                            append(strings.chapters)
                                        }
                                    }.trim().trimStart('·').trim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                if (pending.candidates.isEmpty()) {
                                    Text(
                                        text = strings.malSyncNoMatchesFound,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Surface(
                                            onClick = { selections[pending.pendingKey] = "" },
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (noneSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            tonalElevation = 0.dp,
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(10.dp),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                                ) {
                                                    Text(
                                                        text = "None of these",
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = FontWeight.SemiBold,
                                                    )
                                                    Text(
                                                        text = "Leave this manga unmatched for now",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                Checkbox(
                                                    checked = noneSelected,
                                                    onCheckedChange = {
                                                        selections[pending.pendingKey] = ""
                                                    },
                                                )
                                            }
                                        }
                                        pending.candidates.forEach { candidate ->
                                            val isSelected = selectedKey == candidate.key
                                            Surface(
                                                onClick = {
                                                    selections[pending.pendingKey] = candidate.key
                                                },
                                                shape = RoundedCornerShape(10.dp),
                                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                                tonalElevation = 0.dp,
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(10.dp),
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    MangadotAwareAsyncImage(
                                                        model = candidate.coverUrl,
                                                        contentDescription = candidate.displayTitle,
                                                        modifier = Modifier
                                                            .size(width = 58.dp, height = 82.dp)
                                                            .clip(RoundedCornerShape(8.dp)),
                                                    )
                                                    Column(
                                                        modifier = Modifier.weight(1f),
                                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                                    ) {
                                                        Text(
                                                            text = candidate.displayTitle,
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            fontWeight = FontWeight.SemiBold,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        candidate.status.takeIf { it.isNotBlank() }?.let {
                                                            Text(
                                                                text = it,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                        }
                                                        candidate.chaptersCount.takeIf { it.isNotBlank() }?.let {
                                                            Text(
                                                                text = "${strings.chapters} $it",
                                                                style = MaterialTheme.typography.labelMedium,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                        }
                                                        candidate.detailPath.takeIf { it.isNotBlank() }?.let {
                                                            Text(
                                                                text = it,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                        }
                                                    }
                                                    Checkbox(
                                                        checked = isSelected,
                                                        onCheckedChange = { checked ->
                                                            selections[pending.pendingKey] = if (checked) candidate.key else ""
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text(strings.cancel)
                    }
                    Button(
                        onClick = { onConfirm(selectionsToApply) },
                        enabled = pendingImports.isNotEmpty(),
                    ) {
                        Text(strings.malSyncImportSelected)
                    }
                }
            }
        }
    }
}

private fun komaDarkColorScheme() = darkColorScheme(
    primary = Color(0xFF7E57FF),
    onPrimary = Color(0xFFF7F3FF),
    primaryContainer = Color(0xFF2A194E),
    onPrimaryContainer = Color(0xFFE5DCFF),
    secondary = Color(0xFFA48EFF),
    onSecondary = Color(0xFF140A24),
    background = Color(0xFF0A0813),
    onBackground = Color(0xFFF6F2FF),
    surface = Color(0xFF12101D),
    onSurface = Color(0xFFF5F2FB),
    surfaceVariant = Color(0xFF1A1628),
    onSurfaceVariant = Color(0xFFB4ACC9),
    outlineVariant = Color(0xFF312A46),
)

private fun komaLightColorScheme() = lightColorScheme(
    primary = Color(0xFF5D34E6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E1FF),
    onPrimaryContainer = Color(0xFF1C1037),
    secondary = Color(0xFF6A5C96),
    onSecondary = Color.White,
    background = Color(0xFFF7F4FC),
    onBackground = Color(0xFF191522),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191522),
    surfaceVariant = Color(0xFFF0EBF8),
    onSurfaceVariant = Color(0xFF635C74),
    outlineVariant = Color(0xFFD9D2E7),
)

@Composable
private fun komaTypography(): Typography {
    val displayFamily = FontFamily(
        Font(R.font.orbitron_regular, FontWeight.Normal),
        Font(R.font.orbitron_semibold, FontWeight.SemiBold),
    )
    val bodyFamily = FontFamily(
        Font(R.font.rajdhani_regular, FontWeight.Normal),
        Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    )
    return Typography(
        displayLarge = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = 0.5.sp),
        displayMedium = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = 0.4.sp),
        headlineSmall = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = 0.2.sp),
        titleLarge = TextStyle(fontFamily = displayFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 1.2.sp),
        titleMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, letterSpacing = 0.15.sp),
        titleSmall = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.15.sp),
        bodyLarge = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 18.sp, letterSpacing = 0.15.sp),
        bodyMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, letterSpacing = 0.15.sp),
        bodySmall = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, letterSpacing = 0.15.sp),
        labelLarge = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.4.sp),
        labelMedium = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.5.sp),
        labelSmall = TextStyle(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.5.sp),
    )
}

private fun Screen.saveableKey(): String = when (this) {
    is Screen.Root -> "root:${tab.name}"
    is Screen.Detail -> "detail:$providerId:$detailPath:$isMalIdEditorOpen"
    is Screen.Reader -> "reader:$providerId:$chapterPath"
    is Screen.HomeSection -> "home-section:$sectionId"
    is Screen.Community -> "community:$providerId:$communityPath"
    Screen.ProviderPicker -> "provider-picker"
    Screen.Settings -> "settings"
    Screen.SettingsLanguage -> "settings-language"
    Screen.SettingsTheme -> "settings-theme"
    Screen.SettingsChapterLanguage -> "settings-chapter-language"
    Screen.SettingsReader -> "settings-reader"
    Screen.SettingsContent -> "settings-content"
    Screen.SettingsUpdates -> "settings-updates"
    Screen.SettingsMyAnimeList -> "settings-mal"
    Screen.SettingsBackup -> "settings-backup"
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

private fun buildMalSyncOverlayDetail(
    processed: Int,
    total: Int,
    etaSeconds: Int?,
): String {
    val safeProcessed = processed.coerceAtLeast(0)
    val safeTotal = total.coerceAtLeast(0)
    val percent = if (safeTotal > 0) ((safeProcessed.toFloat() / safeTotal.toFloat()) * 100f).toInt().coerceIn(0, 100) else 0
    val progress = "$safeProcessed/$safeTotal  $percent%"
    val eta = etaSeconds?.takeIf { it > 0 }?.let(::formatEtaLabel)
    return if (eta != null) "$progress  $eta" else progress
}

private fun formatEtaLabel(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return when {
        minutes > 0 -> "~${minutes}m ${seconds}s"
        else -> "~${seconds}s"
    }
}
