package com.paudinc.komastream.utils

import com.paudinc.komastream.data.model.MangaDetail

class MangaBakaMetadataResolver(
    private val api: MangaBakaApi,
) {
    suspend fun enrichDetail(detail: MangaDetail, knownMalId: Long? = null): MangaDetail {
        var bakaSeries: MangaBakaSeries? = null

        if (detail.mangaBakaId != null) {
            bakaSeries = api.getSeries(detail.mangaBakaId).getOrNull()
        }

        if (bakaSeries == null && knownMalId != null) {
            bakaSeries = api.lookupByMalId(knownMalId).getOrNull()
        }

        if (bakaSeries == null && detail.mangaBakaId == null) {
            val searchResults = api.searchSeries(detail.title, limit = 5).getOrNull().orEmpty()
            bakaSeries = pickBestMatch(searchResults, detail)
        }

        if (bakaSeries == null) return detail

        return mergeMetadata(detail, bakaSeries)
    }

    suspend fun resolveBakaId(detail: MangaDetail, knownMalId: Long? = null): Long? {
        if (detail.mangaBakaId != null) return detail.mangaBakaId
        if (knownMalId != null) {
            val byMal = api.lookupByMalId(knownMalId).getOrNull()
            if (byMal != null) return byMal.id
        }
        val searchResults = api.searchSeries(detail.title, limit = 5).getOrNull().orEmpty()
        val best = pickBestMatch(searchResults, detail) ?: return null
        return best.id
    }

    private fun mergeMetadata(detail: MangaDetail, baka: MangaBakaSeries): MangaDetail {
        val description = if (detail.description.isBlank() && !baka.description.isNullOrBlank()) {
            baka.description
        } else {
            detail.description
        }

        val status = if (detail.status.isBlank() || detail.status == "unknown") {
            when (baka.status) {
                "releasing" -> "Ongoing"
                "completed" -> "Completed"
                "hiatus" -> "Hiatus"
                "cancelled" -> "Cancelled"
                else -> detail.status
            }
        } else {
            detail.status
        }

        val year = baka.publishedStart?.take(4) ?: baka.year?.toString() ?: ""

        return detail.copy(
            authors = if (detail.authors.isEmpty() && baka.authors.isNotEmpty()) baka.authors else detail.authors,
            artists = if (detail.artists.isEmpty() && baka.artists.isNotEmpty()) baka.artists else detail.artists,
            genres = if (detail.genres.isEmpty() && baka.genres.isNotEmpty()) baka.genres else detail.genres,
            mangaBakaId = detail.mangaBakaId ?: baka.id,
            mangaBakaRating = detail.mangaBakaRating ?: baka.rating,
            description = description,
            status = status,
            publicationDate = if (detail.publicationDate.isBlank() && year.isNotBlank()) year else detail.publicationDate,
            coverUrl = if (detail.coverUrl.isBlank() && !baka.coverUrl.isNullOrBlank()) baka.coverUrl else detail.coverUrl,
            bannerUrl = if (detail.bannerUrl.isBlank() && !baka.coverUrl.isNullOrBlank()) baka.coverUrl else detail.bannerUrl,
        )
    }

    private fun pickBestMatch(results: List<MangaBakaSeries>, detail: MangaDetail): MangaBakaSeries? {
        if (results.isEmpty()) return null
        if (results.size == 1) return results.first()

        val queryTitle = detail.title.lowercase().trim()

        val scored = results.map { series ->
            var score = 0

            val seriesTitle = series.title.lowercase().trim()
            if (seriesTitle == queryTitle) score += 100
            else if (queryTitle in seriesTitle || seriesTitle in queryTitle) score += 50

            series.nativeTitle?.lowercase()?.let {
                if (queryTitle in it || it in queryTitle) score += 30
            }
            series.romanizedTitle?.lowercase()?.let {
                if (queryTitle in it || it in queryTitle) score += 20
            }

            val seriesType = series.type.lowercase()
            val detailType = detail.identification.lowercase()
            if (detailType.isNotBlank()) {
                if (detailType.contains("manhwa") && seriesType == "manhwa") score += 25
                else if (detailType.contains("manga") && seriesType == "manga") score += 25
                else if (detailType.contains("manhua") && seriesType == "manhua") score += 25
                else if (detailType.contains("comic") && seriesType == "oel") score += 15
            }

            if (series.status == "releasing" && (detail.status.lowercase() in listOf("ongoing", "releasing"))) score += 15
            if (series.status == "completed" && detail.status.lowercase() in listOf("completed", "complete")) score += 15

            series to score
        }

        return scored.maxByOrNull { it.second }?.first
    }
}
