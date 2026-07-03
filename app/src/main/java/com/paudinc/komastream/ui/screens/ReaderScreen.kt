package com.paudinc.komastream.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.forEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.imageLoader
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size
import android.webkit.CookieManager as WebkitCookieManager
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.ReaderPage
import com.paudinc.komastream.ui.components.cardBorder
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.OfflineChapterStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import okhttp3.Headers
import java.io.File

@Composable
fun ReaderScreen(
    strings: AppStrings,
    reader: ReaderData,
    offlineStore: OfflineChapterStore,
    initialPageIndex: Int,
    isDownloaded: Boolean,
    downloadPercent: Int?,
    onPagePositionChanged: (Int, Boolean) -> Unit,
    onToggleDownload: () -> Unit,
    isRead: Boolean,
    onToggleRead: () -> Unit,
    onOpenChapter: (String, String, Boolean) -> Unit,
    onOpenManga: (String) -> Unit,
    onBack: () -> Unit,
    isChapterLoading: Boolean = false,
) {
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val useLightReaderChrome = colorScheme.background.luminance() > 0.5f
    val outerBackgroundTop = if (useLightReaderChrome) colorScheme.surfaceVariant.copy(alpha = 0.55f) else Color(0xFF06040D)
    val outerBackgroundBottom = if (useLightReaderChrome) colorScheme.background else Color(0xFF06040D)
    val readerSurfaceColor = if (useLightReaderChrome) colorScheme.surface else Color(0xFF090811)
    val readerHeaderColor = if (useLightReaderChrome) colorScheme.surfaceContainerHighest else Color(0xFF0B0A13)
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imageWidthPx = remember(configuration.screenWidthDp, density) {
        with(density) {
            ((configuration.screenWidthDp.dp - 8.dp).toPx().toInt()).coerceAtLeast(1)
        }
    }
    val restoredPageIndex = remember(reader.chapterPath, initialPageIndex, reader.pages.size) {
        if (reader.pages.isEmpty()) 0 else initialPageIndex.coerceIn(0, reader.pages.lastIndex)
    }
    val latestOnPagePositionChanged by rememberUpdatedState(onPagePositionChanged)
    val latestOnOpenChapter by rememberUpdatedState(onOpenChapter)
    val currentChapterPath by rememberUpdatedState(reader.chapterPath)
    val previousChapterPath by rememberUpdatedState(reader.previousChapterPath)
    val nextChapterPath by rememberUpdatedState(reader.nextChapterPath)
    var sliderPage by remember(reader.chapterPath) { mutableIntStateOf(restoredPageIndex) }
    var pageTrackingReady by remember(reader.chapterPath) { mutableStateOf(false) }
    var allowAutoReadMark by remember(reader.chapterPath) { mutableStateOf(false) }
    var overflowExpanded by remember(reader.chapterPath) { mutableStateOf(false) }
    var zoomedPageKey by remember(reader.chapterPath) { mutableStateOf<String?>(null) }
    val prefetchedPageKeys = remember(reader.chapterPath) { mutableSetOf<String>() }
    var hasLoadedPageImage by remember(reader.chapterPath) { mutableStateOf(false) }
    var showFloatingHeader by remember(reader.chapterPath) { mutableStateOf(false) }
    var showChapterNavigationBar by remember(reader.chapterPath) { mutableStateOf(true) }
    val chapterSubtitle = remember(reader.chapterTitle) { readerChapterSubtitle(reader.chapterTitle) }

    LaunchedEffect(zoomedPageKey) {
    }

    LaunchedEffect(reader.chapterPath, restoredPageIndex, reader.pages.size) {
        pageTrackingReady = false
        allowAutoReadMark = false
        hasLoadedPageImage = false
        prefetchedPageKeys.clear()
        listState.scrollToItem(initialReaderItemIndex(restoredPageIndex, reader.pages.size))
        pageTrackingReady = true
    }

    LaunchedEffect(reader.chapterPath, listState, pageTrackingReady) {
        snapshotFlow {
            if (pageTrackingReady) currentReaderPageIndex(listState, reader.pages.size) else Int.MIN_VALUE
        }
        .filter { it != Int.MIN_VALUE }
        .distinctUntilChanged()
        .collect { pageIndex ->
            sliderPage = pageIndex
            latestOnPagePositionChanged(pageIndex, allowAutoReadMark)
            prefetchedPageKeys.addAll(
                prefetchReaderPages(
                    context = context,
                    offlineStore = offlineStore,
                    providerId = reader.providerId,
                    chapterPath = reader.chapterPath,
                    pages = reader.pages,
                    targetWidthPx = imageWidthPx,
                    startIndex = pageIndex + 1,
                    alreadyPrefetchedKeys = prefetchedPageKeys,
                )
            )
            allowAutoReadMark = true
        }
    }

    LaunchedEffect(reader.chapterPath, pageTrackingReady, hasLoadedPageImage) {
        if (pageTrackingReady && hasLoadedPageImage) {
            latestOnPagePositionChanged(
                currentReaderPageIndex(listState, reader.pages.size, sliderPage),
                allowAutoReadMark,
            )
        }
    }

    LaunchedEffect(reader.chapterPath, restoredPageIndex, reader.pages) {
            prefetchedPageKeys.addAll(
                prefetchReaderPages(
                    context = context,
                    offlineStore = offlineStore,
                    providerId = reader.providerId,
                    chapterPath = reader.chapterPath,
                    pages = reader.pages,
                    targetWidthPx = imageWidthPx,
                    startIndex = restoredPageIndex + 1,
                    alreadyPrefetchedKeys = prefetchedPageKeys,
                )
            )
    }

    LaunchedEffect(reader.chapterPath, listState) {
        var previousScrollPosition = 0
        snapshotFlow {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val firstVisibleOffset = listState.firstVisibleItemScrollOffset
            val absolutePosition = (firstVisibleIndex * 100000) + firstVisibleOffset
            val isNearTop = firstVisibleIndex == 0 && firstVisibleOffset < FLOATING_HEADER_TOP_THRESHOLD_PX
            val currentPageIndex = currentReaderPageIndex(listState, reader.pages.size, sliderPage)
            val isAtLastPage = reader.pages.isNotEmpty() && currentPageIndex == reader.pages.lastIndex
            Triple(absolutePosition, isNearTop, isAtLastPage)
        }
            .distinctUntilChanged()
            .collect { (scrollPosition, isNearTop, isAtLastPage) ->
                showFloatingHeader = when {
                    isNearTop -> false
                    scrollPosition < previousScrollPosition -> true
                    scrollPosition > previousScrollPosition -> false
                    else -> showFloatingHeader
                }
                showChapterNavigationBar = when {
                    isAtLastPage -> true
                    scrollPosition < previousScrollPosition -> true
                    scrollPosition > previousScrollPosition -> false
                    else -> showChapterNavigationBar
                }
                previousScrollPosition = scrollPosition
            }
    }

    DisposableEffect(reader.chapterPath, listState) {
        onDispose {
            if (pageTrackingReady) {
                latestOnPagePositionChanged(
                    currentReaderPageIndex(listState, reader.pages.size, sliderPage),
                    allowAutoReadMark,
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        outerBackgroundTop,
                        colorScheme.background,
                        outerBackgroundBottom,
                    )
                )
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(24.dp),
            color = readerSurfaceColor,
            border = cardBorder(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .edgeSwipeGesture(
                            dragThresholdPx = HORIZONTAL_DRAG_THRESHOLD_DP,
                            density = LocalDensity.current.density,
                            isZoomed = zoomedPageKey != null,
                            onSwipeLeft = { nextChapterPath?.let { latestOnOpenChapter(currentChapterPath, it, true) } },
                            onSwipeRight = { previousChapterPath?.let { latestOnOpenChapter(currentChapterPath, it, true) } },
                        ),
                    state = listState,
                    userScrollEnabled = zoomedPageKey == null,
                    contentPadding = PaddingValues(top = 6.dp, bottom = 86.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item {
                        ReaderHeaderCard(
                            strings = strings,
                            mangaTitle = reader.mangaTitle,
                            chapterSubtitle = chapterSubtitle,
                            readerHeaderColor = readerHeaderColor,
                            isRead = isRead,
                            downloadPercent = downloadPercent,
                            isDownloaded = isDownloaded,
                            overflowExpanded = overflowExpanded,
                            onOverflowExpandedChange = { overflowExpanded = it },
                            onBack = onBack,
                            onToggleRead = onToggleRead,
                            onOpenManga = { onOpenManga(reader.mangaDetailPath) },
                            onToggleDownload = onToggleDownload,
                        )
                    }
                    itemsIndexed(
                        items = reader.pages,
                        key = { _, page -> "${reader.chapterPath}:${page.id}" },
                        contentType = { _, _ -> "reader-page" }
                    ) { _, page ->
                        val pageKey = "${reader.chapterPath}:${page.id}"
                        ZoomableReaderPage(
                            providerId = reader.providerId,
                            chapterPath = reader.chapterPath,
                            page = page,
                            offlineStore = offlineStore,
                            useOfflineFile = isDownloaded || reader.providerId != "mkissa-en",
                            targetWidthPx = imageWidthPx,
                            onImageLoaded = {
                                hasLoadedPageImage = true
                            },
                            onZoomStateChanged = { isZoomed ->
                                zoomedPageKey = when {
                                    isZoomed -> pageKey
                                    zoomedPageKey == pageKey -> null
                                    else -> zoomedPageKey
                                }
                            },
                        )
                    }
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                            Text(
                                text = reader.chapterTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showFloatingHeader,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 2 }),
                ) {
                    ReaderHeaderCard(
                        strings = strings,
                        mangaTitle = reader.mangaTitle,
                        chapterSubtitle = chapterSubtitle,
                        readerHeaderColor = readerHeaderColor.copy(alpha = 0.97f),
                        isRead = isRead,
                        downloadPercent = downloadPercent,
                        isDownloaded = isDownloaded,
                        overflowExpanded = overflowExpanded,
                        onOverflowExpandedChange = { overflowExpanded = it },
                        onBack = onBack,
                        onToggleRead = onToggleRead,
                        onOpenManga = { onOpenManga(reader.mangaDetailPath) },
                        onToggleDownload = onToggleDownload,
                    )
                }

                AnimatedVisibility(
                    visible = showChapterNavigationBar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        border = cardBorder(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SmallReaderNavButton(
                                    onClick = { reader.previousChapterPath?.let { onOpenChapter(reader.chapterPath, it, true) } },
                                    enabled = reader.previousChapterPath != null,
                                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = strings.previous,
                                )
                                Text(
                                    text = "${sliderPage + 1} / ${reader.pages.size}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                SmallReaderNavButton(
                                    onClick = { reader.nextChapterPath?.let { onOpenChapter(reader.chapterPath, it, true) } },
                                    enabled = reader.nextChapterPath != null,
                                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = strings.next,
                                )
                            }
                            Slider(
                                value = sliderPage.toFloat(),
                                onValueChange = { sliderPage = it.toInt() },
                                onValueChangeFinished = {
                                    scope.launch {
                                        listState.animateScrollToItem((sliderPage + 1).coerceAtMost(reader.pages.size))
                                    }
                                },
                                valueRange = 0f..(reader.pages.lastIndex.coerceAtLeast(0)).toFloat(),
                                modifier = Modifier.height(22.dp),
                            )
                        }
                    }
                }

                if (isChapterLoading) {
                    ChapterLoadingOverlay(
                        modifier = Modifier.fillMaxSize(),
                        backgroundColor = readerSurfaceColor.copy(alpha = 0.85f),
                        text = strings.loadingChapter,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderHeaderCard(
    strings: AppStrings,
    mangaTitle: String,
    chapterSubtitle: String,
    readerHeaderColor: Color,
    isRead: Boolean,
    downloadPercent: Int?,
    isDownloaded: Boolean,
    overflowExpanded: Boolean,
    onOverflowExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onToggleRead: () -> Unit,
    onOpenManga: () -> Unit,
    onToggleDownload: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        shape = RoundedCornerShape(22.dp),
        color = readerHeaderColor,
        border = cardBorder(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(36.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                ReaderHeaderActionButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = strings.back,
                    onClick = onBack,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = mangaTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = chapterSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            Row(
                modifier = Modifier.width(72.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderHeaderActionButton(
                    icon = if (isRead) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = strings.chapterReadAction,
                    onClick = onToggleRead,
                )
                Box {
                    ReaderHeaderActionButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = strings.settings,
                        onClick = { onOverflowExpandedChange(true) },
                    )
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { onOverflowExpandedChange(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text(strings.manga) },
                            onClick = {
                                onOverflowExpandedChange(false)
                                onOpenManga()
                            },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (isRead) strings.markAllUnread else strings.chapterReadAction
                                )
                            },
                            onClick = {
                                onOverflowExpandedChange(false)
                                onToggleRead()
                            },
                            leadingIcon = {
                                Icon(
                                    if (isRead) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = null,
                                )
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when {
                                        downloadPercent != null -> strings.cancel
                                        isDownloaded -> strings.removeDownload
                                        else -> strings.download
                                    }
                                )
                            },
                            onClick = {
                                onOverflowExpandedChange(false)
                                onToggleDownload()
                            },
                            leadingIcon = {
                                Icon(
                                    if (downloadPercent != null || !isDownloaded) Icons.Default.Download else Icons.Default.Delete,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderHeaderActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(34.dp),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SmallReaderNavButton(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun readerChapterSubtitle(chapterTitle: String): String {
    val normalized = chapterTitle.trim()
    val explicit = Regex("(?i)(chapter|capitulo|capítulo)\\s*([0-9]+(?:\\.[0-9]+)?)").find(normalized)
    if (explicit != null) return "Chapter ${explicit.groupValues[2]}"
    val numeric = Regex("([0-9]+(?:\\.[0-9]+)?)").find(normalized)
    return if (numeric != null) "Chapter ${numeric.groupValues[1]}" else normalized
}

private fun currentReaderPageIndex(
    listState: androidx.compose.foundation.lazy.LazyListState,
    pageCount: Int,
    fallbackPageIndex: Int = 0,
): Int {
    if (pageCount <= 0) return 0
    val layoutInfo = listState.layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty() || layoutInfo.totalItemsCount < 2) {
        return fallbackPageIndex.coerceIn(0, pageCount - 1)
    }

    val firstVisible = visibleItems.firstOrNull()
    return when {
        firstVisible != null && firstVisible.index > 0 -> {
            (firstVisible.index - 1).coerceIn(0, pageCount - 1)
        }
        else -> 0
    }
}

private fun initialReaderItemIndex(
    pageIndex: Int,
    pageCount: Int,
): Int {
    if (pageCount <= 0) return 0
    return if (pageIndex <= 0) 0 else (pageIndex + 1).coerceAtMost(pageCount)
}

private const val DOUBLE_TAP_ZOOM_SCALE = 2f
private const val ZOOM_PAN_SPEED_MULTIPLIER = 1.6f
private const val READER_GESTURE_TAG = "KomaReaderGesture"
private const val HORIZONTAL_DRAG_THRESHOLD_DP = 100f
private const val READER_PREFETCH_AHEAD_PAGES = 3
private const val FLOATING_HEADER_TOP_THRESHOLD_PX = 48

@Composable
fun ReaderChapterNavigationButtons(
    currentChapterPath: String,
    previousChapterPath: String?,
    nextChapterPath: String?,
    strings: AppStrings,
    onOpenChapter: (String, String, Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { previousChapterPath?.let { onOpenChapter(currentChapterPath, it, true) } },
            enabled = previousChapterPath != null,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
            Spacer(Modifier.size(4.dp))
            Text(strings.previous)
        }
        Button(
            onClick = { nextChapterPath?.let { onOpenChapter(currentChapterPath, it, true) } },
            enabled = nextChapterPath != null,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                contentColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text(strings.next)
            Spacer(Modifier.size(4.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
fun ZoomableReaderPage(
    providerId: String,
    chapterPath: String,
    page: ReaderPage,
    offlineStore: OfflineChapterStore,
    useOfflineFile: Boolean,
    targetWidthPx: Int,
    onImageLoaded: () -> Unit,
    onZoomStateChanged: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val useLightReaderChrome = colorScheme.background.luminance() > 0.5f
    val pageSurfaceColor = if (useLightReaderChrome) colorScheme.surfaceContainerLow else Color.Black
    val zoomBorderColor = if (useLightReaderChrome) colorScheme.primary else colorScheme.tertiary
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val pageKey = remember(providerId, chapterPath, page.id) { "$providerId:$chapterPath:${page.id}" }
    LaunchedEffect(scale > 1f) {
        onZoomStateChanged(scale > 1f)
    }

    val offlineFile by offlineReaderPageFileState(
        offlineStore = offlineStore,
        providerId = providerId,
        chapterPath = chapterPath,
        page = page,
        enabled = useOfflineFile,
    )

    val pageZoomModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 1.dp)
        .zIndex(if (scale > 1f) 10f else 0f)
        .pointerInput(providerId, chapterPath, page.id) {
            detectTapGestures(
                onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = androidx.compose.ui.geometry.Offset.Zero
                    } else {
                        scale = DOUBLE_TAP_ZOOM_SCALE
                        offset = androidx.compose.ui.geometry.Offset.Zero
                    }
                }
            )
        }
        .graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = offset.x,
            translationY = offset.y,
            clip = false,
        )
        .pointerInput(providerId, chapterPath, page.id) {
            awaitEachGesture {
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }

                    when {
                        pressed.size >= 2 -> {
                            val currentCentroid = pressed
                                .map { it.position }
                                .reduce { acc, position -> acc + position } / pressed.size.toFloat()
                            val previousCentroid = pressed
                                .map { it.previousPosition }
                                .reduce { acc, position -> acc + position } / pressed.size.toFloat()
                            val currentSpan = pressed
                                .map { (it.position - currentCentroid).getDistance() }
                                .average()
                                .toFloat()
                            val previousSpan = pressed
                                .map { (it.previousPosition - previousCentroid).getDistance() }
                                .average()
                                .toFloat()
                            val zoomChange = if (previousSpan > 0f) {
                                (currentSpan / previousSpan).takeIf { it.isFinite() } ?: 1f
                            } else {
                                1f
                            }
                            val panChange = pressed
                                .map { it.position - it.previousPosition }
                                .reduce { acc, delta -> acc + delta } / pressed.size.toFloat()
                            val updatedScale = (scale * zoomChange).coerceIn(1f, 4f)
                            val appliedPan = panChange * ZOOM_PAN_SPEED_MULTIPLIER
                            if (kotlin.math.abs(zoomChange - 1f) > 0.001f ||
                                panChange != androidx.compose.ui.geometry.Offset.Zero
                            ) {
                            }
                            scale = updatedScale
                            offset = if (updatedScale > 1f) {
                                offset + appliedPan
                            } else {
                                androidx.compose.ui.geometry.Offset.Zero
                            }
                            pressed.forEach { it.consume() }
                        }

                        pressed.size == 1 && scale > 1f -> {
                            val change = pressed.first()
                            val panChange = change.position - change.previousPosition
                            if (panChange != androidx.compose.ui.geometry.Offset.Zero) {
                                val appliedPan = panChange * ZOOM_PAN_SPEED_MULTIPLIER
                                offset += appliedPan
                                change.consume()
                            }
                        }

                        else -> Unit
                    }
                } while (event.changes.any { it.pressed })
            }
        }

    val imageRequest = rememberReaderImageRequest(
        context = context,
        providerId = providerId,
        chapterPath = chapterPath,
        page = page,
        targetWidthPx = targetWidthPx,
        offlineImageFile = offlineFile,
    )
    Box(modifier = pageZoomModifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = pageSurfaceColor,
            border = if (scale > 1f) BorderStroke(4.dp, zoomBorderColor) else null,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(pageSurfaceColor),
                contentAlignment = Alignment.TopCenter
            ) {
                ReaderNetworkImage(
                    pageNumberLabel = page.numberLabel,
                    imageModifier = Modifier.fillMaxWidth(),
                    imageRequest = imageRequest,
                    pageSurfaceColor = pageSurfaceColor,
                    onSuccess = onImageLoaded,
                )
            }
        }
    }
}

@Composable
private fun ReaderNetworkImage(
    pageNumberLabel: String,
    imageModifier: Modifier,
    imageRequest: ImageRequest,
    pageSurfaceColor: Color,
    onSuccess: () -> Unit,
) {
    SubcomposeAsyncImage(
        model = imageRequest,
        contentDescription = "Page $pageNumberLabel",
        modifier = imageModifier.background(pageSurfaceColor),
        contentScale = ContentScale.FillWidth,
        onSuccess = { onSuccess() },
        loading = {
            ReaderImageLoadingPlaceholder(pageSurfaceColor = pageSurfaceColor)
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(pageSurfaceColor),
            )
        },
    )
}

@Composable
private fun ReaderImageLoadingPlaceholder(pageSurfaceColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .background(pageSurfaceColor),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun offlineReaderPageFileState(
    offlineStore: OfflineChapterStore,
    providerId: String,
    chapterPath: String,
    page: ReaderPage,
    enabled: Boolean,
): State<File?> {
    return produceState<File?>(initialValue = null, providerId, chapterPath, page.offlineFileName, enabled) {
        if (!enabled) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            offlineStore.getAvailableReaderPageFile(providerId, chapterPath, page)
        }
    }
}

@Composable
private fun rememberReaderImageRequest(
    context: android.content.Context,
    providerId: String,
    chapterPath: String,
    page: ReaderPage,
    targetWidthPx: Int,
    offlineImageFile: File?,
): ImageRequest {
    val requestHeaders = remember(providerId, chapterPath) {
        readerRequestHeaders(providerId, chapterPath)
    }
    val imageSource = offlineImageFile ?: page.imageUrl
    val sizeResolver = remember(targetWidthPx) {
        coil.size.SizeResolver(Size(targetWidthPx, Dimension.Undefined))
    }
    return remember(context, imageSource, requestHeaders, sizeResolver) {
        ImageRequest.Builder(context)
            .data(imageSource)
            .size(sizeResolver)
            .apply {
                requestHeaders?.let { headers(it) }
            }
            .crossfade(false)
            .build()
    }
}

private suspend fun prefetchReaderPages(
    context: android.content.Context,
    offlineStore: OfflineChapterStore,
    providerId: String,
    chapterPath: String,
    pages: List<ReaderPage>,
    targetWidthPx: Int,
    startIndex: Int,
    alreadyPrefetchedKeys: Set<String> = emptySet(),
): Set<String> {
    if (pages.isEmpty()) return alreadyPrefetchedKeys
    val fromIndex = startIndex.coerceAtLeast(0)
    val toIndex = (fromIndex + READER_PREFETCH_AHEAD_PAGES - 1).coerceAtMost(pages.lastIndex)
    if (fromIndex > toIndex) return alreadyPrefetchedKeys

    val loader = context.imageLoader
    val updatedPrefetchedKeys = alreadyPrefetchedKeys.toMutableSet()
    for (index in fromIndex..toIndex) {
        val page = pages[index]
        val pageKey = "$providerId:$chapterPath:${page.id}"
        if (!updatedPrefetchedKeys.add(pageKey)) continue
        val offlineFile = withContext(Dispatchers.IO) {
            offlineStore.getAvailableReaderPageFile(providerId, chapterPath, page)
        }
        val imageSource = offlineFile?.takeIf { it.exists() } ?: page.imageUrl
        val sizeResolver = coil.size.SizeResolver(Size(targetWidthPx, Dimension.Undefined))
        val request = ImageRequest.Builder(context)
            .data(imageSource)
            .size(sizeResolver)
            .apply {
                readerRequestHeaders(providerId, chapterPath)?.let { headers(it) }
            }
            .crossfade(false)
            .build()
        loader.enqueue(request)
    }
    return updatedPrefetchedKeys
}

private fun readerRequestHeaders(providerId: String, chapterPath: String): Headers? {
    return when (providerId) {
        "mangadotnet-en" -> Headers.Builder()
            .add("User-Agent", com.paudinc.komastream.provider.providers.MangadotProvider.USER_AGENT)
            .add("Referer", "https://mangadot.net/")
            .apply {
                val cookieHeader = WebkitCookieManager.getInstance().getCookie("https://mangadot.net").orEmpty()
                if (cookieHeader.isNotBlank()) {
                    add("Cookie", cookieHeader)
                }
            }
            .build()
        "inmanga-es" -> Headers.Builder()
            .add("Referer", "https://inmanga.com$chapterPath")
            .add(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            .build()
        "marmota-es" -> Headers.Builder()
            .add("Referer", "https://marmota.me$chapterPath")
            .add(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            .add("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .apply {
                val cookieHeader = WebkitCookieManager.getInstance().getCookie("https://marmota.me").orEmpty()
                if (cookieHeader.isNotBlank()) {
                    add("Cookie", cookieHeader)
                }
            }
            .build()
        "manhwa-latino-es" -> Headers.Builder()
            .add("User-Agent", com.paudinc.komastream.provider.providers.ManhwaLatinoProvider.USER_AGENT)
            .add("Referer", "https://manhwa-latino.com/")
            .add("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .build()
        "mkissa-en" -> Headers.Builder()
            .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:152.0) Gecko/20100101 Firefox/152.0")
            .add("Referer", "https://mkissa.to/")
            .add("Origin", "https://mkissa.to")
            .add("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .build()
        "leermangaesp-es" -> Headers.Builder()
            .add("User-Agent", com.paudinc.komastream.provider.providers.LeerMangaEspProvider.USER_AGENT)
            .add("Referer", "https://leermangaesp.net$chapterPath")
            .add("Accept", com.paudinc.komastream.provider.providers.LeerMangaEspProvider.IMAGE_ACCEPT)
            .build()
        else -> null
    }
}

fun Modifier.edgeSwipeGesture(
    dragThresholdPx: Float,
    density: Float,
    isZoomed: Boolean,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
): Modifier = this.pointerInput(dragThresholdPx, isZoomed) {
    val thresholdPx = dragThresholdPx * density
    val verticalCancelPx = thresholdPx * 0.5f

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (isZoomed) return@awaitEachGesture

        val startX = down.position.x
        val startY = down.position.y
        var totalDragX = 0f
        var totalDragY = 0f
        var triggered = false
        var cancelled = false

        do {
            val event = awaitPointerEvent()
            val drag = event.changes.firstOrNull() ?: continue
            if (!drag.pressed) break

            totalDragX = drag.position.x - startX
            totalDragY = drag.position.y - startY

            if (!cancelled && kotlin.math.abs(totalDragY) > verticalCancelPx) {
                cancelled = true
            }

            if (!cancelled && kotlin.math.abs(totalDragX) > thresholdPx && !triggered) {
                triggered = true
                event.changes.forEach { it.consume() }
                if (totalDragX < 0) {
                    onSwipeLeft()
                } else {
                    onSwipeRight()
                }
                break
            } else if (cancelled && kotlin.math.abs(totalDragX) > thresholdPx * 2f) {
                break
            }
        } while (true)
    }
}

@Composable
private fun ChapterLoadingOverlay(
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    text: String,
) {
    Surface(
        modifier = modifier,
        color = backgroundColor,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 4.dp,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
