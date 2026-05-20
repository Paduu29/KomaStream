package com.paudinc.komastream.provider.providers

import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CatalogSearchResult
import com.paudinc.komastream.data.model.CategoryOption
import com.paudinc.komastream.data.model.FilterOption
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.HomeFeedSection
import com.paudinc.komastream.data.model.HomeSectionPageResult
import com.paudinc.komastream.data.model.HomeSectionType
import com.paudinc.komastream.data.model.MangaChapter
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.ReaderPage
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.utils.normalizeStoredPath
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.util.Locale

class OlympusBibliotecaProvider : MangaProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "Olympus Biblioteca"
    override val language: AppLanguage = AppLanguage.ES
    override val websiteUrl: String = BASE_URL
    override val logoUrl: String = "$BASE_URL/olympus-logo-180.webp"

    private val client = OkHttpClient()

    @Volatile
    private var cachedSeriesList: List<SeriesListItem>? = null
    @Volatile
    private var cachedHomepagePayload: JSONObject? = null

    override fun fetchHomeFeed(): HomeFeed {
        val document = getDocument("/")
        val featured = parseFeaturedMangas(document)
        val parsedSections = parseHomeSections(document).associateBy { it.id }
        val sections = buildList {
            parsedSections[SECTION_ID_POPULAR_DAY]?.let(::add)
            fetchLatestReleasesSection()?.let(::add)
            fetchTopSeriesSection()?.let(::add)
            fetchNovelsSection()?.let(::add)
            addAll(parsedSections.values.filterNot { section ->
                section.id == SECTION_ID_POPULAR_DAY ||
                    section.id == SECTION_ID_NEW_RELEASES ||
                    section.id == SECTION_ID_TOP_SERIES ||
                    section.id == SECTION_ID_NOVELS
            })
        }
        val popular = sections.firstOrNull { it.id == "top-series" }?.mangas
            .orEmpty()
            .ifEmpty { sections.firstOrNull()?.mangas.orEmpty() }
            .ifEmpty { featured }
        return HomeFeed(
            latestUpdates = emptyList(),
            popularChapters = emptyList(),
            popularMangas = popular,
            sections = buildList {
                if (featured.isNotEmpty()) {
                    add(
                        HomeFeedSection(
                            id = "featured",
                            title = "Destacados",
                            type = HomeSectionType.MANGAS,
                            mangas = featured,
                        )
                    )
                }
                addAll(sections)
            },
        )
    }

    override fun fetchHomeSectionPage(sectionId: String, page: Int): HomeSectionPageResult? {
        if (page < 1) return null
        return when (sectionId) {
            SECTION_ID_TOP_SERIES -> fetchTopSeriesPage(page)
            SECTION_ID_NEW_RELEASES -> fetchLatestReleasesPage(page)
            else -> null
        }
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        return CatalogFilterOptions(
            categories = listOf(
                CategoryOption("comic", "Comic"),
                CategoryOption("novel", "Novela"),
            ),
            sortOptions = listOf(
                FilterOption("default", "Destacados"),
                FilterOption("alphabetical", "A-Z"),
            ),
            statusOptions = emptyList(),
        )
    }

    override fun searchCatalog(
        query: String,
        categoryIds: List<String>,
        sortBy: String,
        broadcastStatus: String,
        onlyFavorites: Boolean,
        skip: Int,
        take: Int,
    ): CatalogSearchResult {
        val pageSize = take.coerceAtLeast(1)
        val normalizedQuery = normalizeForSearch(query)
        val queryTokens = normalizedQuery.split(' ').filter { it.isNotBlank() }
        val selectedTypes = categoryIds.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.toSet()
        val filtered = fetchSeriesList()
            .asSequence()
            .filter { selectedTypes.isEmpty() || it.type in selectedTypes }
            .mapNotNull { item ->
                val searchScore = catalogSearchScore(
                    normalizedQuery = normalizedQuery,
                    queryTokens = queryTokens,
                    title = item.name,
                    slug = item.slug,
                ) ?: return@mapNotNull null
                item.toSummary() to searchScore
            }
            .let { sequence ->
                when (sortBy.lowercase(Locale.ROOT)) {
                    "alphabetical", "a-z" -> sequence.sortedBy { normalizeForSearch(it.first.title) }
                    else -> sequence.sortedWith(
                        compareBy<Pair<MangaSummary, Double>> { it.second }
                            .thenBy { normalizeForSearch(it.first.title) }
                    )
                }
            }
            .map { it.first }
            .toList()
        val start = skip.coerceAtLeast(0)
        val items = filtered.drop(start).take(pageSize)
        return CatalogSearchResult(
            items = items,
            hasMore = start + items.size < filtered.size,
        )
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizePath(detailPath)
        val seriesType = extractSeriesType(normalizedPath)
        val slug = extractSeriesSlug(normalizedPath)
        val payload = getJson("/api/series/$slug?type=$seriesType").optJSONObject("data") ?: JSONObject()
        val genres = payload.optJSONArray("genres").toGenreLabel()
        val status = payload.optJSONObject("status")?.optString("name").cleanText()
        val coverUrl = payload.optString("cover").cleanText()

        return MangaDetail(
            providerId = id,
            identification = payload.optInt("id").takeIf { it > 0 }?.toString().orEmpty().ifBlank { slug },
            title = payload.optString("name").trim(),
            detailPath = "/series/$seriesType-$slug",
            coverUrl = coverUrl,
            bannerUrl = coverUrl,
            description = payload.optString("summary").trim(),
            status = status,
            publicationDate = payload.optString("created_at").trim(),
            periodicity = genres,
            chapters = fetchAllChapters(slug, seriesType),
        )
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        val normalizedPath = normalizePath(chapterPath)
        val chapterId = extractChapterId(normalizedPath)
        val seriesType = extractSeriesType(normalizedPath)
        val slug = extractSeriesSlug(normalizedPath)
        val payload = getJson("/api/capitulo/$slug/$chapterId?type=$seriesType")
        val chapter = payload.optJSONObject("chapter") ?: JSONObject()
        val comic = payload.optJSONObject("comic") ?: JSONObject()
        val pages = chapter.optJSONArray("pages").toReaderPages()
        val mangaTitle = comic.optString("name").cleanText()
        val chapterName = chapter.optString("name").cleanText()
        val mangaDetailPath = "/series/$seriesType-$slug"

        return ReaderData(
            providerId = id,
            mangaTitle = mangaTitle,
            mangaDetailPath = mangaDetailPath,
            chapterTitle = chapterName.ifBlank { "Capitulo" }.let { "Capitulo $it" },
            chapterPath = normalizedPath,
            previousChapterPath = payload.optJSONObject("prev_chapter")?.toChapterPath(slug, seriesType),
            nextChapterPath = payload.optJSONObject("next_chapter")?.toChapterPath(slug, seriesType),
            pages = pages,
        )
    }

    override fun downloadBytes(url: String, referer: String?): ByteArray {
        val request = Request.Builder()
            .url(resolveAbsoluteUrl(url))
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer?.let(::resolveAbsoluteUrl) ?: BASE_URL)
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun fetchAllChapters(slug: String, seriesType: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        var lastPage = 1

        do {
            val payload = getJson("$DASHBOARD_URL/api/series/$slug/chapters?page=$page&direction=desc&type=$seriesType")
            val items = payload.optJSONArray("data") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val chapterId = item.optInt("id")
                val chapterName = item.optString("name").trim()
                if (chapterId <= 0) continue
                chapters += MangaChapter(
                    id = chapterId.toString(),
                    chapterLabel = "Capitulo $chapterName",
                    chapterNumberUrl = chapterName,
                    path = "/capitulo/$chapterId/$seriesType-$slug",
                    pagesCount = 0,
                    registrationDate = item.optString("published_at").cleanText(),
                    uploaderLabel = item.optJSONObject("team")?.optString("name").cleanText(),
                )
            }
            lastPage = payload.optJSONObject("meta")?.optInt("last_page", page) ?: page
            page += 1
        } while (page <= lastPage)

        return chapters
    }

    private fun fetchSeriesList(): List<SeriesListItem> {
        cachedSeriesList?.let { return it }
        val payload = getJson("/api/series/list")
        val items = payload.optJSONArray("data") ?: JSONArray()
        val parsed = buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(
                    SeriesListItem(
                        id = item.optInt("id"),
                        name = item.optString("name").cleanText(),
                        slug = item.optString("slug").cleanText(),
                        cover = item.optString("cover").cleanText(),
                        type = item.optString("type").cleanText(),
                    )
                )
            }
        }.filter { it.name.isNotBlank() && it.slug.isNotBlank() }
        cachedSeriesList = parsed
        return parsed
    }

    private fun fetchHomepagePayload(): JSONObject {
        cachedHomepagePayload?.let { return it }
        val payload = getJson("/api/homepage").optJSONObject("data") ?: JSONObject()
        cachedHomepagePayload = payload
        return payload
    }

    private fun fetchTopSeriesSection(): HomeFeedSection? {
        val result = fetchTopSeriesPage(page = 1) ?: return null
        if (result.mangas.isEmpty()) return null
        return HomeFeedSection(
            id = SECTION_ID_TOP_SERIES,
            title = "Top Series",
            type = HomeSectionType.MANGAS,
            mangas = result.mangas,
        )
    }

    private fun fetchTopSeriesPage(page: Int): HomeSectionPageResult? {
        val payload = getJson("/api/rankings?page=$page&period=total_ranking")
        val items = payload.optJSONArray("data") ?: JSONArray()
        val mangas = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                parseSeriesListItem(item)?.toRankingSummary()?.let(::add)
                parseSeriesListItem(item.optJSONObject("series"))?.toRankingSummary(item)?.let(::add)
                parseSeriesListItem(item.optJSONObject("comic"))?.toRankingSummary(item)?.let(::add)
                parseSeriesListItem(item.optJSONObject("novel"))?.toRankingSummary(item)?.let(::add)
            }
        }.distinctBy { it.detailPath }
        return HomeSectionPageResult(
            type = HomeSectionType.MANGAS,
            mangas = mangas,
            hasMore = payload.optInt("current_page", page) < payload.optInt("last_page", page),
        )
    }

    private fun fetchLatestReleasesSection(): HomeFeedSection? {
        val mangas = fetchLatestReleasesPage(page = 1)?.mangas
            .orEmpty()
            .ifEmpty { fetchHomepagePayload().optJSONArray("new_chapters").toLatestReleaseSummaries() }
        if (mangas.isEmpty()) return null
        return HomeFeedSection(
            id = SECTION_ID_NEW_RELEASES,
            title = "Nuevos Lanzamientos",
            type = HomeSectionType.MANGAS,
            mangas = mangas,
        )
    }

    private fun fetchLatestReleasesPage(page: Int): HomeSectionPageResult? {
        val payload = getJson("/api/new-chapters?page=$page")
        val items = payload.optJSONArray("data") ?: JSONArray()
        val mangas = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                parseSeriesListItem(item)?.toLatestReleaseSummary(item)?.let(::add)
            }
        }.distinctBy { it.detailPath }
        return HomeSectionPageResult(
            type = HomeSectionType.MANGAS,
            mangas = mangas,
            hasMore = payload.optInt("current_page", page) < payload.optInt("last_page", page),
        )
    }

    private fun fetchNovelsSection(): HomeFeedSection? {
        val mangas = fetchSeriesList()
            .asSequence()
            .filter { it.type == "novel" }
            .map { it.toSummary() }
            .take(24)
            .toList()
        if (mangas.isEmpty()) return null
        return HomeFeedSection(
            id = "novelas",
            title = "Novelas",
            type = HomeSectionType.MANGAS,
            mangas = mangas,
        )
    }

    private fun parseFeaturedMangas(document: Document): List<MangaSummary> {
        return document.select("main section").firstOrNull()
            ?.select("a[href*='/series/comic-']")
            ?.mapNotNull { anchor ->
                val detailPath = normalizePath(anchor.attr("href"))
                val title = anchor.selectFirst("img[alt]")?.attr("alt").cleanText()
                    .ifBlank { detailPath.substringAfter("comic-").replace('-', ' ') }
                if (detailPath.isBlank() || title.isBlank()) return@mapNotNull null
                MangaSummary(
                    providerId = id,
                    title = title,
                    detailPath = detailPath,
                    coverUrl = anchor.selectFirst("img")?.attr("abs:src").cleanText(),
                )
            }
            ?.distinctBy { it.detailPath }
            .orEmpty()
    }

    private fun parseHomeSections(document: Document): List<HomeFeedSection> {
        return document.select("main section").mapNotNull { section ->
            val title = section.selectFirst("h2")?.text().cleanText()
            if (title.isBlank()) return@mapNotNull null
            val sectionId = slugify(title)
            if (
                sectionId == SECTION_ID_NEW_RELEASES ||
                sectionId == SECTION_ID_TOP_SERIES ||
                sectionId == SECTION_ID_NOVELS
            ) return@mapNotNull null
            val mangas = section.select("figure").mapNotNull(::parseFigureCard)
            if (mangas.isEmpty()) return@mapNotNull null
            HomeFeedSection(
                id = sectionId,
                title = title,
                type = HomeSectionType.MANGAS,
                mangas = mangas,
            )
        }
    }

    private fun parseFigureCard(figure: Element): MangaSummary? {
        val link = figure.selectFirst("a[href*='/series/comic-']") ?: return null
        val detailPath = normalizePath(link.attr("href"))
        val title = figure.selectFirst("figcaption")?.text().cleanText()
            .ifBlank { link.selectFirst("img[alt]")?.attr("alt").cleanText() }
        if (detailPath.isBlank() || title.isBlank()) return null
        val status = figure.select("div")
            .map { it.text().cleanText() }
            .lastOrNull { it.equals("Activo", true) || it.contains("Hiatus", true) || it.equals("Finalizado", true) }
            .orEmpty()
        return MangaSummary(
            providerId = id,
            title = title,
            detailPath = detailPath,
            coverUrl = figure.selectFirst("img")?.attr("abs:src").cleanText(),
            status = status,
        )
    }

    private fun getDocument(path: String): Document = Jsoup.parse(getText(path), BASE_URL)

    private fun getJson(path: String): JSONObject = JSONObject(getText(path))

    private fun getText(path: String): String {
        val request = Request.Builder()
            .url(resolveAbsoluteUrl(path))
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    private fun resolveAbsoluteUrl(path: String): String {
        val value = path.cleanText()
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> "$BASE_URL$value"
            else -> "$BASE_URL/$value"
        }
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        val parsed = path.toHttpUrlOrNull()
        return when {
            parsed != null -> normalizeStoredPath(parsed.encodedPath + parsed.query?.let { "?$it" }.orEmpty())
            path.startsWith("/") -> normalizeStoredPath(path)
            else -> normalizeStoredPath("/$path")
        }
    }

    private fun extractSeriesType(path: String): String {
        val normalized = normalizePath(path)
        return when {
            normalized.contains("/novel-") || normalized.contains("novel-") -> "novel"
            else -> "comic"
        }
    }

    private fun extractSeriesSlug(path: String): String {
        val normalized = normalizePath(path)
        val segment = normalized.trim('/').substringAfterLast('/')
        return segment
            .removePrefix("comic-")
            .removePrefix("novel-")
    }

    private fun extractChapterId(path: String): String {
        return normalizePath(path).trim('/').split('/').getOrNull(1).orEmpty()
    }

    private fun normalizeForSearch(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return normalized.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
    }

    private fun catalogSearchScore(
        normalizedQuery: String,
        queryTokens: List<String>,
        title: String,
        slug: String,
    ): Double? {
        if (queryTokens.isEmpty()) return 0.0
        val normalizedTitle = normalizeForSearch(title)
        val normalizedSlug = normalizeForSearch(slug)
        val searchIndex = listOf(normalizedTitle, normalizedSlug).filter { it.isNotBlank() }.joinToString(" ")
        if (searchIndex.isBlank()) return null

        if (normalizedTitle == normalizedQuery) return 0.0
        if (normalizedSlug == normalizedQuery) return 0.02
        if (normalizedTitle.startsWith(normalizedQuery)) return 0.04
        if (normalizedTitle.contains(normalizedQuery)) return 0.08
        if (normalizedSlug.contains(normalizedQuery)) return 0.12

        if (queryTokens.all(searchIndex::contains)) {
            return 0.2 + queryTokens.sumOf { token ->
                searchIndex.indexOf(token).takeIf { it >= 0 }?.toDouble() ?: 0.0
            } / 10_000.0
        }

        val wordScores = queryTokens.map { token ->
            bestFuzzyTokenScore(token, normalizedTitle, normalizedSlug)
        }
        if (wordScores.any { it == null }) return null
        return 0.4 + wordScores.filterNotNull().average()
    }

    private fun bestFuzzyTokenScore(
        token: String,
        normalizedTitle: String,
        normalizedSlug: String,
    ): Double? {
        val titleWords = normalizedTitle.split(' ').filter { it.isNotBlank() }
        val slugWords = normalizedSlug.split(' ').filter { it.isNotBlank() }
        val candidates = buildList {
            add(normalizedTitle)
            add(normalizedSlug)
            addAll(titleWords)
            addAll(slugWords)
        }.distinct()

        return candidates.mapNotNull { candidate ->
            fuzzyTokenScore(token, candidate)
        }.minOrNull()
    }

    private fun fuzzyTokenScore(token: String, candidate: String): Double? {
        if (token.isBlank() || candidate.isBlank()) return null
        if (candidate.contains(token)) {
            return candidate.indexOf(token).toDouble() / 1_000.0
        }
        if (!isSubsequence(token, candidate)) return null

        val distance = levenshteinDistance(token, candidate.take(token.length + 2))
        val threshold = when {
            token.length <= 4 -> 2
            token.length <= 7 -> 3
            else -> 4
        }
        if (distance > threshold) return null
        return 0.1 + distance.toDouble() / 10.0 + (candidate.length - token.length).coerceAtLeast(0) / 100.0
    }

    private fun isSubsequence(token: String, candidate: String): Boolean {
        var tokenIndex = 0
        for (char in candidate) {
            if (tokenIndex < token.length && char == token[tokenIndex]) {
                tokenIndex += 1
            }
        }
        return tokenIndex == token.length
    }

    private fun levenshteinDistance(first: String, second: String): Int {
        if (first == second) return 0
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length

        val previous = IntArray(second.length + 1) { it }
        val current = IntArray(second.length + 1)

        for (i in first.indices) {
            current[0] = i + 1
            for (j in second.indices) {
                val substitutionCost = if (first[i] == second[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + substitutionCost,
                )
            }
            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }
        return previous[second.length]
    }

    private fun slugify(value: String): String {
        return normalizeForSearch(value).replace(' ', '-')
    }

    private fun JSONArray?.toGenreLabel(): String {
        if (this == null) return ""
        return buildList(length()) {
            for (index in 0 until length()) {
                val name = optJSONObject(index)?.optString("name").cleanText()
                if (name.isNotBlank()) add(name)
            }
        }.joinToString(", ")
    }

    private fun JSONArray?.toReaderPages(): List<ReaderPage> {
        if (this == null) return emptyList()
        return buildList(length()) {
            for (index in 0 until length()) {
                val url = optString(index).cleanText()
                if (url.isBlank()) continue
                add(
                    ReaderPage(
                        id = url.substringAfterLast('/').substringBefore('?').ifBlank { "${index + 1}" },
                        numberLabel = "${index + 1}",
                        imageUrl = url,
                    )
                )
            }
        }
    }

    private fun JSONObject.toChapterPath(slug: String, seriesType: String): String? {
        val chapterId = optInt("id")
        return chapterId.takeIf { it > 0 }?.let { "/capitulo/$it/$seriesType-$slug" }
    }

    private fun SeriesListItem.toSummary(): MangaSummary {
        return MangaSummary(
            providerId = PROVIDER_ID,
            title = name,
            detailPath = detailPath(),
            coverUrl = cover,
        )
    }

    private fun SeriesListItem.toLatestReleaseSummary(source: JSONObject): MangaSummary {
        val latestChapters = source.optJSONArray("last_chapters").toChapterNames()
        return MangaSummary(
            providerId = PROVIDER_ID,
            title = name,
            detailPath = detailPath(),
            coverUrl = cover,
            status = source.optString("status").cleanText(),
            latestPublication = latestChapters,
        )
    }

    private fun SeriesListItem.toRankingSummary(source: JSONObject? = null): MangaSummary {
        val ranking = source?.optInt("position").takeIf { it != null && it > 0 }
        val totalViews = source?.optLong("total_views") ?: 0L
        val monthlyViews = source?.optLong("monthly_views") ?: 0L
        val chapters = source?.optInt("chapter_count") ?: 0
        return MangaSummary(
            providerId = PROVIDER_ID,
            title = name,
            detailPath = detailPath(),
            coverUrl = cover,
            status = source?.optJSONObject("status")?.optString("name").cleanText()
                .ifBlank { source?.optString("status").cleanText() },
            chaptersCount = chapters.takeIf { it > 0 }?.toString().orEmpty(),
            latestPublication = ranking?.let { "#$it" }.orEmpty(),
            views = when {
                monthlyViews > 0L -> formatViews(monthlyViews, "mes")
                totalViews > 0L -> formatViews(totalViews, "total")
                else -> ""
            },
        )
    }

    private fun SeriesListItem.detailPath(): String = "/series/$type-$slug"

    private fun parseSeriesListItem(item: JSONObject?): SeriesListItem? {
        if (item == null) return null
        val id = item.optInt("id")
        val name = item.optString("name").cleanText()
        val slug = item.optString("slug").cleanText()
        val cover = item.optString("cover").cleanText()
        val type = item.optString("type").cleanText().ifBlank {
            when {
                item.has("novel_id") -> "novel"
                else -> "comic"
            }
        }
        if (name.isBlank() || slug.isBlank() || (type != "comic" && type != "novel")) return null
        return SeriesListItem(
            id = id,
            name = name,
            slug = slug,
            cover = cover,
            type = type,
        )
    }

    private fun JSONArray?.toChapterNames(): String {
        if (this == null) return ""
        val names = buildList {
            for (index in 0 until length()) {
                val chapter = optJSONObject(index) ?: continue
                val name = chapter.optString("name").cleanText()
                if (name.isNotBlank()) add(name)
            }
        }
        if (names.isEmpty()) return ""
        return names.joinToString(", ", prefix = "Capitulos ")
    }

    private fun String?.cleanText(): String {
        val value = this?.trim().orEmpty()
        return if (value.equals("null", ignoreCase = true)) "" else value
    }

    private fun JSONArray?.toLatestReleaseSummaries(): List<MangaSummary> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                parseSeriesListItem(item)?.toLatestReleaseSummary(item)?.let(::add)
            }
        }.distinctBy { it.detailPath }
    }

    private fun formatViews(value: Long, label: String): String {
        return when {
            value >= 1_000_000L -> String.format(Locale.US, "%.1fM %s", value / 1_000_000.0, label)
            value >= 1_000L -> String.format(Locale.US, "%.1fK %s", value / 1_000.0, label)
            else -> "$value $label"
        }
    }

    private data class SeriesListItem(
        val id: Int,
        val name: String,
        val slug: String,
        val cover: String,
        val type: String,
    )

    private companion object {
        const val PROVIDER_ID = "olympusbiblioteca-es"
        const val BASE_URL = "https://olympusbiblioteca.com"
        const val DASHBOARD_URL = "https://dashboard.olympusbiblioteca.com"
        const val SECTION_ID_POPULAR_DAY = "popular-del-dia"
        const val SECTION_ID_NEW_RELEASES = "nuevos-lanzamientos"
        const val SECTION_ID_TOP_SERIES = "top-series"
        const val SECTION_ID_NOVELS = "novelas"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
