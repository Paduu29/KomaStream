package com.paudinc.komastream.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LibraryDao {
    @Query("SELECT * FROM favorite_manga ORDER BY order_index DESC")
    fun readFavorites(): List<FavoriteMangaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertFavorite(entity: FavoriteMangaEntity)

    @Query("DELETE FROM favorite_manga WHERE provider_id = :providerId AND detail_path = :detailPath")
    fun deleteFavorite(providerId: String, detailPath: String)

    @Query("DELETE FROM favorite_manga")
    fun clearFavorites()

    @Query("SELECT * FROM reading_manga ORDER BY order_index DESC")
    fun readReading(): List<ReadingMangaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertReading(entity: ReadingMangaEntity)

    @Query("DELETE FROM reading_manga WHERE provider_id = :providerId AND detail_path = :detailPath")
    fun deleteReading(providerId: String, detailPath: String)

    @Query("DELETE FROM reading_manga")
    fun clearReading()

    @Query("SELECT * FROM read_chapters ORDER BY read_order DESC")
    fun readChapters(): List<ReadChapterEntity>

    @Query("SELECT * FROM read_chapters WHERE provider_id = :providerId ORDER BY read_order DESC")
    fun readChaptersForProvider(providerId: String): List<ReadChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertReadChapter(entity: ReadChapterEntity)

    @Query("DELETE FROM read_chapters WHERE provider_id = :providerId AND chapter_path = :chapterPath")
    fun deleteReadChapter(providerId: String, chapterPath: String)

    @Query("DELETE FROM read_chapters WHERE provider_id = :providerId")
    fun deleteReadChaptersForProvider(providerId: String)

    @Query("DELETE FROM read_chapters")
    fun clearReadChapters()

    @Query("SELECT * FROM chapter_progress")
    fun readChapterProgress(): List<ChapterProgressEntity>

    @Query("SELECT * FROM chapter_progress WHERE provider_id = :providerId AND chapter_path = :chapterPath LIMIT 1")
    fun readChapterProgress(providerId: String, chapterPath: String): ChapterProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertChapterProgress(entity: ChapterProgressEntity)

    @Query("DELETE FROM chapter_progress WHERE provider_id = :providerId AND chapter_path = :chapterPath")
    fun deleteChapterProgress(providerId: String, chapterPath: String)

    @Query("DELETE FROM chapter_progress")
    fun clearChapterProgress()

    @Query("SELECT * FROM chapter_page_counts")
    fun readChapterPageCounts(): List<ChapterPageCountEntity>

    @Query("SELECT * FROM chapter_page_counts WHERE provider_id = :providerId AND chapter_path = :chapterPath LIMIT 1")
    fun readChapterPageCount(providerId: String, chapterPath: String): ChapterPageCountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertChapterPageCount(entity: ChapterPageCountEntity)

    @Query("DELETE FROM chapter_page_counts WHERE provider_id = :providerId AND chapter_path = :chapterPath")
    fun deleteChapterPageCount(providerId: String, chapterPath: String)

    @Query("DELETE FROM chapter_page_counts")
    fun clearChapterPageCounts()

    @Query("SELECT * FROM app_settings WHERE id = 0 LIMIT 1")
    fun readSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSettings(entity: AppSettingsEntity)

    @Query("DELETE FROM app_settings")
    fun clearSettings()

    @Query("SELECT * FROM manga_detail_cache WHERE provider_id = :providerId AND detail_key = :detailKey LIMIT 1")
    fun readMangaDetailCache(providerId: String, detailKey: String): MangaDetailCacheEntity?

    @Query("SELECT * FROM manga_detail_cache WHERE provider_id = :providerId AND detail_path = :detailPath LIMIT 1")
    fun readMangaDetailCacheByPath(providerId: String, detailPath: String): MangaDetailCacheEntity?

    @Query("SELECT * FROM manga_detail_cache")
    fun readMangaDetailCaches(): List<MangaDetailCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMangaDetailCache(entity: MangaDetailCacheEntity)

    @Query("DELETE FROM manga_detail_cache WHERE provider_id = :providerId AND detail_key = :detailKey")
    fun deleteMangaDetailCache(providerId: String, detailKey: String)

    @Query("DELETE FROM manga_detail_cache")
    fun clearMangaDetailCache()
}
