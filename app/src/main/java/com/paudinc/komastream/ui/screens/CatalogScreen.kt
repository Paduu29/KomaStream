package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.CategoryOption
import com.paudinc.komastream.data.model.FilterOption
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.ui.components.*
import com.paudinc.komastream.ui.navigation.CatalogMode
import com.paudinc.komastream.utils.AppStrings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(
    strings: AppStrings,
    providerId: String,
    query: String,
    categories: List<CategoryOption>,
    sortOptions: List<FilterOption>,
    statusOptions: List<FilterOption>,
    selectedCategoryIds: Set<String>,
    selectedSortOptionId: String,
    selectedStatusOptionId: String,
    onlyFavorites: Boolean,
    results: List<MangaSummary>,
    hasMoreResults: Boolean,
    isLoadingMore: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleCategory: (String) -> Unit,
    onSelectSort: (String) -> Unit,
    onSelectStatus: (String) -> Unit,
    onToggleOnlyFavorites: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (String, String) -> Unit,
    onToggleFavorite: (SavedManga) -> Unit,
    isFavorite: (String, String) -> Boolean,
) {
    var catalogMode by rememberSaveable(providerId) { mutableStateOf(CatalogMode.Basic) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                border = cardBorder(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    TabRow(
                        selectedTabIndex = catalogMode.ordinal,
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ) {
                        Tab(
                            selected = catalogMode == CatalogMode.Basic,
                            onClick = { catalogMode = CatalogMode.Basic },
                            text = { Text(strings.search) },
                        )
                        Tab(
                            selected = catalogMode == CatalogMode.Advanced,
                            onClick = { catalogMode = CatalogMode.Advanced },
                            text = { Text(strings.additionalFilters) },
                        )
                    }
                    when (catalogMode) {
                        CatalogMode.Basic -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = onQueryChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(strings.searchAvailableMangas) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = onSearch) { Text(strings.search) }
                                    Button(onClick = onClearFilters) { Text(strings.clearFilters) }
                                }
                            }
                        }

                        CatalogMode.Advanced -> {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (categories.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(strings.categories, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            categories.forEach { category ->
                                                val selected = selectedCategoryIds.contains(category.id)
                                                FilterChip(
                                                    onClick = { onToggleCategory(category.id) },
                                                    selected = selected,
                                                    label = { Text(category.name, maxLines = 1) },
                                                    leadingIcon = if (selected) {
                                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                                    } else null,
                                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                                    border = FilterChipDefaults.filterChipBorder(
                                                        enabled = true,
                                                        selected = selected,
                                                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                                                        selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                                    ),
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        containerColor = MaterialTheme.colorScheme.surface,
                                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                                if (sortOptions.isNotEmpty()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(strings.sortBy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            sortOptions.forEach { option ->
                                                val selected = option.id == selectedSortOptionId
                                                FilterChip(
                                                    onClick = { onSelectSort(option.id) },
                                                    selected = selected,
                                                    label = { Text(option.name, maxLines = 1) },
                                                    leadingIcon = if (selected) {
                                                        { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                                    } else null,
                                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                                    border = FilterChipDefaults.filterChipBorder(
                                                        enabled = true,
                                                        selected = selected,
                                                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                                                        selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                                    ),
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        containerColor = MaterialTheme.colorScheme.surface,
                                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(strings.additionalFilters, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (statusOptions.isNotEmpty()) {
                                        Text(strings.state, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            statusOptions.forEach { option ->
                                                val selected = option.id == selectedStatusOptionId
                                                FilterChip(
                                                    onClick = { onSelectStatus(option.id) },
                                                    selected = selected,
                                                    label = { Text(option.name, maxLines = 1) },
                                                    leadingIcon = if (selected) {
                                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                                    } else null,
                                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                                    border = FilterChipDefaults.filterChipBorder(
                                                        enabled = true,
                                                        selected = selected,
                                                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                                                        selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                                    ),
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        containerColor = MaterialTheme.colorScheme.surface,
                                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                                        selectedLabelColor = MaterialTheme.colorScheme.primary,
                                                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        FilterChip(
                                            selected = onlyFavorites,
                                            onClick = { onToggleOnlyFavorites(!onlyFavorites) },
                                            label = { Text(strings.favorites) },
                                            leadingIcon = if (onlyFavorites) {
                                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                            } else null,
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                                            border = FilterChipDefaults.filterChipBorder(
                                                enabled = true,
                                                selected = onlyFavorites,
                                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                                selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                            ),
                                            colors = FilterChipDefaults.filterChipColors(
                                                containerColor = MaterialTheme.colorScheme.surface,
                                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                                selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                                            ),
                                        )
                                    }
                                }
                                Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) { Text(strings.search) }
                            }
                        }
                    }
                }
            }
        }
        if (results.isEmpty()) {
            item { EmptyCard(strings.searchEmptyCatalog) }
        } else {
            items(results) { manga ->
                MangaCoverCard(
                    manga = manga,
                    strings = strings,
                    constrained = false,
                    favoriteActionLabel = if (isFavorite(manga.providerId, manga.detailPath)) strings.removeFromFavorites else strings.addToFavorites,
                    onClick = { onOpen(manga.providerId, manga.detailPath) },
                    onFavoriteAction = {
                        onToggleFavorite(
                            SavedManga(
                                providerId = manga.providerId,
                                title = manga.title,
                                detailPath = manga.detailPath,
                                coverUrl = manga.coverUrl,
                            )
                        )
                    },
                    onOpenMangaAction = { onOpen(manga.providerId, manga.detailPath) },
                )
            }
            if (hasMoreResults) {
                item {
                    if (isLoadingMore) {
                        LoadingPlaceholder()
                    } else {
                        Button(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text(strings.loadMore) }
                    }
                }
            }
        }
    }
}
