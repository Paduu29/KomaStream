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

    fun refreshHome(
        provider: MangaProvider,
        onError: (String) -> Unit,
    ) {
        if (uiState.isRefreshing) return

        scope.launch {
            uiState = uiState.copy(isRefreshing = true)

            runCatching { withContext(Dispatchers.IO) { provider.fetchHomeFeed() } }
                .onSuccess {
                    uiState = uiState.copy(
                        feed = it,
                        isRefreshing = false
                    )
                }
                .onFailure {
                    uiState = uiState.copy(isRefreshing = false)
                    onError(it.message ?: "Could not load home")
                }
        }
    }

    fun clearFeed() {
        uiState = uiState.copy(feed = null)
    }
}
