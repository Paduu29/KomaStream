package com.paudinc.komastream.provider.providers

import android.net.Uri
import android.util.Log
import com.paudinc.komastream.data.model.*
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.utils.LibrarySettingsState
import com.paudinc.komastream.utils.normalizeStoredPath
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MkissaMangaProvider(
    private val http: OkHttpClient = OkHttpClient(),
    private val settingsState: LibrarySettingsState? = null,
) : MangaProvider {
    private val tag = "MkissaMangaProvider"
    override val id = "mkissa-en"
    override val displayName = "MKissa"
    override val language = AppLanguage.EN
    override val websiteUrl = "https://mkissa.to/manga"
    override val logoUrl = "https://mkissa.to/favicon-32x32.png"

    private val apiBase = "https://api.allanime.day/api"
    private val pageUrl = "https://mkissa.to/manga"

    private val hardcodedHashes = mapOf(
        "recent" to "c9d17db5fa0b87db21b90bc34db800dddf4e0be683a6cbd3b88d3f9d54968db9",
        "latest" to "84320a72d89b08a722035afb24019aa9e372c65cd361806e0104b92467795a2",
        "popular" to "ac2c75884a11fca5707ce4ad10f2e3e2aae31e42af5e4d9c511a4a5e708e4c6",
        "trending" to "a0aca6827cc9a3ad7bc711da4d200a04adea8f1a7545dc418d5e92e74c3aad15",
        "recommendations" to "fbd24de3aec73d35332185b621beec15396aaf8e8ae00183ddac6c19fbf8adcf",
        "random" to "6f0effa873cd88f71421fd2198d0a5bcd88f632f6b57d4c5b524def6eb908488",
        "tagSlug" to "ff61a63ff776f334f80c1e6ad1aa49ef71eab831e235e5d6ec679eae5b83450f",
        "mangaDetail" to "ebe753ec6e514d6368d48d7e36ab662d71e174221bfb27cc7b26c7407aefec69",
        "watchState" to "21c54fb01a8de305bbf70d48d879438f195e3e51da2e95a09ac57c77babb1030",
        "relatedPlaylists" to "479cc0238c38f5ff983e7d20d43c3fc9f034f387246bdad2f186d98321f9f85a",
        "search" to "a24c500a1b765c68ae1d8dd85174931f661c71369c89b92b88b75a725afc471c",
        "chapterSources" to "fe1f609dfea8a85618039516b01aa5c7979e9b13d5f3a2a7aaa31d09e5af0d51",
    )

    private val discoveredHashes = mutableListOf<String>()
    private val workingHashCache = ConcurrentHashMap<String, String>()
    private val mangaTitleCache = ConcurrentHashMap<String, String>()
    @Volatile private var hashDiscoveryAttempted = false

    companion object {
        private const val AES_PASSPHRASE = "Xot36i3lK3:v1"
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:152.0) Gecko/20100101 Firefox/152.0"
        private const val DEFAULT_IMAGE_HOST = "aln.youtube-anime.com"

        private val ALLOWED_IMAGE_HOSTS = setOf(
            "wp.youtube-anime.com",
            "mangayaro.to",
            "allanime.day",
        )

        val TAG_SECTIONS: List<Pair<String, String>> = listOf(
            "isekai" to "Isekai",
            "borderline_h" to "Borderline H",
            "boys_love" to "Boys' Love",
            "female_harem" to "Female Harem",
            "yuri" to "Yuri",
            "reincarnation" to "Reincarnation",
            "male_protagonist" to "Male Protagonist",
            "overpowered_protagonist" to "Overpowered Protagonist",
            "yandere" to "Yandere",
            "gyaru" to "Gyaru",
            "cultivation" to "Cultivation",
            "female_protagonist" to "Female Protagonist",
            "full_color" to "Full Color",
            "magic" to "Magic",
            "school" to "School",
            "anti_hero" to "Anti-Hero",
            "pov" to "POV",
            "succubus" to "Succubus",
            "post_apocalyptic" to "Post-Apocalyptic",
            "primarily_adult_cast" to "Primarily Adult Cast",
        )
    }

    private fun adultContentAllowed(): Boolean =
        settingsState?.current?.adultContentEnabled == true

    private fun mkissaStoredPath(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return ""
        val withoutFragment = trimmed.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
            .takeIf { withoutFragment.contains('?') }
            ?.let { "?$it" }
            .orEmpty()
        val normalizedPath = when {
            path.startsWith("/") -> path
            path.contains("://") -> Uri.parse(path).encodedPath?.let { if (it.startsWith("/")) it else "/$it" }.orEmpty()
            else -> "/$path"
        }
        return normalizedPath + query
    }

    private fun hashForKey(key: String): String =
        workingHashCache[key] ?: hardcodedHashes[key] ?: ""

    private fun triggerHashDiscovery() {
        if (hashDiscoveryAttempted) return
        synchronized(this) {
            if (hashDiscoveryAttempted) return
            hashDiscoveryAttempted = true
        }
        try {
            val html = fetchPage(pageUrl)
            val jsUrls = extractJsUrls(html)
            discoveredHashes.clear()
            discoveredHashes.addAll(collectHashesFromJs(jsUrls) - hardcodedHashes.values)
        } catch (_: Exception) { }
    }

    private fun fetchPage(url: String): String {
        Log.d(tag, "fetchPage url=$url")
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return http.newCall(req).execute().body?.string().orEmpty()
    }

    private fun extractJsUrls(html: String): List<String> {
        val urls = mutableListOf<String>()
        val modulePattern = Regex("""<link[^>]*rel=["']modulepreload["'][^>]*href=["']([^"']+)""", RegexOption.IGNORE_CASE)
        urls.addAll(modulePattern.findAll(html).map { it.groupValues[1] })
        val scriptPattern = Regex("""<script[^>]*src=["']([^"']+\.js[^"']*)""", RegexOption.IGNORE_CASE)
        urls.addAll(scriptPattern.findAll(html).map { it.groupValues[1] })
        return urls.map { resolveUrl(it) }.distinct()
    }

    private fun resolveUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return if (url.startsWith("/")) "https://mkissa.to$url" else "https://mkissa.to/${url.trimStart('/')}"
    }

    private fun collectHashesFromJs(jsUrls: List<String>): List<String> {
        val allHashes = mutableSetOf<String>()
        val hashPattern = Regex("""sha256Hash["':]\s*["']([a-f0-9]{64})["']""", RegexOption.IGNORE_CASE)
        for (url in jsUrls) {
            try {
                val js = fetchPage(url)
                allHashes.addAll(hashPattern.findAll(js).map { it.groupValues[1] })
            } catch (_: Exception) { }
        }
        return allHashes.toList()
    }

    override fun fetchHomeFeed(): HomeFeed {
        val sections = mutableListOf<HomeFeedSection>()
        val latestUpdates = fetchLatestUpdates()
        val popularMangas = fetchPopularMangas()
        val recommendedMangas = fetchRecommendedMangas()
        val randomMangas = fetchRandomMangas()

        val recentMangas = fetchRecentMangas()
        if (recentMangas.isNotEmpty()) {
            sections.add(HomeFeedSection("recent", "Latest", HomeSectionType.MANGAS, mangas = recentMangas))
        }

        val trending = fetchTrendingMangas()
        if (trending.isNotEmpty()) {
            sections.add(HomeFeedSection("trending", "Trending", HomeSectionType.MANGAS, mangas = trending))
        }

        val seasonal = fetchSeasonalMangas()
        if (seasonal.isNotEmpty()) {
            sections.add(HomeFeedSection("seasonal", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString(), HomeSectionType.MANGAS, mangas = seasonal))
        }

        val popularSection = popularMangas.take(12)
        if (popularSection.isNotEmpty()) {
            sections.add(HomeFeedSection("popular", "Popular", HomeSectionType.MANGAS, mangas = popularSection))
        }

        if (recommendedMangas.isNotEmpty()) {
            sections.add(HomeFeedSection("recommended", "Recommendations", HomeSectionType.MANGAS, mangas = recommendedMangas))
        }

        if (randomMangas.isNotEmpty()) {
            sections.add(HomeFeedSection("random", "Random", HomeSectionType.MANGAS, mangas = randomMangas))
        }

        for ((slug, name) in TAG_SECTIONS) {
            val mangas = fetchTagMangas(slug, name, limit = 10, page = 1)
            if (mangas.isNotEmpty()) {
                sections.add(HomeFeedSection(slug, name, HomeSectionType.MANGAS, mangas = mangas))
            }
        }

        if (latestUpdates.isNotEmpty()) {
            sections.add(HomeFeedSection("latest-updates", "Latest Updates", HomeSectionType.CHAPTERS, chapters = latestUpdates))
        }

        return HomeFeed(
            latestUpdates = latestUpdates,
            popularChapters = latestUpdates,
            popularMangas = popularMangas,
            sections = sections,
        )
    }

    override fun fetchHomeSectionPage(sectionId: String, page: Int): HomeSectionPageResult? {
        if (page < 1) return null
        return when {
            sectionId == "latest-updates" -> {
                val chapters = fetchLatestUpdatesPage(page)
                HomeSectionPageResult(
                    type = HomeSectionType.CHAPTERS,
                    chapters = chapters,
                    hasMore = chapters.size >= 20,
                )
            }
            sectionId == "recent" || sectionId == "trending" || sectionId == "seasonal" || sectionId == "popular" || sectionId == "recommended" || sectionId == "random" -> {
                val mangas = fetchSectionPage(sectionId, page)
                HomeSectionPageResult(
                    type = HomeSectionType.MANGAS,
                    mangas = mangas,
                    hasMore = mangas.size >= 20,
                )
            }
            TAG_SECTIONS.any { it.first == sectionId } -> {
                val (slug, name) = TAG_SECTIONS.first { it.first == sectionId }
                val mangas = fetchTagMangas(slug, name, limit = 20, page = page)
                HomeSectionPageResult(
                    type = HomeSectionType.MANGAS,
                    mangas = mangas,
                    hasMore = mangas.size >= 20,
                )
            }
            else -> null
        }
    }

    override fun fetchCatalogFilterOptions() = CatalogFilterOptions(emptyList(), emptyList(), emptyList())

    override fun searchCatalog(
        query: String,
        categoryIds: List<String>,
        sortBy: String,
        broadcastStatus: String,
        onlyFavorites: Boolean,
        skip: Int,
        take: Int,
    ): CatalogSearchResult {
        if (query.isBlank()) return CatalogSearchResult(emptyList(), false)
        val page = (skip / take) + 1
            val variables = JSONObject().apply {
                put("search", JSONObject().apply {
                    put("query", query)
                    put("isManga", true)
                    put("allowAdult", adultContentAllowed())
                    put("allowUnknown", false)
                })
                put("limit", take.coerceAtMost(26))
                put("page", page)
            put("translationType", "sub")
        }
        val resp = apiRequest(variables, "search")
        val shows = resp.optJSONObject("data")?.optJSONObject("shows")
        val edges = shows?.optJSONArray("edges") ?: JSONArray()
        val items = (0 until edges.length()).mapNotNull { i ->
            val edge = edges.optJSONObject(i) ?: return@mapNotNull null
            parseMangaSummary(edge)
        }
        val total = shows?.optJSONObject("pageInfo")?.optInt("total", 0) ?: 0
        return CatalogSearchResult(items, items.size + skip < total)
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = mkissaStoredPath(detailPath)
        val mangaId = normalizedPath.removePrefix("/manga/").substringBefore("/")
        Log.d(tag, "fetchMangaDetail detailPath=$detailPath normalizedPath=$normalizedPath mangaId=$mangaId")
        if (mangaId.isBlank()) return MangaDetail(id, "", "?", normalizedPath, "", "", "", "", "", "", chapters = emptyList())

        val variables = JSONObject().apply {
            put("_id", mangaId)
            put("search", JSONObject().apply {
                put("allowAdult", adultContentAllowed())
                put("allowUnknown", false)
            })
        }
        val resp = apiRequest(variables, "mangaDetail")
        val manga = resp.optJSONObject("data")?.optJSONObject("manga")
            ?: resp.optJSONObject("data")?.optJSONObject("show")
        Log.d(tag, "fetchMangaDetail responseDataKeys=${resp.optJSONObject("data")?.keys()?.asSequence()?.toList().orEmpty()} mangaFound=${manga != null}")
        if (manga == null) return MangaDetail(id, mangaId, "?", normalizedPath, "", "", "", "", "", "", chapters = emptyList())

        val title = extractTitle(manga).ifBlank { mangaId }
        val cover = jsonString(manga, "thumbnail")
            .ifBlank { jsonString(manga, "cover") }
            .ifBlank { manga.optJSONArray("thumbnails")?.let { arr -> jsonArrayString(arr, 0) }.orEmpty() }
        val banner = jsonString(manga, "banner").ifBlank { cover }
        val desc = jsonString(manga, "description").ifBlank { "No description" }
        val genres = manga.optJSONArray("genres")?.let { arr ->
            (0 until arr.length()).mapNotNull { jsonArrayString(arr, it).takeIf { value -> value.isNotBlank() } }.joinToString(", ")
        } ?: ""
        val status = jsonString(manga, "status").ifBlank { jsonString(manga, "airStatus") }
        val author = manga.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).mapNotNull { jsonArrayString(arr, it).takeIf { value -> value.isNotBlank() } }.joinToString(", ")
        } ?: jsonString(manga, "author")
        val artist = manga.optJSONArray("artists")?.let { arr ->
            (0 until arr.length()).mapNotNull { jsonArrayString(arr, it).takeIf { value -> value.isNotBlank() } }.joinToString(", ")
        } ?: author
        val year = manga.optJSONObject("airedStart")?.optInt("year", 0)?.takeIf { it > 0 }?.toString()
            ?: jsonString(manga, "releaseYear")
        val metadata = listOfNotNull(
            genres.takeIf { it.isNotBlank() },
            status.takeIf { it.isNotBlank() },
            year.takeIf { it.isNotBlank() }?.let { "($it)" },
        ).joinToString(" - ")

        val chapters = parseChaptersFromDetail(manga, normalizedPath)

        return MangaDetail(
            providerId = id,
            identification = mangaId,
            title = title,
            detailPath = normalizedPath,
            coverUrl = normalizeImageUrl(cover),
            bannerUrl = normalizeImageUrl(banner),
            description = desc,
            status = metadata,
            publicationDate = year,
            periodicity = "",
            chapters = chapters,
        )
    }

    private fun parseChaptersFromDetail(manga: JSONObject, normalizedPath: String): List<MangaChapter> {
        val chaptersDetail = manga.optJSONObject("availableChaptersDetail")
        if (chaptersDetail != null) {
            Log.d(tag, "parseChaptersFromDetail detailKeys=${chaptersDetail.keys().asSequence().toList()}")
            for (key in listOf("sub", "dub", "raw", "en")) {
                val arr = chaptersDetail.optJSONArray(key) ?: continue
                Log.d(tag, "parseChaptersFromDetail section=$key count=${arr.length()}")
                val result = (0 until arr.length()).mapNotNull { i ->
                    val chStr = jsonArrayString(arr, i)
                    if (chStr.isBlank()) return@mapNotNull null
                    val chPath = normalizedPath + "/chapter/$chStr"
                    MangaChapter(chStr, "Chapter $chStr", chStr, mkissaStoredPath(chPath), 0, "")
                }
                if (result.isNotEmpty()) {
                    return result.sortedByDescending { it.chapterNumberUrl.toDoubleOrNull() }
                }
            }
        }
        val chaptersArr = manga.optJSONArray("chapters")
        if (chaptersArr != null) {
            return (0 until chaptersArr.length()).mapNotNull { i ->
                val ch = chaptersArr.optJSONObject(i) ?: return@mapNotNull null
                val chStr = jsonString(ch, "chapterString").ifBlank { jsonString(ch, "name") }
                if (chStr.isBlank()) return@mapNotNull null
                val chPath = normalizedPath + "/chapter/$chStr"
                MangaChapter(chStr, "Chapter $chStr", chStr, mkissaStoredPath(chPath), 0, "")
            }.sortedByDescending { it.chapterNumberUrl.toDoubleOrNull() }
        }
        val availableChapters = manga.optJSONObject("availableChapters")
        if (availableChapters != null) {
            val count = listOf("sub", "en", "raw")
                .firstNotNullOfOrNull { key -> availableChapters.optInt(key, 0).takeIf { it > 0 } }
                ?: 0
            if (count > 0) {
                return (count downTo 1).map { chapter ->
                    val chStr = chapter.toString()
                    val chPath = normalizedPath + "/chapter/$chStr"
                    MangaChapter(chStr, "Chapter $chStr", chStr, mkissaStoredPath(chPath), 0, "")
                }
            }
        }
        return emptyList()
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        val normalizedPath = mkissaStoredPath(chapterPath)
        val parts = normalizedPath.removePrefix("/manga/").split("/chapter/")
        if (parts.size != 2) return emptyReaderData(chapterPath)
        val (mangaId, cn) = parts

        val mangaDetail = fetchMangaDetail("/manga/$mangaId")
        val currentIndex = mangaDetail.chapters.indexOfFirst { it.chapterNumberUrl == cn || it.id == cn }
        val currentChapter = mangaDetail.chapters.getOrNull(currentIndex)
        val chapterLabel = currentChapter?.chapterLabel ?: "Chapter $cn"
        val previousChapterPath = if (currentIndex >= 0) mangaDetail.chapters.getOrNull(currentIndex + 1)?.path else null
        val nextChapterPath = if (currentIndex >= 0) mangaDetail.chapters.getOrNull(currentIndex - 1)?.path else null

        try {
            val variables = JSONObject().apply {
                put("mangaId", mangaId)
                put("translationType", "sub")
                put("chapterString", cn)
                put("limit", 100)
                put("offset", 0)
            }
            val resp = apiRequest(variables, "chapterSources")
            val data = resp.optJSONObject("data") ?: resp
            val tobeparsed = data.optString("tobeparsed", "")
            val m = data.optString("_m", "")
            Log.d(tag, "fetchReaderData chapterString=$cn dataKeys=${data.keys().asSequence().toList()} hasBlob=${tobeparsed.isNotBlank()} mode=$m")

            if (tobeparsed.isNotBlank() && m == "b7") {
                val decrypted = decryptAllAnime(tobeparsed)
                Log.d(tag, "fetchReaderData decryptedPrefix=${decrypted.take(120)}")
                val pages = parsePagesFromDecrypted(decrypted)
                Log.d(tag, "fetchReaderData pages=${pages.size}")
                if (pages.isNotEmpty()) {
                    return ReaderData(
                        providerId = id,
                        mangaTitle = mangaDetail.title,
                        mangaDetailPath = mangaDetail.detailPath,
                        chapterTitle = chapterLabel,
                        chapterPath = normalizedPath,
                        previousChapterPath = previousChapterPath,
                        nextChapterPath = nextChapterPath,
                        pages = pages,
                    )
                }
            }
        } catch (_: Exception) { }

        return emptyReaderData(chapterPath)
    }

    override fun downloadBytes(url: String, referer: String?): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer ?: "https://mkissa.to/manga")
            .header("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .build()
        http.newCall(request).execute().use { response ->
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun fetchRecentMangas(): List<MangaSummary> {
        return try {
            val variables = JSONObject().apply {
                put("search", JSONObject().apply {
                    put("sortBy", "Recent")
                    put("isManga", true)
                    put("allowAdult", adultContentAllowed())
                    put("allowUnknown", false)
                })
                put("limit", 12)
                put("page", 1)
                put("translationType", "sub")
            }
            val resp = apiRequest(variables, "recent")
            val edges = resp.optJSONObject("data")?.optJSONObject("mangas")?.optJSONArray("edges") ?: JSONArray()
            (0 until edges.length()).mapNotNull { i ->
                parseMangaSummary(edges.optJSONObject(i))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchLatestUpdates(): List<ChapterSummary> {
        return try {
            val today = java.util.Calendar.getInstance()
            today.add(java.util.Calendar.DAY_OF_MONTH, -1)
            val dateAgo = today.get(java.util.Calendar.YEAR) * 10000 +
                (today.get(java.util.Calendar.MONTH) + 1) * 100 +
                today.get(java.util.Calendar.DAY_OF_MONTH)
            val variables = JSONObject().apply {
                put("pageSearch", JSONObject().apply {
                    put("type", "manga")
                    put("allowSameShow", false)
                    put("page", 1)
                    put("allowAdult", adultContentAllowed())
                    put("allowUnknown", false)
                    put("dateAgo", dateAgo)
                })
            }
            val resp = apiRequest(variables, "latest")
            val recommendations = resp.optJSONObject("data")?.optJSONObject("queryLatestPageStatus")?.optJSONArray("recommendations") ?: JSONArray()
            (0 until recommendations.length()).mapNotNull { i ->
                val rec = recommendations.optJSONObject(i) ?: return@mapNotNull null
                val anyCard = rec.optJSONObject("anyCard") ?: return@mapNotNull null
                val pageStatus = rec.optJSONObject("pageStatus") ?: return@mapNotNull null
                val showId = anyCard.optString("_id", "")
                val name = extractTitle(anyCard)
                val cover = jsonString(anyCard, "thumbnail")
                val views = jsonString(pageStatus, "views")
                if (showId.isBlank() || name.isBlank()) return@mapNotNull null
                ChapterSummary(
                    providerId = id,
                    mangaTitle = name,
                    chapterLabel = "Updated",
                    chapterNumberUrl = "",
                    chapterId = "",
                    mangaPath = mkissaStoredPath("/manga/$showId"),
                    chapterPath = "",
                    coverUrl = normalizeImageUrl(cover),
                    registrationLabel = views,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchPopularMangas(page: Int = 1, size: Int = 12): List<MangaSummary> {
        return try {
            val variables = JSONObject().apply {
                put("search", JSONObject().apply {
                    put("page", page)
                    put("size", size)
                    put("sortBy", "Popular")
                    put("formats", JSONArray().put("manga"))
                    put("allowAdult", adultContentAllowed())
                    put("allowUnknown", false)
                })
            }
            val resp = apiRequest(variables, "popular")
            val edges = resp.optJSONObject("data")?.optJSONObject("tierLists")?.optJSONArray("edges") ?: JSONArray()
            val seen = mutableSetOf<String>()
            (0 until edges.length()).flatMap { i ->
                val edge = edges.optJSONObject(i) ?: return@flatMap emptyList()
                val samples = edge.optJSONArray("sampleItems") ?: JSONArray()
                (0 until samples.length()).mapNotNull { j ->
                    val sample = samples.optJSONObject(j) ?: return@mapNotNull null
                    val itemId = jsonString(sample, "_id")
                    if (itemId.isBlank() || itemId in seen) return@mapNotNull null
                    seen.add(itemId)
                    val title = extractTitle(sample)
                    val cover = jsonString(sample, "cover").ifBlank { jsonString(sample, "thumbnail") }
                    MangaSummary(
                        providerId = id,
                        title = title.ifBlank { itemId },
            detailPath = mkissaStoredPath("/manga/$itemId"),
                        coverUrl = normalizeImageUrl(cover),
                    )
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchTrendingMangas(): List<MangaSummary> {
        return try {
            val variables = JSONObject().apply {
                put("type", "manga")
                put("size", 20)
                put("dateRange", 1)
                put("page", 1)
                put("allowAdult", adultContentAllowed())
                put("allowUnknown", false)
            }
            val resp = apiRequest(variables, "trending")
            val recommendations = resp.optJSONObject("data")?.optJSONObject("queryPopular")?.optJSONArray("recommendations") ?: JSONArray()
            (0 until recommendations.length()).mapNotNull { i ->
                recommendations.optJSONObject(i)?.optJSONObject("anyCard")?.let { parseMangaSummary(it) }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchSeasonalMangas(): List<MangaSummary> {
        return try {
            val variables = JSONObject().apply {
                put("search", JSONObject().apply {
                    put("year", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
                    put("isManga", true)
                    put("allowAdult", adultContentAllowed())
                    put("allowUnknown", false)
                })
                put("limit", 8)
                put("page", 1)
                put("translationType", "sub")
            }
            val resp = apiRequest(variables, "recent")
            val edges = resp.optJSONObject("data")?.optJSONObject("mangas")?.optJSONArray("edges") ?: JSONArray()
            (0 until edges.length()).mapNotNull { i ->
                parseMangaSummary(edges.optJSONObject(i))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchRecommendedMangas(page: Int = 1): List<MangaSummary> {
        return try {
            val variables = JSONObject().apply {
                put("search", JSONObject().apply {
                    put("sortBy", "Recommendation")
                    put("format", "manga")
                })
                put("limit", 20)
                put("page", page)
            }
            val resp = apiRequest(variables, "recommendations")
            val edges = resp.optJSONObject("data")?.optJSONObject("queryTags")?.optJSONArray("edges") ?: JSONArray()
            (0 until edges.length()).mapNotNull { i ->
                val tag = edges.optJSONObject(i) ?: return@mapNotNull null
                val sample = tag.optJSONObject("sampleManga") ?: return@mapNotNull null
                val id_ = jsonString(sample, "_id")
                val title = extractTitle(sample).ifBlank { fetchMangaTitle(id_) }
                if (id_.isBlank() || title.isBlank()) return@mapNotNull null
                MangaSummary(
                    providerId = id,
                    title = title,
                    detailPath = mkissaStoredPath("/manga/$id_"),
                    coverUrl = normalizeImageUrl(jsonString(sample, "thumbnail").ifBlank { jsonString(sample, "cover") }),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchMangaTitle(mangaId: String): String {
        if (mangaId.isBlank()) return ""
        mangaTitleCache[mangaId]?.let { return it }
        return runCatching {
            val variables = JSONObject().apply {
                put("_id", mangaId)
                put("search", JSONObject().apply {
                    put("allowAdult", adultContentAllowed())
                    put("allowUnknown", false)
                })
            }
            val resp = apiRequest(variables, "mangaDetail")
            val manga = resp.optJSONObject("data")?.optJSONObject("manga")
                ?: resp.optJSONObject("data")?.optJSONObject("show")
            extractTitle(manga ?: JSONObject())
        }.getOrDefault("")
            .also { title ->
                if (title.isNotBlank()) {
                    mangaTitleCache[mangaId] = title
                }
            }
    }

    private fun fetchRandomMangas(page: Int = 1): List<MangaSummary> {
        return try {
            val variables = JSONObject().apply {
                put("search", JSONObject().apply {
                    put("sortBy", "Random")
                })
                put("limit", 26)
                put("page", page)
            }
            val resp = apiRequest(variables, "random")
            val items = resp.optJSONObject("data")?.optJSONArray("queryRandomRecommendation") ?: JSONArray()
            (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                val id_ = jsonString(item, "_id")
                val title = extractTitle(item)
                if (id_.isBlank() || title.isBlank()) return@mapNotNull null
                MangaSummary(
                    providerId = id,
                    title = title,
                    detailPath = mkissaStoredPath("/manga/$id_"),
                    coverUrl = normalizeImageUrl(jsonString(item, "thumbnail").ifBlank { jsonString(item, "cover") }),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchTagMangas(slug: String, name: String, limit: Int, page: Int): List<MangaSummary> {
        return try {
            val variables = JSONObject().apply {
                put("search", JSONObject().apply {
                    put("slug", slug)
                    put("format", "manga")
                    put("page", page)
                    put("limit", limit)
                    put("name", name)
                })
            }
            val resp = apiRequest(variables, "tagSlug")
            val edges = resp.optJSONObject("data")?.optJSONObject("queryListForTag")?.optJSONArray("edges")
                ?: resp.optJSONObject("data")?.optJSONObject("shows")?.optJSONArray("edges")
                ?: resp.optJSONObject("data")?.optJSONObject("mangas")?.optJSONArray("edges")
                ?: JSONArray()
            (0 until edges.length()).mapNotNull { i ->
                parseMangaSummary(edges.optJSONObject(i))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchSectionPage(sectionId: String, page: Int): List<MangaSummary> {
        return try {
            if (sectionId == "trending") {
                val variables = JSONObject().apply {
                    put("type", "manga")
                    put("size", 20)
                    put("dateRange", 1)
                    put("page", page)
                    put("allowAdult", adultContentAllowed())
                    put("allowUnknown", false)
                }
                val resp = apiRequest(variables, "trending")
                val recommendations = resp.optJSONObject("data")?.optJSONObject("queryPopular")?.optJSONArray("recommendations") ?: JSONArray()
                return (0 until recommendations.length()).mapNotNull { i ->
                    recommendations.optJSONObject(i)?.optJSONObject("anyCard")?.let { parseMangaSummary(it) }
                }
            }
            if (sectionId == "popular") {
                return fetchPopularMangas(page = page, size = 20)
            }
            if (sectionId == "recommended") {
                return fetchRecommendedMangas(page = page)
            }
            if (sectionId == "random") {
                return fetchRandomMangas(page = page)
            }
            val variables = JSONObject().apply {
                put("search", JSONObject().apply {
                    if (sectionId == "seasonal") {
                        put("year", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
                    } else {
                        put("sortBy", "Recent")
                    }
                    put("isManga", true)
                    put("allowAdult", adultContentAllowed())
                    put("allowUnknown", false)
                })
                put("limit", 20)
                put("page", page)
                put("translationType", "sub")
            }
            val resp = apiRequest(variables, "recent")
            val edges = resp.optJSONObject("data")?.optJSONObject("mangas")?.optJSONArray("edges") ?: JSONArray()
            (0 until edges.length()).mapNotNull { i ->
                parseMangaSummary(edges.optJSONObject(i))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchLatestUpdatesPage(page: Int): List<ChapterSummary> {
        return try {
            val today = java.util.Calendar.getInstance()
            today.add(java.util.Calendar.DAY_OF_MONTH, -1)
            val dateAgo = today.get(java.util.Calendar.YEAR) * 10000 +
                (today.get(java.util.Calendar.MONTH) + 1) * 100 +
                today.get(java.util.Calendar.DAY_OF_MONTH)
            val variables = JSONObject().apply {
                put("pageSearch", JSONObject().apply {
                    put("type", "manga")
                    put("allowSameShow", false)
                    put("page", page)
                    put("allowAdult", adultContentAllowed())
                    put("allowUnknown", false)
                    put("dateAgo", dateAgo)
                })
            }
            val resp = apiRequest(variables, "latest")
            val recommendations = resp.optJSONObject("data")?.optJSONObject("queryLatestPageStatus")?.optJSONArray("recommendations") ?: JSONArray()
            (0 until recommendations.length()).mapNotNull { i ->
                val rec = recommendations.optJSONObject(i) ?: return@mapNotNull null
                val anyCard = rec.optJSONObject("anyCard") ?: return@mapNotNull null
                val pageStatus = rec.optJSONObject("pageStatus") ?: return@mapNotNull null
                val showId = anyCard.optString("_id", "")
                val name = extractTitle(anyCard)
                val cover = jsonString(anyCard, "thumbnail")
                val views = jsonString(pageStatus, "views")
                if (showId.isBlank() || name.isBlank()) return@mapNotNull null
                ChapterSummary(
                    providerId = id,
                    mangaTitle = name,
                    chapterLabel = "Updated",
                    chapterNumberUrl = "",
                    chapterId = "",
                    mangaPath = mkissaStoredPath("/manga/$showId"),
                    chapterPath = "",
                    coverUrl = normalizeImageUrl(cover),
                    registrationLabel = views,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseMangaSummary(edge: JSONObject): MangaSummary? {
        val id_ = jsonString(edge, "_id")
        val name = extractTitle(edge)
        val cover = jsonString(edge, "thumbnail")
            .ifBlank { jsonString(edge, "cover") }
            .ifBlank { jsonString(edge, "banner") }
        if (id_.isBlank() || name.isBlank()) return null

        val lastChapter = edge.optJSONObject("lastChapterInfo")?.optJSONObject("sub")
            ?: edge.optJSONObject("lastEpisodeInfo")?.optJSONObject("sub")
        val chapterStr = lastChapter?.let {
            jsonString(it, "chapterString").ifBlank { jsonString(it, "episodeString") }
        }.orEmpty()
        val chapterLabel = if (chapterStr.isNullOrBlank()) "" else "Ch. $chapterStr"

        return MangaSummary(
            providerId = id,
            title = name,
            detailPath = mkissaStoredPath("/manga/$id_"),
            coverUrl = normalizeImageUrl(cover),
            latestPublication = chapterLabel,
        )
    }

    private fun extractTitle(obj: JSONObject): String {
        val candidates = listOf(
            jsonString(obj, "englishName"),
            jsonString(obj, "prettyName"),
            jsonString(obj, "alternativeName"),
            jsonString(obj, "name"),
            jsonString(obj, "title"),
            jsonString(obj, "romajiName"),
            jsonString(obj, "nativeName"),
        )
        return candidates.firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun jsonString(obj: JSONObject, key: String): String =
        cleanString(obj.opt(key)?.toString().orEmpty())

    private fun jsonArrayString(arr: JSONArray, index: Int): String =
        cleanString(arr.opt(index)?.toString().orEmpty())

    private fun cleanString(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.equals("null", ignoreCase = true) || trimmed.equals("undefined", ignoreCase = true)) "" else trimmed
    }

    private fun parsePagesFromDecrypted(decrypted: String): List<ReaderPage> {
        try {
            val obj = JSONObject(decrypted)
            val chapterPages = obj.optJSONObject("chapterPages")
            if (chapterPages != null) {
                val edges = chapterPages.optJSONArray("edges")
                if (edges != null) {
                    val pages = mutableListOf<ReaderPage>()
                    for (edgeIndex in 0 until edges.length()) {
                        val edge = edges.optJSONObject(edgeIndex) ?: continue
                        val pictureUrls = edge.optJSONArray("pictureUrls") ?: continue
                        for (i in 0 until pictureUrls.length()) {
                            val pic = pictureUrls.optJSONObject(i) ?: continue
                            val url = jsonString(pic, "url")
                            if (url.isBlank()) continue
                            val pageNumber = jsonString(pic, "num").ifBlank { (pages.size + 1).toString() }
                            pages.add(ReaderPage(pages.size.toString(), pageNumber, normalizeImageUrl(url)))
                        }
                    }
                    if (pages.isNotEmpty()) return pages
                }
            }
            val chapter = obj.optJSONObject("chapter")
            if (chapter != null) {
                val imgList = chapter.optJSONArray("imgList")
                if (imgList != null) {
                    return (0 until imgList.length()).mapNotNull { i ->
                        val img = imgList.optJSONObject(i)
                        val url = img?.let { jsonString(it, "url").ifBlank { jsonString(it, "img") }.ifBlank { jsonString(it, "src") } }.orEmpty()
                        if (url.isBlank()) null else ReaderPage(i.toString(), (i + 1).toString(), normalizeImageUrl(url))
                    }
                }
                val pages = chapter.optJSONArray("pages")
                if (pages != null) {
                    return (0 until pages.length()).mapNotNull { i ->
                        val page = pages.optJSONObject(i)
                        val url = page?.let { jsonString(it, "url").ifBlank { jsonString(it, "img") }.ifBlank { jsonString(it, "src") } }.orEmpty()
                        if (url.isBlank()) null else ReaderPage(i.toString(), (i + 1).toString(), normalizeImageUrl(url))
                    }
                }
            }
            val arr = JSONArray(decrypted)
            return (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i)
                val url = item?.let { jsonString(it, "url").ifBlank { jsonString(it, "sourceUrl") } }.orEmpty()
                if (url.isBlank()) null else ReaderPage(i.toString(), (i + 1).toString(), normalizeImageUrl(url))
            }
        } catch (_: Exception) { }

        val lines = decrypted.lines().filter { it.isNotBlank() }
        if (lines.isNotEmpty() && lines.all { it.startsWith("http") }) {
            return lines.mapIndexed { i, url -> ReaderPage(i.toString(), (i + 1).toString(), normalizeImageUrl(url)) }
        }

        return emptyList()
    }

    private fun apiRequest(variables: JSONObject, hashKey: String): JSONObject {
        val hash = hashForKey(hashKey)
        if (hash.isBlank()) return JSONObject()

        var result = executeApiRequest(variables, hash)
        if (hasValidData(result)) return result

        if (!hashDiscoveryAttempted) {
            triggerHashDiscovery()
            if (discoveredHashes.isNotEmpty()) {
                for (discovered in discoveredHashes) {
                    if (discovered == hash || discovered in workingHashCache.values) continue
                    result = executeApiRequest(variables, discovered)
                    if (hasValidData(result)) {
                        workingHashCache[hashKey] = discovered
                        return result
                    }
                }
            }
        }

        return result
    }

    private fun hasValidData(json: JSONObject): Boolean {
        val data = json.optJSONObject("data")
        return data != null && data.length() > 0
    }

    private fun executeApiRequest(variables: JSONObject, sha256Hash: String): JSONObject {
        val varsEncoded = URLEncoder.encode(variables.toString(), "UTF-8")
        val extensions = JSONObject().apply {
            put("persistedQuery", JSONObject().apply {
                put("version", 1)
                put("sha256Hash", sha256Hash)
            })
        }
        val extEncoded = URLEncoder.encode(extensions.toString(), "UTF-8")
        val url = "$apiBase?variables=$varsEncoded&extensions=$extEncoded"
        Log.d(tag, "apiRequest hashKeyUrl=$url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://mkissa.to/manga")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) JSONObject()
                else JSONObject(body)
            }
        } catch (_: Exception) { JSONObject() }
    }

    private fun decryptAllAnime(blobBase64: String): String {
        val bytes = android.util.Base64.decode(blobBase64, android.util.Base64.DEFAULT)
        if (bytes.isEmpty()) return ""

        val version = bytes[0].toInt()
        if (version != 1) return ""

        val iv = bytes.copyOfRange(1, 13)
        val cipherText = bytes.copyOfRange(13, bytes.size)
        val key = MessageDigest.getInstance("SHA-256")
            .digest("Xot36i3lK3:v$version".toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun normalizeImageUrl(url: String): String {
        val cleanUrl = cleanString(url)
        if (cleanUrl.isBlank()) return ""
        if (cleanUrl.startsWith("https://wp.youtube-anime.com/")) return cleanUrl
        if (cleanUrl.startsWith("//")) return wrapMkissaImagePath(cleanUrl.removePrefix("//"))
        if (cleanUrl.startsWith("http")) {
            val uri = Uri.parse(cleanUrl)
            val host = uri.host.orEmpty().trim()
            val path = uri.encodedPath.orEmpty().trimStart('/')
            val query = uri.encodedQuery?.let { "?$it" }.orEmpty()
            if (host.isBlank()) return cleanUrl
            return if (host.contains("youtube-anime.com")) {
                wrapMkissaImagePath(path + query)
            } else {
                cleanUrl
            }
        }
        return wrapMkissaImagePath(cleanUrl.trimStart('/'))
    }

    private fun wrapMkissaImagePath(path: String): String {
        val cleanPath = path.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wp.youtube-anime.com/")
            .removePrefix("aln.youtube-anime.com/")
            .trimStart('/')
        return "https://wp.youtube-anime.com/aln.youtube-anime.com/${cleanPath}"
    }

    fun imageRequestHeaders(): okhttp3.Headers {
        return okhttp3.Headers.Builder()
            .add("User-Agent", USER_AGENT)
            .add("Referer", "https://mkissa.to/manga")
            .add("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .build()
    }

    private fun emptyReaderData(path: String) = ReaderData(id, "", "", "", normalizeStoredPath(path), null, null, emptyList())
}
