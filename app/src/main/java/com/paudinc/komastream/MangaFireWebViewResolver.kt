package com.paudinc.komastream

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
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
            mangaTitle = mangaTitle.ifBlank { detailPath.substringAfterLast('/').substringBeforeLast('.').replace('-', ' ') },
            mangaDetailPath = detailPath,
            chapterTitle = chapterContext?.label ?: normalizedChapterPath.substringAfterLast('/'),
            chapterPath = normalizedChapterPath,
            previousChapterPath = chapterContext?.previousPath,
            nextChapterPath = chapterContext?.nextPath,
            pages = pages,
        )
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
            mainHandler.post { webView?.destroy() }
            throw IOException("Timed out while resolving MangaFire reader flow")
        }
        mainHandler.post { webView?.destroy() }
        errors.firstOrNull()?.let { throw IOException(it.message ?: "Could not resolve MangaFire reader flow", it) }
        return result.firstOrNull() ?: throw IOException("Could not capture MangaFire reader request")
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

    private fun getDocument(path: String): org.jsoup.nodes.Document {
        val request = Request.Builder()
            .url(toAbsoluteUrl(path))
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            return Jsoup.parse(response.body?.string().orEmpty(), BASE_URL)
        }
    }

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

    private fun org.json.JSONArray.toReaderPages(chapterPath: String): List<ReaderPage> =
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
        val source = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return imageBytes
        val result = android.graphics.Bitmap.createBitmap(source.width, source.height, android.graphics.Bitmap.Config.ARGB_8888)
        val pieceWidth = min(200, ceil(source.width / 5.0).toInt())
        val pieceHeight = min(200, ceil(source.height / 5.0).toInt())
        val xMax = ceil(source.width / pieceWidth.toDouble()).toInt() - 1
        val yMax = ceil(source.height / pieceHeight.toDouble()).toInt() - 1
        val canvas = android.graphics.Canvas(result)
        for (y in 0..yMax) {
            for (x in 0..xMax) {
                val dstX = pieceWidth * x
                val dstY = pieceHeight * y
                val srcX = pieceWidth * ((x + offset) % (xMax + 1))
                val srcY = pieceHeight * y
                val tileWidth = min(pieceWidth, source.width - srcX)
                val tileHeight = min(pieceHeight, source.height - srcY)
                val tile = android.graphics.Bitmap.createBitmap(source, srcX, srcY, tileWidth, tileHeight)
                canvas.drawBitmap(tile, dstX.toFloat(), dstY.toFloat(), null)
                tile.recycle()
            }
        }
        val output = java.io.ByteArrayOutputStream()
        result.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, output)
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

    private companion object {
        private const val BASE_URL = "https://mangafire.to"
        private const val HOST = "mangafire.to"
        private const val FILE_SCHEME_PREFIX = "file://"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
