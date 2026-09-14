package com.paudinc.komastream.utils

import com.paudinc.komastream.data.model.MangaDetail
import java.text.Normalizer
import java.util.Locale

class MangaBakaMetadataResolver(private val api: MangaBakaDataSource) {
    suspend fun resolve(detail: MangaDetail, knownMalId: Long? = null): MangaDetail {
        val series = find(detail, knownMalId) ?: return detail
        return detail.copy(
            title = series.title.ifUsable() ?: detail.title,
            description = series.description.ifUsable() ?: detail.description,
            coverUrl = series.coverUrl.ifUsable() ?: detail.coverUrl,
            bannerUrl = series.coverUrl.ifUsable() ?: detail.bannerUrl,
            publicationDate = (series.publishedStart?.take(4) ?: series.year?.toString()).ifUsable() ?: detail.publicationDate,
            status = statusLabel(series.status).ifUsable() ?: detail.status,
            authors = series.authors.takeIf { it.isNotEmpty() } ?: detail.authors,
            artists = series.artists.takeIf { it.isNotEmpty() } ?: detail.artists,
            genres = series.genres.takeIf { it.isNotEmpty() } ?: detail.genres,
            mangaBakaId = series.id,
            mangaBakaRating = series.rating ?: detail.mangaBakaRating,
            alternativeTitles = series.alternativeTitles,
        )
    }

    private fun find(detail: MangaDetail, knownMalId: Long?): MangaBakaSeries? {
        detail.mangaBakaId?.let { id -> api.getSeries(id).getOrNull()?.let { return it } }
        knownMalId?.let { id -> api.lookupByMalId(id).getOrNull()?.let { return it } }
        val candidates = api.searchSeries(detail.title, 10).getOrNull().orEmpty()
        return pickBestMatch(candidates, detail)
    }

    private fun pickBestMatch(results: List<MangaBakaSeries>, detail: MangaDetail): MangaBakaSeries? {
        val query = normalize(detail.title)
        if (query.isBlank()) return null
        val scored = results.map { series ->
            val names = listOfNotNull(series.title, series.nativeTitle, series.romanizedTitle)
                .plus(series.alternativeTitles).map(::normalize).filter(String::isNotBlank)
            val exact = names.any { it == query }
            val overlap = names.maxOfOrNull { tokenScore(query, it) } ?: 0
            series to if (exact) 100 else overlap
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val second = scored.getOrNull(1)?.second ?: 0
        return best.first.takeIf { best.second >= 100 || (best.second >= 72 && best.second - second >= 12) }
    }

    private fun tokenScore(a: String, b: String): Int {
        if (a == b) return 100
        val left = a.split(' ').toSet(); val right = b.split(' ').toSet()
        val common = left.intersect(right).size
        return ((2.0 * common / (left.size + right.size).coerceAtLeast(1)) * 100).toInt()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace("\\p{M}".toRegex(), "").replace("&", " and ").replace("['’]".toRegex(), "")
        .replace("[^\\p{L}\\p{N}]+".toRegex(), " ").trim().replace("\\s+".toRegex(), " ")

    private fun statusLabel(value: String): String = when (value.lowercase(Locale.ROOT)) {
        "releasing" -> "Ongoing"; "completed" -> "Completed"; "hiatus" -> "Hiatus"; "cancelled" -> "Cancelled"; else -> ""
    }

    private fun String?.ifUsable(): String? = this?.trim()?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }
}
