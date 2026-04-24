package com.paudinc.komastream.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.paudinc.komastream.data.model.ChapterSummary
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.ReaderPage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AkaiComicWebViewResolver(private val context: Context, private val client: OkHttpClient) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cookieManager = CookieManager.getInstance()
    private var cloudflareSolved = false
    private var webView: WebView? = null

    init { Log.d("AkaiWebView", "=== init resolver ===") }

    fun fetchLatestChapters(): List<ChapterSummary> {
        solveCloudflare()
        return try {
            val recentBody = httpGet("/api/manga/recent?limit=15&page=1")
            val recentJson = JSONObject(recentBody)
            val arr = recentJson.optJSONArray("manga") ?: JSONArray()
            
            val mangaMap = mutableMapOf<String, Pair<String, String>>() // id -> (name, cover)
            (0 until arr.length()).forEach { i ->
                val m = arr.optJSONObject(i) ?: return@forEach
                val id = m.optString("id") ?: return@forEach
                val name = m.optString("series_name", "").ifBlank { m.optString("alternative_name", "").substringBefore(",") }
                val cover = m.optString("cover_url", "")
                mangaMap[id] = Pair(name, cover)
            }
            
            if (mangaMap.isEmpty()) return emptyList()

            val ids = mangaMap.keys.toList()
            val postBody = "{\"ids\":${JSONArray(ids).toString()},\"limit\":3}"
            val chaptersBody = httpPost("/api/manga/chapters", postBody)
            val chaptersJson = JSONObject(chaptersBody)
            val results = chaptersJson.optJSONObject("results") ?: JSONObject()

            val chapters = mutableListOf<ChapterSummary>()
            ids.forEach { mid ->
                val (mangaName, mangaCover) = mangaMap[mid] ?: return@forEach
                val mangaData = results.optJSONObject(mid) ?: return@forEach
                val chArr = mangaData.optJSONArray("chapters") ?: return@forEach
                val ch = chArr.optJSONObject(0) ?: return@forEach
                val chNum = ch.optString("chapter_number", "").ifBlank { return@forEach }
                val chDate = ch.optString("created_at", "").replace("Z", "").replace("GMT", "").ifBlank { "?" }

                chapters.add(ChapterSummary(
                    providerId = "akaicomic-en",
                    mangaTitle = mangaName.ifBlank { "Manga" },
                    chapterLabel = "Chapter $chNum",
                    chapterNumberUrl = chNum,
                    chapterId = chNum,
                    mangaPath = "/manga/$mid",
                    chapterPath = "/manga/$mid/chapter/$chNum",
                    coverUrl = mangaCover,
                    registrationLabel = chDate.take(10),
                ))
            }
            chapters.sortedByDescending { it.registrationLabel }
        } catch (e: Exception) { Log.e("AkaiWebView", "fetchLatestChapters error: ${e.message}"); emptyList() }
    }

    fun fetchHomeSections(): Map<String, List<MangaSummary>> {
        solveCloudflare()
        val result = mutableMapOf<String, List<MangaSummary>>()
        try {
            result["featured"] = fetchFeatured()
            result["recent"] = fetchRecent()
            result["popular"] = fetchPopular()
        } catch (e: Exception) {
            Log.e("AkaiWebView", "fetchHomeSections error: ${e.message}")
        }
        return result
    }

    private fun fetchFeatured(): List<MangaSummary> {
        return try {
            val body = httpGet("/api/series/top?limit=6")
            val json = JSONObject(body)
            val arr = json.optJSONArray("series") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("id")
                val name = m.optString("series_name", "").ifBlank { m.optString("alternative_name", "").substringBefore(",") }
                val cover = m.optString("cover_url", "")
                val status = m.optString("status", "")
                val views = m.optString("views", "")
                Log.d("AkaiWebView", ">>> featured[$i]: name=$name cover=$cover")
                MangaSummary("akaicomic-en", name, "/manga/$id", cover, status, "", "", "", views)
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun fetchRecent(): List<MangaSummary> {
        return try {
            val body = httpGet("/api/manga/recent?limit=10&page=1")
            val json = JSONObject(body)
            val arr = json.optJSONArray("manga") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("id")
                val name = m.optString("series_name", "").ifBlank { m.optString("alternative_name", "").substringBefore(",") }
                val cover = m.optString("cover_url", "")
                val status = m.optString("status", "")
                val mangaType = m.optString("type", "")
                val year = m.optString("release_year", "")
                val updated = m.optString("updated_at", "").replace(" GMT", "").replace(", ", " ")
                MangaSummary("akaicomic-en", name, "/manga/$id", cover, status, mangaType, updated, "", "")
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun fetchPopular(): List<MangaSummary> {
        return try {
            val body = httpGet("/api/manga/list?limit=10&page=1")
            val json = JSONObject(body)
            val arr = json.optJSONArray("manga") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("id")
                val name = m.optString("series_name", "").ifBlank { m.optString("alternative_name", "").substringBefore(",") }
                val cover = m.optString("cover_url", "")
                val status = m.optString("status", "")
                val mangaType = m.optString("type", "")
                val year = m.optString("release_year", "")
                MangaSummary("akaicomic-en", name, "/manga/$id", cover, status, mangaType, "", "", "")
            }
        } catch (e: Exception) { emptyList() }
    }

    fun fetchMangaList(): List<MangaSummary> = fetchPopular()

    fun searchManga(query: String, page: Int, limit: Int = 20): String {
        solveCloudflare()
        return try {
            val body = httpGet("/api/manga/list?limit=$limit&page=$page" + if (query.isNotBlank()) "&search=$query" else "")
            Log.d("AkaiWebView", ">>> search response: $body")
            body
        } catch (e: Exception) { "{}" }
    }

    fun parseMangaListFromJson(body: String): List<MangaSummary> {
        return try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("manga") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("id")
                val name = m.optString("series_name", "").ifBlank { m.optString("alternative_name", "").substringBefore(",") }
                val cover = m.optString("cover_url", "")
                val status = m.optString("status", "")
                val mangaType = m.optString("type", "")
                val year = m.optString("release_year", "")
                MangaSummary("akaicomic-en", name, "/manga/$id", cover, status, mangaType, "", "", "")
            }
        } catch (e: Exception) { emptyList() }
    }

    fun fetchMangaDetail(mangaId: String): String {
        solveCloudflare()
        return try {
            val body = httpGet("/api/manga/$mangaId")
            Log.d("AkaiWebView", ">>> manga detail response: $body")
            body
        } catch (e: Exception) { "{}" }
    }

    fun fetchChapters(mangaId: String): String {
        solveCloudflare()
        return try {
            val body = httpGet("/api/manga/$mangaId/chapters")
            Log.d("AkaiWebView", ">>> chapters response: $body")
            body
        } catch (e: Exception) { "{}" }
    }

    fun fetchPages(mangaId: String, chapterNum: String): List<ReaderPage> {
        solveCloudflare()
        return try {
            val json = JSONObject(httpGet("/api/manga/$mangaId/chapter/$chapterNum/pages"))
            val arr = json.optJSONArray("pages") ?: JSONArray()
            Log.d("AkaiWebView", ">>> pages response: $arr")
            (0 until arr.length()).map { i ->
                val path = arr.optString(i) ?: return@map null
                val fullUrl = "https://akaicomic.org$path"
                val pageNum = path.substringAfter("page/").substringBefore("?").ifEmpty { (i + 1).toString() }
                ReaderPage(i.toString(), pageNum, fullUrl)
            }.filterNotNull()
        } catch (e: Exception) { emptyList() }
    }

    fun downloadBytes(url: String, ref: String?): ByteArray {
        if (url.startsWith("file://")) return ByteArray(0)
        val refUrl = ref ?: "https://akaicomic.org/"
        return try {
            client.newCall(Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", refUrl)
                .header("Origin", "https://akaicomic.org")
                .build()).execute().use { it.body?.bytes() ?: ByteArray(0) }
        } catch (e: Exception) { ByteArray(0) }
    }

    private fun solveCloudflare() {
        if (cloudflareSolved) return
        Log.d("AkaiWebView", ">>> solveCloudflare START")
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                webView = WebView(context)
                webView!!.settings.javaScriptEnabled = true
                webView!!.settings.domStorageEnabled = true
                webView!!.settings.userAgentString = USER_AGENT
                webView!!.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        mainHandler.postDelayed({ cloudflareSolved = true; latch.countDown() }, 8000)
                    }
                }
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView!!, true)
                webView!!.loadUrl("https://akaicomic.org")
            } catch (e: Exception) { latch.countDown() }
        }
        latch.await(50, TimeUnit.SECONDS)
        mainHandler.post { try { webView?.destroy() } catch (e: Exception) {}; webView = null }
    }

    private fun httpGet(path: String): String {
        val url = "https://akaicomic.org$path"
        Log.d("AkaiWebView", ">>> httpGet: $url")
        return try {
            client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "application/json").build()).execute().use {
                Log.d("AkaiWebView", ">>> ${it.code}")
                it.body?.string() ?: "{}"
            }
        } catch (e: Exception) { "{}" }
    }

    private fun httpPost(path: String, body: String): String {
        val url = "https://akaicomic.org$path"
        Log.d("AkaiWebView", ">>> httpPost: $url body=$body")
        return try {
            client.newCall(Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()).execute().use { it.body?.string() ?: "{}" }
        } catch (e: Exception) { "{}" }
    }

    companion object { private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36" }
}