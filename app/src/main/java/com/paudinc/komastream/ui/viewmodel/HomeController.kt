package com.paudinc.komastream.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paudinc.komastream.provider.MangaProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeController(
    private val scope: CoroutineScope,
) {
    var uiState by mutableStateOf(HomeUiState())
        private set

    @Volatile
    private var refreshToken: Long = 0L

    fun refreshHome(
        provider: MangaProvider,
        onError: (String) -> Unit,
        force: Boolean = false,
    ) {
        if (uiState.isRefreshing && !force) return
        val requestToken = synchronized(this) {
            refreshToken += 1L
            refreshToken
        }

        scope.launch {
            uiState = uiState.copy(isRefreshing = true)

            runCatching { withContext(Dispatchers.IO) { provider.fetchHomeFeed() } }
                .onSuccess { feed ->
                    if (requestToken == refreshToken) {
                        uiState = uiState.copy(
                            feed = feed,
                            isRefreshing = false,
                        )
                    }
                }
                .onFailure {
                    if (requestToken == refreshToken) {
                        uiState = uiState.copy(isRefreshing = false)
                        val message = it.message.orEmpty()
                        if (
                            message.contains("Cloudflare challenge", ignoreCase = true) ||
                            message.contains("cf_clearance", ignoreCase = true) ||
                            message.contains("challenge was not fully solved", ignoreCase = true)
                        ) {
                            uiState = uiState.copy(feed = emptyHomeFeed())
                        }
                        onError(it.message ?: "Could not load home")
                    }
                }
        }
    }

    fun clearFeed() {
        uiState = uiState.copy(feed = null)
    }

    fun showEmptyFeed() {
        uiState = uiState.copy(feed = emptyHomeFeed(), isRefreshing = false)
    }
}
