package com.paudinc.komastream.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "favorite_manga",
    primaryKeys = ["provider_id", "detail_path"],
)
data class FavoriteMangaEntity(
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "detail_path")
    val detailPath: String,
    val title: String,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String,
    @ColumnInfo(name = "last_chapter_title")
    val lastChapterTitle: String = "",
    @ColumnInfo(name = "last_chapter_path")
    val lastChapterPath: String = "",
    @ColumnInfo(name = "mal_manga_id")
    val malMangaId: Long? = null,
    @ColumnInfo(name = "order_index")
    val orderIndex: Long = 0L,
)

@Entity(
    tableName = "reading_manga",
    primaryKeys = ["provider_id", "detail_path"],
)
data class ReadingMangaEntity(
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "detail_path")
    val detailPath: String,
    val title: String,
    @ColumnInfo(name = "cover_url")
    val coverUrl: String,
    @ColumnInfo(name = "last_chapter_title")
    val lastChapterTitle: String = "",
    @ColumnInfo(name = "last_chapter_path")
    val lastChapterPath: String = "",
    @ColumnInfo(name = "mal_manga_id")
    val malMangaId: Long? = null,
    @ColumnInfo(name = "order_index")
    val orderIndex: Long = 0L,
)

@Entity(
    tableName = "read_chapters",
    primaryKeys = ["provider_id", "chapter_path"],
)
data class ReadChapterEntity(
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "chapter_path")
    val chapterPath: String,
    @ColumnInfo(name = "read_order")
    val readOrder: Long = 0L,
)

@Entity(
    tableName = "chapter_progress",
    primaryKeys = ["provider_id", "chapter_path"],
)
data class ChapterProgressEntity(
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "chapter_path")
    val chapterPath: String,
    @ColumnInfo(name = "page_index")
    val pageIndex: Int = 0,
)

@Entity(
    tableName = "chapter_page_counts",
    primaryKeys = ["provider_id", "chapter_path"],
)
data class ChapterPageCountEntity(
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "chapter_path")
    val chapterPath: String,
    @ColumnInfo(name = "page_count")
    val pageCount: Int = 0,
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @androidx.room.PrimaryKey
    val id: Int = 0,
    @ColumnInfo(name = "selected_provider_id")
    val selectedProviderId: String = "",
    @ColumnInfo(name = "use_dark_theme")
    val useDarkTheme: Boolean = false,
    @ColumnInfo(name = "auto_jump_to_unread")
    val autoJumpToUnread: Boolean = true,
    @ColumnInfo(name = "mangaball_adult_content_enabled")
    val mangaBallAdultContentEnabled: Boolean = false,
    @ColumnInfo(name = "app_language")
    val appLanguage: String = "EN",
    @ColumnInfo(name = "has_seen_provider_picker")
    val hasSeenProviderPicker: Boolean = false,
    @ColumnInfo(name = "legacy_prefs_migrated")
    val legacyPrefsMigrated: Boolean = false,
)

@Entity(
    tableName = "manga_detail_cache",
    primaryKeys = ["provider_id", "detail_key"],
)
data class MangaDetailCacheEntity(
    @ColumnInfo(name = "provider_id")
    val providerId: String,
    @ColumnInfo(name = "detail_key")
    val detailKey: String,
    @ColumnInfo(name = "detail_path")
    val detailPath: String,
    @ColumnInfo(name = "detail_json")
    val detailJson: String,
    @ColumnInfo(name = "chapter_count")
    val chapterCount: Int,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
