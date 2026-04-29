package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.ui.components.*
import com.paudinc.komastream.ui.navigation.LibraryTab
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.groupLibrarySeries
import com.paudinc.komastream.utils.preferredLibrarySeriesEntry

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    libraryState: LibraryState,
    strings: AppStrings,
    selectedTab: LibraryTab,
    providerNameForId: (String) -> String,
    onSelectTab: (LibraryTab) -> Unit,
    onOpenManga: (String, String) -> Unit,
    onOpenChapter: (String, String) -> Unit,
    onRemoveFromContinueReading: (SavedManga) -> Unit,
    onRemoveFromFavorites: (SavedManga) -> Unit,
) {
    val favoriteSeries = remember(libraryState.favorites) { groupLibrarySeries(libraryState.favorites) }
    val readingSeries = remember(libraryState.reading) { groupLibrarySeries(libraryState.reading) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionTitle(strings.library)
        }
        when (selectedTab) {
            LibraryTab.Favorites -> {
                item {
                    Text(
                        strings.favoritesCount(favoriteSeries.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (favoriteSeries.isEmpty()) {
                    item { EmptyCard(strings.addMangaHint) }
                } else {
                    items(favoriteSeries, key = { it.key }) { series ->
                        val preferred = remember(series.key, libraryState.selectedProviderId) {
                            preferredLibrarySeriesEntry(series, libraryState.selectedProviderId)
                        }
                        var activeProviderId by rememberSaveable(series.key, libraryState.selectedProviderId) {
                            mutableStateOf(preferred.providerId)
                        }
                        LaunchedEffect(series.key, preferred.providerId) {
                            if (series.entries.none { it.providerId == activeProviderId }) {
                                activeProviderId = preferred.providerId
                            }
                        }
                        val activeEntry = series.entries.firstOrNull { it.providerId == activeProviderId } ?: preferred
                        GroupedFavoriteMangaCard(
                            manga = activeEntry,
                            availableProviders = series.entries,
                            strings = strings,
                            providerNameForId = providerNameForId,
                            onProviderSelected = { activeProviderId = it },
                            onOpen = { onOpenManga(activeEntry.providerId, activeEntry.detailPath) },
                            onRemove = { onRemoveFromFavorites(activeEntry) },
                        )
                    }
                }
            }
            LibraryTab.ContinueReading -> {
                item {
                    Text(
                        strings.activeSeriesCount(readingSeries.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (readingSeries.isEmpty()) {
                    item { EmptyCard(strings.readingHint) }
                } else {
                    items(readingSeries, key = { it.key }) { series ->
                        val preferred = remember(series.key, libraryState.selectedProviderId) {
                            preferredLibrarySeriesEntry(series, libraryState.selectedProviderId)
                        }
                        var activeProviderId by rememberSaveable(series.key, libraryState.selectedProviderId) {
                            mutableStateOf(preferred.providerId)
                        }
                        LaunchedEffect(series.key, preferred.providerId) {
                            if (series.entries.none { it.providerId == activeProviderId }) {
                                activeProviderId = preferred.providerId
                            }
                        }
                        val activeEntry = series.entries.firstOrNull { it.providerId == activeProviderId } ?: preferred
                        GroupedContinueReadingCard(
                            manga = activeEntry,
                            availableProviders = series.entries,
                            strings = strings,
                            providerNameForId = providerNameForId,
                            onProviderSelected = { activeProviderId = it },
                            onOpen = { onOpenManga(activeEntry.providerId, activeEntry.detailPath) },
                            onResume = {
                                if (activeEntry.lastChapterPath.isNotBlank()) {
                                    onOpenChapter(activeEntry.providerId, activeEntry.lastChapterPath)
                                }
                            },
                            onRemove = { onRemoveFromContinueReading(activeEntry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupedContinueReadingCard(
    manga: SavedManga,
    availableProviders: List<SavedManga>,
    strings: AppStrings,
    providerNameForId: (String) -> String,
    onProviderSelected: (String) -> Unit,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    GroupedSeriesCardShell(
        manga = manga,
        availableProviders = availableProviders,
        strings = strings,
        providerNameForId = providerNameForId,
        onProviderSelected = onProviderSelected,
        onOpen = onOpen,
        onRemove = onRemove,
    ) { activeEntry ->
        Text(
            "${strings.latestProgress}: ${activeEntry.localizedLastChapterTitle(strings)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onResume,
                enabled = activeEntry.lastChapterPath.isNotBlank(),
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
        }
    }
}

@Composable
private fun GroupedFavoriteMangaCard(
    manga: SavedManga,
    availableProviders: List<SavedManga>,
    strings: AppStrings,
    providerNameForId: (String) -> String,
    onProviderSelected: (String) -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    GroupedSeriesCardShell(
        manga = manga,
        availableProviders = availableProviders,
        strings = strings,
        providerNameForId = providerNameForId,
        onProviderSelected = onProviderSelected,
        onOpen = onOpen,
        onRemove = onRemove,
    ) { activeEntry ->
        val localizedLastChapterTitle = activeEntry.localizedLastChapterTitle(strings)
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
}

@Composable
private fun GroupedSeriesCardShell(
    manga: SavedManga,
    availableProviders: List<SavedManga>,
    strings: AppStrings,
    providerNameForId: (String) -> String,
    onProviderSelected: (String) -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    body: @Composable (SavedManga) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(cardBorder(), RoundedCornerShape(22.dp))
            .clickable(onClick = onOpen),
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
                TagChip(
                    label = providerNameForId(manga.providerId),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                body(manga)
                if (availableProviders.size > 1) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        availableProviders.forEach { entry ->
                            FilterChip(
                                selected = entry.providerId == manga.providerId,
                                onClick = { onProviderSelected(entry.providerId) },
                                label = { Text(providerNameForId(entry.providerId), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                }
            }
            FilledTonalIconButton(
                onClick = onRemove,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(Icons.Default.Delete, contentDescription = strings.removeFromFavorites)
            }
        }
    }
}
