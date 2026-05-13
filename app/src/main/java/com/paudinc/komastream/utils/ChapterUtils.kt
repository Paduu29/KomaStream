package com.paudinc.komastream.utils

import com.paudinc.komastream.data.model.MangaChapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun buildChapterPath(detailPath: String, chapter: MangaChapter): String {
    if (chapter.path.isNotBlank()) {
        return normalizeStoredPath(chapter.path)
    }
    val prefix = normalizeStoredPath(detailPath).substringBeforeLast("/")
    val path = "$prefix/${chapter.chapterNumberUrl}/${chapter.id}".replace("//", "/")
    return normalizeStoredPath(path)
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
    val readKeys = chapterReadKeys(providerId, detailPath, chapters, readChapters)

    val chapterEntries = chapters.map { chapter ->
        val path = buildChapterPath(detailPath, chapter)
        Triple(path, chapter, chapterValue(chapter))
    }
    val hasReadProgress = lastOpenedChapterPath.isNotBlank() || chapterEntries.any { (_, chapter, _) ->
        chapterReadKey(providerId, detailPath, chapter) in readKeys
    }
    if (!hasReadProgress) return null

    val unreadEntries = chapterEntries.filter { (_, chapter, _) ->
        chapterReadKey(providerId, detailPath, chapter) !in readKeys
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
    val readKeys = chapterReadKeys(providerId, detailPath, chapters, readChapters)
    val chapterEntries = chapters.map { chapter ->
        Triple(buildChapterPath(detailPath, chapter), chapter, chapterValue(chapter))
    }
    val readEntries = chapterEntries.filter { (_, chapter, _) ->
        chapterReadKey(providerId, detailPath, chapter) in readKeys
    }
    if (readEntries.isEmpty()) return null

    val lastReadValue = readEntries.maxOf { it.third }
    chapterEntries
        .filter { (_, chapter, value) ->
            value > lastReadValue && chapterReadKey(providerId, detailPath, chapter) !in readKeys
        }
        .minByOrNull { it.third }
        ?.first
        ?.let { return it }

    return readEntries.maxByOrNull { it.third }?.first
}

fun resolveLatestReadChapterPath(
    providerId: String,
    detailPath: String,
    chapters: List<MangaChapter>,
    readChapters: Set<String>,
): String? {
    val readKeys = chapterReadKeys(providerId, detailPath, chapters, readChapters)
    return chapters.mapNotNull { chapter ->
        val path = buildChapterPath(detailPath, chapter)
        if (chapterReadKey(providerId, detailPath, chapter) in readKeys) {
            path to chapterValue(chapter)
        } else {
            null
        }
    }.maxByOrNull { it.second }?.first
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
    return canonicalChapterPathKey(providerId, chapterPath)
}

fun chapterReadKey(providerId: String, detailPath: String, chapter: MangaChapter): String {
    return when (providerId) {
        MANGA_BALL_PROVIDER_ID -> {
            val value = chapterValue(chapter)
            if (value.isFinite() && value != Double.MAX_VALUE) {
                "num:${normalizeMalChapterNumber(value)}"
            } else {
                canonicalChapterKey(providerId, buildChapterPath(detailPath, chapter))
            }
        }
        else -> canonicalChapterKey(providerId, buildChapterPath(detailPath, chapter))
    }
}

fun chapterReadKeys(
    providerId: String,
    detailPath: String,
    chapters: List<MangaChapter>,
    readChapters: Set<String>,
): Set<String> {
    val canonicalReadKeys = canonicalChapterKeys(providerId, readChapters)
    return chapters.asSequence()
        .filter { chapter ->
            canonicalChapterKey(providerId, buildChapterPath(detailPath, chapter)) in canonicalReadKeys
        }
        .map { chapterReadKey(providerId, detailPath, it) }
        .toSet()
}

fun chapterCountForProvider(providerId: String, chapters: List<MangaChapter>): Int {
    return when (providerId) {
        MANGA_BALL_PROVIDER_ID -> {
            val uniqueValues = chapters.asSequence()
                .map(::chapterValue)
                .filter { it.isFinite() && it != Double.MAX_VALUE }
                .toSet()
            if (uniqueValues.isNotEmpty()) uniqueValues.size else chapters.size
        }
        else -> chapters.size
    }
}

fun chapterValue(chapter: MangaChapter): Double {
    return parseChapterInput(chapter.chapterNumberUrl)
        ?: parseChapterInput(chapter.chapterLabel)
        ?: Double.MAX_VALUE
}

fun String.toProgressChapterNumber(): Double? = parseChapterInput(this)

fun chapterPathProgressNumber(providerId: String, chapterPath: String): Double? {
    val segments = canonicalChapterKey(providerId, chapterPath)
        .split("/")
        .filter { it.isNotBlank() }
    val candidates = when (providerId) {
        "leermangaesp-es" -> listOfNotNull(segments.lastOrNull())
        else -> listOfNotNull(
            segments.getOrNull(segments.lastIndex - 1),
            segments.lastOrNull(),
        )
    }
    return candidates.firstNotNullOfOrNull { parseChapterInput(it) }
}

fun resolveChapterPathForProgressReference(
    providerId: String,
    detailPath: String,
    chapters: List<MangaChapter>,
    progressChapterNumber: Double?,
    fallbackChapterPath: String = "",
): String? {
    if (fallbackChapterPath.isNotBlank()) {
        chapters.firstOrNull { chapter ->
            canonicalChapterKey(providerId, buildChapterPath(detailPath, chapter)) == canonicalChapterKey(providerId, fallbackChapterPath)
        }?.let { return buildChapterPath(detailPath, it) }
    }
    val targetNumber = progressChapterNumber?.takeIf { it.isFinite() } ?: return null
    return chapters.firstOrNull { chapter ->
        val value = chapterValue(chapter)
        value.isFinite() && kotlin.math.abs(value - targetNumber) < 0.0001
    }?.let { buildChapterPath(detailPath, it) }
}

fun normalizeMalChapterNumber(value: Double): Int =
    value.toInt().coerceAtLeast(0)

fun resolveMalReadCountForSelection(chapters: List<MangaChapter>): Int {
    val highestValue = chapters.asSequence()
        .map(::chapterValue)
        .filter { it.isFinite() && it != Double.MAX_VALUE }
        .maxOrNull()
        ?: return chapters.size
    return normalizeMalChapterNumber(highestValue)
}

fun resolveMalReadCountForReadChapters(
    providerId: String,
    detailPath: String,
    chapters: List<MangaChapter>,
    readChapters: Set<String>,
): Int {
    val readKeys = chapterReadKeys(providerId, detailPath, chapters, readChapters)
    val normalizedReadNumbers = chapters.asSequence()
        .filter { chapter ->
            chapterReadKey(providerId, detailPath, chapter) in readKeys
        }
        .map(::chapterValue)
        .filter { it.isFinite() && it != Double.MAX_VALUE }
        .filter { kotlin.math.abs(it - it.toInt().toDouble()) < 0.0001 }
        .map(::normalizeMalChapterNumber)
        .filter { it > 0 }
        .toSet()
    if (normalizedReadNumbers.isEmpty()) return 0

    var contiguousCount = 0
    while ((contiguousCount + 1) in normalizedReadNumbers) {
        contiguousCount += 1
    }
    return contiguousCount
}

fun resolveMalReadCountFromProgressPointer(
    providerId: String,
    detailPath: String,
    chapters: List<MangaChapter>,
    progressChapterPath: String,
    readChapters: Set<String>,
): Int? {
    if (progressChapterPath.isBlank()) return null
    val progressChapter = chapters.firstOrNull { chapter ->
        canonicalChapterKey(providerId, buildChapterPath(detailPath, chapter)) ==
            canonicalChapterKey(providerId, progressChapterPath)
    } ?: return null

    val progressValue = chapterValue(progressChapter)
    if (!progressValue.isFinite() || progressValue == Double.MAX_VALUE) return null

    val pointedChapterIsRead = chapterReadKey(providerId, detailPath, progressChapter) in
        chapterReadKeys(providerId, detailPath, chapters, readChapters)
    val normalized = normalizeMalChapterNumber(progressValue)
    val isWholeNumber = kotlin.math.abs(progressValue - progressValue.toInt().toDouble()) < 0.0001

    return when {
        pointedChapterIsRead -> normalized
        isWholeNumber -> (normalized - 1).coerceAtLeast(0)
        else -> normalized
    }
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

private const val MANGA_BALL_PROVIDER_ID = "mangaball-multi"
