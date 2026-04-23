package com.paudinc.komastream.provider.providers

import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CatalogSearchResult
import com.paudinc.komastream.data.model.CategoryOption
import com.paudinc.komastream.data.model.ChapterSummary
import com.paudinc.komastream.data.model.FilterOption
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.HomeSectionPageResult
import com.paudinc.komastream.data.model.HomeFeedSection
import com.paudinc.komastream.data.model.HomeSectionType
import com.paudinc.komastream.data.model.MangaChapter
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.ReaderPage
import com.paudinc.komastream.provider.MangaProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale

class LeerMangaEspProvider : MangaProvider {
    override val id: String = "leermangaesp-es"
    override val displayName: String = "LeerMangaEsp"
    override val language: AppLanguage = AppLanguage.ES
    override val websiteUrl: String = "https://leermangaesp.net"
    override val logoUrl: String = "https://leermangaesp.net/static/mi_app_public/images/icon.282eadc55615.webp"

    private val baseUrl = "https://leermangaesp.net"
    private val imageBaseUrl = "https://images.leermangaesp.net/file/leermangaesp"
    private val client = OkHttpClient()

    override fun fetchHomeFeed(): HomeFeed {
        val homeDocument = getDocument("/")
        val featuredMangas = parseFeaturedMangas(homeDocument)
        val popularMangas = parseHomeMangaSection(homeDocument, "populares-grid")
        val latestUpdates = parseHomeChapterSection(homeDocument, "capitulos-recientes-grid")
        val latestAdded = parseHomeMangaSection(homeDocument, "ultimos-anadidos")
        val fallbackLatestUpdates = latestUpdates.ifEmpty {
            val latestItems = JSONArray(getText("/api/latest_chapters_with_dates/"))
            buildList(latestItems.length()) {
                for (index in 0 until latestItems.length()) {
                    add(latestItems.getJSONObject(index).toLatestChapterSummary())
                }
            }
        }
        val fallbackPopularMangas = popularMangas.ifEmpty {
            fetchCatalogPage(page = 1, query = "", categoryIds = emptyList(), take = 20).items
        }
        return HomeFeed(
            latestUpdates = fallbackLatestUpdates,
            popularChapters = fallbackLatestUpdates,
            popularMangas = fallbackPopularMangas,
            sections = listOf(
                HomeFeedSection(
                    id = "featured",
                    title = "Featured",
                    type = HomeSectionType.MANGAS,
                    mangas = featuredMangas,
                ),
                HomeFeedSection(
                    id = "populares",
                    title = "Populares",
                    type = HomeSectionType.MANGAS,
                    mangas = fallbackPopularMangas,
                ),
                HomeFeedSection(
                    id = "capitulos-recientes",
                    title = "Capítulos Recientes",
                    type = HomeSectionType.CHAPTERS,
                    chapters = fallbackLatestUpdates,
                ),
                HomeFeedSection(
                    id = "ultimos-anadidos",
                    title = "Ultimos Añadidos",
                    type = HomeSectionType.MANGAS,
                    mangas = latestAdded,
                ),
            ).filter { it.chapters.isNotEmpty() || it.mangas.isNotEmpty() },
        )
    }

