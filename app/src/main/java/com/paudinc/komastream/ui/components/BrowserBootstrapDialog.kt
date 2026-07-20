package com.paudinc.komastream.ui.components

import android.webkit.CookieManager as WebkitCookieManager
import android.util.Log
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.provider.providers.MangadotProvider
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun BrowserBootstrapDialog(
    url: String,
    title: String = "Cloudflare",
    onClose: () -> Unit,
) {
    val tag = "BrowserBootstrap"
    var webView by remember(url) { mutableStateOf<WebView?>(null) }
    var pageFinished by remember(url) { mutableStateOf(false) }
    val latestOnClose by rememberUpdatedState(onClose)

    BackHandler(onBack = onClose)

    LaunchedEffect(url) {
        Log.d(tag, "open url=$url")
        var lastCookieHeader = ""
        var lastCookieChangeAt = 0L
        while (true) {
            val cookieHeader = WebkitCookieManager.getInstance().getCookie(url).orEmpty()
            Log.d(tag, "poll cookies=${cookieHeader.ifBlank { "<empty>" }}")
            if (cookieHeader != lastCookieHeader) {
                lastCookieHeader = cookieHeader
                lastCookieChangeAt = System.currentTimeMillis()
            }
            val hasClearance = cookieHeader.contains("cf_clearance=")
            val settledForMs = System.currentTimeMillis() - lastCookieChangeAt
            if (hasClearance && pageFinished && settledForMs >= 2_000L) {
                WebkitCookieManager.getInstance().flush()
                Log.d(tag, "cf_clearance settled for ${settledForMs}ms, closing dialog")
                delay(200.milliseconds)
                latestOnClose()
                break
            }
            delay(200.milliseconds)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "Solve the challenge; this closes automatically once ready.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                        )
                    }
                }
                HorizontalDivider()
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { viewContext ->
                            WebView(viewContext).apply {
                                webView = this
                                Log.d(tag, "webview created for url=$url")
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                settings.userAgentString = MangadotProvider.USER_AGENT
                                WebkitCookieManager.getInstance().setAcceptCookie(true)
                                WebkitCookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                        pageFinished = true
                                        Log.d(tag, "page finished url=${finishedUrl ?: "<unknown>"} current=${view?.url ?: "<none>"}")
                                    }
                                }
                                loadUrl(url)
                            }
                        },
                        update = { view ->
                            if (view.url != url) {
                                view.loadUrl(url)
                            }
                        },
                    )
                }
            }
        }
    }

    DisposableEffect(url) {
        onDispose {
            webView?.let(::disposeWebView)
            webView = null
        }
    }
}

private fun disposeWebView(webView: WebView) {
    runCatching {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.removeAllViews()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }
}
