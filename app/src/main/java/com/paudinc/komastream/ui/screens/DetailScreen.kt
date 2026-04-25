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
import androidx.compose.material.icons.automirrored.filled.MenuBook
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
import com.paudinc.komastream.provider.providers.MangaBallProvider
import com.paudinc.komastream.ui.components.*
import com.paudinc.komastream.utils.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    strings: AppStrings,
    detail: MangaDetail,
    isFavorite: Boolean,
    autoJumpToUnread: Boolean,
    readChapters: Set<String>,
    lastOpenedChapterPath: String,
    isChapterDownloaded: (String) -> Boolean,
    downloadProgress: Map<String, Int>,
    isBulkUpdatingChapters: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleChapterRead: (String) -> Unit,
    onSetAllChaptersRead: (Boolean) -> Unit,
    onSetUntilChapterRead: (Double, Boolean) -> Unit,
    onToggleChapterDownload: (String, Boolean) -> Unit,
    onReadChapter: (String) -> Unit,
    onSelectChapterSource: (String) -> Unit,
    onSolveCloudflare: (() -> Unit)? = null,
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
    var sourceMenuExpanded by rememberSaveable(detail.providerId, detail.detailPath) { mutableStateOf(false) }

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
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface,
                        )
                    )
                ),
            state = listState,
        ) {
            item {
                Box {
                    AsyncImage(
                        model = detail.bannerUrl.ifBlank { detail.coverUrl },
                        contentDescription = detail.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0x55000000),
                                        Color(0xCC0A0813),
                                        MaterialTheme.colorScheme.background,
                                    )
                                )
                            )
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 24.dp)
                            .align(Alignment.BottomStart),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        AsyncImage(
                            model = detail.coverUrl,
                            contentDescription = detail.title,
                            modifier = Modifier
                                .size(width = 116.dp, height = 168.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .border(cardBorder(), RoundedCornerShape(22.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                detail.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            detail.publicationDate.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = strings.publishedDate(formatDateEu(it)),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (detail.status.isNotBlank()) {
                                    TagChip(
                                        strings.localizedStatus(detail.status),
                                        containerColor = statusTagColor(detail.status),
                                        labelColor = Color.White,
                                    )
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
                        FilledTonalButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isFavorite) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                                },
                                contentColor = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isFavorite) strings.removeFromFavorites else strings.addToFavorites)
                        }
                        if (targetUnreadChapterPath != null) {
                            Button(
                                onClick = { onReadChapter(targetUnreadChapterPath) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.continueReadingAction)
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
                        border = cardBorder(),
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                detail.description.ifBlank { strings.noDescription },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                DetailStatCard(
                                    modifier = Modifier.weight(1f),
                                    value = detail.chapters.size.toString(),
                                    label = strings.chapters,
                                    leadingIcon = Icons.AutoMirrored.Filled.MenuBook,
                                )
                                DetailStatCard(
                                    modifier = Modifier.weight(1f),
                                    value = unreadCount.toString(),
                                    label = strings.unread,
                                    leadingIcon = Icons.Default.BookmarkBorder,
                                )
                                DetailStatCard(
                                    modifier = Modifier.weight(1f),
                                    value = if (lastOpenedChapterLabel.isNotBlank()) lastOpenedChapterLabel else "--",
                                    label = strings.latest,
                                    leadingIcon = Icons.Default.History,
                                )
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                        border = cardBorder(),
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
                                    if (detail.needsCloudflareClearance && onSolveCloudflare != null) {
                                        Text(
                                            strings.blockedByCloudflare,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
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
                                    if (detail.needsCloudflareClearance && onSolveCloudflare != null) {
                                        IconButton(onClick = onSolveCloudflare) {
                                            Icon(Icons.Default.LockOpen, contentDescription = strings.solveCloudflare, tint = MaterialTheme.colorScheme.error)
                                        }
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
                            if (detail.chapterSources.isNotEmpty()) {
                                ExposedDropdownMenuBox(
                                    expanded = sourceMenuExpanded,
                                    onExpandedChange = { sourceMenuExpanded = it },
                                ) {
                                    OutlinedTextField(
                                        value = detail.chapterSources
                                            .firstOrNull { it.id == detail.selectedChapterSourceId }
                                            ?.name
                                            .orEmpty(),
                                        onValueChange = {},
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                                        readOnly = true,
                                        label = {
                                            Text(
                                                if (detail.providerId == MangaBallProvider.PROVIDER_ID) {
                                                    strings.languageLabel
                                                } else {
                                                    strings.chapterSource
                                                }
                                            )
                                        },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceMenuExpanded)
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                    )
                                    ExposedDropdownMenu(
                                        expanded = sourceMenuExpanded,
                                        onDismissRequest = { sourceMenuExpanded = false },
                                    ) {
                                        detail.chapterSources.forEach { source ->
                                            DropdownMenuItem(
                                                text = { Text(source.name) },
                                                onClick = {
                                                    sourceMenuExpanded = false
                                                    if (source.detailPath != detail.detailPath) {
                                                        onSelectChapterSource(source.detailPath)
                                                    }
                                                },
                                            )
                                        }
                                    }
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
                val isDownloaded = isChapterDownloaded(path)
                val progress = downloadProgress[path]

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable { onReadChapter(path) },
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = cardBorder(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                strings.chapterLabelWithNumber(chapter),
                                fontWeight = if (path == lastOpenedChapterPath) FontWeight.Bold else FontWeight.Normal,
                                color = if (isRead) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (chapter.languageCode.isNotBlank() || chapter.uploaderLabel.isNotBlank()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(top = 6.dp)
                                ) {
                                    chapter.languageCode
                                        .takeIf { it.isNotBlank() }
                                        ?.let { code ->
                                            AssistChip(
                                                onClick = {},
                                                enabled = false,
                                                label = {
                                                    Text(
                                                        "${chapterLanguageFlag(code)} ${chapter.languageLabel.ifBlank { code.uppercase() }}".trim(),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                    disabledLabelColor = MaterialTheme.colorScheme.primary,
                                                    disabledLeadingIconContentColor = MaterialTheme.colorScheme.primary,
                                                ),
                                            )
                                        }
                                    chapter.uploaderLabel
                                        .takeIf { it.isNotBlank() }
                                        ?.let { uploader ->
                                            AssistChip(
                                                onClick = {},
                                                enabled = false,
                                                label = {
                                                    Text(
                                                        uploader,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            )
                                        }
                                }
                            }
                            Text(
                                formatDateEu(chapter.registrationDate),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Box(contentAlignment = Alignment.Center) {
                            if (progress != null) {
                                CircularProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 3.dp,
                                )
                            }
                            IconButton(onClick = { onToggleChapterDownload(path, isDownloaded || progress != null) }) {
                                Icon(
                                    if (isDownloaded || progress != null) Icons.Default.Delete else Icons.Default.Download,
                                    contentDescription = when {
                                        progress != null -> strings.cancel
                                        isDownloaded -> strings.removeDownload
                                        else -> strings.download
                                    },
                                    tint = if (isDownloaded || progress != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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

private fun chapterLanguageFlag(code: String): String = when (code.lowercase()) {
    "en" -> "🇬🇧"
    "es" -> "🇪🇸"
    "pt-br" -> "🇧🇷"
    "pt-pt", "pt" -> "🇵🇹"
    "id" -> "🇮🇩"
    "fr" -> "🇫🇷"
    "de" -> "🇩🇪"
    "it" -> "🇮🇹"
    "vi" -> "🇻🇳"
    "th" -> "🇹🇭"
    "ru" -> "🇷🇺"
    "uk" -> "🇺🇦"
    "ar" -> "🇦🇪"
    "zh", "zh-cn", "zh-sg" -> "🇨🇳"
    "zh-hk" -> "🇭🇰"
    "zh-tw" -> "🇹🇼"
    "jp" -> "🇯🇵"
    "kr" -> "🇰🇷"
    else -> ""
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

@Composable
private fun DetailStatCard(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        border = cardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
