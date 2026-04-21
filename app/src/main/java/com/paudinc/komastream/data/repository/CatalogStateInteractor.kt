package com.paudinc.komastream.data.repository

import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.MangaSummary

class CatalogStateInteractor {
    fun mergeResults(
        currentItems: List<MangaSummary>,
        incomingItems: List<MangaSummary>,
        loadMore: Boolean,
    ): List<MangaSummary> {
        return if (loadMore) {
            (currentItems + incomingItems).distinctBy { item -> item.providerId to item.detailPath }
        } else {
            incomingItems
        }
    }

    fun normalizeSelection(
        options: CatalogFilterOptions,
        selectedSortOptionId: String,
        selectedStatusOptionId: String,
    ): CatalogSelectionState {
        return CatalogSelectionState(
            selectedSortOptionId = if (options.sortOptions.any { it.id == selectedSortOptionId }) {
                selectedSortOptionId
            } else {
                options.sortOptions.firstOrNull()?.id ?: "2"
            },
            selectedStatusOptionId = if (options.statusOptions.any { it.id == selectedStatusOptionId }) {
                selectedStatusOptionId
            } else {
                options.statusOptions.firstOrNull()?.id ?: "0"
            },
        )
    }
}

data class CatalogSelectionState(
    val selectedSortOptionId: String,
    val selectedStatusOptionId: String,
)
