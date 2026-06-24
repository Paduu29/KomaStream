package com.paudinc.komastream.provider.providers

import android.webkit.CookieManager as WebkitCookieManager
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CatalogSearchResult
import com.paudinc.komastream.data.model.CategoryOption
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
import com.paudinc.komastream.utils.LibrarySettingsState
import com.paudinc.komastream.utils.normalizeStoredPath
import okhttp3.FormBody
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MangaBallProvider(
    private val settingsState: LibrarySettingsState,
    baseClient: OkHttpClient = OkHttpClient(),
) : MangaProvider {
    companion object {
        const val PROVIDER_ID = "mangaball-en"
        const val PREF_MANGABALL_ADULT_CONTENT = "mangaballAdultContentEnabled"
        private const val CLOUDFLARE_WAIT_TIMEOUT_MS = 60_000L
        private const val CLOUDFLARE_POLL_INTERVAL_MS = 500L
        private const val HOME_FEED_CACHE_MS = 2 * 60 * 1000L
        private const val FILTER_CACHE_MS = 30 * 60 * 1000L
        private const val CHAPTER_LISTING_CACHE_MS = 10 * 60 * 1000L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private val HOME_FEED_EXECUTOR = Executors.newFixedThreadPool(5)
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "MangaBall"
    override val language: AppLanguage = AppLanguage.MULTI
    override val websiteUrl: String = "https://mangaball.net"
    override val logoUrl: String = "https://mangaball.net/public/frontend/images/favicon.png"

    private val baseUrl = "https://mangaball.net"
    private val baseUri = URI(baseUrl)
    private val webkitCookieManager = WebkitCookieManager.getInstance()
    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }
    private val sessionLock = Any()
    private val cloudflareLock = Any()
    private val client = baseClient.newBuilder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()
    @Volatile
    var cloudflareReady: Boolean = false
        private set

    @Volatile
    private var csrfToken: String? = null
    @Volatile
    private var homeFeedCache: TimedValue<HomeFeed>? = null
    @Volatile
    private var catalogFilterCache: TimedValue<CatalogFilterOptions>? = null
    private val chapterListingCache = ConcurrentHashMap<String, TimedValue<JSONArray>>()

    override fun fetchHomeFeed(): HomeFeed {
        val adultContentEnabled = isAdultContentEnabled()
        homeFeedCache?.takeIf { it.isValidFor(adultContentEnabled, HOME_FEED_CACHE_MS) }?.let { return it.value }
        val latestFuture = HOME_FEED_EXECUTOR.submit(Callable { fetchSectionMangas("getLatestTable") })
        val recommendedFuture = HOME_FEED_EXECUTOR.submit(Callable { fetchSectionMangas("getRecommend") })
        val popularFuture = HOME_FEED_EXECUTOR.submit(Callable { fetchSectionMangas("getPopular") })
        val featuredFuture = HOME_FEED_EXECUTOR.submit(Callable { fetchSectionMangas("getFeatured") })
        val latestUpdates = latestFuture.get()
        val recommended = recommendedFuture.get()
        val popular = popularFuture.get()
        val featured = featuredFuture.get()

        return HomeFeed(
            latestUpdates = emptyList(),
            popularChapters = emptyList(),
            popularMangas = popular.ifEmpty { recommended },
            sections = listOfNotNull(
                featured.takeIf { it.isNotEmpty() }?.let {
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
                        type = HomeSectionType.MANGAS,
                        mangas = latestUpdates,
                    )
                },
                recommended.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "recommended",
                        title = "Recommended",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
                popular.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "popular",
                        title = "Popular",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
            ),
        ).also { homeFeedCache = TimedValue(adultContentEnabled, it) }
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        val adultContentEnabled = isAdultContentEnabled()
        catalogFilterCache?.takeIf { it.isValidFor(adultContentEnabled, FILTER_CACHE_MS) }?.let { return it.value }
        return CatalogFilterOptions(
            categories = listOf(
                CategoryOption("JP", "Manga"),
                CategoryOption("KR", "Manhwa"),
                CategoryOption("CN", "Manhua"),
                CategoryOption("ONESHOT", "One Shot"),
            ),
            sortOptions = listOf(
                FilterOption("updated_chapters_desc", "Latest"),
                FilterOption("name_asc", "A \u2192 Z"),
                FilterOption("name_desc", "Z \u2192 A"),
                FilterOption("views_desc", "Most viewed"),
                FilterOption("rating_desc", "Top rated"),
            ),
            statusOptions = listOf(
                FilterOption("any", "Any"),
                FilterOption("Ongoing", "Ongoing"),
                FilterOption("Completed", "Completed"),
                FilterOption("Hiatus", "Hiatus"),
            ),
        ).also { catalogFilterCache = TimedValue(adultContentEnabled, it) }
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
        val page = (skip / take.coerceAtLeast(1)) + 1
        val formValues = buildList {
            add("search_input" to query)
            add("filters[page]" to page.toString())
            add("filters[sort]" to sortBy.ifBlank { "updated_chapters_desc" })
            add("filters[contentRating]" to if (isAdultContentEnabled()) "any" else "safe")
            add("filters[demographic]" to "any")
            add("filters[publicationStatus]" to broadcastStatus.ifBlank { "any" })
            add("filters[person]" to "any")
            add("filters[person_name]" to "")
            add("filters[publicationYear]" to "")
            add("filters[tagIncludedMode]" to "or")
            add("filters[tagExcludedMode]" to "or")
            categoryIds.forEach { add("filters[tagIncludedIds][]" to it) }
        }
        val response = postJson(
            path = "/api/v1/title/search-advanced/",
            formValues = formValues,
        )
        val items = (response.optJSONArray("data") ?: JSONArray()).toMangaSummaries()
        val pagination = response.optJSONObject("pagination")
        return CatalogSearchResult(
            items = items,
            hasMore = pagination != null &&
                pagination.optInt("current_page", page) < pagination.optInt("last_page", page),
        )
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizePath(detailPath)
        val document = getDocument(normalizedPath)
        val title = document.selectFirst("#comicDetail h6")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Online Free")?.trim()
            .orEmpty()
        val coverUrl = document.selectFirst(".featured-cover")?.absUrl("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            .orEmpty()
        val synopsis = document.selectFirst("#comicDescription .description-text p")?.text()?.trim().orEmpty()
        val description = synopsis.ifBlank { "No description" }
        val status = document.selectFirst(".badge-status")?.text()?.trim().orEmpty()
        val publicationDate = document.select("span.badge").firstOrNull { it.text().contains("Published:", ignoreCase = true) }
            ?.text()
            ?.substringAfter("Published:")
            ?.trim()
            .orEmpty()
        val titleId = extractTitleId(document.html())
        val chapters = parseChapters(fetchChapterListing(titleId, normalizedPath))

        return MangaDetail(
            providerId = id,
            identification = titleId.ifBlank { normalizedPath.trimEnd('/').substringAfterLast('-') },
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
        val normalizedPath = normalizePath(chapterPath)
        val document = getDocument(normalizedPath)
        val html = document.html()
        val titleId = Regex("const\\s+titleId\\s*=\\s*`([^`]+)`")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val mangaDetailPath = extractMangaDetailPath(document, html, titleId)
        val mangaTitle = document.selectFirst("meta[property=og:title]")
            ?.attr("content")
            .cleanReaderMangaTitle()
            .ifBlank { mangaDetailPath.titleFromMangaDetailPath() }
        val chapterTitle = document.selectFirst("#chapterTitle")?.text()?.trim().orEmpty()
        val imagesRaw = Regex("const\\s+chapterImages\\s*=\\s*JSON\\.parse\\(`(.*?)`\\);", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val imageArray = JSONArray(imagesRaw.ifBlank { "[]" })
        val pages = buildList(imageArray.length()) {
            for (index in 0 until imageArray.length()) {
                val imageUrl = imageArray.optString(index)
                if (imageUrl.isBlank()) continue
                add(
                    ReaderPage(
                        id = imageUrl.substringAfterLast('/').substringBefore('?'),
                        numberLabel = (index + 1).toString(),
                        imageUrl = imageUrl,
                    )
                )
            }
        }
        val chapterListing = fetchChapterListing(titleId, normalizedPath)
        val chapterEntries = buildList {
            for (index in 0 until chapterListing.length()) {
                val chapter = chapterListing.optJSONObject(index) ?: continue
                val translations = chapter.optJSONArray("translations") ?: continue
                for (translationIndex in 0 until translations.length()) {
                    val translation = translations.optJSONObject(translationIndex) ?: continue
                    add(
                        ChapterNavEntry(
                            id = translation.optString("id"),
                            path = normalizePath(translation.optString("url")),
                        )
                    )
                }
            }
        }
        val currentChapterId = normalizedPath.trim('/').substringAfterLast('/')
        val currentIndex = chapterEntries.indexOfFirst { it.id == currentChapterId }
        val previousChapterPath = chapterEntries.getOrNull(currentIndex + 1)?.path
        val nextChapterPath = chapterEntries.getOrNull(currentIndex - 1)?.path

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
            .url(url)
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
        synchronized(sessionLock) {
            synchronized(cloudflareLock) {
                cloudflareReady = false
            }
            csrfToken = null
            homeFeedCache = null
            catalogFilterCache = null
            chapterListingCache.clear()
        }
        runCatching {
            cookieManager.cookieStore.removeAll()
        }
        runCatching {
            webkitCookieManager.setAcceptCookie(true)
            webkitCookieManager.removeAllCookies(null)
            webkitCookieManager.flush()
        }
    }

    fun waitForCloudflareCookie(timeoutMs: Long = CLOUDFLARE_WAIT_TIMEOUT_MS): Boolean {
        if (cloudflareReady) return true
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadlineMs) {
            if (markCloudflareReadyIfCookiesPresent()) {
                return true
            }
            Thread.sleep(CLOUDFLARE_POLL_INTERVAL_MS)
        }
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
        }.getOrElse { "" }
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

    private fun ensureSession() {
        synchronized(sessionLock) {
            ensureAdultCookie()
            ensureCloudflareReady()
            if (csrfToken != null) return
            val document = getDocument("/")
            csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()
        }
    }

    private fun isAdultContentEnabled(): Boolean =
        settingsState.current.adultContentEnabled

    private fun ensureAdultCookie() {
        val cookieStore = cookieManager.cookieStore
        val adultContentEnabled = isAdultContentEnabled()
        val existingCookies = cookieStore.get(baseUri)
        val adultCookies = existingCookies.filter { it.name == "show18PlusContent" }
        val currentValue = adultCookies.lastOrNull()?.value

        if (!adultContentEnabled && adultCookies.isEmpty()) return
        if (adultContentEnabled && currentValue == "true") return

        adultCookies.forEach { cookieStore.remove(baseUri, it) }
        if (adultContentEnabled) {
            cookieStore.add(
                baseUri,
                HttpCookie("show18PlusContent", "true").apply {
                    path = "/"
                }
            )
        }
        clearSessionCaches()
    }

    private fun clearSessionCaches() {
        synchronized(sessionLock) {
            csrfToken = null
            homeFeedCache = null
            catalogFilterCache = null
            chapterListingCache.clear()
        }
    }

    private fun fetchSectionMangas(sectionType: String): List<MangaSummary> {
        val response = postJson(
            path = "/api/v1/title/search/",
            formValues = listOf(
                "search_type" to sectionType,
                "search_limit" to "24",
            ),
        )
        return (response.optJSONArray("data") ?: JSONArray()).toMangaSummaries()
    }

    private fun fetchChapterListing(titleId: String, referer: String): JSONArray {
        if (titleId.isBlank()) return JSONArray()
        return postJson(
            path = "/api/v1/chapter/chapter-listing-by-title-id/",
            formValues = listOf(
                "title_id" to titleId,
                "userSettingsEnabled" to "false",
            ),
        ).optJSONArray("ALL_CHAPTERS") ?: JSONArray()
    }

    private fun parseChapters(chapters: JSONArray): List<MangaChapter> = buildList {
        for (index in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(index) ?: continue
            val chapterNumber = chapter.optDouble("number_float", Double.NaN)
            val chapterNumberLabel = chapter.optString("number")
            val translations = chapter.optJSONArray("translations") ?: continue
            val visibleTranslations = buildList {
                for (translationIndex in 0 until translations.length()) {
                    val translation = translations.optJSONObject(translationIndex) ?: continue
                    add(translation)
                }
            }.sortedByDescending { it.optString("date") }
            visibleTranslations.forEach { translation ->
                val path = normalizePath(translation.optString("url"))
                val group = translation.optJSONObject("group")?.optString("name").orEmpty()
                val languageCode = translation.optString("language").trim().lowercase()
                val languageLabel = translation.optString("languageName").trim()
                val label = translation.optString("name")
                    .trim()
                    .ifBlank { chapterNumberLabel.ifBlank { formatChapterNumber(chapterNumber) } }
                add(
                    MangaChapter(
                        id = translation.optString("id"),
                        chapterLabel = label,
                        chapterNumberUrl = if (chapterNumber.isFinite()) formatChapterNumber(chapterNumber) else label,
                        path = path,
                        pagesCount = translation.optInt("pages", 0),
                        registrationDate = translation.optString("date"),
                        languageCode = languageCode,
                        languageLabel = languageLabel,
                        uploaderLabel = group,
                    )
                )
            }
        }
    }

    private fun formatChapterNumber(value: Double): String {
        val whole = value.toLong()
        return if (value == whole.toDouble()) whole.toString() else value.toString().trimEnd('0').trimEnd('.')
    }

    private fun extractTitleId(html: String): String {
        return Regex("const\\s+titleId\\s*=\\s*'([^']+)'")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun extractMangaDetailPath(document: Document, html: String, titleId: String): String {
        document.select("script[type=application/ld+json]").forEach { script ->
            val match = Regex("""https?://[^"']+/title-detail/[^"']+""")
                .find(script.html())
                ?.value
                ?.let(::normalizePath)
            if (!match.isNullOrBlank()) return match
        }

        val scriptPath = Regex("window\\.location\\.href\\s*=\\s*'([^']+/title-detail/[^']+)'")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::normalizePath)
        if (!scriptPath.isNullOrBlank()) return scriptPath

        val titleSlug = Regex("""/title-detail/([a-z0-9-]+-$titleId)/""", RegexOption.IGNORE_CASE)
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
        if (!titleSlug.isNullOrBlank()) return "/title-detail/$titleSlug/"

        return ""
    }

    private fun String?.cleanReaderMangaTitle(): String {
        return this
            ?.replace(Regex("""\s+Ch(?:apter)?\.?\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s+Online\s+Free.*$""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("""\s*[|\\-–—]\s*MangaBall.*$""", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }

    private fun String.titleFromMangaDetailPath(): String {
        return normalizePath(this)
            .trim('/')
            .substringAfterLast('/')
            .replace(Regex("""-\d+$"""), "")
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun getDocument(path: String): Document {
        ensureCloudflareReady()
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (body.isBlank() || body.contains("Just a moment", ignoreCase = true)) {
                invalidateCaches()
                throw IllegalStateException("Cloudflare challenge still active for MangaBall")
            }
            return Jsoup.parse(body, baseUrl)
        }
    }

    private fun postJson(
        path: String,
        formValues: List<Pair<String, String>>,
    ): JSONObject {
        ensureSession()
        val body = FormBody.Builder().apply {
            formValues.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .header("X-CSRF-TOKEN", csrfToken.orEmpty())
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            val trimmed = responseBody.trimStart()
            if (!trimmed.startsWith("{")) {
                if (looksLikeCloudflareChallenge(trimmed)) {
                    invalidateCaches()
                    throw IllegalStateException("Cloudflare challenge still active for MangaBall")
                }
                throw IllegalStateException(
                    "Expected JSON from $path but received ${trimmed.take(80)}"
                )
            }
            return JSONObject(responseBody)
        }
    }

    private fun JSONArray.toMangaSummaries(): List<MangaSummary> = buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(item.toMangaSummary())
        }
    }

    private fun JSONObject.toMangaSummary(): MangaSummary =
        MangaSummary(
            providerId = id,
            title = optString("name"),
            detailPath = normalizePath(optString("url")),
            coverUrl = optString("cover"),
            status = optString("status"),
            latestPublication = optString("updated_at"),
            views = "",
        )

    private fun looksLikeCloudflareChallenge(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("just a moment") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("challenge-platform") ||
            lower.contains("cloudflare")
    }

    private fun String.toAbsoluteUrl(): String {
        val trimmed = trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return if (trimmed.startsWith("/")) "$baseUrl$trimmed" else "$baseUrl/$trimmed"
    }

    private fun normalizePath(path: String): String = normalizeStoredPath(path)

    private data class ChapterNavEntry(
        val id: String,
        val path: String,
    )

    private data class TimedValue<T>(
        val adultContentEnabled: Boolean,
        val value: T,
        val cachedAtMillis: Long = System.currentTimeMillis(),
    ) {
        fun isValidFor(expectedAdultContentEnabled: Boolean, maxAgeMillis: Long): Boolean =
            adultContentEnabled == expectedAdultContentEnabled &&
                System.currentTimeMillis() - cachedAtMillis <= maxAgeMillis
    }
}
