package com.paudinc.komastream.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.view.ViewGroup
import android.webkit.WebViewClient
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.ReaderPage
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.min

class MangaFireWebViewResolver(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cookieManager = CookieManager.getInstance()

    init {
        AppCacheMaintenance.trimMangaFirePageCache(context)
    }

    data class DetailPayload(
        val titleResponse: JSONObject,
        val chaptersResponse: JSONObject,
    )

    fun fetchDetailPayload(titleId: String, detailUrl: String): DetailPayload {
        val latch = CountDownLatch(1)
        val titleBody = AtomicReference<String>()
        val chapterBodies = ConcurrentHashMap<Int, String>()
        val lastChapterPage = AtomicInteger(0)
        val error = AtomicReference<Throwable>()
        val completed = AtomicBoolean(false)
        val titlePath = "/api/titles/$titleId"
        val chaptersPath = "$titlePath/chapters"
        var webView: WebView? = null

        fun finishIfComplete() {
            val lastPage = lastChapterPage.get()
            if (
                titleBody.get() != null &&
                lastPage > 0 &&
                chapterBodies.size >= lastPage &&
                completed.compareAndSet(false, true)
            ) {
                latch.countDown()
            }
        }

        fun fail(throwable: Throwable) {
            error.compareAndSet(null, throwable)
            if (completed.compareAndSet(false, true)) latch.countDown()
        }

        fun scheduleNextPage(activeWebView: WebView, currentPage: Int, attempt: Int = 0) {
            mainHandler.postDelayed(
                {
                    if (completed.get()) return@postDelayed
                    activeWebView.evaluateJavascript(
                        """
                        (function() {
                          const pager = document.querySelector(
                            '.title-detail__chapters-pager .npager, .reader-chapters__pager .npager'
                          );
                          if (!pager) return 'wait';
                          const active = pager.querySelector('.npager__num.is-active');
                          if (!active || Number(active.textContent.trim()) !== $currentPage) return 'wait';
                          const nextPage = String($currentPage + 1);
                          const next = Array.from(pager.querySelectorAll('.npager__num'))
                            .find(button => button.textContent.trim() === nextPage)
                            || pager.querySelector('button[aria-label="Next page"]');
                          if (!next || next.disabled) return 'missing';
                          next.click();
                          return 'clicked';
                        })();
                        """.trimIndent(),
                    ) { value ->
                        if (value == "\"clicked\"") return@evaluateJavascript
                        if (attempt < CHAPTER_PAGER_MAX_ATTEMPTS) {
                            scheduleNextPage(activeWebView, currentPage, attempt + 1)
                        } else {
                            fail(IOException("Could not advance MangaFire chapter pager from page $currentPage"))
                        }
                    }
                },
                CHAPTER_PAGE_DELAY_MS,
            )
        }

        mainHandler.post {
            webView = WebView(context)
            val activeWebView = checkNotNull(webView)
            activeWebView.settings.javaScriptEnabled = true
            activeWebView.settings.domStorageEnabled = true
            activeWebView.settings.loadsImagesAutomatically = false
            activeWebView.settings.blockNetworkImage = true
            activeWebView.settings.userAgentString = USER_AGENT
            activeWebView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (request.url.host != HOST) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    val path = request.url.encodedPath
                    if (path == "$titlePath/volumes") {
                        return jsonResponse("""{"items":[],"meta":{"hasNext":false}}""")
                    }
                    if (path != titlePath && path != chaptersPath) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    if (request.url.getQueryParameter("vrf").isNullOrBlank()) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    if (path == titlePath) {
                        titleBody.get()?.let { return jsonResponse(it) }
                    } else {
                        val requestedPage = request.url.getQueryParameter("page")?.toIntOrNull()
                        requestedPage?.let(chapterBodies::get)?.let { return jsonResponse(it) }
                    }

                    return try {
                        val body = executeProtectedRequest(request, detailUrl)
                        if (path == titlePath) {
                            titleBody.compareAndSet(null, body)
                            finishIfComplete()
                        } else {
                            val response = JSONObject(body)
                            val meta = response.optJSONObject("meta") ?: JSONObject()
                            val page = meta.optInt("page", 1).coerceAtLeast(1)
                            val lastPage = meta.optInt("lastPage", page).coerceAtLeast(page)
                            lastChapterPage.set(lastPage)
                            if (chapterBodies.putIfAbsent(page, body) == null) {
                                if (page < lastPage) {
                                    scheduleNextPage(activeWebView, page)
                                }
                                finishIfComplete()
                            }
                        }
                        jsonResponse(body)
                    } catch (throwable: Throwable) {
                        fail(throwable)
                        emptyJsonResponse()
                    }
                }
            }
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(activeWebView, true)
            activeWebView.loadUrl(detailUrl)
        }

        if (!latch.await(DETAIL_PAYLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            disposeWebView(webView)
            throw IOException(
                "MangaFire chapter collection timed out " +
                    "(${chapterBodies.size}/${lastChapterPage.get()} pages)"
            )
        }
        disposeWebView(webView)
        error.get()?.let { throw it }
        val allChapterItems = JSONArray()
        chapterBodies.toSortedMap().values.forEach { pageBody ->
            val items = JSONObject(pageBody).optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                allChapterItems.put(items.opt(index))
            }
        }
        return DetailPayload(
            titleResponse = JSONObject(titleBody.get().orEmpty()),
            chaptersResponse = JSONObject()
                .put("items", allChapterItems)
                .put(
                    "meta",
                    JSONObject()
                        .put("total", allChapterItems.length())
                        .put("page", 1)
                        .put("lastPage", 1)
                        .put("hasNext", false),
                ),
        )
    }

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

    /**
     * MangaFire now protects several JSON endpoints with a browser-issued
     * token.  OkHttp cannot obtain that token, but a same-origin WebView can.
     */
    fun fetchJson(absoluteUrl: String, referer: String = BASE_URL): JSONObject {
        val latch = CountDownLatch(1)
        val token = AtomicReference<String>()
        val error = AtomicReference<Throwable>()
        val completed = AtomicBoolean(false)
        var webView: WebView? = null
        val escapedUrl = JSONObject.quote(absoluteUrl)
        val callbackPath = "/__komastream_vrf"

        mainHandler.post {
            webView = WebView(context)
            val activeWebView = checkNotNull(webView)
            activeWebView.settings.javaScriptEnabled = true
            activeWebView.settings.domStorageEnabled = true
            activeWebView.settings.loadsImagesAutomatically = false
            activeWebView.settings.blockNetworkImage = true
            activeWebView.settings.userAgentString = USER_AGENT
            activeWebView.webViewClient = object : WebViewClient() {
                private var requestedToken = false

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    if (request.url.host != HOST || request.url.encodedPath != callbackPath) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    if (completed.compareAndSet(false, true)) {
                        val resolvedToken = request.url.getQueryParameter("token").orEmpty()
                        val tokenError = request.url.getQueryParameter("error").orEmpty()
                        when {
                            resolvedToken.isNotBlank() -> token.set(resolvedToken)
                            tokenError.isNotBlank() -> error.set(IOException(tokenError))
                            else -> error.set(IOException("MangaFire generated an empty token"))
                        }
                        latch.countDown()
                    }
                    return emptyJsonResponse()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (requestedToken || view == null || url.isNullOrBlank() || !url.startsWith(BASE_URL)) return
                    requestedToken = true
                    view.evaluateJavascript(
                        """
                        (async function() {
                          const requested = new URL($escapedUrl);
                          const moduleUrl = Array.from(
                            document.querySelectorAll('link[rel="modulepreload"][href]')
                          ).map(link => link.href).find(href => /\/polyfill-[^/]+\.js(?:\?|$)/.test(href));
                          if (!moduleUrl) throw new Error('MangaFire token module was not found');

                          const tokenModule = await import(moduleUrl);
                          let transformRequest = null;
                          tokenModule.a({
                            interceptors: {
                              request: {
                                use: handler => { transformRequest = handler; }
                              }
                            }
                          });
                          if (typeof transformRequest !== 'function') {
                            throw new Error('MangaFire token interceptor was not installed');
                          }

                          const params = {};
                          for (const [rawKey, value] of requested.searchParams) {
                            if (rawKey === 'vrf') continue;
                            const bracket = rawKey.match(/^([^\[\]]+)\[([^\[\]]*)\]$/);
                            if (bracket && bracket[2] === '') {
                              (params[bracket[1]] ??= []).push(value);
                            } else if (bracket) {
                              (params[bracket[1]] ??= {})[bracket[2]] = value;
                            } else if (Object.prototype.hasOwnProperty.call(params, rawKey)) {
                              params[rawKey] = Array.isArray(params[rawKey])
                                ? [...params[rawKey], value]
                                : [params[rawKey], value];
                            } else {
                              params[rawKey] = value;
                            }
                          }

                          const apiPath = requested.pathname.startsWith('/api/')
                            ? requested.pathname.slice(4)
                            : requested.pathname;
                          const transformed = await transformRequest({
                            url: apiPath,
                            baseURL: '/api',
                            method: 'get',
                            params,
                            headers: {}
                          });
                          const vrf = transformed && transformed.params && transformed.params.vrf;
                          if (typeof vrf !== 'string' || !vrf) {
                            throw new Error('MangaFire did not generate a token');
                          }
                          return vrf;
                        })().then(vrf => {
                          fetch('$callbackPath?token=' + encodeURIComponent(vrf));
                        }).catch(cause => {
                          const message = cause instanceof Error ? cause.message : String(cause);
                          fetch('$callbackPath?error=' + encodeURIComponent(message));
                        });
                        """.trimIndent(),
                        null,
                    )
                }
            }
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(activeWebView, true)
            activeWebView.loadUrl(referer)
        }

        if (!latch.await(15, TimeUnit.SECONDS)) {
            disposeWebView(webView)
            throw IOException("MangaFire did not generate a token for the requested API URL")
        }
        disposeWebView(webView)
        error.get()?.let { throw IOException("Could not generate MangaFire API token", it) }
        val tokenizedUrl = mangaFireUrlWithVrf(
            absoluteUrl = absoluteUrl,
            vrf = token.get() ?: throw IOException("MangaFire did not generate a token"),
        )
        val body = executeProtectedRequest(tokenizedUrl, referer)
        return runCatching { JSONObject(body) }
            .getOrElse { throw IOException("Could not parse MangaFire JSON response", it) }
    }

    private fun executeProtectedRequest(request: WebResourceRequest, referer: String): String {
        return executeProtectedRequest(
            url = request.url.toString().toHttpUrlOrNull()
                ?: throw IOException("Invalid MangaFire protected API URL"),
            referer = referer,
        )
    }

    private fun executeProtectedRequest(url: HttpUrl, referer: String): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
        cookieManager.getCookie(url.toString())
            ?.takeIf { it.isNotBlank() }
            ?.let { requestBuilder.header("Cookie", it) }
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("MangaFire protected API returned HTTP ${response.code}")
            }
            JSONObject(body)
            return body
        }
    }

    fun searchCatalog(providerId: String, query: String, skip: Int, take: Int): List<MangaSummary> {
        val resolvedUrl = resolveSearchUrl(query)
        val document = getDocumentAbsolute(resolvedUrl, referer = BASE_URL)
        val items = parseCatalogCards(document, providerId)
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
            (webView.parent as? ViewGroup)?.removeView(webView)
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
        AppCacheMaintenance.trimMangaFirePageCache(context)
        val cacheDir = File(context.cacheDir, "mangafire-pages").apply { mkdirs() }
        val fileName = sha1("$chapterPath|$pageIndex|$offset|$imageUrl") + ".jpg"
        val file = File(cacheDir, fileName)
        if (!file.exists()) {
            val scrambledBytes = downloadBytes(imageUrl, referer = chapterPath)
            file.writeBytes(descramble(scrambledBytes, offset))
        } else {
            file.setLastModified(System.currentTimeMillis())
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
                val srcRect = Rect(srcX, srcY, srcX + tileWidth, srcY + tileHeight)
                val dstRect = Rect(dstX, dstY, dstX + tileWidth, dstY + tileHeight)
                canvas.drawBitmap(source, srcRect, dstRect, null)
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
            !uri.scheme.isNullOrBlank() -> uri.path.orEmpty().lowercase(Locale.ROOT)
            path.startsWith("/") -> path.lowercase(Locale.ROOT)
            else -> "/$path".lowercase(Locale.ROOT)
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
        private const val CHAPTER_PAGE_DELAY_MS = 800L
        private const val CHAPTER_PAGER_MAX_ATTEMPTS = 20
        private const val DETAIL_PAYLOAD_TIMEOUT_SECONDS = 90L
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}

internal fun mangaFireUrlWithVrf(absoluteUrl: String, vrf: String): HttpUrl {
    val requestedUrl = absoluteUrl.toHttpUrlOrNull()
        ?: throw IOException("Invalid MangaFire API URL")
    return requestedUrl.newBuilder()
        .removeAllQueryParameters("vrf")
        .addQueryParameter("vrf", vrf)
        .build()
}
