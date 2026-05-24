package com.paudinc.komastream.ui.viewmodel

import com.paudinc.komastream.provider.MangaProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeController(
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    @Volatile
    private var refreshToken: Long = 0L

    fun refreshHome(
        provider: MangaProvider,
        onError: (String) -> Unit,
        force: Boolean = false,
    ) {
        if (_uiState.value.isRefreshing && !force) return
        val requestToken = synchronized(this) {
            refreshToken += 1L
            refreshToken
        }

        scope.launch {
            _uiState.update { it.copy(isRefreshing = true) }

            runCatching { withContext(Dispatchers.IO) { provider.fetchHomeFeed() } }
                .onSuccess { feed ->
                    if (requestToken == refreshToken) {
                        _uiState.update {
                            it.copy(
                            feed = feed,
                            isRefreshing = false,
                        )
                        }
                    }
                }
                .onFailure {
                    if (requestToken == refreshToken) {
                        _uiState.update { state -> state.copy(isRefreshing = false) }
                        val message = it.message.orEmpty()
                        if (
                            message.contains("Cloudflare challenge", ignoreCase = true) ||
                            message.contains("cf_clearance", ignoreCase = true) ||
                            message.contains("challenge was not fully solved", ignoreCase = true)
                        ) {
                            _uiState.update { state -> state.copy(feed = emptyHomeFeed()) }
                        }
                        onError(it.message ?: "Could not load home")
                    }
                }
        }
    }

    fun clearFeed() {
        _uiState.update { it.copy(feed = null) }
    }

    fun showEmptyFeed() {
        _uiState.update { it.copy(feed = emptyHomeFeed(), isRefreshing = false) }
    }
}
