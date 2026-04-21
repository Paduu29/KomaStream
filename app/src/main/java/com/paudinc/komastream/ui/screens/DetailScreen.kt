package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.ui.components.*
import com.paudinc.komastream.utils.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    strings: AppStrings,
    detail: MangaDetail,
    isFavorite: Boolean,
    autoJumpToUnread: Boolean,
    readChapters: Set<String>,
    lastOpenedChapterPath: String,
    downloadedChapters: Set<String>,
    downloadProgress: Map<String, Int>,
    isBulkUpdatingChapters: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleChapterRead: (String) -> Unit,
    onSetAllChaptersRead: (Boolean) -> Unit,
    onSetUntilChapterRead: (Double, Boolean) -> Unit,
    onToggleChapterDownload: (String, Boolean) -> Unit,
    onReadChapter: (String) -> Unit,
) {
    var chapterQuery by rememberSaveable(detail.providerId, detail.detailPath) { mutableStateOf("") }
    var bulkChapterInput by rememberSaveable(detail.providerId, detail.detailPath) { mutableStateOf("") }
    var hasAutoPositionedChapterList by rememberSaveable(detail.providerId, detail.detailPath, chapterQuery) { mutableStateOf(false) }
    var suppressAutoPositioning by rememberSaveable(detail.providerId, detail.detailPath) { mutableStateOf(false) }
    val favoriteStateAtEntry = rememberSaveable(detail.providerId, detail.detailPath) { isFavorite }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val canonicalReadChapterKeys = remember(detail.providerId, readChapters) {
        canonicalChapterKeys(detail.providerId, readChapters)
    }
    val chapterPathsByLabel = remember(detail.providerId, detail.detailPath, detail.chapters) {
        detail.chapters.associate { chapter ->
            buildChapterPath(detail.detailPath, chapter) to canonicalChapterKey(detail.providerId, buildChapterPath(detail.detailPath, chapter))
        }
    }

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
    val targetUnreadChapterPath = remember(
        detail.providerId,
        detail.detailPath,
        detail.chapters,
        readChapters,
        lastOpenedChapterPath,
        favoriteStateAtEntry,
        autoJumpToUnread,
    ) {
        resolveTargetUnreadChapterPath(
            providerId = detail.providerId,
            detailPath = detail.detailPath,
            chapters = detail.chapters,
            readChapters = readChapters,
            lastOpenedChapterPath = lastOpenedChapterPath,
            isFavorite = favoriteStateAtEntry,
            autoJumpToUnread = autoJumpToUnread,
        )
    }
    val targetUnreadIndex = remember(filteredChapters, targetUnreadChapterPath, detail.detailPath) {
        targetUnreadChapterPath?.let { path ->
            filteredChapters
                .indexOfFirst { chapter -> buildChapterPath(detail.detailPath, chapter) == path }
                .takeIf { it >= 0 }
        }
    }
    val lastUnreadIndex = remember(filteredChapters, canonicalReadChapterKeys, detail.detailPath, detail.providerId) {
        filteredChapters.indexOfLast { chapter ->
            val path = buildChapterPath(detail.detailPath, chapter)
            chapterPathsByLabel[path] !in canonicalReadChapterKeys
        }.takeIf { it >= 0 }
    }
    val lastOpenedChapterLabel = remember(detail.chapters, lastOpenedChapterPath, detail.detailPath) {
        detail.chapters.firstOrNull { chapter ->
            buildChapterPath(detail.detailPath, chapter) == lastOpenedChapterPath
        }?.chapterLabel.orEmpty()
    }
    val unreadCount = remember(detail.chapters, canonicalReadChapterKeys, detail.detailPath, detail.providerId) {
        detail.chapters.count { chapter ->
            val path = buildChapterPath(detail.detailPath, chapter)
            chapterPathsByLabel[path] !in canonicalReadChapterKeys
        }
    }

    LaunchedEffect(detail.providerId, detail.detailPath, chapterQuery, targetUnreadIndex) {
        if (chapterQuery.isNotBlank()) return@LaunchedEffect
        if (suppressAutoPositioning) return@LaunchedEffect
        if (hasAutoPositionedChapterList) return@LaunchedEffect
        val targetIndex = targetUnreadIndex ?: return@LaunchedEffect
        val chapterStartIndex = 2
        listState.scrollToItem((targetIndex + chapterStartIndex).coerceAtLeast(0))
        hasAutoPositionedChapterList = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
        ) {
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
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (detail.status.isNotBlank()) {
                                    TagChip(detail.status, containerColor = statusTagColor(detail.status), labelColor = Color.White)
                                }
                                if (detail.periodicity.isNotBlank()) {
                                    TagChip(detail.periodicity, containerColor = periodicityTagColor(detail.periodicity), labelColor = Color.White)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onToggleFavorite,
                            modifier = Modifier.weight(1f),
                            colors = if (isFavorite) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) else ButtonDefaults.buttonColors()
                        ) {
                            Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isFavorite) strings.removeFromFavorites else strings.favorites)
                        }
                        if (targetUnreadChapterPath != null) {
                            Button(onClick = { onReadChapter(targetUnreadChapterPath) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.read)
                            }
                        }
                    }
                    Text(detail.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    OutlinedCard(
                        shape = RoundedCornerShape(20.dp),
                        border = CardDefaults.outlinedCardBorder().copy(
                            brush = androidx.compose.ui.graphics.SolidColor(
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                            )
                        ),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        strings.chaptersCount(detail.chapters.size),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        "$unreadCount ${strings.unread.lowercase()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(onClick = {
                                        suppressAutoPositioning = true
                                        hasAutoPositionedChapterList = true
                                        onSetAllChaptersRead(true)
                                    }) {
                                        Icon(Icons.Default.DoneAll, contentDescription = strings.markAllRead)
                                    }
                                    IconButton(onClick = {
                                        suppressAutoPositioning = true
                                        hasAutoPositionedChapterList = true
                                        onSetAllChaptersRead(false)
                                    }) {
                                        Icon(Icons.Default.RemoveDone, contentDescription = strings.markAllUnread)
                                    }
                                }
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (lastOpenedChapterLabel.isNotBlank()) {
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(lastOpenedChapterLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            leadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                }
                                if (targetUnreadChapterPath != null) {
                                    AssistChip(
                                        onClick = { onReadChapter(targetUnreadChapterPath) },
                                        label = {
                                            Text(
                                                detail.chapters.firstOrNull { buildChapterPath(detail.detailPath, it) == targetUnreadChapterPath }?.chapterLabel
                                                    ?: strings.read
                                            )
                                        },
                                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
                                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        ),
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = chapterQuery,
                                onValueChange = {
                                    chapterQuery = it
                                    suppressAutoPositioning = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(strings.searchChapter) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                            )
                            OutlinedTextField(
                                value = bulkChapterInput,
                                onValueChange = { bulkChapterInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(strings.untilChapter) },
                                placeholder = { Text(strings.untilChapterPlaceholder) },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    val value = parseChapterInput(bulkChapterInput)
                                    if (value != null) {
                                        onSetUntilChapterRead(value, true)
                                        bulkChapterInput = ""
                                    }
                                })
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        suppressAutoPositioning = true
                                        hasAutoPositionedChapterList = true
                                        val value = parseChapterInput(bulkChapterInput)
                                        if (value != null) {
                                            onSetUntilChapterRead(value, true)
                                            bulkChapterInput = ""
                                        }
                                    },
                                    enabled = bulkChapterInput.isNotBlank(),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    ),
                                ) {
                                    Text(strings.readToX)
                                }
                                OutlinedButton(
                                    onClick = {
                                        suppressAutoPositioning = true
                                        hasAutoPositionedChapterList = true
                                        val value = parseChapterInput(bulkChapterInput)
                                        if (value != null) {
                                            onSetUntilChapterRead(value, false)
                                            bulkChapterInput = ""
                                        }
                                    },
                                    enabled = bulkChapterInput.isNotBlank(),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    ),
                                    border = ButtonDefaults.outlinedButtonBorder(enabled = bulkChapterInput.isNotBlank()).copy(
                                        brush = androidx.compose.ui.graphics.SolidColor(
                                            if (bulkChapterInput.isNotBlank()) MaterialTheme.colorScheme.outline
                                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                        )
                                    ),
                                ) {
                                    Text(strings.unreadToX)
                                }
                            }
                        }
                    }
                }
            }
            items(filteredChapters) { chapter ->
                val path = buildChapterPath(detail.detailPath, chapter)
                val isRead = chapterPathsByLabel[path] in canonicalReadChapterKeys
                val isDownloaded = path in downloadedChapters
                val progress = downloadProgress[path]

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onReadChapter(path) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            chapter.chapterLabel,
                            fontWeight = if (path == lastOpenedChapterPath) FontWeight.Bold else FontWeight.Normal,
                            color = if (isRead) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatDateEu(chapter.registrationDate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    if (progress != null) {
                        CircularProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        IconButton(onClick = { onToggleChapterDownload(path, isDownloaded) }) {
                            Icon(
                                if (isDownloaded) Icons.Default.Delete else Icons.Default.Download,
                                contentDescription = if (isDownloaded) strings.removeDownload else strings.download,
                                tint = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { onToggleChapterRead(path) }) {
                        Icon(
                            if (isRead) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = if (isRead) strings.unread else strings.read,
                            tint = if (isRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(140.dp)) }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    suppressAutoPositioning = true
                    hasAutoPositionedChapterList = true
                    scope.launch {
                        listState.animateScrollToItem(index = 0, scrollOffset = 0)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = strings.scrollToTop)
            }
            SmallFloatingActionButton(
                onClick = {
                    suppressAutoPositioning = true
                    hasAutoPositionedChapterList = true
                    scope.launch {
                        val chapterStartIndex = 2
                        val targetIndex = lastUnreadIndex?.let { chapterStartIndex + it }
                            ?: (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                        listState.animateScrollToItem(targetIndex)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = strings.scrollToBottom)
            }
        }
        if (isBulkUpdatingChapters) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 4.dp,
                    shadowElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                        Text(strings.updatedReadStatus)
                    }
                }
            }
        }
    }
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
