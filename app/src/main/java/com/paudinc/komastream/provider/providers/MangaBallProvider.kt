package com.paudinc.komastream.provider.providers

import android.content.Context
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CatalogSearchResult
import com.paudinc.komastream.data.model.CategoryOption
import com.paudinc.komastream.data.model.ChapterSummary
import com.paudinc.komastream.data.model.FilterOption
import com.paudinc.komastream.data.model.HomeFeed
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

class MangaBallProvider(
    context: Context,
) : MangaProvider {
    private val appContext = context.applicationContext

    override val id: String = PROVIDER_ID
    override val displayName: String = "MangaBall"
    override val language: AppLanguage = AppLanguage.EN
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

    override fun fetchHomeFeed(): HomeFeed {
        ensureSession()
        val latestItems = postJson(
            path = "/api/v1/title/search/",
            referer = "/",
            formValues = listOf(
                "search_type" to "getLatestTable",
                "search_limit" to "24",
            ),
        ).optJSONArray("data") ?: JSONArray()
        val popularChapterItems = postJson(
            path = "/api/v1/title/search/",
            referer = "/",
            formValues = listOf(
                "search_type" to "getRecentChapterRead",
                "search_limit" to "12",
                "search_time" to "week",
            ),
        ).optJSONArray("data") ?: JSONArray()
        val popularMangaItems = postJson(
            path = "/api/v1/title/search/",
            referer = "/",
            formValues = listOf(
                "search_type" to "getPopular",
                "search_limit" to "24",
            ),
        ).optJSONArray("data") ?: JSONArray()

        return HomeFeed(
            latestUpdates = latestItems.toChapterSummaries(),
            popularChapters = popularChapterItems.toChapterSummaries(),
            popularMangas = popularMangaItems.toMangaSummaries(),
        )
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
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
            add("filters[translatedLanguages][]" to "en")
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
        ensureSession(detailPath)
        val normalizedPath = normalizePath(detailPath)
        val document = getDocument(normalizedPath)
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
        val chapterResponse = postJson(
            path = "/api/v1/chapter/chapter-listing-by-title-id/",
            referer = normalizedPath,
            formValues = listOf(
                "title_id" to titleId,
                "userSettingsEnabled" to "false",
            ),
        )
        val chapters = parseChapters(chapterResponse.optJSONArray("ALL_CHAPTERS") ?: JSONArray())
        return MangaDetail(
            providerId = id,
            identification = titleId.ifBlank { normalizedPath.substringAfterLast('-').trimEnd('/') },
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
        val chapterListing = postJson(
            path = "/api/v1/chapter/chapter-listing-by-title-id/",
            referer = normalizedPath,
            formValues = listOf(
                "title_id" to titleId,
                "userSettingsEnabled" to "false",
                "lang" to "en",
            ),
        ).optJSONArray("ALL_CHAPTERS") ?: JSONArray()
        val chapterEntries = buildList {
            for (index in 0 until chapterListing.length()) {
                val chapter = chapterListing.optJSONObject(index) ?: continue
                val translations = chapter.optJSONArray("translations") ?: continue
                val number = chapter.optDouble("number_float", Double.NaN)
                for (translationIndex in 0 until translations.length()) {
                    val translation = translations.optJSONObject(translationIndex) ?: continue
                    if (!translation.isEnglishTranslation()) continue
                    add(ChapterEntry(number, translation.optString("id"), normalizePath(translation.optString("url"))))
                }
            }
        }
        val currentChapterId = normalizedPath.trim('/').substringAfterLast('/')
        val currentIndex = chapterEntries.indexOfFirst { it.translationId == currentChapterId }
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

    private fun ensureSession(path: String = "/") {
        ensureAdultCookie()
        if (!csrfToken.isNullOrBlank()) return
        val document = getDocument(path)
        csrfToken = document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()
    }

    private fun ensureAdultCookie() {
        val cookieStore = cookieManager.cookieStore
        val desiredValue = isAdultContentEnabled().toString()
        val existingCookies = cookieStore.get(baseUri)
        val adultCookies = existingCookies.filter { it.name == "show18PlusContent" }
        val currentValue = adultCookies.lastOrNull()?.value

        if (currentValue == desiredValue) return

        adultCookies.forEach { cookieStore.remove(baseUri, it) }
        cookieStore.add(
            baseUri,
            HttpCookie("show18PlusContent", desiredValue).apply {
                path = "/"
            }
        )
        csrfToken = null
    }

    private fun isAdultContentEnabled(): Boolean =
        appContext
            .getSharedPreferences("manga_library", Context.MODE_PRIVATE)
            .getBoolean(PREF_MANGABALL_ADULT_CONTENT, false)

    companion object {
        const val PROVIDER_ID = "mangaball-en"
        const val PREF_MANGABALL_ADULT_CONTENT = "mangaballAdultContentEnabled"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
    }

    private fun getDocument(path: String): Document {
        val request = Request.Builder()
            .url(toAbsoluteUrl(path))
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            return Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        }
    }

    private fun postJson(
        path: String,
        referer: String,
        formValues: List<Pair<String, String>>,
    ): JSONObject {
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
            return JSONObject(response.body?.string().orEmpty())
        }
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
            if (!languageCode.equals("en", ignoreCase = true)) return@mapNotNull null
            val chapterLink = row.selectFirst("a[href*=chapter-detail]") ?: return@mapNotNull null
            val chapterPath = normalizePath(chapterLink.attr("href"))
            val chapterLabel = chapterLink.text().trim()
            if (chapterPath.isBlank() || chapterLabel.isBlank()) return@mapNotNull null
            ChapterSummary(
                providerId = id,
                mangaTitle = mangaTitle,
                chapterLabel = chapterLabel,
                chapterNumberUrl = chapterPath.trim('/').substringAfterLast('/'),
                chapterId = chapterPath.trim('/').substringAfterLast('/'),
                mangaPath = mangaPath,
                chapterPath = chapterPath,
                coverUrl = coverUrl,
                registrationLabel = row.selectFirst(".text-muted")?.text()?.substringAfterLast(' ')?.trim().orEmpty(),
            )
        }
    }

    private fun parseChapters(chapters: JSONArray): List<MangaChapter> = buildList {
        for (index in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(index) ?: continue
            val number = chapter.optString("number")
            val numberFloat = chapter.optDouble("number_float", Double.NaN)
            val translations = chapter.optJSONArray("translations") ?: continue
            val englishTranslations = buildList {
                for (translationIndex in 0 until translations.length()) {
                    val translation = translations.optJSONObject(translationIndex) ?: continue
                    if (translation.isEnglishTranslation()) {
                        add(translation)
                    }
                }
            }.sortedByDescending { it.optString("date") }
            englishTranslations.forEach { translation ->
                val path = normalizePath(translation.optString("url"))
                val group = translation.optJSONObject("group")?.optString("name").orEmpty()
                val languageCode = translation.optString("language").trim()
                val languageLabel = translation.optString("languageName").trim()
                val label = translation.optString("name")
                    .trim()
                    .ifBlank { number }
                add(
                    MangaChapter(
                        id = translation.optString("id"),
                        chapterLabel = label,
                        chapterNumberUrl = translation.optString("id"),
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

    private fun JSONObject.isEnglishTranslation(): Boolean =
        optString("language").equals("en", ignoreCase = true)

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

    private data class ChapterEntry(
        val number: Double,
        val translationId: String,
        val path: String,
    )
}
