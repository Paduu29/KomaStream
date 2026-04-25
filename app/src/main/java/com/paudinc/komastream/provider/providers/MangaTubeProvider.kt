package com.paudinc.komastream.provider.providers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager as WebkitCookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MangaTubeProvider(
    context: Context? = null,
) : MangaProvider {
    override val id: String = "mangatube-de"
    override val displayName: String = "Manga-Tube"
    override val language: AppLanguage = AppLanguage.DE
    override val websiteUrl: String = "https://manga-tube.me"
    override val logoUrl: String = "https://manga-tube.me/assets/img/MangaTubeLogo.png"

    private val baseUrl = "https://manga-tube.me"
    private val appContext = context?.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webkitCookieManager = WebkitCookieManager.getInstance()
    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }
    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()

    @Volatile
    private var csrfToken: String? = null

    override fun fetchHomeFeed(): HomeFeed {
        ensureSession()
        val homeDocument = getDocument("/")
        val homeHtml = homeDocument.outerHtml()
        val routeData = extractRouteData(homeHtml)
        logHtmlPreview("fetchHomeFeed:okhttpHome", homeHtml)
        Log.d(TAG, "fetchHomeFeed: routeData=${routeData != null}")
        var latestUpdates = routeData?.optJSONArray("published").toHomeChapterSummaries()
            .ifEmpty {
                runCatching { fetchLatestUpdates(offset = 0) }
                    .getOrDefault(emptyList())
            }
            .ifEmpty { parseLatestUpdatesFromHtml(homeDocument) }
        var popularMangas = routeData?.optJSONArray("top-manga").toMangaSummaries()
            .ifEmpty { runCatching { fetchTopManga() }.getOrDefault(emptyList()) }
        var newMangas = routeData?.optJSONArray("new-manga").toMangaSummaries()
            .ifEmpty { runCatching { fetchNewManga() }.getOrDefault(emptyList()) }

        if (latestUpdates.isEmpty() && popularMangas.isEmpty() && newMangas.isEmpty()) {
            val renderedDocument = getRenderedDocument("/")
            if (renderedDocument != null) {
                logHtmlPreview("fetchHomeFeed:webViewHome", renderedDocument.outerHtml())
                val renderedRouteData = extractRouteData(renderedDocument.outerHtml())
                Log.d(TAG, "fetchHomeFeed: renderedRouteData=${renderedRouteData != null}")
                latestUpdates = renderedRouteData?.optJSONArray("published").toHomeChapterSummaries()
                    .ifEmpty { parseLatestUpdatesFromHtml(renderedDocument) }
                popularMangas = renderedRouteData?.optJSONArray("top-manga").toMangaSummaries()
                    .ifEmpty { runCatching { fetchTopManga() }.getOrDefault(emptyList()) }
                    .ifEmpty { parsePopularMangasFromHtml(renderedDocument) }
                newMangas = renderedRouteData?.optJSONArray("new-manga").toMangaSummaries()
                    .ifEmpty { runCatching { fetchNewManga() }.getOrDefault(emptyList()) }
                    .ifEmpty { parseNewMangasFromHtml(renderedDocument) }
            }
        }
        Log.d(
            TAG,
            "fetchHomeFeed: latest=${latestUpdates.size} popular=${popularMangas.size} new=${newMangas.size}"
        )
        val sections = listOf(
            HomeFeedSection(
                id = "new-on-mangatube",
                title = "Neu auf Manga-Tube",
                type = HomeSectionType.MANGAS,
                mangas = newMangas,
            ),
            HomeFeedSection(
                id = "latest-updates",
                title = "Kürzlich hinzugefügt",
                type = HomeSectionType.CHAPTERS,
                chapters = latestUpdates,
            ),
            HomeFeedSection(
                id = "popular-mangas",
                title = "Beliebte Manga",
                type = HomeSectionType.MANGAS,
                mangas = popularMangas,
            ),
        ).filter { it.chapters.isNotEmpty() || it.mangas.isNotEmpty() }
        return HomeFeed(
            latestUpdates = latestUpdates,
            popularChapters = latestUpdates,
            popularMangas = popularMangas,
            sections = sections,
        )
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        ensureSession()
        val document = getDocument("/")
        val routeData = extractRouteData(document.outerHtml())
        val categories = routeData?.optJSONArray("genre-map").toCategoryOptions()
        return CatalogFilterOptions(
            categories = categories,
            sortOptions = emptyList<FilterOption>(),
            statusOptions = emptyList<FilterOption>(),
        )
    }

    override fun fetchHomeSectionPage(sectionId: String, page: Int): HomeSectionPageResult? {
        if (page < 1) return null
        return when (sectionId) {
            "latest-updates" -> {
                val offset = (page - 1) * HOME_UPDATES_PAGE_SIZE
                val chapters = fetchLatestUpdates(offset)
                HomeSectionPageResult(
                    type = HomeSectionType.CHAPTERS,
                    chapters = chapters,
                    hasMore = chapters.size >= HOME_UPDATES_PAGE_SIZE,
                )
            }
            else -> null
        }
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
        ensureSession()
        if (query.isNotBlank()) {
            val response = runCatching {
                getJson(
                    "/api/manga/quick-search".toAbsoluteUrl().toHttpUrl().newBuilder()
                        .addQueryParameter("query", query)
                        .build()
                        .toString()
                )
            }.getOrNull()
            if (response == null) {
                return CatalogSearchResult(items = emptyList(), hasMore = false)
            }
            val items = response.toMangaSummaries()
            return CatalogSearchResult(
                items = items.drop(skip).take(take),
                hasMore = response.optInt("to", items.size) < response.optInt("total", items.size),
            )
        }
        val pageSize = take.coerceAtLeast(1)
        val page = (skip / pageSize) + 1
        val urlBuilder = "/api/manga/search".toAbsoluteUrl().toHttpUrl().newBuilder()
            .addQueryParameter("query", "")
            .addQueryParameter("genre", categoryIds.firstOrNull().orEmpty())
            .addQueryParameter("status", broadcastStatus)
            .addQueryParameter("year", "")
            .addQueryParameter("type", "")
            .addQueryParameter("mature", "")
            .addQueryParameter("artist", "")
            .addQueryParameter("author", "")
            .addQueryParameter("page", page.toString())
        sortBy.takeIf { it.isNotBlank() }?.let { urlBuilder.addQueryParameter("sort", it) }
        val response = getJson(urlBuilder.build().toString())
        val items = response.optJSONArray("data").toMangaSummaries()
        val pagination = response.optJSONObject("pagination")
        return CatalogSearchResult(
            items = items,
            hasMore = pagination != null &&
                pagination.optInt("current_page", page) < pagination.optInt("last_page", page),
        )
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        ensureSession()
        val slug = extractSlug(detailPath)
        val manga = getJson("/api/manga/$slug", useMangaSlugHeader = true)
            .optJSONObject("data")
            ?.optJSONObject("manga")
            ?: JSONObject()
        val chapters = getJson("/api/manga/$slug/chapters", useMangaSlugHeader = true)
            .optJSONObject("data")
            ?.optJSONArray("chapters")
            .toChapters(manga)
        val detailPathNormalized = manga.optString("url").normalizePath()
            .ifBlank { "/series/$slug" }
        val coverUrl = manga.optString("cover")
        return MangaDetail(
            providerId = id,
            identification = manga.optString("id").ifBlank { slug },
            title = manga.optString("title"),
            detailPath = detailPathNormalized,
            coverUrl = coverUrl,
            bannerUrl = coverUrl,
            description = manga.optString("description"),
            status = mapStatus(manga.optInt("status")),
            publicationDate = manga.optString("release"),
            periodicity = mapType(manga.optInt("type")),
            chapters = chapters,
        )
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        ensureSession()
        val normalizedPath = chapterPath.normalizePath()
        val slug = extractSlug(normalizedPath)
        val chapterId = normalizedPath.trim('/').split("/").getOrNull(3).orEmpty()
        val chapterResponse = getJson("/api/manga/$slug/chapter/$chapterId", useMangaSlugHeader = true)
            .optJSONObject("data")
            ?.optJSONObject("chapter")
            ?: JSONObject()
        val manga = getJson("/api/manga/$slug", useMangaSlugHeader = true)
            .optJSONObject("data")
            ?.optJSONObject("manga")
            ?: JSONObject()
        val allChapters = getJson("/api/manga/$slug/chapters", useMangaSlugHeader = true)
            .optJSONObject("data")
            ?.optJSONArray("chapters")
            ?: JSONArray()

        val chapterList = allChapters.toChapterItems()
        val currentIndex = chapterList.indexOfFirst { it.id == chapterId }
        val previousChapterPath = chapterList.getOrNull(currentIndex + 1)?.path
        val nextChapterPath = chapterList.getOrNull(currentIndex - 1)?.path
        val chapterTitle = buildChapterLabel(chapterResponse, manga)
        val pages = chapterResponse.optJSONArray("pages").toReaderPages()
        return ReaderData(
            providerId = id,
            mangaTitle = manga.optString("title").ifBlank { slug.toDisplayTitle() },
            mangaDetailPath = manga.optString("url").normalizePath().ifBlank { "/series/$slug" },
            chapterTitle = chapterTitle,
            chapterPath = normalizedPath,
            previousChapterPath = previousChapterPath,
            nextChapterPath = nextChapterPath,
            pages = pages,
        )
    }

    override fun downloadBytes(url: String, referer: String?): ByteArray {
        ensureSession()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", cookieHeader(url))
            .apply {
                if (!referer.isNullOrBlank()) {
                    header("Referer", referer.toAbsoluteUrl())
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun getRenderedDocument(path: String): org.jsoup.nodes.Document? {
        val context = appContext ?: return null
        val latch = CountDownLatch(1)
        val waitState = WebViewWaitState()
        var webView: WebView? = null
        var html: String? = null
        var settled = false
        mainHandler.post {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.userAgentString = USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(TAG, "getRenderedDocument:onPageFinished url=$url")
                        if (url.isNullOrBlank() || !url.startsWith(baseUrl)) return
                        if (!settled) {
                            settled = true
                            waitForUsablePage(view, latch, waitState, onHtmlReady = { resolvedHtml: String ->
                                html = resolvedHtml
                            })
                        }
                    }
                }
            }
            webkitCookieManager.setAcceptCookie(true)
            webkitCookieManager.setAcceptThirdPartyCookies(checkNotNull(webView), true)
            webView?.loadUrl(path.toAbsoluteUrl())
        }
        latch.await(20, TimeUnit.SECONDS)
        mainHandler.post {
            waitState.closed = true
            webView?.stopLoading()
            webView?.destroy()
        }
        if (html == null) {
            Log.w(TAG, "getRenderedDocument: no HTML captured for path=$path")
        }
        return html?.takeIf { it.contains("<html", ignoreCase = true) }
            ?.let { Jsoup.parse(it, baseUrl) }
    }

    private fun parseLatestUpdatesFromHtml(document: org.jsoup.nodes.Document): List<ChapterSummary> = buildList {
        val updateItems = document.select("#series-updates .series-update, .series-update")
        for (item in updateItems) {
            val seriesLink = item.selectFirst("a.series-name[href], .series-name a[href], a[href*=/series/]")
                ?: continue
            val mangaTitle = seriesLink.text().trim()
            val mangaPath = seriesLink.attr("href").normalizePath()
            if (mangaTitle.isBlank() || mangaPath.isBlank()) continue
            val coverUrl = item.selectFirst(".cover img, img")
                ?.let { image ->
                    image.absUrl("data-original")
                        .ifBlank { image.absUrl("data-src") }
                        .ifBlank { image.absUrl("src") }
                }
                .orEmpty()
            val publishedAt = item.selectFirst(".chapter-date, .date, time")
                ?.text()
                ?.trim()
                .orEmpty()

            val chapterLinks = item.select("a[href]")
                .filter { link ->
                    val href = link.attr("href").normalizePath()
                    href.isNotBlank() &&
                        href != mangaPath &&
                        "/series/" in href &&
                        ("/chapter/" in href || "/lesen/" in href || "/read/" in href)
                }
                .distinctBy { it.attr("href").normalizePath() }

            for (chapterLink in chapterLinks) {
                add(chapterSummaryFromHtmlLink(chapterLink, mangaTitle, mangaPath, coverUrl, publishedAt))
            }
        }
    }

    private fun chapterSummaryFromHtmlLink(
        chapterLink: Element,
        mangaTitle: String,
        mangaPath: String,
        coverUrl: String,
        publishedAt: String,
    ): ChapterSummary {
        val chapterPath = chapterLink.attr("href").normalizePath()
        val chapterLabel = chapterLink.text()
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { chapterPath.substringAfterLast('/') }
        val chapterId = chapterPath.substringAfterLast('/').substringBefore('?')
        return ChapterSummary(
            providerId = id,
            mangaTitle = mangaTitle,
            chapterLabel = chapterLabel,
            chapterNumberUrl = chapterId,
            chapterId = chapterId,
            mangaPath = mangaPath,
            chapterPath = chapterPath,
            coverUrl = coverUrl,
            registrationLabel = publishedAt,
        )
    }

    private fun parsePopularMangasFromHtml(document: org.jsoup.nodes.Document): List<MangaSummary> =
        parseMangaCardsFromHtml(document, listOf("top", "popular", "beliebt"))

    private fun parseNewMangasFromHtml(document: org.jsoup.nodes.Document): List<MangaSummary> =
        parseMangaCardsFromHtml(document, listOf("new", "neu"))

    private fun parseMangaCardsFromHtml(
        document: org.jsoup.nodes.Document,
        headingKeywords: List<String>,
    ): List<MangaSummary> {
        val section = document.select("section, div, article")
            .firstOrNull { container ->
                val heading = container.selectFirst("h1, h2, h3, h4, .title, .headline")
                    ?.text()
                    ?.lowercase()
                    .orEmpty()
                headingKeywords.any { it in heading }
            }
            ?: return emptyList()
        return section.select("a[href*=/series/], a[href*=/manga/]")
            .mapNotNull { link ->
                val detailPath = link.attr("href").normalizePath()
                val title = link.attr("title").trim().ifBlank {
                    link.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
                }.ifBlank {
                    link.text().replace(Regex("\\s+"), " ").trim()
                }
                if (detailPath.isBlank() || title.isBlank()) return@mapNotNull null
                val coverUrl = link.selectFirst("img")
                    ?.let { image ->
                        image.absUrl("data-original")
                            .ifBlank { image.absUrl("data-src") }
                            .ifBlank { image.absUrl("src") }
                    }
                    .orEmpty()
                MangaSummary(
                    providerId = id,
                    title = title,
                    detailPath = detailPath,
                    coverUrl = coverUrl,
                )
            }
            .distinctBy { it.detailPath }
    }

    private fun fetchLatestUpdates(offset: Int): List<ChapterSummary> {
        val response = getJson(
            "/api/home/updates".toAbsoluteUrl().toHttpUrl().newBuilder()
                .addQueryParameter("offset", offset.toString())
                .build()
                .toString()
        )
        val items = response.optJSONObject("data")?.optJSONArray("published") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val manga = item.optJSONObject("manga") ?: continue
                val teams = item.optJSONArray("teams")
                    ?.let { joinTeamNames(it) }
                    .orEmpty()
                val chapters = item.optJSONArray("chapters") ?: continue
                for (chapterIndex in 0 until chapters.length()) {
                    val chapter = chapters.optJSONObject(chapterIndex) ?: continue
                    val chapterPath = chapter.optString("readerURL").normalizePath()
                    if (chapterPath.isBlank()) continue
                    add(
                        ChapterSummary(
                            providerId = id,
                            mangaTitle = manga.optString("title"),
                            chapterLabel = buildChapterLabel(chapter, manga),
                            chapterNumberUrl = chapter.optString("id"),
                            chapterId = chapter.optString("id"),
                            mangaPath = manga.optString("url").normalizePath(),
                            chapterPath = chapterPath,
                            coverUrl = manga.optString("cover"),
                            registrationLabel = listOfNotNull(
                                teams.takeIf { it.isNotBlank() },
                                chapter.optString("publishedAt").takeIf { it.isNotBlank() },
                            ).joinToString(" | "),
                        )
                    )
                }
            }
        }
    }

    private fun fetchTopManga(): List<MangaSummary> =
        getJson("/api/home/top-manga")
            .also { response ->
                Log.d(
                    TAG,
                    "fetchTopManga: keys=${response.keys().asSequence().toList()} dataType=${response.opt("data")?.javaClass?.simpleName}"
                )
                response.optJSONObject("data")?.let { data ->
                    Log.d(
                        TAG,
                        "fetchTopManga:data keys=${data.keys().asSequence().toList()}"
                    )
                }
            }
            .extractHomeMangaArray("top-manga")
            .also { array -> Log.d(TAG, "fetchTopManga: extracted=${array?.length() ?: -1}") }
            .toMangaSummaries()

    private fun fetchNewManga(): List<MangaSummary> =
        getJson("/api/home/new-manga")
            .also { response ->
                Log.d(
                    TAG,
                    "fetchNewManga: keys=${response.keys().asSequence().toList()} dataType=${response.opt("data")?.javaClass?.simpleName}"
                )
                response.optJSONObject("data")?.let { data ->
                    Log.d(
                        TAG,
                        "fetchNewManga:data keys=${data.keys().asSequence().toList()}"
                    )
                }
            }
            .extractHomeMangaArray("new-manga")
            .also { array -> Log.d(TAG, "fetchNewManga: extracted=${array?.length() ?: -1}") }
            .toMangaSummaries()

    private fun getDocument(path: String): org.jsoup.nodes.Document {
        val absoluteUrl = path.toAbsoluteUrl()
        val request = Request.Builder()
            .url(absoluteUrl)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", cookieHeader(absoluteUrl))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            Log.d(
                TAG,
                "getDocument: path=$path code=${response.code} finalUrl=${response.request.url}"
            )
            logHtmlPreview("getDocument:$path", body)
            return Jsoup.parse(body, baseUrl)
        }
    }

    private fun getJson(
        path: String,
        useMangaSlugHeader: Boolean = false,
    ): JSONObject {
        val absoluteUrl = path.toAbsoluteUrl()
        val request = Request.Builder()
            .url(absoluteUrl)
            .header("User-Agent", USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", baseUrl)
            .header("Cookie", cookieHeader(absoluteUrl))
            .apply {
                csrfToken?.takeIf { it.isNotBlank() }?.let { header("X-CSRF-TOKEN", it) }
                xsrfCookie()?.takeIf { it.isNotBlank() }?.let { header("X-XSRF-TOKEN", it) }
                if (useMangaSlugHeader) {
                    header("Use-Parameter", "manga_slug")
                    if (path.contains("/chapters")) {
                        header("include-teams", "true")
                    }
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val trimmed = body.trimStart()
            Log.d(
                TAG,
                "getJson: path=$path code=${response.code} finalUrl=${response.request.url} startsWith=${trimmed.take(20)}"
            )
            if (!trimmed.startsWith("{")) {
                logHtmlPreview("getJson:$path", body)
                throw IllegalStateException(
                    "Expected JSON from $path but received: ${trimmed.take(120)}"
                )
            }
            return JSONObject(body)
        }
    }

    private fun postFormJson(
        path: String,
        formBody: String,
    ): JSONObject? {
        val absoluteUrl = path.toAbsoluteUrl()
        val request = Request.Builder()
            .url(absoluteUrl)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", baseUrl)
            .header("Cookie", cookieHeader(absoluteUrl))
            .post(formBody.toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val trimmed = body.trimStart()
            Log.d(
                TAG,
                "postFormJson: path=$path code=${response.code} finalUrl=${response.request.url} startsWith=${trimmed.take(20)}"
            )
            if (!trimmed.startsWith("{")) {
                logHtmlPreview("postFormJson:$path", body)
            }
            if (!trimmed.startsWith("{")) return null
            return runCatching { JSONObject(body) }.getOrNull()
        }
    }

    private fun ensureSession() {
        if (!csrfToken.isNullOrBlank()) return
        bootstrapWebSession()
        val html = getDocument("/").outerHtml()
        csrfToken = extractJsStringValue(html, "window.laravel={\"_token\": \"")
            ?: Regex("""window\.laravel=\{"_token":\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
        Log.d(
            TAG,
            "ensureSession: csrf=${!csrfToken.isNullOrBlank()} xsrf=${xsrfCookie() != null} cookies=${cookieHeader(baseUrl).take(LOG_PREVIEW_LENGTH)}"
        )
    }

    private fun bootstrapWebSession() {
        val context = appContext ?: return
        val existingCookies = webkitCookieManager.getCookie(baseUrl).orEmpty()
        if ("XSRF-TOKEN=" in existingCookies || "laravel_session=" in existingCookies) return

        val latch = CountDownLatch(1)
        val waitState = WebViewWaitState()
        var webView: WebView? = null
        var settled = false
        mainHandler.post {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadsImagesAutomatically = false
                settings.blockNetworkImage = true
                settings.userAgentString = USER_AGENT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!url.isNullOrBlank() && url.startsWith(baseUrl)) {
                            Log.d(TAG, "bootstrapWebSession:onPageFinished url=$url")
                            if (!settled) {
                                settled = true
                                waitForUsablePage(view, latch, waitState)
                            }
                        }
                    }
                }
            }
            webkitCookieManager.setAcceptCookie(true)
            webkitCookieManager.setAcceptThirdPartyCookies(checkNotNull(webView), true)
            webView?.loadUrl(baseUrl)
        }
        latch.await(15, TimeUnit.SECONDS)
        mainHandler.post {
            waitState.closed = true
            webView?.stopLoading()
            webView?.destroy()
        }
        Log.d(
            TAG,
            "bootstrapWebSession: done cookies=${webkitCookieManager.getCookie(baseUrl).orEmpty().take(LOG_PREVIEW_LENGTH)}"
        )
    }

    private fun waitForUsablePage(
        view: WebView?,
        latch: CountDownLatch,
        waitState: WebViewWaitState,
        onHtmlReady: ((String) -> Unit)? = null,
        attempt: Int = 0,
    ) {
        if (view == null || waitState.closed) {
            latch.countDown()
            return
        }
        view.evaluateJavascript(
            """
            (function() {
              const title = document.title || '';
              const text = document.body ? (document.body.innerText || '') : '';
              const buttons = Array.from(document.querySelectorAll('button, a, [role="button"]'));
              const accept = buttons.find(el => /alle akzeptieren|akzeptieren|accept all|accept/i.test((el.innerText || '').trim()));
              if (accept) { try { accept.click(); } catch (e) {} }
              return JSON.stringify({
                title: title,
                text: text.slice(0, 1200),
                html: document.documentElement ? document.documentElement.outerHTML : ''
              });
            })();
            """.trimIndent()
        ) { raw ->
            if (waitState.closed) return@evaluateJavascript
            val payload = parseEvaluatedJson(raw)
            val title = payload?.optString("title").orEmpty()
            val text = payload?.optString("text").orEmpty()
            val html = payload?.optString("html").orEmpty()
            val blocked = isVerificationPage(title, text, html)
            Log.d(
                TAG,
                "waitForUsablePage: attempt=$attempt blocked=$blocked title='$title' text='${text.take(180)}'"
            )
            if (!blocked && html.contains("<html", ignoreCase = true)) {
                onHtmlReady?.invoke(html)
                latch.countDown()
                return@evaluateJavascript
            }
            if (attempt >= MAX_WEBVIEW_WAIT_STEPS) {
                onHtmlReady?.invoke(html)
                latch.countDown()
                return@evaluateJavascript
            }
            mainHandler.postDelayed(
                {
                    if (!waitState.closed) {
                        waitForUsablePage(view, latch, waitState, onHtmlReady, attempt + 1)
                    }
                },
                WEBVIEW_WAIT_INTERVAL_MS,
            )
        }
    }

    private fun parseEvaluatedJson(raw: String?): JSONObject? {
        val normalized = raw
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.replace("\\\\\"", "\"")
            ?.replace("\\u003C", "<")
            ?.replace("\\n", "\\n")
            ?.replace("\\t", "\\t")
            ?.replace("\\/", "/")
            ?.replace("\\\\", "\\")
            ?: return null
        return runCatching { JSONObject(normalized) }.getOrNull()
    }

    private fun isVerificationPage(title: String, text: String, html: String): Boolean {
        val haystack = listOf(title, text, html.take(3000))
            .joinToString("\n")
            .lowercase()
        return "verifying the connection" in haystack ||
            "please wait" in haystack ||
            "checking your browser" in haystack ||
            "cloudflare" in haystack ||
            "ddos" in haystack
    }

    private fun xsrfCookie(): String? {
        val okhttpValue = cookieManager.cookieStore.cookies
            .lastOrNull { it.name == "XSRF-TOKEN" }
            ?.value
        val webkitValue = webkitCookieManager.getCookie(baseUrl)
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("XSRF-TOKEN=") }
            ?.substringAfter('=')
        val value = okhttpValue ?: webkitValue ?: return null
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    private fun cookieHeader(url: String): String {
        val pairs = linkedSetOf<String>()
        webkitCookieManager.getCookie(url)
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.forEach { pairs += it }
        cookieManager.cookieStore.cookies
            .filter { cookie ->
                val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
                host.endsWith(cookie.domain.trimStart('.'))
            }
            .mapTo(pairs) { "${it.name}=${it.value}" }
        return pairs.joinToString("; ")
    }

    private fun logHtmlPreview(label: String, body: String) {
        val trimmed = body.replace(Regex("\\s+"), " ").trim()
        if (trimmed.isBlank()) {
            Log.d(TAG, "$label: <blank>")
            return
        }
        val title = runCatching { Jsoup.parse(body).title() }.getOrDefault("")
        Log.d(TAG, "$label: title='$title' preview='${trimmed.take(LOG_PREVIEW_LENGTH)}'")
    }

    private fun JSONObject.extractHomeMangaArray(key: String): JSONArray? {
        val dataObject = optJSONObject("data")
        return when {
            optJSONArray("data") != null -> optJSONArray("data")
            optJSONArray(key) != null -> optJSONArray(key)
            dataObject?.optJSONArray(key) != null -> dataObject.optJSONArray(key)
            dataObject?.optJSONObject(key)?.optJSONArray("items") != null -> dataObject.optJSONObject(key)?.optJSONArray("items")
            dataObject?.optJSONObject(key)?.optJSONArray("data") != null -> dataObject.optJSONObject(key)?.optJSONArray("data")
            dataObject?.optJSONArray("items") != null -> dataObject.optJSONArray("items")
            dataObject?.optJSONArray("manga") != null -> dataObject.optJSONArray("manga")
            else -> null
        }
    }

    private fun extractRouteData(html: String): JSONObject? {
        val markers = listOf(
            "window.laravel.route.data =",
            "window.laravel.route.data=",
            "window.__DATA__ =",
            "window.__DATA__=",
        )
        val marker = markers.firstOrNull { html.contains(it) } ?: return null
        val json = extractJsObjectAfterMarker(html, marker) ?: return null
        return runCatching { JSONObject(json) }.getOrNull()
    }

    private fun extractJsStringValue(text: String, marker: String): String? {
        val start = text.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val end = text.indexOf('"', valueStart)
        if (end <= valueStart) return null
        return text.substring(valueStart, end)
    }

    private fun extractJsObjectAfterMarker(text: String, marker: String): String? {
        val markerIndex = text.indexOf(marker)
        if (markerIndex < 0) return null
        val start = text.indexOf('{', markerIndex)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                if (escape) {
                    escape = false
                } else if (char == '\\') {
                    escape = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun JSONArray?.toMangaSummaries(): List<MangaSummary> = buildList {
        val items = this@toMangaSummaries ?: return@buildList
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val detailPath = item.optString("url").normalizePath()
            val title = item.optString("title")
            if (detailPath.isBlank() || title.isBlank()) continue
            add(
                MangaSummary(
                    providerId = id,
                    title = title,
                    detailPath = detailPath,
                    coverUrl = item.optString("cover"),
                    status = mapStatus(item.optInt("statusScanlation", item.optInt("status"))),
                    periodicity = mapType(item.optInt("type")),
                    latestPublication = item.optString("release"),
                    chaptersCount = item.optInt("chapterCount", 0)
                        .takeIf { it > 0 }
                        ?.toString()
                        .orEmpty(),
                )
            )
        }
    }

    private fun JSONArray?.toHomeChapterSummaries(): List<ChapterSummary> = buildList {
        val items = this@toHomeChapterSummaries ?: return@buildList
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val manga = item.optJSONObject("manga") ?: continue
            val teams = item.optJSONArray("teams")
                ?.let { joinTeamNames(it) }
                .orEmpty()
            val chapters = item.optJSONArray("chapters") ?: continue
            for (chapterIndex in 0 until chapters.length()) {
                val chapter = chapters.optJSONObject(chapterIndex) ?: continue
                val chapterPath = chapter.optString("readerURL").normalizePath()
                if (chapterPath.isBlank()) continue
                add(
                    ChapterSummary(
                        providerId = id,
                        mangaTitle = manga.optString("title"),
                        chapterLabel = buildChapterLabel(chapter, manga),
                        chapterNumberUrl = chapter.opt("id")?.toString().orEmpty(),
                        chapterId = chapter.opt("id")?.toString().orEmpty(),
                        mangaPath = manga.optString("url").normalizePath(),
                        chapterPath = chapterPath,
                        coverUrl = manga.optString("cover"),
                        registrationLabel = listOfNotNull(
                            teams.takeIf { it.isNotBlank() },
                            chapter.optString("publishedAt").takeIf { it.isNotBlank() },
                        ).joinToString(" | "),
                    )
                )
            }
        }
    }

    private fun JSONObject.toMangaSummaries(): List<MangaSummary> =
        optJSONArray("data").toMangaSummaries()

    private fun JSONArray?.toCategoryOptions(): List<CategoryOption> = buildList {
        val items = this@toCategoryOptions ?: return@buildList
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.opt("genre_id")?.toString().orEmpty()
            val name = item.optString("genre_name")
            if (id.isBlank() || name.isBlank()) continue
            add(CategoryOption(id = id, name = name))
        }
    }

    private fun JSONArray?.toChapters(manga: JSONObject): List<MangaChapter> =
        toChapterItems().map { item ->
            MangaChapter(
                id = item.id,
                chapterLabel = item.label.ifBlank { buildFallbackChapterLabel(item.number, item.subNumber, item.volume, manga) },
                chapterNumberUrl = item.number.toString(),
                path = item.path,
                pagesCount = 0,
                registrationDate = item.date,
                uploaderLabel = item.uploader,
            )
        }

    private fun JSONArray?.toChapterItems(): List<ChapterItem> {
        val items = this ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val path = item.optString("readerURL").normalizePath()
                if (path.isBlank()) continue
                val teams = item.optJSONArray("teams")
                    ?.let(::joinTeamNames)
                    .orEmpty()
                add(
                    ChapterItem(
                        id = item.opt("id")?.toString().orEmpty(),
                        number = item.optDouble("number", 0.0),
                        subNumber = item.optDouble("subNumber", 0.0),
                        volume = item.optInt("volume", 0),
                        label = item.optString("name"),
                        path = path,
                        date = item.optString("publishedAt"),
                        uploader = teams,
                    )
                )
            }
        }.sortedWith(
            compareByDescending<ChapterItem> { it.number }
                .thenByDescending { it.subNumber }
                .thenByDescending { it.id.toLongOrNull() ?: Long.MIN_VALUE }
        )
    }

    private fun JSONArray?.toReaderPages(): List<ReaderPage> = buildList {
        val items = this@toReaderPages ?: return@buildList
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val imageUrl = item.optString("url").ifBlank { item.optString("alt_source") }
            if (imageUrl.isBlank()) continue
            add(
                ReaderPage(
                    id = item.optInt("page", index + 1).toString(),
                    numberLabel = item.optInt("page", index + 1).toString(),
                    imageUrl = imageUrl,
                )
            )
        }
    }

    private fun joinTeamNames(teams: JSONArray): String = buildList {
        for (index in 0 until teams.length()) {
            val team = teams.optJSONObject(index) ?: continue
            val name = team.optString("name")
            if (name.isNotBlank()) add(name)
        }
    }.joinToString(", ")

    private fun buildChapterLabel(chapter: JSONObject, manga: JSONObject): String {
        val name = chapter.optString("name").trim()
        if (name.isNotBlank()) return name
        return buildFallbackChapterLabel(
            number = chapter.optDouble("number", 0.0),
            subNumber = chapter.optDouble("subNumber", 0.0),
            volume = chapter.optInt("volume", 0),
            manga = manga,
        )
    }

    private fun buildFallbackChapterLabel(
        number: Double,
        subNumber: Double,
        volume: Int,
        manga: JSONObject,
    ): String {
        val chapterTemplate = manga.optString("chpFormat").ifBlank { "Kapitel %chapter_number%" }
        val volumeTemplate = manga.optString("volFormat")
        val chapterNumber = formatChapterNumber(number, subNumber)
        val chapterText = chapterTemplate.replace("%chapter_number%", chapterNumber)
        if (volume <= 0 || volumeTemplate.isBlank()) return chapterText
        val volumeText = volumeTemplate.replace("%chapter_volume%", volume.toString())
        return "$volumeText - $chapterText"
    }

    private fun formatChapterNumber(number: Double, subNumber: Double): String {
        val whole = number.toLong()
        val base = if (number == whole.toDouble()) whole.toString() else number.toString()
        val sub = subNumber.toLong()
        return if (subNumber > 0.0) "$base.$sub" else base
    }

    private fun extractSlug(path: String): String =
        path.normalizePath()
            .trim('/')
            .split("/")
            .getOrNull(1)
            .orEmpty()

    private fun String.toDisplayTitle(): String =
        replace('_', ' ')
            .replace('-', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
                }
            }

    private fun mapType(type: Int): String = when (type) {
        0 -> "Manga"
        1 -> "Novel"
        2 -> "Manhua"
        3 -> "Manhwa"
        4 -> "Webtoon"
        5 -> "Doujinshi"
        else -> ""
    }

    private fun mapStatus(status: Int): String = when (status) {
        0, 1 -> "Ongoing"
        2 -> "Hiatus"
        3 -> "Cancelled"
        4 -> "Completed"
        else -> ""
    }

    private fun String.toAbsoluteUrl(): String =
        if (startsWith("http://") || startsWith("https://")) this else "$baseUrl${normalizePath()}"

    private fun String.normalizePath(): String {
        if (isBlank()) return ""
        val parsed = toHttpUrlOrNull()
        return when {
            parsed != null -> parsed.encodedPath + parsed.encodedQuery?.let { "?$it" }.orEmpty()
            startsWith("/") -> this
            else -> "/$this"
        }
    }

    companion object {
        private const val TAG = "MangaTubeProvider"
        private const val LOG_PREVIEW_LENGTH = 300
        private const val HOME_UPDATES_PAGE_SIZE = 40
        private const val WEBVIEW_WAIT_INTERVAL_MS = 1500L
        private const val MAX_WEBVIEW_WAIT_STEPS = 10
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    private data class ChapterItem(
        val id: String,
        val number: Double,
        val subNumber: Double,
        val volume: Int,
        val label: String,
        val path: String,
        val date: String,
        val uploader: String,
    )

    private data class WebViewWaitState(
        var closed: Boolean = false,
    )
}
