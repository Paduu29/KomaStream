package com.paudinc.komastream.ui.screens

import android.graphics.BitmapFactory
import android.util.Log
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.ReaderPage
import com.paudinc.komastream.ui.components.cardBorder
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.OfflineChapterStore
import kotlinx.coroutines.launch
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
    onPagePositionChanged: (Int) -> Unit,
    onToggleDownload: () -> Unit,
    isRead: Boolean,
    onToggleRead: () -> Unit,
    onOpenChapter: (String, String, Boolean) -> Unit,
    onOpenManga: (String) -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val useLightReaderChrome = colorScheme.background.luminance() > 0.5f
    val outerBackgroundTop = if (useLightReaderChrome) colorScheme.surfaceVariant.copy(alpha = 0.55f) else Color(0xFF06040D)
    val outerBackgroundBottom = if (useLightReaderChrome) colorScheme.background else Color(0xFF06040D)
    val readerSurfaceColor = if (useLightReaderChrome) colorScheme.surface else Color(0xFF090811)
    val readerHeaderColor = if (useLightReaderChrome) colorScheme.surfaceContainerHighest else Color(0xFF0B0A13)
    val restoredPageIndex = remember(reader.chapterPath, initialPageIndex, reader.pages.size) {
        if (reader.pages.isEmpty()) 0 else initialPageIndex.coerceIn(0, reader.pages.lastIndex)
    }
    var sliderPage by remember(reader.chapterPath) { mutableIntStateOf(restoredPageIndex) }
    var overflowExpanded by remember(reader.chapterPath) { mutableStateOf(false) }
    var zoomedPageKey by remember(reader.chapterPath) { mutableStateOf<String?>(null) }
    val chapterSubtitle = remember(reader.chapterTitle) { readerChapterSubtitle(reader.chapterTitle) }

    LaunchedEffect(zoomedPageKey) {
        Log.d(READER_GESTURE_TAG, "zoomedPageKey=$zoomedPageKey chapter=${reader.chapterPath}")
    }

    LaunchedEffect(reader.chapterPath, restoredPageIndex, reader.pages.size) {
        listState.scrollToItem((restoredPageIndex + 1).coerceAtMost(reader.pages.size))
    }

    LaunchedEffect(reader.chapterPath, listState) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val totalItems = layoutInfo.totalItemsCount
            val pageCount = reader.pages.size

            if (visibleItems.isEmpty() || totalItems < 2) {
                return@snapshotFlow 0
            }

            val lastVisible = visibleItems.lastOrNull()
            val firstVisible = visibleItems.firstOrNull()

            val targetIndex = when {
                lastVisible != null && lastVisible.index > 1 -> {
                    (lastVisible.index - 2).coerceIn(0, pageCount - 1)
                }
                firstVisible != null && firstVisible.index > 0 -> {
                    (firstVisible.index - 1).coerceIn(0, pageCount - 1)
                }
                else -> 0
            }

            targetIndex
        }
        .distinctUntilChanged()
        .collect { pageIndex ->
            sliderPage = pageIndex
            onPagePositionChanged(pageIndex)
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
                            onSwipeLeft = { reader.nextChapterPath?.let { onOpenChapter(reader.chapterPath, it, true) } },
                            onSwipeRight = { reader.previousChapterPath?.let { onOpenChapter(reader.chapterPath, it, false) } },
                        ),
                    state = listState,
                    userScrollEnabled = zoomedPageKey == null,
                    contentPadding = PaddingValues(top = 6.dp, bottom = 86.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
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
                                        text = reader.mangaTitle,
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
                                            onClick = { overflowExpanded = true },
                                        )
                                        DropdownMenu(
                                            expanded = overflowExpanded,
                                            onDismissRequest = { overflowExpanded = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(strings.manga) },
                                                onClick = {
                                                    overflowExpanded = false
                                                    onOpenManga(reader.mangaDetailPath)
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
                                                    overflowExpanded = false
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
                                                    overflowExpanded = false
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
                    itemsIndexed(
                        items = reader.pages,
                        key = { index, page -> "${reader.chapterPath}:${page.id}:$index" }
                    ) { _, page ->
                        val pageKey = "${reader.chapterPath}:${page.id}"
                        ZoomableReaderPage(
                            providerId = reader.providerId,
                            chapterPath = reader.chapterPath,
                            page = page,
                            offlineStore = offlineStore,
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

                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                onClick = { reader.previousChapterPath?.let { onOpenChapter(reader.chapterPath, it, false) } },
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

private const val DOUBLE_TAP_ZOOM_SCALE = 2f
private const val ZOOM_PAN_SPEED_MULTIPLIER = 1.6f
private const val READER_GESTURE_TAG = "KomaReaderGesture"
private const val HORIZONTAL_DRAG_THRESHOLD_DP = 100f

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
            onClick = { previousChapterPath?.let { onOpenChapter(currentChapterPath, it, false) } },
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

    val offlineFile = remember(providerId, chapterPath, page.offlineFileName) {
        if (page.offlineFileName.isBlank()) null else {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(com.paudinc.komastream.utils.qualifyProviderValue(providerId, chapterPath).toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            val name = digest.joinToString("") { "%02x".format(it) }
            File(File(offlineStore.context.filesDir, "offline_chapters"), "$name/${page.offlineFileName}")
        }
    }

    val pageZoomModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 1.dp)
        .zIndex(if (scale > 1f) 10f else 0f)
        .pointerInput(providerId, chapterPath, page.id) {
            detectTapGestures(
                onDoubleTap = {
                    Log.d(READER_GESTURE_TAG, "double-tap page=$pageKey scaleBefore=$scale")
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
            Log.d(READER_GESTURE_TAG, "attach-raw-gestures page=$pageKey")
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
                                Log.d(
                                    READER_GESTURE_TAG,
                                    "raw-transform page=$pageKey pointers=${pressed.size} span=$currentSpan zoomChange=$zoomChange pan=(${panChange.x},${panChange.y}) scale=$scale->$updatedScale"
                                )
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
                                Log.d(
                                    READER_GESTURE_TAG,
                                    "raw-drag page=$pageKey pan=(${panChange.x},${panChange.y}) scale=$scale"
                                )
                                offset += appliedPan
                                change.consume()
                            }
                        }

                        else -> Unit
                    }
                } while (event.changes.any { it.pressed })
            }
        }

    val imageRequest = remember(providerId, chapterPath, page.imageUrl, context) {
        ImageRequest.Builder(context)
            .data(page.imageUrl)
            .apply {
                readerRequestHeaders(providerId, chapterPath)?.let { headers(it) }
            }
            .crossfade(false)
            .build()
    }
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
                contentAlignment = Alignment.Center
            ) {
                if (offlineFile != null && offlineFile.exists()) {
                    val bitmap = remember(offlineFile) {
                        BitmapFactory.decodeFile(offlineFile.absolutePath)?.asImageBitmap()
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Page ${page.numberLabel}",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                        )
                    } else {
                        ReaderNetworkImage(
                            pageNumberLabel = page.numberLabel,
                            imageModifier = Modifier.fillMaxWidth(),
                            imageRequest = imageRequest,
                        )
                    }
                } else {
                    ReaderNetworkImage(
                        pageNumberLabel = page.numberLabel,
                        imageModifier = Modifier.fillMaxWidth(),
                        imageRequest = imageRequest,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderNetworkImage(
    pageNumberLabel: String,
    imageModifier: Modifier,
    imageRequest: ImageRequest,
) {
    AsyncImage(
        model = imageRequest,
        contentDescription = "Page $pageNumberLabel",
        modifier = imageModifier,
        contentScale = ContentScale.FillWidth,
    )
}

private fun readerRequestHeaders(providerId: String, chapterPath: String): Headers? {
    return when (providerId) {
        "inmanga-es" -> Headers.Builder()
            .add("Referer", "https://inmanga.com$chapterPath")
            .add(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
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

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (isZoomed) return@awaitEachGesture

        val startX = down.position.x
        var totalDragX = 0f
        var triggered = false

        do {
            val event = awaitPointerEvent()
            val drag = event.changes.firstOrNull() ?: continue
            if (!drag.pressed) break

            totalDragX = drag.position.x - startX

            if (kotlin.math.abs(totalDragX) > thresholdPx && !triggered) {
                triggered = true
                event.changes.forEach { it.consume() }
                if (totalDragX < 0) {
                    Log.d(READER_GESTURE_TAG, "swipe LEFT triggered dragX=$totalDragX")
                    onSwipeLeft()
                } else {
                    Log.d(READER_GESTURE_TAG, "swipe RIGHT triggered dragX=$totalDragX")
                    onSwipeRight()
                }
                break
            }
        } while (true)
    }
}
