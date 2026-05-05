package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paudinc.komastream.data.model.FavoriteMangaStatus
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.ui.components.*
import com.paudinc.komastream.ui.navigation.LibraryTab
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.groupLibrarySeries
import com.paudinc.komastream.utils.librarySeriesSortTitle
import com.paudinc.komastream.utils.preferredLibrarySeriesEntry
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    libraryState: LibraryState,
    strings: AppStrings,
    selectedTab: LibraryTab,
    providerNameForId: (String) -> String,
    chapterCountForManga: (String, String) -> Int?,
    resolveChapterPathForManga: (String, String, Double?, String) -> String?,
    onSelectTab: (LibraryTab) -> Unit,
    onOpenManga: (String, String) -> Unit,
    onOpenChapter: (String, String) -> Unit,
    onRemoveFromContinueReading: (SavedManga) -> Unit,
    onRemoveFromFavorites: (SavedManga) -> Unit,
    onSetFavoriteStatus: (SavedManga, FavoriteMangaStatus) -> Unit,
) {
    val readingSeries = remember(libraryState.reading) { groupLibrarySeries(libraryState.reading) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var favoritesQuery by rememberSaveable { mutableStateOf("") }
    var favoritesSortOption by rememberSaveable { mutableStateOf(LibrarySortOption.TitleAscending.name) }
    var favoritesStatusFilter by rememberSaveable { mutableStateOf(FavoriteLibraryStatusFilter.All.name) }
    var readingQuery by rememberSaveable { mutableStateOf("") }
    var readingSortOption by rememberSaveable { mutableStateOf(LibrarySortOption.TitleAscending.name) }
    val selectedFavoriteStatusFilter = FavoriteLibraryStatusFilter.fromName(favoritesStatusFilter)
    val favoriteFilteredSeries = remember(libraryState.favorites, selectedFavoriteStatusFilter) {
        groupLibrarySeries(
            libraryState.favorites.filter {
                selectedFavoriteStatusFilter == FavoriteLibraryStatusFilter.All ||
                    it.favoriteStatus == selectedFavoriteStatusFilter.status
            }
        )
    }
    val selectedSeries = when (selectedTab) {
        LibraryTab.Favorites -> favoriteFilteredSeries
        LibraryTab.ContinueReading -> readingSeries
    }
    val selectedQuery = when (selectedTab) {
        LibraryTab.Favorites -> favoritesQuery
        LibraryTab.ContinueReading -> readingQuery
    }
    val selectedSortOption = LibrarySortOption.fromName(
        when (selectedTab) {
            LibraryTab.Favorites -> favoritesSortOption
            LibraryTab.ContinueReading -> readingSortOption
        }
    )
    val filteredSeries = remember(selectedSeries, selectedQuery) {
        filterLibrarySeriesByTitle(selectedSeries, selectedQuery)
    }
    val preferredProviderId = libraryState.selectedProviderId
    val sortedSeries = remember(filteredSeries, selectedSortOption, preferredProviderId, chapterCountForManga) {
        sortLibrarySeries(
            series = filteredSeries,
            sortOption = selectedSortOption,
            preferredProviderId = preferredProviderId,
            chapterCountForManga = chapterCountForManga,
        )
    }
    val emptyHint = when (selectedTab) {
        LibraryTab.Favorites -> strings.addMangaHint
        LibraryTab.ContinueReading -> strings.readingHint
    }
    val emptyMessage = when (selectedTab) {
        LibraryTab.Favorites -> if (libraryState.favorites.isEmpty()) emptyHint else strings.noLibraryResults
        LibraryTab.ContinueReading -> if (readingSeries.isEmpty()) emptyHint else strings.noLibraryResults
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionTitle(strings.library)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selectedTab == LibraryTab.Favorites) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedFavoriteStatusFilter.ordinal,
                        edgePadding = 0.dp,
                        divider = {},
                    ) {
                        FavoriteLibraryStatusFilter.entries.forEach { filter ->
                            Tab(
                                selected = selectedFavoriteStatusFilter == filter,
                                onClick = { favoritesStatusFilter = filter.name },
                                text = {
                                    Text(
                                        filter.label(strings),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = selectedQuery,
                    onValueChange = { query ->
                        when (selectedTab) {
                            LibraryTab.Favorites -> favoritesQuery = query
                            LibraryTab.ContinueReading -> readingQuery = query
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.searchLibrary) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { sortMenuExpanded = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Icon(Icons.Default.Sort, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = selectedSortOption.label(strings),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                    ) {
                        LibrarySortOption.entries.forEach { option ->
                            val selected = option == selectedSortOption
                            DropdownMenuItem(
                                text = { Text(option.label(strings)) },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    sortMenuExpanded = false
                                    when (selectedTab) {
                                        LibraryTab.Favorites -> favoritesSortOption = option.name
                                        LibraryTab.ContinueReading -> readingSortOption = option.name
                                    }
                                },
                            )
                        }
                    }
                    if (
                        selectedQuery.isNotBlank() ||
                        selectedSortOption != LibrarySortOption.TitleAscending ||
                        (selectedTab == LibraryTab.Favorites && selectedFavoriteStatusFilter != FavoriteLibraryStatusFilter.All)
                    ) {
                        TextButton(
                            onClick = {
                                when (selectedTab) {
                                    LibraryTab.Favorites -> {
                                        favoritesQuery = ""
                                        favoritesSortOption = LibrarySortOption.TitleAscending.name
                                        favoritesStatusFilter = FavoriteLibraryStatusFilter.All.name
                                    }
                                    LibraryTab.ContinueReading -> {
                                        readingQuery = ""
                                        readingSortOption = LibrarySortOption.TitleAscending.name
                                    }
                                }
                            }
                        ) {
                            Text(strings.clearFilters)
                        }
                    }
                }
            }
        }
        item {
            Text(
                when (selectedTab) {
                    LibraryTab.Favorites -> strings.favoritesCount(sortedSeries.size)
                    LibraryTab.ContinueReading -> strings.activeSeriesCount(sortedSeries.size)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (sortedSeries.isEmpty()) {
            item { EmptyCard(emptyMessage) }
        } else {
            items(sortedSeries, key = { it.key }) { series ->
                val preferred = remember(series.key, preferredProviderId) {
                    preferredLibrarySeriesEntry(series, preferredProviderId)
                }
                var activeProviderId by rememberSaveable(series.key, preferredProviderId) {
                    mutableStateOf(preferred.providerId)
                }
                val activeEntry = series.entries.firstOrNull { it.providerId == activeProviderId } ?: preferred
                when (selectedTab) {
                    LibraryTab.Favorites -> GroupedFavoriteMangaCard(
                        manga = activeEntry,
                        availableProviders = series.entries,
                        strings = strings,
                        providerNameForId = providerNameForId,
                        chapterCount = seriesChapterCount(series.entries, chapterCountForManga),
                        onProviderSelected = { activeProviderId = it },
                        onOpen = { onOpenManga(activeEntry.providerId, activeEntry.detailPath) },
                        onRemove = { onRemoveFromFavorites(activeEntry) },
                        onSetStatus = { status -> onSetFavoriteStatus(activeEntry, status) },
                    )
                    LibraryTab.ContinueReading -> GroupedContinueReadingCard(
                        manga = activeEntry,
                        availableProviders = series.entries,
                        strings = strings,
                        providerNameForId = providerNameForId,
                        chapterCount = seriesChapterCount(series.entries, chapterCountForManga),
                        resumeChapterPath = resolveChapterPathForManga(
                            activeEntry.providerId,
                            activeEntry.detailPath,
                            activeEntry.lastProgressChapterNumber,
                            activeEntry.lastChapterPath,
                        ) ?: activeEntry.lastChapterPath,
                        onProviderSelected = { activeProviderId = it },
                        onOpen = { onOpenManga(activeEntry.providerId, activeEntry.detailPath) },
                        onResume = {
                            val chapterPath = resolveChapterPathForManga(
                                activeEntry.providerId,
                                activeEntry.detailPath,
                                activeEntry.lastProgressChapterNumber,
                                activeEntry.lastChapterPath,
                            ) ?: activeEntry.lastChapterPath
                            if (chapterPath.isNotBlank()) {
                                onOpenChapter(activeEntry.providerId, chapterPath)
                            }
                        },
                        onRemove = { onRemoveFromContinueReading(activeEntry) },
                    )
                }
            }
        }
    }
}

private enum class LibrarySortOption {
    TitleAscending,
    TitleDescending,
    CompletionDescending,
    CompletionAscending,
    ProgressDescending,
    ProgressAscending,
    ;

    fun label(strings: AppStrings): String = when (this) {
        TitleAscending -> strings.librarySortTitleAscending
        TitleDescending -> strings.librarySortTitleDescending
        CompletionDescending -> strings.librarySortCompletionDescending
        CompletionAscending -> strings.librarySortCompletionAscending
        ProgressDescending -> strings.librarySortProgressDescending
        ProgressAscending -> strings.librarySortProgressAscending
    }

    companion object {
        fun fromName(value: String): LibrarySortOption =
            entries.firstOrNull { it.name == value } ?: TitleAscending
    }
}

private fun filterLibrarySeriesByTitle(
    series: List<com.paudinc.komastream.utils.LibrarySeriesGroup>,
    query: String,
): List<com.paudinc.komastream.utils.LibrarySeriesGroup> {
    val normalizedQuery = normalizeLibraryQuery(query)
    if (normalizedQuery.isBlank()) return series
    return series.filter { group ->
        group.entries.any { normalizeLibraryQuery(it.title).contains(normalizedQuery) }
    }
}

private fun sortLibrarySeries(
    series: List<com.paudinc.komastream.utils.LibrarySeriesGroup>,
    sortOption: LibrarySortOption,
    preferredProviderId: String,
    chapterCountForManga: (String, String) -> Int?,
): List<com.paudinc.komastream.utils.LibrarySeriesGroup> {
    fun preferredEntry(group: com.paudinc.komastream.utils.LibrarySeriesGroup): SavedManga =
        preferredLibrarySeriesEntry(group, preferredProviderId)

    fun completionScore(group: com.paudinc.komastream.utils.LibrarySeriesGroup): Double {
        val chapterCount = seriesChapterCount(group.entries, chapterCountForManga)?.takeIf { it > 0 } ?: return Double.NaN
        val entry = preferredEntry(group)
        val readCount = displayReadCount(entry)?.takeIf { it > 0 } ?: return Double.NaN
        return (readCount.toDouble() / chapterCount.toDouble()).coerceIn(0.0, 1.0)
    }

    fun progressScore(group: com.paudinc.komastream.utils.LibrarySeriesGroup): Double {
        val entry = preferredEntry(group)
        return displayReadCount(entry)?.toDouble()
            ?: entry.lastReadChapterNumber?.takeIf { it > 0 }?.toDouble()
            ?: 0.0
    }

    val comparator = when (sortOption) {
        LibrarySortOption.TitleAscending -> compareBy<com.paudinc.komastream.utils.LibrarySeriesGroup> {
            librarySeriesSortTitle(it)
        }.thenBy { it.key }

        LibrarySortOption.TitleDescending -> compareByDescending<com.paudinc.komastream.utils.LibrarySeriesGroup> {
            librarySeriesSortTitle(it)
        }.thenByDescending { it.key }

        LibrarySortOption.CompletionDescending -> compareByDescending<com.paudinc.komastream.utils.LibrarySeriesGroup> {
            completionScore(it).takeIf { score -> !score.isNaN() } ?: -1.0
        }.thenBy { librarySeriesSortTitle(it) }
            .thenBy { it.key }

        LibrarySortOption.CompletionAscending -> compareBy<com.paudinc.komastream.utils.LibrarySeriesGroup> {
            completionScore(it).takeIf { score -> !score.isNaN() } ?: Double.POSITIVE_INFINITY
        }.thenBy { librarySeriesSortTitle(it) }
            .thenBy { it.key }

        LibrarySortOption.ProgressDescending -> compareByDescending<com.paudinc.komastream.utils.LibrarySeriesGroup> {
            progressScore(it)
        }.thenBy { librarySeriesSortTitle(it) }
            .thenBy { it.key }

        LibrarySortOption.ProgressAscending -> compareBy<com.paudinc.komastream.utils.LibrarySeriesGroup> {
            progressScore(it)
        }.thenBy { librarySeriesSortTitle(it) }
            .thenBy { it.key }
    }
    return series.sortedWith(comparator)
}

@Composable
private fun GroupedContinueReadingCard(
    manga: SavedManga,
    availableProviders: List<SavedManga>,
    chapterCount: Int?,
    resumeChapterPath: String?,
    strings: AppStrings,
    providerNameForId: (String) -> String,
    onProviderSelected: (String) -> Unit,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    CompactSeriesCardShell(
        manga = manga,
        availableProviders = availableProviders,
        strings = strings,
        providerNameForId = providerNameForId,
        onProviderSelected = onProviderSelected,
        onOpen = onOpen,
        onRemove = onRemove,
    ) {
        val progressText = libraryProgressText(strings, manga, chapterCount)
        if (progressText.isNotBlank()) {
            Text(
                progressText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val latestProgressText = manga.localizedLastChapterTitle(strings)
        if (latestProgressText.isNotBlank()) {
            Text(
                "${strings.latestProgress}: $latestProgressText",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(
            onClick = onResume,
            enabled = !resumeChapterPath.isNullOrBlank(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
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

@Composable
private fun GroupedFavoriteMangaCard(
    manga: SavedManga,
    availableProviders: List<SavedManga>,
    chapterCount: Int?,
    strings: AppStrings,
    providerNameForId: (String) -> String,
    onProviderSelected: (String) -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onSetStatus: (FavoriteMangaStatus) -> Unit,
) {
    CompactSeriesCardShell(
        manga = manga,
        availableProviders = availableProviders,
        strings = strings,
        providerNameForId = providerNameForId,
        onProviderSelected = onProviderSelected,
        onOpen = onOpen,
        onRemove = onRemove,
        onSetStatus = onSetStatus,
    ) {
        TagChip(
            label = manga.favoriteStatus.label(strings),
            containerColor = favoriteStatusContainerColor(manga.favoriteStatus),
            labelColor = favoriteStatusLabelColor(manga.favoriteStatus),
        )
        val progressText = libraryProgressText(strings, manga, chapterCount)
        if (progressText.isNotBlank()) {
            Text(
                progressText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
}

@Composable
private fun CompactSeriesCardShell(
    manga: SavedManga,
    availableProviders: List<SavedManga>,
    strings: AppStrings,
    providerNameForId: (String) -> String,
    onProviderSelected: (String) -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onSetStatus: ((FavoriteMangaStatus) -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    var menuExpanded by rememberSaveable(manga.providerId, manga.detailPath) { mutableStateOf(false) }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(cardBorder(), RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuExpanded = true },
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AsyncImage(
                model = manga.coverUrl,
                contentDescription = manga.title,
                modifier = Modifier
                    .size(width = 60.dp, height = 84.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                placeholder = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery),
                error = androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_report_image),
            )
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    manga.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                body()
                if (availableProviders.size > 1) {
                    ProviderMenu(
                        selectedProviderId = manga.providerId,
                        availableProviders = availableProviders,
                        providerNameForId = providerNameForId,
                        onProviderSelected = onProviderSelected,
                    )
                } else {
                    Text(
                        providerNameForId(manga.providerId),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Icon(Icons.Default.Delete, contentDescription = strings.removeFromFavorites)
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
        if (onSetStatus != null) {
            FavoriteMangaStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(status.label(strings)) },
                    leadingIcon = if (manga.favoriteStatus == status) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        menuExpanded = false
                        onSetStatus(status)
                    },
                )
            }
        }
        DropdownMenuItem(
            text = { Text(strings.removeFromFavorites) },
            onClick = {
                menuExpanded = false
                onRemove()
            },
        )
    }
}

@Composable
private fun ProviderMenu(
    selectedProviderId: String,
    availableProviders: List<SavedManga>,
    providerNameForId: (String) -> String,
    onProviderSelected: (String) -> Unit,
) {
    var expanded by rememberSaveable(selectedProviderId) { mutableStateOf(false) }
    val selectedLabel = providerNameForId(selectedProviderId)
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) {
            Text(
                selectedLabel,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            availableProviders.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(providerNameForId(entry.providerId)) },
                    leadingIcon = if (entry.providerId == selectedProviderId) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        expanded = false
                        onProviderSelected(entry.providerId)
                    },
                )
            }
        }
    }
}

private fun normalizeLibraryQuery(value: String): String {
    return value.trim().lowercase()
}

private fun libraryProgressText(
    strings: AppStrings,
    manga: SavedManga,
    chapterCount: Int?,
): String {
    val readCount = displayReadCount(manga)
    val total = chapterCount?.takeIf { it > 0 }
    return when {
        readCount != null && total != null -> {
            val percent = ((readCount.toDouble() / total.toDouble()) * 100).toInt().coerceIn(0, 100)
            "$readCount/$total ${strings.chapters.lowercase()} · $percent%"
        }
        readCount != null -> "$readCount ${strings.chapters.lowercase()} · ${strings.read}"
        total != null -> "$total ${strings.chapters.lowercase()}"
        else -> ""
    }
}

private fun displayReadCount(manga: SavedManga): Int? {
    manga.lastProgressChapterNumber?.let { progressNumber ->
        val completed = ceil(progressNumber).toInt() - 1
        if (completed >= 0) return completed
    }
    return manga.lastReadChapterNumber?.takeIf { it > 0 }
}

private fun seriesChapterCount(
    entries: List<SavedManga>,
    chapterCountForManga: (String, String) -> Int?,
): Int? {
    return entries.asSequence()
        .mapNotNull { entry -> chapterCountForManga(entry.providerId, entry.detailPath) }
        .maxOrNull()
}

private enum class FavoriteLibraryStatusFilter(
    val status: FavoriteMangaStatus?,
) {
    All(null),
    Completed(FavoriteMangaStatus.COMPLETED),
    Reading(FavoriteMangaStatus.READING),
    Paused(FavoriteMangaStatus.PAUSED),
    Dropped(FavoriteMangaStatus.DROPPED),
    ;

    fun label(strings: AppStrings): String = when (this) {
        All -> strings.favoriteStatusAll
        Completed -> strings.completedStatus
        Reading -> strings.favoriteStatusReading
        Paused -> strings.favoriteStatusPaused
        Dropped -> strings.favoriteStatusDropped
    }

    companion object {
        fun fromName(value: String?): FavoriteLibraryStatusFilter =
            entries.firstOrNull { it.name == value } ?: All
    }
}

@Composable
private fun favoriteStatusContainerColor(status: FavoriteMangaStatus): Color = when (status) {
    FavoriteMangaStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
    FavoriteMangaStatus.READING -> MaterialTheme.colorScheme.secondaryContainer
    FavoriteMangaStatus.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer
    FavoriteMangaStatus.DROPPED -> MaterialTheme.colorScheme.errorContainer
}

@Composable
private fun favoriteStatusLabelColor(status: FavoriteMangaStatus): Color = when (status) {
    FavoriteMangaStatus.COMPLETED -> MaterialTheme.colorScheme.onPrimaryContainer
    FavoriteMangaStatus.READING -> MaterialTheme.colorScheme.onSecondaryContainer
    FavoriteMangaStatus.PAUSED -> MaterialTheme.colorScheme.onTertiaryContainer
    FavoriteMangaStatus.DROPPED -> MaterialTheme.colorScheme.onErrorContainer
}
