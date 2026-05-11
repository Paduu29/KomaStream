package com.paudinc.komastream.provider.providers

import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CatalogSearchResult
import com.paudinc.komastream.data.model.CategoryOption
import com.paudinc.komastream.data.model.ChapterSummary
import com.paudinc.komastream.data.model.FilterOption
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.HomeFeedSection
import com.paudinc.komastream.data.model.HomeSectionType
import com.paudinc.komastream.data.model.MangaChapter
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.ReaderPage
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.utils.chapterValue
import com.paudinc.komastream.utils.normalizeStoredPath
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MangadotProvider : MangaProvider {
    override val id: String = "mangadotnet-en"
    override val displayName: String = "Mangadotnet"
    override val language: AppLanguage = AppLanguage.EN
    override val websiteUrl: String = "https://mangadot.net"
    override val logoUrl: String = "https://mangadot.net/mangadotnet-purple.svg"

    private val baseUrl = "https://mangadot.net"
    private val client = OkHttpClient()

    override fun fetchHomeFeed(): HomeFeed {
        val latestUpdates = fetchSectionMangas("latest_updates")
        val recentlyAdded = fetchSectionMangas("recently_added")
        val mostTracked = fetchSectionMangas("most_tracked")
        val topRated = fetchSectionMangas("top_rated")

        return HomeFeed(
            latestUpdates = emptyList(),
            popularChapters = emptyList(),
            popularMangas = mostTracked.ifEmpty { topRated }.ifEmpty { latestUpdates },
            sections = listOfNotNull(
                latestUpdates.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "latest-updates",
                        title = "Latest Updates",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
                recentlyAdded.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "recently-added",
                        title = "Recently Added",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
                mostTracked.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "most-tracked",
                        title = "Most Tracked",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
                topRated.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "top-rated",
                        title = "Top Rated",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
            ),
        )
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        return CatalogFilterOptions(
            categories = listOf(
                CategoryOption("JP", "Manga"),
                CategoryOption("KR", "Manhwa"),
                CategoryOption("CN", "Manhua"),
                CategoryOption("ONESHOT", "One Shot"),
            ),
            sortOptions = listOf(
                FilterOption("relevance", "Relevance"),
                FilterOption("latest", "Latest"),
                FilterOption("alphabetical", "A → Z"),
                FilterOption("chapters", "Chapters"),
                FilterOption("views", "Most viewed"),
                FilterOption("tracked", "Most tracked"),
                FilterOption("rating", "Top rated"),
            ),
            statusOptions = listOf(
                FilterOption("any", "Any"),
                FilterOption("Ongoing", "Ongoing"),
                FilterOption("Completed", "Completed"),
                FilterOption("Hiatus", "Hiatus"),
            ),
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
        val page = (skip / pageSize) + 1
        val localSkip = skip % pageSize
        val document = getDocument(
            buildSearchPath(
                query = query,
                categoryIds = categoryIds,
                sortBy = sortBy,
                broadcastStatus = broadcastStatus,
                page = page,
                perPage = pageSize,
            )
        )
        val pageItems = parseMangaCards(document)
        val items = pageItems.drop(localSkip).take(pageSize)
        return CatalogSearchResult(
            items = items,
            hasMore = hasNextPage(document) || localSkip + pageSize < pageItems.size,
        )
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizeStoredPath(detailPath)
        val mangaId = normalizedPath.trim('/').substringAfterLast('/').ifBlank { normalizedPath.trim('/') }
        val document = getDocument("/manga/$mangaId")
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val coverUrl = document.select("img[alt]")
            .firstOrNull { it.attr("alt").trim() == title && it.absUrl("src").isNotBlank() }
            ?.absUrl("src")
            .orEmpty()
        val bannerUrl = document.selectFirst("div[style*='background-image:url(/uploads/banners/']")
            ?.attr("style")
            ?.extractBackgroundImageUrl()
            .orEmpty()
            .ifBlank { coverUrl }
        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            .orEmpty()
        val status = document.select("span.inline-flex")
            .firstOrNull { it.text().trim() in setOf("Ongoing", "Completed", "Hiatus") }
            ?.text()
            ?.trim()
            .orEmpty()
        val publicationDate = extractJsonField(document.outerHtml(), "date_added_formatted")
        val chapters = fetchMangaChapters(mangaId)

        return MangaDetail(
            providerId = id,
            identification = mangaId,
            title = title,
            detailPath = normalizedPath.ifBlank { "/manga/$mangaId" },
            coverUrl = coverUrl,
            bannerUrl = bannerUrl,
            description = description,
            status = status,
            publicationDate = publicationDate,
            periodicity = "",
            chapters = chapters,
        )
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        val normalizedPath = normalizeStoredPath(chapterPath)
        val chapterId = normalizedPath.substringBefore('?').trim('/').substringAfterLast('/')
        val sourceUser = normalizedPath.queryParameter("source") == "user"
        val volumeMode = normalizedPath.queryParameter("mode") == "volume"
        val apiPath = if (sourceUser) {
            "/api/uploads/$chapterId/images"
        } else {
            "/api/chapters/$chapterId/images"
        }
        val response = getJson(apiPath)
        val chapter = response.optJSONObject("chapter") ?: JSONObject()
        val manga = response.optJSONObject("manga") ?: JSONObject()
        val images = response.optJSONArray("images")?.toReaderPages().orEmpty()

        val chapterTitle = chapter.optString("chapter_title")
            .trim()
            .ifBlank { "Chapter ${chapter.optString("chapter_number").trim()}" }
        val mangaDetailPath = manga.optLong("id").takeIf { it > 0 }
            ?.let { "/manga/$it" }
            .orEmpty()
        val previousChapterPath = buildAdjacentChapterPath(
            chapterId = response.opt("prev_chapter_id"),
            sourceUser = sourceUser,
            volumeMode = volumeMode,
            source = response.optString("prev_source"),
        )
        val nextChapterPath = buildAdjacentChapterPath(
            chapterId = response.opt("next_chapter_id"),
            sourceUser = sourceUser,
            volumeMode = volumeMode,
            source = response.optString("next_source"),
        )

        return ReaderData(
            providerId = id,
            mangaTitle = manga.optString("title").trim(),
            mangaDetailPath = mangaDetailPath.ifBlank { normalizedPath },
            chapterTitle = chapterTitle,
            chapterPath = normalizedPath,
            previousChapterPath = previousChapterPath,
            nextChapterPath = nextChapterPath,
            pages = images,
        )
    }

    override fun downloadBytes(url: String, referer: String?): ByteArray {
        val request = Request.Builder()
            .url(url.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .apply {
                if (referer != null) {
                    header("Referer", referer.toAbsoluteUrl())
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun fetchSectionMangas(sectionId: String): List<MangaSummary> {
        val response = getJson("/api/manga/section?id=$sectionId&adult=0")
        return response.optJSONArray("items")?.toMangaSummaries().orEmpty()
    }

    private fun fetchMangaChapters(mangaId: String): List<MangaChapter> {
        val response = JSONArray(getText("/api/manga/$mangaId/chapters/list"))
        return buildList {
            for (index in 0 until response.length()) {
                val item = response.optJSONObject(index) ?: continue
                val chapterId = item.optString("id").trim()
                val source = item.optString("source").trim()
                val chapterNumber = item.optString("chapter_number").trim()
                val chapterTitle = item.optString("chapter_title").trim()
                val volumeNumber = item.optString("volume_number").trim()
                val pageCount = item.optInt("page_count", 0)
                val label = chapterTitle.ifBlank { "Chapter ${chapterNumber.ifBlank { chapterId }}" }
                add(
                    MangaChapter(
                        id = chapterId,
                        chapterLabel = label,
                        chapterNumberUrl = chapterNumber.ifBlank { chapterId },
                        path = buildChapterPath(chapterId, source, volumeNumber),
                        pagesCount = pageCount,
                        registrationDate = item.optString("date_added").trim(),
                        languageCode = item.optString("language").trim(),
                        languageLabel = item.optString("language").trim(),
                        uploaderLabel = item.optString("group_name").trim()
                            .ifBlank { item.optString("scanlator_name").trim() }
                            .ifBlank { item.optString("uploader_username").trim() },
                    )
                )
            }
        }.sortedWith(
            compareByDescending<MangaChapter> { chapterValue(it) }
                .thenByDescending { it.registrationDate }
                .thenByDescending { it.id }
        )
    }

    private fun buildMangaCard(element: Element): MangaSummary? {
        val link = element.attr("href").trim()
        val title = element.selectFirst("div.line-clamp-2")?.text()?.trim().orEmpty()
        val coverUrl = element.selectFirst("img")?.absUrl("src").orEmpty()
        if (!link.startsWith("/manga/") || title.isBlank()) return null
        return MangaSummary(
            providerId = id,
            title = title,
            detailPath = link.normalizePath(),
            coverUrl = coverUrl,
        )
    }

    private fun parseMangaCards(document: Document): List<MangaSummary> {
        return document.select("a[href^=/manga/]")
            .mapNotNull { card ->
                val title = card.selectFirst("div.line-clamp-2")?.text()?.trim().orEmpty()
                val coverUrl = card.selectFirst("img")?.absUrl("src").orEmpty()
                val detailPath = card.attr("href").trim()
                if (title.isBlank() || !detailPath.startsWith("/manga/")) {
                    null
                } else {
                    MangaSummary(
                        providerId = id,
                        title = title,
                        detailPath = detailPath.normalizePath(),
                        coverUrl = coverUrl,
                    )
                }
            }
            .distinctBy { it.detailPath }
    }

    private fun buildSearchPath(
        query: String,
        categoryIds: List<String>,
        sortBy: String,
        broadcastStatus: String,
        page: Int,
        perPage: Int,
    ): String {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", perPage.toString())
        val normalizedSort = sortBy.takeIf { it.isNotBlank() } ?: if (query.isNotBlank()) "relevance" else "latest"
        url.addQueryParameter("sortBy", normalizedSort)
        url.addQueryParameter("sortOrder", "desc")
        query.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("search", it) }
        broadcastStatus.takeIf { it.isNotBlank() && it != "any" }?.let { url.addQueryParameter("status", it) }
        categoryIds.forEach { origin -> url.addQueryParameter("origin", origin) }
        return url.build().toString()
    }

    private fun hasNextPage(document: Document): Boolean {
        val nextButton = document.selectFirst("nav[aria-label='Pagination'] button[aria-label='Next page']")
        return nextButton != null && !nextButton.hasAttr("disabled")
    }

    private fun buildChapterPath(
        chapterId: String,
        source: String,
        volumeNumber: String,
    ): String {
        val sourceQuery = if (source == "user") "source=user" else ""
        val volumeQuery = if (source == "user" && volumeNumber.isNotBlank()) "mode=volume" else ""
        val query = listOf(sourceQuery, volumeQuery).filter { it.isNotBlank() }.joinToString("&")
        return if (query.isBlank()) {
            "/chapter/$chapterId"
        } else {
            "/chapter/$chapterId?$query"
        }.normalizePath()
    }

    private fun buildAdjacentChapterPath(
        chapterId: Any?,
        sourceUser: Boolean,
        volumeMode: Boolean,
        source: String,
    ): String? {
        val idValue = when (chapterId) {
            is Number -> chapterId.toLong().takeIf { it > 0 }?.toString()
            is String -> chapterId.trim().takeIf { it.isNotBlank() }
            else -> null
        } ?: return null
        val shouldUseUserSource = sourceUser || source == "user"
        val query = buildList {
            if (shouldUseUserSource) add("source=user")
            if (volumeMode) add("mode=volume")
        }.joinToString("&")
        return if (query.isBlank()) {
            "/chapter/$idValue"
        } else {
            "/chapter/$idValue?$query"
        }.normalizePath()
    }

    private fun getDocument(path: String): Document {
        return Jsoup.parse(getText(path), baseUrl)
    }

    private fun getJson(path: String): JSONObject {
        return JSONObject(getText(path))
    }

    private fun getText(path: String): String {
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    private fun String.toAbsoluteUrl(): String {
        val trimmed = trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return if (trimmed.startsWith("/")) "$baseUrl$trimmed" else "$baseUrl/$trimmed"
    }

    private fun String.normalizePath(): String = normalizeStoredPath(this)

    private fun String.queryParameter(name: String): String {
        val query = substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return ""
        return query.split('&')
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size != 2) return@mapNotNull null
                pieces[0] to pieces[1]
            }
            .firstOrNull { (key, _) -> key == name }
            ?.second
            .orEmpty()
    }

    private fun JSONArray.toMangaSummaries(): List<MangaSummary> {
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val idValue = item.optLong("id", -1)
                val title = item.optString("title").trim()
                val photo = item.optString("photo").trim()
                if (idValue <= 0 || title.isBlank()) continue
                add(
                    MangaSummary(
                        providerId = id,
                        title = title,
                        detailPath = "/manga/$idValue",
                        coverUrl = photo.toAbsoluteUrl(),
                    )
                )
            }
        }
    }

    private fun JSONArray.toReaderPages(): List<ReaderPage> {
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (url.isBlank()) continue
                add(
                    ReaderPage(
                        id = item.optString("filename").trim().ifBlank { "${index + 1}" },
                        numberLabel = item.optString("filename").trim().ifBlank { "${index + 1}" },
                        imageUrl = url.toAbsoluteUrl(),
                    )
                )
            }
        }
    }

    private fun String.extractBackgroundImageUrl(): String {
        val match = Regex("""background-image:url\(([^)]+)\)""").find(this) ?: return ""
        return match.groupValues.getOrNull(1).orEmpty().trim('\'', '"')
    }

    private fun extractJsonField(html: String, fieldName: String): String {
        val regex = Regex("""\"$fieldName\",\"([^\"]*)\"""")
        return regex.find(html)?.groupValues?.getOrNull(1).orEmpty()
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
