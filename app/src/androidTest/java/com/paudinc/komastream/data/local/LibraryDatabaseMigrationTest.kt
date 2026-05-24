package com.paudinc.komastream.data.local

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "library-migration-test.db"
    private val databaseFile = context.getDatabasePath(databaseName)

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
        if (databaseFile.exists()) {
            databaseFile.delete()
        }
    }

    @Test
    fun migrate10To11_preservesSettingsAndFavorites() {
        createVersion10Database()

        val database = Room.databaseBuilder(context, LibraryDatabase::class.java, databaseName)
            .addMigrations(LibraryDatabase.MIGRATION_10_11)
            .build()

        val dao = database.libraryDao()
        val settings = requireNotNull(dao.readSettings())
        val favorites = dao.readFavorites()
        database.close()

        assertEquals("mangadotnet-en", settings.selectedProviderId)
        assertTrue(settings.useDarkTheme)
        assertTrue(settings.adultContentEnabled)
        assertFalse(settings.adultOnlyProvidersEnabled)
        assertEquals("""["disabled-provider"]""", settings.disabledProviderIdsJson)
        assertEquals(1, favorites.size)
        assertEquals("/series/test", favorites.first().detailPath)
    }

    private fun createVersion10Database() {
        if (databaseFile.exists()) {
            databaseFile.delete()
        }
        val configuration = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    createVersion10Schema(db)
                    seedVersion10Data(db)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        helper.writableDatabase.close()
        helper.close()
    }

    private fun createVersion10Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorite_manga (
                provider_id TEXT NOT NULL,
                detail_path TEXT NOT NULL COLLATE NOCASE,
                title TEXT NOT NULL,
                cover_url TEXT NOT NULL,
                favorite_status TEXT NOT NULL,
                last_chapter_title TEXT NOT NULL,
                last_chapter_path TEXT NOT NULL,
                last_progress_chapter_number REAL,
                mal_manga_id INTEGER,
                last_read_chapter_number INTEGER,
                order_index INTEGER NOT NULL,
                PRIMARY KEY(provider_id, detail_path)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reading_manga (
                provider_id TEXT NOT NULL,
                detail_path TEXT NOT NULL COLLATE NOCASE,
                title TEXT NOT NULL,
                cover_url TEXT NOT NULL,
                last_chapter_title TEXT NOT NULL,
                last_chapter_path TEXT NOT NULL,
                last_progress_chapter_number REAL,
                mal_manga_id INTEGER,
                last_read_chapter_number INTEGER,
                order_index INTEGER NOT NULL,
                PRIMARY KEY(provider_id, detail_path)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS read_chapters (
                provider_id TEXT NOT NULL,
                chapter_path TEXT NOT NULL,
                read_order INTEGER NOT NULL,
                PRIMARY KEY(provider_id, chapter_path)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapter_progress (
                provider_id TEXT NOT NULL,
                chapter_path TEXT NOT NULL,
                page_index INTEGER NOT NULL,
                PRIMARY KEY(provider_id, chapter_path)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapter_page_counts (
                provider_id TEXT NOT NULL,
                chapter_path TEXT NOT NULL,
                page_count INTEGER NOT NULL,
                PRIMARY KEY(provider_id, chapter_path)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_settings (
                id INTEGER PRIMARY KEY NOT NULL,
                selected_provider_id TEXT NOT NULL,
                use_dark_theme INTEGER NOT NULL,
                auto_jump_to_unread INTEGER NOT NULL,
                adult_content_enabled INTEGER NOT NULL,
                adult_content_pin_hash TEXT NOT NULL,
                disabled_provider_ids_json TEXT NOT NULL,
                mangaball_adult_content_enabled INTEGER NOT NULL,
                manhwa_latino_adult_content_enabled INTEGER NOT NULL,
                app_language TEXT NOT NULL,
                preferred_chapter_language TEXT NOT NULL,
                has_seen_provider_picker INTEGER NOT NULL,
                legacy_prefs_migrated INTEGER NOT NULL,
                favorite_status_backfill_done INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS manga_detail_cache (
                provider_id TEXT NOT NULL,
                detail_key TEXT NOT NULL,
                detail_path TEXT NOT NULL COLLATE NOCASE,
                detail_json TEXT NOT NULL,
                chapter_count INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(provider_id, detail_key)
            )
            """.trimIndent()
        )
    }

    private fun seedVersion10Data(db: SupportSQLiteDatabase) {
        db.insert(
            "app_settings",
            0,
            ContentValues().apply {
                put("id", 0)
                put("selected_provider_id", "mangadotnet-en")
                put("use_dark_theme", 1)
                put("auto_jump_to_unread", 1)
                put("adult_content_enabled", 1)
                put("adult_content_pin_hash", "pin-hash")
                put("disabled_provider_ids_json", """["disabled-provider"]""")
                put("mangaball_adult_content_enabled", 1)
                put("manhwa_latino_adult_content_enabled", 1)
                put("app_language", "EN")
                put("preferred_chapter_language", "EN")
                put("has_seen_provider_picker", 1)
                put("legacy_prefs_migrated", 1)
                put("favorite_status_backfill_done", 1)
            }
        )
        db.insert(
            "favorite_manga",
            0,
            ContentValues().apply {
                put("provider_id", "mangadotnet-en")
                put("detail_path", "/series/test")
                put("title", "Test Series")
                put("cover_url", "https://example.com/cover.jpg")
                put("favorite_status", "READING")
                put("last_chapter_title", "Chapter 1")
                put("last_chapter_path", "/series/test/chapter-1")
                put("last_progress_chapter_number", 1.0)
                put("mal_manga_id", 123L)
                put("last_read_chapter_number", 1)
                put("order_index", 1L)
            }
        )
    }
}
