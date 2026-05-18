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
    if (host != "mangadot.net" && !host.endsWith(".mangadot.net")) {
        return null
    }

    val cookieHeader = WebkitCookieManager.getInstance().getCookie(url).orEmpty()
    val builder = Headers.Builder()
        .add("User-Agent", MangadotProvider.USER_AGENT)
        .add("Referer", "https://mangadot.net/")
    if (cookieHeader.isNotBlank()) {
        builder.add("Cookie", cookieHeader)
    }
    return builder.build()
}
