package com.paudinc.komastream.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FavoriteMangaEntity::class,
        ReadingMangaEntity::class,
        ReadChapterEntity::class,
        ChapterProgressEntity::class,
        ChapterPageCountEntity::class,
        AppSettingsEntity::class,
        MangaDetailCacheEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile
        private var INSTANCE: LibraryDatabase? = null

        fun getInstance(context: Context): LibraryDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    "komastream_library.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .allowMainThreadQueries()
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_manga ADD COLUMN last_read_chapter_number INTEGER")
                db.execSQL("ALTER TABLE reading_manga ADD COLUMN last_read_chapter_number INTEGER")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE reading_manga_new (
                provider_id TEXT NOT NULL,
                detail_path TEXT NOT NULL COLLATE NOCASE,
                title TEXT NOT NULL,
                cover_url TEXT NOT NULL,
                last_chapter_title TEXT NOT NULL,
                last_chapter_path TEXT NOT NULL,
                mal_manga_id INTEGER,
                last_read_chapter_number INTEGER,
                order_index INTEGER NOT NULL,
                PRIMARY KEY(provider_id, detail_path)
            )
        """)
                db.execSQL("""
            INSERT OR IGNORE INTO reading_manga_new
            SELECT 
                provider_id,
                LOWER(detail_path),
                title,
                cover_url,
                last_chapter_title,
                last_chapter_path,
                mal_manga_id,
                last_read_chapter_number,
                order_index
            FROM reading_manga
        """)

                db.execSQL("DROP TABLE reading_manga")
                db.execSQL("""
            ALTER TABLE reading_manga_new RENAME TO reading_manga
        """)
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE favorite_manga_new (
                provider_id TEXT NOT NULL,
                detail_path TEXT NOT NULL COLLATE NOCASE,
                title TEXT NOT NULL,
                cover_url TEXT NOT NULL,
                last_chapter_title TEXT NOT NULL,
                last_chapter_path TEXT NOT NULL,
                mal_manga_id INTEGER,
                last_read_chapter_number INTEGER,
                order_index INTEGER NOT NULL,
                PRIMARY KEY(provider_id, detail_path)
            )
        """)
                db.execSQL("""
            INSERT OR REPLACE INTO favorite_manga_new
            SELECT
                provider_id,
                LOWER(detail_path),
                title,
                cover_url,
                last_chapter_title,
                LOWER(last_chapter_path),
                mal_manga_id,
                last_read_chapter_number,
                order_index
            FROM favorite_manga
            ORDER BY order_index ASC
        """)
                db.execSQL("DROP TABLE favorite_manga")
                db.execSQL("ALTER TABLE favorite_manga_new RENAME TO favorite_manga")

                db.execSQL("""
            CREATE TABLE reading_manga_new (
                provider_id TEXT NOT NULL,
                detail_path TEXT NOT NULL COLLATE NOCASE,
                title TEXT NOT NULL,
                cover_url TEXT NOT NULL,
                last_chapter_title TEXT NOT NULL,
                last_chapter_path TEXT NOT NULL,
                mal_manga_id INTEGER,
                last_read_chapter_number INTEGER,
                order_index INTEGER NOT NULL,
                PRIMARY KEY(provider_id, detail_path)
            )
        """)
                db.execSQL("""
            INSERT OR REPLACE INTO reading_manga_new
            SELECT
                provider_id,
                LOWER(detail_path),
                title,
                cover_url,
                last_chapter_title,
                LOWER(last_chapter_path),
                mal_manga_id,
                last_read_chapter_number,
                order_index
            FROM reading_manga
            ORDER BY order_index ASC
        """)
                db.execSQL("DROP TABLE reading_manga")
                db.execSQL("ALTER TABLE reading_manga_new RENAME TO reading_manga")

                db.execSQL("""
            CREATE TABLE read_chapters_new (
                provider_id TEXT NOT NULL,
                chapter_path TEXT NOT NULL,
                read_order INTEGER NOT NULL,
                PRIMARY KEY(provider_id, chapter_path)
            )
        """)
                db.execSQL("""
            INSERT OR REPLACE INTO read_chapters_new
            SELECT
                provider_id,
                LOWER(chapter_path),
                read_order
            FROM read_chapters
            ORDER BY read_order ASC
        """)
                db.execSQL("DROP TABLE read_chapters")
                db.execSQL("ALTER TABLE read_chapters_new RENAME TO read_chapters")

                db.execSQL("""
            CREATE TABLE chapter_progress_new (
                provider_id TEXT NOT NULL,
                chapter_path TEXT NOT NULL,
                page_index INTEGER NOT NULL,
                PRIMARY KEY(provider_id, chapter_path)
            )
        """)
                db.execSQL("""
            INSERT OR REPLACE INTO chapter_progress_new
            SELECT
                provider_id,
                LOWER(chapter_path),
                page_index
            FROM chapter_progress
            ORDER BY rowid ASC
        """)
                db.execSQL("DROP TABLE chapter_progress")
                db.execSQL("ALTER TABLE chapter_progress_new RENAME TO chapter_progress")

                db.execSQL("""
            CREATE TABLE chapter_page_counts_new (
                provider_id TEXT NOT NULL,
                chapter_path TEXT NOT NULL,
                page_count INTEGER NOT NULL,
                PRIMARY KEY(provider_id, chapter_path)
            )
        """)
                db.execSQL("""
            INSERT OR REPLACE INTO chapter_page_counts_new
            SELECT
                provider_id,
                LOWER(chapter_path),
                page_count
            FROM chapter_page_counts
            ORDER BY rowid ASC
        """)
                db.execSQL("DROP TABLE chapter_page_counts")
                db.execSQL("ALTER TABLE chapter_page_counts_new RENAME TO chapter_page_counts")

                db.execSQL("""
            CREATE TABLE manga_detail_cache_new (
                provider_id TEXT NOT NULL,
                detail_key TEXT NOT NULL,
                detail_path TEXT NOT NULL COLLATE NOCASE,
                detail_json TEXT NOT NULL,
                chapter_count INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(provider_id, detail_key)
            )
        """)
                db.execSQL("""
            INSERT OR REPLACE INTO manga_detail_cache_new
            SELECT
                provider_id,
                provider_id || '::' || LOWER(detail_path),
                LOWER(detail_path),
                detail_json,
                chapter_count,
                updated_at
            FROM manga_detail_cache
            ORDER BY updated_at ASC
        """)
                db.execSQL("DROP TABLE manga_detail_cache")
                db.execSQL("ALTER TABLE manga_detail_cache_new RENAME TO manga_detail_cache")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorite_manga ADD COLUMN last_progress_chapter_number REAL")
                db.execSQL("ALTER TABLE reading_manga ADD COLUMN last_progress_chapter_number REAL")
            }
        }

    }
}
