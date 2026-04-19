package com.paudinc.mangascraper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("manga_library", Context.MODE_PRIVATE)

    fun read(): LibraryState {
        return LibraryState(
            favorites = parseList(prefs.getString("favorites", "[]").orEmpty()),
            reading = parseList(prefs.getString("reading", "[]").orEmpty()),
            readChapters = parseReadChapters(prefs.getString("readChapters", "[]").orEmpty()),
            useDarkTheme = prefs.getBoolean("useDarkTheme", false),
            appLanguage = AppLanguage.valueOf(prefs.getString("appLanguage", AppLanguage.EN.name) ?: AppLanguage.EN.name),
        )
    }

    fun toggleFavorite(manga: SavedManga) {
        val current = read().favorites.toMutableList()
        val existingIndex = current.indexOfFirst { it.detailPath == manga.detailPath }
        if (existingIndex >= 0) current.removeAt(existingIndex) else current.add(0, manga)
        prefs.edit().putString("favorites", serialize(current)).apply()
    }

    fun upsertReading(manga: SavedManga) {
        val current = read().reading.toMutableList()
        current.removeAll { it.detailPath == manga.detailPath }
        current.add(0, manga)
        prefs.edit().putString("reading", serialize(current.take(20))).apply()
    }

    fun isFavorite(detailPath: String): Boolean {
        return read().favorites.any { it.detailPath == detailPath }
    }

    fun markChapterRead(chapterPath: String) {
        if (chapterPath.isBlank()) return
        val current = read().readChapters.toMutableList()
        current.remove(chapterPath)
        current.add(0, chapterPath)
        prefs.edit().putString("readChapters", serializeReadChapters(current.take(3000))).apply()
    }

    fun toggleChapterRead(chapterPath: String) {
        if (chapterPath.isBlank()) return
        val current = read().readChapters.toMutableList()
        if (current.remove(chapterPath).not()) {
            current.add(0, chapterPath)
        }
        prefs.edit().putString("readChapters", serializeReadChapters(current.take(3000))).apply()
    }

    fun isChapterRead(chapterPath: String): Boolean {
        return read().readChapters.contains(chapterPath)
    }

    fun setChaptersRead(chapterPaths: Collection<String>, read: Boolean) {
        val normalized = chapterPaths.filter { it.isNotBlank() }
        if (normalized.isEmpty()) return
        val current = read().readChapters.toMutableList()
        if (read) {
            normalized.forEach { path ->
                current.remove(path)
                current.add(0, path)
            }
        } else {
            current.removeAll(normalized.toSet())
        }
        prefs.edit().putString("readChapters", serializeReadChapters(current.take(3000))).apply()
    }

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean("useDarkTheme", enabled).apply()
    }

    fun setAppLanguage(language: AppLanguage) {
        prefs.edit().putString("appLanguage", language.name).apply()
    }

    fun exportBackup(): String {
        return JSONObject()
            .put("favorites", JSONArray(prefs.getString("favorites", "[]").orEmpty()))
            .put("reading", JSONArray(prefs.getString("reading", "[]").orEmpty()))
            .put("readChapters", JSONArray(prefs.getString("readChapters", "[]").orEmpty()))
            .put("readProgress", JSONObject(prefs.getString("readProgress", "{}").orEmpty()))
            .toString()
    }

    fun importBackup(payload: String) {
        val json = JSONObject(payload)
        prefs.edit()
            .putString("favorites", json.optJSONArray("favorites")?.toString() ?: "[]")
            .putString("reading", json.optJSONArray("reading")?.toString() ?: "[]")
            .putString("readChapters", json.optJSONArray("readChapters")?.toString() ?: "[]")
            .putString("readProgress", json.optJSONObject("readProgress")?.toString() ?: "{}")
            .apply()
    }

    fun saveChapterProgress(chapterPath: String, pageIndex: Int) {
        if (chapterPath.isBlank()) return
        val json = JSONObject(prefs.getString("readProgress", "{}").orEmpty())
        json.put(chapterPath, pageIndex.coerceAtLeast(0))
        prefs.edit().putString("readProgress", json.toString()).apply()
    }

    fun getChapterProgress(chapterPath: String): Int {
        if (chapterPath.isBlank()) return 0
        val json = JSONObject(prefs.getString("readProgress", "{}").orEmpty())
        return json.optInt(chapterPath, 0).coerceAtLeast(0)
    }

    private fun parseList(value: String): List<SavedManga> {
        val json = JSONArray(value)
        return buildList(json.length()) {
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                add(
                    SavedManga(
                        title = item.optString("title"),
                        detailPath = item.optString("detailPath"),
                        coverUrl = item.optString("coverUrl"),
                        lastChapterTitle = item.optString("lastChapterTitle"),
                        lastChapterPath = item.optString("lastChapterPath"),
                    )
                )
            }
        }
    }

    private fun serialize(items: List<SavedManga>): String {
        val json = JSONArray()
        items.forEach { item ->
            json.put(
                JSONObject()
                    .put("title", item.title)
                    .put("detailPath", item.detailPath)
                    .put("coverUrl", item.coverUrl)
                    .put("lastChapterTitle", item.lastChapterTitle)
                    .put("lastChapterPath", item.lastChapterPath)
            )
        }
        return json.toString()
    }

    private fun parseReadChapters(value: String): Set<String> {
        val json = JSONArray(value)
        return buildSet(json.length()) {
            for (index in 0 until json.length()) {
                add(json.optString(index))
            }
        }.filter { it.isNotBlank() }.toSet()
    }

    private fun serializeReadChapters(items: List<String>): String {
        val json = JSONArray()
        items.forEach { item -> json.put(item) }
        return json.toString()
    }
}
