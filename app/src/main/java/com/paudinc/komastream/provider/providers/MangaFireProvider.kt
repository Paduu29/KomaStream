package com.paudinc.komastream.provider.providers

import android.content.Context
import com.paudinc.komastream.data.model.*
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.utils.MangaFireWebViewResolver
import com.paudinc.komastream.utils.normalizeStoredPath
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.io.IOException

class MangaFireProvider(
    context: Context? = null,
    private val client: OkHttpClient = OkHttpClient(),
) : MangaProvider {
    private val browserResolver = context?.let { MangaFireWebViewResolver(it.applicationContext, client) }
    override val id: String = "mangafire-en"
    override val displayName: String = "MangaFire"
    override val language: AppLanguage = AppLanguage.EN
    override val websiteUrl: String = BASE_URL
    override val logoUrl: String = "https://mangafire.to/assets/mangafire/favicon.svg"

    override fun fetchHomeFeed(): HomeFeed {
        val latestTitles = fetchTitleList(
            path = "/api/titles",
            query = mapOf(
                "order[chapter_updated_at]" to "desc",
                "hot" to "1",
                "page" to "1",
                "limit" to HOME_PAGE_SIZE.toString(),
            ),
        ).items
        val trendingTitles = fetchTitleList(
            path = "/api/top-titles",
            query = mapOf(
                "type" to "trending",
                "days" to "1",
                "limit" to HOME_PAGE_SIZE.toString(),
            ),
        ).items
        // Chapter endpoints are protected individually. Resolving them here
        // would launch one browser-token flow per title and block home startup.
        val latestUpdates = emptyList<ChapterSummary>()

        val sections = listOf(
            HomeFeedSection(
                id = "trending",
                title = "Trending",
                type = HomeSectionType.MANGAS,
                mangas = trendingTitles,
            ),
            HomeFeedSection(
                id = "latest-titles",
                title = "Latest Updates",
                type = HomeSectionType.MANGAS,
                mangas = latestTitles,
            ),
            HomeFeedSection(
                id = "latest-chapters",
                title = "Latest Chapters",
                type = HomeSectionType.CHAPTERS,
                chapters = latestUpdates,
            ),
        ).filter { it.mangas.isNotEmpty() || it.chapters.isNotEmpty() }

        return HomeFeed(
            latestUpdates = latestUpdates,
            popularChapters = latestUpdates,
            popularMangas = trendingTitles,
            sections = sections,
        )
    }

    override fun fetchHomeSectionPage(sectionId: String, page: Int): HomeSectionPageResult? =
        when (sectionId) {
            "latest-titles" -> fetchTitleList(
                path = "/api/titles",
                query = mapOf(
                    "order[chapter_updated_at]" to "desc",
                    "hot" to "1",
                    "page" to page.coerceAtLeast(1).toString(),
                    "limit" to HOME_PAGE_SIZE.toString(),
                ),
            ).let { result ->
                HomeSectionPageResult(
                    type = HomeSectionType.MANGAS,
                    mangas = result.items,
                    hasMore = result.hasMore,
                )
            }
            else -> null
        }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        val data = getJson("/api/filter-options").optJSONObject("data") ?: JSONObject()
        return CatalogFilterOptions(
            categories = data.optJSONArray("genres").toCategoryOptions("genres") +
                data.optJSONArray("themes").toCategoryOptions("themes") +
                data.optJSONArray("demographics").toCategoryOptions("demographics") +
                data.optJSONArray("formats").toCategoryOptions("formats"),
            sortOptions = data.optJSONArray("sorts").toFilterOptions(),
            statusOptions = data.optJSONArray("statuses").toFilterOptions(),
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
        val limit = take.coerceAtLeast(BROWSE_PAGE_SIZE)
        val page = (skip / limit) + 1
        val localSkip = skip % limit
        val url = "$BASE_URL/api/titles".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", limit.toString())
            .apply {
                if (query.isNotBlank()) addQueryParameter("keyword", query)
                addSortQuery(sortBy.ifBlank { DEFAULT_SORT })
                categoryIds.forEach { addCategoryQuery(it) }
                if (broadcastStatus.isNotBlank()) addQueryParameter("statuses[]", broadcastStatus)
            }
            .build()
            .toString()
        val response = getJsonAbsolute(url, referer = "$BASE_URL/browse")
        val pageItems = response.optJSONArray("items").toMangaSummaries()
        val meta = response.optJSONObject("meta")
        return CatalogSearchResult(
            items = pageItems.drop(localSkip).take(take),
            hasMore = meta?.optBoolean("hasNext", false) == true || localSkip + take < pageItems.size,
        )
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizePath(detailPath)
        val titleId = extractTitleId(normalizedPath)
        val absoluteDetailUrl = toAbsoluteUrl(normalizedPath)
        val browserPayload = browserResolver?.fetchDetailPayload(titleId, absoluteDetailUrl)
        val titleResponse = browserPayload?.titleResponse
            ?: getJson("/api/titles/$titleId", referer = absoluteDetailUrl)
        val title = titleResponse.optJSONObject("data") ?: JSONObject()
        val titlePath = normalizePath(title.optString("url").ifBlank { normalizedPath })
        val chapters = browserPayload?.chaptersResponse
            ?.toMangaChapters(titlePath)
            ?: fetchAllChapters(titleId, titlePath)
        val coverUrl = title.optJSONObject("poster")?.optString("large").orEmpty()
            .ifBlank { title.optJSONObject("poster")?.optString("medium").orEmpty() }
        val synopsis = Jsoup.parseBodyFragment(title.optString("synopsisHtml")).text().trim()
        val alternateTitles = title.optJSONArray("altTitles").toStringList().take(5)
        val genres = title.optJSONArray("genres").toNameList()
        val description = listOf(
            synopsis,
            alternateTitles.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Also known as: "),
            genres.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Genres: "),
        ).filterNotNull().filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { "No description" }

        return MangaDetail(
            providerId = id,
            identification = title.optString("hid").ifBlank { titleId },
            title = title.optString("title").ifBlank { normalizedPath.substringAfterLast('/').substringAfter('-') },
            detailPath = titlePath,
            coverUrl = coverUrl,
            bannerUrl = coverUrl,
            description = description,
            status = title.optString("status").toDisplayLabel(),
            publicationDate = title.opt("year")?.toString().orEmpty().takeIf { it != "null" }.orEmpty(),
            periodicity = title.optString("type").toDisplayLabel(),
            chapters = chapters,
        )
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        val normalizedPath = normalizePath(chapterPath)
        val chapterId = extractChapterId(normalizedPath)
        val data = getJson("/api/chapters/$chapterId", referer = toAbsoluteUrl(normalizedPath))
            .optJSONObject("data")
            ?: JSONObject()
        val title = data.optJSONObject("title") ?: JSONObject()
        val titlePath = normalizePath(title.optString("url"))
        val chapterNumber = data.opt("number")?.toString().orEmpty()
        val chapterName = data.optString("name")
        val chapterLabel = buildChapterLabel(chapterNumber, chapterName)

        return ReaderData(
            providerId = id,
            mangaTitle = title.optString("name").ifBlank { title.optString("title") },
            mangaDetailPath = titlePath,
            chapterTitle = chapterLabel,
            chapterPath = normalizedPath,
            previousChapterPath = data.optJSONObject("prev")?.optString("url")?.let(::normalizePath),
            nextChapterPath = data.optJSONObject("next")?.optString("url")?.let(::normalizePath),
            pages = data.optJSONArray("pages").toReaderPages(),
        )
    }

    override fun downloadBytes(url: String, referer: String?): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*,*/*;q=0.8")
            .apply { header("Referer", referer?.let(::toAbsoluteUrl) ?: BASE_URL) }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun fetchAllChapters(titleId: String, detailPath: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        var hasNext: Boolean
        do {
            val response = getJson(
                path = "/api/titles/$titleId/chapters?language=$LANGUAGE_CODE&sort=number&order=desc&page=$page&limit=$CHAPTER_PAGE_SIZE",
                referer = toAbsoluteUrl(detailPath),
            )
            val items = response.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val chapter = items.optJSONObject(index) ?: continue
                chapters += chapter.toMangaChapter(detailPath)
            }
            val meta = response.optJSONObject("meta")
            hasNext = meta?.optBoolean("hasNext", false) == true
            page += 1
        } while (hasNext && page <= MAX_CHAPTER_PAGES)
        return chapters.distinctBy { it.id }.sortedByDescending { it.chapterNumberUrl.toDoubleOrNull() ?: 0.0 }
    }

    private fun JSONObject.toMangaChapters(detailPath: String): List<MangaChapter> {
        val items = optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.toMangaChapter(detailPath)?.let(::add)
            }
        }.distinctBy { it.id }
            .sortedByDescending { it.chapterNumberUrl.toDoubleOrNull() ?: 0.0 }
    }

    private fun fetchTitleList(path: String, query: Map<String, String>): TitleListResult {
        val url = "$BASE_URL$path".toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build().toString()
        val response = getJsonAbsolute(url, referer = BASE_URL)
        val meta = response.optJSONObject("meta")
        return TitleListResult(
            items = response.optJSONArray("items").toMangaSummaries(),
            hasMore = meta?.optBoolean("hasNext", false) == true,
        )
    }

    private fun getJson(path: String, referer: String = BASE_URL): JSONObject =
        getJsonAbsolute(toAbsoluteUrl(path), referer)

    private fun getJsonAbsolute(absoluteUrl: String, referer: String): JSONObject {
        val request = Request.Builder()
            .url(absoluteUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) return JSONObject(body)
            if (response.code == 403 && body.contains("Missing token", ignoreCase = true)) {
                return browserResolver?.fetchJson(absoluteUrl, referer)
                    ?: throw IOException("MangaFire requires browser clearance")
            }
            throw IOException("MangaFire API returned HTTP ${response.code}")
        }
    }

    private fun okhttp3.HttpUrl.Builder.addSortQuery(sortBy: String) {
        val field = sortBy.substringBefore(':').ifBlank { "chapter_updated_at" }
        val direction = sortBy.substringAfter(':', "desc").ifBlank { "desc" }
        addQueryParameter("order[$field]", direction)
    }

    private fun okhttp3.HttpUrl.Builder.addCategoryQuery(categoryId: String) {
        val group = categoryId.substringBefore(':', "genres")
        val value = categoryId.substringAfter(':', categoryId)
        val parameter = when (group) {
            "themes" -> "theme_ids[]"
            "demographics" -> "demographics[]"
            "formats" -> "genres_in[]"
            else -> "genres_in[]"
        }
        addQueryParameter(parameter, value)
    }

    private fun JSONArray?.toMangaSummaries(): List<MangaSummary> =
        buildList {
            val source = this@toMangaSummaries ?: return@buildList
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                item.toMangaSummary()?.let(::add)
            }
        }

    private fun JSONObject.toMangaSummary(): MangaSummary? {
        val title = optString("title").trim()
        val detailPath = normalizePath(optString("url"))
        if (title.isBlank() || detailPath.isBlank()) return null
        val poster = optJSONObject("poster")
        return MangaSummary(
            providerId = id,
            title = title,
            detailPath = detailPath,
            coverUrl = poster?.optString("medium").orEmpty().ifBlank { poster?.optString("large").orEmpty() },
            contentType = optString("type").toDisplayLabel(),
            status = optString("status").toDisplayLabel(),
            periodicity = opt("year")?.toString().orEmpty().takeIf { it != "null" }.orEmpty(),
            latestPublication = optString("chapterUpdatedAt"),
            chaptersCount = opt("latestChapter")?.toString().orEmpty().takeIf { it != "null" }.orEmpty(),
            rating = opt("rating")?.toString().orEmpty().takeIf { it != "null" }.orEmpty(),
            views = opt("viewsTotal")?.toString().orEmpty().takeIf { it != "null" }.orEmpty(),
        )
    }

    private fun JSONObject.toMangaChapter(detailPath: String): MangaChapter {
        val number = opt("number")?.toString().orEmpty()
        val name = optString("name")
        val chapterId = optLong("id").toString()
        return MangaChapter(
            id = chapterId,
            chapterLabel = buildChapterLabel(number, name),
            chapterNumberUrl = number,
            path = chapterPath(detailPath = detailPath, chapterId = chapterId),
            pagesCount = 0,
            registrationDate = formatTimestamp(optLong("createdAt", 0L)),
            languageCode = optString("language"),
            languageLabel = optString("language").uppercase(Locale.ROOT),
            uploaderLabel = optString("type").toDisplayLabel(),
        )
    }

    private fun JSONArray?.toReaderPages(): List<ReaderPage> =
        buildList {
            val source = this@toReaderPages ?: return@buildList
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val imageUrl = item.optString("url")
                if (imageUrl.isBlank()) continue
                add(
                    ReaderPage(
                        id = (index + 1).toString(),
                        numberLabel = (index + 1).toString(),
                        imageUrl = imageUrl,
                    )
                )
            }
        }

    private fun JSONArray?.toCategoryOptions(group: String): List<CategoryOption> =
        buildList {
            val source = this@toCategoryOptions ?: return@buildList
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val value = item.opt("id")?.toString().orEmpty()
                val label = item.optString("name")
                if (value.isNotBlank() && label.isNotBlank()) add(CategoryOption("$group:$value", label))
            }
        }

    private fun JSONArray?.toFilterOptions(): List<FilterOption> =
        buildList {
            val source = this@toFilterOptions ?: return@buildList
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                val value = item.optString("value")
                val label = item.optString("label")
                if (value.isNotBlank() && label.isNotBlank()) add(FilterOption(value, label))
            }
        }

    private fun JSONArray?.toStringList(): List<String> =
        buildList {
            val source = this@toStringList ?: return@buildList
            for (index in 0 until source.length()) {
                val value = source.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }

    private fun JSONArray?.toNameList(): List<String> =
        buildList {
            val source = this@toNameList ?: return@buildList
            for (index in 0 until source.length()) {
                val value = source.optJSONObject(index)?.optString("title")
                    ?: source.optJSONObject(index)?.optString("name")
                    ?: ""
                if (value.isNotBlank()) add(value)
            }
        }

    private fun chapterPath(detailPath: String, chapterId: String): String =
        mangaFireChapterPath(normalizePath(detailPath), chapterId)

    private fun buildChapterLabel(number: String, name: String): String =
        listOf(
            number.takeIf { it.isNotBlank() }?.let { "Chapter $it" },
            name.takeIf { it.isNotBlank() },
        ).filterNotNull().joinToString(" - ").ifBlank { "Chapter" }

    private fun extractTitleId(path: String): String =
        normalizePath(path)
            .substringAfter("/title/", "")
            .substringBefore('/')
            .substringBefore('-')
            .ifBlank { path.substringAfterLast('.').substringBefore('/') }

    private fun extractChapterId(path: String): String =
        normalizePath(path)
            .substringAfterLast('/')
            .substringBefore('-')
            .takeIf { it.all(Char::isDigit) }
            ?: path.substringAfterLast('/').substringBefore('.')

    private fun formatTimestamp(timestamp: Long): String =
        if (timestamp <= 0L) {
            ""
        } else {
            DATE_FORMATTER.format(Instant.ofEpochSecond(timestamp))
        }

    private fun String.toDisplayLabel(): String =
        replace('_', ' ')
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
                }
            }

    private fun toAbsoluteUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else "$BASE_URL${normalizePath(path)}"

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        val parsed = path.toHttpUrlOrNull()
        return when {
            parsed != null -> normalizeStoredPath(parsed.encodedPath)
            path.startsWith("/") -> normalizeStoredPath(path)
            else -> normalizeStoredPath("/$path")
        }
    }

    private data class TitleListResult(
        val items: List<MangaSummary>,
        val hasMore: Boolean,
    )

    private companion object {
        private const val BASE_URL = "https://mangafire.to"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private const val LANGUAGE_CODE = "en"
        private const val HOME_PAGE_SIZE = 30
        private const val BROWSE_PAGE_SIZE = 30
        private const val CHAPTER_PAGE_SIZE = 100
        private const val MAX_CHAPTER_PAGES = 20
        private const val DEFAULT_SORT = "chapter_updated_at:desc"
        private val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    }
}

internal fun mangaFireChapterPath(detailPath: String, chapterId: String): String =
    "${detailPath.trimEnd('/')}/chapter/$chapterId"
