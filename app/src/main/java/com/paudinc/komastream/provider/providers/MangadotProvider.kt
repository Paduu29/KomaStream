package com.paudinc.komastream.provider.providers

import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.CatalogFilterOptions
import com.paudinc.komastream.data.model.CatalogSearchResult
import com.paudinc.komastream.data.model.CategoryOption
import com.paudinc.komastream.data.model.CommunitySpotlightFeed
import com.paudinc.komastream.data.model.CommunitySpotlightItem
import com.paudinc.komastream.data.model.CommunitySpotlightRange
import com.paudinc.komastream.data.model.CommunityPage
import com.paudinc.komastream.data.model.CommunityPageStat
import com.paudinc.komastream.data.model.CommunityPageType
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
import com.paudinc.komastream.utils.chapterValue
import com.paudinc.komastream.utils.normalizeStoredPath
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class MangadotProvider : MangaProvider {
    override val id: String = "mangadotnet-en"
    override val displayName: String = "Mangadotnet"
    override val language: AppLanguage = AppLanguage.EN
    override val websiteUrl: String = "https://mangadot.net"
    override val logoUrl: String = "https://mangadot.net/mangadotnet-purple.svg"

    private val baseUrl = "https://mangadot.net"
    private val client = OkHttpClient()

    override fun fetchHomeFeed(): HomeFeed {
        val homeHtml = getText("/")
        val latestUpdates = fetchSectionMangas("latest_updates")
        val recentlyAdded = fetchSectionMangas("recently_added")
        val mostTracked = fetchSectionMangas("most_tracked")
        val topRated = fetchSectionMangas("top_rated")

        return HomeFeed(
            latestUpdates = emptyList(),
            popularChapters = emptyList(),
            popularMangas = mostTracked.ifEmpty { topRated }.ifEmpty { latestUpdates },
            communitySpotlight = parseCommunitySpotlight(homeHtml),
            sections = listOfNotNull(
                latestUpdates.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "latest-updates",
                        title = "Latest Updates",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
                recentlyAdded.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "recently-added",
                        title = "Recently Added",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
                mostTracked.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "most-tracked",
                        title = "Most Tracked",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
                topRated.takeIf { it.isNotEmpty() }?.let {
                    HomeFeedSection(
                        id = "top-rated",
                        title = "Top Rated",
                        type = HomeSectionType.MANGAS,
                        mangas = it,
                    )
                },
            ),
        )
    }

    override fun fetchCatalogFilterOptions(): CatalogFilterOptions {
        return CatalogFilterOptions(
            categories = listOf(
                CategoryOption("JP", "Manga"),
                CategoryOption("KR", "Manhwa"),
                CategoryOption("CN", "Manhua"),
                CategoryOption("ONESHOT", "One Shot"),
            ),
            sortOptions = listOf(
                FilterOption("relevance", "Relevance"),
                FilterOption("latest", "Latest"),
                FilterOption("alphabetical", "A → Z"),
                FilterOption("chapters", "Chapters"),
                FilterOption("views", "Most viewed"),
                FilterOption("tracked", "Most tracked"),
                FilterOption("rating", "Top rated"),
            ),
            statusOptions = listOf(
                FilterOption("any", "Any"),
                FilterOption("Ongoing", "Ongoing"),
                FilterOption("Completed", "Completed"),
                FilterOption("Hiatus", "Hiatus"),
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
        val document = getDocument(
            buildSearchPath(
                query = query,
                categoryIds = categoryIds,
                sortBy = sortBy,
                broadcastStatus = broadcastStatus,
                page = page,
                perPage = pageSize,
            )
        )
        val pageItems = parseMangaCards(document)
        val items = pageItems.drop(localSkip).take(pageSize)
        return CatalogSearchResult(
            items = items,
            hasMore = hasNextPage(document) || localSkip + pageSize < pageItems.size,
        )
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizeStoredPath(detailPath)
        val mangaId = normalizedPath.trim('/').substringAfterLast('/').ifBlank { normalizedPath.trim('/') }
        val document = getDocument("/manga/$mangaId")
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val coverUrl = document.select("img[alt]")
            .firstOrNull { it.attr("alt").trim() == title && it.absUrl("src").isNotBlank() }
            ?.absUrl("src")
            .orEmpty()
        val bannerUrl = document.selectFirst("div[style*='background-image:url(/uploads/banners/']")
            ?.attr("style")
            ?.extractBackgroundImageUrl()
            .orEmpty()
            .ifBlank { coverUrl }
        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            .orEmpty()
        val status = document.select("span.inline-flex")
            .firstOrNull { it.text().trim() in setOf("Ongoing", "Completed", "Hiatus") }
            ?.text()
            ?.trim()
            .orEmpty()
        val publicationDate = extractJsonField(document.outerHtml(), "date_added_formatted")
        val chapters = fetchMangaChapters(mangaId)

        return MangaDetail(
            providerId = id,
            identification = mangaId,
            title = title,
            detailPath = normalizedPath.ifBlank { "/manga/$mangaId" },
            coverUrl = coverUrl,
            bannerUrl = bannerUrl,
            description = description,
            status = status,
            publicationDate = publicationDate,
            periodicity = "",
            chapters = chapters,
        )
    }

    override fun fetchCommunityPage(communityPath: String): CommunityPage? {
        val normalizedPath = normalizeStoredPath(communityPath)
        val apiPath = normalizedPath.toCommunityApiPath() ?: return null
        val raw = getText(apiPath)
        return when {
            normalizedPath.startsWith("/group/") -> parseGroupCommunityPage(raw, normalizedPath)
            normalizedPath.startsWith("/user/") -> parseProfileCommunityPage(raw, normalizedPath)
            normalizedPath.startsWith("/collections/user/") -> parseCollectionCommunityPage(raw, normalizedPath)
            else -> null
        }
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        val normalizedPath = normalizeStoredPath(chapterPath)
        val chapterId = normalizedPath.substringBefore('?').trim('/').substringAfterLast('/')
        val sourceUser = normalizedPath.queryParameter("source") == "user"
        val volumeMode = normalizedPath.queryParameter("mode") == "volume"
        val apiPath = if (sourceUser) {
            "/api/uploads/$chapterId/images"
        } else {
            "/api/chapters/$chapterId/images"
        }
        val response = getJson(apiPath)
        val chapter = response.optJSONObject("chapter") ?: JSONObject()
        val manga = response.optJSONObject("manga") ?: JSONObject()
        val images = response.optJSONArray("images")?.toReaderPages().orEmpty()

        val chapterTitle = chapter.optString("chapter_title")
            .trim()
            .ifBlank { "Chapter ${chapter.optString("chapter_number").trim()}" }
        val mangaDetailPath = manga.optLong("id").takeIf { it > 0 }
            ?.let { "/manga/$it" }
            .orEmpty()
        val previousChapterPath = buildAdjacentChapterPath(
            chapterId = response.opt("prev_chapter_id"),
            sourceUser = sourceUser,
            volumeMode = volumeMode,
            source = response.optString("prev_source"),
        )
        val nextChapterPath = buildAdjacentChapterPath(
            chapterId = response.opt("next_chapter_id"),
            sourceUser = sourceUser,
            volumeMode = volumeMode,
            source = response.optString("next_source"),
        )

        return ReaderData(
            providerId = id,
            mangaTitle = manga.optString("title").trim(),
            mangaDetailPath = mangaDetailPath.ifBlank { normalizedPath },
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
                if (referer != null) {
                    header("Referer", referer.toAbsoluteUrl())
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun fetchSectionMangas(sectionId: String): List<MangaSummary> {
        val response = getJson("/api/manga/section?id=$sectionId&adult=0")
        return response.optJSONArray("items")?.toMangaSummaries().orEmpty()
    }

    private fun parseCommunitySpotlight(html: String): CommunitySpotlightFeed? {
        val spotlightStart = html.indexOf("community_spotlight")
            .takeIf { it >= 0 } ?: return null
        val daily = parseCommunitySpotlightRange(
            html = html,
            startIndex = spotlightStart,
            rangeKey = "daily",
            nextRangeKey = "weekly",
        )
        val weekly = parseCommunitySpotlightRange(
            html = html,
            startIndex = spotlightStart,
            rangeKey = "weekly",
            nextRangeKey = "monthly",
        )
        val monthly = parseCommunitySpotlightRange(
            html = html,
            startIndex = spotlightStart,
            rangeKey = "monthly",
            nextRangeKey = null,
        )
        val ranges = buildMap {
            if (daily.isNotEmpty()) put(CommunitySpotlightRange.DAILY, daily)
            if (weekly.isNotEmpty()) put(CommunitySpotlightRange.WEEKLY, weekly)
            if (monthly.isNotEmpty()) put(CommunitySpotlightRange.MONTHLY, monthly)
        }
        if (ranges.isEmpty()) return null
        val defaultRange = when {
            CommunitySpotlightRange.WEEKLY in ranges -> CommunitySpotlightRange.WEEKLY
            CommunitySpotlightRange.DAILY in ranges -> CommunitySpotlightRange.DAILY
            else -> ranges.keys.first()
        }
        return CommunitySpotlightFeed(
            title = "Powered by the Community",
            kicker = "Powered by you",
            defaultRange = defaultRange,
            ranges = ranges,
        )
    }

    private fun parseCommunitySpotlightRange(
        html: String,
        startIndex: Int,
        rangeKey: String,
        nextRangeKey: String?,
    ): List<CommunitySpotlightItem> {
        val rangeStart = findCommunitySpotlightToken(html, rangeKey, startIndex)
            .takeIf { it >= 0 } ?: return emptyList()
        val rangeEndCandidates = buildList {
            nextRangeKey?.let { add(findCommunitySpotlightToken(html, it, rangeStart + 1)) }
            listOf("latest_updates", "recently_added", "most_tracked", "top_rated")
                .forEach { marker -> add(findCommunitySpotlightToken(html, marker, rangeStart + 1)) }
        }.filter { it >= 0 }
        val rangeEnd = rangeEndCandidates.minOrNull() ?: html.length
        val block = html.substring(rangeStart, rangeEnd)
        val normalizedBlock = block.replace("\\", "")

        val parsedItems = buildList {
            addAll(parseSerializedCommunitySpotlightItems(normalizedBlock))
            addAll(parseRenderedCommunitySpotlightItems(normalizedBlock))
        }

        return parsedItems
            .distinctBy { it.detailUrl }
            .takeIf { it.isNotEmpty() }
            .orEmpty()
    }

    private fun findCommunitySpotlightToken(html: String, token: String, startIndex: Int): Int {
        val candidates = listOf(
            html.indexOf(token, startIndex),
            html.indexOf("\"$token\"", startIndex),
            html.indexOf("""\\\"$token\\\"""", startIndex),
            html.indexOf("""\\\\\"$token\\\\\"""", startIndex),
        )
        return candidates.filter { it >= 0 }.minOrNull() ?: -1
    }

    private fun parseSerializedCommunitySpotlightItems(block: String): List<CommunitySpotlightItem> {
        val items = mutableListOf<CommunitySpotlightItem>()
        val groupUploaderRegex = Regex(
            """\"type\",\"(?<type>group|uploader)\",\d+,\"name\",\"(?<name>[^\"]+)\",\"handle\",\"(?<handle>[^\"]+)\",\"href\",\"(?<href>[^\"]+)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val groupCompactRegex = Regex(
            """(?<id>\d+),\"(?<name>[^\"]+)\",\"(?<handle>/g/[^\"]+)\",\"(?<href>/group/\d+)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val uploaderTaggedRegex = Regex(
            """\"uploader\",\"@(?<name>[^\"]+)\",\"uploader · [^\"]+\",\"(?<href>/user/[^\"]+)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val uploaderTitledRegex = Regex(
            """\"@(?<name>[^\"]+)\",\"uploader · [^\"]+\",\"(?<href>/user/[^\"]+)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val uploaderPlainRegex = Regex(
            """(?<id>\d+),\"@(?<name>[^\"]+)\",\"(?<href>/user/[^\"]+)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val collectionRegex = Regex(
            """\"(?<name>[^\"]+)\",\"by @(?<user>[^\"]+)\",\"(?<href>/collections/user/\d+)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        groupUploaderRegex.findAll(block).forEach { match ->
            val type = match.groups["type"]?.value.orEmpty()
            val name = match.groups["name"]?.value.orEmpty()
            val handle = match.groups["handle"]?.value.orEmpty()
            val href = match.groups["href"]?.value.orEmpty()
            val coverUrl = extractFirstCommunitySpotlightImage(block, match.range.last + 1)
            if (name.isNotBlank() && href.isNotBlank()) {
                items += CommunitySpotlightItem(
                    title = name,
                    subtitle = handle,
                    coverUrl = coverUrl,
                    detailUrl = href.toAbsoluteUrl(),
                    badge = when (type) {
                        "group" -> "Group"
                        "uploader" -> "Uploader"
                        else -> type.replaceFirstChar { it.uppercase() }
                    },
                    statLabel = handle,
                )
            }
        }

        groupCompactRegex.findAll(block).forEach { match ->
            val name = match.groups["name"]?.value.orEmpty()
            val handle = match.groups["handle"]?.value.orEmpty()
            val href = match.groups["href"]?.value.orEmpty()
            val coverUrl = extractFirstCommunitySpotlightImage(block, match.range.last + 1)
            if (name.isNotBlank() && href.isNotBlank()) {
                items += CommunitySpotlightItem(
                    title = name,
                    subtitle = handle,
                    coverUrl = coverUrl,
                    detailUrl = href.toAbsoluteUrl(),
                    badge = "Group",
                    statLabel = handle,
                )
            }
        }

        uploaderTaggedRegex.findAll(block).forEach { match ->
            val name = match.groups["name"]?.value.orEmpty()
            val href = match.groups["href"]?.value.orEmpty()
            val coverUrl = extractFirstCommunitySpotlightImage(block, match.range.last + 1)
            if (name.isNotBlank() && href.isNotBlank()) {
                items += CommunitySpotlightItem(
                    title = "@$name",
                    subtitle = "uploader",
                    coverUrl = coverUrl,
                    detailUrl = href.toAbsoluteUrl(),
                    badge = "Uploader",
                    statLabel = "uploader",
                )
            }
        }

        uploaderTitledRegex.findAll(block).forEach { match ->
            val name = match.groups["name"]?.value.orEmpty()
            val href = match.groups["href"]?.value.orEmpty()
            val coverUrl = extractFirstCommunitySpotlightImage(block, match.range.last + 1)
            if (name.isNotBlank() && href.isNotBlank()) {
                items += CommunitySpotlightItem(
                    title = "@$name",
                    subtitle = "uploader",
                    coverUrl = coverUrl,
                    detailUrl = href.toAbsoluteUrl(),
                    badge = "Uploader",
                    statLabel = "uploader",
                )
            }
        }

        uploaderPlainRegex.findAll(block).forEach { match ->
            val name = match.groups["name"]?.value.orEmpty()
            val href = match.groups["href"]?.value.orEmpty()
            val coverUrl = extractFirstCommunitySpotlightImage(block, match.range.last + 1)
            if (name.isNotBlank() && href.isNotBlank()) {
                items += CommunitySpotlightItem(
                    title = "@$name",
                    subtitle = "uploader",
                    coverUrl = coverUrl,
                    detailUrl = href.toAbsoluteUrl(),
                    badge = "Uploader",
                    statLabel = "uploader",
                )
            }
        }

        collectionRegex.findAll(block).forEach { match ->
            val name = match.groups["name"]?.value.orEmpty()
            val handle = match.groups["user"]?.value.orEmpty()
            val href = match.groups["href"]?.value.orEmpty()
            val coverUrl = extractFirstCommunitySpotlightImage(block, match.range.last + 1)
            if (name.isNotBlank() && href.isNotBlank()) {
                items += CommunitySpotlightItem(
                    title = name,
                    subtitle = "by @$handle",
                    coverUrl = coverUrl,
                    detailUrl = href.toAbsoluteUrl(),
                    badge = "Collection",
                    statLabel = "by @$handle",
                )
            }
        }

        return items
    }

    private fun parseRenderedCommunitySpotlightItems(block: String): List<CommunitySpotlightItem> {
        val items = mutableListOf<CommunitySpotlightItem>()
        val cardRegex = Regex(
            """<a class="cs-card cs-card--(?<kind>group|uploader|collection)" href="(?<href>[^"]+)"[^>]*>(?<body>.*?)(?:</a>)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        cardRegex.findAll(block).forEach { match ->
            val kind = match.groups["kind"]?.value.orEmpty()
            val href = match.groups["href"]?.value.orEmpty()
            val body = match.groups["body"]?.value.orEmpty()
            val name = Regex("""<div class="cs-name">([^<]+)</div>""", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(body)?.groupValues?.getOrNull(1).orEmpty()
            val handle = Regex("""<div class="cs-handle">([^<]+)</div>""", setOf(RegexOption.DOT_MATCHES_ALL))
                .find(body)?.groupValues?.getOrNull(1).orEmpty()
            val coverUrl = Regex("""(?:https?://[^"\\]+|/uploads/[^"\\]+)""")
                .find(body)?.value.orEmpty()
            if (name.isNotBlank() && href.isNotBlank()) {
                items += CommunitySpotlightItem(
                    title = name,
                    subtitle = handle,
                    coverUrl = coverUrl,
                    detailUrl = href.toAbsoluteUrl(),
                    badge = when (kind) {
                        "group" -> "Group"
                        "uploader" -> "Uploader"
                        else -> "Collection"
                    },
                    statLabel = handle,
                )
            }
        }
        return items
    }

    private fun extractFirstCommunitySpotlightImage(block: String, fromIndex: Int): String {
        val window = block.substring(fromIndex).take(2500)
        val candidate = Regex("""(?:https?://[^"\\]+|/uploads/[^"\\]+)""")
            .findAll(window)
            .map { it.value }
            .firstOrNull { it.contains("/uploads/") || it.startsWith("http") }
            .orEmpty()
        return candidate.toAbsoluteUrl()
    }

    private fun fetchMangaChapters(mangaId: String): List<MangaChapter> {
        val response = JSONArray(getText("/api/manga/$mangaId/chapters/list"))
        return buildList {
            for (index in 0 until response.length()) {
                val item = response.optJSONObject(index) ?: continue
                val chapterId = item.optString("id").trim()
                val source = item.optString("source").trim()
                val chapterNumber = item.optString("chapter_number").trim()
                val chapterTitle = item.optString("chapter_title").trim()
                val volumeNumber = item.optString("volume_number").trim()
                val pageCount = item.optInt("page_count", 0)
                val label = chapterTitle.ifBlank { "Chapter ${chapterNumber.ifBlank { chapterId }}" }
                add(
                    MangaChapter(
                        id = chapterId,
                        chapterLabel = label,
                        chapterNumberUrl = chapterNumber.ifBlank { chapterId },
                        path = buildChapterPath(chapterId, source, volumeNumber),
                        pagesCount = pageCount,
                        registrationDate = item.optString("date_added").trim(),
                        languageCode = item.optString("language").trim(),
                        languageLabel = item.optString("language").trim(),
                        uploaderLabel = item.optString("group_name").trim()
                            .ifBlank { item.optString("scanlator_name").trim() }
                            .ifBlank { item.optString("uploader_username").trim() },
                    )
                )
            }
        }.sortedWith(
            compareByDescending<MangaChapter> { chapterValue(it) }
                .thenByDescending { it.registrationDate }
                .thenByDescending { it.id }
        )
    }

    private fun buildMangaCard(element: Element): MangaSummary? {
        val link = element.attr("href").trim()
        val title = element.selectFirst("div.line-clamp-2")?.text()?.trim().orEmpty()
        val coverUrl = element.selectFirst("img")?.absUrl("src").orEmpty()
        if (!link.startsWith("/manga/") || title.isBlank()) return null
        return MangaSummary(
            providerId = id,
            title = title,
            detailPath = link.normalizePath(),
            coverUrl = coverUrl,
        )
    }

    private fun parseMangaCards(document: Document): List<MangaSummary> {
        return document.select("a[href^=/manga/]")
            .mapNotNull { card ->
                val title = card.selectFirst("div.line-clamp-2")?.text()?.trim().orEmpty()
                val coverUrl = card.selectFirst("img")?.absUrl("src").orEmpty()
                val detailPath = card.attr("href").trim()
                if (title.isBlank() || !detailPath.startsWith("/manga/")) {
                    null
                } else {
                    MangaSummary(
                        providerId = id,
                        title = title,
                        detailPath = detailPath.normalizePath(),
                        coverUrl = coverUrl,
                    )
                }
            }
            .distinctBy { it.detailPath }
    }

    private fun buildSearchPath(
        query: String,
        categoryIds: List<String>,
        sortBy: String,
        broadcastStatus: String,
        page: Int,
        perPage: Int,
    ): String {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", perPage.toString())
        val normalizedSort = sortBy.takeIf { it.isNotBlank() } ?: if (query.isNotBlank()) "relevance" else "latest"
        url.addQueryParameter("sortBy", normalizedSort)
        url.addQueryParameter("sortOrder", "desc")
        query.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("search", it) }
        broadcastStatus.takeIf { it.isNotBlank() && it != "any" }?.let { url.addQueryParameter("status", it) }
        categoryIds.forEach { origin -> url.addQueryParameter("origin", origin) }
        return url.build().toString()
    }

    private fun String.toCommunityApiPath(): String? = when {
        startsWith("/group/") -> "$this.data?_routes=pages%2FGroupDetailPage"
        startsWith("/user/") -> "$this.data?_routes=pages%2FPublicProfilePage"
        startsWith("/collections/user/") -> "$this.data?_routes=pages%2FUserCollectionDetailPage"
        else -> null
    }

    private fun parseGroupCommunityPage(raw: String, path: String): CommunityPage {
        val title = extractStringField(raw, "name").ifBlank { "Mangadot group" }
        val ownerUsername = extractStringField(raw, "owner_username")
        val avatarUrl = extractStringField(raw, "avatar_url").toAbsoluteUrl()
        val bannerUrl = extractStringField(raw, "banner_url").toAbsoluteUrl()
        val description = extractStringField(raw, "description")
        val status = extractStringField(raw, "status")
        val createdAt = extractStringField(raw, "created_at")
        val updatedAt = extractStringField(raw, "updated_at")
        val uploadCount = extractIntField(raw, "upload_count")
        val followerCount = extractIntField(raw, "follower_count")
        val memberNames = parseMemberNames(raw)
        val recentUploads = parseGroupRecentUploads(raw)

        return CommunityPage(
            providerId = id,
            type = CommunityPageType.GROUP,
            title = title,
            subtitle = ownerUsername.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(),
            avatarUrl = avatarUrl,
            bannerUrl = bannerUrl,
            description = description,
            stats = listOfNotNull(
                uploadCount.takeIf { it > 0 }?.let { CommunityPageStat("Uploads", it.toString()) },
                followerCount.takeIf { it > 0 }?.let { CommunityPageStat("Followers", it.toString()) },
                status.takeIf { it.isNotBlank() }?.let { CommunityPageStat("Status", it.replaceFirstChar(Char::uppercase)) },
                createdAt.takeIf { it.isNotBlank() }?.let { CommunityPageStat("Created", it.toHomeDateLabel()) },
                updatedAt.takeIf { it.isNotBlank() }?.let { CommunityPageStat("Updated", it.toHomeDateLabel()) },
            ),
            mangaItems = recentUploads,
            memberNames = memberNames,
        )
    }

    private fun parseProfileCommunityPage(raw: String, path: String): CommunityPage {
        val username = extractStringField(raw, "username")
        val displayName = extractStringField(raw, "display_name")
        val avatarUrl = extractStringField(raw, "profile_pic").toAbsoluteUrl()
        val bannerUrl = extractStringField(raw, "background_pic").toAbsoluteUrl()
        val bio = extractStringField(raw, "bio")
        val totalXp = extractIntField(raw, "total_xp")
        val level = extractIntField(raw, "level")
        val levelName = extractStringField(raw, "level_name")
        val currentStreak = extractIntField(raw, "current_streak")
        val longestStreak = extractIntField(raw, "longest_streak")
        val lastReadDate = extractStringField(raw, "last_read_date")
        val totalManga = extractIntField(raw, "total_manga")
        val chaptersRead = extractIntField(raw, "chapters_read")
        val achievements = parseAchievementNames(raw)
        val trackedManga = parseUserTrackedMangaItems(raw)

        return CommunityPage(
            providerId = id,
            type = CommunityPageType.PROFILE,
            title = displayName.ifBlank { "@$username" }.ifBlank { "Mangadot profile" },
            subtitle = username.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty(),
            avatarUrl = avatarUrl,
            bannerUrl = bannerUrl,
            description = bio,
            stats = listOfNotNull(
                totalXp.takeIf { it > 0 }?.let { CommunityPageStat("XP", it.toString()) },
                level.takeIf { it > 0 }?.let { CommunityPageStat("Level", buildString {
                    append(it)
                    if (levelName.isNotBlank()) {
                        append(" · ")
                        append(levelName)
                    }
                }) },
                totalManga.takeIf { it > 0 }?.let { CommunityPageStat("Titles", it.toString()) },
                chaptersRead.takeIf { it > 0 }?.let { CommunityPageStat("Chapters", it.toString()) },
                currentStreak.takeIf { it > 0 }?.let { CommunityPageStat("Streak", "${it}d") },
                longestStreak.takeIf { it > 0 }?.let { CommunityPageStat("Best streak", "${it}d") },
                lastReadDate.takeIf { it.isNotBlank() }?.let { CommunityPageStat("Last read", it.toHomeDateLabel()) },
            ),
            mangaItems = trackedManga,
            achievementNames = achievements,
        )
    }

    private fun parseCollectionCommunityPage(raw: String, path: String): CommunityPage {
        val title = extractStringField(raw, "name").ifBlank { "Collection" }
        val username = extractStringField(raw, "username")
        val avatarUrl = extractStringField(raw, "profile_pic").toAbsoluteUrl()
        val description = extractStringField(raw, "description")
        val isPublic = extractBooleanField(raw, "is_public")
        val createdAt = extractStringField(raw, "created_at")
        val updatedAt = extractStringField(raw, "updated_at")
        val mangaItems = parseCollectionMangaItems(raw)

        return CommunityPage(
            providerId = id,
            type = CommunityPageType.COLLECTION,
            title = title,
            subtitle = username.takeIf { it.isNotBlank() }?.let { "by @$it" }.orEmpty(),
            avatarUrl = avatarUrl,
            description = description,
            stats = listOfNotNull(
                mangaItems.size.takeIf { it > 0 }?.let { CommunityPageStat("Titles", it.toString()) },
                CommunityPageStat("Visibility", if (isPublic) "Public" else "Private"),
                createdAt.takeIf { it.isNotBlank() }?.let { CommunityPageStat("Created", it.toHomeDateLabel()) },
                updatedAt.takeIf { it.isNotBlank() }?.let { CommunityPageStat("Updated", it.toHomeDateLabel()) },
            ),
            mangaItems = mangaItems,
        )
    }

    private fun parseGroupRecentUploads(raw: String): List<MangaSummary> {
        val labeledRegex = Regex(
            """(?<recordId>\d+),\"manga_id\",(?<mangaId>\d+),\"chapter_number\",(?<chapterNumber>\d+),\"chapter_title\",\"(?<chapterTitle>[^\"]*)\",\"date_added\",\"language\",\"(?<language>[^\"]*)\",\"scanlator_name\",\"manga_title\",\"(?<mangaTitle>[^\"]*)\",\"manga_photo\",\"(?<photo>[^\"]*)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val compactRegex = Regex(
            """(?<recordId>\d+),(?<mangaId>\d+),(?<chapterNumber>\d+),\"(?<dateAdded>[^\"]*)\",\"(?<mangaTitle>[^\"]*)\",\"(?<photo>[^\"]*)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return buildList {
            labeledRegex.findAll(raw).forEach { match ->
                val mangaId = match.groups["mangaId"]?.value.orEmpty()
                val chapterTitle = match.groups["chapterTitle"]?.value.orEmpty()
                val language = match.groups["language"]?.value.orEmpty()
                val mangaTitle = match.groups["mangaTitle"]?.value.orEmpty()
                val photo = match.groups["photo"]?.value.orEmpty()
                if (mangaId.isBlank() || mangaTitle.isBlank()) return@forEach
                add(
                    MangaSummary(
                        providerId = id,
                        title = mangaTitle,
                        detailPath = "/manga/$mangaId",
                        coverUrl = photo.toAbsoluteUrl(),
                        status = chapterTitle.ifBlank { "Recent upload" },
                        views = language.uppercase(),
                    )
                )
            }
            compactRegex.findAll(raw).forEach { match ->
                val mangaId = match.groups["mangaId"]?.value.orEmpty()
                val chapterNumber = match.groups["chapterNumber"]?.value.orEmpty()
                val dateAdded = match.groups["dateAdded"]?.value.orEmpty()
                val mangaTitle = match.groups["mangaTitle"]?.value.orEmpty()
                val photo = match.groups["photo"]?.value.orEmpty()
                if (mangaId.isBlank() || mangaTitle.isBlank()) return@forEach
                add(
                    MangaSummary(
                        providerId = id,
                        title = mangaTitle,
                        detailPath = "/manga/$mangaId",
                        coverUrl = photo.toAbsoluteUrl(),
                        status = "Chapter ${chapterNumber.ifBlank { "?" }}",
                        latestPublication = dateAdded.toHomeDateLabel(),
                    )
                )
            }
        }.distinctBy { it.detailPath }
    }

    private fun parseCollectionMangaItems(raw: String): List<MangaSummary> {
        val titleFirstRegex = Regex(
            """(?<mangaId>\d+),\"title\",\"(?<title>[^\"]+)\",\"genres\",\\[(?:.*?)\\],\"(?<genreSample>[^\"]*)\".*?\"status\",\"(?<status>[^\"]*)\",\"photo\",\"(?<photo>[^\"]*)\".*?\"chapter_count\",(?<chapterCount>\d+).*?\"country_of_origin\",\"(?<origin>[A-Z]+)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val userListRegex = Regex(
            """\"manga_id\",(?<mangaId>\d+).*?\"title\",\"(?<title>[^\"]+)\",\"photo\",\"(?<photo>[^\"]*)\".*?\"manga_status\",\"(?<status>[^\"]*)\"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val matches = buildList {
            addAll(titleFirstRegex.findAll(raw))
            addAll(userListRegex.findAll(raw))
        }
        return matches.mapNotNull { match ->
            val mangaId = match.groups["mangaId"]?.value.orEmpty()
            val title = match.groups["title"]?.value.orEmpty()
            val photo = match.groups["photo"]?.value.orEmpty()
            val status = match.groups["status"]?.value.orEmpty()
            val chapterCount = match.groups["chapterCount"]?.value.orEmpty()
            val origin = match.groups["origin"]?.value.orEmpty()
            if (mangaId.isBlank() || title.isBlank()) return@mapNotNull null
            MangaSummary(
                providerId = id,
                title = title,
                detailPath = "/manga/$mangaId",
                coverUrl = photo.toAbsoluteUrl(),
                contentType = origin.toContentTypeLabel(),
                status = status,
                chaptersCount = chapterCount.takeIf { it.isNotBlank() && it != "0" }.orEmpty(),
            )
        }
            .distinctBy { it.detailPath }
    }

    private fun parseUserTrackedMangaItems(raw: String): List<MangaSummary> {
        return parseCollectionMangaItems(raw)
    }

    private fun parseMemberNames(raw: String): List<String> {
        val membersIndex = raw.indexOf("\"members\"")
        if (membersIndex < 0) return emptyList()
        val tail = raw.substring(membersIndex)
        return Regex("\"username\",\"([^\"]+)\"")
            .findAll(tail)
            .map { it.groupValues.getOrNull(1).orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun parseAchievementNames(raw: String): List<String> {
        val start = raw.indexOf("\"achievements\"").takeIf { it >= 0 } ?: return emptyList()
        val end = raw.indexOf("\"stats\"", start + 1).takeIf { it > start } ?: raw.length
        return Regex("\"name\",\"([^\"]+)\"")
            .findAll(raw.substring(start, end))
            .mapNotNull { match ->
                val value = match.groupValues.getOrNull(1).orEmpty()
                value.takeIf { it.isNotBlank() }
            }
            .distinct()
            .take(8)
            .toList()
    }

    private fun extractStringField(raw: String, fieldName: String): String {
        return Regex("\"$fieldName\",(?:null|\"([^\"]*)\"|(-?\\d+(?:\\.\\d+)?))", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(raw)
            ?.groupValues
            ?.let { groups ->
                groups.getOrNull(1).orEmpty().ifBlank { groups.getOrNull(2).orEmpty() }
            }
            .orEmpty()
    }

    private fun extractIntField(raw: String, fieldName: String): Int {
        return Regex("\"$fieldName\",(-?\\d+)", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }

    private fun extractBooleanField(raw: String, fieldName: String): Boolean {
        return Regex("\"$fieldName\",(true|false)", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
            ?.toBooleanStrictOrNull()
            ?: false
    }

    private fun hasNextPage(document: Document): Boolean {
        val nextButton = document.selectFirst("nav[aria-label='Pagination'] button[aria-label='Next page']")
        return nextButton != null && !nextButton.hasAttr("disabled")
    }

    private fun buildChapterPath(
        chapterId: String,
        source: String,
        volumeNumber: String,
    ): String {
        val sourceQuery = if (source == "user") "source=user" else ""
        val volumeQuery = if (source == "user" && volumeNumber.isNotBlank()) "mode=volume" else ""
        val query = listOf(sourceQuery, volumeQuery).filter { it.isNotBlank() }.joinToString("&")
        return if (query.isBlank()) {
            "/chapter/$chapterId"
        } else {
            "/chapter/$chapterId?$query"
        }.normalizePath()
    }

    private fun buildAdjacentChapterPath(
        chapterId: Any?,
        sourceUser: Boolean,
        volumeMode: Boolean,
        source: String,
    ): String? {
        val idValue = when (chapterId) {
            is Number -> chapterId.toLong().takeIf { it > 0 }?.toString()
            is String -> chapterId.trim().takeIf { it.isNotBlank() }
            else -> null
        } ?: return null
        val shouldUseUserSource = sourceUser || source == "user"
        val query = buildList {
            if (shouldUseUserSource) add("source=user")
            if (volumeMode) add("mode=volume")
        }.joinToString("&")
        return if (query.isBlank()) {
            "/chapter/$idValue"
        } else {
            "/chapter/$idValue?$query"
        }.normalizePath()
    }

    private fun getDocument(path: String): Document {
        return Jsoup.parse(getText(path), baseUrl)
    }

    private fun getJson(path: String): JSONObject {
        return JSONObject(getText(path))
    }

    private fun getText(path: String): String {
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    private fun String.toAbsoluteUrl(): String {
        val trimmed = trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return if (trimmed.startsWith("/")) "$baseUrl$trimmed" else "$baseUrl/$trimmed"
    }

    private fun String.normalizePath(): String = normalizeStoredPath(this)

    private fun String.queryParameter(name: String): String {
        val query = substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return ""
        return query.split('&')
            .mapNotNull { part ->
                val pieces = part.split('=', limit = 2)
                if (pieces.size != 2) return@mapNotNull null
                pieces[0] to pieces[1]
            }
            .firstOrNull { (key, _) -> key == name }
            ?.second
            .orEmpty()
    }

    private fun JSONArray.toMangaSummaries(): List<MangaSummary> {
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val idValue = item.optLong("id", -1)
                val title = item.optString("title").trim()
                val photo = item.optString("photo").trim()
                if (idValue <= 0 || title.isBlank()) continue
                add(
                    MangaSummary(
                        providerId = id,
                        title = title,
                        detailPath = "/manga/$idValue",
                        coverUrl = photo.toAbsoluteUrl(),
                        contentType = item.optString("country_of_origin").toContentTypeLabel(),
                        status = item.optString("status").trim(),
                        latestPublication = item.optString("last_chapter_date").trim().toHomeDateLabel(),
                        chaptersCount = item.optInt("chapter_count", 0).takeIf { it > 0 }?.toString().orEmpty(),
                        rating = item.optDouble("avg_rating", 0.0).takeIf { it > 0 }?.let { "Rating %.1f".format(it) }.orEmpty(),
                        views = item.optInt("view_count", 0).takeIf { it > 0 }?.let { "Views $it" }.orEmpty(),
                    )
                )
            }
        }
    }

    private fun JSONArray.toReaderPages(): List<ReaderPage> {
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val url = item.optString("url").trim()
                if (url.isBlank()) continue
                add(
                    ReaderPage(
                        id = item.optString("filename").trim().ifBlank { "${index + 1}" },
                        numberLabel = item.optString("filename").trim().ifBlank { "${index + 1}" },
                        imageUrl = url.toAbsoluteUrl(),
                    )
                )
            }
        }
    }

    private fun String.extractBackgroundImageUrl(): String {
        val match = Regex("""background-image:url\(([^)]+)\)""").find(this) ?: return ""
        return match.groupValues.getOrNull(1).orEmpty().trim('\'', '"')
    }

    private fun String.toContentTypeLabel(): String = when (uppercase()) {
        "JP" -> "Manga"
        "KR" -> "Manhwa"
        "CN" -> "Manhua"
        "ONESHOT" -> "One Shot"
        else -> ""
    }

    private fun String.toHomeDateLabel(): String {
        val value = trim()
        if (value.isBlank()) return ""
        return value
    }

    private fun extractJsonField(html: String, fieldName: String): String {
        val regex = Regex("""\"$fieldName\",\"([^\"]*)\"""")
        return regex.find(html)?.groupValues?.getOrNull(1).orEmpty()
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
