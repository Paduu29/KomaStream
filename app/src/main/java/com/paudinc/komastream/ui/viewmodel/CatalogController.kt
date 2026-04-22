package com.paudinc.komastream.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paudinc.komastream.data.repository.CatalogStateInteractor
import com.paudinc.komastream.provider.MangaProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CatalogController(
    private val scope: CoroutineScope,
    private val catalogStateInteractor: CatalogStateInteractor,
) {
    var uiState by mutableStateOf(CatalogUiState())
        private set

    fun refreshFilterOptions(provider: MangaProvider) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { provider.fetchCatalogFilterOptions() } }
                .onSuccess { options ->
                    val selectionState = catalogStateInteractor.normalizeSelection(
                        options = options,
                        selectedSortOptionId = uiState.selectedSortOptionId,
                        selectedStatusOptionId = uiState.selectedStatusOptionId,
                    )
                    uiState = uiState.copy(
                        filterOptions = options,
                        selectedSortOptionId = selectionState.selectedSortOptionId,
                        selectedStatusOptionId = selectionState.selectedStatusOptionId,
                    )
                }
                .onFailure { Log.e("KomaStream", "Could not fetch catalog filters", it) }
        }
    }

    fun search(
        provider: MangaProvider,
        loadMore: Boolean,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            if (loadMore) {
                uiState = uiState.copy(isLoadingMore = true)
            } else {
                onLoadingChange(true)
            }
            val skip = if (loadMore) uiState.results.size else 0
            runCatching {
                withContext(Dispatchers.IO) {
                    provider.searchCatalog(
                        query = uiState.query,
                        categoryIds = uiState.selectedCategoryIds.toList(),
                        sortBy = uiState.selectedSortOptionId,
                        broadcastStatus = uiState.selectedStatusOptionId,
                        onlyFavorites = uiState.onlyFavorites,
                        skip = skip,
                        take = 20,
                    )
                }
            }.onSuccess { result ->
                uiState = uiState.copy(
                    results = catalogStateInteractor.mergeResults(
                        currentItems = uiState.results,
                        incomingItems = result.items,
                        loadMore = loadMore,
                    ),
                    hasMoreResults = result.hasMore,
                    isLoadingMore = false,
                )
            }.onFailure {
                Log.e("KomaStream", "Search failed", it)
                onError(it.message ?: "Could not search catalog")
            }.also {
                if (loadMore) {
                    uiState = uiState.copy(isLoadingMore = false)
                } else {
                    onLoadingChange(false)
                }
            }
        }
    }

    fun updateQuery(query: String) {
        uiState = uiState.copy(query = query)
    }

    fun toggleCategory(categoryId: String) {
        val selectedCategoryIds = uiState.selectedCategoryIds
        uiState = uiState.copy(
            selectedCategoryIds = if (selectedCategoryIds.contains(categoryId)) {
                selectedCategoryIds - categoryId
            } else {
                selectedCategoryIds + categoryId
            }
        )
    }

    fun selectSort(sortOptionId: String) {
        uiState = uiState.copy(selectedSortOptionId = sortOptionId)
    }

    fun selectStatus(statusOptionId: String) {
        uiState = uiState.copy(selectedStatusOptionId = statusOptionId)
    }

    fun setOnlyFavorites(onlyFavorites: Boolean) {
        uiState = uiState.copy(onlyFavorites = onlyFavorites)
    }

    fun clearFilters() {
        uiState = uiState.copy(
            query = "",
            selectedCategoryIds = emptySet(),
            selectedSortOptionId = "",
            selectedStatusOptionId = "",
            onlyFavorites = false,
        )
    }

    fun resetForProviderChange() {
        uiState = CatalogUiState()
    }

    fun clearResults() {
        uiState = uiState.copy(
            results = emptyList(),
            hasMoreResults = false,
            isLoadingMore = false,
        )
    }
}
