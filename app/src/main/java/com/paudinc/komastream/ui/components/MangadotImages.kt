package com.paudinc.komastream.ui.components

import android.net.Uri
import android.webkit.CookieManager as WebkitCookieManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.paudinc.komastream.provider.providers.MangadotProvider
import okhttp3.Headers

private val MANGADOT_HOSTS = setOf(
    "mangadot.net",
    "manhwa-latino.com",
    "zai.manhwa-latino.com",
    "redhive.cyou",
)
private val MKISSA_HOSTS = setOf(
    "wp.youtube-anime.com",
    "aln.youtube-anime.com",
    "mangayaro.to",
    "allanime.day",
)

@Composable
fun MangadotAwareAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    placeholder: androidx.compose.ui.graphics.painter.Painter? = null,
    error: androidx.compose.ui.graphics.painter.Painter? = null,
) {
    val context = LocalContext.current
    val request = remember(model, context) {
        model?.takeIf(String::isNotBlank)?.let { imageUrl ->
            ImageRequest.Builder(context)
                .data(imageUrl)
                .apply {
                    mangadotImageHeaders(imageUrl)?.let { headers(it) }
                    mkissaImageHeaders(imageUrl)?.let { headers(it) }
                }
                .build()
        }
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = placeholder,
        error = error,
    )
}

fun mangadotImageHeaders(url: String): Headers? {
    val host = Uri.parse(url).host.orEmpty().lowercase()
    if (host !in MANGADOT_HOSTS && MANGADOT_HOSTS.none { host.endsWith(".$it") }) {
        return null
    }

    val cookieHeader = WebkitCookieManager.getInstance().getCookie(url).orEmpty()
    val builder = Headers.Builder()
        .add("User-Agent", MangadotProvider.USER_AGENT)
        .add(
            "Referer",
            when {
                host == "mangadot.net" || host.endsWith(".mangadot.net") -> "https://mangadot.net/"
                else -> "https://manhwa-latino.com/"
            },
        )
    if (cookieHeader.isNotBlank()) {
        builder.add("Cookie", cookieHeader)
    }
    return builder.build()
}

fun mkissaImageHeaders(url: String): Headers? {
    val host = Uri.parse(url).host.orEmpty().lowercase()
    if (host !in MKISSA_HOSTS && MKISSA_HOSTS.none { host.endsWith(".$it") }) {
        return null
    }
    return Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:152.0) Gecko/20100101 Firefox/152.0")
        .add("Referer", "https://mkissa.to/manga")
        .add("Origin", "https://mkissa.to")
        .add("Accept", "image/avif,image/webp,image/png,image/svg+xml,image/*;q=0.8,*/*;q=0.5")
        .build()
}
