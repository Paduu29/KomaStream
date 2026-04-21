package com.paudinc.komastream.utils

import com.paudinc.komastream.data.model.MangaChapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun buildChapterPath(detailPath: String, chapter: MangaChapter): String {
    if (chapter.path.isNotBlank()) {
        return if (chapter.path.startsWith("/")) chapter.path else "/${chapter.path}"
    }
    val prefix = detailPath.substringBeforeLast("/")
    val path = "$prefix/${chapter.chapterNumberUrl}/${chapter.id}".replace("//", "/")
    return if (path.startsWith("/")) path else "/$path"
}

fun parseChapterInput(value: String): Double? {
    val normalized = value
        .trim()
        .replace(",", ".")
        .replace(Regex("(?<=\\d)-(?=\\d)"), ".")
    return Regex("\\d+(?:\\.\\d+)?")
        .find(normalized)
        ?.value
        ?.toDoubleOrNull()
}

fun resolveTargetUnreadChapterPath(
    providerId: String,
    detailPath: String,
    chapters: List<MangaChapter>,
    readChapters: Set<String>,
    lastOpenedChapterPath: String,
    isFavorite: Boolean,
    autoJumpToUnread: Boolean,
): String? {
    if (!autoJumpToUnread) return null
    val canonicalReadKeys = canonicalChapterKeys(providerId, readChapters)

    val chapterEntries = chapters.map { chapter ->
        val path = buildChapterPath(detailPath, chapter)
        Triple(path, chapter, chapterValue(chapter))
    }
    val hasReadProgress = lastOpenedChapterPath.isNotBlank() || chapterEntries.any { (path, _, _) ->
        canonicalChapterKey(providerId, path) in canonicalReadKeys
    }
    if (!isFavorite && !hasReadProgress) return null

    val unreadEntries = chapterEntries.filter { (path, _, _) ->
        canonicalChapterKey(providerId, path) !in canonicalReadKeys
    }
    if (unreadEntries.isEmpty()) return null

    if (lastOpenedChapterPath.isNotBlank()) {
        val lastReadValue = chapterEntries.firstOrNull { (path, _, _) -> path == lastOpenedChapterPath }?.third
        if (lastReadValue != null) {
            unreadEntries
                .filter { (_, _, value) -> value > lastReadValue }
                .minByOrNull { (_, _, value) -> value }
                ?.let { return it.first }

            unreadEntries
                .filter { (_, _, value) -> value < lastReadValue }
                .maxByOrNull { (_, _, value) -> value }
                ?.let { return it.first }
        }
    }

    return unreadEntries.minByOrNull { (_, _, value) -> value }?.first
}

fun sameChapterPath(providerId: String, left: String, right: String): Boolean {
    if (left == right) return true
    if (left.isBlank() || right.isBlank()) return false
    return canonicalChapterKey(providerId, left) == canonicalChapterKey(providerId, right)
}

fun canonicalChapterKeys(providerId: String, chapterPaths: Iterable<String>): Set<String> {
    return chapterPaths
        .filter { it.isNotBlank() }
        .map { canonicalChapterKey(providerId, it) }
        .toSet()
}

fun canonicalChapterKey(providerId: String, chapterPath: String): String {
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

fun chapterValue(chapter: MangaChapter): Double {
    return parseChapterInput(chapter.chapterNumberUrl)
        ?: parseChapterInput(chapter.chapterLabel)
        ?: Double.MAX_VALUE
}

fun defaultBackupFileName(): String {
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "KomaStream-$timestamp.json"
}

fun formatDateEu(input: String): String {
    val raw = input.take(10)
    return if (Regex("""\d{4}-\d{2}-\d{2}""").matches(raw)) {
        val (year, month, day) = raw.split("-")
        "$day/$month/$year"
    } else {
        input
    }
}

private fun isUuid(value: String): Boolean {
    return Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)
}
