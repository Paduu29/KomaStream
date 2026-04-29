package com.paudinc.komastream.utils

import com.paudinc.komastream.data.model.MangaChapter
import java.math.BigDecimal
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
    val token = Regex("\\d[\\d.,-]*")
        .find(value.trim())
        ?.value
        ?: return null
    return normalizeChapterNumberToken(token)?.toDoubleOrNull()
}

fun resolveTargetUnreadChapterPath(
    providerId: String,
    detailPath: String,
    chapters: List<MangaChapter>,
    readChapters: Set<String>,
    lastOpenedChapterPath: String,
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
    if (!hasReadProgress) return null

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

fun resolveProgressChapterPath(
    providerId: String,
    detailPath: String,
    chapters: List<MangaChapter>,
    readChapters: Set<String>,
): String? {
    val canonicalReadKeys = canonicalChapterKeys(providerId, readChapters)
    val chapterEntries = chapters.map { chapter ->
        Triple(buildChapterPath(detailPath, chapter), chapter, chapterValue(chapter))
    }
    val readEntries = chapterEntries.filter { (path, _, _) ->
        canonicalChapterKey(providerId, path) in canonicalReadKeys
    }
    if (readEntries.isEmpty()) return null

    val lastReadValue = readEntries.maxOf { it.third }
    chapterEntries
        .filter { (path, _, value) ->
            value > lastReadValue && canonicalChapterKey(providerId, path) !in canonicalReadKeys
        }
        .minByOrNull { it.third }
        ?.first
        ?.let { return it }

    return readEntries.maxByOrNull { it.third }?.first
}

fun resolveReadThroughChapterPaths(
    providerId: String,
    detailPath: String,
    chapters: List<MangaChapter>,
    currentChapterPath: String,
): List<String> {
    val currentValue = chapters.firstOrNull { chapter ->
        canonicalChapterKey(providerId, buildChapterPath(detailPath, chapter)) ==
            canonicalChapterKey(providerId, currentChapterPath)
    }?.let(::chapterValue)
        ?: return listOf(currentChapterPath).filter { it.isNotBlank() }

    return chapters.mapNotNull { chapter ->
        val path = buildChapterPath(detailPath, chapter)
        if (chapterValue(chapter) <= currentValue) path else null
    }.distinct()
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
    val normalized = canonicalizeChapterPath(chapterPath)
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

private fun normalizeChapterNumberToken(value: String): String? {
    if (value.isBlank()) return null
    val normalizedHyphen = value.replace(Regex("(?<=\\d)-(?=\\d)"), ".")
    val separators = normalizedHyphen.filter { it == ',' || it == '.' }
    if (separators.isEmpty()) {
        return normalizedHyphen.filter { it.isDigit() }
    }

    if (separators.length > 1) {
        val lastSeparatorIndex = normalizedHyphen.lastIndexOfAny(charArrayOf(',', '.'))
        if (lastSeparatorIndex < 0) return normalizedHyphen.filter { it.isDigit() }
        val integerPart = normalizedHyphen.substring(0, lastSeparatorIndex).filter { it.isDigit() }
        val fractionalPart = normalizedHyphen.substring(lastSeparatorIndex + 1).filter { it.isDigit() }
        return if (fractionalPart.isBlank()) integerPart else "$integerPart.$fractionalPart"
    }

    val separator = separators.first()
    val parts = normalizedHyphen.split(separator)
    if (parts.size != 2) {
        return normalizedHyphen.filter { it.isDigit() }
    }

    val left = parts[0].filter { it.isDigit() }
    val right = parts[1].filter { it.isDigit() }
    if (left.isBlank()) return right
    if (right.isBlank()) return left

    return when {
        right.length == 3 -> left + right
        else -> "$left.$right"
    }
}

private fun canonicalizeChapterPath(chapterPath: String): String {
    val normalized = chapterPath
        .substringBefore("?")
        .substringBefore("#")
        .trim('/')
    if (normalized.isBlank()) return ""

    val parts = normalized.split("/").filter { it.isNotBlank() }.toMutableList()
    if (parts.size >= 2) {
        val chapterIndex = parts.lastIndex - 1
        normalizeChapterPathToken(parts[chapterIndex])?.let { parts[chapterIndex] = it }
    }
    return parts.joinToString("/")
}

private fun normalizeChapterPathToken(value: String): String? {
    val parsed = parseChapterInput(value) ?: return null
    return BigDecimal(parsed.toString()).stripTrailingZeros().toPlainString()
}
