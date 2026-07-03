package com.paudinc.komastream.ui.viewmodel

import com.paudinc.komastream.data.model.HomeFeedSection
import com.paudinc.komastream.data.model.HomeSectionPageResult
import com.paudinc.komastream.data.model.HomeSectionType
import com.paudinc.komastream.data.model.MangaSummary
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
        onCloudflareChallenge: (() -> Unit)? = null,
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
                            onCloudflareChallenge?.invoke()
                        }
                        onError(it.message ?: "Could not load home")
                    }
                }
        }
    }

    fun bindSection(
        provider: MangaProvider,
        section: HomeFeedSection,
        onError: (String) -> Unit,
    ) {
        val sectionKey = homeSectionStateKey(provider.id, section.id)
        val sectionSignature = buildHomeSectionSignature(provider.id, section.id, section)
        val currentState = _uiState.value.sectionStates[sectionKey]
        if (currentState?.initializedSectionSignature == sectionSignature) return

        _uiState.update { state ->
            state.copy(
                sectionStates = state.sectionStates + (
                    sectionKey to HomeSectionUiState(
                        providerId = provider.id,
                        sectionId = section.id,
                        title = section.title,
                        type = section.type,
                        mangas = section.mangas,
                        chapters = section.chapters,
                        currentPage = 1,
                        hasMore = initialHasMore(provider.id, section),
                        isLoadingMore = false,
                        initializedSectionSignature = sectionSignature,
                    )
                )
            )
        }

        if (!shouldRefreshFromPagedSource(provider.id, section.id)) return

        scope.launch {
            runCatching { withContext(Dispatchers.IO) { provider.fetchHomeSectionPage(section.id, 1) } }
                .onSuccess { result ->
                    if (result != null && (result.mangas.isNotEmpty() || result.chapters.isNotEmpty())) {
                        updateSectionState(sectionKey) {
                            it.copy(
                                mangas = result.mangas,
                                chapters = result.chapters,
                                currentPage = 1,
                                hasMore = result.hasMore,
                            )
                        }
                    }
                }
                .onFailure { error ->
                    onError(error.message ?: "Could not load home section")
                }
        }
    }

    fun loadNextSectionPage(
        provider: MangaProvider,
        sectionId: String,
        onError: (String) -> Unit,
    ) {
        val sectionKey = homeSectionStateKey(provider.id, sectionId)
        val state = _uiState.value.sectionStates[sectionKey] ?: return
        if (state.isLoadingMore || !state.hasMore) return

        updateSectionState(sectionKey) { it.copy(isLoadingMore = true) }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    provider.fetchHomeSectionPage(sectionId, state.currentPage + 1)
                }
            }.onSuccess { result ->
                val currentState = _uiState.value.sectionStates[sectionKey] ?: return@onSuccess
                if (result == null) {
                    updateSectionState(sectionKey) { it.copy(hasMore = false, isLoadingMore = false) }
                    return@onSuccess
                }
                updateSectionState(sectionKey) {
                    mergeSectionPage(
                        providerId = provider.id,
                        sectionId = sectionId,
                        currentState = currentState,
                        result = result,
                    )
                }
            }.onFailure { error ->
                updateSectionState(sectionKey) { it.copy(isLoadingMore = false) }
                onError(error.message ?: "Could not load home section")
            }
        }
    }

    fun sectionState(providerId: String, sectionId: String): HomeSectionUiState? {
        return _uiState.value.sectionStates[homeSectionStateKey(providerId, sectionId)]
    }

    fun clearFeed() {
        _uiState.update { it.copy(feed = null, sectionStates = emptyMap()) }
    }

    fun showEmptyFeed() {
        _uiState.update { it.copy(feed = emptyHomeFeed(), isRefreshing = false, sectionStates = emptyMap()) }
    }

    private fun updateSectionState(
        sectionKey: String,
        transform: (HomeSectionUiState) -> HomeSectionUiState,
    ) {
        _uiState.update { state ->
            val existing = state.sectionStates[sectionKey] ?: return@update state
            state.copy(sectionStates = state.sectionStates + (sectionKey to transform(existing)))
        }
    }
}

