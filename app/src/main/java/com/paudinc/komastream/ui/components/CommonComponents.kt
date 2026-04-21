package com.paudinc.komastream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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

@Composable
fun MangaCoverCard(manga: MangaSummary, strings: AppStrings, constrained: Boolean = false, onClick: () -> Unit) {
    ElevatedCard(
        modifier = (if (constrained) Modifier.width(160.dp) else Modifier.fillMaxWidth())
            .border(cardBorder(), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
    ) {
        if (constrained) {
            Column {
                AsyncImage(
                    model = manga.coverUrl,
                    contentDescription = manga.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                    contentScale = ContentScale.Crop,
                    placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
                    error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image),
                )
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(manga.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (manga.status.isNotBlank()) {
                        Text(manga.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    if (manga.periodicity.isNotBlank()) {
                        Text(manga.periodicity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (manga.latestPublication.isNotBlank()) {
                        Text(manga.latestPublication, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    if (manga.views.isNotBlank()) {
                        Text(manga.views, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        } else {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = manga.coverUrl,
                    contentDescription = manga.title,
                    modifier = Modifier
                        .size(width = 100.dp, height = 140.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                    placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
                    error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(manga.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)

                    if (manga.status.isNotBlank()) {
                        InfoRow(strings.status, manga.status)
                    }
                    if (manga.periodicity.isNotBlank()) {
                        InfoRow(strings.periodicity, manga.periodicity)
                    }
                    if (manga.latestPublication.isNotBlank()) {
                        InfoRow(strings.latest, manga.latestPublication)
                    }
                    if (manga.chaptersCount.isNotBlank()) {
                        InfoRow(strings.chapters, manga.chaptersCount)
                    }
                    if (manga.views.isNotBlank()) {
                        Text(manga.views, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        Text(value, style = MaterialTheme.typography.bodySmall)
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
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = manga.coverUrl,
                    contentDescription = manga.title,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                    placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
                    error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(manga.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        manga.lastChapterTitle.ifBlank { strings.noChapterSavedYet },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = strings.removeFromContinueReading)
                }
                Button(onClick = onResume, enabled = manga.lastChapterPath.isNotBlank()) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(strings.resume)
                }
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(strings.manga) },
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
                if (manga.lastChapterTitle.isNotBlank()) {
                    Text(
                        manga.lastChapterTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = strings.removeFromFavorites,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
fun ChapterRow(item: ChapterSummary, strings: AppStrings, onOpenChapter: (String, String) -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(cardBorder(), RoundedCornerShape(22.dp))
            .clickable { onOpenChapter(item.providerId, item.chapterPath) },
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.mangaTitle,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
                error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.mangaTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    item.chapterLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.registrationLabel.isNotBlank()) {
                    Text(
                        item.registrationLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onOpenChapter(item.providerId, item.chapterPath) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(strings.read)
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
fun EmptyCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Box(modifier = Modifier.padding(24.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun TagChip(label: String, containerColor: Color, labelColor: Color) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Text(
                        text = release.body,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
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
fun cardBorder() = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
