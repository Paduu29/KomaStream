package com.paudinc.komastream.utils

import com.paudinc.komastream.data.model.SavedManga
import java.text.Normalizer
import java.util.LinkedHashMap

data class LibrarySeriesGroup(
    val key: String,
    val entries: List<SavedManga>,
)

fun groupLibrarySeries(items: List<SavedManga>): List<LibrarySeriesGroup> {
    if (items.isEmpty()) return emptyList()
    val grouped = LinkedHashMap<String, MutableList<SavedManga>>()
    items.forEach { manga ->
        grouped.getOrPut(librarySeriesKey(manga)) { mutableListOf() }.add(manga)
    }
    return grouped.entries.map { (key, entries) ->
        LibrarySeriesGroup(
            key = key,
            entries = entries.sortedWith(
                compareByDescending<SavedManga> { it.malMangaId != null }
                    .thenByDescending { it.lastChapterPath.isNotBlank() }
                    .thenBy { it.providerId }
                    .thenBy { it.detailPath }
            ),
        )
    }.sortedWith(
        compareBy<LibrarySeriesGroup> { canonicalGroupTitle(it) }
            .thenBy { it.key }
    )
}

fun preferredLibrarySeriesEntry(
    group: LibrarySeriesGroup,
    preferredProviderId: String,
): SavedManga {
    return group.entries.firstOrNull { it.providerId == preferredProviderId }
        ?: group.entries.firstOrNull { it.lastChapterPath.isNotBlank() }
        ?: group.entries.first()
}

private fun librarySeriesKey(manga: SavedManga): String {
    manga.malMangaId?.let { return "mal:$it" }
    val normalizedTitle = normalizeSeriesTitle(manga.title)
    if (normalizedTitle.isNotBlank()) return "title:$normalizedTitle"
    return "path:${manga.providerId}:${manga.detailPath}"
}

private fun canonicalGroupTitle(group: LibrarySeriesGroup): String {
    return normalizeSeriesTitle(group.entries.firstOrNull()?.title.orEmpty())
}

private fun normalizeSeriesTitle(value: String): String {
    if (value.isBlank()) return ""
    return Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
