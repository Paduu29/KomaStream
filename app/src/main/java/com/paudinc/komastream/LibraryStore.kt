package com.paudinc.komastream

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("manga_library", Context.MODE_PRIVATE)
    private val defaultProviderId = createDefaultProviderRegistry().defaultProvider().id

    fun read(): LibraryState {
        val selectedProviderId = selectedProviderId()
        val allFavorites = parseList(
            value = prefs.getString("favorites", "[]").orEmpty(),
            fallbackProviderId = selectedProviderId,
        )
        val allReading = parseList(
            value = prefs.getString("reading", "[]").orEmpty(),
            fallbackProviderId = selectedProviderId,
        )
        return LibraryState(
            favorites = allFavorites.filter { it.providerId == selectedProviderId },
            reading = allReading.filter { it.providerId == selectedProviderId },
            readChapters = parseReadChapters(
                value = prefs.getString("readChapters", "[]").orEmpty(),
                providerId = selectedProviderId,
            ),
            useDarkTheme = prefs.getBoolean("useDarkTheme", false),
            autoJumpToUnread = prefs.getBoolean("autoJumpToUnread", true),
            selectedProviderId = selectedProviderId,
            appLanguage = AppLanguage.valueOf(prefs.getString("appLanguage", AppLanguage.EN.name) ?: AppLanguage.EN.name),
        )
    }

    fun toggleFavorite(manga: SavedManga) {
        val current = parseList(prefs.getString("favorites", "[]").orEmpty(), fallbackProviderId = selectedProviderId()).toMutableList()
        val existingIndex = current.indexOfFirst { it.providerId == manga.providerId && it.detailPath == manga.detailPath }
        if (existingIndex >= 0) current.removeAt(existingIndex) else current.add(0, manga)
        prefs.edit().putString("favorites", serialize(current)).apply()
    }

    fun removeFavorite(providerId: String, detailPath: String) {
        val current = parseList(prefs.getString("favorites", "[]").orEmpty(), fallbackProviderId = selectedProviderId())
            .filterNot { it.providerId == providerId && it.detailPath == detailPath }
        prefs.edit().putString("favorites", serialize(current)).apply()
    }

    fun upsertReading(manga: SavedManga) {
        val current = parseList(prefs.getString("reading", "[]").orEmpty(), fallbackProviderId = selectedProviderId()).toMutableList()
        current.removeAll { it.providerId == manga.providerId && it.detailPath == manga.detailPath }
        current.add(0, manga)
        prefs.edit().putString("reading", serialize(current.take(20))).apply()
    }

    fun removeReading(providerId: String, detailPath: String) {
        val current = parseList(prefs.getString("reading", "[]").orEmpty(), fallbackProviderId = selectedProviderId())
            .filterNot { it.providerId == providerId && it.detailPath == detailPath }
        prefs.edit().putString("reading", serialize(current)).apply()
    }

    fun isFavorite(providerId: String, detailPath: String): Boolean {
        return parseList(prefs.getString("favorites", "[]").orEmpty(), fallbackProviderId = selectedProviderId())
            .any { it.providerId == providerId && it.detailPath == detailPath }
    }

    fun markChapterRead(providerId: String, chapterPath: String) {
        if (chapterPath.isBlank()) return
        val qualifiedPath = qualify(providerId, chapterPath)
        val current = parseRawReadChapters(prefs.getString("readChapters", "[]").orEmpty()).toMutableList()
        current.remove(qualifiedPath)
        current.add(0, qualifiedPath)
        prefs.edit().putString("readChapters", serializeReadChapters(current.take(3000))).apply()
    }

    fun toggleChapterRead(providerId: String, chapterPath: String) {
        if (chapterPath.isBlank()) return
        val qualifiedPath = qualify(providerId, chapterPath)
        val current = parseRawReadChapters(prefs.getString("readChapters", "[]").orEmpty()).toMutableList()
        if (current.remove(qualifiedPath).not()) {
            current.add(0, qualifiedPath)
        }
        prefs.edit().putString("readChapters", serializeReadChapters(current.take(3000))).apply()
    }

    fun isChapterRead(providerId: String, chapterPath: String): Boolean {
        return parseRawReadChapters(prefs.getString("readChapters", "[]").orEmpty()).contains(qualify(providerId, chapterPath))
    }

    fun setChaptersRead(providerId: String, chapterPaths: Collection<String>, read: Boolean) {
        val normalized = chapterPaths.filter { it.isNotBlank() }
        if (normalized.isEmpty()) return
        val normalizedQualified = normalized.map { qualify(providerId, it) }
        val current = parseRawReadChapters(prefs.getString("readChapters", "[]").orEmpty()).toMutableList()
        if (read) {
            normalizedQualified.forEach { path ->
                current.remove(path)
                current.add(0, path)
            }
        } else {
            current.removeAll(normalizedQualified.toSet())
        }
        prefs.edit().putString("readChapters", serializeReadChapters(current.take(3000))).apply()
    }

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean("useDarkTheme", enabled).apply()
    }

    fun setAutoJumpToUnread(enabled: Boolean) {
        prefs.edit().putBoolean("autoJumpToUnread", enabled).apply()
    }

    fun setAppLanguage(language: AppLanguage) {
        prefs.edit().putString("appLanguage", language.name).apply()
    }

    fun selectedProviderId(): String =
        prefs.getString("selectedProviderId", defaultProviderId) ?: defaultProviderId

    fun setSelectedProviderId(providerId: String) {
        prefs.edit().putString("selectedProviderId", providerId).apply()
    }

    fun exportBackup(): String {
        return JSONObject()
            .put("favorites", JSONArray(prefs.getString("favorites", "[]").orEmpty()))
            .put("reading", JSONArray(prefs.getString("reading", "[]").orEmpty()))
            .put("readChapters", JSONArray(prefs.getString("readChapters", "[]").orEmpty()))
            .put("readProgress", JSONObject(prefs.getString("readProgress", "{}").orEmpty()))
            .put("selectedProviderId", selectedProviderId())
            .toString()
    }

    fun importBackup(payload: String) {
        val json = JSONObject(payload)
        prefs.edit()
            .putString("favorites", json.optJSONArray("favorites")?.toString() ?: "[]")
            .putString("reading", json.optJSONArray("reading")?.toString() ?: "[]")
            .putString("readChapters", json.optJSONArray("readChapters")?.toString() ?: "[]")
            .putString("readProgress", json.optJSONObject("readProgress")?.toString() ?: "{}")
            .putString("selectedProviderId", json.optString("selectedProviderId").ifBlank { defaultProviderId })
            .apply()
    }

    fun saveChapterProgress(providerId: String, chapterPath: String, pageIndex: Int) {
        if (chapterPath.isBlank()) return
        val json = JSONObject(prefs.getString("readProgress", "{}").orEmpty())
        json.put(qualify(providerId, chapterPath), pageIndex.coerceAtLeast(0))
        prefs.edit().putString("readProgress", json.toString()).apply()
    }

    fun getChapterProgress(providerId: String, chapterPath: String): Int {
        if (chapterPath.isBlank()) return 0
        val json = JSONObject(prefs.getString("readProgress", "{}").orEmpty())
        return json.optInt(qualify(providerId, chapterPath), 0).coerceAtLeast(0)
    }

    private fun parseList(value: String, fallbackProviderId: String): List<SavedManga> {
        val json = JSONArray(value)
        return buildList(json.length()) {
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                add(
                    SavedManga(
                        providerId = item.optString("providerId").ifBlank { fallbackProviderId },
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
                    .put("providerId", item.providerId)
                    .put("title", item.title)
                    .put("detailPath", item.detailPath)
                    .put("coverUrl", item.coverUrl)
                    .put("lastChapterTitle", item.lastChapterTitle)
                    .put("lastChapterPath", item.lastChapterPath)
            )
        }
        return json.toString()
    }

    private fun parseReadChapters(value: String, providerId: String): Set<String> {
        return parseRawReadChapters(value)
            .mapNotNull { unqualify(providerId, it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun parseRawReadChapters(value: String): Set<String> {
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

    private fun qualify(providerId: String, value: String): String = "$providerId::$value"

    private fun unqualify(providerId: String, value: String): String? {
        val prefix = "$providerId::"
        return when {
            value.startsWith(prefix) -> value.removePrefix(prefix)
            "::" !in value && providerId == defaultProviderId -> value
            else -> null
        }
    }
}
