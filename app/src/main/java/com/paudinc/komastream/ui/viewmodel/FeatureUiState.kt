package com.paudinc.komastream.ui.viewmodel

import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CommunityPage
import com.paudinc.komastream.data.model.AppLanguage
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
    val lookup: LibraryLookupState = LibraryLookupState(),
    val selectedTab: LibraryTab = LibraryTab.ContinueReading,
    val downloadedChapterPaths: Set<String> = emptySet(),
    val isBulkUpdatingChapters: Boolean = false,
)

data class LibraryLookupState(
    val favoriteKeys: Set<String> = emptySet(),
    val chapterProgressByKey: Map<String, Int> = emptyMap(),
    val cachedDetailByKey: Map<String, MangaDetail> = emptyMap(),
    val readChaptersByProvider: Map<String, Set<String>> = emptyMap(),
)

fun emptyLibraryState(): LibraryState =
    LibraryState(
        favorites = emptyList(),
        reading = emptyList(),
        readChapters = emptySet(),
        useDarkTheme = false,
        autoJumpToUnread = true,
        adultContentEnabled = false,
        adultOnlyProvidersEnabled = false,
        disabledProviderIds = emptySet(),
        mangaBallAdultContentEnabled = false,
        manhwaLatinoAdultContentEnabled = false,
        preferredChapterLanguage = AppLanguage.EN,
        selectedProviderId = "",
        appLanguage = AppLanguage.EN,
    )

fun emptyHomeFeed(): HomeFeed =
    HomeFeed(
        latestUpdates = emptyList(),
        popularChapters = emptyList(),
        popularMangas = emptyList(),
    )

data class ReaderUiState(
    val selectedDetail: MangaDetail? = null,
    val requestedDetailPath: String = "",
    val selectedCommunityPage: CommunityPage? = null,
    val requestedCommunityPath: String = "",
    val readerData: ReaderData? = null,
    val initialPageIndex: Int = 0,
    val currentPageIndex: Int = 0,
    val isChapterLoading: Boolean = false,
    val isCommunityLoading: Boolean = false,
)

data class MyAnimeListUiState(
    val isConfigured: Boolean = false,
    val isConnected: Boolean = false,
    val clientId: String = "",
    val username: String = "",
    val isSyncing: Boolean = false,
    val syncStageMessage: String = "",
    val syncItemsProcessed: Int = 0,
    val syncItemsTotal: Int = 0,
    val syncEtaSeconds: Int? = null,
    val lastMessage: String = "",
    val errorMessage: String = "",
    val pendingImports: List<MyAnimeListPendingImport> = emptyList(),
)

enum class MyAnimeListPendingImportSource {
    FROM_REMOTE,
    TO_REMOTE,
}

data class MyAnimeListMatchCandidate(
    val key: String,
    val title: String,
    val displayTitle: String = title,
    val coverUrl: String,
    val providerId: String = "",
    val detailPath: String = "",
    val malMangaId: Long? = null,
    val status: String = "",
    val chaptersCount: String = "",
)

data class MyAnimeListPendingImport(
    val source: MyAnimeListPendingImportSource,
    val pendingKey: String,
    val malMangaId: Long? = null,
    val localProviderId: String = "",
    val localDetailPath: String = "",
    val title: String,
    val status: String,
    val numChaptersRead: Int,
    val alternativeTitles: List<String>,
    val candidates: List<MyAnimeListMatchCandidate>,
)
