package com.paudinc.komastream.ui.viewmodel

import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.ui.navigation.LibraryTab

data class HomeUiState(
    val feed: HomeFeed? = null,
    val isRefreshing: Boolean = false
)

data class CatalogUiState(
    val query: String = "",
    val filterOptions: CatalogFilterOptions = CatalogFilterOptions(emptyList(), emptyList(), emptyList()),
    val selectedCategoryIds: Set<String> = emptySet(),
    val selectedSortOptionId: String = "",
    val selectedStatusOptionId: String = "",
    val onlyFavorites: Boolean = false,
    val results: List<MangaSummary> = emptyList(),
    val hasMoreResults: Boolean = false,
    val isLoadingMore: Boolean = false,
)

data class LibraryUiState(
    val state: LibraryState,
    val allProvidersState: LibraryState = state,
    val selectedTab: LibraryTab = LibraryTab.ContinueReading,
    val downloadedChapterPaths: Set<String> = emptySet(),
    val isBulkUpdatingChapters: Boolean = false,
)

data class ReaderUiState(
    val selectedDetail: MangaDetail? = null,
    val readerData: ReaderData? = null,
    val initialPageIndex: Int = 0,
    val currentPageIndex: Int = 0,
    val isChapterLoading: Boolean = false,
)

data class MyAnimeListUiState(
    val isConfigured: Boolean = false,
    val isConnected: Boolean = false,
    val clientId: String = "",
    val username: String = "",
    val isSyncing: Boolean = false,
    val syncItemsProcessed: Int = 0,
    val syncItemsTotal: Int = 0,
    val syncEtaSeconds: Int? = null,
    val lastMessage: String = "",
    val errorMessage: String = "",
)