    override fun fetchHomeSectionPage(sectionId: String, page: Int): HomeSectionPageResult? {
        if (page < 1) return null
        return when (sectionId) {
            "populares" -> {
                val result = fetchCatalogPage(page = page, query = "", categoryIds = emptyList(), take = HOME_SECTION_PAGE_SIZE)
                HomeSectionPageResult(
                    type = HomeSectionType.MANGAS,
                    mangas = result.items,
                    hasMore = result.hasMore,
                )
            }
            "capitulos-recientes" -> {
                val chapters = fetchLatestChapterPage(page = page, take = HOME_SECTION_PAGE_SIZE)
                HomeSectionPageResult(
                    type = HomeSectionType.CHAPTERS,
                    chapters = chapters,
                    hasMore = chapters.size >= HOME_SECTION_PAGE_SIZE,
                )
            }
            else -> null
        }
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        return CatalogFilterOptions(
            categories = listOf(
                CategoryOption("Manga", "Manga"),
                CategoryOption("Manhwa", "Manhwa"),
                CategoryOption("Manhua", "Manhua"),
                CategoryOption("Novela", "Novela"),
            ),
            sortOptions = emptyList<FilterOption>(),
            statusOptions = emptyList<FilterOption>(),
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
        val page = (skip / take.coerceAtLeast(1)) + 1
        return fetchCatalogPage(page = page, query = query, categoryIds = categoryIds, take = take)
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizePath(detailPath)
        val document = getDocument(normalizedPath)
        val title = document.selectFirst(".manga-title, h1")?.text()?.trim().orEmpty()
        val coverUrl = document.selectFirst(".manga-cover")?.absUrl("src").orEmpty()
        val infoBlocks = document.select(".manga-details-wrapper .info-block")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val status = infoBlocks.getOrNull(1).orEmpty()
        val alternateTitle = infoBlocks.firstOrNull().orEmpty()
        val demography = document.selectFirst(".demography")?.text()?.trim().orEmpty()
        val description = buildString {
            append(document.selectFirst(".synopsis")?.text()?.trim().orEmpty())
            if (alternateTitle.isNotBlank()) {
                if (isNotBlank()) append("\n\n")
                append(alternateTitle)
            }
        }.ifBlank { "No description" }

        return MangaDetail(
            providerId = id,
            identification = normalizedPath.trim('/').substringAfterLast('/'),
            title = title,
            detailPath = normalizedPath,
            coverUrl = coverUrl,
            bannerUrl = coverUrl,
            description = description,
            status = status,
            publicationDate = "",
            periodicity = demography,
            chapters = fetchAllChapters(normalizedPath),
        )
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        val normalizedPath = normalizePath(chapterPath)
        val document = getDocument(normalizedPath)
        val chapterTitle = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val mangaLink = document.select("a[href^=/manga/]")
            .firstOrNull { it.text().isNotBlank() && !it.text().contains("volver", ignoreCase = true) }
        val mangaDetailPath = normalizePath(mangaLink?.attr("href").orEmpty())
        val mangaTitle = mangaLink?.text()?.trim().orEmpty()
            .ifBlank { chapterTitle.substringBefore(" Capitulo").substringBefore(" Capítulo").trim() }
        val navigationLinks = document.select("a[href^=/leer-m/]")
            .map { it.attr("href").trim() }
            .distinct()
        val previousChapterPath = navigationLinks.firstOrNull {
            chapterValueFromPath(it) < chapterValueFromPath(normalizedPath)
        }?.let(::normalizePath)
        val nextChapterPath = navigationLinks.firstOrNull {
            chapterValueFromPath(it) > chapterValueFromPath(normalizedPath)
        }?.let(::normalizePath)

        val pages = document.select("img[alt*=Pagina], img[alt*=P\\u00e1gina], img[alt*=Manga]")
            .mapNotNull { image ->
                val imageUrl = image.absUrl("src").ifBlank { resolveAbsoluteUrl(image.attr("src")) }
                if (imageUrl.isBlank()) return@mapNotNull null
                val pageNumber = Regex("(\\d+)").find(image.attr("alt"))?.groupValues?.get(1).orEmpty()
                ReaderPage(
                    id = imageUrl.substringAfterLast('/').substringBefore('?'),
                    numberLabel = pageNumber.ifBlank { (1).toString() },
                    imageUrl = imageUrl,
                )
            }
            .distinctBy { it.imageUrl }

        return ReaderData(
            providerId = id,
            mangaTitle = mangaTitle,
            mangaDetailPath = mangaDetailPath,
            chapterTitle = chapterTitle,
            chapterPath = normalizedPath,
            previousChapterPath = previousChapterPath,
            nextChapterPath = nextChapterPath,
            pages = pages,
        )
    }

    override fun downloadBytes(url: String, referer: String?): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { if (referer != null) header("Referer", resolveAbsoluteUrl(referer)) }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun fetchCatalogPage(
        page: Int,
        query: String,
        categoryIds: List<String>,
        take: Int,
    ): CatalogSearchResult {
        val url = "$baseUrl/api/buscar_mangas/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", take.coerceAtLeast(1).toString())
        if (query.isNotBlank()) {
            url.addQueryParameter("query", query)
        }
        categoryIds.firstOrNull()?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("tipo", it) }
        val payload = JSONObject(getText(url.build().toString()))
        val results = payload.optJSONArray("resultados") ?: JSONArray()
        val items = buildList(results.length()) {
            for (index in 0 until results.length()) {
                add(results.getJSONObject(index).toCatalogSummary())
            }
        }
        return CatalogSearchResult(
            items = items,
            hasMore = payload.optInt("page", page) < payload.optInt("total_pages", page),
        )
    }

    private fun fetchLatestChapterPage(page: Int, take: Int): List<ChapterSummary> {
        val url = "$baseUrl/api/latest_chapters_with_dates/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", take.coerceAtLeast(1).toString())
            .build()
        val raw = getText(url.toString()).trim()
        if (raw.isBlank()) return emptyList()
        val items = when {
            raw.startsWith("[") -> JSONArray(raw)
            raw.startsWith("{") -> JSONObject(raw).optJSONArray("resultados")
                ?: JSONObject(raw).optJSONArray("results")
                ?: JSONObject(raw).optJSONArray("data")
                ?: JSONArray()
            else -> JSONArray()
        }
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                add(items.getJSONObject(index).toLatestChapterSummary())
            }
        }
    }

    private fun fetchAllChapters(detailPath: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var nextPath: String? = detailPath
        val seen = mutableSetOf<String>()

        while (!nextPath.isNullOrBlank() && seen.add(nextPath)) {
            val document = getDocument(nextPath)
            document.select(".chapter-card a.chapter-link[href]")
                .filterNot { it.attr("href") == "#" }
                .forEach { link ->
                    val path = normalizePath(link.attr("href"))
                    if (chapters.any { it.path == path }) return@forEach
                    val label = link.selectFirst(".chapter-title")?.text()?.trim()
                        ?: link.text().substringBefore('\n').trim()
                    val registrationDate = link.selectFirst(".chapter-date")?.text()?.trim()
                        ?: link.text().lines().drop(1).joinToString(" ").trim()
                    chapters.add(
                        MangaChapter(
                            id = path.trim('/').substringAfterLast('/'),
                            chapterLabel = label,
                            chapterNumberUrl = path.trim('/').substringAfterLast('/'),
                            path = path,
                            pagesCount = 0,
                            registrationDate = registrationDate,
                        )
                    )
                }
            nextPath = document.selectFirst("#more-link[href]")?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?.let { href ->
                    val current = detailPath.toHttpUrlOrNull() ?: "$baseUrl$detailPath".toHttpUrl()
                    current.resolve(href)?.encodedPath +
                        current.resolve(href)?.query?.let { "?$it" }.orEmpty()
                }
        }

        return chapters.sortedByDescending { chapterValueFromPath(it.path) }
    }

    private fun JSONObject.toCatalogSummary(): MangaSummary {
        val slug = optString("slug")
        val coverPath = optString("portada")
        val type = optString("tipo").replaceFirstChar { it.uppercase(Locale.ROOT) }
        val demography = optString("demografia").replaceFirstChar { it.uppercase(Locale.ROOT) }
        val latestChapter = optDouble("ultimo_capitulo", 0.0)
        return MangaSummary(
            providerId = id,
            title = optString("titulo"),
            detailPath = "/manga/$slug/",
            coverUrl = portadaUrl(coverPath),
            status = demography,
            periodicity = type,
            latestPublication = latestChapter.takeIf { it > 0.0 }?.let { "Cap. ${formatChapterNumber(it)}" }.orEmpty(),
        )
    }

    private fun JSONObject.toLatestChapterSummary(): ChapterSummary {
        val slug = optString("slug")
        val latestChapter = optDouble("ultimo_capitulo", 0.0)
        val chapterSegment = formatChapterPathSegment(latestChapter)
        return ChapterSummary(
            providerId = id,
            mangaTitle = optString("titulo"),
            chapterLabel = "Capitulo ${formatChapterNumber(latestChapter)}",
            chapterNumberUrl = chapterSegment,
            chapterId = chapterSegment,
            mangaPath = "/manga/$slug/",
            chapterPath = "/leer-m/$slug/$chapterSegment/",
            coverUrl = portadaUrl(optString("portada")),
            registrationLabel = optString("fecha_publicacion"),
        )
    }

    private fun parseFeaturedMangas(document: Document): List<MangaSummary> {
        return document.select(".carousel-container-principal .carousel .slide a[href]")
            .mapNotNull { link ->
                val detailPath = normalizePath(link.attr("href"))
                val title = link.selectFirst(".slide-content h3")?.text()?.trim().orEmpty()
                val coverUrl = link.selectFirst("img")?.let(::imageUrlFromElement).orEmpty()
                val demography = link.selectFirst(".demografia-tag")?.text()?.trim().orEmpty()
                val genres = link.select(".genre-tag")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() }
                    .take(4)
                    .joinToString(" · ")
                if (detailPath.isBlank() || title.isBlank()) return@mapNotNull null
                MangaSummary(
                    providerId = id,
                    title = title,
                    detailPath = detailPath,
                    coverUrl = coverUrl,
                    status = demography,
                    periodicity = genres,
                )
            }
            .distinctBy { it.detailPath }
    }

    private fun parseHomeMangaSection(document: Document, sectionId: String): List<MangaSummary> {
        return document.select("#$sectionId .manga-item").mapNotNull { item ->
            val link = item.selectFirst("a[href^=/manga/]") ?: return@mapNotNull null
            val detailPath = normalizePath(link.attr("href"))
            val title = item.selectFirst(".manga-title")?.text()?.trim().orEmpty()
            val coverUrl = item.selectFirst("img")?.let(::imageUrlFromElement).orEmpty()
            val demography = item.selectFirst(".manga-demografia")?.text()?.trim().orEmpty()
            val type = item.selectFirst(".manga-type")?.text()?.trim()?.replaceFirstChar { it.uppercase(Locale.ROOT) }.orEmpty()
            val latestPublication = item.selectFirst(".chapter-button")?.text()?.trim().orEmpty()
            if (detailPath.isBlank() || title.isBlank()) return@mapNotNull null
            MangaSummary(
                providerId = id,
                title = title,
                detailPath = detailPath,
                coverUrl = coverUrl,
                status = demography,
                periodicity = type,
                latestPublication = latestPublication,
            )
        }
    }

    private fun parseHomeChapterSection(document: Document, sectionId: String): List<ChapterSummary> {
        return document.select("#$sectionId .manga-item").mapNotNull { item ->
            val mangaLink = item.selectFirst("a[href^=/manga/]") ?: return@mapNotNull null
            val chapterLink = item.selectFirst(".chapter-button[href^=/leer-m/]") ?: return@mapNotNull null
            val mangaTitle = item.selectFirst(".manga-title")?.text()?.trim().orEmpty()
            val mangaPath = normalizePath(mangaLink.attr("href"))
            val chapterPath = normalizePath(chapterLink.attr("href"))
            val coverUrl = item.selectFirst("img")?.let(::imageUrlFromElement).orEmpty()
            val chapterLabel = chapterLink.text().trim()
            if (mangaTitle.isBlank() || mangaPath.isBlank() || chapterPath.isBlank()) return@mapNotNull null
            ChapterSummary(
                providerId = id,
                mangaTitle = mangaTitle,
                chapterLabel = chapterLabel,
                chapterNumberUrl = chapterPath.trim('/').substringAfterLast('/'),
                chapterId = chapterPath.trim('/').substringAfterLast('/'),
                mangaPath = mangaPath,
                chapterPath = chapterPath,
                coverUrl = coverUrl,
                registrationLabel = "",
            )
        }
    }

    private fun imageUrlFromElement(element: org.jsoup.nodes.Element): String {
        return element.absUrl("data-src")
            .ifBlank { resolveAbsoluteUrl(element.attr("data-src")) }
            .ifBlank { element.absUrl("src") }
            .ifBlank { resolveAbsoluteUrl(element.attr("src")) }
    }

    private fun portadaUrl(value: String): String {
        if (value.isBlank()) return ""
        return if (value.startsWith("http://") || value.startsWith("https://")) value
        else "$imageBaseUrl/${value.trimStart('/')}"
    }

    private fun getDocument(path: String): Document {
        val request = Request.Builder()
            .url(resolveAbsoluteUrl(path))
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            return Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        }
    }

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
        return if (path.startsWith("http://") || path.startsWith("https://")) path else "$baseUrl${normalizePath(path)}"
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        val parsed = path.toHttpUrlOrNull()
        return when {
            parsed != null -> parsed.encodedPath + parsed.query?.let { "?$it" }.orEmpty()
            path.startsWith("/") -> path
            else -> "/$path"
        }
    }

    private fun chapterValueFromPath(path: String): Double {
        return Regex("(\\d+(?:\\.\\d+)?)")
            .find(path.substringBeforeLast('/').substringAfterLast('/'))
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
            ?: Double.MIN_VALUE
    }

    private fun formatChapterNumber(value: Double): String {
        val whole = value.toLong()
        return if (value == whole.toDouble()) whole.toString() else value.toString().trimEnd('0').trimEnd('.')
    }

    private fun formatChapterPathSegment(value: Double): String {
        val whole = value.toLong()
        return if (value == whole.toDouble()) String.format(Locale.US, "%d.00", whole) else value.toString()
    }

    private companion object {
        private const val HOME_SECTION_PAGE_SIZE = 20
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
