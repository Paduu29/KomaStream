package com.paudinc.komastream.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.ReaderPage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.min

class MangaFireWebViewResolver(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cookieManager = CookieManager.getInstance()

    fun fetchReaderData(providerId: String, chapterPath: String): ReaderData {
        val normalizedChapterPath = normalizePath(chapterPath)
        val chapterDocument = getDocument(normalizedChapterPath)
        val detailPath = chapterDocument.selectFirst("#ctrl-menu .head a[href]")
            ?.attr("href")
            ?.let(::normalizePath)
            .orEmpty()
        val mangaTitle = chapterDocument.selectFirst("#ctrl-menu .head a[href]")
            ?.text()
            ?.trim()
            .orEmpty()
        val languageCode = chapterDocument.selectFirst("body")?.attr("data-lang").orEmpty().ifBlank { "en" }
        val mangaId = detailPath.substringAfterLast('.', "")
        val chaptersHtml = if (mangaId.isNotBlank()) {
            getJson("/ajax/manga/$mangaId/chapter/$languageCode").optString("result")
        } else {
            ""
        }
        val chapterContext = parseChapterContext(chaptersHtml, normalizedChapterPath)
        val pagePayload = resolvePagePayload(normalizedChapterPath)
        val pages = pagePayload.optJSONObject("result")
            ?.optJSONArray("images")
            ?.toReaderPages(normalizedChapterPath)
            .orEmpty()

        return ReaderData(
            providerId = providerId,
            mangaTitle = mangaTitle.ifBlank {
                detailPath.substringAfterLast('/').substringBeforeLast('.').replace('-', ' ')
            },
            mangaDetailPath = detailPath,
            chapterTitle = chapterContext?.label ?: normalizedChapterPath.substringAfterLast('/'),
            chapterPath = normalizedChapterPath,
            previousChapterPath = chapterContext?.previousPath,
            nextChapterPath = chapterContext?.nextPath,
            pages = pages,
        )
    }

    fun searchCatalog(providerId: String, query: String, skip: Int, take: Int): List<MangaSummary> {
        val resolvedUrl = resolveSearchUrl(query)
        Log.d("MangaFireWebView", "searchCatalog: query='$query' resolvedUrl='$resolvedUrl'")
        val document = getDocumentAbsolute(resolvedUrl, referer = BASE_URL)
        val items = parseCatalogCards(document, providerId)
        Log.d(
            "MangaFireWebView",
            "searchCatalog: query='$query' parsed=${items.size} titles=${items.take(5).joinToString { it.title }}"
        )
        return items.drop(skip).take(take)
    }

    fun downloadBytes(url: String, referer: String?): ByteArray {
        if (url.startsWith(FILE_SCHEME_PREFIX)) {
            return Uri.parse(url).path?.let(::File)?.takeIf(File::exists)?.readBytes() ?: ByteArray(0)
        }
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

    private fun resolvePagePayload(chapterPath: String): JSONObject {
        val fullUrl = toAbsoluteUrl(chapterPath)
        val firstRequestUrl = captureAjaxRequest(
            fullUrl = fullUrl,
            responseResolver = { request, _ ->
                val host = request.url.host.orEmpty()
                val path = request.url.encodedPath.orEmpty()
                if (host == HOST && path.contains("/ajax/read")) {
                    emptyJsonResponse()
                } else {
                    null
                }
            },
            isTargetRequest = { request ->
                val host = request.url.host.orEmpty()
                val path = request.url.encodedPath.orEmpty()
                host == HOST && path.contains("/ajax/read")
            },
        )
        val firstBody = getText(
            absoluteUrl = firstRequestUrl,
            referer = fullUrl,
            ajax = true,
        )
        val secondRequestUrl = captureAjaxRequest(
            fullUrl = fullUrl,
            responseResolver = { request, _ ->
                val host = request.url.host.orEmpty()
                val path = request.url.encodedPath.orEmpty()
                if (host == HOST && path.contains("/ajax/read")) {
                    if (path.contains("/ajax/read/chapter/") || path.contains("/ajax/read/volume/")) {
                        emptyJsonResponse()
                    } else {
                        jsonResponse(firstBody)
                    }
                } else {
                    null
                }
            },
            isTargetRequest = { request ->
                val host = request.url.host.orEmpty()
                val path = request.url.encodedPath.orEmpty()
                host == HOST &&
                    (path.contains("/ajax/read/chapter/") || path.contains("/ajax/read/volume/"))
            },
        )
        return JSONObject(
            getText(
                absoluteUrl = secondRequestUrl,
                referer = fullUrl,
                ajax = true,
            )
        )
    }

    private fun captureAjaxRequest(
        fullUrl: String,
        responseResolver: (WebResourceRequest, ByteArray?) -> WebResourceResponse?,
        isTargetRequest: (WebResourceRequest) -> Boolean,
    ): String {
        val latch = CountDownLatch(1)
        val result = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        var webView: WebView? = null

        mainHandler.post {
            webView = WebView(context)
            val activeWebView = checkNotNull(webView)
            activeWebView.settings.javaScriptEnabled = true
            activeWebView.settings.domStorageEnabled = true
            activeWebView.settings.loadsImagesAutomatically = false
            activeWebView.settings.blockNetworkImage = true
            activeWebView.settings.userAgentString = USER_AGENT
            activeWebView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    try {
                        if (isTargetRequest(request)) {
                            result += request.url.toString()
                            latch.countDown()
                        }
                        responseResolver(request, null)?.let { return it }
                    } catch (throwable: Throwable) {
                        errors += throwable
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(activeWebView, true)
            activeWebView.loadUrl(fullUrl)
        }

        if (!latch.await(20, TimeUnit.SECONDS)) {
            disposeWebView(webView)
            throw IOException("Timed out while resolving MangaFire reader flow")
        }
        disposeWebView(webView)
        errors.firstOrNull()?.let { throw IOException(it.message ?: "Could not resolve MangaFire reader flow", it) }
        return result.firstOrNull() ?: throw IOException("Could not capture MangaFire reader request")
    }

    private fun resolveSearchUrl(query: String): String {
        val latch = CountDownLatch(1)
        val result = mutableListOf<String>()
        val errors = mutableListOf<Throwable>()
        var webView: WebView? = null
        val escapedQuery = JSONObject.quote(query)

        mainHandler.post {
            webView = WebView(context)
            val activeWebView = checkNotNull(webView)
            activeWebView.settings.javaScriptEnabled = true
            activeWebView.settings.domStorageEnabled = true
            activeWebView.settings.loadsImagesAutomatically = false
            activeWebView.settings.blockNetworkImage = true
            activeWebView.settings.userAgentString = USER_AGENT
            activeWebView.webViewClient = object : WebViewClient() {
                private var submitted = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (submitted) return
                    if (url.isNullOrBlank()) return
                    if (!url.startsWith(BASE_URL)) return
                    submitted = true
                    view?.evaluateJavascript(
                        """
                        (function() {
                          const form = document.querySelector('#nav-search form[action="filter"]');
                          const input = form ? form.querySelector('input[name="keyword"]') : null;
                          if (!form || !input) return "missing-form";
                          input.value = $escapedQuery;
                          input.dispatchEvent(new Event('input', { bubbles: true }));
                          input.dispatchEvent(new Event('change', { bubbles: true }));
                          if (typeof form.requestSubmit === 'function') {
                            form.requestSubmit();
                            return "requestSubmit";
                          }
                          const submitEvent = new Event('submit', { bubbles: true, cancelable: true });
                          const allowed = form.dispatchEvent(submitEvent);
                          if (allowed) {
                            form.submit();
                            return "fallback-submit";
                          }
                          return "submit-cancelled";
                        })();
                        """.trimIndent(),
                        null
                    )
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                    try {
                        val url = request.url.toString()
                        if (
                            request.url.host == HOST &&
                            request.url.encodedPath == "/filter" &&
                            request.url.getQueryParameter("keyword").isNullOrBlank().not() &&
                            request.url.getQueryParameter("vrf").isNullOrBlank().not()
                        ) {
                            result += url
                            latch.countDown()
                        }
                    } catch (throwable: Throwable) {
                        errors += throwable
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(activeWebView, true)
            activeWebView.loadUrl("$BASE_URL/home")
        }

        if (!latch.await(20, TimeUnit.SECONDS)) {
            disposeWebView(webView)
            throw IOException("Timed out while resolving MangaFire search flow")
        }
        disposeWebView(webView)
        errors.firstOrNull()?.let { throw IOException(it.message ?: "Could not resolve MangaFire search flow", it) }
        return result.firstOrNull() ?: throw IOException("Could not capture MangaFire search request")
    }

    private fun disposeWebView(webView: WebView?) {
        mainHandler.post {
            webView ?: return@post
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    private fun parseChapterContext(html: String, currentPath: String): ChapterContext? {
        if (html.isBlank()) return null
        val document = Jsoup.parseBodyFragment(html, BASE_URL)
        val items = document.select("li.item a[href]").map { link ->
            val path = normalizePath(link.attr("href"))
            val label = link.selectFirst("span")?.text()?.trim().orEmpty()
            ChapterContextEntry(path = path, label = label)
        }
        val index = items.indexOfFirst { it.path == currentPath }
        if (index < 0) return null
        return ChapterContext(
            label = items[index].label.ifBlank { currentPath.substringAfterLast('/') },
            previousPath = items.getOrNull(index + 1)?.path,
            nextPath = items.getOrNull(index - 1)?.path,
        )
    }

    private fun getDocument(path: String): Document {
        val request = Request.Builder()
            .url(toAbsoluteUrl(path))
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            return Jsoup.parse(response.body?.string().orEmpty(), BASE_URL)
        }
    }

    private fun getDocumentAbsolute(absoluteUrl: String, referer: String): Document =
        Jsoup.parse(getText(absoluteUrl = absoluteUrl, referer = referer, ajax = false), BASE_URL)

    private fun getJson(path: String): JSONObject =
        JSONObject(getText(absoluteUrl = toAbsoluteUrl(path), referer = BASE_URL, ajax = true))

    private fun getText(absoluteUrl: String, referer: String, ajax: Boolean): String {
        val request = Request.Builder()
            .url(absoluteUrl)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Cookie", cookieManager.getCookie(absoluteUrl).orEmpty())
            .apply {
                if (ajax) {
                    header("X-Requested-With", "XMLHttpRequest")
                    header("Accept", "application/json, text/javascript, */*; q=0.01")
                }
            }
            .build()
        client.newCall(request).execute().use { response ->
            return response.body?.string().orEmpty()
        }
    }

    private fun parseCatalogCards(document: Document, providerId: String): List<MangaSummary> =
        document.select(".original.card-lg .unit .inner").mapNotNull { item ->
            val detailLink = item.selectFirst(".info > a[href]") ?: return@mapNotNull null
            val detailPath = normalizePath(detailLink.attr("href"))
            val title = detailLink.text().trim()
            val coverUrl = item.selectFirst(".poster img")?.absUrl("src").orEmpty()

            var status = ""
            var periodicity = ""
            var chaptersCount = ""
            item.select(".info-list .item").forEach { infoItem ->
                val label = infoItem.selectFirst("b")?.text()?.trim().orEmpty().lowercase()
                val value = infoItem.ownText().trim()
                when {
                    "status" in label -> status = value
                    "period" in label -> periodicity = value
                    "chap" in label -> chaptersCount = value
                }
            }

            if (detailPath.isBlank() || title.isBlank()) null else {
                MangaSummary(
                    providerId = providerId,
                    title = title,
                    detailPath = detailPath,
                    coverUrl = coverUrl,
                    status = status,
                    periodicity = periodicity,
                    chaptersCount = chaptersCount,
                )
            }
        }

    private fun JSONArray.toReaderPages(chapterPath: String): List<ReaderPage> =
        buildList(length()) {
            for (index in 0 until length()) {
                val entry = optJSONArray(index) ?: continue
                val imageUrl = entry.optString(0).orEmpty()
                if (imageUrl.isBlank()) continue
                val offset = entry.optInt(2, 0)
                val resolvedImageUrl = if (offset > 0) {
                    cacheDescrambledPage(chapterPath = chapterPath, pageIndex = index, imageUrl = imageUrl, offset = offset)
                } else {
                    imageUrl
                }
                add(
                    ReaderPage(
                        id = (index + 1).toString(),
                        numberLabel = (index + 1).toString(),
                        imageUrl = resolvedImageUrl,
                    )
                )
            }
        }

    private fun cacheDescrambledPage(chapterPath: String, pageIndex: Int, imageUrl: String, offset: Int): String {
        val cacheDir = File(context.cacheDir, "mangafire-pages").apply { mkdirs() }
        val fileName = sha1("$chapterPath|$pageIndex|$offset|$imageUrl") + ".jpg"
        val file = File(cacheDir, fileName)
        if (!file.exists()) {
            val scrambledBytes = downloadBytes(imageUrl, referer = chapterPath)
            file.writeBytes(descramble(scrambledBytes, offset))
        }
        return Uri.fromFile(file).toString()
    }

    private fun descramble(imageBytes: ByteArray, offset: Int): ByteArray {
        if (offset <= 0) return imageBytes
        val source = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return imageBytes
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val pieceWidth = min(200, ceil(source.width / 5.0).toInt())
        val pieceHeight = min(200, ceil(source.height / 5.0).toInt())
        val xMax = ceil(source.width / pieceWidth.toDouble()).toInt() - 1
        val yMax = ceil(source.height / pieceHeight.toDouble()).toInt() - 1
        val canvas = Canvas(result)
        for (y in 0..yMax) {
            for (x in 0..xMax) {
                val dstX = pieceWidth * x
                val dstY = pieceHeight * y
                val srcX = pieceWidth * ((x + offset) % (xMax + 1))
                val srcY = pieceHeight * y
                val tileWidth = min(pieceWidth, source.width - srcX)
                val tileHeight = min(pieceHeight, source.height - srcY)
                val tile = Bitmap.createBitmap(source, srcX, srcY, tileWidth, tileHeight)
                canvas.drawBitmap(tile, dstX.toFloat(), dstY.toFloat(), null)
                tile.recycle()
            }
        }
        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 95, output)
        source.recycle()
        result.recycle()
        return output.toByteArray()
    }

    private fun toAbsoluteUrl(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path else "$BASE_URL${normalizePath(path)}"

    private fun normalizePath(path: String): String {
        if (path.isBlank()) return ""
        val uri = Uri.parse(path)
        return when {
            !uri.scheme.isNullOrBlank() -> uri.path.orEmpty()
            path.startsWith("/") -> path
            else -> "/$path"
        }
    }

    private fun emptyJsonResponse(): WebResourceResponse = jsonResponse("""{"status":200,"result":{}}""")

    private fun jsonResponse(body: String): WebResourceResponse =
        WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(body.toByteArray()))

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private data class ChapterContextEntry(
        val path: String,
        val label: String,
    )

    private data class ChapterContext(
        val label: String,
        val previousPath: String?,
        val nextPath: String?,
    )

    companion object {
        const val BASE_URL = "https://mangafire.to"
        const val HOST = "mangafire.to"
        const val FILE_SCHEME_PREFIX = "file://"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
