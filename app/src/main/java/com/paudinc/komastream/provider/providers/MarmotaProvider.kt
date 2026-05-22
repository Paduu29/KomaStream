package com.paudinc.komastream.provider.providers

import android.content.Context
import android.net.Uri
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CatalogSearchResult
import com.paudinc.komastream.data.model.CategoryOption
import com.paudinc.komastream.data.model.ChapterSummary
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
import com.paudinc.komastream.utils.sameChapterPath
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class MarmotaProvider : MangaProvider {
    override val id: String = PROVIDER_ID
    override val displayName: String = "Marmota"
    override val language: AppLanguage = AppLanguage.ES
    override val websiteUrl: String = BASE_URL
    override val logoUrl: String = "https://marmota.me/wp-content/uploads/2023/03/logo-marmota-me.png"

    private val client = OkHttpClient()

    override fun fetchHomeFeed(): HomeFeed {
        val homeDocument = getDocument("/")
        val sections = parseGenreSections(homeDocument)
        val fallbackSection = if (sections.isEmpty()) {
            parseMangaCards(homeDocument)
                .take(12)
                .takeIf { it.isNotEmpty() }
                ?.let {
                    HomeFeedSection(
                        id = "todos-los-comics",
                        title = "Todos los cómics",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                }
        } else {
            null
        }
        return HomeFeed(
            latestUpdates = emptyList(),
            popularChapters = emptyList(),
            popularMangas = sections.firstOrNull()?.mangas.orEmpty().ifEmpty { fallbackSection?.mangas.orEmpty() },
            sections = if (sections.isNotEmpty()) sections else listOfNotNull(fallbackSection),
        )
    }

    override fun fetchHomeSectionPage(sectionId: String, page: Int): HomeSectionPageResult? {
        if (sectionId.isBlank()) return null
        val document = getDocument(buildGenrePath(sectionId, page))
        val mangas = parseMangaCards(document)
        return HomeSectionPageResult(
            type = HomeSectionType.MANGAS,
            mangas = mangas,
            hasMore = hasNextPage(document),
        )
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        val genres = parseGenreLinks(getDocument("/"))
        return CatalogFilterOptions(
            categories = genres.map { CategoryOption(it.slug, it.name) },
            sortOptions = listOf(
                FilterOption("latest", "Mas reciente"),
                FilterOption("alphabet", "A-Z"),
                FilterOption("ratings", "Calificación"),
                FilterOption("trending", "Trending"),
                FilterOption("views", "Más leidos"),
                FilterOption("new", "Nuevos"),
            ),
            statusOptions = listOf(
                FilterOption("ongoing", "OnGoing"),
                FilterOption("completed", "Completed"),
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
        val document = when {
            query.isNotBlank() -> getDocument(buildSearchPath(query, sortBy, page))
            categoryIds.isNotEmpty() -> getDocument(buildGenrePath(categoryIds.first(), page))
            else -> getDocument(buildArchivePath(sortBy, page))
        }
        val items = parseMangaCards(document)
        return CatalogSearchResult(
            items = items.drop(localSkip).take(pageSize),
            hasMore = hasNextPage(document) || localSkip + pageSize < items.size,
        )
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizeStoredPath(detailPath)
        val document = getDocument(normalizedPath)
        val title = document.selectFirst("h1, .entry-title")?.text()?.trim().orEmpty()
            .ifBlank { normalizedPath.trim('/').substringAfterLast('/').replace('-', ' ') }
        val coverUrl = findFirstImage(
            document,
            listOf(".featured-cover", "meta[property=og:image]", "img"),
        )
        val description = document.selectFirst(".manga-excerpt")?.text()?.trim().orEmpty()
            .ifBlank {
                document.selectFirst(".entry-content p, .comic-summary, article p")
                    ?.text()
                    ?.trim()
                    .orEmpty()
            }
            .ifBlank {
                document.selectFirst("meta[property=og:description], meta[name=description]")
                    ?.attr("content")
                    ?.trim()
                    .orEmpty()
            }
        val status = extractMetaValue(document, "Estado", "Status")
        val publicationDate = extractMetaValue(document, "Publicación", "Publication")
        val genreLinks = document.select("a[href*='/genero/']")
            .mapNotNull { link ->
                val href = normalizeStoredPath(link.attr("href"))
                val slug = href.substringAfter("/genero/", "").trim('/').substringBefore('/').ifBlank { return@mapNotNull null }
                GenreLink(slug = slug, name = link.text().trim(), href = href)
            }
            .distinctBy { it.slug }
        var chapters = fetchChapterList(normalizedPath)
        if (chapters.isEmpty()) {
            chapters = genreLinks.asSequence()
                .mapNotNull { genre ->
                    val genreDoc = getDocument(buildGenrePath(genre.slug, 1))
                    val card = genreDoc.selectFirst("div.page-item-detail.manga")
                        ?.takeIf {
                            normalizeStoredPath(it.selectFirst(".post-title a[href*='/comic/']")?.attr("href").orEmpty()) == normalizedPath
                        }
                    card?.let { parseChapterListFromCard(it, normalizedPath) }
                }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
        }
        val genres = genreLinks.joinToString(", ") { it.name }

        return MangaDetail(
            providerId = id,
            identification = normalizedPath.trim('/').substringAfterLast('/'),
            title = title,
            detailPath = normalizedPath,
            coverUrl = coverUrl,
            bannerUrl = coverUrl,
            description = description.ifBlank { "No description" },
            status = status,
            publicationDate = publicationDate,
            periodicity = genres,
            chapters = chapters,
            chapterSources = emptyList(),
            selectedChapterSourceId = "",
            needsCloudflareClearance = false,
        )
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        val normalizedPath = normalizeStoredPath(chapterPath)
        val document = getDocument(normalizedPath)
        val chapterTitle = document.selectFirst("h1, .entry-title")?.text()?.trim().orEmpty()
            .ifBlank { normalizedPath.substringAfterLast('/') }
        val seriesLink = document.select("a[href*='/comic/']")
            .firstOrNull { link -> isSeriesPath(link.attr("href")) || link.text().isNotBlank() }
        val mangaDetailPath = normalizeStoredPath(
            seriesLink?.attr("href").orEmpty().ifBlank { parentComicPath(normalizedPath) }
        )
        val mangaTitle = seriesLink?.text()?.trim().orEmpty()
            .ifBlank { mangaDetailPath.trim('/').substringAfterLast('/').replace('-', ' ') }
        val images = document.select("article img, .entry-content img, .wp-block-image img, .wp-caption img, img")
            .mapNotNullIndexed { index, image ->
                val imageUrl = firstNonBlank(
                    image.absUrl("src"),
                    image.attr("data-src"),
                    image.attr("data-lazy-src"),
                    image.attr("data-original"),
                    image.attr("src"),
                )
                if (imageUrl.isBlank() || !looksLikeImageUrl(imageUrl)) return@mapNotNullIndexed null
                ReaderPage(
                    id = imageUrl.substringAfterLast('/').substringBefore('?').ifBlank { "page-${index + 1}" },
                    numberLabel = (index + 1).toString(),
                    imageUrl = imageUrl.toAbsoluteUrl(),
                )
            }
            .distinctBy { it.imageUrl }
        val chapterLinks = fetchChapterList(mangaDetailPath).ifEmpty {
            parseChapterLinks(document, parentComicPath(normalizedPath))
        }
        val currentIndex = chapterLinks.indexOfFirst { sameChapterPath(id, it.path, normalizedPath) }
        val previousChapterPath = chapterLinks.getOrNull(currentIndex + 1)?.path
        val nextChapterPath = chapterLinks.getOrNull(currentIndex - 1)?.path

        return ReaderData(
            providerId = id,
            mangaTitle = mangaTitle,
            mangaDetailPath = mangaDetailPath,
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
                if (!referer.isNullOrBlank()) {
                    header("Referer", referer.toAbsoluteUrl())
                } else {
                    header("Referer", BASE_URL)
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun parseGenreSections(document: Document): List<HomeFeedSection> {
        return parseGenreLinks(document).mapNotNull { genre ->
            val genreDoc = getDocument(buildGenrePath(genre.slug, 1))
            val mangas = parseMangaCards(genreDoc).take(12)
            mangas.takeIf { it.isNotEmpty() }?.let {
                HomeFeedSection(
                    id = genre.slug,
                    title = genre.name,
                    type = HomeSectionType.MANGAS,
                    mangas = it,
                )
            }
        }
    }

    private fun parseGenreLinks(document: Document): List<GenreLink> {
        return document.select("a[href*='/genero/']")
            .mapNotNull { link ->
                val href = link.attr("href").trim()
                val slug = href.substringAfter("/genero/", "").trim('/').substringBefore('/').ifBlank { return@mapNotNull null }
                val name = link.text().trim()
                    .replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
                    .ifBlank { slug.replace('-', ' ').replaceFirstChar { it.uppercase() } }
                GenreLink(slug = slug, name = name, href = href)
            }
            .distinctBy { it.slug }
    }

    private fun parseMangaCards(document: Document): List<MangaSummary> {
        return document.select("div.page-item-detail.manga")
            .mapNotNull { card ->
                val titleLink = card.selectFirst(".post-title a[href*='/comic/']") ?: return@mapNotNull null
                val detailPath = normalizeStoredPath(titleLink.attr("href"))
                if (!isSeriesPath(detailPath)) return@mapNotNull null
                val title = titleLink.text().trim().ifBlank {
                    detailPath.trim('/').substringAfterLast('/').replace('-', ' ')
                }
                if (title.isBlank()) return@mapNotNull null
                val coverUrl = firstNonBlank(
                    card.selectFirst(".item-thumb img")?.attr("data-src").orEmpty(),
                    card.selectFirst(".item-thumb img")?.attr("data-lazy-src").orEmpty(),
                    card.selectFirst(".item-thumb img")?.attr("src").orEmpty(),
                )
                val chapters = parseChapterListFromCard(card, detailPath)
                val latestPublication = chapters.firstOrNull()?.chapterLabel
                    .orEmpty()
                    .ifBlank { card.selectFirst(".quick-chapter-link")?.text()?.trim().orEmpty() }
                val chaptersCount = chapters.size.takeIf { it > 0 }?.toString().orEmpty()
                val rating = card.selectFirst(".meta-item.rating .score, .post-total-rating .score")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                MangaSummary(
                    providerId = id,
                    title = title,
                    detailPath = detailPath,
                    coverUrl = coverUrl,
                    latestPublication = latestPublication,
                    chaptersCount = chaptersCount,
                    rating = rating,
                )
            }
            .distinctBy { it.detailPath }
    }

    private fun parseChapterList(document: Document, seriesPath: String): List<MangaChapter> {
        return parseChapterListFromCard(document.selectFirst("div.page-item-detail.manga"), seriesPath)
    }

    private fun fetchChapterList(seriesPath: String): List<MangaChapter> {
        val path = normalizeStoredPath(seriesPath).trim('/')
        if (path.isBlank()) return emptyList()
        val request = Request.Builder()
            .url("$BASE_URL/$path/ajax/chapters/")
            .header("User-Agent", USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", BASE_URL)
            .header("Referer", "$BASE_URL/$path/")
            .post(okhttp3.FormBody.Builder().build())
            .build()
        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return emptyList()
            }
            response.body?.string().orEmpty()
        }
        if (html.isBlank()) {
            return emptyList()
        }
        val document = Jsoup.parse(html, BASE_URL)
        return document.select("ul.main.version-chap .wp-manga-chapter, .listing-chapters_wrap .chapter-item, .listing-chapters_wrap .wp-manga-chapter")
            .mapNotNull { item ->
                val link = item.selectFirst("a[href*='/comic/']") ?: return@mapNotNull null
                val chapterPath = normalizeStoredPath(link.attr("href"))
                val chapterLabel = link.text().trim().ifBlank {
                    chapterPath.substringAfterLast('/').replace('-', ' ')
                }
                val releaseDate = item.selectFirst(".chapter-release-date i, .chapter-release-date, .post-on i")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                MangaChapter(
                    id = chapterPath.substringAfterLast('/'),
                    chapterLabel = chapterLabel,
                    chapterNumberUrl = chapterPath.substringAfterLast('/'),
                    path = chapterPath,
                    pagesCount = 0,
                    registrationDate = releaseDate,
                )
            }
            .distinctBy { it.path }
    }

    private fun parseChapterListFromCard(card: Element?, seriesPath: String): List<MangaChapter> {
        if (card == null) return emptyList()
        val seriesNormalized = normalizeStoredPath(seriesPath).substringBefore('?').trim('/')
        return card.select(".list-chapter .chapter-item .chapter a[href*='/comic/']")
            .mapNotNull { link ->
                val href = normalizeStoredPath(link.attr("href"))
                val normalizedHref = href.substringBefore('?').trim('/')
                if (!normalizedHref.startsWith(seriesNormalized) || normalizedHref == seriesNormalized) return@mapNotNull null
                MangaChapter(
                    id = normalizedHref.substringAfterLast('/'),
                    chapterLabel = link.text().trim().ifBlank { normalizedHref.substringAfterLast('/').replace('-', ' ') },
                    chapterNumberUrl = normalizedHref.substringAfterLast('/'),
                    path = href,
                    pagesCount = 0,
                    registrationDate = "",
                )
            }
            .distinctBy { it.path }
    }

    private fun parseChapterLinks(document: Document, seriesPath: String): List<MangaChapter> {
        return parseChapterList(document, seriesPath)
            .sortedByDescending { chapterSortKey(it.chapterNumberUrl) }
    }

    private fun buildGenrePath(slug: String, page: Int): String {
        val normalizedSlug = slug.trim('/').lowercase()
        return when {
            page <= 1 -> "/genero/$normalizedSlug/"
            else -> "/genero/$normalizedSlug/page/$page/"
        }
    }

    private fun buildArchivePath(sortBy: String, page: Int): String {
        val order = sortBy.takeIf(String::isNotBlank)?.let(::mapSortOrder).orEmpty()
        val base = buildString {
            append("/comic/")
            if (order.isNotBlank()) {
                append("?m_orderby=")
                append(order)
            }
        }
        return when {
            page <= 1 -> base
            base.contains('?') -> "$base&page=$page"
            else -> "$base?page=$page"
        }
    }

    private fun buildSearchPath(query: String, sortBy: String, page: Int): String {
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val order = sortBy.takeIf(String::isNotBlank)?.let(::mapSortOrder).orEmpty()
        val base = buildString {
            append("/comic/?s=")
            append(encodedQuery)
            if (order.isNotBlank()) {
                append("&m_orderby=")
                append(order)
            }
        }
        return when {
            page <= 1 -> base
            else -> "$base&page=$page"
        }
    }

    private fun mapSortOrder(sortBy: String): String {
        return when (sortBy.lowercase()) {
            "alphabetical", "alphabet", "a-z" -> "alphabet"
            "rating", "ratings" -> "ratings"
            "trending" -> "trending"
            "views", "popular" -> "views"
            "new", "latest" -> "latest"
            else -> "latest"
        }
    }

    private fun getDocument(path: String): Document {
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            val html = response.body?.string().orEmpty()
            return Jsoup.parse(html, BASE_URL)
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.selectFirst("a.next.page-numbers, a.page-numbers.next, .nav-links a.next, .pagination a.next") != null
    }

    private fun extractMetaValue(document: Document, vararg labels: String): String {
        val entries = document.select("strong, b, span, p")
        for (element in entries) {
            val text = element.text().trim()
            if (labels.any { text.startsWith(it, ignoreCase = true) }) {
                val value = text.substringAfter(':').trim()
                if (value.isNotBlank()) return value
            }
        }
        return ""
    }

    private fun extractChapterCount(text: String): String {
        return Regex("Chapter\\s+([0-9]+(?:/[0-9]+)?)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun extractFirstRating(text: String): String {
        return Regex("\\b([0-5](?:\\.\\d)?)\\b")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun findFirstImage(scope: Element, selectors: List<String>): String {
        for (selector in selectors) {
            val image = scope.selectFirst(selector) ?: continue
            val url = firstNonBlank(
                image.absUrl("src"),
                image.absUrl("data-src"),
                image.absUrl("data-lazy-src"),
                image.attr("content"),
                image.attr("src"),
                image.attr("data-src"),
                image.attr("data-lazy-src"),
            )
            if (url.isNotBlank() && looksLikeImageUrl(url)) return url
        }
        return ""
    }

    private fun ancestorElement(start: Element, vararg queries: String): Element? {
        val candidates = sequenceOf(start).plus(start.parents().asSequence())
        for (element in candidates) {
            val tag = element.tagName()
            val classAttr = element.className()
            if (queries.any { query ->
                    when {
                        query.startsWith('.') -> classAttr.contains(query.substringAfter('.'))
                        query.startsWith('#') -> element.id() == query.substringAfter('#')
                        else -> tag.equals(query, ignoreCase = true)
                    }
                }) {
                return element
            }
        }
        return null
    }

    private fun isSeriesPath(path: String): Boolean {
        val normalized = normalizeStoredPath(path).substringBefore('?').trim('/')
        val parts = normalized.split('/').filter { it.isNotBlank() }
        return parts.size == 2 && parts[0] == "comic"
    }

    private fun isChapterPathForSeries(path: String, seriesPath: String): Boolean {
        val normalized = normalizeStoredPath(path).substringBefore('?').trim('/')
        val seriesNormalized = normalizeStoredPath(seriesPath).substringBefore('?').trim('/')
        return normalized.startsWith("$seriesNormalized/") && normalized != seriesNormalized
    }

    private fun parentComicPath(path: String): String {
        val normalized = normalizeStoredPath(path).substringBefore('?').trim('/')
        val parts = normalized.split('/').filter { it.isNotBlank() }
        return when {
            parts.size >= 3 && parts[0] == "comic" -> "/comic/${parts[1]}/"
            parts.size >= 2 && parts[0] == "comic" -> "/comic/${parts[1]}/"
            else -> "/comic/"
        }
    }

    private fun chapterSortKey(value: String): Double {
        return Regex("\\d+(?:\\.\\d+)?")
            .find(value)
            ?.value
            ?.toDoubleOrNull()
            ?: Double.NEGATIVE_INFINITY
    }

    private fun firstNonBlank(vararg values: String): String {
        return values.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun looksLikeImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp") || lower.contains(".gif") || lower.contains("/wp-content/uploads/")
    }

    private fun String.toAbsoluteUrl(): String {
        val value = trim()
        return when {
            value.isBlank() -> BASE_URL
            value.startsWith("http://") || value.startsWith("https://") -> {
                if (value.startsWith("http://")) {
                    val host = Uri.parse(value).host.orEmpty().lowercase()
                    if (host == "marmota.me" || host.endsWith(".marmota.me")) {
                        value.replaceFirst("http://", "https://")
                    } else {
                        value
                    }
                } else {
                    value
                }
            }
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$BASE_URL$value"
            else -> "$BASE_URL/$value"
        }
    }

    private data class GenreLink(
        val slug: String,
        val name: String,
        val href: String,
    )

    companion object {
        const val PROVIDER_ID = "marmota-es"
        private const val BASE_URL = "https://marmota.me"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}

private inline fun <T, R> Iterable<T>.mapNotNullIndexed(transform: (index: Int, T) -> R?): List<R> {
    val destination = ArrayList<R>()
    var index = 0
    for (item in this) {
        val value = transform(index, item)
        if (value != null) {
            destination.add(value)
        }
        index += 1
    }
    return destination
}
