package com.paudinc.komastream.provider.providers

import android.content.Context
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Manhwa18Provider(
    context: Context,
) : MangaProvider {
    companion object {
        const val PROVIDER_ID = "manhwa18-en"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private const val HOME_SECTION_POPULAR = "popular-manga"
        private const val HOME_SECTION_LATEST_MAHNWA = "latest-update-manhwa"
        private const val HOME_SECTION_LATEST_RAW = "latest-raw-sub-kor"
        private const val HOME_SECTION_LATEST_ART = "latest-update-art"
        private const val HOME_SECTION_NEW = "new-manhwa"
    }

    override val id: String = PROVIDER_ID
    override val displayName: String = "Manhwa18"
    override val language: AppLanguage = AppLanguage.EN
    override val websiteUrl: String = "https://www.manhwa18.com"
    override val logoUrl: String = "https://www.manhwa18.com/favicon.ico"
    override val isAdultOnly: Boolean = true

    private val baseUrl = websiteUrl
    private val client = OkHttpClient()

    override fun fetchHomeFeed(): HomeFeed {
        val document = getDocument("/")
        val sections = parseHomeSections(document)
        val latestUpdates = sections.asSequence()
            .filter { it.type == HomeSectionType.CHAPTERS }
            .flatMap { it.chapters.asSequence() }
            .distinctBy { it.chapterPath }
            .toList()
        val popularMangas = sections.firstOrNull { it.id == HOME_SECTION_POPULAR }?.mangas.orEmpty()
        return HomeFeed(
            latestUpdates = latestUpdates,
            popularChapters = latestUpdates,
            popularMangas = popularMangas,
            sections = sections,
        )
    }

    override fun fetchHomeSectionPage(sectionId: String, page: Int): HomeSectionPageResult? {
        if (page < 1) return null
        val spec = homeSectionSpec(sectionId) ?: return null
        val document = getDocument(appendPage(spec.path, page))
        return when (spec.type) {
            HomeSectionType.MANGAS -> HomeSectionPageResult(
                type = HomeSectionType.MANGAS,
                mangas = parseListingCards(document).map { it.manga },
                hasMore = hasNextPage(document),
            )

            HomeSectionType.CHAPTERS -> HomeSectionPageResult(
                type = HomeSectionType.CHAPTERS,
                chapters = parseListingCards(document).mapNotNull { it.toChapterSummary() },
                hasMore = hasNextPage(document),
            )
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
        val collected = mutableListOf<MangaSummary>()
        var currentPage = page
        var firstPageSkip = localSkip
        var hasMore = false

        while (collected.size < pageSize) {
            val document = getDocument(appendPage(buildSearchPath(query), currentPage))
            val pageItems = parseListingCards(document).map { it.manga }
            val items = if (currentPage == page) pageItems.drop(firstPageSkip) else pageItems
            collected.addAll(items.take(pageSize - collected.size))
            hasMore = hasNextPage(document)
            if (!hasMore || items.size < pageSize - collected.size) {
                break
            }
            currentPage += 1
            firstPageSkip = 0
        }

        return CatalogSearchResult(
            items = collected,
            hasMore = hasMore || collected.size < pageSize && currentPage > page,
        )
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizeStoredPath(detailPath)
        val detailDocument = getDocument(normalizedPath.ifBlank { "/" })
        val title = listOfNotNull(
            detailDocument.selectFirst(".series-name a")?.text()?.trim(),
            detailDocument.selectFirst("meta[property=og:title]")?.attr("content")?.trim(),
            detailDocument.selectFirst("h1")?.text()?.trim(),
            normalizedPath.trim('/').substringAfterLast('/').replace('-', ' ').trim(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val coverUrl = listOfNotNull(
            detailDocument.selectFirst(".series-cover .content[data-bg]")?.let(::imageUrlFromElement),
            detailDocument.selectFirst(".series-cover .content")?.let(::imageUrlFromElement),
            detailDocument.selectFirst(".series-cover img")?.let(::imageUrlFromElement),
            detailDocument.selectFirst("meta[property=og:image]")?.attr("content")?.trim(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val description = listOfNotNull(
            detailDocument.selectFirst("meta[property=og:description]")?.attr("content")?.trim(),
            detailDocument.selectFirst("meta[name=description]")?.attr("content")?.trim(),
            detailDocument.selectFirst(".summary")?.text()?.trim(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val status = infoValue(detailDocument, "Status")
        val publicationDate = ""
        val chapters = parseDetailChapters(detailDocument)
        val identification = normalizedPath.trim('/').substringAfterLast('/').ifBlank {
            title.lowercase().replace(' ', '-')
        }

        return MangaDetail(
            providerId = id,
            identification = identification,
            title = title,
            detailPath = normalizedPath.ifBlank { "/manga/$identification" },
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
        val document = getDocument(normalizedPath)
        val chapterTitle = listOfNotNull(
            document.selectFirst("meta[name=chapter_name]")?.attr("content")?.trim(),
            document.selectFirst(".chapter-navigator .chapter-nav-item.active")?.text()?.trim(),
            document.selectFirst("title")?.text()?.substringBefore(" - manga")?.trim(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val mangaDetailPath = listOfNotNull(
            document.selectFirst(".rd_sidebar-header h5 a[href*='/manga/']")?.attr("href").orEmpty().let(::normalizeStoredPath),
            document.selectFirst(".chapter-navigator a[href*='/manga/']")?.attr("href").orEmpty().let(::normalizeStoredPath),
            document.selectFirst(".breadcrumb a[href*='/manga/']")?.attr("href").orEmpty().let(::normalizeStoredPath),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
            .ifBlank { detailPathFromChapterPath(normalizedPath) }
        val mangaTitle = listOfNotNull(
            document.selectFirst(".rd_sidebar-header h5 a")?.text()?.trim(),
            document.selectFirst(".chapter-navigator a[href*='/manga/']")?.text()?.trim(),
            document.selectFirst("meta[property=og:title]")?.attr("content")?.trim(),
            chapterTitle.substringBefore(" -").trim(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val chapterOptions = parseDetailChapters(document)
        val currentIndex = chapterOptions.indexOfFirst { sameChapterPath(id, it.path, normalizedPath) }
        val previousChapterPath = if (currentIndex >= 0) chapterOptions.getOrNull(currentIndex + 1)?.path else null
        val nextChapterPath = if (currentIndex >= 0) chapterOptions.getOrNull(currentIndex - 1)?.path else null
        val pages = document.select("#chapter-content img").mapIndexedNotNull { index, image ->
            val imageUrl = imageUrlFromElement(image)
            if (imageUrl.isBlank()) return@mapIndexedNotNull null
            ReaderPage(
                id = imageUrl.substringAfterLast('/').substringBefore('?').ifBlank { "page-${index + 1}" },
                numberLabel = (index + 1).toString(),
                imageUrl = imageUrl,
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

    override fun invalidateCaches() = Unit

    private fun parseHomeSections(document: Document): List<HomeFeedSection> {
        return document.select("main .card.card-dark")
            .mapNotNull { card ->
                val title = card.selectFirst(".card-title")?.text()?.cleanText().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                val cards = parseListingCards(card)
                when (title) {
                    "Popular manga" -> {
                        val mangas = cards.map { it.manga }.distinctBy { it.detailPath }
                        mangas.takeIf { it.isNotEmpty() }?.let {
                            HomeFeedSection(
                                id = HOME_SECTION_POPULAR,
                                title = title,
                                type = HomeSectionType.MANGAS,
                                mangas = it,
                            )
                        }
                    }

                    "Latest update Manhwa" -> chapterSection(HOME_SECTION_LATEST_MAHNWA, title, cards)
                    "Latest Raw Sub Kor" -> chapterSection(HOME_SECTION_LATEST_RAW, title, cards)
                    "Latest update Art" -> chapterSection(HOME_SECTION_LATEST_ART, title, cards)
                    "New Manhwa" -> chapterSection(HOME_SECTION_NEW, title, cards)
                    else -> null
                }
            }
    }

    private fun chapterSection(sectionId: String, title: String, cards: List<ListingCard>): HomeFeedSection? {
        val chapters = cards.mapNotNull { it.toChapterSummary() }.distinctBy { it.chapterPath }
        return chapters.takeIf { it.isNotEmpty() }?.let {
            HomeFeedSection(
                id = sectionId,
                title = title,
                type = HomeSectionType.CHAPTERS,
                chapters = it,
            )
        }
    }

    private fun parseListingCards(root: Element): List<ListingCard> {
        return root.select(".popular-thumb-item, .thumb-item-flow")
            .mapNotNull { card ->
                if (card.hasClass("see-more")) return@mapNotNull null
                parseListingCard(card)
            }
            .distinctBy { it.manga.detailPath + "|" + it.latestChapterPath.orEmpty() }
    }

    private fun parseListingCard(card: Element): ListingCard? {
        val titleLink = card.selectFirst(".thumb_attr.series-title a[href*='/manga/']") ?: return null
        val detailPath = normalizeStoredPath(titleLink.attr("href"))
        if (detailPath.isBlank()) return null
        val title = titleLink.text().cleanText().ifBlank {
            detailPath.trim('/').substringAfterLast('/').replace('-', ' ')
        }
        val chapterLink = card.selectFirst(".thumb_attr.chapter-title a[href*='/manga/']")
        val chapterPath = chapterLink?.attr("href").orEmpty().let(::normalizeStoredPath)
        val chapterLabel = chapterLink?.text()?.cleanText().orEmpty()
        val coverUrl = listOfNotNull(
            card.selectFirst(".content.img-in-ratio[data-bg]")?.let(::imageUrlFromElement),
            card.selectFirst(".content.img-in-ratio")?.let(::imageUrlFromElement),
            card.selectFirst("img")?.let(::imageUrlFromElement),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val manga = MangaSummary(
            providerId = id,
            title = title,
            detailPath = detailPath,
            coverUrl = coverUrl,
            latestPublication = chapterLabel,
        )
        return ListingCard(
            manga = manga,
            latestChapterPath = chapterPath.ifBlank { null },
            latestChapterLabel = chapterLabel,
        )
    }

    private fun ListingCard.toChapterSummary(): ChapterSummary? {
        val chapterPath = normalizeStoredPath(latestChapterPath.orEmpty())
        if (chapterPath.isBlank()) return null
        val chapterLabel = latestChapterLabel.ifBlank {
            chapterPath.trim('/').substringAfterLast('/').replace('-', ' ').cleanText()
        }
        val chapterId = chapterPath.trim('/').substringAfterLast('/').ifBlank { chapterLabel }
        return ChapterSummary(
            providerId = id,
            mangaTitle = manga.title,
            chapterLabel = chapterLabel,
            chapterNumberUrl = chapterLabel.ifBlank { chapterId },
            chapterId = chapterId,
            mangaPath = manga.detailPath,
            chapterPath = chapterPath,
            coverUrl = manga.coverUrl,
        )
    }

    private fun parseDetailChapters(document: Document): List<MangaChapter> {
        return document.select(
            "ul.list-chapters.at-series a[href*='/manga/'], " +
                ".list-chapters.at-series a[href*='/manga/'], " +
                "#chap_list a[href], .rd_sidebar #chap_list a[href], " +
                ".chapter-list a[href*='/manga/'], .listing-chapters_wrap a[href*='/manga/']",
        )
            .mapNotNull { link ->
                val chapterPath = normalizeStoredPath(link.attr("href"))
                if (chapterPath.isBlank()) return@mapNotNull null
                val chapterLabel = link.selectFirst(".chapter-name")?.text()?.cleanText()
                    .orEmpty()
                    .ifBlank {
                        link.attr("title").cleanText().ifBlank {
                            chapterPath.trim('/').substringAfterLast('/').replace('-', ' ')
                        }
                    }
                val registrationDate = link.selectFirst(".chapter-time")?.text()?.cleanText().orEmpty()
                val chapterId = chapterPath.trim('/').substringAfterLast('/')
                MangaChapter(
                    id = chapterId,
                    chapterLabel = chapterLabel,
                    chapterNumberUrl = chapterLabel.ifBlank { chapterId },
                    path = chapterPath,
                    pagesCount = 0,
                    registrationDate = registrationDate,
                )
            }
            .distinctBy { it.path }
            .sortedWith(
                compareByDescending<MangaChapter> { chapterValue(it) }
                    .thenByDescending { it.path },
            )
    }

    private fun homeSectionSpec(sectionId: String): SectionSpec? {
        return when (sectionId) {
            HOME_SECTION_POPULAR -> SectionSpec(sectionId, HomeSectionType.MANGAS, "/ranking")
            HOME_SECTION_LATEST_MAHNWA -> SectionSpec(sectionId, HomeSectionType.CHAPTERS, "/genre/manhwa?sort=update")
            HOME_SECTION_LATEST_RAW -> SectionSpec(sectionId, HomeSectionType.CHAPTERS, "/genre/raw?sort=update")
            HOME_SECTION_LATEST_ART -> SectionSpec(sectionId, HomeSectionType.CHAPTERS, "/genre/art?sort=update")
            HOME_SECTION_NEW -> SectionSpec(sectionId, HomeSectionType.CHAPTERS, "/manga-list?sort=new")
            else -> null
        }
    }

    private fun buildSearchPath(query: String): String {
        val encodedQuery = query.trim()
        return if (encodedQuery.isBlank()) {
            "/manga-list?sort=update"
        } else {
            "/tim-kiem?q=${URLEncoder.encode(encodedQuery, Charsets.UTF_8.name())}"
        }
    }

    private fun appendPage(path: String, page: Int): String {
        if (page <= 1) return path
        return if (path.contains('?')) {
            "$path&page=$page"
        } else {
            "$path?page=$page"
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        return document.selectFirst(
            ".pagination_wrap a.next, .pagination_wrap a.paging_prevnext.next, .pagination_wrap a.paging_item.next, " +
                ".wp-pagenavi a.nextpostslink, .wp-pagenavi a.next, a.nextpostslink, a.next",
        ) != null
    }

    private fun getDocument(path: String): Document {
        val request = Request.Builder()
            .url(path.toAbsoluteUrl())
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        client.newCall(request).execute().use { response ->
            return Jsoup.parse(response.body?.string().orEmpty(), baseUrl)
        }
    }

    private fun infoValue(document: Document, label: String): String {
        return document.select(".series-information .info-item")
            .firstOrNull { item ->
                item.selectFirst(".info-name")
                    ?.text()
                    ?.cleanText()
                    ?.trimEnd(':')
                    ?.equals(label, ignoreCase = true) == true
            }
            ?.selectFirst(".info-value")
            ?.text()
            ?.cleanText()
            .orEmpty()
    }

    private fun imageUrlFromElement(element: Element): String {
        val candidates = listOf(
            element.attr("data-bg"),
            element.attr("data-src"),
            element.attr("src"),
            element.attr("data-original"),
            extractUrlFromStyle(element.attr("style")),
        )
        return candidates.firstOrNull { it.isNotBlank() }.orEmpty().trim()
    }

    private fun extractUrlFromStyle(style: String): String {
        val match = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)
        return match?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun String.toAbsoluteUrl(): String {
        val trimmed = trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$baseUrl$trimmed"
            trimmed.isBlank() -> baseUrl
            else -> "$baseUrl/$trimmed"
        }
    }

    private fun String.cleanText(): String {
        return replace(Regex("\\s+"), " ").trim()
    }

    private fun detailPathFromChapterPath(chapterPath: String): String {
        val normalized = normalizeStoredPath(chapterPath).trim('/').split('/').filter { it.isNotBlank() }
        return if (normalized.size >= 2) {
            "/${normalized[0]}/${normalized[1]}"
        } else {
            chapterPath.substringBeforeLast('/').trimEnd('/')
        }
    }

    private data class ListingCard(
        val manga: MangaSummary,
        val latestChapterPath: String?,
        val latestChapterLabel: String,
    )

    private data class SectionSpec(
        val id: String,
        val type: HomeSectionType,
        val path: String,
    )
}
