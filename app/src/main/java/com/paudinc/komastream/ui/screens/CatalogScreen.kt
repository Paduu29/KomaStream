package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.CategoryOption
import com.paudinc.komastream.data.model.FilterOption
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.ui.components.*
import com.paudinc.komastream.ui.navigation.CatalogMode
import com.paudinc.komastream.utils.AppStrings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(
    strings: AppStrings,
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
) {
    var catalogMode by rememberSaveable { mutableStateOf(CatalogMode.Basic) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TabRow(selectedTabIndex = catalogMode.ordinal) {
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
                                            AssistChip(
                                                onClick = { onToggleCategory(category.id) },
                                                label = { Text(category.name) },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                    labelColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                            AssistChip(
                                                onClick = { onSelectSort(option.id) },
                                                label = { Text(option.name) },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                    labelColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                            AssistChip(
                                                onClick = { onSelectStatus(option.id) },
                                                label = { Text(option.name) },
                                                colors = AssistChipDefaults.assistChipColors(
                                                    containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                                    labelColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            )
                                        }
                                    }
                                }
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(checked = onlyFavorites, onCheckedChange = onToggleOnlyFavorites)
                                    Text(strings.favorites)
                                }
                            }
                            Button(onClick = onSearch, modifier = Modifier.fillMaxWidth()) { Text(strings.search) }
                        }
                    }
                }
            }
        }
        item { SectionTitle(strings.search) }
        if (results.isEmpty()) {
            item { EmptyCard(strings.searchEmptyCatalog) }
        } else {
            items(results) { manga ->
                MangaCoverCard(manga, strings, constrained = false) { onOpen(manga.providerId, manga.detailPath) }
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
