package com.paudinc.komastream

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class MangaFireProvider(
    context: Context? = null,
) : MangaProvider {
    override val id: String = "mangafire-en"
    override val displayName: String = "MangaFire"
    override val language: AppLanguage = AppLanguage.EN
    override val websiteUrl: String = "https://mangafire.to"
    override val logoUrl: String = "https://s.mfcdn.nl/assets/sites/mangafire/favicon.png?v4"

    private val baseUrl = "https://mangafire.to"
    private val client = OkHttpClient()
    private val readerResolver = context?.let { MangaFireWebViewResolver(it.applicationContext, client) }

    override fun fetchHomeFeed(): HomeFeed {
        val latestUpdates = parseCatalogCards(getDocument("/updated")).mapNotNull { card ->
            card.toChapterSummary(id, languageCode)
        }
        val popularCards = parseCatalogCards(getDocument("/filter?language=$languageCode&sort=most_viewed"))
        return HomeFeed(
            latestUpdates = latestUpdates,
            popularChapters = popularCards.mapNotNull { card -> card.toChapterSummary(id, languageCode) },
            popularMangas = popularCards.map { it.toMangaSummary(id) },
        )
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        val document = getDocument("/filter?language=$languageCode")
        return CatalogFilterOptions(
            categories = document.select("input[name='genre[]'][value]").mapNotNull { input ->
                val value = input.attr("value").trim()
                val label = document.selectFirst("label[for='${input.id()}']")?.text()?.trim().orEmpty()
                if (value.isBlank() || label.isBlank()) null else CategoryOption(value, label)
            },
            sortOptions = document.select("input[name='sort'][value]").mapNotNull { input ->
                val value = input.attr("value").trim()
                val label = document.selectFirst("label[for='${input.id()}']")?.text()?.trim().orEmpty()
                if (value.isBlank() || label.isBlank()) null else FilterOption(value, label)
            },
            statusOptions = document.select("input[name='status[]'][value]").mapNotNull { input ->
                val value = input.attr("value").trim()
                val label = document.selectFirst("label[for='${input.id()}']")?.text()?.trim().orEmpty()
                if (value.isBlank() || label.isBlank()) null else FilterOption(value, label)
            },
        )
    }

    override fun searchCatalog(
        query: String,
        categoryIds: List<String>,
        sortBy: String,
        broadcastStatus: String,
        onlyFavorites: Boolean,
        skip: Int,
        take: Int
    ): CatalogSearchResult {
        val page = (skip / BROWSE_PAGE_SIZE) + 1
        val localSkip = skip % BROWSE_PAGE_SIZE
        return if (query.isNotBlank()) {
            val items = searchByQuery(query).drop(skip).take(take)
            CatalogSearchResult(
                items = items,
                hasMore = false,
            )
        } else {
            val document = getDocument(buildFilterPath(categoryIds, sortBy, broadcastStatus, page))
            val pageItems = parseCatalogCards(document)
                .map { it.toMangaSummary(id) }
            val items = pageItems
                .drop(localSkip)
                .take(take)
            CatalogSearchResult(
                items = items,
                hasMore = document.selectFirst(".pagination .page-item a[rel='next']") != null || localSkip + take < pageItems.size,
            )
        }
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizePath(detailPath)
        val document = getDocument(normalizedPath)
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val coverUrl = document.selectFirst(".main-inner .poster img")?.absUrl("src").orEmpty()
        val synopsis = document.selectFirst("#synopsis .modal-content")
            ?.text()
            ?.trim()
            .orEmpty()
        val alternateTitle = document.selectFirst(".main-inner h6")
            ?.text()
            ?.trim()
            .orEmpty()
        val description = listOf(synopsis, alternateTitle)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { "No description" }

        val metaText = document.select(".main-inner .meta span")
            .map { it.text().trim() }
        val publicationDate = metaValue(metaText, "Published:")
        val status = document.selectFirst(".main-inner .info > p")?.text()?.trim().orEmpty()

        val mangaId = normalizedPath.substringAfterLast('.')
        val chaptersHtml = getJson("/ajax/manga/$mangaId/chapter/$languageCode")
            .optString("result")
        val chapters = parseChapterList(chaptersHtml)

        return MangaDetail(
            providerId = id,
            identification = mangaId,
            title = title,
            detailPath = normalizedPath,
            coverUrl = coverUrl,
            bannerUrl = coverUrl,
            description = description,
            status = status,
            publicationDate = publicationDate,
            periodicity = "",
            chapters = chapters,
        )
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        val resolver = requireNotNull(readerResolver) { "MangaFire reader requires Android context" }
        return resolver.fetchReaderData(providerId = id, chapterPath = normalizePath(chapterPath))
    }

    override fun downloadBytes(url: String, referer: String?): ByteArray {
        readerResolver?.takeIf { url.startsWith("file://") }?.let { resolver ->
            return resolver.downloadBytes(url, referer)
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { if (referer != null) header("Referer", toAbsoluteUrl(referer)) }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun searchByQuery(query: String): List<MangaSummary> {
        val response = getJson("/ajax/manga/search?query=${query.urlEncode()}")
        val html = response.optJSONObject("result")?.optString("html").orEmpty()
        if (html.isBlank()) return emptyList()
        val document = Jsoup.parse(html, baseUrl)
        return document.select(".unit[href], a.unit[href]").mapNotNull { item ->
            val link = item.attr("href").trim()
            val title = item.selectFirst("h6")?.text()?.trim().orEmpty()
            val coverUrl = item.selectFirst("img")?.absUrl("src").orEmpty()
            if (link.isBlank() || title.isBlank()) null else {
                MangaSummary(
                    providerId = id,
                    title = title,
                    detailPath = normalizePath(link),
                    coverUrl = coverUrl,
                )
            }
        }
    }

    private fun parseCatalogCards(document: Document): List<MangaFireCard> =
        document.select(".original.card-lg .unit .inner").mapNotNull { item ->
            val detailLink = item.selectFirst(".info > a[href]") ?: return@mapNotNull null
            val detailPath = normalizePath(detailLink.attr("href"))
            val title = detailLink.text().trim()
            val coverUrl = item.selectFirst(".poster img")?.absUrl("src").orEmpty()
            val type = item.selectFirst(".type")?.text()?.trim().orEmpty()
            val chapterLink = item.select("ul.content[data-name='chap'] a[href]")
                .firstOrNull { link ->
                    link.selectFirst("b")?.text()?.trim()?.equals(languageCode.uppercase(), ignoreCase = true) == true
                }
                ?: item.selectFirst("ul.content[data-name='chap'] a[href]")
            val chapterPath = chapterLink?.attr("href").orEmpty()
            val chapterLabel = chapterLink?.selectFirst("span")?.ownText()?.trim().orEmpty()
            val chapterDate = chapterLink?.select("span")?.getOrNull(1)?.text()?.trim().orEmpty()
            MangaFireCard(
                title = title,
                detailPath = detailPath,
                coverUrl = coverUrl,
                type = type,
                chapterPath = normalizePath(chapterPath),
                chapterLabel = chapterLabel,
                chapterDate = chapterDate,
            )
        }

    private fun parseChapterList(html: String): List<MangaChapter> {
        if (html.isBlank()) return emptyList()
        val document = Jsoup.parseBodyFragment(html, baseUrl)
        return document.select("li.item").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val path = normalizePath(link.attr("href"))
            val label = item.selectFirst("span")?.text()?.trim().orEmpty()
            if (path.isBlank() || label.isBlank()) return@mapNotNull null
            MangaChapter(
                id = path.substringAfterLast('/'),
                chapterLabel = label,
                chapterNumberUrl = path.substringAfterLast('/'),
                path = path,
                pagesCount = 0,
                registrationDate = item.select("span").getOrNull(1)?.text()?.trim().orEmpty(),
            )
        }
    }

    private fun buildFilterPath(
        categoryIds: List<String>,
        sortBy: String,
        status: String,
        page: Int,
    ): String {
        val url = "$baseUrl/filter".toHttpUrl().newBuilder()
            .addQueryParameter("language", languageCode)
            .addQueryParameter("page", page.toString())
        if (sortBy.isNotBlank()) {
            url.addQueryParameter("sort", sortBy)
        }
        categoryIds.forEach { url.addQueryParameter("genre[]", it) }
        if (status.isNotBlank()) {
            url.addQueryParameter("status[]", status)
        }
        return url.build().toString()
    }

    private fun metaValue(items: List<String>, prefix: String): String =
        items.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.substringAfter(prefix)
            ?.trim()
            .orEmpty()

    private fun getDocument(path: String): Document {
        val request = Request.Builder()
            .url(toAbsoluteUrl(path))
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            return Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        }
    }

    private fun getJson(path: String): JSONObject {
        val request = Request.Builder()
            .url(toAbsoluteUrl(path))
            .header("User-Agent", USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(request).execute().use { response ->
            return JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun toAbsoluteUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else "$baseUrl${normalizePath(path)}"

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        val parsed = path.toHttpUrlOrNull()
        return when {
            parsed != null -> parsed.encodedPath
            path.startsWith("/") -> path
            else -> "/$path"
        }
    }

    private fun String.urlEncode(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

    private data class MangaFireCard(
        val title: String,
        val detailPath: String,
        val coverUrl: String,
        val type: String,
        val chapterPath: String,
        val chapterLabel: String,
        val chapterDate: String,
    ) {
        fun toMangaSummary(providerId: String): MangaSummary =
            MangaSummary(
                providerId = providerId,
                title = title,
                detailPath = detailPath,
                coverUrl = coverUrl,
                status = type,
            )

        fun toChapterSummary(providerId: String, languageCode: String): ChapterSummary? {
            if (chapterPath.isBlank() || chapterLabel.isBlank()) return null
            if ("/$languageCode/" !in chapterPath) return null
            return ChapterSummary(
                providerId = providerId,
                mangaTitle = title,
                chapterLabel = chapterLabel,
                chapterNumberUrl = chapterPath.substringAfterLast('/'),
                chapterId = chapterPath.substringAfterLast('/'),
                mangaPath = detailPath,
                chapterPath = chapterPath,
                coverUrl = coverUrl,
                registrationLabel = chapterDate,
            )
        }
    }

    private companion object {
        private const val BROWSE_PAGE_SIZE = 30
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private const val languageCode = "en"
    }
}
