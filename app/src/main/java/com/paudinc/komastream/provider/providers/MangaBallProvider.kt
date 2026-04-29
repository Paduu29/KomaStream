package com.paudinc.komastream.provider.providers

import android.content.Context
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CatalogSearchResult
import com.paudinc.komastream.data.model.CategoryOption
import com.paudinc.komastream.data.model.ChapterSourceOption
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
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import java.util.concurrent.TimeUnit

class MangaBallProvider(
    context: Context,
) : MangaProvider {
    private val appContext = context.applicationContext
    private val sessionLock = Any()

    override val id: String = PROVIDER_ID
    override val displayName: String = "MangaBall"
    override val language: AppLanguage = AppLanguage.MULTI
    override val websiteUrl: String = "https://mangaball.net"
    override val logoUrl: String = "https://mangaball.net/public/frontend/images/favicon.png"

    private val baseUrl = "https://mangaball.net"
    private val baseUri = URI(baseUrl)
    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }
    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .build()

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
        ensureSession()
        val sectionRequests = listOf(
            HomeSectionRequest(
                id = "featured",
                title = "Featured",
                type = HomeSectionType.MANGAS,
                formValues = listOf(
                    "search_type" to "getFeatured",
                    "search_limit" to "12",
                ),
            ),
            HomeSectionRequest(
                id = "latest-updates",
                title = "Latest Updates",
                type = HomeSectionType.CHAPTERS,
                formValues = listOf(
                    "search_type" to "getLatestTable",
                    "search_limit" to "24",
                ),
            ),
            HomeSectionRequest(
                id = "recommended-titles",
                title = "Titles Recommended",
                type = HomeSectionType.MANGAS,
                formValues = listOf(
                    "search_type" to "getRecommend",
                    "search_limit" to "24",
                ),
            ),
            HomeSectionRequest(
                id = "top-viewed-titles",
                title = "Top Viewed Titles",
                type = HomeSectionType.MANGAS,
                formValues = listOf(
                    "search_type" to "getRecentRead",
                    "search_limit" to "24",
                    "search_time" to "week",
                ),
            ),
            HomeSectionRequest(
                id = "by-origin",
                title = "By Origin",
                type = HomeSectionType.MANGAS,
                formValues = listOf(
                    "search_type" to "getByOrigin",
                    "search_limit" to "24",
                    "search_origin" to "all",
                ),
            ),
            HomeSectionRequest(
                id = "recent-chapter-read",
                title = "Recent Chapter Read",
                type = HomeSectionType.CHAPTERS,
                formValues = listOf(
                    "search_type" to "getRecentChapterRead",
                    "search_limit" to "24",
                    "search_time" to "week",
                ),
            ),
            HomeSectionRequest(
                id = "popular-this-season",
                title = "Popular This Season",
                type = HomeSectionType.MANGAS,
                formValues = listOf(
                    "search_type" to "getPopular",
                    "search_limit" to "24",
                ),
            ),
        )
        val sectionsById = fetchHomeSections(sectionRequests)
        val latestUpdates = sectionsById.getValue("latest-updates")
        val recentChapterRead = sectionsById.getValue("recent-chapter-read")
        val popularThisSeason = sectionsById.getValue("popular-this-season")
        val sections = listOf(
            sectionsById.getValue("featured"),
            sectionsById.getValue("recommended-titles"),
            sectionsById.getValue("top-viewed-titles"),
            latestUpdates,
            sectionsById.getValue("by-origin"),
            recentChapterRead,
            popularThisSeason,
        ).filter { it.chapters.isNotEmpty() || it.mangas.isNotEmpty() }

        return HomeFeed(
            latestUpdates = latestUpdates.chapters,
            popularChapters = recentChapterRead.chapters,
            popularMangas = popularThisSeason.mangas,
            sections = sections,
        ).also { homeFeedCache = TimedValue(adultContentEnabled, it) }
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        val adultContentEnabled = isAdultContentEnabled()
        catalogFilterCache?.takeIf { it.isValidFor(adultContentEnabled, FILTER_CACHE_MS) }?.let { return it.value }
        ensureSession("/search-advanced")
        val document = getDocument("/search-advanced")
        val sortOptions = document.select("#sortBy option[value]").mapNotNull { option ->
            val value = option.attr("value").trim()
            val label = option.text().trim()
            if (value.isBlank() || label.isBlank()) null else FilterOption(value, label)
        }
        val statusOptions = document.select("#publicationStatus option[value]").mapNotNull { option ->
            val value = option.attr("value").trim()
            val label = option.text().trim()
            if (value.isBlank() || label.isBlank() || value == "any") null else FilterOption(value, label)
        }
        val tagGroups = postJson(
            path = "/api/v1/tag/search/",
            referer = "/search-advanced",
            formValues = listOf("search_type" to "getTagFilter"),
        ).optJSONObject("data")
        val categories = tagGroups?.optJSONArray("genre")?.let { genres ->
            buildList(genres.length()) {
                for (index in 0 until genres.length()) {
                    val tag = genres.optJSONObject(index) ?: continue
                    val tagId = tag.optString("_id")
                    val name = tag.optString("name")
                    if (tagId.isNotBlank() && name.isNotBlank()) {
                        add(CategoryOption(tagId, name))
                    }
                }
            }
        }.orEmpty()
        return CatalogFilterOptions(
            categories = categories,
            sortOptions = sortOptions,
            statusOptions = statusOptions,
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
        ensureSession("/search-advanced")
        val page = (skip / take.coerceAtLeast(1)) + 1
        val formValues = buildList {
            add("search_input" to query)
            add("filters[page]" to page.toString())
            add("filters[sort]" to sortBy.ifBlank { "updated_chapters_desc" })
            add("filters[contentRating]" to "any")
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
            referer = "/search-advanced",
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
        val detailRequest = parseDetailRequest(detailPath)
        ensureSession(detailRequest.basePath)
        val document = getDocument(detailRequest.basePath)
        val title = document.selectFirst("#comicDetail h6")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" Online Free")?.trim()
            .orEmpty()
        val coverUrl = document.selectFirst(".featured-cover")?.absUrl("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            .orEmpty()
        val alternateTitle = document.selectFirst(".alternate-name-container")?.text()?.trim().orEmpty()
        val synopsis = document.selectFirst("#comicDescription .description-text p")?.text()?.trim().orEmpty()
        val description = listOf(synopsis, alternateTitle)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { "No description" }
        val status = document.selectFirst(".badge-status")?.text()?.trim().orEmpty()
        val publicationDate = document.select("span.badge").firstOrNull { it.text().contains("Published:", ignoreCase = true) }
            ?.text()
            ?.substringAfter("Published:")
            ?.trim()
            .orEmpty()
        val titleId = Regex("const\\s+titleId\\s*=\\s*'([^']+)'")
            .find(document.html())
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        val allChapters = fetchChapterListing(titleId, detailRequest.basePath)
        val chapterSources = buildLanguageOptions(detailRequest.basePath, allChapters)
        val selectedLanguageId = chapterSources
            .firstOrNull { it.id == detailRequest.selectedLanguageId }
            ?.id
            ?: LANGUAGE_ALL
        val chapters = parseChapters(
            chapters = allChapters,
            selectedLanguageId = selectedLanguageId,
        )
        return MangaDetail(
            providerId = id,
            identification = titleId.ifBlank { detailRequest.basePath.substringAfterLast('-').trimEnd('/') },
            title = title,
            detailPath = withLanguageFilter(detailRequest.basePath, selectedLanguageId),
            coverUrl = coverUrl,
            bannerUrl = coverUrl,
            description = description,
            status = status,
            publicationDate = publicationDate,
            periodicity = "",
            chapters = chapters,
            chapterSources = chapterSources,
            selectedChapterSourceId = selectedLanguageId,
        )
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        ensureSession(chapterPath)
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
            ?.substringBefore(" Ch.")
            ?.substringBefore(" Online Free")
            ?.trim()
            .orEmpty()
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
                val number = chapter.optDouble("number_float", Double.NaN)
                for (translationIndex in 0 until translations.length()) {
                    val translation = translations.optJSONObject(translationIndex) ?: continue
                    add(
                        ChapterEntry(
                            number = number,
                            translationId = translation.optString("id"),
                            path = normalizePath(translation.optString("url")),
                            languageCode = translation.optString("language").trim().lowercase(),
                        )
                    )
                }
            }
        }
        val currentChapterId = normalizedPath.trim('/').substringAfterLast('/')
        val currentEntry = chapterEntries.firstOrNull { it.translationId == currentChapterId }
        val navigationEntries = if (currentEntry?.languageCode.isNullOrBlank()) {
            chapterEntries
        } else {
            chapterEntries.filter { it.languageCode == currentEntry?.languageCode }
        }
        val currentIndex = navigationEntries.indexOfFirst { it.translationId == currentChapterId }
        val previousChapterPath = navigationEntries.getOrNull(currentIndex + 1)?.path
        val nextChapterPath = navigationEntries.getOrNull(currentIndex - 1)?.path

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
            .apply {
                if (!referer.isNullOrBlank()) {
                    header("Referer", toAbsoluteUrl(referer))
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    override fun invalidateCaches() {
        synchronized(sessionLock) {
            csrfToken = null
            homeFeedCache = null
            catalogFilterCache = null
            chapterListingCache.clear()
        }
    }

    private fun ensureSession(path: String = "/") {
        synchronized(sessionLock) {
            ensureAdultCookie()
            if (!csrfToken.isNullOrBlank()) return
            val document = getDocument(path, retry = false)
            csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()
        }
    }

    private fun ensureAdultCookie() {
        val cookieStore = cookieManager.cookieStore
        val adultContentEnabled = isAdultContentEnabled()
        val existingCookies = cookieStore.get(baseUri)
        val adultCookies = existingCookies.filter { it.name == "show18PlusContent" }
        val currentValue = adultCookies.lastOrNull()?.value

        // MangaBall works with the opt-in cookie present for adult mode.
        // When adult mode is off, omitting the cookie is more reliable than sending "false".
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
        invalidateCaches()
    }

    private fun isAdultContentEnabled(): Boolean =
        appContext
            .getSharedPreferences("manga_library", Context.MODE_PRIVATE)
            .getBoolean(PREF_MANGABALL_ADULT_CONTENT, false)

    private fun getDocument(path: String, retry: Boolean = true): Document {
        return runCatching {
            val request = Request.Builder()
                .url(toAbsoluteUrl(path))
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
            }
        }.getOrElse { error ->
            if (!retry) throw error
            invalidateCaches()
            ensureSession(path)
            getDocument(path, retry = false)
        }
    }

    private fun postJson(
        path: String,
        referer: String,
        formValues: List<Pair<String, String>>,
        retry: Boolean = true,
    ): JSONObject {
        return runCatching {
            ensureSession(referer)
            val body = FormBody.Builder().apply {
                formValues.forEach { (key, value) -> add(key, value) }
            }.build()
            val request = Request.Builder()
                .url(toAbsoluteUrl(path))
                .header("User-Agent", USER_AGENT)
                .header("Referer", toAbsoluteUrl(referer))
                .header("X-CSRF-TOKEN", csrfToken.orEmpty())
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrElse { error ->
            if (!retry) throw error
            invalidateCaches()
            ensureSession(referer)
            postJson(path, referer, formValues, retry = false)
        }
    }

    private fun fetchHomeSection(
        id: String,
        title: String,
        type: HomeSectionType,
        formValues: List<Pair<String, String>>,
    ): HomeFeedSection {
        val items = postJson(
            path = "/api/v1/title/search/",
            referer = "/",
            formValues = formValues,
        ).optJSONArray("data") ?: JSONArray()
        return when (type) {
            HomeSectionType.CHAPTERS -> HomeFeedSection(
                id = id,
                title = title,
                type = type,
                chapters = items.toChapterSummaries(),
            )
            HomeSectionType.MANGAS -> HomeFeedSection(
                id = id,
                title = title,
                type = type,
                mangas = items.toMangaSummaries(),
            )
        }
    }

    private fun fetchHomeSections(requests: List<HomeSectionRequest>): Map<String, HomeFeedSection> {
        if (requests.isEmpty()) return emptyMap()
        return runCatching {
            val executor = Executors.newFixedThreadPool(minOf(requests.size, HOME_SECTION_PARALLELISM))
            try {
                executor.invokeAll(
                    requests.map { request ->
                        Callable {
                            request.id to fetchHomeSection(
                                id = request.id,
                                title = request.title,
                                type = request.type,
                                formValues = request.formValues,
                            )
                        }
                    }
                ).associate { it.get() }
            } finally {
                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }.getOrElse {
            invalidateCaches()
            ensureSession()
            requests.associate { request ->
                request.id to fetchHomeSection(
                    id = request.id,
                    title = request.title,
                    type = request.type,
                    formValues = request.formValues,
                )
            }
        }
    }

    private fun fetchChapterListing(titleId: String, referer: String): JSONArray {
        if (titleId.isBlank()) return JSONArray()
        val cacheKey = "${isAdultContentEnabled()}::$titleId"
        chapterListingCache[cacheKey]
            ?.takeIf { System.currentTimeMillis() - it.cachedAtMillis <= CHAPTER_LISTING_CACHE_MS }
            ?.let { return JSONArray(it.value.toString()) }
        val chapters = postJson(
            path = "/api/v1/chapter/chapter-listing-by-title-id/",
            referer = referer,
            formValues = listOf(
                "title_id" to titleId,
                "userSettingsEnabled" to "false",
            ),
        ).optJSONArray("ALL_CHAPTERS") ?: JSONArray()
        chapterListingCache[cacheKey] = TimedValue(isAdultContentEnabled(), JSONArray(chapters.toString()))
        return chapters
    }

    private fun JSONArray.toMangaSummaries(): List<MangaSummary> = buildList(length()) {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            add(item.toMangaSummary())
        }
    }

    private fun JSONArray.toChapterSummaries(): List<ChapterSummary> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            addAll(item.toChapterSummaries())
        }
    }

    private fun JSONObject.toMangaSummary(): MangaSummary =
        MangaSummary(
            providerId = id,
            title = optString("name"),
            detailPath = normalizePath(optString("url")),
            coverUrl = optString("cover"),
            status = optString("status").htmlText(),
            latestPublication = optString("updated_at"),
            views = "",
        )

    private fun JSONObject.toChapterSummaries(): List<ChapterSummary> {
        val document = Jsoup.parseBodyFragment(optString("last_chapter"), baseUrl)
        val mangaTitle = optString("name")
        val mangaPath = normalizePath(optString("url"))
        val coverUrl = optString("cover")
        return document.select("div.d-flex.align-items-center.gap-2.flex-nowrap").mapNotNull { row ->
            val languageCode = row.select("img[title], img[alt]").lastOrNull()
                ?.attr("title")
                ?.ifBlank { row.select("img[title], img[alt]").lastOrNull()?.attr("alt") }
                ?.trim()
                .orEmpty()
            val chapterLink = row.selectFirst("a[href*=chapter-detail]") ?: return@mapNotNull null
            val chapterPath = normalizePath(chapterLink.attr("href"))
            val chapterLabel = chapterLink.text().trim()
            if (chapterPath.isBlank() || chapterLabel.isBlank()) return@mapNotNull null
            val registrationLabel = buildList {
                languageCode.takeIf { it.isNotBlank() }?.let { add(languageDisplayLabel(it, it)) }
                row.selectFirst(".text-muted")
                    ?.text()
                    ?.substringAfterLast(' ')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }.joinToString(" • ")
            ChapterSummary(
                providerId = id,
                mangaTitle = mangaTitle,
                chapterLabel = chapterLabel,
                chapterNumberUrl = chapterLabel,
                chapterId = chapterPath.trim('/').substringAfterLast('/'),
                mangaPath = mangaPath,
                chapterPath = chapterPath,
                coverUrl = coverUrl,
                registrationLabel = registrationLabel,
            )
        }
    }

    private fun parseChapters(
        chapters: JSONArray,
        selectedLanguageId: String,
    ): List<MangaChapter> = buildList {
        for (index in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(index) ?: continue
            val chapterNumber = chapter.optDouble("number_float", Double.NaN)
            val chapterNumberLabel = chapter.optString("number")
            val translations = chapter.optJSONArray("translations") ?: continue
            val visibleTranslations = buildList {
                for (translationIndex in 0 until translations.length()) {
                    val translation = translations.optJSONObject(translationIndex) ?: continue
                    val languageCode = translation.optString("language").trim().lowercase()
                    if (selectedLanguageId == LANGUAGE_ALL || languageCode == selectedLanguageId) {
                        add(translation)
                    }
                }
            }.sortedByDescending { it.optString("date") }
            visibleTranslations.forEach { translation ->
                val path = normalizePath(translation.optString("url"))
                val group = translation.optJSONObject("group")?.optString("name").orEmpty()
                val languageCode = translation.optString("language").trim().lowercase()
                val languageLabel = languageDisplayLabel(
                    code = languageCode,
                    label = translation.optString("languageName").trim(),
                )
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

    private fun buildLanguageOptions(
        detailPath: String,
        chapters: JSONArray,
    ): List<ChapterSourceOption> {
        val languages = linkedMapOf<String, String>()
        for (index in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(index) ?: continue
            val translations = chapter.optJSONArray("translations") ?: continue
            for (translationIndex in 0 until translations.length()) {
                val translation = translations.optJSONObject(translationIndex) ?: continue
                val languageCode = translation.optString("language").trim().lowercase()
                if (languageCode.isBlank()) continue
                languages.putIfAbsent(
                    languageCode,
                    languageDisplayLabel(
                        code = languageCode,
                        label = translation.optString("languageName").trim(),
                    ),
                )
            }
        }
        if (languages.isEmpty()) return emptyList()
        return buildList {
            add(ChapterSourceOption(LANGUAGE_ALL, "All languages", withLanguageFilter(detailPath, LANGUAGE_ALL)))
            languages
                .toList()
                .sortedBy { (_, label) -> label.lowercase() }
                .forEach { (languageCode, label) ->
                    add(ChapterSourceOption(languageCode, label, withLanguageFilter(detailPath, languageCode)))
                }
        }
    }

    private fun parseDetailRequest(detailPath: String): DetailRequest {
        val normalizedPath = normalizePath(detailPath)
        val pathPart = normalizedPath.substringBefore("?")
        val queryPart = normalizedPath.substringAfter("?", "")
        if (queryPart.isBlank()) return DetailRequest(pathPart, LANGUAGE_ALL)

        var selectedLanguageId = LANGUAGE_ALL
        val remainingQueryParts = mutableListOf<String>()
        queryPart.split("&")
            .filter { it.isNotBlank() }
            .forEach { part ->
                val key = part.substringBefore("=")
                val value = part.substringAfter("=", "")
                if (key == DETAIL_LANGUAGE_QUERY && value.isNotBlank()) {
                    selectedLanguageId = value.lowercase()
                } else {
                    remainingQueryParts += part
                }
            }
        val basePath = if (remainingQueryParts.isEmpty()) {
            pathPart
        } else {
            "$pathPart?${remainingQueryParts.joinToString("&")}"
        }
        return DetailRequest(basePath, selectedLanguageId)
    }

    private fun withLanguageFilter(detailPath: String, languageId: String): String {
        val basePath = parseDetailRequest(detailPath).basePath
        if (languageId == LANGUAGE_ALL) return basePath
        val separator = if ("?" in basePath) "&" else "?"
        return "$basePath${separator}$DETAIL_LANGUAGE_QUERY=$languageId"
    }

    private fun languageDisplayLabel(code: String, label: String): String {
        if (label.isNotBlank()) return label
        if (code.isBlank()) return ""
        return if (code.length <= 3) code.uppercase() else code.replaceFirstChar { it.uppercase() }
    }

    private fun formatChapterNumber(value: Double): String {
        val whole = value.toLong()
        return if (value == whole.toDouble()) whole.toString() else value.toString().trimEnd('0').trimEnd('.')
    }

    private fun extractMangaDetailPath(document: Document, html: String, titleId: String): String {
        document.select("script[type=application/ld+json]").forEach { script ->
            val match = Regex("""https?://[^"']+/title-detail/[^"']+""")
                .find(script.html())
                ?.value
                ?.let(::normalizePath)
            if (!match.isNullOrBlank()) return match
        }

        document.select("meta[property=og:image]").attr("content")

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

    private fun String.htmlText(): String =
        if (isBlank()) "" else Jsoup.parseBodyFragment(this).text().trim()

    private fun toAbsoluteUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else "$baseUrl${normalizePath(path)}"

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        val parsed = path.toHttpUrlOrNull()
        return when {
            parsed != null -> parsed.encodedPath + parsed.encodedQuery?.let { "?$it" }.orEmpty()
            path.startsWith("/") -> path
            else -> "/$path"
        }
    }

    companion object {
        const val PROVIDER_ID = "mangaball-en"
        const val PREF_MANGABALL_ADULT_CONTENT = "mangaballAdultContentEnabled"
        private const val DETAIL_LANGUAGE_QUERY = "__lang"
        private const val LANGUAGE_ALL = "all"
        private const val HOME_FEED_CACHE_MS = 2 * 60 * 1000L
        private const val FILTER_CACHE_MS = 30 * 60 * 1000L
        private const val CHAPTER_LISTING_CACHE_MS = 10 * 60 * 1000L
        private const val HOME_SECTION_PARALLELISM = 4
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
    }

    private data class ChapterEntry(
        val number: Double,
        val translationId: String,
        val path: String,
        val languageCode: String,
    )

    private data class HomeSectionRequest(
        val id: String,
        val title: String,
        val type: HomeSectionType,
        val formValues: List<Pair<String, String>>,
    )

    private data class DetailRequest(
        val basePath: String,
        val selectedLanguageId: String,
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
