package com.paudinc.komastream.provider.providers

import android.webkit.CookieManager as WebkitCookieManager
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CatalogSearchResult
import com.paudinc.komastream.data.model.CategoryOption
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
import com.paudinc.komastream.utils.normalizeStoredPath
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI

class KaganeProvider(
    baseClient: OkHttpClient = OkHttpClient(),
) : MangaProvider {
    companion object {
        const val PROVIDER_ID = "kagane-en"
        private const val HOME_PAGE_SIZE = 6
        private const val SEARCH_PAGE_SIZE = 100
        private const val CLOUDFLARE_WAIT_TIMEOUT_MS = 60_000L
        private const val CLOUDFLARE_POLL_INTERVAL_MS = 500L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "Kagane"
    override val language: AppLanguage = AppLanguage.EN
    override val websiteUrl: String = "https://kagane.to"
    override val logoUrl: String = "https://kagane.to/favicons/android-icon-192x192.png"

    private val apiBaseUrl = "https://yuzuki.kagane.to"
    private val cloudflareBootstrapUrl = websiteUrl
    private val webkitCookieManager = WebkitCookieManager.getInstance()
    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val cloudflareLock = Any()
    private val client = baseClient.newBuilder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()

    @Volatile
    var cloudflareReady: Boolean = false
        private set

    override fun fetchHomeFeed(): HomeFeed {
        val newest = fetchSeriesPage(sort = "created_at,desc", page = 0, size = HOME_PAGE_SIZE)
        val updated = fetchSeriesPage(sort = "updated_at,desc", page = 0, size = HOME_PAGE_SIZE)
        val popular = fetchSeriesPage(sort = "avg_views_today,desc", page = 0, size = HOME_PAGE_SIZE)

        return HomeFeed(
            latestUpdates = emptyList(),
            popularChapters = emptyList(),
            popularMangas = popular.mangas,
            sections = listOfNotNull(
                popular.mangas.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "popular",
                        title = "Popular",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
                newest.mangas.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "new-releases",
                        title = "New Releases",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
                updated.mangas.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "recently-updated",
                        title = "Recently Updated",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
            ),
        )
    }

    override fun fetchHomeSectionPage(sectionId: String, page: Int): HomeSectionPageResult? {
        if (page < 1) return null
        val apiPage = page - 1
        return when (sectionId) {
            "new-releases" -> fetchSeriesPage(sort = "created_at,desc", page = apiPage, size = HOME_PAGE_SIZE)
            "recently-updated" -> fetchSeriesPage(sort = "updated_at,desc", page = apiPage, size = HOME_PAGE_SIZE)
            "popular" -> fetchSeriesPage(sort = "avg_views_today,desc", page = apiPage, size = HOME_PAGE_SIZE)
            else -> null
        }
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        val genres = getJsonArray("/api/v2/genres/list")
            .toCategoryOptions()
            .sortedBy { it.name.lowercase() }

        return CatalogFilterOptions(
            categories = genres,
            sortOptions = listOf(
                com.paudinc.komastream.data.model.FilterOption("avg_views,desc", "Popular"),
                com.paudinc.komastream.data.model.FilterOption("avg_views_today,desc", "Popular Today"),
                com.paudinc.komastream.data.model.FilterOption("avg_views_week,desc", "Popular This Week"),
                com.paudinc.komastream.data.model.FilterOption("avg_views_month,desc", "Popular This Month"),
                com.paudinc.komastream.data.model.FilterOption("updated_at,desc", "Recently Updated"),
                com.paudinc.komastream.data.model.FilterOption("created_at,desc", "New Releases"),
            ),
            statusOptions = listOf(
                com.paudinc.komastream.data.model.FilterOption("any", "Any"),
                com.paudinc.komastream.data.model.FilterOption("Ongoing", "Ongoing"),
                com.paudinc.komastream.data.model.FilterOption("Completed", "Completed"),
                com.paudinc.komastream.data.model.FilterOption("Hiatus", "Hiatus"),
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
        val pageSize = take.coerceIn(1, SEARCH_PAGE_SIZE)
        val page = (skip / pageSize) + 1
        val localSkip = skip % pageSize
        val response = searchSeries(
            query = query,
            categoryIds = categoryIds,
            sortBy = sortBy,
            broadcastStatus = broadcastStatus,
            page = page - 1,
            size = pageSize,
        )
        val items = response.optJSONArray("content").toMangaSummaries()
        return CatalogSearchResult(
            items = items.drop(localSkip).take(pageSize),
            hasMore = !response.optBoolean("last", false) || localSkip + pageSize < items.size,
        )
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizeStoredPath(detailPath)
        val seriesId = extractSeriesId(normalizedPath)
        val response = getJson("/api/v2/series/$seriesId")

        val title = response.optString("title").trim().ifBlank { seriesId }
        val coverUrl = seriesImageUrl(
            response.optJSONArray("series_covers")
                ?.optJSONObject(0)
                ?.optString("image_id")
                .orEmpty()
        ).ifBlank {
            seriesImageUrl(response.optString("cover_image_id"))
        }
        val description = response.optString("description").trim()
        val status = response.optString("publication_status").trim()
        val publicationDate = response.optString("created_at").trim()
        val chapters = response.optJSONArray("series_books").toMangaChapters(seriesId)

        return MangaDetail(
            providerId = id,
            identification = seriesId,
            title = title,
            detailPath = "/series/$seriesId",
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
        val normalizedPath = normalizeStoredPath(chapterPath)
        val seriesId = extractSeriesId(normalizedPath)
        val bookId = extractBookId(normalizedPath)
        val series = getJson("/api/v2/series/$seriesId")
        val bookResponse = postJson("/api/v2/books/$bookId?is_datasaver=false")

        val chapters = series.optJSONArray("series_books").toMangaChapters(seriesId)
        val currentIndex = chapters.indexOfFirst { it.path == normalizedPath || it.id == bookId }
            .coerceAtLeast(0)
        val previousChapterPath = chapters.getOrNull(currentIndex + 1)?.path
        val nextChapterPath = chapters.getOrNull(currentIndex - 1)?.path
        val currentBookTitle = chapters.getOrNull(currentIndex)?.chapterLabel.orEmpty().ifBlank {
            chapters.getOrNull(currentIndex)?.chapterNumberUrl.orEmpty()
        }

        val pages = bookResponse.optJSONObject("manifest")
            ?.optJSONArray("pages")
            .toReaderPages(bookResponse.optString("access_token"), bookResponse.optString("cache_url"), bookId)

        return ReaderData(
            providerId = id,
            mangaTitle = series.optString("title").trim().ifBlank { seriesId },
            mangaDetailPath = "/series/$seriesId",
            chapterTitle = currentBookTitle.ifBlank { bookId },
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
                if (!referer.isNullOrBlank()) {
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

    private fun fetchSeriesPage(sort: String, page: Int, size: Int): HomeSectionPageResult {
        val response = searchSeries(
            query = "",
            categoryIds = emptyList(),
            sortBy = sort,
            broadcastStatus = "any",
            page = page,
            size = size,
        )
        return HomeSectionPageResult(
            type = HomeSectionType.MANGAS,
            mangas = response.optJSONArray("content").toMangaSummaries(),
            hasMore = !response.optBoolean("last", false),
        )
    }

    private fun searchSeries(
        query: String,
        categoryIds: List<String>,
        sortBy: String,
        broadcastStatus: String,
        page: Int,
        size: Int,
    ): JSONObject {
        val url = apiBaseUrl.toHttpUrl().newBuilder()
            .addEncodedPathSegments("api/v2/search/series")
            .addQueryParameter("page", page.coerceAtLeast(0).toString())
            .addQueryParameter("size", size.coerceAtLeast(1).toString())
            .addQueryParameter("sort", mapSort(sortBy))
            .apply {
                val trimmedQuery = query.trim()
                if (trimmedQuery.isNotBlank()) {
                    addQueryParameter("title", trimmedQuery)
                }
            }
            .build()
        val body = JSONObject().apply {
            put("content_rating", JSONArray().apply {
                put("Safe")
                put("Suggestive")
            })
            val trimmedQuery = query.trim()
            if (trimmedQuery.isNotBlank()) {
                put("title", trimmedQuery)
            }
            if (broadcastStatus.isNotBlank() && broadcastStatus != "any") {
                put("publication_status", JSONArray().put(broadcastStatus))
            }
            if (categoryIds.isNotEmpty()) {
                put("genres", JSONObject().apply {
                    put("values", JSONArray(categoryIds))
                })
            }
        }
        return postJson(url.toString(), body)
    }

    private fun getJson(path: String): JSONObject {
        ensureCloudflareReady()
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", websiteUrl)
            .header("Origin", websiteUrl)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("{")) {
                if (looksLikeCloudflareChallenge(trimmed)) {
                    invalidateCaches()
                    throw IllegalStateException("Cloudflare challenge still active for $path; received HTML instead of JSON")
                }
                throw IllegalStateException("Expected JSON from $path but received ${trimmed.take(80)}")
            }
            return JSONObject(body)
        }
    }

    private fun getJsonArray(path: String): JSONArray {
        ensureCloudflareReady()
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", websiteUrl)
            .header("Origin", websiteUrl)
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("[")) {
                if (looksLikeCloudflareChallenge(trimmed)) {
                    invalidateCaches()
                    throw IllegalStateException("Cloudflare challenge still active for $path; received HTML instead of JSON")
                }
                throw IllegalStateException("Expected JSON array from $path but received ${trimmed.take(80)}")
            }
            return JSONArray(body)
        }
    }

    private fun postJson(path: String, body: JSONObject = JSONObject()): JSONObject {
        ensureCloudflareReady()
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", websiteUrl)
            .header("Origin", websiteUrl)
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val trimmed = body.trimStart()
            if (!trimmed.startsWith("{")) {
                if (looksLikeCloudflareChallenge(trimmed)) {
                    invalidateCaches()
                    throw IllegalStateException("Cloudflare challenge still active for $path; received HTML instead of JSON")
                }
                throw IllegalStateException("Expected JSON from $path but received ${trimmed.take(80)}")
            }
            return JSONObject(body)
        }
    }

    private fun snapshotWebkitCookies(): String {
        return runCatching {
            webkitCookieManager.setAcceptCookie(true)
            webkitCookieManager.flush()
            webkitCookieManager.getCookie(cloudflareBootstrapUrl).orEmpty()
        }.getOrElse { "" }
    }

    private fun syncCookies(cookieHeader: String) {
        runCatching {
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
                    listOf(URI(websiteUrl), URI(apiBaseUrl)).forEach { uri ->
                        cookieManager.cookieStore.add(
                            uri,
                            HttpCookie(name, value).apply { path = "/" },
                        )
                    }
                }
        }
    }

    private fun looksLikeCloudflareChallenge(body: String): Boolean {
        val lower = body.lowercase()
        return lower.startsWith("<!doctype html") ||
            lower.startsWith("<html") ||
            "just a moment" in lower ||
            "cloudflare" in lower
    }

    private fun mapSort(sortBy: String): String = when (sortBy) {
        "relevance" -> "avg_views,desc"
        "avg_views_today,desc",
        "avg_views_week,desc",
        "avg_views_month,desc",
        "avg_views,desc",
        "created_at,desc",
        "updated_at,desc" -> sortBy
        else -> "avg_views,desc"
    }

    private fun extractSeriesId(path: String): String {
        val normalized = normalizeStoredPath(path).trim('/')
        return normalized.substringAfter("series/").substringBefore('/').ifBlank {
            normalized.substringAfterLast('/')
        }
    }

    private fun extractBookId(path: String): String {
        val normalized = normalizeStoredPath(path).trim('/')
        return normalized.substringAfter("reader/").substringBefore('/').ifBlank {
            normalized.substringAfterLast('/')
        }
    }

    private fun seriesImageUrl(imageId: String): String =
        imageId.trim().takeIf { it.isNotBlank() }?.let { "$apiBaseUrl/api/v2/image/$it/compressed" }.orEmpty()

    private fun String.toAbsoluteUrl(): String {
        val trimmed = trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return if (trimmed.startsWith("/")) "$websiteUrl$trimmed" else "$websiteUrl/$trimmed"
    }

    private fun JSONArray?.toCategoryOptions(): List<CategoryOption> = buildList {
        val items = this@toCategoryOptions ?: return@buildList
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val name = item.optString("genre_name").trim()
            if (id.isBlank() || name.isBlank()) continue
            add(CategoryOption(id = id, name = name))
        }
    }

    private fun JSONArray?.toMangaSummaries(): List<MangaSummary> = buildList {
        val items = this@toMangaSummaries ?: return@buildList
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val seriesId = item.optString("series_id").trim()
            val title = item.optString("title").trim()
            if (seriesId.isBlank() || title.isBlank()) continue
            val latestChapter = item.optJSONArray("latest_chapters")?.optJSONObject(0)
            add(
                MangaSummary(
                    providerId = id,
                    title = title,
                    detailPath = "/series/$seriesId",
                    coverUrl = seriesImageUrl(item.optString("cover_image_id")),
                    contentType = item.optString("format").trim(),
                    status = item.optString("publication_status").trim(),
                    latestPublication = latestChapter?.optString("title").orEmpty().ifBlank {
                        latestChapter?.optString("chapter_no").orEmpty()
                    },
                    chaptersCount = item.optInt("current_books", 0).takeIf { it > 0 }?.toString().orEmpty(),
                    rating = item.optDouble("average_rating", 0.0).takeIf { it > 0 }?.let {
                        "Rating %.1f".format(it)
                    }.orEmpty(),
                    views = item.optLong("total_views", 0L).takeIf { it > 0 }?.let { "Views $it" }.orEmpty(),
                )
            )
        }
    }

    private fun JSONArray?.toMangaChapters(seriesId: String): List<MangaChapter> = buildList {
        val items = this@toMangaChapters ?: return@buildList
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val bookId = item.optString("book_id").trim()
            if (bookId.isBlank()) continue
            val title = item.optString("title").trim().ifBlank {
                item.optString("chapter_no").trim().ifBlank { "Chapter ${item.optDouble("sort_no", index.toDouble()).toInt()}" }
            }
            val chapterNumber = item.optString("chapter_no").trim().ifBlank {
                item.optDouble("sort_no", 0.0).takeIf { it > 0.0 }?.toInt()?.toString().orEmpty()
            }
            val path = "/series/$seriesId/reader/$bookId"
            add(
                MangaChapter(
                    id = bookId,
                    chapterLabel = title,
                    chapterNumberUrl = chapterNumber.ifBlank { title },
                    path = path,
                    pagesCount = item.optInt("page_count", 0).coerceAtLeast(0),
                    registrationDate = item.optString("created_at").trim(),
                    languageCode = item.optString("translated_language").trim(),
                    languageLabel = item.optString("translated_language").trim().uppercase(),
                    uploaderLabel = item.optJSONArray("groups")
                        ?.optJSONObject(0)
                        ?.optString("title")
                        .orEmpty()
                        .ifBlank { item.optJSONObject("uploader")?.optString("username").orEmpty() },
                )
            )
        }
    }.reversed()

    private fun JSONArray?.toReaderPages(token: String, cacheUrl: String, bookId: String): List<ReaderPage> = buildList {
        val items = this@toReaderPages ?: return@buildList
        val resolvedCacheUrl = cacheUrl.trim().ifBlank { "https://kstatic.to" }
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val pageId = item.optString("page_id").trim()
            val ext = item.optString("ext").trim().ifBlank { "jpg" }
            if (pageId.isBlank()) continue
            add(
                ReaderPage(
                    id = pageId,
                    numberLabel = item.optInt("page_no", index + 1).toString(),
                    imageUrl = "$resolvedCacheUrl/api/v2/books/page/$bookId/$pageId.$ext?token=$token",
                )
            )
        }
    }
}
