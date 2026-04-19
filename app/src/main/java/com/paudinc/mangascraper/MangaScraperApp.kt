@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.paudinc.mangascraper

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private enum class RootTab(val label: String) { Home("Home"), Library("Library"), Catalog("Catalog") }

private enum class LibraryTab(val label: String) { ContinueReading("Continue Reading"), Favorites("Favorites") }

private enum class CatalogMode { Basic, Advanced }

private sealed interface Screen {
    data class Root(val tab: RootTab) : Screen
    data class Detail(val detailPath: String) : Screen
    data class Reader(val chapterPath: String) : Screen
    data object Settings : Screen
}

private val ScreenSaver = Saver<Screen, List<String>>(
    save = { screen ->
        when (screen) {
            is Screen.Root -> listOf("root", screen.tab.name)
            is Screen.Detail -> listOf("detail", screen.detailPath)
            is Screen.Reader -> listOf("reader", screen.chapterPath)
            Screen.Settings -> listOf("settings")
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            "root" -> Screen.Root(RootTab.valueOf(saved.getOrElse(1) { RootTab.Home.name }))
            "detail" -> Screen.Detail(saved.getOrElse(1) { "/" })
            "reader" -> Screen.Reader(saved.getOrElse(1) { "/" })
            "settings" -> Screen.Settings
            else -> Screen.Root(RootTab.Home)
        }
    },
)

