package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.MangaChapter
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.provider.providers.MangaBallProvider
import com.paudinc.komastream.ui.components.*
import com.paudinc.komastream.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    malMangaId: Long?,
    showMalIdEditor: Boolean,
    onOpenMalIdEditor: () -> Unit,
    onCloseMalIdEditor: () -> Unit,
    onSetMalMangaId: (Long?) -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleChapterRead: (String) -> Unit,
    onSetAllChaptersRead: (Boolean) -> Unit,
    onSetUntilChapterRead: (Double, Boolean) -> Unit,
    onDownloadAllChapters: (List<String>) -> Unit,
    onToggleChapterDownload: (String, Boolean) -> Unit,
    onReadChapter: (String) -> Unit,
    onSelectChapterSource: (String) -> Unit,
    onSolveCloudflare: (() -> Unit)? = null,
) {
    var chapterQuery by rememberSaveable(detail.providerId, detail.detailPath) { mutableStateOf("") }
    var bulkChapterInput by rememberSaveable(detail.providerId, detail.detailPath) { mutableStateOf("") }
    var malIdInput by rememberSaveable(detail.providerId, detail.detailPath) { mutableStateOf(malMangaId?.toString().orEmpty()) }
    var hasAutoPositionedChapterList by remember(detail.providerId, detail.detailPath, autoJumpToUnread) { mutableStateOf(false) }
    var suppressAutoPositioning by remember(detail.providerId, detail.detailPath) { mutableStateOf(false) }
    val listState = rememberSaveable(detail.providerId, detail.detailPath, saver = LazyListState.Saver) {
        LazyListState()
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(malMangaId) {
        malIdInput = malMangaId?.toString().orEmpty()
    }
    BackHandler(enabled = showMalIdEditor) {
        onCloseMalIdEditor()
    }

    val selectedChapterSourceId = detail.selectedChapterSourceId
    val chapterUiData by produceState(
        initialValue = DetailChapterUiData.empty(detail.chapters),
        detail.providerId,
        detail.detailPath,
        detail.chapters,
        selectedChapterSourceId,
        chapterQuery,
        readChapters,
        lastOpenedChapterPath,
        autoJumpToUnread,
    ) {
        value = withContext(Dispatchers.Default) {
            computeDetailChapterUiData(
                providerId = detail.providerId,
                detailPath = detail.detailPath,
                chapters = detail.chapters,
                selectedChapterSourceId = selectedChapterSourceId,
                chapterQuery = chapterQuery,
                readChapters = readChapters,
                lastOpenedChapterPath = lastOpenedChapterPath,
                autoJumpToUnread = autoJumpToUnread,
            )
        }
    }
    val canonicalReadChapterKeys = chapterUiData.canonicalReadChapterKeys
    val chapterPathsByLabel = chapterUiData.chapterPathsByLabel
    val sourceFilteredChapters = chapterUiData.sourceFilteredChapters
    val uniqueChapters = chapterUiData.uniqueChapters
    val chapterCount = chapterCountForProvider(detail.providerId, uniqueChapters)
    val filteredChapters = chapterUiData.filteredChapters
    val targetUnreadChapterPath = chapterUiData.targetUnreadChapterPath
    val targetUnreadIndex = chapterUiData.targetUnreadIndex
    val lastUnreadIndex = chapterUiData.lastUnreadIndex
    val lastOpenedChapterLabel = chapterUiData.lastOpenedChapterLabel
    val unreadCount = chapterUiData.unreadCount
    var sourceMenuExpanded by rememberSaveable(detail.providerId, detail.detailPath) { mutableStateOf(false) }

    LaunchedEffect(detail.providerId, detail.detailPath, chapterUiData.ready, chapterQuery, targetUnreadIndex, autoJumpToUnread, selectedChapterSourceId) {
        if (!chapterUiData.ready) return@LaunchedEffect
        if (chapterQuery.isNotBlank()) return@LaunchedEffect
        if (suppressAutoPositioning) {
            suppressAutoPositioning = false
            return@LaunchedEffect
        }
        if (hasAutoPositionedChapterList) return@LaunchedEffect
        if (autoJumpToUnread && targetUnreadIndex != null) {
            val chapterStartIndex = DETAIL_CHAPTER_LIST_START_INDEX
            listState.scrollToItem((targetUnreadIndex + chapterStartIndex).coerceAtLeast(0))
        }
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
                    MangadotAwareAsyncImage(
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
                        MangadotAwareAsyncImage(
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
                                    value = chapterCount.toString(),
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
                            MalIdStatCard(
                                value = malMangaId?.toString().orEmpty().ifBlank { strings.malIdNotSet },
                                label = strings.malId,
                                onEdit = {
                                    malIdInput = malMangaId?.toString().orEmpty()
                                    onOpenMalIdEditor()
                                },
                            )
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
                                        strings.chaptersCount(chapterCount),
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
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = if (detail.providerId == MangaBallProvider.PROVIDER_ID) {
                                            strings.languageLabel
                                        } else {
                                            strings.chapterSource
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Box {
                                        OutlinedButton(
                                            onClick = { sourceMenuExpanded = true },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = MaterialTheme.colorScheme.onSurface,
                                            ),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                                        ) {
                                            Text(
                                                detail.chapterSources
                                                    .firstOrNull { it.id == selectedChapterSourceId }
                                                    ?.name
                                                    .orEmpty()
                                                    .ifBlank { strings.chapterSource },
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                        DropdownMenu(
                                            expanded = sourceMenuExpanded,
                                            onDismissRequest = { sourceMenuExpanded = false },
                                        ) {
                                            detail.chapterSources.forEach { source ->
                                                DropdownMenuItem(
                                                    text = { Text(source.name) },
                                                    leadingIcon = if (source.id == selectedChapterSourceId) {
                                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                                    } else null,
                                                    onClick = {
                                                        sourceMenuExpanded = false
                                                        onSelectChapterSource(source.id)
                                                    },
                                                )
                                            }
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
                            FilledTonalButton(
                                onClick = {
                                    onDownloadAllChapters(
                                        uniqueChapters.map { chapter -> buildChapterPath(detail.detailPath, chapter) }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ),
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.downloadAllChapters)
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
                        listState.scrollToItem(index = 0, scrollOffset = 0)
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
                        val chapterStartIndex = DETAIL_CHAPTER_LIST_START_INDEX
                        val targetIndex = lastUnreadIndex?.let { chapterStartIndex + it }
                            ?: (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                        listState.scrollToItem(targetIndex)
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
        if (showMalIdEditor) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = strings.malId,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = onCloseMalIdEditor) {
                            Icon(Icons.Default.Close, contentDescription = strings.cancel)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border = cardBorder(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                text = malMangaId?.toString().orEmpty().ifBlank { strings.malIdNotSet },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            OutlinedTextField(
                                value = malIdInput,
                                onValueChange = { input -> malIdInput = input.filter { it.isDigit() } },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(strings.malId) },
                                placeholder = { Text(strings.malIdNotSet) },
                                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = {
                                    val parsed = malIdInput.trim().takeIf { it.isNotBlank() }?.toLongOrNull()
                                    onSetMalMangaId(parsed)
                                    onCloseMalIdEditor()
                                }),
                            )
                            Button(
                                onClick = {
                                    val parsed = malIdInput.trim().takeIf { it.isNotBlank() }?.toLongOrNull()
                                    onSetMalMangaId(parsed)
                                    onCloseMalIdEditor()
                                },
                                enabled = malIdInput.trim().toLongOrNull() != malMangaId,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.save)
                            }
                            OutlinedButton(
                                onClick = {
                                    malIdInput = ""
                                    onSetMalMangaId(null)
                                    onCloseMalIdEditor()
                                },
                                enabled = malMangaId != null || malIdInput.isNotBlank(),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(strings.clear)
                            }
                        }
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

private fun chapterDedupeKey(providerId: String, chapter: com.paudinc.komastream.data.model.MangaChapter): String {
    val numericValue = chapterValue(chapter)
    return when {
        providerId == "manhwa-latino-es" && numericValue.isFinite() && numericValue != Double.MAX_VALUE ->
            "num:${kotlin.math.floor(numericValue).toInt()}"
        numericValue.isFinite() && numericValue != Double.MAX_VALUE -> "num:${numericValue}"
        chapter.chapterNumberUrl.isNotBlank() -> "url:${chapter.chapterNumberUrl.trim().lowercase()}"
        else -> "label:${chapter.chapterLabel.trim().lowercase()}"
    }
}

private const val DETAIL_CHAPTER_LIST_START_INDEX = 2

private data class DetailChapterUiData(
    val canonicalReadChapterKeys: Set<String>,
    val chapterPathsByLabel: Map<String, String>,
    val sourceFilteredChapters: List<MangaChapter>,
    val uniqueChapters: List<MangaChapter>,
    val filteredChapters: List<MangaChapter>,
    val targetUnreadChapterPath: String?,
    val targetUnreadIndex: Int?,
    val lastUnreadIndex: Int?,
    val lastOpenedChapterLabel: String,
    val unreadCount: Int,
    val ready: Boolean,
) {
    companion object {
        fun empty(chapters: List<MangaChapter>) = DetailChapterUiData(
            canonicalReadChapterKeys = emptySet(),
            chapterPathsByLabel = emptyMap(),
            sourceFilteredChapters = chapters,
            uniqueChapters = chapters,
            filteredChapters = chapters,
            targetUnreadChapterPath = null,
            targetUnreadIndex = null,
            lastUnreadIndex = null,
            lastOpenedChapterLabel = "",
            unreadCount = chapters.size,
            ready = chapters.isEmpty(),
        )
    }
}

private fun computeDetailChapterUiData(
    providerId: String,
    detailPath: String,
    chapters: List<MangaChapter>,
    selectedChapterSourceId: String,
    chapterQuery: String,
    readChapters: Set<String>,
    lastOpenedChapterPath: String,
    autoJumpToUnread: Boolean,
): DetailChapterUiData {
    val canonicalReadChapterKeys = chapterReadKeys(providerId, detailPath, chapters, readChapters)
    val sourceFilteredChapters = if (selectedChapterSourceId.isBlank() || selectedChapterSourceId == "all") {
        chapters
    } else {
        chapters.filter { chapter -> chapter.languageCode == selectedChapterSourceId }
    }
    val logicalChapters = sourceFilteredChapters.distinctBy { chapterDedupeKey(providerId, it) }
    val normalizedQuery = chapterQuery.trim().replace(",", ".")
    val trimmedQuery = chapterQuery.trim()
    val filteredChapters = if (normalizedQuery.isBlank()) {
        sourceFilteredChapters
    } else {
        sourceFilteredChapters.filter { chapter ->
            chapter.chapterLabel.contains(trimmedQuery, ignoreCase = true) ||
                chapter.chapterNumberUrl.contains(trimmedQuery, ignoreCase = true)
        }
    }
    val chapterPathsByLabel = filteredChapters.associate { chapter ->
        val path = buildChapterPath(detailPath, chapter)
        path to chapterReadKey(providerId, detailPath, chapter)
    }
    val totalChapterCount = chapterCountForProvider(providerId, logicalChapters)
    val readChapterCount = logicalChapters.count { chapter ->
        val path = buildChapterPath(detailPath, chapter)
        chapterPathsByLabel[path] in canonicalReadChapterKeys
    }
    val targetUnreadChapterPath = if (autoJumpToUnread) {
        resolveTargetUnreadChapterPath(
            providerId = providerId,
            detailPath = detailPath,
            chapters = filteredChapters,
            readChapters = readChapters,
            lastOpenedChapterPath = lastOpenedChapterPath,
            autoJumpToUnread = true,
        )
    } else {
        null
    }
    val targetUnreadIndex = targetUnreadChapterPath?.let { path ->
        filteredChapters.indexOfFirst { chapter -> buildChapterPath(detailPath, chapter) == path }
            .takeIf { it >= 0 }
    }
    val lastUnreadIndex = filteredChapters.indexOfLast { chapter ->
        val path = buildChapterPath(detailPath, chapter)
        chapterPathsByLabel[path] !in canonicalReadChapterKeys
    }.takeIf { it >= 0 }
    val lastOpenedChapterLabel = filteredChapters.firstOrNull { chapter ->
        buildChapterPath(detailPath, chapter) == lastOpenedChapterPath
    }?.chapterLabel.orEmpty()
    val unreadCount = (totalChapterCount - readChapterCount).coerceAtLeast(0)
    return DetailChapterUiData(
        canonicalReadChapterKeys = canonicalReadChapterKeys,
        chapterPathsByLabel = chapterPathsByLabel,
        sourceFilteredChapters = sourceFilteredChapters,
        uniqueChapters = logicalChapters,
        filteredChapters = filteredChapters,
        targetUnreadChapterPath = targetUnreadChapterPath,
        targetUnreadIndex = targetUnreadIndex,
        lastUnreadIndex = lastUnreadIndex,
        lastOpenedChapterLabel = lastOpenedChapterLabel,
        unreadCount = unreadCount,
        ready = true,
    )
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

@Composable
private fun MalIdStatCard(
    value: String,
    label: String,
    onEdit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        border = cardBorder(),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(end = 44.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Link,
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
            FilledIconButton(
                onClick = onEdit,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(32.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = label,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
