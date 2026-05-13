package com.paudinc.komastream.ui.viewmodel

import android.util.Log
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
            val providerId = provider.id

            runCatching { withContext(Dispatchers.IO) { provider.fetchHomeFeed() } }
                .onSuccess {
                    if (requestToken == refreshToken) {
                        uiState = uiState.copy(
                            feed = it,
                            isRefreshing = false
                        )
                    }
                }
                .onFailure {
                    if (requestToken == refreshToken) {
                        uiState = uiState.copy(isRefreshing = false)
                        onError(it.message ?: "Could not load home")
                    } else {
                        Log.d("KomaStream", "Dropped stale home refresh for $providerId", it)
                    }
                }
        }
    }

    fun clearFeed() {
        uiState = uiState.copy(feed = null)
    }
}