private val ScreenStackSaver = Saver<List<Screen>, List<List<String>>>(
    save = { stack ->
        stack.map { screen ->
            when (screen) {
                is Screen.Root -> listOf("root", screen.tab.name)
                is Screen.Detail -> listOf("detail", screen.detailPath)
                is Screen.Reader -> listOf("reader", screen.chapterPath)
                Screen.Settings -> listOf("settings")
            }
        }
    },
    restore = { saved ->
        saved.map { item ->
            when (item.firstOrNull()) {
                "root" -> Screen.Root(RootTab.valueOf(item.getOrElse(1) { RootTab.Home.name }))
                "detail" -> Screen.Detail(item.getOrElse(1) { "/" })
                "reader" -> Screen.Reader(item.getOrElse(1) { "/" })
                "settings" -> Screen.Settings
                else -> Screen.Root(RootTab.Home)
            }
        }.ifEmpty { listOf(Screen.Root(RootTab.Home)) }
    },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MangaScraperApp() {
    val context = LocalContext.current
    val service = remember { InMangaService() }
    val libraryStore = remember { LibraryStore(context) }
    val offlineStore = remember { OfflineChapterStore(context) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var navigationStack by rememberSaveable(stateSaver = ScreenStackSaver) {
        mutableStateOf(listOf<Screen>(Screen.Root(RootTab.Home)))
    }
    val screen = navigationStack.last()
    var homeFeed by remember { mutableStateOf<HomeFeed?>(null) }
    var catalogQuery by rememberSaveable { mutableStateOf("") }
    var catalogFilterOptions by remember { mutableStateOf(CatalogFilterOptions(emptyList(), emptyList(), emptyList())) }
    var selectedCategoryIds by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selectedSortOptionId by rememberSaveable { mutableStateOf("2") }
    var selectedStatusOptionId by rememberSaveable { mutableStateOf("0") }
    var onlyFavoritesFilter by rememberSaveable { mutableStateOf(false) }
    var catalogResults by remember { mutableStateOf<List<MangaSummary>>(emptyList()) }
    var catalogHasMore by remember { mutableStateOf(false) }
    var catalogLoadingMore by remember { mutableStateOf(false) }
    var libraryState by remember { mutableStateOf(libraryStore.read()) }
    var downloadedChapterPaths by remember { mutableStateOf(offlineStore.getDownloadedChapterPaths()) }
    var selectedDetail by remember { mutableStateOf<MangaDetail?>(null) }
    var readerData by remember { mutableStateOf<ReaderData?>(null) }
    var readerInitialPageIndex by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    val strings = appStrings()

    fun showError(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun refreshOfflineDownloads() {
        downloadedChapterPaths = offlineStore.getDownloadedChapterPaths()
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(libraryStore.exportBackup())
            } ?: error("Could not open output stream")
        }.onSuccess {
            scope.launch { snackbarHostState.showSnackbar(strings.backupExported) }
        }.onFailure {
            showError(strings.exportBackupError)
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val payload = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.readText()
            } ?: error("Could not open input stream")
            libraryStore.importBackup(payload)
            libraryState = libraryStore.read()
        }.onSuccess {
            scope.launch { snackbarHostState.showSnackbar(strings.backupImported) }
        }.onFailure {
            showError(strings.invalidBackup)
        }
    }

    fun pushScreen(next: Screen) {
        navigationStack = navigationStack + next
    }

    fun replaceRoot(tab: RootTab) {
        navigationStack = listOf(Screen.Root(tab))
    }

    fun goBack() {
        navigationStack = when {
            navigationStack.size > 1 -> navigationStack.dropLast(1)
            screen is Screen.Root && screen.tab != RootTab.Home -> listOf(Screen.Root(RootTab.Home))
            else -> navigationStack
        }
    }

    fun refreshHome() {
        scope.launch {
            loading = true
            runCatching { withContext(Dispatchers.IO) { service.fetchHomeFeed() } }
                .onSuccess { homeFeed = it }
                .onFailure { showError(it.message ?: strings.couldNotLoadHome) }
            loading = false
        }
    }

    fun searchCatalog(loadMore: Boolean = false) {
        if (loadMore && (catalogLoadingMore || !catalogHasMore)) return
        scope.launch {
            if (loadMore) {
                catalogLoadingMore = true
            } else {
                loading = true
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    service.searchCatalog(
                        query = catalogQuery,
                        categoryIds = selectedCategoryIds.toList(),
                        sortBy = selectedSortOptionId,
                        broadcastStatus = selectedStatusOptionId,
                        onlyFavorites = onlyFavoritesFilter,
                        skip = if (loadMore) catalogResults.size else 0,
                    )
                }
            }
                .onSuccess { result ->
                    catalogResults = if (loadMore) {
                        (catalogResults + result.items).distinctBy { it.detailPath }
                    } else {
                        result.items
                    }
                    catalogHasMore = result.hasMore
                }
                .onFailure { showError(it.message ?: strings.couldNotSearchCatalog) }
            if (loadMore) {
                catalogLoadingMore = false
            } else {
                loading = false
            }
        }
    }

    fun openDetail(path: String) {
        scope.launch {
            loading = true
            runCatching { withContext(Dispatchers.IO) { service.fetchMangaDetail(path) } }
                .onSuccess {
                    selectedDetail = it
                    pushScreen(Screen.Detail(path))
                }
                .onFailure { showError(it.message ?: strings.couldNotOpenManga) }
            loading = false
        }
    }

    fun openReader(path: String) {
        scope.launch {
            loading = true
            runCatching {
                withContext(Dispatchers.IO) {
                    offlineStore.loadChapter(path) ?: service.fetchReaderData(path)
                }
            }
                .onSuccess {
                    readerData = it
                    readerInitialPageIndex = libraryStore.getChapterProgress(it.chapterPath)
                    libraryStore.markChapterRead(it.chapterPath)
                    val cover = selectedDetail?.coverUrl
                        ?: libraryState.reading.firstOrNull { item -> item.detailPath == it.mangaDetailPath }?.coverUrl
                        .orEmpty()
                    libraryStore.upsertReading(
                        SavedManga(
                            title = it.mangaTitle,
                            detailPath = it.mangaDetailPath,
                            coverUrl = cover,
                            lastChapterTitle = it.chapterTitle,
                            lastChapterPath = it.chapterPath,
                        )
                    )
                    libraryState = libraryStore.read()
                    pushScreen(Screen.Reader(path))
                }
                .onFailure { showError(it.message ?: strings.couldNotOpenChapter) }
            loading = false
        }
    }

    fun downloadChapter(path: String) {
        if (downloadedChapterPaths.contains(path)) return
        scope.launch {
            loading = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val reader = service.fetchReaderData(path)
                    val pageBytes = reader.pages.map { page ->
                        service.downloadBytes(page.imageUrl, referer = path)
                    }
                    offlineStore.saveChapter(reader, pageBytes)
                }
            }
                .onSuccess {
                    refreshOfflineDownloads()
                    snackbarHostState.showSnackbar(strings.chapterDownloaded)
                }
                .onFailure { showError(it.message ?: strings.couldNotDownloadChapter) }
            loading = false
        }
    }

    fun removeDownloadedChapter(path: String) {
        if (downloadedChapterPaths.contains(path).not()) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { offlineStore.removeChapter(path) }
            }
                .onSuccess {
                    refreshOfflineDownloads()
                    snackbarHostState.showSnackbar(strings.chapterRemoved)
                }
                .onFailure { showError(it.message ?: strings.couldNotRemoveDownload) }
        }
    }

    fun openSettings() {
        pushScreen(Screen.Settings)
    }

    LaunchedEffect(Unit) {
        refreshHome()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { service.fetchCatalogFilterOptions() } }
                .onSuccess {
                    catalogFilterOptions = it
                    if (it.sortOptions.any { option -> option.id == selectedSortOptionId }.not()) {
                        selectedSortOptionId = it.sortOptions.firstOrNull()?.id ?: "2"
                    }
                    if (it.statusOptions.any { option -> option.id == selectedStatusOptionId }.not()) {
                        selectedStatusOptionId = it.statusOptions.firstOrNull()?.id ?: "0"
                    }
                }
        }
        searchCatalog()
    }

    LaunchedEffect(screen) {
        when (val currentScreen = screen) {
            is Screen.Detail -> {
                if (selectedDetail?.detailPath != currentScreen.detailPath) {
                    runCatching {
                        withContext(Dispatchers.IO) { service.fetchMangaDetail(currentScreen.detailPath) }
                    }.onSuccess { selectedDetail = it }
                }
            }

            is Screen.Reader -> {
                if (readerData?.chapterPath != currentScreen.chapterPath) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            offlineStore.loadChapter(currentScreen.chapterPath) ?: service.fetchReaderData(currentScreen.chapterPath)
                        }
                    }.onSuccess {
                        readerData = it
                        readerInitialPageIndex = libraryStore.getChapterProgress(currentScreen.chapterPath)
                    }
                }
            }

            is Screen.Root, Screen.Settings -> Unit
        }
    }

    BackHandler(
        enabled = screen !is Screen.Root || (screen is Screen.Root && (screen as Screen.Root).tab != RootTab.Home)
    ) {
        goBack()
    }

    val lightScheme = lightColorScheme(
        primary = Color(0xFF2C5DAA),
        secondary = Color(0xFF5B6B86),
        tertiary = Color(0xFF8D3B3B),
        background = Color(0xFFF5F7FB),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE2E8F0),
    )
    val darkScheme = darkColorScheme(
        primary = Color(0xFF8DB4FF),
        onPrimary = Color(0xFF0B1830),
        secondary = Color(0xFFC9D6EB),
        tertiary = Color(0xFFFFC2B8),
        background = Color(0xFF0A101B),
        onBackground = Color(0xFFF2F5FA),
        surface = Color(0xFF1F2D42),
        onSurface = Color(0xFFF2F5FA),
        surfaceVariant = Color(0xFF2B3C56),
        onSurfaceVariant = Color(0xFFE0E7F2),
        outline = Color(0xFF7A90B2),
    )
    val colorScheme = if (libraryState.useDarkTheme) darkScheme else lightScheme

    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        title = {
                            Text(
                                when (screen) {
                                    is Screen.Root -> strings.appName
                                    is Screen.Detail -> selectedDetail?.title ?: strings.manga
                                    is Screen.Reader -> readerData?.chapterTitle ?: strings.reader
                                    Screen.Settings -> strings.settings
                                }
                            )
                        },
                        navigationIcon = {
                            if (screen is Screen.Root) {
                                IconButton(onClick = { openSettings() }) {
                                    Icon(Icons.Default.Settings, contentDescription = strings.settings)
                                }
                            } else {
                                IconButton(onClick = { goBack() }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = strings.back)
                                }
                            }
                        },
                        actions = {
                            if (screen is Screen.Root) {
                                IconButton(onClick = { refreshHome() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = strings.refresh)
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (screen is Screen.Root) {
                        val currentTab = (screen as Screen.Root).tab
                        NavigationBar(
                            modifier = Modifier.navigationBarsPadding(),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            listOf(
                                Triple(RootTab.Home, strings.home, Icons.Default.Home),
                                Triple(RootTab.Library, strings.library, Icons.Default.Bookmark),
                                Triple(RootTab.Catalog, strings.catalog, Icons.Default.Explore),
                            ).forEach { (tab, label, icon) ->
                                NavigationBarItem(
                                    selected = currentTab == tab,
                                    onClick = { replaceRoot(tab) },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    when (val current = screen) {
                        is Screen.Root -> when (current.tab) {
                            RootTab.Home -> HomeScreen(homeFeed, strings, ::openDetail, ::openReader)
                            RootTab.Library -> LibraryScreen(libraryState, strings, ::openDetail, ::openReader)
                            RootTab.Catalog -> CatalogScreen(
                                strings,
                                catalogQuery,
                                catalogFilterOptions.categories,
                                catalogFilterOptions.sortOptions,
                                catalogFilterOptions.statusOptions,
                                selectedCategoryIds,
                                selectedSortOptionId,
                                selectedStatusOptionId,
                                onlyFavoritesFilter,
                                catalogResults,
                                catalogHasMore,
                                catalogLoadingMore,
                                { catalogQuery = it },
                                { categoryId ->
                                    selectedCategoryIds = selectedCategoryIds.toMutableSet().apply {
                                        if (contains(categoryId)) remove(categoryId) else add(categoryId)
                                    }
                                },
                                { selectedSortOptionId = it },
                                { selectedStatusOptionId = it },
                                { onlyFavoritesFilter = it },
                                {
                                    selectedCategoryIds = emptySet()
                                    selectedSortOptionId = catalogFilterOptions.sortOptions.firstOrNull()?.id ?: "2"
                                    selectedStatusOptionId = catalogFilterOptions.statusOptions.firstOrNull()?.id ?: "0"
                                    onlyFavoritesFilter = false
                                    catalogQuery = ""
                                    searchCatalog()
                                },
                                ::searchCatalog,
                                { searchCatalog(loadMore = true) },
                                ::openDetail,
                            )
                        }

                        is Screen.Detail -> selectedDetail?.let { detail ->
                            DetailScreen(
                                strings = strings,
                                detail = detail,
                                isFavorite = libraryState.favorites.any { it.detailPath == detail.detailPath },
                                readChapters = libraryState.readChapters,
                                downloadedChapters = downloadedChapterPaths,
                                onToggleFavorite = {
                                    val wasFavorite = libraryState.favorites.any { it.detailPath == detail.detailPath }
                                    val currentReading = libraryState.reading.firstOrNull { item -> item.detailPath == detail.detailPath }
                                    libraryStore.toggleFavorite(
                                        SavedManga(
                                            title = detail.title,
                                            detailPath = detail.detailPath,
                                            coverUrl = detail.coverUrl,
                                            lastChapterTitle = currentReading?.lastChapterTitle.orEmpty(),
                                            lastChapterPath = currentReading?.lastChapterPath.orEmpty(),
                                        )
                                    )
                                    libraryState = libraryStore.read()
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (wasFavorite) strings.removedFromFavorites else strings.addedToFavorites
                                        )
                                    }
                                },
                                onToggleChapterRead = { chapterPath ->
                                    val wasRead = libraryState.readChapters.contains(chapterPath)
                                    libraryStore.toggleChapterRead(chapterPath)
                                    libraryState = libraryStore.read()
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (wasRead) strings.markedAsUnread else strings.markedAsRead
                                        )
                                    }
                                },
                                onSetAllChaptersRead = { read ->
                                    val paths = detail.chapters.map { buildChapterPath(detail.detailPath, it) }
                                    libraryStore.setChaptersRead(paths, read)
                                    libraryState = libraryStore.read()
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            if (read) strings.allChaptersRead else strings.allChaptersUnread
                                        )
                                    }
                                },
                                onSetUntilChapterRead = { chapterNumber, read ->
                                    val paths = detail.chapters
                                        .filter { chapterValue(it) <= chapterNumber }
                                        .map { buildChapterPath(detail.detailPath, it) }
                                    libraryStore.setChaptersRead(paths, read)
                                    libraryState = libraryStore.read()
                                    scope.launch {
                                        snackbarHostState.showSnackbar(strings.markedUntilChapter(chapterNumber, read))
                                    }
                                },
                                onToggleChapterDownload = { chapterPath, isDownloaded ->
                                    if (isDownloaded) removeDownloadedChapter(chapterPath) else downloadChapter(chapterPath)
                                },
                                onReadChapter = ::openReader,
                            )
                        }

                        is Screen.Reader -> readerData?.let {
                            ReaderScreen(
                                strings = strings,
                                reader = it,
                                offlineStore = offlineStore,
                                initialPageIndex = readerInitialPageIndex,
                                isDownloaded = downloadedChapterPaths.contains(it.chapterPath),
                                onPagePositionChanged = { pageIndex ->
                                    libraryStore.saveChapterProgress(it.chapterPath, pageIndex)
                                },
                                onToggleDownload = {
                                    if (downloadedChapterPaths.contains(it.chapterPath)) {
                                        removeDownloadedChapter(it.chapterPath)
                                    } else {
                                        downloadChapter(it.chapterPath)
                                    }
                                },
                                onOpenChapter = ::openReader,
                                onOpenManga = ::openDetail,
                            )
                        }
                        Screen.Settings -> SettingsScreen(
                            strings = strings,
                            appLanguage = libraryState.appLanguage,
                            useDarkTheme = libraryState.useDarkTheme,
                            onLanguageChange = { language ->
                                libraryStore.setAppLanguage(language)
                                val languageTag = if (language == AppLanguage.ES) "es" else "en"
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
                                libraryState = libraryStore.read()
                            },
                            onThemeChange = { enabled ->
                                libraryStore.setDarkTheme(enabled)
                                libraryState = libraryStore.read()
                            },
                            onExportBackup = {
                                exportBackupLauncher.launch(defaultBackupFileName())
                            },
                            onImportBackup = { importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        )
                    }

                    if (loading) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color(0x66000000)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(feed: HomeFeed?, strings: AppStrings, onOpenManga: (String) -> Unit, onOpenChapter: (String) -> Unit) {
    if (feed == null) {
        LoadingPlaceholder()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { SectionTitle(strings.latestUpdates) }
        items(feed.latestUpdates) { ChapterRow(it, strings, onOpenChapter) }
        item { SectionTitle(strings.popularChapters) }
        items(feed.popularChapters) { ChapterRow(it, strings, onOpenChapter) }
        item { SectionTitle(strings.popularMangas) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(feed.popularMangas) { MangaCoverCard(it) { onOpenManga(it.detailPath) } }
            }
        }
    }
}

@Composable
private fun LibraryScreen(libraryState: LibraryState, strings: AppStrings, onOpenManga: (String) -> Unit, onOpenChapter: (String) -> Unit) {
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.ContinueReading) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionTitle(strings.library)
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    AssistChip(
                        onClick = { selectedTab = tab },
                        label = { Text(if (tab == LibraryTab.ContinueReading) strings.continueReading else strings.favorites) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
        when (selectedTab) {
            LibraryTab.Favorites -> {
                item {
                    Text(
                        strings.favoritesCount(libraryState.favorites.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (libraryState.favorites.isEmpty()) {
                    item { EmptyCard(strings.addMangaHint) }
                } else {
                    items(libraryState.favorites) { saved ->
                        FavoriteMangaCard(
                            manga = saved,
                            strings = strings,
                            onOpen = { onOpenManga(saved.detailPath) },
                        )
                    }
                }
            }
            LibraryTab.ContinueReading -> {
                item {
                    Text(
                        strings.activeSeriesCount(libraryState.reading.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (libraryState.reading.isEmpty()) {
                    item { EmptyCard(strings.readingHint) }
                } else {
                    items(libraryState.reading) { saved ->
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(cardBorder(), RoundedCornerShape(22.dp))
                                .clickable { onOpenManga(saved.detailPath) },
                            shape = RoundedCornerShape(22.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = saved.coverUrl,
                                    contentDescription = saved.title,
                                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(saved.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        saved.lastChapterTitle.ifBlank { strings.noChapterSavedYet },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Button(onClick = { if (saved.lastChapterPath.isNotBlank()) onOpenChapter(saved.lastChapterPath) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(strings.resume)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogScreen(
    strings: AppStrings,
    query: String,
    categories: List<CategoryOption>,
    sortOptions: List<FilterOption>,
    statusOptions: List<FilterOption>,
    selectedCategoryIds: Set<String>,
    selectedSortOptionId: String,
    selectedStatusOptionId: String,
    onlyFavorites: Boolean,
    results: List<MangaSummary>,
    hasMoreResults: Boolean,
    isLoadingMore: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onSelectSort: (String) -> Unit,
    onSelectStatus: (String) -> Unit,
    onToggleOnlyFavorites: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var catalogMode by rememberSaveable { mutableStateOf(CatalogMode.Basic) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TabRow(selectedTabIndex = catalogMode.ordinal) {
                    Tab(
                        selected = catalogMode == CatalogMode.Basic,
                        onClick = { catalogMode = CatalogMode.Basic },
                        text = { Text(strings.search) },
                    )
                    Tab(
                        selected = catalogMode == CatalogMode.Advanced,
                        onClick = { catalogMode = CatalogMode.Advanced },
                        text = { Text(strings.additionalFilters) },
                    )
                }
                when (catalogMode) {
                    CatalogMode.Basic -> {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = onQueryChange,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(strings.searchAvailableMangas) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onSearch) { Text(strings.search) }
                                Button(onClick = onClearFilters) { Text(strings.clearFilters) }
                            }
                        }
                    }

                    CatalogMode.Advanced -> {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (categories.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(strings.categories, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        categories.forEach { category ->
                                            val selected = selectedCategoryIds.contains(category.id)
                                            AssistChip(
                                                onClick = { onToggleCategory(category.id) },
                                                label = { Text(category.name) },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                    labelColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            if (sortOptions.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(strings.sortBy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        sortOptions.forEach { option ->
                                            val selected = option.id == selectedSortOptionId
                                            AssistChip(
                                                onClick = { onSelectSort(option.id) },
                                                label = { Text(option.name) },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                    labelColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            )
                                        }
                                    }
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(strings.additionalFilters, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (statusOptions.isNotEmpty()) {
                                    Text(strings.state, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        statusOptions.forEach { option ->
                                            val selected = option.id == selectedStatusOptionId
                                            AssistChip(
                                                onClick = { onSelectStatus(option.id) },
                                                label = { Text(option.name) },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                    labelColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            )
                                        }
                                    }
                                }
                                AssistChip(
                                    onClick = { onToggleOnlyFavorites(onlyFavorites.not()) },
                                    label = { Text(strings.onlyFavorites) },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = if (onlyFavorites) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        labelColor = if (onlyFavorites) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = onSearch) { Text(strings.search) }
                                    Button(onClick = onClearFilters) { Text(strings.clearFilters) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (query.isBlank() && results.isEmpty()) {
            item {
                EmptyCard(strings.searchEmptyCatalog)
            }
        }
        items(results) { manga ->
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(cardBorder(), RoundedCornerShape(24.dp))
                    .clickable { onOpen(manga.detailPath) },
                shape = RoundedCornerShape(24.dp),
            ) {
                Row(modifier = Modifier.padding(14.dp)) {
                    AsyncImage(
                        model = manga.coverUrl,
                        contentDescription = manga.title,
                        modifier = Modifier.size(width = 92.dp, height = 128.dp).clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(manga.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        MetadataLine(strings.status, manga.status)
                        MetadataLine(strings.periodicity, manga.periodicity)
                        MetadataLine(strings.latest, formatDateEu(manga.latestPublication))
                        MetadataLine(strings.chapters, manga.chaptersCount)
                    }
                }
            }
        }
        if (results.isNotEmpty() && hasMoreResults) {
            item {
                Button(
                    onClick = onLoadMore,
                    enabled = !isLoadingMore,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(strings.loadMore)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailScreen(
    strings: AppStrings,
    detail: MangaDetail,
    isFavorite: Boolean,
    readChapters: Set<String>,
    downloadedChapters: Set<String>,
    onToggleFavorite: () -> Unit,
    onToggleChapterRead: (String) -> Unit,
    onSetAllChaptersRead: (Boolean) -> Unit,
    onSetUntilChapterRead: (Double, Boolean) -> Unit,
    onToggleChapterDownload: (String, Boolean) -> Unit,
    onReadChapter: (String) -> Unit,
) {
    var chapterQuery by rememberSaveable(detail.detailPath) { mutableStateOf("") }
    var bulkChapterInput by rememberSaveable(detail.detailPath) { mutableStateOf("") }
    val filteredChapters = remember(detail.chapters, chapterQuery) {
        val normalizedQuery = chapterQuery.trim().replace(",", ".")
        if (normalizedQuery.isBlank()) {
            detail.chapters
        } else {
            detail.chapters.filter { chapter ->
                chapter.chapterLabel.contains(chapterQuery.trim(), ignoreCase = true) ||
                    chapter.chapterNumberUrl.contains(chapterQuery.trim(), ignoreCase = true)
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box {
                AsyncImage(
                    model = detail.bannerUrl.ifBlank { detail.coverUrl },
                    contentDescription = detail.title,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD0B1220))))
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.BottomStart),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    AsyncImage(
                        model = detail.coverUrl,
                        contentDescription = detail.title,
                        modifier = Modifier.size(width = 102.dp, height = 146.dp).clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            detail.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${detail.status} | ${detail.periodicity}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)),
                    ) {
                        Icon(
                            if (isFavorite) Icons.Default.Favorite else Icons.Default.BookmarkBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) Color(0xFFE85A5A) else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        item {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(detail.description.ifBlank { strings.noDescription })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TagChip(
                        label = strings.publishedDate(formatDateEu(detail.publicationDate)),
                        containerColor = Color(0xFF274777),
                        labelColor = Color(0xFFE7F0FF),
                    )
                    detail.status.takeIf { it.isNotBlank() }?.let {
                        TagChip(
                            label = it,
                            containerColor = statusTagColor(it),
                            labelColor = Color.White,
                        )
                    }
                    detail.periodicity.takeIf { it.isNotBlank() }?.let {
                        TagChip(
                            label = it,
                            containerColor = periodicityTagColor(it),
                            labelColor = Color.White,
                        )
                    }
                }
                HorizontalDivider()
                Text(strings.chapters, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = chapterQuery,
                    onValueChange = { chapterQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.searchChapter) },
                    placeholder = { Text(strings.searchChapterPlaceholder) },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { onSetAllChaptersRead(true) },
                        label = { Text(strings.markAllRead) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color(0xFF1E6B47),
                            labelColor = Color.White,
                        ),
                    )
                    AssistChip(
                        onClick = { onSetAllChaptersRead(false) },
                        label = { Text(strings.markAllUnread) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color(0xFF7A3045),
                            labelColor = Color.White,
                        ),
                    )
                }
                OutlinedTextField(
                    value = bulkChapterInput,
                    onValueChange = { bulkChapterInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.untilChapter) },
                    placeholder = { Text(strings.untilChapterPlaceholder) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            parseChapterInput(bulkChapterInput)?.let { onSetUntilChapterRead(it, true) }
                        }
                    ) {
                        Icon(Icons.Default.DoneAll, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(strings.readToX)
                    }
                    Button(
                        onClick = {
                            parseChapterInput(bulkChapterInput)?.let { onSetUntilChapterRead(it, false) }
                        }
                    ) {
                        Text(strings.unreadToX)
                    }
                }
                Text(
                    if (chapterQuery.isBlank()) {
                        strings.chaptersCount(detail.chapters.size)
                    } else {
                        strings.resultsCount(filteredChapters.size)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (filteredChapters.isEmpty()) {
            item {
                EmptyCard(strings.noChaptersFound(chapterQuery))
            }
        }
        items(filteredChapters) { chapter ->
            val chapterPath = buildChapterPath(detail.detailPath, chapter)
            val isRead = readChapters.contains(chapterPath)
            val isDownloaded = downloadedChapters.contains(chapterPath)
            ElevatedCard(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .border(cardBorder(), RoundedCornerShape(22.dp))
                    .clickable { onReadChapter(chapterPath) },
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isRead) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                ),
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isRead) Icons.Default.Bookmark else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isRead) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            strings.chapterTitle(chapter.chapterLabel),
                            fontWeight = FontWeight.SemiBold,
                            color = if (isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(strings.pagesCount(chapter.pagesCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(formatDateEu(chapter.registrationDate), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isDownloaded) {
                            Text(
                                strings.offlineAvailable,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        AssistChip(
                            onClick = { onToggleChapterRead(chapterPath) },
                            label = { Text(if (isRead) strings.read else strings.unread) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isRead) Color(0xFF1E6B47) else Color(0xFF5B6678),
                                labelColor = Color.White,
                            ),
                        )
                        AssistChip(
                            onClick = { onToggleChapterDownload(chapterPath, isDownloaded) },
                            label = { Text(if (isDownloaded) strings.removeDownload else strings.download) },
                            leadingIcon = {
                                Icon(
                                    if (isDownloaded) Icons.Default.Delete else Icons.Default.Download,
                                    contentDescription = null,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (isDownloaded) Color(0xFF7A3045) else Color(0xFF2E5B9A),
                                labelColor = Color.White,
                                leadingIconContentColor = Color.White,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    strings: AppStrings,
    appLanguage: AppLanguage,
    useDarkTheme: Boolean,
    onLanguageChange: (AppLanguage) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.languageLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onLanguageChange(AppLanguage.EN) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (appLanguage == AppLanguage.EN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (appLanguage == AppLanguage.EN) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) { Text(strings.english) }
                        Button(
                            onClick = { onLanguageChange(AppLanguage.ES) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (appLanguage == AppLanguage.ES) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (appLanguage == AppLanguage.ES) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) { Text(strings.spanish) }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.theme, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onThemeChange(false) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!useDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (!useDarkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Icon(Icons.Default.LightMode, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(strings.light)
                        }
                        Button(
                            onClick = { onThemeChange(true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (useDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (useDarkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Icon(Icons.Default.DarkMode, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(strings.dark)
                        }
                    }
                    Text(if (useDarkTheme) strings.darkThemeActive else strings.lightThemeActive, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.backup, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(strings.backupDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onExportBackup) { Text(strings.exportBackup) }
                        Button(onClick = onImportBackup) { Text(strings.importBackup) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderScreen(
    strings: AppStrings,
    reader: ReaderData,
    offlineStore: OfflineChapterStore,
    initialPageIndex: Int,
    isDownloaded: Boolean,
    onPagePositionChanged: (Int) -> Unit,
    onToggleDownload: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenManga: (String) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(reader.chapterPath, initialPageIndex, reader.pages.size) {
        listState.scrollToItem((initialPageIndex + 1).coerceAtMost(reader.pages.size))
    }

    LaunchedEffect(reader.chapterPath, listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .map { index -> (index - 1).coerceAtLeast(0) }
            .filter { pageIndex -> reader.pages.isNotEmpty() }
            .distinctUntilChanged()
            .collect { pageIndex -> onPagePositionChanged(pageIndex) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reader.chapterTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (isDownloaded) {
                    Text(strings.offlineAvailable, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenManga(reader.mangaDetailPath) }) { Text(strings.manga) }
                    Button(onClick = onToggleDownload) {
                        Icon(if (isDownloaded) Icons.Default.Delete else Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isDownloaded) strings.removeDownload else strings.download)
                    }
                    reader.previousChapterPath?.let { Button(onClick = { onOpenChapter(it) }) { Text(strings.previous) } }
                    reader.nextChapterPath?.let { Button(onClick = { onOpenChapter(it) }) { Text(strings.next) } }
                }
            }
        }
        items(reader.pages) { page ->
            ZoomableReaderPage(chapterPath = reader.chapterPath, page = page, offlineStore = offlineStore)
        }
    }
}

@Composable
private fun ZoomableReaderPage(chapterPath: String, page: ReaderPage, offlineStore: OfflineChapterStore) {
    var scale by remember(page.id) { mutableStateOf(1f) }
    var offset by remember(page.id) { mutableStateOf(Offset.Zero) }
    val offlineBytes by produceState<ByteArray?>(initialValue = null, chapterPath, page.id, page.offlineFileName) {
        value = if (page.offlineFileName.isNotBlank()) {
            withContext(Dispatchers.IO) { offlineStore.loadPageBytes(chapterPath, page) }
        } else {
            null
        }
    }
    val offlineBitmap = remember(offlineBytes) {
        offlineBytes?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }
    val imageModifier = Modifier
        .fillMaxWidth()
        .graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = offset.x,
            translationY = offset.y,
        )
        .pointerInput(page.id) {
            awaitEachGesture {
                var event = awaitPointerEvent()
                while (event.changes.none { pointer -> pointer.pressed }) {
                    event = awaitPointerEvent()
                }
                do {
                    val pointerCount = event.changes.count { pointer -> pointer.pressed }
                    val shouldHandleGesture = pointerCount > 1 || scale > 1f
                    if (shouldHandleGesture) {
                        val nextScale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                        scale = nextScale
                        offset = if (nextScale == 1f) {
                            Offset.Zero
                        } else {
                            offset + event.calculatePan()
                        }
                        event.changes.forEach { change ->
                            if (change.positionChanged()) {
                                change.consume()
                            }
                        }
                    }
                    event = awaitPointerEvent()
                } while (event.changes.any { pointer -> pointer.pressed })
            }
        }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .zIndex(if (scale > 1f) 1f else 0f)
            .background(if (scale > 1f) Color.Black else Color.Transparent)
    ) {
        if (offlineBitmap != null) {
            Image(
                bitmap = offlineBitmap.asImageBitmap(),
                contentDescription = "Page ${page.numberLabel}",
                modifier = imageModifier,
                contentScale = ContentScale.FillWidth,
            )
        } else {
            AsyncImage(
                model = page.imageUrl,
                contentDescription = "Page ${page.numberLabel}",
                modifier = imageModifier,
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun MangaCoverCard(manga: MangaSummary, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .width(160.dp)
            .border(cardBorder(), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column {
            AsyncImage(
                model = manga.coverUrl,
                contentDescription = manga.title,
                modifier = Modifier.fillMaxWidth().height(210.dp),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Text(manga.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                if (manga.views.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(manga.views, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FavoriteMangaCard(
    manga: SavedManga,
    strings: AppStrings,
    onOpen: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(cardBorder(), RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = manga.coverUrl,
                contentDescription = manga.title,
                modifier = Modifier.size(width = 86.dp, height = 120.dp).clip(RoundedCornerShape(18.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    manga.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (manga.lastChapterTitle.isNotBlank()) {
                    Text(
                        strings.latestProgress,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        manga.lastChapterTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        strings.noChapterOpenedYet,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(item: ChapterSummary, strings: AppStrings, onOpenChapter: (String) -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(cardBorder(), RoundedCornerShape(22.dp))
            .clickable { onOpenChapter(item.chapterPath) },
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.mangaTitle,
                modifier = Modifier.size(70.dp).clip(RoundedCornerShape(18.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.mangaTitle, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.chapterLabel, color = MaterialTheme.colorScheme.primary)
                if (item.registrationLabel.isNotBlank()) {
                    Text(
                        item.registrationLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.Default.PlayArrow, contentDescription = null)
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label:", fontWeight = FontWeight.SemiBold)
        Text(value.ifBlank { "-" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TagChip(
    label: String,
    containerColor: Color,
    labelColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
    ) {
        Text(
            text = label,
            color = labelColor,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun cardBorder() = androidx.compose.foundation.BorderStroke(
    width = 1.dp,
    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun EmptyCard(message: String) {
    Card(shape = RoundedCornerShape(20.dp), border = cardBorder()) {
        Text(message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LoadingPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

private fun buildChapterPath(detailPath: String, chapter: MangaChapter): String {
    val prefix = detailPath.substringBeforeLast("/")
    val path = "$prefix/${chapter.chapterNumberUrl}/${chapter.id}".replace("//", "/")
    return if (path.startsWith("/")) path else "/$path"
}

private fun parseChapterInput(value: String): Double? {
    return value.trim().replace(",", ".").toDoubleOrNull()
}

private fun chapterValue(chapter: MangaChapter): Double {
    return parseChapterInput(chapter.chapterNumberUrl)
        ?: parseChapterInput(chapter.chapterLabel)
        ?: Double.MAX_VALUE
}

private fun defaultBackupFileName(): String {
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    return "KomaStream-$timestamp.json"
}

private fun statusTagColor(status: String): Color {
    val normalized = status.lowercase()
    return when {
        "emisi" in normalized || "ongoing" in normalized -> Color(0xFF1F8A5B)
        "final" in normalized || "complet" in normalized -> Color(0xFF3166C7)
        "paus" in normalized || "hiatus" in normalized -> Color(0xFFB7791F)
        "cancel" in normalized -> Color(0xFFB23A48)
        else -> Color(0xFF51617B)
    }
}

private fun periodicityTagColor(periodicity: String): Color {
    val normalized = periodicity.lowercase()
    return when {
        "seman" in normalized || "week" in normalized -> Color(0xFF7A3FC7)
        "mens" in normalized || "month" in normalized -> Color(0xFF1F7A8C)
        "diar" in normalized || "day" in normalized -> Color(0xFFB85C38)
        "irreg" in normalized -> Color(0xFF8A6B2F)
        else -> Color(0xFF5D6B82)
    }
}

private fun formatDateEu(input: String): String {
    val raw = input.take(10)
    return if (Regex("""\d{4}-\d{2}-\d{2}""").matches(raw)) {
        val (year, month, day) = raw.split("-")
        "$day/$month/$year"
    } else {
        input
    }
}
