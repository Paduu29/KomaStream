package com.paudinc.komastream.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LibraryDao {
    @Query("SELECT * FROM favorite_manga ORDER BY order_index DESC")
    suspend fun readFavorites(): List<FavoriteMangaEntity>

    @Query("SELECT * FROM favorite_manga WHERE provider_id = :providerId ORDER BY order_index DESC")
    suspend fun readFavoritesForProvider(providerId: String): List<FavoriteMangaEntity>

    @Query("SELECT * FROM favorite_manga WHERE provider_id = :providerId AND detail_path = :detailPath LIMIT 1")
    suspend fun readFavorite(providerId: String, detailPath: String): FavoriteMangaEntity?

    @Query("SELECT * FROM favorite_manga WHERE mal_manga_id = :malMangaId")
    suspend fun readFavoritesByMalId(malMangaId: Long): List<FavoriteMangaEntity>

    @Query("SELECT * FROM favorite_manga WHERE manga_baka_id = :mangaBakaId")
    suspend fun readFavoritesByMangaBakaId(mangaBakaId: Long): List<FavoriteMangaEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_manga WHERE provider_id = :providerId AND detail_path = :detailPath LIMIT 1)")
    suspend fun hasFavorite(providerId: String, detailPath: String): Boolean

    @Query("SELECT MAX(order_index) FROM favorite_manga")
    suspend fun readMaxFavoriteOrder(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(entity: FavoriteMangaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorites(entities: List<FavoriteMangaEntity>)

    @Query("DELETE FROM favorite_manga WHERE provider_id = :providerId AND detail_path = :detailPath")
    suspend fun deleteFavorite(providerId: String, detailPath: String)

    @Query("DELETE FROM favorite_manga")
    suspend fun clearFavorites()

    @Query("SELECT * FROM reading_manga ORDER BY order_index DESC")
    suspend fun readReading(): List<ReadingMangaEntity>

    @Query("SELECT * FROM reading_manga WHERE provider_id = :providerId ORDER BY order_index DESC")
    suspend fun readReadingForProvider(providerId: String): List<ReadingMangaEntity>

    @Query("SELECT * FROM reading_manga WHERE provider_id = :providerId AND detail_path = :detailPath LIMIT 1")
    suspend fun readReading(providerId: String, detailPath: String): ReadingMangaEntity?

    @Query("SELECT * FROM reading_manga WHERE mal_manga_id = :malMangaId")
    suspend fun readReadingByMalId(malMangaId: Long): List<ReadingMangaEntity>

    @Query("SELECT * FROM reading_manga WHERE manga_baka_id = :mangaBakaId")
    suspend fun readReadingByMangaBakaId(mangaBakaId: Long): List<ReadingMangaEntity>

    @Query("SELECT MAX(order_index) FROM reading_manga")
    suspend fun readMaxReadingOrder(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReading(entity: ReadingMangaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadings(entities: List<ReadingMangaEntity>)

    @Query("DELETE FROM reading_manga WHERE provider_id = :providerId AND detail_path = :detailPath")
    suspend fun deleteReading(providerId: String, detailPath: String)

    @Query("DELETE FROM reading_manga")
    suspend fun clearReading()

    @Query("SELECT * FROM read_chapters ORDER BY read_order DESC")
    suspend fun readChapters(): List<ReadChapterEntity>

    @Query("SELECT * FROM read_chapters WHERE provider_id = :providerId ORDER BY read_order DESC")
    suspend fun readChaptersForProvider(providerId: String): List<ReadChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadChapter(entity: ReadChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadChapters(entities: List<ReadChapterEntity>)

    @Query("DELETE FROM read_chapters WHERE provider_id = :providerId AND chapter_path = :chapterPath")
    suspend fun deleteReadChapter(providerId: String, chapterPath: String)

    @Query("DELETE FROM read_chapters WHERE provider_id = :providerId AND chapter_path IN (:chapterPaths)")
    suspend fun deleteReadChapters(providerId: String, chapterPaths: List<String>)

    @Query("DELETE FROM read_chapters WHERE provider_id = :providerId")
    suspend fun deleteReadChaptersForProvider(providerId: String)

    @Query("DELETE FROM read_chapters")
    suspend fun clearReadChapters()

    @Query("SELECT EXISTS(SELECT 1 FROM read_chapters WHERE provider_id = :providerId AND chapter_path = :chapterPath LIMIT 1)")
    suspend fun hasReadChapter(providerId: String, chapterPath: String): Boolean

    @Query("SELECT MAX(read_order) FROM read_chapters WHERE provider_id = :providerId")
    suspend fun readMaxReadOrderForProvider(providerId: String): Long?

    @Query("SELECT * FROM chapter_progress")
    suspend fun readChapterProgress(): List<ChapterProgressEntity>

    @Query("SELECT * FROM chapter_progress WHERE provider_id = :providerId AND chapter_path = :chapterPath LIMIT 1")
    suspend fun readChapterProgress(providerId: String, chapterPath: String): ChapterProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapterProgress(entity: ChapterProgressEntity)

    @Query("DELETE FROM chapter_progress WHERE provider_id = :providerId AND chapter_path = :chapterPath")
    suspend fun deleteChapterProgress(providerId: String, chapterPath: String)

    @Query("DELETE FROM chapter_progress")
    suspend fun clearChapterProgress()

    @Query("SELECT * FROM chapter_page_counts")
    suspend fun readChapterPageCounts(): List<ChapterPageCountEntity>

    @Query("SELECT * FROM chapter_page_counts WHERE provider_id = :providerId AND chapter_path = :chapterPath LIMIT 1")
    suspend fun readChapterPageCount(providerId: String, chapterPath: String): ChapterPageCountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapterPageCount(entity: ChapterPageCountEntity)

    @Query("DELETE FROM chapter_page_counts WHERE provider_id = :providerId AND chapter_path = :chapterPath")
    suspend fun deleteChapterPageCount(providerId: String, chapterPath: String)

    @Query("DELETE FROM chapter_page_counts")
    suspend fun clearChapterPageCounts()

    @Query("SELECT * FROM app_settings WHERE id = 0 LIMIT 1")
    suspend fun readSettings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(entity: AppSettingsEntity)

    @Query("DELETE FROM app_settings")
    suspend fun clearSettings()

    @Query("SELECT * FROM manga_detail_cache WHERE provider_id = :providerId AND detail_key = :detailKey LIMIT 1")
    suspend fun readMangaDetailCache(providerId: String, detailKey: String): MangaDetailCacheEntity?

    @Query("SELECT * FROM manga_detail_cache WHERE provider_id = :providerId AND detail_path = :detailPath LIMIT 1")
    suspend fun readMangaDetailCacheByPath(providerId: String, detailPath: String): MangaDetailCacheEntity?

    @Query("SELECT * FROM manga_detail_cache")
    suspend fun readMangaDetailCaches(): List<MangaDetailCacheEntity>

    @Query("DELETE FROM manga_detail_cache WHERE LENGTH(detail_json) > :maxPayloadSize")
    suspend fun deleteOversizedMangaDetailCaches(maxPayloadSize: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMangaDetailCache(entity: MangaDetailCacheEntity)

    @Query("DELETE FROM manga_detail_cache WHERE provider_id = :providerId AND detail_key = :detailKey")
    suspend fun deleteMangaDetailCache(providerId: String, detailKey: String)

    @Query("DELETE FROM manga_detail_cache WHERE provider_id = :providerId AND detail_path = :detailPath")
    suspend fun deleteMangaDetailCacheByPath(providerId: String, detailPath: String)

    @Query("DELETE FROM manga_detail_cache")
    suspend fun clearMangaDetailCache()
}
