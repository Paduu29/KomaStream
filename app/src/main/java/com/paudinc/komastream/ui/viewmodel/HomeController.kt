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
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { provider.fetchHomeFeed() } }
                .onSuccess { uiState = uiState.copy(feed = it) }
                .onFailure {
                    Log.e("KomaStream", "Could not fetch home feed", it)
                    onError(it.message ?: "Could not load home")
                }
        }
    }

    fun clearFeed() {
        uiState = uiState.copy(feed = null)
    }
}
