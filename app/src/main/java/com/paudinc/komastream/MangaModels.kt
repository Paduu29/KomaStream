package com.paudinc.komastream

enum class AppLanguage {
    EN,
    ES,
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
)

data class MangaChapter(
    val id: String,
    val chapterLabel: String,
    val chapterNumberUrl: String,
    val pagesCount: Int,
    val registrationDate: String,
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

data class HomeFeed(
    val latestUpdates: List<ChapterSummary>,
    val popularChapters: List<ChapterSummary>,
    val popularMangas: List<MangaSummary>,
)

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
    val selectedProviderId: String,
    val appLanguage: AppLanguage,
)
