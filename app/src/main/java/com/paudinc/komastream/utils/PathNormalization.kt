package com.paudinc.komastream.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

fun normalizeStoredPath(value: String): String {
    val (path, query) = splitPathAndQuery(value)
    if (path.isBlank()) return ""
    return path.lowercase(Locale.ROOT) + query
}

fun canonicalMangaPathKey(providerId: String, detailPath: String): String {
    val normalized = normalizeStoredPath(detailPath).substringBefore('?').substringBefore('#').trim('/')
    return when (providerId) {
        "inmanga-es" -> normalized.split("/").filter { it.isNotBlank() }.take(3).joinToString("/")
        else -> normalized
    }
}

fun canonicalChapterPathKey(providerId: String, chapterPath: String): String {
    val normalized = normalizeStoredPath(chapterPath).substringBefore('?').substringBefore('#').trim('/')
    if (normalized.isBlank()) return ""
    val parts = normalized.split("/").filter { it.isNotBlank() }.toMutableList()
    return when (providerId) {
        "leermangaesp-es" -> {
            if (parts.isNotEmpty()) {
                normalizeChapterPathToken(parts.last())?.let { parts[parts.lastIndex] = it }
            }
            parts.joinToString("/")
        }
        "inmanga-es" -> {
            if (parts.size >= 2) {
                val chapterIndex = parts.lastIndex - 1
                normalizeChapterPathToken(parts[chapterIndex])?.let { parts[chapterIndex] = it }
            }
            when {
                parts.size >= 6 && isUuid(parts[3]) -> listOf(parts[0], parts[1], parts[2], parts[4], parts[5]).joinToString("/")
                else -> parts.joinToString("/")
            }
        }
        else -> {
            if (parts.size >= 2) {
                val chapterIndex = parts.lastIndex - 1
                normalizeChapterPathToken(parts[chapterIndex])?.let { parts[chapterIndex] = it }
            }
            parts.joinToString("/")
        }
    }
}

fun sameMangaPath(providerId: String, left: String, right: String): Boolean {
    if (left == right) return true
    if (left.isBlank() || right.isBlank()) return false
    return canonicalMangaPathKey(providerId, left) == canonicalMangaPathKey(providerId, right)
}

fun normalizePathForProvider(path: String): String = normalizeStoredPath(path)

private fun splitPathAndQuery(value: String): Pair<String, String> {
    if (value.isBlank()) return "" to ""
    val trimmed = value.trim()
    val parsed = trimmed.toHttpUrlOrNull()
    if (parsed != null) {
        val path = parsed.encodedPath.orEmpty()
        val query = parsed.encodedQuery?.let { "?$it" }.orEmpty()
        return path to query
    }

    val withoutFragment = trimmed.substringBefore('#')
    val path = withoutFragment.substringBefore('?')
    val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        .takeIf { withoutFragment.contains('?') }
        ?.let { "?$it" }
        .orEmpty()
    val normalizedPath = when {
        path.isBlank() -> ""
        path.startsWith("/") -> path
        else -> "/$path"
    }
    return normalizedPath to query
}

private fun normalizeChapterPathToken(value: String): String? {
    val parsed = parseChapterInput(value) ?: return null
    return java.math.BigDecimal(parsed.toString()).stripTrailingZeros().toPlainString()
}

private fun isUuid(value: String): Boolean {
    return Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)
}
