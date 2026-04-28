package com.paudinc.komastream.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paudinc.komastream.BuildConfig
import com.paudinc.komastream.data.model.MangaChapter
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.MyAnimeListApi
import com.paudinc.komastream.utils.MyAnimeListLinkStore
import com.paudinc.komastream.utils.MyAnimeListSessionStore
import com.paudinc.komastream.utils.buildChapterPath
import com.paudinc.komastream.utils.chapterValue
import com.paudinc.komastream.utils.canonicalChapterKey
import com.paudinc.komastream.utils.canonicalChapterKeys
import com.paudinc.komastream.utils.generateMalCodeChallenge
import com.paudinc.komastream.utils.generateMalCodeVerifier
import com.paudinc.komastream.utils.generateMalState
import com.paudinc.komastream.utils.malRedirectUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MalSyncController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val providerRegistry: com.paudinc.komastream.utils.ProviderRegistry,
    private val libraryStore: LibraryStore,
    private val strings: AppStrings,
) {
    private val api = MyAnimeListApi()
    private val sessionStore = MyAnimeListSessionStore(context)
    private val linkStore = MyAnimeListLinkStore(context)
    private val clientId: String = BuildConfig.MAL_CLIENT_ID.trim()

    var uiState by mutableStateOf(buildState())
        private set

    fun refreshState() {
        uiState = buildState()
    }

    fun beginConnect(): String {
        require(clientId.isNotBlank()) { "MyAnimeList client ID is not configured" }
        val codeVerifier = generateMalCodeVerifier()
        val state = generateMalState()
        sessionStore.beginAuthorization(codeVerifier = codeVerifier, state = state)
        refreshState()
        return api.buildAuthorizationUrl(
            clientId = clientId,
            codeChallenge = generateMalCodeChallenge(codeVerifier),
            state = state,
            redirectUri = malRedirectUri(),
        )
    }

    fun handleAuthorizationCallback(uri: Uri?): Boolean {
        if (uri == null) return false
        val current = sessionStore.read()
        val code = uri.getQueryParameter("code").orEmpty()
        val returnedState = uri.getQueryParameter("state").orEmpty()
        val error = uri.getQueryParameter("error").orEmpty()
        if (error.isNotBlank()) {
            updateMessage(error, error = true)
            return true
        }
        if (code.isBlank() || returnedState.isBlank() || current.pendingState.isBlank()) return false
        if (returnedState != current.pendingState) {
            updateMessage("MAL login failed: state mismatch", error = true)
            return true
        }
        if (current.codeVerifier.isBlank()) {
            updateMessage("MAL login failed: missing verifier", error = true)
            return true
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val token = api.exchangeCodeForToken(
                        clientId = clientId,
                        codeVerifier = current.codeVerifier,
                        code = code,
                        redirectUri = malRedirectUri(),
                    )
                    val userInfo = api.getMyInfo(token.accessToken, clientId)
                    val username = userInfo.optString("name").ifBlank { userInfo.optString("username") }
                    sessionStore.saveConnectedAccount(
                        accessToken = token.accessToken,
                        refreshToken = token.refreshToken,
                        expiresInSeconds = token.expiresInSeconds,
                        username = username,
                    )
                }
            }.onSuccess {
                refreshState()
                updateMessage("Connected to MyAnimeList")
            }.onFailure { throwable ->
                updateMessage(throwable.message ?: "Could not connect to MyAnimeList", error = true)
            }
        }
        return true
    }

    fun disconnect() {
        sessionStore.clear()
        linkStore.clear()
        refreshState()
        updateMessage("Disconnected from MyAnimeList")
    }

    fun syncFromRemote() {
        val current = sessionStore.read()
        if (!isConnected(current)) {
            updateMessage("Connect to MyAnimeList first", error = true)
            return
        }
        scope.launch {
            uiState = uiState.copy(isSyncing = true, errorMessage = "")
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = refreshTokensIfNeeded(current)
                    val list = api.fetchUserMangaList(refreshed.accessToken, clientId)
                    linkStore.clear()
                    val mapped = list.mapNotNull { entry ->
                        resolveLocalManga(entry.manga.title, entry.manga.alternativeTitles)?.let { local ->
                            linkStore.setMangaId(local.providerId, local.detailPath, entry.manga.id)
                            local.copy(
                                malMangaId = entry.manga.id,
                                lastChapterTitle = if (entry.listStatus.numChaptersRead > 0) {
                                    "Chapter ${entry.listStatus.numChaptersRead}"
                                } else {
                                    ""
                                },
                            )
                        }
                    }
                    if (mapped.isNotEmpty()) {
                        libraryStore.replaceReading(mapped)
                    }
                }
            }.onSuccess {
                updateMessage("Synced from MyAnimeList")
            }.onFailure { throwable ->
                updateMessage(throwable.message ?: "Could not sync from MyAnimeList", error = true)
            }
            uiState = buildState().copy(isSyncing = false)
        }
    }

    fun syncLocalLibraryToRemote(providerId: String) {
        val current = sessionStore.read()
        if (!isConnected(current)) {
            updateMessage("Connect to MyAnimeList first", error = true)
            return
        }
        scope.launch {
            uiState = uiState.copy(isSyncing = true, errorMessage = "", lastMessage = "")
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = refreshTokensIfNeeded(current)
                    val state = libraryStore.read(filterBySelectedProvider = false)
                    val entriesByKey = linkedMapOf<String, SyncEntry>()

                    state.reading.filter { it.providerId == providerId }.forEach { manga ->
                        entriesByKey[syncKey(manga)] = SyncEntry(manga = manga, isReading = true)
                    }
                    state.favorites.filter { it.providerId == providerId }.forEach { manga ->
                        entriesByKey.putIfAbsent(syncKey(manga), SyncEntry(manga = manga, isReading = false))
                    }

                    entriesByKey.values.forEach { entry ->
                        syncMangaToRemote(
                            accessToken = refreshed.accessToken,
                            clientId = clientId,
                            manga = entry.manga,
                            isReading = entry.isReading,
                        )
                    }
                }
            }.onSuccess {
                updateMessage("Synced local library to MyAnimeList")
            }.onFailure { throwable ->
                updateMessage(throwable.message ?: "Could not sync to MyAnimeList", error = true)
            }
            uiState = buildState().copy(isSyncing = false)
        }
    }

    fun pushReadingEntry(manga: SavedManga, isRemoved: Boolean = false) {
        syncManga(manga, isRemoved = isRemoved, status = "reading", numChaptersRead = null)
    }

    fun pushFavoriteEntry(manga: SavedManga, isRemoved: Boolean = false) {
        syncManga(manga, isRemoved = isRemoved, status = "plan_to_read", numChaptersRead = 0)
    }

    fun pushReadProgress(manga: SavedManga, numChaptersRead: Int, isRemoved: Boolean = false) {
        syncManga(
            manga = manga,
            isRemoved = isRemoved,
            status = "reading",
            numChaptersRead = numChaptersRead,
        )
    }

    fun pushBulkReadProgress(
        providerId: String,
        detailPath: String,
        title: String,
        coverUrl: String,
        chapters: List<MangaChapter>,
        read: Boolean,
    ) {
        val manga = SavedManga(providerId, title, detailPath, coverUrl)
        val count = if (read) chapters.size else 0
        syncManga(manga, isRemoved = false, status = if (read) "reading" else "plan_to_read", numChaptersRead = count)
    }

    private fun syncManga(
        manga: SavedManga,
        isRemoved: Boolean,
        status: String,
        numChaptersRead: Int?,
    ) {
        val current = sessionStore.read()
        if (!isConnected(current)) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = refreshTokensIfNeeded(current)
                    val mangaId = resolveMangaId(refreshed.accessToken, manga)
                        ?: return@withContext
                    if (isRemoved) {
                        api.deleteMangaStatus(refreshed.accessToken, clientId, mangaId)
                    } else {
                        api.updateMangaStatus(
                            accessToken = refreshed.accessToken,
                            clientId = clientId,
                            mangaId = mangaId,
                            status = status,
                            numChaptersRead = numChaptersRead ?: 0,
                        )
                    }
                }
            }.onFailure { throwable ->
                updateMessage(throwable.message ?: "Could not sync with MyAnimeList", error = true)
            }
        }
    }

    private fun syncMangaToRemote(
        accessToken: String,
        clientId: String,
        manga: SavedManga,
        isReading: Boolean,
    ) {
        val detail = runCatching {
            val provider = providerRegistry.get(manga.providerId)
            provider.fetchMangaDetail(manga.detailPath)
        }.getOrNull() ?: return
        val target = manga.copy(
            title = manga.title.ifBlank { detail.title },
            coverUrl = manga.coverUrl.ifBlank { detail.coverUrl },
        )
        val mangaId = resolveMangaId(accessToken, target) ?: return
        val readCount = if (isReading) {
            resolveReadCountFromProgress(target, detail, libraryStore.readChaptersForProvider(target.providerId))
        } else {
            0
        }
        val status = when {
            isReading && detail.chapters.isNotEmpty() && readCount >= detail.chapters.size -> "completed"
            isReading -> "reading"
            else -> "plan_to_read"
        }
        api.updateMangaStatus(
            accessToken = accessToken,
            clientId = clientId,
            mangaId = mangaId,
            status = status,
            numChaptersRead = readCount,
        )
    }

    private fun resolveReadCountFromProgress(
        manga: SavedManga,
        detail: com.paudinc.komastream.data.model.MangaDetail,
        readChapters: Set<String>,
    ): Int {
        val explicitReadCount = countReadChapters(
            providerId = manga.providerId,
            detailPath = manga.detailPath,
            detail = detail,
            readChapters = readChapters,
        )
        if (explicitReadCount > 0) return explicitReadCount

        val progressPath = manga.lastChapterPath.trim()
        if (progressPath.isBlank()) return 0

        val progressValue = detail.chapters.firstOrNull { chapter ->
            canonicalChapterKey(manga.providerId, buildChapterPath(manga.detailPath, chapter)) ==
                canonicalChapterKey(manga.providerId, progressPath)
        }?.let(::chapterValue)
            ?: return 0

        return detail.chapters.count { chapter ->
            chapterValue(chapter) < progressValue
        }
    }

    private fun countReadChapters(
        providerId: String,
        detailPath: String,
        detail: com.paudinc.komastream.data.model.MangaDetail,
        readChapters: Set<String>,
    ): Int {
        val canonicalReadKeys = canonicalChapterKeys(providerId, readChapters)
        return detail.chapters.count { chapter ->
            canonicalChapterKey(providerId, buildChapterPath(detailPath, chapter)) in canonicalReadKeys
        }
    }

    private fun resolveMangaId(accessToken: String, manga: SavedManga): Long? {
        linkStore.getMangaId(manga.providerId, manga.detailPath)?.let { return it }
        if (manga.malMangaId != null) {
            linkStore.setMangaId(manga.providerId, manga.detailPath, manga.malMangaId)
            return manga.malMangaId
        }
        val candidates = buildList {
            add(manga.title)
            add(normalizeCandidateTitle(manga.title))
            add(manga.lastChapterTitle)
            add(normalizeCandidateTitle(manga.lastChapterTitle))
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        for (candidate in candidates) {
            val remote = api.searchManga(accessToken, clientId, candidate)
                .firstOrNull { matchesTitle(manga.title, it.title, it.alternativeTitles) }
            if (remote != null) {
                linkStore.setMangaId(manga.providerId, manga.detailPath, remote.id)
                return remote.id
            }
        }
        return null
    }

    private fun syncKey(manga: SavedManga): String = "${manga.providerId}::${manga.detailPath}"

    private data class SyncEntry(
        val manga: SavedManga,
        val isReading: Boolean,
    )

    private fun resolveLocalManga(title: String, alternativeTitles: List<String>): SavedManga? {
        val normalizedTitle = normalizeTitle(title)
        val providers = providerRegistry.all()
        for (provider in providers) {
            val searchResult = runCatching {
                provider.searchCatalog(
                    query = title,
                    categoryIds = emptyList(),
                    sortBy = "",
                    broadcastStatus = "",
                    onlyFavorites = false,
                    skip = 0,
                    take = 5,
                )
            }.getOrNull() ?: continue
            val candidate = searchResult.items.firstOrNull { item ->
                matchesTitle(normalizedTitle, normalizeTitle(item.title), alternativeTitles)
            } ?: searchResult.items.firstOrNull()
            if (candidate != null) {
                return SavedManga(
                    providerId = candidate.providerId,
                    title = candidate.title,
                    detailPath = candidate.detailPath,
                    coverUrl = candidate.coverUrl,
                    malMangaId = null,
                )
            }
        }
        return null
    }

    private fun matchesTitle(localTitle: String, remoteTitle: String, alternativeTitles: List<String>): Boolean {
        val normalizedLocal = normalizeTitle(localTitle)
        val normalizedRemote = normalizeTitle(remoteTitle)
        if (normalizedLocal.isBlank() || normalizedRemote.isBlank()) return false
        if (normalizedLocal == normalizedRemote) return true
        if (normalizedLocal in normalizedRemote || normalizedRemote in normalizedLocal) return true
        return alternativeTitles.any { normalizeTitle(it) == normalizedLocal }
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeCandidateTitle(value: String): String =
        value
            .replace(Regex("\\s*[-–:|]\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun refreshTokensIfNeeded(current: com.paudinc.komastream.utils.MyAnimeListSession): com.paudinc.komastream.utils.MyAnimeListSession {
        if (current.accessToken.isBlank() || current.refreshToken.isBlank()) {
            return current
        }
        val needsRefresh = current.accessTokenExpiresAtMs > 0 && System.currentTimeMillis() >= current.accessTokenExpiresAtMs - 60_000L
        if (!needsRefresh) return current
        val token = api.refreshToken(clientId, current.refreshToken)
        sessionStore.updateTokens(token.accessToken, token.refreshToken, token.expiresInSeconds)
        return sessionStore.read()
    }

    private fun isConnected(session: com.paudinc.komastream.utils.MyAnimeListSession): Boolean =
        clientId.isNotBlank() && session.accessToken.isNotBlank()

    private fun buildState(): MyAnimeListUiState {
        val session = sessionStore.read()
        return MyAnimeListUiState(
            isConfigured = clientId.isNotBlank(),
            isConnected = isConnected(session),
            clientId = clientId,
            username = session.username,
            isSyncing = false,
        )
    }

    private fun updateMessage(message: String, error: Boolean = false) {
        uiState = uiState.copy(
            lastMessage = if (error) "" else message,
            errorMessage = if (error) message else "",
            isConfigured = clientId.isNotBlank(),
            isConnected = isConnected(sessionStore.read()),
            clientId = clientId,
            username = sessionStore.read().username,
        )
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
