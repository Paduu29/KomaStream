package com.paudinc.komastream

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.CookieManager
import java.net.CookiePolicy

class InMangaService {
    private val baseUrl = "https://inmanga.com"
    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }
    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()

    fun fetchHomeFeed(): HomeFeed {
        ensureSession()
        val latest = parseChapterCards(getText("/chapter/getRecentChapters", "/", ajax = true))
        val popularChapters = parseChapterCards(getText("/chapter/getMostViewedChapters", "/", ajax = true))
        val popularMangas = parsePopularMangas(getText("/manga/getMostViewedMangas", "/", ajax = true))
        return HomeFeed(latest, popularChapters, popularMangas)
    }

    fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        ensureSession()
        val document = getDocument("/manga/consult", referer = "/", userAgent = DESKTOP_USER_AGENT)
        val categoryItems = linkedMapOf<String, String>()

        document.select("input[type=checkbox][value]").forEach { input ->
            val id = input.attr("value").trim()
            val context = buildString {
                append(input.id()).append(' ')
                append(input.attr("name")).append(' ')
                append(input.parent()?.text().orEmpty()).append(' ')
                append(input.parent()?.parent()?.text().orEmpty())
            }.lowercase()
            val label = extractCheckboxLabel(document, input)
            val normalizedLabel = label.lowercase()
            val looksLikeGenre = "gener" in context || "genre" in context || "género" in context || "genero" in context
            val shouldSkip = id.isBlank() ||
                id == "-1" ||
                label.isBlank() ||
                "favorit" in context ||
                "favorit" in normalizedLabel ||
                normalizedLabel == "todos"
            if (!shouldSkip && (looksLikeGenre || label.length > 2)) {
                categoryItems.putIfAbsent(id, label)
            }
        }

        var sortOptions = emptyList<FilterOption>()
        var statusOptions = emptyList<FilterOption>()
        document.select("select").forEach { select ->
            val options = select.select("option[value]").mapNotNull { option ->
                val id = option.attr("value").trim()
                val label = option.text().trim()
                if (id.isBlank() || label.isBlank()) null else FilterOption(id, label)
            }
            val context = buildString {
                append(select.id()).append(' ')
                append(select.attr("name")).append(' ')
                append(select.parent()?.text().orEmpty()).append(' ')
                append(select.previousElementSibling()?.text().orEmpty())
            }.lowercase()
            when {
                "sort" in context || "orden" in context || "clasific" in context -> sortOptions = options
                "estado" in context || "broadcast" in context || "status" in context -> statusOptions = options
            }
        }

        return CatalogFilterOptions(
            categories = categoryItems.map { (id, name) -> CategoryOption(id = id, name = name) },
            sortOptions = sortOptions,
            statusOptions = statusOptions,
        )
    }

    fun searchCatalog(
        query: String,
        categoryIds: List<String>,
        sortBy: String,
        broadcastStatus: String,
        onlyFavorites: Boolean,
        skip: Int = 0,
        take: Int = 10
    ): CatalogSearchResult {
        ensureSession()
        val formValues = mutableListOf(
            "filter[queryString]" to query,
            "filter[skip]" to skip.toString(),
            "filter[take]" to take.toString(),
            "filter[sortby]" to sortBy,
            "filter[broadcastStatus]" to broadcastStatus,
            "filter[onlyFavorites]" to onlyFavorites.toString(),
            "d" to "",
        )
        if (categoryIds.isEmpty()) {
            formValues += "filter[generes][]" to "-1"
        } else {
            categoryIds.forEach { id ->
                formValues += "filter[generes][]" to id
            }
        }
        val html = postText(
            path = "/manga/getMangasConsultResult",
            referer = "/manga/consult",
            formValues = formValues,
            userAgent = DESKTOP_USER_AGENT,
        )
        val items = parseCatalogResults(html)
        return CatalogSearchResult(
            items = items,
            hasMore = items.size >= take,
        )
    }

    fun fetchMangaDetail(detailPath: String): MangaDetail {
        ensureSession()
        val document = getDocument(detailPath, referer = "/")
        val identification = document.selectFirst("#Identification")?.`val`().orEmpty()
        val stats = document.select(".list-group-item")
        return MangaDetail(
            identification = identification,
            title = document.selectFirst("h1")?.text().orEmpty(),
            detailPath = detailPath.normalizePath(),
            coverUrl = document.select(".custom-bg-center img").attr("abs:src"),
            bannerUrl = document.select("img[src^=/cover/manga]").attr("abs:src")
                .ifBlank { document.select(".custom-bg-center img").attr("abs:src") },
            description = document.select(".panel-body").getOrNull(1)?.text().orEmpty(),
            status = stats.getOrNull(0)?.selectFirst(".label")?.text().orEmpty(),
            publicationDate = stats.getOrNull(1)?.selectFirst(".label")?.text().orEmpty(),
            periodicity = stats.getOrNull(2)?.selectFirst(".label")?.text().orEmpty(),
            chapters = parseMangaChapters(
                getText("/chapter/getall?mangaIdentification=$identification", detailPath, ajax = true)
            ),
        )
    }

    fun fetchReaderData(chapterPath: String): ReaderData {
        ensureSession()
        val chapterDocument = getDocument(chapterPath, referer = "/")
        val scriptText = chapterDocument.select("script").joinToString("\n") { it.html() }
        val pageTemplate = Regex("var pu = '([^']+)'").find(scriptText)?.groupValues?.get(1).orEmpty()
        val chapterId = Regex("var cid = '([^']+)'").find(scriptText)?.groupValues?.get(1).orEmpty()
        val chapterTitle = chapterDocument.selectFirst(".ChapterDescriptionContainer h1")?.text().orEmpty()
        val mangaTitle = chapterTitle.substringBefore("Capítulo").trim()
        val controls = getDocument("/chapter/chapterIndexControls?identification=$chapterId", referer = chapterPath)
        val mangaDetailPath = controls.select(".chapterControlsContainer a.blue").attr("href").normalizePath()
        val chapterOptions = controls.select("#ChapList option")
        val selectedIndex = chapterOptions.indexOfFirst { it.hasAttr("selected") }
        val currentChapterValue = chapterOptions.getOrNull(selectedIndex)?.let { option ->
            parseChapterNumber(option.text())
        }
        val chapterEntries = chapterOptions.mapIndexedNotNull { index, option ->
            val chapterValue = parseChapterNumber(option.text()) ?: return@mapIndexedNotNull null
            Triple(index, option, chapterValue)
        }
        val previousChapterPath = currentChapterValue?.let { currentValue ->
            chapterEntries
                .filter { (_, _, value) -> value < currentValue }
                .maxByOrNull { (_, _, value) -> value }
                ?.second
                ?.let { buildChapterPath(mangaDetailPath, it.text(), it.attr("value")) }
        } ?: chapterOptions.getOrNull(selectedIndex - 1)?.let {
            buildChapterPath(mangaDetailPath, it.text(), it.attr("value"))
        }
        val nextChapterPath = currentChapterValue?.let { currentValue ->
            chapterEntries
                .filter { (_, _, value) -> value > currentValue }
                .minByOrNull { (_, _, value) -> value }
                ?.second
                ?.let { buildChapterPath(mangaDetailPath, it.text(), it.attr("value")) }
        } ?: chapterOptions.getOrNull(selectedIndex + 1)?.let {
            buildChapterPath(mangaDetailPath, it.text(), it.attr("value"))
        }
        val pages = controls.select("#PageList option").map { option ->
            ReaderPage(
                id = option.attr("value"),
                numberLabel = option.text(),
                imageUrl = pageTemplate
                    .replace("identification", option.attr("value"))
                    .replace("pageNumber", option.text())
                    .toAbsoluteUrl(),
            )
        }
        return ReaderData(
            mangaTitle = mangaTitle,
            mangaDetailPath = mangaDetailPath,
            chapterTitle = chapterTitle,
            chapterPath = chapterPath.normalizePath(),
            previousChapterPath = previousChapterPath,
            nextChapterPath = nextChapterPath,
            pages = pages,
        )
    }

    fun downloadBytes(url: String, referer: String? = null): ByteArray {
        ensureSession()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_USER_AGENT)
            .apply { if (referer != null) header("Referer", referer.toAbsoluteUrl()) }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun ensureSession() {
        getDocument("/")
    }

    private fun getDocument(path: String, referer: String? = null, userAgent: String = MOBILE_USER_AGENT): Document {
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", userAgent)
            .apply { if (referer != null) header("Referer", referer.toAbsoluteUrl()) }
            .build()
        client.newCall(request).execute().use { response ->
            return Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        }
    }

    private fun getText(path: String, referer: String? = null, ajax: Boolean = false, userAgent: String = MOBILE_USER_AGENT): String {
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", userAgent)
            .apply {
                if (referer != null) header("Referer", referer.toAbsoluteUrl())
                if (ajax) header("X-Requested-With", "XMLHttpRequest")
            }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    private fun postText(path: String, formValues: List<Pair<String, String>>, referer: String, userAgent: String = MOBILE_USER_AGENT): String {
        val body = FormBody.Builder().apply {
            formValues.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .post(body)
            .header("User-Agent", userAgent)
            .header("Referer", referer.toAbsoluteUrl())
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    private fun parsePopularMangas(html: String): List<MangaSummary> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a.list-group-item").map { anchor ->
            MangaSummary(
                title = anchor.selectFirst(".media-box-heading")?.text()?.trim().orEmpty(),
                detailPath = anchor.attr("href").normalizePath(),
                coverUrl = anchor.selectFirst("img")?.attr("abs:src").orEmpty(),
                views = anchor.selectFirst(".label-success")?.text()?.trim().orEmpty(),
            )
        }
    }

    private fun parseChapterCards(html: String): List<ChapterSummary> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a[href^=/ver/manga/]").distinctBy { it.attr("href") }.map { anchor ->
            val chapterPath = anchor.attr("href").normalizePath()
            val parts = chapterPath.trim('/').split("/")
            ChapterSummary(
                mangaTitle = anchor.selectFirst("strong")?.text()?.trim().orEmpty(),
                chapterLabel = anchor.selectFirst("small strong, .recent-chapter-container-footer strong")?.text().orEmpty(),
                chapterNumberUrl = parts.getOrNull(3).orEmpty(),
                chapterId = parts.getOrNull(4).orEmpty(),
                mangaPath = parts.take(3).joinToString("/", prefix = "/"),
                chapterPath = chapterPath,
                coverUrl = anchor.selectFirst("img")?.attr("abs:src").orEmpty(),
                registrationLabel = anchor.selectFirst("[data-registrationdate]")?.attr("data-registrationdate").orEmpty(),
            )
        }
    }

    private fun parseCatalogResults(html: String): List<MangaSummary> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("a.manga-result").map { anchor ->
            val items = anchor.select(".list-group-item")
            MangaSummary(
                title = anchor.selectFirst("h4.list-group-item")?.ownText()?.trim().orEmpty(),
                detailPath = anchor.attr("href").normalizePath(),
                coverUrl = anchor.selectFirst("img")?.attr("data-src")?.toAbsoluteUrl().orEmpty(),
                status = items.getOrNull(0)?.selectFirst(".label")?.text().orEmpty(),
                latestPublication = items.getOrNull(1)?.selectFirst(".label")?.text().orEmpty(),
                periodicity = items.getOrNull(2)?.selectFirst(".label")?.text().orEmpty(),
                chaptersCount = items.getOrNull(3)?.selectFirst(".label")?.text().orEmpty(),
            )
        }
    }

    private fun extractCheckboxLabel(document: Document, input: org.jsoup.nodes.Element): String {
        val explicitLabel = input.id().takeIf { it.isNotBlank() }
            ?.let { document.selectFirst("label[for=$it]")?.text()?.trim() }
            .orEmpty()
        if (explicitLabel.isNotBlank()) return explicitLabel

        val parentLabel = input.parents().firstOrNull { it.tagName() == "label" }?.text()?.trim().orEmpty()
        if (parentLabel.isNotBlank()) return parentLabel

        val siblingText = sequenceOf(
            input.nextSibling()?.outerHtml()?.trim().orEmpty(),
            input.nextElementSibling()?.text()?.trim().orEmpty(),
            input.parent()?.ownText()?.trim().orEmpty(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        return Jsoup.parse(siblingText).text().trim()
    }

    private fun parseMangaChapters(json: String): List<MangaChapter> {
        val payload = JSONObject(json)
        val inner = JSONObject(payload.getString("data"))
        val result = inner.getJSONArray("result")
        return buildList(result.length()) {
            for (index in 0 until result.length()) {
                val item = result.getJSONObject(index)
                add(
                    MangaChapter(
                        id = item.getString("Identification"),
                        chapterLabel = item.getString("FriendlyChapterNumber"),
                        chapterNumberUrl = item.getString("FriendlyChapterNumberUrl"),
                        pagesCount = item.getInt("PagesCount"),
                        registrationDate = item.getString("RegistrationDate"),
                    )
                )
            }
        }.sortedByDescending {
            parseChapterNumber(it.chapterNumberUrl)
                ?: parseChapterNumber(it.chapterLabel)
                ?: Double.NEGATIVE_INFINITY
        }
    }

    private fun buildChapterPath(mangaDetailPath: String, chapterLabel: String, chapterId: String): String {
        val chapterNumberUrl = chapterLabel.replace(",", "").trim()
        return "${mangaDetailPath.substringBeforeLast("/")}/$chapterNumberUrl/$chapterId".normalizePath()
    }

    private fun parseChapterNumber(raw: String): Double? {
        val normalized = raw
            .trim()
            .replace(',', '.')
            .replace(Regex("(?<=\\d)-(?=\\d)"), ".")
        return Regex("\\d+(?:\\.\\d+)?")
            .find(normalized)
            ?.value
            ?.toDoubleOrNull()
    }

    private fun String.toAbsoluteUrl(): String {
        if (startsWith("http://") || startsWith("https://")) return this
        return baseUrl.toHttpUrl().resolve(this)?.toString() ?: "$baseUrl$this"
    }

    private fun String.normalizePath(): String {
        return when {
            startsWith("http://") || startsWith("https://") -> toHttpUrl().encodedPath
            startsWith("/") -> this
            else -> "/$this"
        }
    }

    companion object {
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
