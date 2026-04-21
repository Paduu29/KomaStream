package com.paudinc.komastream.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.ReaderPage
import com.paudinc.komastream.ui.components.LoadingPlaceholder
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.OfflineChapterStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.io.File
import okhttp3.Headers

@OptIn(ExperimentalLayoutApi::class)
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
    onOpenChapter: (String, String, Boolean) -> Unit,
    onOpenManga: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val restoredPageIndex = remember(reader.chapterPath, initialPageIndex, reader.pages.size) {
        if (reader.pages.isEmpty()) {
            0
        } else if (initialPageIndex in reader.pages.indices) {
            initialPageIndex
        } else {
            0
        }
    }

    LaunchedEffect(reader.chapterPath, restoredPageIndex, reader.pages.size) {
        listState.scrollToItem((restoredPageIndex + 1).coerceAtMost(reader.pages.size))
    }

    LaunchedEffect(reader.chapterPath, listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .map { index -> (index - 1).coerceAtLeast(0) }
            .filter { pageIndex -> pageIndex in reader.pages.indices }
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
                if (downloadPercent != null) {
                    Text("${strings.downloading} ${downloadPercent}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                } else if (isDownloaded) {
                    Text(strings.offlineAvailable, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { onOpenManga(reader.mangaDetailPath) }) { Text(strings.manga) }
                    Button(onClick = onToggleDownload, enabled = downloadPercent == null) {
                        Icon(
                            if (downloadPercent != null || !isDownloaded) Icons.Default.Download else Icons.Default.Delete,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (downloadPercent != null) "${strings.downloading} ${downloadPercent}%"
                            else if (isDownloaded) strings.removeDownload
                            else strings.download
                        )
                    }
                }
                ReaderChapterNavigationButtons(
                    currentChapterPath = reader.chapterPath,
                    previousChapterPath = reader.previousChapterPath,
                    nextChapterPath = reader.nextChapterPath,
                    strings = strings,
                    onOpenChapter = onOpenChapter,
                )
            }
        }
        itemsIndexed(
            items = reader.pages,
            key = { index, page -> "${reader.chapterPath}:${page.id}:$index" }
        ) { _, page ->
            ZoomableReaderPage(
                providerId = reader.providerId,
                chapterPath = reader.chapterPath,
                page = page,
                offlineStore = offlineStore,
            )
        }
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HorizontalDivider()
                Text(
                    text = reader.chapterTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ReaderChapterNavigationButtons(
                    currentChapterPath = reader.chapterPath,
                    previousChapterPath = reader.previousChapterPath,
                    nextChapterPath = reader.nextChapterPath,
                    strings = strings,
                    onOpenChapter = onOpenChapter,
                )
            }
        }
    }
}

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
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(strings.previous)
        }
        Button(
            onClick = { nextChapterPath?.let { onOpenChapter(currentChapterPath, it, true) } },
            enabled = nextChapterPath != null,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Text(strings.next)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
fun ZoomableReaderPage(
    providerId: String,
    chapterPath: String,
    page: ReaderPage,
    offlineStore: OfflineChapterStore
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    val offlineFile = remember(providerId, chapterPath, page.offlineFileName) {
        if (page.offlineFileName.isBlank()) null else {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(com.paudinc.komastream.utils.qualifyProviderValue(providerId, chapterPath).toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            val name = digest.joinToString("") { "%02x".format(it) }
            File(File(offlineStore.context.filesDir, "offline_chapters"), "$name/${page.offlineFileName}")
        }
    }

    val imageModifier = Modifier
        .fillMaxWidth()
        .graphicsLayer(
            scaleX = scale,
            scaleY = scale,
            translationX = offset.x,
            translationY = offset.y
        )
        .pointerInput(Unit) {
            awaitEachGesture {
                do {
                    val event = awaitPointerEvent()
                    scale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        val pan = event.calculatePan()
                        offset = androidx.compose.ui.geometry.Offset(offset.x + pan.x, offset.y + pan.y)
                    } else {
                        offset = androidx.compose.ui.geometry.Offset.Zero
                    }
                } while (event.changes.any { it.pressed && it.positionChanged() })
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .padding(vertical = 4.dp),
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
                    modifier = imageModifier,
                    contentScale = ContentScale.FillWidth,
                )
            } else {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = "Page ${page.numberLabel}",
                    modifier = imageModifier,
                    contentScale = ContentScale.FillWidth,
                )
            }
        } else {
            AsyncImage(
                model = imageRequest,
                contentDescription = "Page ${page.numberLabel}",
                modifier = imageModifier,
                contentScale = ContentScale.FillWidth,
            )
        }
    }
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
