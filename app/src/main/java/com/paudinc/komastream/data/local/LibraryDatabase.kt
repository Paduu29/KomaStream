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
    version = 4,
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
                    .allowMainThreadQueries()
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE favorite_manga ADD COLUMN last_read_chapter_number INTEGER")
                database.execSQL("ALTER TABLE reading_manga ADD COLUMN last_read_chapter_number INTEGER")
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

    }
}
