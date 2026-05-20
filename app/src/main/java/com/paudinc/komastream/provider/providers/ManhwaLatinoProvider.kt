package com.paudinc.komastream.provider.providers

import android.util.Log
import android.webkit.CookieManager as WebkitCookieManager
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CatalogSearchResult
import com.paudinc.komastream.data.model.ChapterSummary
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
import com.paudinc.komastream.utils.chapterValue
import com.paudinc.komastream.utils.normalizeStoredPath
import com.paudinc.komastream.utils.sameChapterPath
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI

class ManhwaLatinoProvider : MangaProvider {
    companion object {
        const val PROVIDER_ID = "manhwa-latino-es"
        private const val TAG = "ManhwaLatinoProvider"
        private const val CLOUDFLARE_WAIT_TIMEOUT_MS = 60_000L
        private const val CLOUDFLARE_POLL_INTERVAL_MS = 500L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "Manhwa Latino"
    override val language: AppLanguage = AppLanguage.ES
    override val websiteUrl: String = "https://manhwa-latino.com"
    override val logoUrl: String = "https://zai.manhwa-latino.com/wp-content/uploads/2023/05/icon-l.png"

    private val baseUrl = websiteUrl
    private val webkitCookieManager = WebkitCookieManager.getInstance()
    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }
    private val cloudflareLock = Any()
    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()

    @Volatile
    var cloudflareReady: Boolean = false
        private set

