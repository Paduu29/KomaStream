package com.paudinc.komastream.data.model

import java.util.Locale

enum class AppLanguage {
    EN,
    ES,
    DE,
    MULTI,
    ;

    fun toLanguageTag(): String = when (this) {
        ES -> "es"
        DE -> "de"
        else -> "en"
    }

    companion object {
        fun fromStored(value: String?): AppLanguage =
            entries.firstOrNull { it.name == value } ?: EN

        fun defaultForSystem(locale: Locale?): AppLanguage = when (locale?.language?.lowercase(Locale.ROOT)) {
            "es" -> ES
            "de" -> DE
            else -> EN
        }
    }
}

data class MangaSummary(
    val providerId: String,
    val title: String,
    val detailPath: String,
    val coverUrl: String,
    val status: String = "",
    val periodicity: String = "",
    val latestPublication: String = "",
    val chaptersCount: String = "",
    val views: String = "",
)

data class CategoryOption(
    val id: String,
    val name: String,
)

data class FilterOption(
    val id: String,
    val name: String,
)

data class CatalogFilterOptions(
    val categories: List<CategoryOption>,
    val sortOptions: List<FilterOption>,
    val statusOptions: List<FilterOption>,
)

data class CatalogSearchResult(
    val items: List<MangaSummary>,
    val hasMore: Boolean,
)

data class ChapterSummary(
    val providerId: String,
    val mangaTitle: String,
    val chapterLabel: String,
    val chapterNumberUrl: String,
    val chapterId: String,
    val mangaPath: String,
    val chapterPath: String,
    val coverUrl: String,
    val registrationLabel: String = "",
)

data class MangaDetail(
    val providerId: String,
    val identification: String,
    val title: String,
    val detailPath: String,
    val coverUrl: String,
    val bannerUrl: String,
    val description: String,
    val status: String,
    val publicationDate: String,
    val periodicity: String,
    val chapters: List<MangaChapter>,
    val chapterSources: List<ChapterSourceOption> = emptyList(),
    val selectedChapterSourceId: String = "",
    val needsCloudflareClearance: Boolean = false,
)

data class ChapterSourceOption(
    val id: String,
    val name: String,
    val detailPath: String,
)

data class MangaChapter(
    val id: String,
    val chapterLabel: String,
    val chapterNumberUrl: String,
    val path: String = "",
    val pagesCount: Int,
    val registrationDate: String,
    val languageCode: String = "",
    val languageLabel: String = "",
    val uploaderLabel: String = "",
)

data class ReaderPage(
    val id: String,
    val numberLabel: String,
    val imageUrl: String,
    val offlineFileName: String = "",
)

data class ReaderData(
    val providerId: String,
    val mangaTitle: String,
    val mangaDetailPath: String,
    val chapterTitle: String,
    val chapterPath: String,
    val previousChapterPath: String?,
    val nextChapterPath: String?,
    val pages: List<ReaderPage>,
)

enum class HomeSectionType {
    CHAPTERS,
    MANGAS,
}

data class HomeFeedSection(
    val id: String,
    val title: String,
    val type: HomeSectionType,
    val chapters: List<ChapterSummary> = emptyList(),
    val mangas: List<MangaSummary> = emptyList(),
)

data class HomeSectionPageResult(
    val type: HomeSectionType,
    val chapters: List<ChapterSummary> = emptyList(),
    val mangas: List<MangaSummary> = emptyList(),
    val hasMore: Boolean = false,
)

data class HomeFeed(
    val latestUpdates: List<ChapterSummary>,
    val popularChapters: List<ChapterSummary>,
    val popularMangas: List<MangaSummary>,
    val sections: List<HomeFeedSection> = defaultHomeSections(
        latestUpdates = latestUpdates,
        popularChapters = popularChapters,
        popularMangas = popularMangas,
    ),
) {
    val chapterSections: List<HomeFeedSection>
        get() = sections.filter { it.type == HomeSectionType.CHAPTERS && it.chapters.isNotEmpty() }
}

private fun defaultHomeSections(
    latestUpdates: List<ChapterSummary>,
    popularChapters: List<ChapterSummary>,
    popularMangas: List<MangaSummary>,
): List<HomeFeedSection> = buildList {
    if (latestUpdates.isNotEmpty()) {
        add(
            HomeFeedSection(
                id = "latest-updates",
                title = "Latest Updates",
                type = HomeSectionType.CHAPTERS,
                chapters = latestUpdates,
            )
        )
    }
    if (popularChapters.isNotEmpty()) {
        add(
            HomeFeedSection(
                id = "popular-chapters",
                title = "Popular Chapters",
                type = HomeSectionType.CHAPTERS,
                chapters = popularChapters,
            )
        )
    }
    if (popularMangas.isNotEmpty()) {
        add(
            HomeFeedSection(
                id = "popular-mangas",
                title = "Popular Mangas",
                type = HomeSectionType.MANGAS,
                mangas = popularMangas,
            )
        )
    }
}

data class SavedManga(
    val providerId: String,
    val title: String,
    val detailPath: String,
    val coverUrl: String,
    val lastChapterTitle: String = "",
    val lastChapterPath: String = "",
)

data class LibraryState(
    val favorites: List<SavedManga>,
    val reading: List<SavedManga>,
    val readChapters: Set<String>,
    val useDarkTheme: Boolean,
    val autoJumpToUnread: Boolean,
    val mangaBallAdultContentEnabled: Boolean,
    val selectedProviderId: String,
    val appLanguage: AppLanguage,
)

enum class BackupOperationType {
    IMPORT,
    EXPORT,
}

sealed interface BackupOperationUiState {
    data object Idle : BackupOperationUiState

    data class InProgress(
        val type: BackupOperationType,
        val progressPercent: Int,
    ) : BackupOperationUiState

    data class Completed(
        val type: BackupOperationType,
        val success: Boolean,
        val message: String,
    ) : BackupOperationUiState
}
