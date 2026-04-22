package com.paudinc.komastream.utils

import android.content.Context
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.data.repository.LibraryBackupPayloadCodec
import com.paudinc.komastream.data.repository.LibraryJsonCodec
import org.json.JSONObject

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("manga_library", Context.MODE_PRIVATE)
    private val defaultProviderId = createDefaultProviderRegistry().defaultProvider().id
    private val jsonCodec = LibraryJsonCodec(defaultProviderId = defaultProviderId)
    private val backupPayloadCodec = LibraryBackupPayloadCodec()

    fun read(filterBySelectedProvider: Boolean = true): LibraryState {
        val selectedProviderId = selectedProviderId()
        val parsedFavorites = jsonCodec.parseSavedMangaList(
            value = prefs.getString("favorites", "[]").orEmpty(),
            fallbackProviderId = defaultProviderId,
        )
        val parsedReading = jsonCodec.parseSavedMangaList(
            value = prefs.getString("reading", "[]").orEmpty(),
            fallbackProviderId = defaultProviderId,
        )
        val (allFavorites, allReading) = canonicalizeSavedEntries(parsedFavorites, parsedReading)
        return LibraryState(
            favorites = if (filterBySelectedProvider) allFavorites.filter { it.providerId == selectedProviderId } else allFavorites,
            reading = if (filterBySelectedProvider) allReading.filter { it.providerId == selectedProviderId } else allReading,
            readChapters = jsonCodec.parseReadChapters(
                value = prefs.getString("readChapters", "[]").orEmpty(),
                providerId = selectedProviderId,
            ),
            useDarkTheme = prefs.getBoolean("useDarkTheme", false),
            autoJumpToUnread = prefs.getBoolean("autoJumpToUnread", true),
            mangaBallAdultContentEnabled = prefs.getBoolean(KEY_MANGABALL_ADULT_CONTENT, false),
            selectedProviderId = selectedProviderId,
            appLanguage = AppLanguage.valueOf(
                prefs.getString("appLanguage", AppLanguage.EN.name) ?: AppLanguage.EN.name
            ),
        )
    }

    fun toggleFavorite(manga: SavedManga) {
        val current = jsonCodec.parseSavedMangaList(
            prefs.getString("favorites", "[]").orEmpty(),
            fallbackProviderId = defaultProviderId,
        ).toMutableList()
        val existing = current.any { sameStoredManga(it, manga) }
        current.removeAll { sameStoredManga(it, manga) }
        if (!existing) current.add(0, manga)
        prefs.edit().putString("favorites", jsonCodec.serializeSavedMangaList(current)).apply()
    }

    fun removeFavorite(providerId: String, detailPath: String) {
        val target = SavedManga(providerId, "", detailPath, "")
        val current = jsonCodec.parseSavedMangaList(
            prefs.getString("favorites", "[]").orEmpty(),
            fallbackProviderId = defaultProviderId,
        )
            .filterNot { sameStoredManga(it, target) }
        prefs.edit().putString("favorites", jsonCodec.serializeSavedMangaList(current)).apply()
    }

    fun upsertReading(manga: SavedManga) {
        val current = jsonCodec.parseSavedMangaList(
            prefs.getString("reading", "[]").orEmpty(),
            fallbackProviderId = defaultProviderId,
        ).toMutableList()
        val existingReading = current.firstOrNull { sameStoredManga(it, manga) }
        val mergedReading = manga.copy(
            title = manga.title.ifBlank { existingReading?.title.orEmpty() },
            coverUrl = manga.coverUrl.ifBlank { existingReading?.coverUrl.orEmpty() },
            lastChapterTitle = manga.lastChapterTitle.ifBlank { existingReading?.lastChapterTitle.orEmpty() },
            lastChapterPath = manga.lastChapterPath.ifBlank { existingReading?.lastChapterPath.orEmpty() },
        )
        current.removeAll { sameStoredManga(it, mergedReading) }
        current.add(0, mergedReading)

        val favorites = jsonCodec.parseSavedMangaList(
            prefs.getString("favorites", "[]").orEmpty(),
            fallbackProviderId = defaultProviderId,
        ).map { saved ->
            if (sameStoredManga(saved, mergedReading)) {
                saved.copy(
                    title = saved.title.ifBlank { mergedReading.title },
                    coverUrl = saved.coverUrl.ifBlank { mergedReading.coverUrl },
                    detailPath = preferCanonicalDetailPath(saved, mergedReading),
                    lastChapterTitle = mergedReading.lastChapterTitle.ifBlank { saved.lastChapterTitle },
                    lastChapterPath = mergedReading.lastChapterPath.ifBlank { saved.lastChapterPath },
                )
            } else {
                saved
            }
        }
        val (canonicalFavorites, canonicalReading) = canonicalizeSavedEntries(favorites, current)

        prefs.edit()
            .putString("reading", jsonCodec.serializeSavedMangaList(canonicalReading.take(20)))
            .putString("favorites", jsonCodec.serializeSavedMangaList(canonicalFavorites))
            .apply()
    }

    fun removeReading(providerId: String, detailPath: String) {
        val target = SavedManga(providerId, "", detailPath, "")
        val current = jsonCodec.parseSavedMangaList(
            prefs.getString("reading", "[]").orEmpty(),
            fallbackProviderId = defaultProviderId,
        )
            .filterNot { sameStoredManga(it, target) }
        prefs.edit().putString("reading", jsonCodec.serializeSavedMangaList(current)).apply()
    }

    fun isFavorite(providerId: String, detailPath: String): Boolean {
        val target = SavedManga(providerId, "", detailPath, "")
        return jsonCodec.parseSavedMangaList(
            prefs.getString("favorites", "[]").orEmpty(),
            fallbackProviderId = defaultProviderId,
        )
            .any { sameStoredManga(it, target) }
    }

    fun markChapterRead(providerId: String, chapterPath: String) {
        if (chapterPath.isBlank()) return
        val qualifiedPath = jsonCodec.qualify(providerId, chapterPath)
        val current = jsonCodec.parseRawReadChapters(prefs.getString("readChapters", "[]").orEmpty()).toMutableList()
        current.removeAll { sameStoredChapter(providerId, it, chapterPath) }
        current.add(0, qualifiedPath)
        prefs.edit().putString("readChapters", jsonCodec.serializeReadChapters(current.take(3000))).apply()
    }

    fun toggleChapterRead(providerId: String, chapterPath: String) {
        if (chapterPath.isBlank()) return
        val qualifiedPath = jsonCodec.qualify(providerId, chapterPath)
        val current = jsonCodec.parseRawReadChapters(prefs.getString("readChapters", "[]").orEmpty()).toMutableList()
        if (current.removeAll { sameStoredChapter(providerId, it, chapterPath) }.not()) {
            current.add(0, qualifiedPath)
        }
        prefs.edit().putString("readChapters", jsonCodec.serializeReadChapters(current.take(3000))).apply()
    }

    fun isChapterRead(providerId: String, chapterPath: String): Boolean {
        return jsonCodec.parseRawReadChapters(
            prefs.getString("readChapters", "[]").orEmpty()
        ).any { sameStoredChapter(providerId, it, chapterPath) }
    }

    fun setChaptersRead(providerId: String, chapterPaths: Collection<String>, read: Boolean) {
        val normalized = chapterPaths.filter { it.isNotBlank() }
        if (normalized.isEmpty()) return
        val normalizedQualified = normalized.map { jsonCodec.qualify(providerId, it) }
        val current = jsonCodec.parseRawReadChapters(prefs.getString("readChapters", "[]").orEmpty()).toMutableList()
        if (read) {
            normalized.forEachIndexed { index, chapterPath ->
                current.removeAll { sameStoredChapter(providerId, it, chapterPath) }
                current.add(0, normalizedQualified[index])
            }
        } else {
            current.removeAll { stored ->
                normalized.any { chapterPath -> sameStoredChapter(providerId, stored, chapterPath) }
            }
        }
        prefs.edit().putString("readChapters", jsonCodec.serializeReadChapters(current.take(3000))).apply()
    }

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit().putBoolean("useDarkTheme", enabled).apply()
    }

    fun setAutoJumpToUnread(enabled: Boolean) {
        prefs.edit().putBoolean("autoJumpToUnread", enabled).apply()
    }

    fun setMangaBallAdultContentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MANGABALL_ADULT_CONTENT, enabled).apply()
    }

    fun isMangaBallAdultContentEnabled(): Boolean =
        prefs.getBoolean(KEY_MANGABALL_ADULT_CONTENT, false)

    fun setAppLanguage(language: AppLanguage) {
        prefs.edit().putString("appLanguage", language.name).apply()
    }

    fun selectedProviderId(): String =
        prefs.getString("selectedProviderId", defaultProviderId) ?: defaultProviderId

    fun setSelectedProviderId(providerId: String) {
        prefs.edit().putString("selectedProviderId", providerId).apply()
    }

    fun hasSeenProviderPicker(): Boolean =
        prefs.getBoolean("hasSeenProviderPicker", false)

    fun setHasSeenProviderPicker(seen: Boolean) {
        prefs.edit().putBoolean("hasSeenProviderPicker", seen).apply()
    }

    fun exportBackup(): String {
        return backupPayloadCodec.exportPayload(
            favorites = prefs.getString("favorites", "[]").orEmpty(),
            reading = prefs.getString("reading", "[]").orEmpty(),
            readChapters = prefs.getString("readChapters", "[]").orEmpty(),
            readProgress = prefs.getString("readProgress", "{}").orEmpty(),
            chapterPageCounts = prefs.getString("chapterPageCounts", "{}").orEmpty(),
            selectedProviderId = selectedProviderId(),
        )
    }

    fun importBackup(
        payload: String,
        selectedProviderIdFallback: String = selectedProviderId(),
    ) {
        val importedPayload = backupPayloadCodec.importPayload(payload, selectedProviderIdFallback)
        prefs.edit()
            .putString("favorites", importedPayload.favorites)
            .putString("reading", importedPayload.reading)
            .putString("readChapters", importedPayload.readChapters)
            .putString("readProgress", importedPayload.readProgress)
            .putString("chapterPageCounts", importedPayload.chapterPageCounts)
            .putString("selectedProviderId", importedPayload.selectedProviderId)
            .apply()
    }

    fun saveChapterProgress(providerId: String, chapterPath: String, pageIndex: Int) {
        if (chapterPath.isBlank()) return
        val json = JSONObject(prefs.getString("readProgress", "{}").orEmpty())
        json.put(jsonCodec.qualify(providerId, chapterPath), pageIndex.coerceAtLeast(0))
        prefs.edit().putString("readProgress", json.toString()).apply()
    }

    fun getChapterProgress(providerId: String, chapterPath: String): Int {
        if (chapterPath.isBlank()) return 0
        val json = JSONObject(prefs.getString("readProgress", "{}").orEmpty())
        return json.optInt(jsonCodec.qualify(providerId, chapterPath), 0).coerceAtLeast(0)
    }

    fun saveChapterPageCount(providerId: String, chapterPath: String, pageCount: Int) {
        if (chapterPath.isBlank() || pageCount <= 0) return
        val json = JSONObject(prefs.getString("chapterPageCounts", "{}").orEmpty())
        json.put(jsonCodec.qualify(providerId, chapterPath), pageCount)
        prefs.edit().putString("chapterPageCounts", json.toString()).apply()
    }

    fun getChapterPageCount(providerId: String, chapterPath: String): Int {
        if (chapterPath.isBlank()) return 0
        val json = JSONObject(prefs.getString("chapterPageCounts", "{}").orEmpty())
        return json.optInt(jsonCodec.qualify(providerId, chapterPath), 0).coerceAtLeast(0)
    }

    private fun canonicalizeSavedEntries(
        favorites: List<SavedManga>,
        reading: List<SavedManga>,
    ): Pair<List<SavedManga>, List<SavedManga>> {
        val canonicalByKey = (favorites + reading)
            .groupBy { mangaKey(it.providerId, it.detailPath) }
            .mapValues { (_, items) ->
                items.maxWithOrNull(
                    compareBy<SavedManga> { detailPathScore(it.providerId, it.detailPath) }
                        .thenBy { it.detailPath.length }
                )
            }

        fun normalize(items: List<SavedManga>): List<SavedManga> {
            return items.map { item ->
                val canonical = canonicalByKey[mangaKey(item.providerId, item.detailPath)]
                item.copy(detailPath = canonical?.detailPath ?: item.detailPath)
            }.distinctBy { it.providerId to it.detailPath }
        }

        return normalize(favorites) to normalize(reading)
    }

    private fun sameStoredManga(left: SavedManga, right: SavedManga): Boolean {
        return left.providerId == right.providerId &&
            mangaKey(left.providerId, left.detailPath) == mangaKey(right.providerId, right.detailPath)
    }

    private fun preferCanonicalDetailPath(left: SavedManga, right: SavedManga): String {
        return if (detailPathScore(right.providerId, right.detailPath) >= detailPathScore(left.providerId, left.detailPath)) {
            right.detailPath
        } else {
            left.detailPath
        }
    }

    private fun mangaKey(providerId: String, detailPath: String): String {
        val normalized = detailPath.trim('/')
        return when (providerId) {
            "inmanga-es" -> normalized.split("/").take(3).joinToString("/")
            else -> normalized
        }
    }

    private fun detailPathScore(providerId: String, detailPath: String): Int {
        val normalized = detailPath.trim('/')
        return when (providerId) {
            "inmanga-es" -> normalized.split("/").size
            else -> normalized.length
        }
    }

    private fun sameStoredChapter(providerId: String, storedQualifiedPath: String, chapterPath: String): Boolean {
        val storedPath = when {
            "::" in storedQualifiedPath -> {
                val prefix = "$providerId::"
                if (!storedQualifiedPath.startsWith(prefix)) return false
                storedQualifiedPath.removePrefix(prefix)
            }
            providerId == defaultProviderId -> storedQualifiedPath
            else -> return false
        }
        return canonicalChapterKey(providerId, storedPath) == canonicalChapterKey(providerId, chapterPath)
    }

    private fun canonicalChapterKey(providerId: String, chapterPath: String): String {
        val normalized = chapterPath.trim('/')
        return when (providerId) {
            "inmanga-es" -> {
                val parts = normalized.split("/").filter { it.isNotBlank() }
                when {
                    parts.size >= 6 && isUuid(parts[3]) -> listOf(parts[0], parts[1], parts[2], parts[4], parts[5]).joinToString("/")
                    else -> normalized
                }
            }
            else -> normalized
        }
    }

    private fun isUuid(value: String): Boolean {
        return Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)
    }

    private companion object {
        private const val KEY_MANGABALL_ADULT_CONTENT = "mangaballAdultContentEnabled"
    }
}