    override fun fetchHomeFeed(): HomeFeed {
        val document = getDocument("/")
        val featuredMangas = parseFeaturedMangaCards(document)
        val cards = parseMangaCards(document)
        val mangas = cards.map { it.manga }
        val latestUpdates = cards.mapNotNull { card ->
            card.latestChapterPath?.toChapterSummary(card.manga.title, card.manga.detailPath, card.manga.coverUrl)
        }
        return HomeFeed(
            latestUpdates = latestUpdates,
            popularChapters = latestUpdates,
            popularMangas = mangas,
            sections = listOfNotNull(
                featuredMangas.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "featured",
                        title = "Featured",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
                latestUpdates.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "latest-updates",
                        title = "Latest Updates",
                        type = HomeSectionType.CHAPTERS,
                        chapters = it,
                    )
                },
            ),
        )
    }

    override fun fetchHomeSectionPage(sectionId: String, page: Int): HomeSectionPageResult? {
        if (page < 1) return null
        val document = getDocument(pagePath(page))
        val featuredMangas = parseFeaturedMangaCards(document)
        val cards = parseMangaCards(document)
        val mangas = cards.map { it.manga }
        val latestUpdates = cards.mapNotNull { card ->
            card.latestChapterPath?.toChapterSummary(card.manga.title, card.manga.detailPath, card.manga.coverUrl)
        }
        return when (sectionId) {
            "featured" -> HomeSectionPageResult(
                type = HomeSectionType.MANGAS,
                mangas = featuredMangas,
                hasMore = hasNextPage(document),
            )
            "latest-updates" -> HomeSectionPageResult(
                type = HomeSectionType.CHAPTERS,
                chapters = latestUpdates,
                hasMore = hasNextPage(document),
            )
            else -> null
        }
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        return CatalogFilterOptions(
            categories = emptyList(),
            sortOptions = emptyList(),
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
        val page = (skip / pageSize) + 1
        val localSkip = skip % pageSize
        Log.d(
            TAG,
            "searchCatalog: query='$query' skip=$skip take=$take page=$page localSkip=$localSkip sortBy='$sortBy' status='$broadcastStatus' onlyFavorites=$onlyFavorites categories=${categoryIds.joinToString()}"
        )
        val searchPath = buildSearchPath(query = query, page = page)
        Log.d(TAG, "searchCatalog: loading page=$page path='$searchPath'")
        val document = getDocument(searchPath)
        val pageItems = parseMangaCards(document).map { it.manga }
        val hasMore = hasNextPage(document)
        Log.d(
            TAG,
            "searchCatalog: page=$page parsed=${pageItems.size} hasMore=$hasMore titles=${pageItems.take(5).joinToString { it.title }}"
        )
        return CatalogSearchResult(
            items = pageItems.drop(localSkip).take(pageSize),
            hasMore = hasMore || localSkip + pageSize < pageItems.size,
        )
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizeStoredPath(detailPath)
        val slug = normalizedPath.trim('/').substringAfterLast('/').ifBlank {
            normalizedPath.trim('/').substringAfterLast('/')
        }
        val document = getDocument("/manga/$slug/")
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
            .ifBlank { document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty() }
            .ifBlank { slug.replace('-', ' ') }
        val coverUrl = listOf(
            document.selectFirst("#cover img")?.let(::imageUrlFromElement),
            document.selectFirst(".manga-info-pic img")?.let(::imageUrlFromElement),
            document.selectFirst(".summary_image img")?.let(::imageUrlFromElement),
            document.selectFirst(".summary_image a img")?.let(::imageUrlFromElement),
            document.selectFirst(".tab-summary .summary_image img")?.let(::imageUrlFromElement),
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("meta[property=og:image:secure_url]")?.attr("content"),
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val bannerUrl = coverUrl
        val description = listOf(
            document.selectFirst("meta[property=og:description]")?.attr("content")?.trim(),
            document.selectFirst("meta[name=description]")?.attr("content")?.trim(),
            document.selectFirst(".manga-excerpt")?.text()?.trim(),
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        val status = extractStatus(document)
        val publicationDate = document.selectFirst("meta[property=article:modified_time]")?.attr("content")?.trim().orEmpty()
        val chapters = fetchMangaChapters(document, normalizedPath)
        Log.d(
            TAG,
            "fetchMangaDetail: path='$normalizedPath' slug='$slug' title='$title' chapters=${chapters.size} cover='${coverUrl.take(120)}' banner='${bannerUrl.take(120)}'"
        )
        if (chapters.isEmpty()) {
            Log.w(TAG, "fetchMangaDetail: no chapters parsed for '$normalizedPath'")
        }
        return MangaDetail(
            providerId = id,
            identification = slug,
            title = title,
            detailPath = normalizedPath.ifBlank { "/manga/$slug/" },
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
        val document = getDocument(normalizedPath)
        val chapterTitle = document.selectFirst("#chapter-heading")?.text()?.trim().orEmpty()
            .ifBlank { document.title().trim() }
        val mangaDetailPath = document.selectFirst(".M-breadcrumb a[href*='/manga/']")?.attr("href")?.normalizePath().orEmpty()
            .ifBlank { normalizedPath.substringBeforeLast('/').substringBeforeLast('/').let { "$it/" } }
        val mangaTitle = document.selectFirst(".M-breadcrumb img")?.attr("alt")?.trim().orEmpty()
            .ifBlank { document.selectFirst(".M-breadcrumb a[href*='/manga/']")?.text()?.trim().orEmpty() }
            .ifBlank { chapterTitle.substringAfter(" - ").trim() }
        val chapterOptions = fetchMangaChapters(document, normalizedPath)
        val currentIndex = chapterOptions.indexOfFirst { sameChapterPath(id, it.path, normalizedPath) }
        val previousChapterPath = chapterOptions.getOrNull(currentIndex + 1)?.path
        val nextChapterPath = chapterOptions.getOrNull(currentIndex - 1)?.path
        Log.d(
            TAG,
            "fetchReaderData: path='$normalizedPath' title='$chapterTitle' mangaTitle='$mangaTitle' mangaDetailPath='$mangaDetailPath' chapterOptions=${chapterOptions.size} currentIndex=$currentIndex"
        )
        val pages = document.select(
            "#wp-manga-current-chap + div > p > img, " +
                ".wp-manga-chapter-img, " +
                "#readerarea > img, " +
                "#readerarea p > img:not([alt='1 2'],[alt='2 2']), " +
                "#readerarea img[alt$=jpg], " +
                "#readerarea.rdminimal > img, " +
                "#readerarea .gallery-item img[data-src], " +
                ".read-content > img[data-src], " +
                ".read-content > div.page-break > img[data-src], " +
                ".reading-content > p > img[alt][data-src], " +
                ".reading-content .blocks-gallery-item img[data-full-url], " +
                ".reading-detail > .page-chapter > img:not([style]), " +
                ".read-img > img, " +
                ".chapter-img, " +
                ".chapter-image-anchor + img",
        ).mapIndexedNotNull { index, image ->
            val imageUrl = listOf(
                image.absUrl("data-src"),
                image.attr("data-src"),
                image.absUrl("data-full-url"),
                image.attr("data-full-url"),
                image.absUrl("src"),
                image.attr("src"),
                image.attr("data-lazy-src"),
                image.attr("data-original"),
            ).firstOrNull { it.isNotBlank() }.orEmpty().trim()
            if (imageUrl.isBlank()) return@mapIndexedNotNull null
            ReaderPage(
                id = imageUrl.substringAfterLast('/').substringBefore('?').ifBlank { "page-${index + 1}" },
                numberLabel = (index + 1).toString(),
                imageUrl = imageUrl.toAbsoluteUrl(),
            )
        }.distinctBy { it.imageUrl }
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
        ensureCloudflareReady()
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

    override fun invalidateCaches() {
        synchronized(cloudflareLock) {
            cloudflareReady = false
        }
        runCatching {
            cookieManager.cookieStore.removeAll()
        }
        runCatching {
            webkitCookieManager.setAcceptCookie(true)
            webkitCookieManager.removeAllCookies(null)
            webkitCookieManager.flush()
        }
        Log.d(TAG, "Cleared Manhwa Latino Cloudflare state")
    }

    fun waitForCloudflareCookie(timeoutMs: Long = CLOUDFLARE_WAIT_TIMEOUT_MS): Boolean {
        if (cloudflareReady) return true
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        var lastSeenCookieHeader = ""
        while (System.currentTimeMillis() < deadlineMs) {
            if (markCloudflareReadyIfCookiesPresent()) {
                return true
            }
            val cookieHeader = snapshotWebkitCookies()
            lastSeenCookieHeader = cookieHeader
            Log.d(TAG, "Waiting for cf_clearance cookie; hasCfBm=${cookieHeader.contains("__cf_bm=")}")
            Thread.sleep(CLOUDFLARE_POLL_INTERVAL_MS)
        }
        Log.e(TAG, "Timed out waiting for cf_clearance cookie; lastCookies=${lastSeenCookieHeader.take(120)}")
        throw IllegalStateException("Cloudflare challenge was not fully solved before timeout")
    }

    fun markCloudflareReadyIfCookiesPresent(): Boolean {
        if (cloudflareReady) return true
        val cookieHeader = snapshotWebkitCookies()
        if (!cookieHeader.contains("cf_clearance=")) {
            return false
        }
        synchronized(cloudflareLock) {
            if (!cloudflareReady) {
                syncCookies(cookieHeader)
                cloudflareReady = true
                Log.d(TAG, "Cloudflare clearance cookie detected and synced")
            }
        }
        return true
    }

    private fun ensureCloudflareReady() {
        if (cloudflareReady) return
        waitForCloudflareCookie()
    }

    private fun snapshotWebkitCookies(): String {
        return runCatching {
            webkitCookieManager.setAcceptCookie(true)
            webkitCookieManager.flush()
            webkitCookieManager.getCookie(baseUrl).orEmpty()
        }.getOrElse { throwable ->
            Log.w(TAG, "Unable to read WebView cookies", throwable)
            ""
        }
    }

    private fun syncCookies(cookieHeader: String) {
        runCatching {
            val uri = URI(baseUrl)
            cookieManager.cookieStore.removeAll()
            cookieHeader.split(';')
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && it.contains('=') }
                .forEach { token ->
                    val parts = token.split('=', limit = 2)
                    val name = parts.getOrNull(0)?.trim().orEmpty()
                    val value = parts.getOrNull(1)?.trim().orEmpty()
                    if (name.isBlank() || value.isBlank()) return@forEach
                    cookieManager.cookieStore.add(
                        uri,
                        HttpCookie(name, value).apply {
                            domain = uri.host
                            path = "/"
                        },
                    )
                }
        }
    }

    private fun parseMangaCards(document: Document): List<ParsedMangaCard> {
        return buildList {
            document.select(".page-listing-item .page-item-detail").forEach { card ->
                parseArchiveMangaCard(card)?.let(::add)
            }
            document.select(".search-wrap .tab-content-wrap .c-tabs-item__content, .search-wrap .c-tabs-item__content").forEach { card ->
                parseSearchMangaCard(card)?.let(::add)
            }
        }.distinctBy { it.manga.detailPath }
    }

    private fun parseFeaturedMangaCards(document: Document): List<MangaSummary> {
        return document.select(".widget-manga-slider .slider__item")
            .mapNotNull { slide ->
                val titleLink = slide.selectFirst(".slider__content .post-title a[href*='/manga/'], .slider__thumb a[href*='/manga/']") ?: return@mapNotNull null
                val detailPath = titleLink.attr("href").normalizePath()
                val title = titleLink.text().trim().ifBlank {
                    detailPath.trim('/').substringAfterLast('/').replace('-', ' ')
                }
                val coverUrl = slide.selectFirst(".slider__thumb img")?.let { image ->
                    imageUrlFromElement(image)
                }.orEmpty()
                if (title.isBlank() || !detailPath.startsWith("/manga/")) return@mapNotNull null
                MangaSummary(
                    providerId = id,
                    title = title,
                    detailPath = detailPath,
                    coverUrl = coverUrl.toAbsoluteUrl(),
                )
            }
            .distinctBy { it.detailPath }
    }

    private fun parseArchiveMangaCard(card: Element): ParsedMangaCard? {
        val titleLink = card.selectFirst(".post-title a[href*='/manga/']") ?: return null
        return buildMangaCard(
            rootElement = card,
            titleLink = titleLink,
            coverSelectors = listOf(
                ".item-thumb img",
            ),
            latestChapterSelector = ".list-chapter .chapter a[href*='/manga/']",
            contentType = card.selectFirst(".manga-type")?.text()?.trim().orEmpty(),
            latestPublication = card.selectFirst(".list-chapter .chapter a")?.text()?.trim().orEmpty(),
            chaptersCount = card.select(".list-chapter .chapter").size.takeIf { it > 0 }?.toString().orEmpty(),
            rating = card.selectFirst(".score")?.text()?.trim().orEmpty(),
        )
    }

    private fun parseSearchMangaCard(card: Element): ParsedMangaCard? {
        val titleLink = card.selectFirst(".tab-summary .post-title a[href*='/manga/']") ?: return null
        val latestChapterPath = card.selectFirst(".tab-meta .latest-chap .chapter a[href*='/manga/']")?.attr("href")?.normalizePath()
        val contentType = card.selectFirst(".tab-thumb .translation_tag")?.text()?.trim().orEmpty()
        val latestPublication = card.selectFirst(".tab-meta .latest-chap .chapter a")?.text()?.trim().orEmpty()
        val chaptersCount = card.selectFirst(".tab-summary .mg_status .summary-content")?.text()?.trim().orEmpty()
        val rating = card.selectFirst(".tab-meta .score, .tab-meta .total_votes")?.text()?.trim().orEmpty()
        return buildMangaCard(
            rootElement = card,
            titleLink = titleLink,
            coverSelectors = listOf(
                ".tab-thumb img",
                ".item-thumb img",
            ),
            latestChapterSelector = ".tab-meta .latest-chap .chapter a[href*='/manga/']",
            contentType = contentType,
            latestPublication = latestPublication,
            chaptersCount = chaptersCount,
            rating = rating,
            latestChapterPathOverride = latestChapterPath,
        )
    }

    private fun buildMangaCard(
        rootElement: Element,
        titleLink: Element,
        coverSelectors: List<String>,
        latestChapterSelector: String,
        contentType: String,
        latestPublication: String,
        chaptersCount: String,
        rating: String,
        latestChapterPathOverride: String? = null,
    ): ParsedMangaCard? {
        val detailPath = titleLink.attr("href").normalizePath()
        val title = titleLink.text().trim().ifBlank {
            detailPath.trim('/').substringAfterLast('/').replace('-', ' ')
        }
        if (title.isBlank() || !detailPath.startsWith("/manga/")) return null
        val coverUrl = coverSelectors.asSequence()
            .mapNotNull { selector ->
                rootElement.selectFirst(selector)
            }
            .map { imageUrlFromElement(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val manga = MangaSummary(
            providerId = id,
            title = title,
            detailPath = detailPath,
            coverUrl = coverUrl.toAbsoluteUrl(),
            contentType = contentType,
            latestPublication = latestPublication,
            chaptersCount = chaptersCount,
            rating = rating,
        )
        return ParsedMangaCard(
            manga = manga,
            latestChapterPath = latestChapterPathOverride
                ?: rootElement.selectFirst(latestChapterSelector)?.attr("href")?.normalizePath(),
        )
    }

    private fun fetchMangaChapters(document: Document, detailPath: String): List<MangaChapter> {
        val chapterElements = selectChapterElements(document)
        Log.d(TAG, "fetchMangaChapters: detailPath='$detailPath' directSelectorCount=${chapterElements.size}")
        val parsedChapters = parseChapterElements(chapterElements)
        if (parsedChapters.isNotEmpty()) {
            Log.d(TAG, "fetchMangaChapters: parsed ${parsedChapters.size} chapters from direct selectors for '$detailPath'")
            return parsedChapters
        }

        val mangaId = extractMangaId(document, detailPath)
        Log.d(TAG, "fetchMangaChapters: no direct chapters for '$detailPath', extracted mangaId='$mangaId'")
        if (mangaId.isBlank()) return emptyList()

        val ajaxDocument = runCatching {
            val request = Request.Builder()
                .url("$baseUrl/wp-admin/admin-ajax.php?manga_id=$mangaId&action=chapter_list")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$baseUrl${normalizeStoredPath(detailPath).ifBlank { "/" }}")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "fetchMangaChapters: ajax response for mangaId='$mangaId' http=${response.code} contentType='${response.body?.contentType()}'")
                response.body?.string().orEmpty()
            }
        }.getOrElse {
            Log.w(TAG, "Failed to load chapter ajax for $detailPath", it)
            ""
        }

        if (ajaxDocument.isBlank()) {
            Log.w(TAG, "fetchMangaChapters: ajax chapter body was empty for '$detailPath' mangaId='$mangaId'")
            return emptyList()
        }
        val ajaxChapters = parseChapterElements(
            Jsoup.parseBodyFragment(ajaxDocument, baseUrl).select("*"),
        )
        Log.d(TAG, "fetchMangaChapters: parsed ${ajaxChapters.size} chapters from ajax for '$detailPath'")
        return ajaxChapters
    }

    private fun selectChapterElements(document: Document): org.jsoup.select.Elements {
        val selectors = listOf(
            "#tab-chapter-listing .listing-chapters_wrap ul.main.version-chap > li.wp-manga-chapter",
            ".page-content-listing.single-page .listing-chapters_wrap ul.main.version-chap > li.wp-manga-chapter",
            ".page-content-listing.single-page .listing-chapters_wrap ul.main.no-volumn.version-chap > li.wp-manga-chapter",
            ".listing-chapters_wrap ul.main.version-chap > li.wp-manga-chapter",
            ".listing-chapters_wrap li.wp-manga-chapter",
            ".manga-detailchapter .detail-chlist li",
            "#chapterlist li",
            ".chapter-list li",
            ".box-list-chapter li",
            ".row-content-chapter li",
            "#list_chapter_id_detail li",
            ".list-chapter .chapter",
            "select.single-chapter-select option[data-redirect]",
            ".page-content-listing.single-page .wp-manga-chapter",
        )
        for (selector in selectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                Log.d(TAG, "selectChapterElements: selector='$selector' count=${elements.size}")
                return elements
            }
        }
        Log.d(TAG, "selectChapterElements: no chapter elements matched")
        return org.jsoup.select.Elements()
    }

    private fun parseChapterElements(chapterElements: org.jsoup.select.Elements): List<MangaChapter> {
        val parsed = chapterElements.mapIndexedNotNull { index, element ->
            val chapterLinks = when {
                element.tagName().equals("a", ignoreCase = true) -> listOf(element)
                element.tagName().equals("option", ignoreCase = true) -> emptyList()
                else -> element.select("a[href], a[data-redirect]").toList()
            }
            val chapterPath = when {
                element.tagName().equals("option", ignoreCase = true) ->
                    element.attr("data-redirect").ifBlank { element.attr("value") }
                else -> sequenceOf(
                    element.attr("data-redirect"),
                    chapterLinks.firstOrNull()?.attr("data-redirect").orEmpty(),
                    chapterLinks.firstOrNull()?.attr("href").orEmpty(),
                    chapterLinks.firstOrNull()?.absUrl("href").orEmpty(),
                    chapterLinks.asSequence().map { it.attr("data-redirect").ifBlank { it.attr("href") } }
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty(),
                ).firstOrNull { it.isNotBlank() }.orEmpty()
            }
            val normalizedPath = chapterPath.normalizePath()
            val chapterLabel = listOfNotNull(
                element.selectFirst("h5, .chapternum, .chapter-name, .chapter-title")?.text()?.trim(),
                chapterLinks.firstOrNull()?.selectFirst("h4")?.text()?.trim(),
                chapterLinks.firstOrNull()?.text()?.trim(),
                element.selectFirst("h4")?.text()?.trim(),
                element.selectFirst(".mini-letters")?.text()?.trim(),
                element.selectFirst(".chapter-name")?.text()?.trim(),
                if (element.tagName().equals("option", ignoreCase = true)) element.text().trim() else null,
                element.text().trim(),
            ).firstOrNull { it.isNotBlank() }
                ?: normalizedPath.trim('/').substringAfterLast('/').replace('-', ' ')
            if (index < 3) {
                Log.d(
                    TAG,
                    buildString {
                        append("parseChapterElements: raw[")
                        append(index)
                        append("] tag=")
                        append(element.tagName())
                        append(" class='")
                        append(element.className())
                        append("' path='")
                        append(normalizedPath)
                        append("' label='")
                        append(chapterLabel)
                        append("' hrefs=")
                        append(element.select("a[href]").take(3).joinToString { it.attr("href") })
                    },
                )
            }
            if (normalizedPath.isBlank() || chapterLabel.isBlank()) return@mapIndexedNotNull null
            val chapterId = normalizedPath.trim('/').substringAfterLast('/')
            MangaChapter(
                id = chapterId,
                chapterLabel = chapterLabel,
                chapterNumberUrl = chapterId,
                path = normalizedPath,
                pagesCount = 0,
                registrationDate = "",
            )
        }
            .distinctBy { it.path }
            .sortedWith(
                compareByDescending<MangaChapter> { chapterValue(it) }
                    .thenByDescending { it.path },
            )
        if (parsed.isEmpty()) {
            Log.d(TAG, "parseChapterElements: no chapter nodes parsed from ${chapterElements.size} candidate elements")
        } else {
            val sample = parsed.take(5).joinToString { chapter ->
                "${chapter.chapterLabel} -> ${chapter.path}"
            }
            Log.d(
                TAG,
                "parseChapterElements: parsed ${parsed.size} chapters sample=$sample"
            )
        }
        return parsed
    }

    private fun extractMangaId(document: Document, detailPath: String): String {
        val candidates = listOfNotNull(
            document.selectFirst("[data-manga-id]")?.attr("data-manga-id")?.trim(),
            document.selectFirst(".chapter-list")?.attr("data-manga-id")?.trim(),
            document.selectFirst("#chapter-list")?.attr("data-manga-id")?.trim(),
            document.selectFirst(".manga-detailchapter")?.attr("data-manga-id")?.trim(),
            Regex("""data-manga-id=["'](\d+)["']""")
                .find(document.outerHtml())
                ?.groupValues
                ?.getOrNull(1)
                ?.trim(),
            Regex("""manga_id\s*=\s*["']?(\d+)["']?""")
                .find(document.outerHtml())
                ?.groupValues
                ?.getOrNull(1)
                ?.trim(),
            Regex("""manga_id\s*:\s*["']?(\d+)["']?""")
                .find(document.outerHtml())
                ?.groupValues
                ?.getOrNull(1)
                ?.trim(),
        )
        return candidates.firstOrNull { it.isNotBlank() }.orEmpty().ifBlank {
            detailPath.trim('/').substringAfterLast('/').takeIf { it.any(Char::isDigit) }.orEmpty()
        }
    }

    private fun imageUrlFromElement(element: Element): String {
        val candidates = listOf(
            element.attr("data-src"),
            element.attr("src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("data-srcset").substringBefore(','),
            element.attr("srcset").substringBefore(','),
        )
        return candidates.asSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun String.toChapterSummary(mangaTitle: String, mangaPath: String, coverUrl: String): com.paudinc.komastream.data.model.ChapterSummary? {
        val chapterPath = normalizeStoredPath(this)
        val chapterLabel = chapterPath.trim('/').substringAfterLast('/').replace('-', ' ').capitalizeChapterLabel()
        if (chapterPath.isBlank()) return null
        return com.paudinc.komastream.data.model.ChapterSummary(
            providerId = id,
            mangaTitle = mangaTitle,
            chapterLabel = chapterLabel,
            chapterNumberUrl = chapterPath.trim('/').substringAfterLast('/'),
            chapterId = chapterPath.trim('/').substringAfterLast('/'),
            mangaPath = mangaPath,
            chapterPath = chapterPath,
            coverUrl = coverUrl,
        )
    }

    private fun extractStatus(document: Document): String {
        val candidates = document.select(".post-status .summary-content, .summary_content .post-status, .post-status")
            .map { it.text().trim() }
        return candidates.firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun buildSearchPath(query: String, page: Int): String {
        val encodedQuery = query.trim().takeIf { it.isNotBlank() }?.let { java.net.URLEncoder.encode(it, Charsets.UTF_8.name()) }
        val path = when {
            encodedQuery == null && page <= 1 -> "/"
            encodedQuery == null -> "/page/$page/"
            page <= 1 -> "/?s=$encodedQuery&post_type=wp-manga"
            else -> "/page/$page/?s=$encodedQuery&post_type=wp-manga"
        }
        return "$baseUrl$path"
    }

    private fun pagePath(page: Int): String {
        return if (page <= 1) "/" else "/page/$page/"
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.selectFirst(".wp-pagenavi a.nextpostslink, .wp-pagenavi a.next, a.nextpostslink, a.next") != null
    }

    private fun getDocument(path: String): Document {
        ensureCloudflareReady()
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        client.newCall(request).execute().use { response ->
            return Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        }
    }

    private fun String.normalizePath(): String {
        return normalizeStoredPath(this)
    }

    private fun String.toAbsoluteUrl(): String {
        val trimmed = trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "$baseUrl${if (trimmed.startsWith("/")) trimmed else "/$trimmed"}"
        }
    }

    private fun String.capitalizeChapterLabel(): String {
        return split(' ')
            .joinToString(" ") { token ->
                token.lowercase().replaceFirstChar { it.uppercase() }
            }
    }

    private data class ParsedMangaCard(
        val manga: MangaSummary,
        val latestChapterPath: String?,
    )
}
