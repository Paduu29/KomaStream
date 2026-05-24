package com.paudinc.komastream.ui.viewmodel

import com.paudinc.komastream.data.repository.CatalogStateInteractor
import com.paudinc.komastream.provider.MangaProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CatalogController(
    private val scope: CoroutineScope,
    private val catalogStateInteractor: CatalogStateInteractor,
) {
    private val _uiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    fun refreshFilterOptions(provider: MangaProvider) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { provider.fetchCatalogFilterOptions() } }
                .onSuccess { options ->
                    val selectionState = catalogStateInteractor.normalizeSelection(
                        options = options,
                        selectedSortOptionId = _uiState.value.selectedSortOptionId,
                        selectedStatusOptionId = _uiState.value.selectedStatusOptionId,
                    )
                    _uiState.update {
                        it.copy(
                            filterOptions = options,
                            selectedSortOptionId = selectionState.selectedSortOptionId,
                            selectedStatusOptionId = selectionState.selectedStatusOptionId,
                        )
                    }
                }
                .onFailure { }
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
                _uiState.update { it.copy(isLoadingMore = true) }
            } else {
                onLoadingChange(true)
            }
            val state = _uiState.value
            val skip = if (loadMore) state.results.size else 0
            runCatching {
                withContext(Dispatchers.IO) {
                    provider.searchCatalog(
                        query = state.query,
                        categoryIds = state.selectedCategoryIds.toList(),
                        sortBy = state.selectedSortOptionId,
                        broadcastStatus = state.selectedStatusOptionId,
                        onlyFavorites = state.onlyFavorites,
                        skip = skip,
                        take = 20,
                    )
                }
            }.onSuccess { result ->
                _uiState.update { current ->
                    current.copy(
                        results = catalogStateInteractor.mergeResults(
                            currentItems = current.results,
                            incomingItems = result.items,
                            loadMore = loadMore,
                        ),
                        hasMoreResults = result.hasMore,
                        isLoadingMore = false,
                    )
                }
            }.onFailure {
                onError(it.message ?: "Could not search catalog")
            }.also {
                if (loadMore) {
                    _uiState.update { stateValue -> stateValue.copy(isLoadingMore = false) }
                } else {
                    onLoadingChange(false)
                }
            }
        }
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun toggleCategory(categoryId: String) {
        _uiState.update { state ->
            val selectedCategoryIds = state.selectedCategoryIds
            state.copy(
                selectedCategoryIds = if (selectedCategoryIds.contains(categoryId)) {
                    selectedCategoryIds - categoryId
                } else {
                    selectedCategoryIds + categoryId
                }
            )
        }
    }

    fun selectSort(sortOptionId: String) {
        _uiState.update { it.copy(selectedSortOptionId = sortOptionId) }
    }

    fun selectStatus(statusOptionId: String) {
        _uiState.update { it.copy(selectedStatusOptionId = statusOptionId) }
    }

    fun setOnlyFavorites(onlyFavorites: Boolean) {
        _uiState.update { it.copy(onlyFavorites = onlyFavorites) }
    }

    fun clearFilters() {
        _uiState.update {
            it.copy(
                query = "",
                selectedCategoryIds = emptySet(),
                selectedSortOptionId = "",
                selectedStatusOptionId = "",
                onlyFavorites = false,
            )
        }
    }

    fun resetForProviderChange() {
        _uiState.value = CatalogUiState()
    }

    fun clearResults() {
        _uiState.update {
            it.copy(
                results = emptyList(),
                hasMoreResults = false,
                isLoadingMore = false,
            )
        }
    }
}