private fun homeSectionStateKey(providerId: String, sectionId: String): String = "$providerId:$sectionId"

private fun initialHasMore(providerId: String, section: HomeFeedSection): Boolean =
    when {
        providerId == "leermangaesp-es" && section.id == "populares" -> section.mangas.size >= 20
        providerId == "leermangaesp-es" && section.id == "capitulos-recientes" -> section.chapters.size >= 20
        providerId == "mangatube-de" && section.id == "latest-updates" -> section.chapters.size >= 40
        providerId == "manhwa-latino-es" && section.id == "featured" -> section.mangas.size >= 20
        providerId == "manhwa-latino-es" && section.id == "latest-updates" -> section.chapters.size >= 20
        providerId == "kagane-en" && section.type == HomeSectionType.MANGAS -> section.mangas.size >= 6
        providerId == "olympusbiblioteca-es" && section.id == "nuevos-lanzamientos" -> section.mangas.size >= 15
        providerId == "olympusbiblioteca-es" && section.id == "top-series" -> section.mangas.size >= 15
        providerId == "mkissa-en" && section.id == "latest-updates" -> section.chapters.size >= 20
        providerId == "mkissa-en" && section.type == HomeSectionType.MANGAS -> section.mangas.size >= 10
        else -> false
    }

private fun shouldRefreshFromPagedSource(providerId: String, sectionId: String): Boolean {
    return (providerId == "olympusbiblioteca-es" &&
        (sectionId == "nuevos-lanzamientos" || sectionId == "top-series")) ||
        providerId == "kagane-en" ||
        providerId == "mkissa-en"
}

private fun mergeSectionPage(
    providerId: String,
    sectionId: String,
    currentState: HomeSectionUiState,
    result: HomeSectionPageResult,
): HomeSectionUiState {
    return when (result.type) {
        HomeSectionType.MANGAS -> {
            val newItems = result.mangas.let { incomingItems ->
                if (providerId == "olympusbiblioteca-es" &&
                    (sectionId == "nuevos-lanzamientos" || sectionId == "top-series")
                ) {
                    incomingItems
                } else {
                    incomingItems.filterNot { incoming ->
                        currentState.mangas.any { existing ->
                            mangaIdentityKey(providerId, sectionId, existing) ==
                                mangaIdentityKey(providerId, sectionId, incoming)
                        }
                    }
                }
            }
            currentState.copy(
                type = result.type,
                mangas = currentState.mangas + newItems,
                currentPage = currentState.currentPage + 1,
                hasMore = result.hasMore && newItems.isNotEmpty(),
                isLoadingMore = false,
            )
        }
        HomeSectionType.CHAPTERS -> {
            val newItems = result.chapters
                .filterNot { incoming -> currentState.chapters.any { it.chapterPath == incoming.chapterPath } }
            currentState.copy(
                type = result.type,
                chapters = currentState.chapters + newItems,
                currentPage = currentState.currentPage + 1,
                hasMore = result.hasMore && newItems.isNotEmpty(),
                isLoadingMore = false,
            )
        }
    }
}

private fun mangaIdentityKey(providerId: String, sectionId: String, manga: MangaSummary): String =
    when {
        providerId == "olympusbiblioteca-es" && sectionId == "nuevos-lanzamientos" ->
            listOf(manga.detailPath, manga.latestPublication, manga.status).joinToString("|")
        else -> manga.detailPath
    }

private fun buildHomeSectionSignature(
    providerId: String,
    sectionId: String,
    section: HomeFeedSection,
): String = buildString {
    append(providerId)
    append('|')
    append(sectionId)
    append('|')
    append(section.title)
    append('|')
    append(section.mangas.size)
    append('|')
    append(section.chapters.size)
    append('|')
    section.mangas.take(3).forEach {
        append(it.detailPath)
        append(':')
        append(it.latestPublication)
        append(':')
        append(it.status)
        append('|')
    }
    section.chapters.take(3).forEach {
        append(it.chapterPath)
        append(':')
        append(it.registrationLabel)
        append('|')
    }
}
