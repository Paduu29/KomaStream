package com.paudinc.komastream.provider.providers

import android.content.Context
import com.paudinc.komastream.data.model.*
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.utils.AkaiComicWebViewResolver
import com.paudinc.komastream.utils.normalizeStoredPath
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

class AkaiComicProvider(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient(),
) : MangaProvider {
    override val id = "akaicomic-en"
    override val displayName = "Akai Comic"
    override val language = AppLanguage.EN
    override val websiteUrl = "https://akaicomic.org"
    override val logoUrl = "https://akaicomic.org/favicon.ico"

    private val webViewResolver by lazy { AkaiComicWebViewResolver(context.applicationContext, http) }

    override fun fetchHomeFeed(): HomeFeed {
        return try {
            val sections = webViewResolver.fetchHomeSections()
            val latestChapters = webViewResolver.fetchLatestChapters()
            
            val feedSections = mutableListOf<HomeFeedSection>()
            
            sections["featured"]?.takeIf { it.isNotEmpty() }?.let {
                feedSections.add(HomeFeedSection(id = "featured", title = "Featured", type = HomeSectionType.MANGAS, mangas = it))
            }
            sections["recent"]?.takeIf { it.isNotEmpty() }?.let {
                feedSections.add(HomeFeedSection(id = "recent", title = "Recent Updates", type = HomeSectionType.MANGAS, mangas = it))
            }
            latestChapters.takeIf { it.isNotEmpty() }?.let {
                feedSections.add(HomeFeedSection(id = "latest-chapters", title = "Latest Chapters", type = HomeSectionType.CHAPTERS, chapters = it))
            }
            sections["popular"]?.takeIf { it.isNotEmpty() }?.let {
                feedSections.add(HomeFeedSection(id = "popular", title = "Popular", type = HomeSectionType.MANGAS, mangas = it))
            }
            
            HomeFeed(
                latestUpdates = latestChapters,
                popularChapters = latestChapters,
                popularMangas = sections["popular"] ?: emptyList(),
                sections = feedSections
            )
        } catch (e: Exception) {
            emptyFeed()
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
        return try {
            val page = (skip / take) + 1
            val body = webViewResolver.searchManga(query, page, take)
            val results = webViewResolver.parseMangaListFromJson(body)
            val hasMore = results.size >= take
            CatalogSearchResult(results, hasMore)
        } catch (e: Exception) { CatalogSearchResult(emptyList(), false) }
    }

    override fun fetchMangaDetail(detailPath: String): MangaDetail {
        val normalizedPath = normalizeStoredPath(detailPath)
        val mid = normalizedPath.removePrefix("/manga/")
        return try {
            val detailJson = webViewResolver.fetchMangaDetail(mid)
            val detail = JSONObject(detailJson).optJSONObject("manga")
            if (detail == null) return MangaDetail(id, mid, "?", normalizedPath, "", "", "", "", "", "", emptyList())
            
            val seriesName = detail.optString("series_name", "")
            val altName = detail.optString("alternative_name", "")
            val title = seriesName.ifBlank { altName.split(",").firstOrNull()?.trim() ?: "Manga $mid" }
            val cover = detail.optString("cover_url", "")
            val banner = detail.optString("banner_url", cover)
            val author = detail.optString("author", "")
            val artist = detail.optString("artist", "")
            val desc = detail.optString("description", "")
            val genres = detail.optString("genres", "")
            val status = detail.optString("status", "")
            val mangaType = detail.optString("type", "")
            val year = detail.optString("release_year", "")
            
            val chaptersJson = webViewResolver.fetchChapters(mid)
            val chaptersJsonObj = JSONObject(chaptersJson)
            val chArr = chaptersJsonObj.optJSONArray("chapters") ?: JSONArray()
            val chapters = (0 until chArr.length()).mapNotNull { i ->
                val ch = chArr.optJSONObject(i) ?: return@mapNotNull null
                val locked = ch.optInt("locked_by_coins", 0)
                if (locked > 0) return@mapNotNull null
                val chNum = ch.optString("chapter_number") ?: return@mapNotNull null
                MangaChapter("$mid/$chNum", "Chapter $chNum", chNum, normalizeStoredPath("/manga/$mid/chapter/$chNum"), 0, ch.optString("created_at") ?: "")
            }.sortedByDescending { it.chapterNumberUrl.toFloatOrNull() }
            
            MangaDetail(id, title, title, normalizedPath, cover, banner, desc, "$genres\n$status - $mangaType ($year)", author, artist, chapters)
        } catch (e: Exception) { MangaDetail(id, mid, "?", normalizedPath, "", "", "", "", "", "", emptyList()) }
    }

    override fun fetchReaderData(chapterPath: String): ReaderData {
        val normalizedPath = normalizeStoredPath(chapterPath)
        val parts = normalizedPath.removePrefix("/manga/").split("/chapter/")
        if (parts.size != 2) return rd(chapterPath)
        val (mid, cn) = parts
        return try {
            val pages = webViewResolver.fetchPages(mid, cn)
            val detailPath = normalizeStoredPath("/manga/$mid")
            ReaderData(id, fetchReaderMangaTitle(mid), detailPath, "Chapter $cn", normalizedPath, null, null, pages)
        } catch (e: Exception) { rd(chapterPath) }
    }

    override fun downloadBytes(url: String, referer: String?) = webViewResolver.downloadBytes(url, referer)
    private fun emptyFeed() = HomeFeed(
        latestUpdates = emptyList(),
        popularChapters = emptyList(),
        popularMangas = emptyList(),
        sections = emptyList(),
    )

    private fun fetchReaderMangaTitle(mangaId: String): String {
        return runCatching {
            val detail = JSONObject(webViewResolver.fetchMangaDetail(mangaId)).optJSONObject("manga")
            val seriesName = detail?.optString("series_name", "").orEmpty()
            val altName = detail?.optString("alternative_name", "").orEmpty()
            seriesName.ifBlank { altName.split(",").firstOrNull()?.trim().orEmpty() }
        }.getOrDefault("")
            .ifBlank { mangaId.replace('-', ' ').replace('_', ' ') }
            .trim()
    }

    private fun rd(p: String) = ReaderData(id, "", "", "", p, null, null, emptyList())
}
