package com.paudinc.komastream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.paudinc.komastream.data.model.ChapterSummary
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.data.model.BackupOperationType
import com.paudinc.komastream.data.model.BackupOperationUiState
import com.paudinc.komastream.updater.AppUpdateUiState
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.parseChapterInput

@Composable
fun MangaCoverCard(
    manga: MangaSummary,
    strings: AppStrings,
    constrained: Boolean = false,
    favoriteActionLabel: String = strings.addToFavorites,
    onClick: () -> Unit,
    onFavoriteAction: (() -> Unit)? = null,
    onOpenMangaAction: (() -> Unit)? = null,
) {
    var menuExpanded by rememberSaveable(manga.providerId, manga.detailPath) { mutableStateOf(false) }

    Box {
        ElevatedCard(
            modifier = (if (constrained) Modifier.width(160.dp) else Modifier.fillMaxWidth())
                .border(cardBorder(), RoundedCornerShape(24.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        if (onFavoriteAction != null || onOpenMangaAction != null) menuExpanded = true
                    },
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.84f),
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        ) {
            if (constrained) {
                Column {
                    Box {
                        AsyncImage(
                            model = manga.coverUrl,
                            contentDescription = manga.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            contentScale = ContentScale.Crop,
                            placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
                            error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image),
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color(0xCC0B0911)),
                                    )
                                )
                        )
                        manga.status.takeIf { it.isNotBlank() }?.let { status ->
                            TagChip(
                                label = strings.localizedStatus(status),
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                labelColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            manga.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        manga.periodicity.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        manga.latestPublication.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        manga.views.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    AsyncImage(
                        model = manga.coverUrl,
                        contentDescription = manga.title,
                        modifier = Modifier
                            .size(width = 84.dp, height = 116.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                        placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
                        error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            manga.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            manga.status.takeIf { it.isNotBlank() }?.let {
                                TagChip(
                                    label = strings.localizedStatus(it),
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            manga.chaptersCount.takeIf { it.isNotBlank() }?.let {
                                TagChip(
                                    label = "${strings.chapters} $it",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                        manga.chaptersCount.takeIf { it.isNotBlank() }?.let { chapters ->
                            Text(
                                text = "${strings.chapters} $chapters",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        manga.periodicity.takeIf { it.isNotBlank() }?.let {
                            InfoRow(strings.periodicity, it)
                        }
                        manga.latestPublication.takeIf { it.isNotBlank() }?.let {
                            InfoRow(strings.latest, it)
                        }
                        manga.views.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            onFavoriteAction?.let {
                DropdownMenuItem(
                    text = { Text(favoriteActionLabel) },
                    onClick = {
                        menuExpanded = false
                        it()
                    },
                )
            }
            onOpenMangaAction?.let {
                DropdownMenuItem(
                    text = { Text(strings.openManga) },
                    onClick = {
                        menuExpanded = false
                        it()
                    },
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ContinueReadingCard(
    manga: SavedManga,
    strings: AppStrings,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by rememberSaveable(manga.providerId, manga.detailPath) { mutableStateOf(false) }

    Box {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .border(cardBorder(), RoundedCornerShape(22.dp))
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuExpanded = true },
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = manga.coverUrl,
                    contentDescription = manga.title,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                    placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
                    error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image),
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        manga.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${strings.latestProgress}: ${manga.localizedLastChapterTitle(strings)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = onResume,
                            enabled = manga.lastChapterPath.isNotBlank(),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                contentColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            ),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(strings.resume, maxLines = 1)
                        }
                        FilledTonalIconButton(
                            onClick = onRemove,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = strings.removeFromContinueReading)
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(strings.openManga) },
                onClick = {
                    menuExpanded = false
                    onOpen()
                },
            )
            if (manga.lastChapterPath.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text(strings.resume) },
                    onClick = {
                        menuExpanded = false
                        onResume()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(strings.removeFromContinueReading) },
                onClick = {
                    menuExpanded = false
                    onRemove()
                },
            )
        }
    }
}

@Composable
fun FavoriteMangaCard(
    manga: SavedManga,
    strings: AppStrings,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(cardBorder(), RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = manga.coverUrl,
                contentDescription = manga.title,
                modifier = Modifier
                    .size(width = 84.dp, height = 118.dp)
                    .clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop,
                placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
                error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image),
            )
            Spacer(Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        manga.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val localizedLastChapterTitle = manga.localizedLastChapterTitle(strings)
                    if (localizedLastChapterTitle.isNotBlank()) {
                        Text(
                            localizedLastChapterTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            FilledTonalIconButton(
                onClick = onRemove,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = strings.removeFromFavorites,
                )
            }
        }
    }
}

fun SavedManga.localizedLastChapterTitle(strings: AppStrings): String {
    val chapterNumber = parseChapterInput(lastChapterTitle)
        ?: parseChapterInput(lastChapterPath.substringBeforeLast("/").substringAfterLast("/"))
    return when {
        chapterNumber != null -> strings.chapterNumberPrefix.format(chapterNumber)
        lastChapterTitle.isNotBlank() -> lastChapterTitle
        else -> strings.noChapterSavedYet
    }
}

@Composable
fun ChapterRow(
    item: ChapterSummary,
    strings: AppStrings,
    actionLabel: String = strings.read,
    onOpenChapter: (String, String) -> Unit,
    onAddToReading: (() -> Unit)? = null,
    onOpenManga: (() -> Unit)? = null,
) {
    var menuExpanded by rememberSaveable(item.providerId, item.chapterPath) { mutableStateOf(false) }

    Box {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .border(cardBorder(), RoundedCornerShape(22.dp))
                .combinedClickable(
                    onClick = { onOpenManga?.invoke() ?: onOpenChapter(item.providerId, item.chapterPath) },
                    onLongClick = {
                        if (onAddToReading != null || onOpenManga != null) menuExpanded = true
                    },
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.mangaTitle,
                    modifier = Modifier
                        .size(width = 62.dp, height = 84.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            onOpenManga?.invoke() ?: onOpenChapter(item.providerId, item.chapterPath)
                        },
                    contentScale = ContentScale.Crop,
                    placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
                    error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image),
                )
                Spacer(Modifier.width(14.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onOpenManga?.invoke() ?: onOpenChapter(item.providerId, item.chapterPath)
                        },
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        item.mangaTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TagChip(
                        label = strings.chapterLabelWithNumber(item),
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        labelColor = MaterialTheme.colorScheme.primary,
                    )
                    if (item.registrationLabel.isNotBlank()) {
                        Text(
                            item.registrationLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = { onOpenChapter(item.providerId, item.chapterPath) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        actionLabel,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            onAddToReading?.let {
                DropdownMenuItem(
                    text = { Text(strings.addToContinueReading) },
                    onClick = {
                        menuExpanded = false
                        it()
                    },
                )
            }
            onOpenManga?.let {
                DropdownMenuItem(
                    text = { Text(strings.openManga) },
                    onClick = {
                        menuExpanded = false
                        it()
                    },
                )
            }
        }
    }
}

@Composable
fun LoadingPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun LoadingPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun EmptyCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = cardBorder(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TagChip(
    label: String,
    containerColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun MarkdownReleaseNotes(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                is MarkdownBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
                is MarkdownBlock.Bullet -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = if (block.orderedIndex != null) "${block.orderedIndex}." else "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = block.text,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                    )
                }
            }
        }
    }
}

@Composable
fun UpdateAvailableDialog(
    strings: AppStrings,
    updateState: AppUpdateUiState,
    onDismiss: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    val release = when (updateState) {
        is AppUpdateUiState.Available -> updateState.release
        is AppUpdateUiState.Downloading -> updateState.release
        is AppUpdateUiState.Downloaded -> updateState.release
        else -> null
    } ?: return

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
        title = { Text(strings.updateDialogTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = strings.updateDialogMessage(release.versionLabel),
                    style = MaterialTheme.typography.bodyLarge,
                )
                when (updateState) {
                    is AppUpdateUiState.Downloading -> {
                        Text(
                            text = "${updateState.progressPercent}% ${strings.downloading.lowercase()}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { updateState.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is AppUpdateUiState.Downloaded -> {
                        Text(
                            text = strings.updateDownloadStarted,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    else -> Unit
                }
                if (release.body.isNotBlank()) {
                    Text(
                        text = strings.releaseNotes,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    MarkdownReleaseNotes(
                        markdown = release.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when (updateState) {
                is AppUpdateUiState.Available -> Button(onClick = onDownloadUpdate) { Text(strings.downloadUpdate) }
                is AppUpdateUiState.Downloaded -> Button(onClick = onInstallUpdate) { Text(strings.installUpdate) }
                is AppUpdateUiState.Downloading -> Button(onClick = {}, enabled = false) { Text(strings.downloading) }
                else -> Unit
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenReleasePage) { Text(strings.releasePage) }
                Button(onClick = onDismiss) { Text(strings.dismiss) }
            }
        },
    )
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: AnnotatedString) : MarkdownBlock
    data class Bullet(val text: AnnotatedString, val orderedIndex: Int?) : MarkdownBlock
}

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraphLines = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphLines.isEmpty()) return
        val text = paragraphLines.joinToString(" ").trim()
        if (text.isNotBlank()) {
            blocks += MarkdownBlock.Paragraph(parseInlineMarkdown(text))
        }
        paragraphLines.clear()
    }

    markdown.lines().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.isBlank() -> flushParagraph()
            line.startsWith("#") -> {
                flushParagraph()
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 3)
                val text = line.dropWhile { it == '#' }.trim()
                if (text.isNotBlank()) {
                    blocks += MarkdownBlock.Heading(level, text)
                }
            }
            BULLET_MARKDOWN_REGEX.matches(line) -> {
                flushParagraph()
                val text = BULLET_MARKDOWN_REGEX.matchEntire(line)?.groupValues?.get(1).orEmpty()
                blocks += MarkdownBlock.Bullet(parseInlineMarkdown(text), orderedIndex = null)
            }
            NUMBERED_MARKDOWN_REGEX.matches(line) -> {
                flushParagraph()
                val match = NUMBERED_MARKDOWN_REGEX.matchEntire(line)
                val index = match?.groupValues?.get(1)?.toIntOrNull()
                val text = match?.groupValues?.get(2).orEmpty()
                blocks += MarkdownBlock.Bullet(parseInlineMarkdown(text), orderedIndex = index)
            }
            else -> paragraphLines += line
        }
    }
    flushParagraph()
    return blocks
}

private fun parseInlineMarkdown(value: String): AnnotatedString {
    val cleaned = value
        .replace(LINK_MARKDOWN_REGEX, "$1")
        .replace(INLINE_CODE_MARKDOWN_REGEX, "$1")
        .replace(STRONG_MARKDOWN_REGEX, "$1")
        .replace(EMPHASIS_MARKDOWN_REGEX, "$1")
        .trim()
    return AnnotatedString(cleaned)
}

private val BULLET_MARKDOWN_REGEX = Regex("^[-*+]\\s+(.+)$")
private val NUMBERED_MARKDOWN_REGEX = Regex("^(\\d+)\\.\\s+(.+)$")
private val LINK_MARKDOWN_REGEX = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
private val INLINE_CODE_MARKDOWN_REGEX = Regex("`([^`]+)`")
private val STRONG_MARKDOWN_REGEX = Regex("(\\*\\*|__)(.*?)\\1")
private val EMPHASIS_MARKDOWN_REGEX = Regex("(\\*|_)(.*?)\\1")

@Composable
fun BackupOperationDialog(
    strings: AppStrings,
    state: BackupOperationUiState,
    onConfirm: () -> Unit,
) {
    if (state == BackupOperationUiState.Idle) return

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = {
            Text(
                when (state) {
                    is BackupOperationUiState.InProgress -> {
                        if (state.type == BackupOperationType.IMPORT) strings.backupImporting else strings.backupExporting
                    }
                    is BackupOperationUiState.Completed -> {
                        when {
                            state.type == BackupOperationType.IMPORT && state.success -> strings.backupImportSuccess
                            state.type == BackupOperationType.EXPORT && state.success -> strings.backupExportSuccess
                            else -> if (state.type == BackupOperationType.IMPORT) strings.backupImportError else strings.exportBackupError
                        }
                    }
                    BackupOperationUiState.Idle -> ""
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is BackupOperationUiState.InProgress -> {
                        Text(
                            text = "${state.progressPercent}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        LinearProgressIndicator(
                            progress = { state.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is BackupOperationUiState.Completed -> {
                        Text(
                            text = state.message,
                            color = if (state.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                    BackupOperationUiState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            if (state is BackupOperationUiState.Completed) {
                Button(onClick = onConfirm) { Text(strings.ok) }
            }
        },
        dismissButton = {},
    )
}

@Composable
fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
