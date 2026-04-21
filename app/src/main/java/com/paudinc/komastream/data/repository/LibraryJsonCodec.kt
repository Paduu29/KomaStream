package com.paudinc.komastream.data.repository

import com.paudinc.komastream.data.model.SavedManga
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

class LibraryJsonCodec(
    private val defaultProviderId: String,
) {
    fun parseSavedMangaList(value: String, fallbackProviderId: String = defaultProviderId): List<SavedManga> {
        val json = JSONArray(value)
        val items = buildList(json.length()) {
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                val providerId = item.optString("providerId")
                    .ifBlank { inferProviderId(item, fallbackProviderId) }
                val rawDetailPath = item.optString("detailPath")
                val rawChapterPath = item.optString("chapterPath")
                val rawLastChapterPath = item.optString("lastChapterPath")
                val mangaTitle = item.optString("mangaTitle")
                val detailPath = normalizeFavoriteDetailPath(
                    providerId = providerId,
                    detailPath = rawDetailPath,
                    mangaPath = item.optString("mangaPath"),
                    chapterPath = rawChapterPath.ifBlank { rawLastChapterPath },
                    title = mangaTitle.ifBlank { item.optString("title") },
                )
                val title = mangaTitle
                    .ifBlank { item.optString("title").takeUnless { looksLikeChapterTitle(it) }.orEmpty() }
                    .ifBlank { item.optString("mangaTitle") }
                    .ifBlank { item.optString("chapterLabel") }
                val coverUrl = item.optString("coverUrl")
                    .ifBlank { item.optString("imageUrl") }
                    .ifBlank { item.optString("thumbnailUrl") }
                if (detailPath.isBlank() || title.isBlank()) continue
                add(
                    SavedManga(
                        providerId = providerId,
                        title = title,
                        detailPath = detailPath,
                        coverUrl = coverUrl,
                        lastChapterTitle = item.optString("lastChapterTitle"),
                        lastChapterPath = rawLastChapterPath.ifBlank { rawChapterPath },
                    )
                )
            }
        }
        return items.distinctBy { it.providerId to it.detailPath }
    }

    fun serializeSavedMangaList(items: List<SavedManga>): String {
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

    fun parseReadChapters(value: String, providerId: String): Set<String> {
        return parseRawReadChapters(value)
            .mapNotNull { unqualify(providerId, it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun parseRawReadChapters(value: String): Set<String> {
        val json = JSONArray(value)
        return buildSet(json.length()) {
            for (index in 0 until json.length()) {
                add(json.optString(index))
            }
        }.filter { it.isNotBlank() }.toSet()
    }

    fun serializeReadChapters(items: List<String>): String {
        val json = JSONArray()
        items.forEach { item -> json.put(item) }
        return json.toString()
    }

    fun qualify(providerId: String, value: String): String = "$providerId::$value"

    private fun normalizeFavoriteDetailPath(
        providerId: String,
        detailPath: String,
        mangaPath: String,
        chapterPath: String,
        title: String,
    ): String {
        val normalizedMangaPath = normalizeStoredPath(mangaPath)
        if (normalizedMangaPath.isNotBlank()) return normalizedMangaPath

        val normalizedDetailPath = normalizeStoredPath(detailPath)
        if (normalizedDetailPath.isNotBlank() && !shouldInferFromDetailPath(providerId, normalizedDetailPath, title, chapterPath)) {
            return normalizedDetailPath
        }

        val inferredFromDetailPath = inferDetailPath(providerId, detailPath)
        if (inferredFromDetailPath.isNotBlank()) return inferredFromDetailPath

        return inferDetailPath(providerId, chapterPath)
    }

    private fun shouldInferFromDetailPath(
        providerId: String,
        detailPath: String,
        title: String,
        chapterPath: String,
    ): Boolean {
        if (detailPath.isBlank()) return true
        if (hasCanonicalDetailPath(providerId, detailPath)) return false
        return looksLikeChapterTitle(title) || chapterPath.isNotBlank()
    }

    private fun hasCanonicalDetailPath(providerId: String, detailPath: String): Boolean {
        val normalizedPath = normalizeStoredPath(detailPath)
        return when (providerId) {
            "inmanga-es" -> {
                val parts = normalizedPath.trim('/').split("/")
                parts.size >= 4 && mangaUuidRegex.matches(parts.last())
            }
            else -> normalizedPath.isNotBlank()
        }
    }

    private fun inferProviderId(item: JSONObject, fallbackProviderId: String): String {
        val candidates = listOf(
            item.optString("detailPath"),
            item.optString("mangaPath"),
            item.optString("chapterPath"),
            item.optString("lastChapterPath"),
            item.optString("coverUrl"),
            item.optString("imageUrl"),
            item.optString("thumbnailUrl"),
        )
        return candidates
            .asSequence()
            .map(::inferProviderIdFromValue)
            .firstOrNull { it.isNotBlank() }
            ?: fallbackProviderId
    }

    private fun inferProviderIdFromValue(value: String): String {
        if (value.isBlank()) return ""
        val trimmed = value.trim()
        val host = trimmed.toHttpUrlOrNull()?.host.orEmpty().lowercase()
        val path = normalizeStoredPath(trimmed).lowercase()
        return when {
            host.contains("inmanga.com") || host.contains("intomanga.com") -> "inmanga-es"
            host.contains("mangafire.to") || host.contains("mfcdn.nl") -> "mangafire-en"
            "/ver/manga/" in path -> "inmanga-es"
            "/read/" in path -> "mangafire-en"
            else -> ""
        }
    }

    private fun inferDetailPath(providerId: String, chapterPath: String): String {
        val normalizedPath = normalizeStoredPath(chapterPath)
        if (normalizedPath.isBlank()) return ""
        return when (providerId) {
            "inmanga-es" -> {
                val parts = normalizedPath.trim('/').split("/")
                parts.take(3)
                    .takeIf { it.size == 3 }
                    ?.joinToString("/", prefix = "/")
                    .orEmpty()
            }
            "mangafire-en" -> normalizedPath
                .substringBefore("/read/", missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }
                ?.let { if (it.startsWith("/")) it else "/$it" }
                .orEmpty()
            else -> normalizedPath
        }
    }

    private fun normalizeStoredPath(value: String): String {
        if (value.isBlank()) return ""
        val trimmed = value.trim()
        val parsed = trimmed.toHttpUrlOrNull()
        return when {
            parsed != null -> parsed.encodedPath
            trimmed.startsWith("/") -> trimmed
            else -> "/$trimmed"
        }
    }

    private fun looksLikeChapterTitle(value: String): Boolean {
        if (value.isBlank()) return false
        val normalized = value.trim().lowercase()
        return normalized.startsWith("chapter ") ||
            normalized.startsWith("capitulo ") ||
            normalized.startsWith("capítulo ")
    }

    private fun unqualify(providerId: String, value: String): String? {
        val prefix = "$providerId::"
        return when {
            value.startsWith(prefix) -> value.removePrefix(prefix)
            "::" !in value && providerId == defaultProviderId -> value
            else -> null
        }
    }

    private companion object {
        val mangaUuidRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
