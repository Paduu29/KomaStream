package com.paudinc.komastream.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paudinc.komastream.BuildConfig
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.MangaChapter
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.MalUserMangaEntry
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
import java.text.Normalizer

class MalSyncController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val providerRegistry: com.paudinc.komastream.utils.ProviderRegistry,
    private val libraryStore: LibraryStore,
    private val strings: AppStrings,
    private val onLocalLibraryChanged: (() -> Unit)? = null,
) {
    private companion object {
        private const val MIN_ACCEPTABLE_MAL_SCORE = 250
    }

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
        val preferredProviderId = libraryStore.read(filterBySelectedProvider = false).selectedProviderId
        scope.launch {
            beginSync()
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = refreshTokensIfNeeded(current)
                    val list = api.fetchUserMangaList(refreshed.accessToken, clientId)
                    val detailCache = mutableMapOf<String, MangaDetail?>()
                    mergeRemoteEntriesIntoLocal(
                        remoteEntries = list,
                        providerIdFilter = preferredProviderId,
                        detailCache = detailCache,
                        onItemProcessed = createProgressUpdater(totalItems = list.size),
                    )
                }
            }.onSuccess {
                onLocalLibraryChanged?.invoke()
                updateMessage("Synced from MyAnimeList")
            }.onFailure { throwable ->
                updateMessage(throwable.message ?: "Could not sync from MyAnimeList", error = true)
            }
            finishSync()
        }
    }

    fun syncLocalLibraryToRemote(providerId: String) {
        val current = sessionStore.read()
        if (!isConnected(current)) {
            updateMessage("Connect to MyAnimeList first", error = true)
            return
        }
        scope.launch {
            beginSync()
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = refreshTokensIfNeeded(current)
                    val remoteEntries = api.fetchUserMangaList(refreshed.accessToken, clientId)
                    val detailCache = mutableMapOf<String, MangaDetail?>()
                    val mangaIdCache = mutableMapOf<String, Long?>()
                    val currentProviderState = libraryStore.read(filterBySelectedProvider = false)
                    val preSyncState = currentProviderState
                    val preSyncReadChapters = libraryStore.readChaptersForProvider(providerId)
                    val isCurrentProviderLibraryEmpty =
                        currentProviderState.reading.none { it.providerId == providerId } &&
                            currentProviderState.favorites.none { it.providerId == providerId }
                    mergeRemoteEntriesIntoLocal(
                        remoteEntries = remoteEntries,
                        providerIdFilter = providerId,
                        addToFavorites = isCurrentProviderLibraryEmpty,
                        detailCache = detailCache,
                        onItemProcessed = null,
                    )
                    val remoteEntriesById = remoteEntries.associateBy { it.manga.id }
                    val state = preSyncState
                    val entriesByKey = linkedMapOf<String, SyncEntry>()

                    state.reading.filter { it.providerId == providerId }.forEach { manga ->
                        entriesByKey[syncKey(manga)] = SyncEntry(manga = manga, isReading = true)
                    }
                    state.favorites.filter { it.providerId == providerId }.forEach { manga ->
                        entriesByKey.putIfAbsent(syncKey(manga), SyncEntry(manga = manga, isReading = false))
                    }
                    val updateProgress = createProgressUpdater(totalItems = remoteEntries.size + entriesByKey.size)
                    repeat(remoteEntries.size) { updateProgress() }

                    entriesByKey.values.forEach { entry ->
                        val mangaId = resolveMangaIdCached(
                            accessToken = refreshed.accessToken,
                            manga = entry.manga,
                            cache = mangaIdCache,
                        ) ?: run {
                            updateProgress()
                            return@forEach
                        }
                        syncMangaToRemote(
                            accessToken = refreshed.accessToken,
                            clientId = clientId,
                            mangaId = mangaId,
                            manga = entry.manga,
                            isReading = entry.isReading,
                            remoteEntry = remoteEntriesById[mangaId],
                            detailCache = detailCache,
                            readChapters = preSyncReadChapters,
                        )
                        updateProgress()
                    }
                }
            }.onSuccess {
                onLocalLibraryChanged?.invoke()
                updateMessage("Synced local library to MyAnimeList")
            }.onFailure { throwable ->
                updateMessage(throwable.message ?: "Could not sync to MyAnimeList", error = true)
            }
            finishSync()
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
                        val remoteEntry = api.fetchUserMangaList(refreshed.accessToken, clientId)
                            .firstOrNull { it.manga.id == mangaId }
                        val mergedReadCount = maxOf(
                            numChaptersRead ?: 0,
                            remoteEntry?.listStatus?.numChaptersRead ?: 0,
                        )
                        val mergedStatus = mergeStatus(
                            localStatus = status,
                            localReadCount = numChaptersRead ?: 0,
                            mergedReadCount = mergedReadCount,
                            remoteStatus = remoteEntry?.listStatus?.status.orEmpty(),
                        )
                        api.updateMangaStatus(
                            accessToken = refreshed.accessToken,
                            clientId = clientId,
                            mangaId = mangaId,
                            status = mergedStatus,
                            numChaptersRead = mergedReadCount,
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
        mangaId: Long,
        manga: SavedManga,
        isReading: Boolean,
        remoteEntry: MalUserMangaEntry?,
        detailCache: MutableMap<String, MangaDetail?>,
        readChapters: Set<String>,
    ) {
        if (!isReading) {
            val remoteStatus = remoteEntry?.listStatus?.status.orEmpty()
            val remoteReadCount = remoteEntry?.listStatus?.numChaptersRead ?: 0
            if (remoteStatus == "plan_to_read" && remoteReadCount == 0) return
        }
        val detail = fetchDetailCached(manga, detailCache) ?: return
        val target = manga.copy(
            title = manga.title.ifBlank { detail.title },
            coverUrl = manga.coverUrl.ifBlank { detail.coverUrl },
        )
        val localReadCount = if (isReading) {
            resolveReadCountFromProgress(target, detail, readChapters)
        } else {
            0
        }
        val readCount = maxOf(localReadCount, remoteEntry?.listStatus?.numChaptersRead ?: 0)
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

    private fun mergeRemoteEntriesIntoLocal(
        remoteEntries: List<MalUserMangaEntry>,
        providerIdFilter: String? = null,
        addToFavorites: Boolean = false,
        detailCache: MutableMap<String, MangaDetail?>,
        onItemProcessed: (() -> Unit)? = null,
    ) {
        val currentState = libraryStore.read(filterBySelectedProvider = false)
        remoteEntries.forEach { entry ->
            try {
                val local = resolveLocalManga(
                    title = entry.manga.title,
                    alternativeTitles = entry.manga.alternativeTitles,
                    libraryState = currentState,
                    preferredProviderId = providerIdFilter,
                ) ?: return@forEach

                linkStore.setMangaId(local.providerId, local.detailPath, entry.manga.id)

                val detail = fetchDetailCached(local, detailCache)
                val progressSnapshot = detail?.let {
                    buildRemoteProgressSnapshot(
                        detailPath = local.detailPath,
                        detail = it,
                        remoteReadCount = entry.listStatus.numChaptersRead,
                    )
                }
                val remoteChapterCount = entry.listStatus.numChaptersRead.coerceAtLeast(0)
                val remoteChapterLabel = remoteChapterCount.takeIf { it > 0 }?.toString()
                    ?: progressSnapshot?.lastChapterTitle.orEmpty()

                if (progressSnapshot != null && progressSnapshot.readPaths.isNotEmpty()) {
                    libraryStore.setChaptersRead(local.providerId, progressSnapshot.readPaths, true)
                }

                if (addToFavorites) {
                    libraryStore.upsertFavorite(
                        local.copy(
                            malMangaId = entry.manga.id,
                            lastChapterTitle = remoteChapterLabel,
                            lastChapterPath = "",
                        )
                    )
                }

                if (shouldTrackLocally(entry)) {
                    libraryStore.upsertReading(
                        local.copy(
                            malMangaId = entry.manga.id,
                            lastChapterTitle = remoteChapterLabel,
                            lastChapterPath = progressSnapshot?.lastChapterPath.orEmpty(),
                        )
                    )
                }
            } finally {
                onItemProcessed?.invoke()
            }
        }
    }

    private fun fetchDetailCached(
        manga: SavedManga,
        detailCache: MutableMap<String, MangaDetail?>,
    ): MangaDetail? {
        val cacheKey = syncKey(manga)
        if (detailCache.containsKey(cacheKey)) {
            return detailCache[cacheKey]
        }
        val detail = runCatching {
            val provider = providerRegistry.get(manga.providerId)
            provider.fetchMangaDetail(manga.detailPath)
        }.getOrNull()
        detailCache[cacheKey] = detail
        return detail
    }

    private fun resolveMangaIdCached(
        accessToken: String,
        manga: SavedManga,
        cache: MutableMap<String, Long?>,
    ): Long? {
        val cacheKey = syncKey(manga)
        if (cache.containsKey(cacheKey)) {
            return cache[cacheKey]
        }
        val resolved = resolveMangaId(accessToken, manga)
        cache[cacheKey] = resolved
        return resolved
    }

    private fun beginSync() {
        uiState = buildState().copy(
            isSyncing = true,
            syncItemsProcessed = 0,
            syncItemsTotal = 0,
            syncEtaSeconds = null,
            errorMessage = "",
            lastMessage = "",
        )
    }

    private fun finishSync() {
        uiState = buildState().copy(isSyncing = false)
    }

    private fun createProgressUpdater(totalItems: Int): () -> Unit {
        val startedAtMs = System.currentTimeMillis()
        var processed = 0
        uiState = uiState.copy(
            syncItemsProcessed = 0,
            syncItemsTotal = totalItems.coerceAtLeast(0),
            syncEtaSeconds = null,
        )
        return {
            processed += 1
            val safeTotal = totalItems.coerceAtLeast(0)
            val etaSeconds = estimateRemainingSeconds(
                startedAtMs = startedAtMs,
                processed = processed,
                total = safeTotal,
            )
            uiState = uiState.copy(
                syncItemsProcessed = processed.coerceAtMost(safeTotal),
                syncItemsTotal = safeTotal,
                syncEtaSeconds = etaSeconds,
            )
        }
    }

    private fun estimateRemainingSeconds(
        startedAtMs: Long,
        processed: Int,
        total: Int,
    ): Int? {
        if (processed <= 0 || total <= processed) return 0
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)
        val averagePerItemMs = elapsedMs.toDouble() / processed.toDouble()
        val remainingItems = total - processed
        return kotlin.math.ceil((averagePerItemMs * remainingItems) / 1000.0).toInt().coerceAtLeast(1)
    }

    private fun buildRemoteProgressSnapshot(
        detailPath: String,
        detail: MangaDetail,
        remoteReadCount: Int,
    ): RemoteProgressSnapshot {
        if (remoteReadCount <= 0 || detail.chapters.isEmpty()) {
            return RemoteProgressSnapshot(emptyList(), "", "")
        }

        val targetValue = remoteReadCount.toDouble()
        val sortedChapters = detail.chapters.sortedBy { chapterValue(it) }
        val readChapters = sortedChapters.filter { chapterValue(it) <= targetValue }
        val lastChapter = readChapters.maxByOrNull { chapterValue(it) }
            ?: sortedChapters.firstOrNull { chapterValue(it) >= targetValue }

        return RemoteProgressSnapshot(
            readPaths = readChapters.map { buildChapterPath(detailPath, it) },
            lastChapterTitle = lastChapter?.let(strings::chapterLabelWithNumber).orEmpty(),
            lastChapterPath = lastChapter?.let { buildChapterPath(detailPath, it) }.orEmpty(),
        )
    }

    private fun shouldTrackLocally(entry: MalUserMangaEntry): Boolean {
        return entry.listStatus.numChaptersRead > 0 || entry.listStatus.status in setOf(
            "reading",
            "completed",
            "on_hold",
        )
    }

    private fun mergeStatus(
        localStatus: String,
        localReadCount: Int,
        mergedReadCount: Int,
        remoteStatus: String,
    ): String {
        if (remoteStatus == "completed" && mergedReadCount > localReadCount) {
            return "completed"
        }
        if (remoteStatus == "reading" && localStatus == "plan_to_read" && mergedReadCount > 0) {
            return "reading"
        }
        return localStatus
    }

    private data class RemoteProgressSnapshot(
        val readPaths: List<String>,
        val lastChapterTitle: String,
        val lastChapterPath: String,
    )

    private fun resolveMangaId(accessToken: String, manga: SavedManga): Long? {
        val candidates = buildMalSearchCandidates(
            providerId = manga.providerId,
            title = manga.title,
            alternativeTitles = emptyList(),
        )
        var bestRemote: com.paudinc.komastream.utils.MalMangaRecord? = null
        var bestScore = Int.MIN_VALUE
        for (candidate in candidates) {
            val results = api.searchManga(accessToken, clientId, candidate)
            if (results.isEmpty()) continue
            val scoredResults = results.map { remote ->
                remote to scoreMalCandidate(localTitle = manga.title, remote = remote)
            }.sortedByDescending { it.second }
            val topResult = scoredResults.firstOrNull()
            if (
                topResult != null &&
                topResult.second > bestScore &&
                isExactTitleMatch(manga.title, buildTitleCandidates(topResult.first.title, topResult.first.alternativeTitles))
            ) {
                bestRemote = topResult.first
                bestScore = topResult.second
            }
        }
        if (bestRemote != null && bestScore >= MIN_ACCEPTABLE_MAL_SCORE) {
            linkStore.setMangaId(manga.providerId, manga.detailPath, bestRemote.id)
            return bestRemote.id
        }
        return null
    }

    private fun scoreMalCandidate(
        localTitle: String,
        remote: com.paudinc.komastream.utils.MalMangaRecord,
    ): Int {
        val localCandidates = buildTitleCandidates(localTitle, emptyList())
        val remoteCandidates = buildTitleCandidates(remote.title, remote.alternativeTitles)

        val localNormalized = localCandidates.map(::normalizeTitle).filter { it.isNotBlank() }.distinct()
        val remoteNormalized = remoteCandidates.map(::normalizeTitle).filter { it.isNotBlank() }.distinct()
        val localSemantic = localNormalized.map(::normalizeSemanticTitle).distinct()
        val remoteSemantic = remoteNormalized.map(::normalizeSemanticTitle).distinct()

        var score = 0

        if (localNormalized.any { local -> remoteNormalized.any { remoteTitle -> local == remoteTitle } }) {
            score += 1_000
        }

        if (localNormalized.any { local ->
                remoteNormalized.any { remoteTitle -> local in remoteTitle || remoteTitle in local }
            }
        ) {
            score += 400
        }

        val localTokens = localNormalized.flatMap(::meaningfulTokens).toSet()
        val remoteTokens = remoteNormalized.flatMap(::meaningfulTokens).toSet()
        val sharedTokens = localTokens intersect remoteTokens
        score += sharedTokens.size * 35

        val localSemanticTokens = localSemantic.flatMap(::meaningfulTokens).toSet()
        val remoteSemanticTokens = remoteSemantic.flatMap(::meaningfulTokens).toSet()
        val sharedSemanticTokens = localSemanticTokens intersect remoteSemanticTokens
        score += sharedSemanticTokens.size * 90

        val localNumbers = localNormalized.flatMap(::numberTokens).toSet()
        val remoteNumbers = remoteNormalized.flatMap(::numberTokens).toSet()
        val sharedNumbers = localNumbers intersect remoteNumbers
        score += sharedNumbers.size * 120

        if ("fairy" in sharedTokens && "tail" in sharedTokens) {
            score += 150
        }

        if ("100" in sharedNumbers) {
            score += 180
        }

        val remotePenaltyTokens = setOf("gaiden", "special", "side", "city", "hero", "zero", "plus", "trail", "blue", "mistral", "christmas")
        score -= remoteTokens.count { it in remotePenaltyTokens } * 60

        val missingNumbers = localNumbers - remoteNumbers
        score -= missingNumbers.size * 220

        val requiredSemanticTokens = localSemanticTokens - setOf("fairy", "tail")
        val missingSemanticTokens = requiredSemanticTokens - remoteSemanticTokens
        score -= missingSemanticTokens.size * 120

        if (matchesAnyTitle(localTitle, remoteCandidates)) {
            score += 250
        }

        return score
    }

    private fun meaningfulTokens(value: String): List<String> {
        val ignored = setOf(
            "the", "a", "an", "of", "to", "la", "el", "los", "las", "de", "del", "y",
            "mission", "mision", "anos", "ano", "years", "year", "quest"
        )
        return value.split(" ")
            .map { it.trim() }
            .filter { it.length >= 2 && it !in ignored && it.any(Char::isLetter) }
    }

    private fun numberTokens(value: String): List<String> =
        Regex("\\d+").findAll(value).map { it.value }.toList()

    private fun syncKey(manga: SavedManga): String = "${manga.providerId}::${manga.detailPath}"

    private data class SyncEntry(
        val manga: SavedManga,
        val isReading: Boolean,
    )

    private fun resolveLocalManga(
        title: String,
        alternativeTitles: List<String>,
        libraryState: LibraryState,
        preferredProviderId: String? = null,
    ): SavedManga? {
        val remoteTitleCandidates = buildTitleCandidates(title, alternativeTitles)
        val localCandidates = (libraryState.reading + libraryState.favorites)
            .filter { preferredProviderId == null || it.providerId == preferredProviderId }
            .distinctBy { it.providerId to it.detailPath }
        localCandidates.firstOrNull { candidate ->
            matchesAnyTitle(candidate.title, remoteTitleCandidates)
        }?.let { return it }

        val providers = preferredProviderId
            ?.let { listOf(providerRegistry.get(it)) }
            ?: providerRegistry.all()
        for (provider in providers) {
            for (query in remoteTitleCandidates) {
                val searchResult = runCatching {
                    provider.searchCatalog(
                        query = query,
                        categoryIds = emptyList(),
                        sortBy = "",
                        broadcastStatus = "",
                        onlyFavorites = false,
                        skip = 0,
                        take = 10,
                    )
                }.getOrNull() ?: continue
                val candidate = searchResult.items.firstOrNull { item ->
                    matchesAnyTitle(item.title, remoteTitleCandidates)
                } ?: continue
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

    private fun buildTitleCandidates(
        title: String,
        alternativeTitles: List<String>,
    ): List<String> {
        return buildList {
            add(title)
            addAll(alternativeTitles)
            add(normalizeCandidateTitle(title))
            alternativeTitles.forEach { add(normalizeCandidateTitle(it)) }
            add(buildSemanticSearchQuery(title))
            add(buildOrderedSemanticSearchQuery(title))
            alternativeTitles.forEach { add(buildSemanticSearchQuery(it)) }
            alternativeTitles.forEach { add(buildOrderedSemanticSearchQuery(it)) }
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun buildMalSearchCandidates(
        providerId: String,
        title: String,
        alternativeTitles: List<String>,
    ): List<String> {
        val allCandidates = buildTitleCandidates(title, alternativeTitles)
        if (!providerId.endsWith("-es")) return allCandidates

        val semanticCandidates = buildList {
            add(buildSemanticSearchQuery(title))
            add(buildOrderedSemanticSearchQuery(title))
            alternativeTitles.forEach { candidate ->
                add(buildSemanticSearchQuery(candidate))
                add(buildOrderedSemanticSearchQuery(candidate))
            }
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        return if (semanticCandidates.isNotEmpty()) semanticCandidates else allCandidates
    }

    private fun shouldSkipMalSearchForLocalizedSpanishTitle(
        providerId: String,
        title: String,
    ): Boolean {
        if (!providerId.endsWith("-es")) return false
        val normalized = foldDiacritics(title).lowercase()
        val spanishMarkers = listOf(
            " la ", " el ", " los ", " las ", " de ", " del ",
            " mision ", " capitulo ", " anos ", " ano ", " temporada ", " especial "
        )
        val padded = " $normalized "
        return spanishMarkers.any { it in padded }
    }

    private fun matchesAnyTitle(localTitle: String, remoteTitleCandidates: List<String>): Boolean {
        return remoteTitleCandidates.any { remoteTitle ->
            matchesTitle(localTitle, remoteTitle, remoteTitleCandidates)
        }
    }

    private fun isExactTitleMatch(localTitle: String, remoteTitleCandidates: List<String>): Boolean {
        val normalizedLocal = normalizeTitle(localTitle)
        if (normalizedLocal.isBlank()) return false
        return remoteTitleCandidates.any { candidate ->
            normalizeTitle(candidate) == normalizedLocal
        }
    }

    private fun matchesTitle(localTitle: String, remoteTitle: String, alternativeTitles: List<String>): Boolean {
        val normalizedLocal = normalizeTitle(localTitle)
        val normalizedRemote = normalizeTitle(remoteTitle)
        if (normalizedLocal.isBlank() || normalizedRemote.isBlank()) return false
        if (normalizedLocal == normalizedRemote) return true
        if (normalizedLocal in normalizedRemote || normalizedRemote in normalizedLocal) return true
        return alternativeTitles.any { candidate ->
            val normalizedCandidate = normalizeTitle(candidate)
            normalizedCandidate == normalizedLocal ||
                (normalizedCandidate.isNotBlank() && (
                    normalizedLocal in normalizedCandidate || normalizedCandidate in normalizedLocal
                ))
        }
    }

    private fun normalizeTitle(value: String): String =
        foldDiacritics(value)
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun normalizeSemanticTitle(value: String): String {
        val normalized = normalizeTitle(value)
        if (normalized.isBlank()) return ""
        return normalized.split(" ")
            .mapNotNull(::mapSemanticToken)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun buildSemanticSearchQuery(value: String): String {
        val semantic = normalizeSemanticTitle(value)
        if (semantic.isBlank()) return ""
        return semantic
            .split(" ")
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
    }

    private fun buildOrderedSemanticSearchQuery(value: String): String {
        val tokens = normalizeSemanticTitle(value)
            .split(" ")
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""

        val franchiseTokens = mutableListOf<String>()
        val numberTokens = mutableListOf<String>()
        val yearTokens = mutableListOf<String>()
        val questTokens = mutableListOf<String>()
        val trailingTokens = mutableListOf<String>()

        tokens.forEach { token ->
            when {
                token.all(Char::isDigit) -> numberTokens += token
                token == "year" || token == "years" -> yearTokens += token
                token == "quest" -> questTokens += token
                else -> franchiseTokens += token
            }
        }

        val ordered = franchiseTokens + numberTokens + yearTokens + questTokens + trailingTokens
        return ordered.distinct().joinToString(" ").trim()
    }

    private fun foldDiacritics(value: String): String {
        if (value.isBlank()) return ""
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{M}+"), "")
    }

    private fun mapSemanticToken(token: String): String? {
        return when (token) {
            "", "la", "el", "los", "las", "de", "del", "y" -> null
            "mision" -> "quest"
            "anos" -> "years"
            "ano" -> "year"
            "capitulo" -> "chapter"
            else -> token
        }
    }

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
