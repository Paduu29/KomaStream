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
import com.paudinc.komastream.data.model.MangaSummary
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
import com.paudinc.komastream.utils.normalizeMalChapterNumber
import com.paudinc.komastream.utils.parseChapterInput
import com.paudinc.komastream.utils.resolveMalReadCountForReadChapters
import com.paudinc.komastream.utils.resolveMalReadCountForSelection
import com.paudinc.komastream.utils.sameMangaPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.max

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
        private const val SYNC_FROM_REMOTE_BASE_UNITS = 3
        private const val SYNC_TO_REMOTE_BASE_UNITS = 3
    }

    private val api = MyAnimeListApi()
    private val sessionStore = MyAnimeListSessionStore(context)
    private val linkStore = MyAnimeListLinkStore(context)
    private val clientId: String = BuildConfig.MAL_CLIENT_ID.trim()
    private var syncStartedAtMs: Long = 0L
    private var pendingSyncContinuation: (() -> Unit)? = null

    var uiState by mutableStateOf(buildState())
        private set

    fun refreshState() {
        uiState = buildState()
    }

    fun setMangaMalId(providerId: String, detailPath: String, malMangaId: Long?) {
        scope.launch {
            withContext(Dispatchers.IO) {
                libraryStore.setMangaMalId(providerId, detailPath, malMangaId)
                if (malMangaId == null) {
                    linkStore.removeMangaId(providerId, detailPath)
                } else {
                    linkStore.setMangaId(providerId, detailPath, malMangaId)
                }
            }
            onLocalLibraryChanged?.invoke()
            refreshState()
        }
    }

    fun getMangaMalId(providerId: String, detailPath: String): Long? {
        return libraryStore.getMangaMalId(providerId, detailPath)
            ?: linkStore.getMangaId(providerId, detailPath)
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

    fun syncFromRemote(providerId: String) {
        val current = sessionStore.read()
        if (!isConnected(current)) {
            updateMessage("Connect to MyAnimeList first", error = true)
            return
        }
        val currentProvider = providerRegistry.get(providerId)
        scope.launch {
            beginSync(
                initialTotalUnits = SYNC_FROM_REMOTE_BASE_UNITS,
                stageMessage = "Refreshing MyAnimeList session",
            )
            val pendingImports = mutableListOf<MyAnimeListPendingImport>()
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = refreshTokensIfNeeded(current)
                    advanceSyncProgress(stageMessage = "Fetching your MyAnimeList library")
                    val list = api.fetchUserMangaList(refreshed.accessToken, clientId)
                    advanceSyncProgress()
                    addSyncTotal(list.size)
                    val detailCache = mutableMapOf<String, MangaDetail?>()
                    val currentState = libraryStore.read(filterBySelectedProvider = false)
                    updateSyncStage("Matching MyAnimeList entries to your library")
                    mergeRemoteEntriesIntoLocal(
                        remoteEntries = list,
                        providerIdFilter = currentProvider.id,
                        provider = currentProvider,
                        currentState = currentState,
                        detailCache = detailCache,
                        onItemStarted = { entry ->
                            updateSyncStage(buildSyncItemMessage("Matching", entry.manga.title))
                        },
                        onItemProcessed = { advanceSyncProgress() },
                        onPendingMatch = { pendingImports += it },
                    )
                }
                uiState = uiState.copy(pendingImports = pendingImports)
            }.onSuccess {
                updateSyncStage("Finalizing sync")
                advanceSyncProgress()
                onLocalLibraryChanged?.invoke()
                updateMessage(
                    if (uiState.pendingImports.isNotEmpty()) {
                        "Synced from MyAnimeList. Review the pending matches to finish importing."
                    } else {
                        "Synced from MyAnimeList"
                    }
                )
            }.onFailure { throwable ->
                updateMessage(throwable.message ?: "Could not sync from MyAnimeList", error = true)
            }
            finishSync()
        }
    }

    fun syncLocalLibraryToRemote(providerId: String, onCompleted: (() -> Unit)? = null) {
        val current = sessionStore.read()
        if (!isConnected(current)) {
            updateMessage("Connect to MyAnimeList first", error = true)
            return
        }
        val currentProvider = providerRegistry.get(providerId)
        scope.launch {
            var deferFinishToFollowUpSync = false
            beginSync(
                initialTotalUnits = SYNC_TO_REMOTE_BASE_UNITS,
                stageMessage = "Refreshing MyAnimeList session",
            )
            val pendingImports = mutableListOf<MyAnimeListPendingImport>()
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = refreshTokensIfNeeded(current)
                    advanceSyncProgress(stageMessage = "Fetching your MyAnimeList library")
                    val remoteEntries = api.fetchUserMangaList(refreshed.accessToken, clientId)
                    advanceSyncProgress()
                    val detailCache = mutableMapOf<String, MangaDetail?>()
                    val mangaIdCache = mutableMapOf<String, Long?>()
                    val currentProviderState = libraryStore.read(filterBySelectedProvider = false)
                    val preSyncState = currentProviderState
                    val preSyncReadChapters = libraryStore.readChaptersForProvider(providerId)
                    val remoteEntriesById = remoteEntries.associateBy { it.manga.id }
                    val state = preSyncState
                    val entriesByKey = linkedMapOf<String, SyncEntry>()

                    state.reading
                        .filter { it.providerId == providerId }
                        .forEach { manga ->
                            val normalized = manga.copy(
                                detailPath = manga.detailPath.lowercase().trim()
                            )
                            entriesByKey[syncKey(normalized)] = SyncEntry(
                                manga = normalized,
                                isReading = true
                            )
                        }

                    state.favorites
                        .filter { it.providerId == providerId }
                        .forEach { manga ->
                            val normalized = manga.copy(
                                detailPath = manga.detailPath.lowercase().trim()
                            )
                            val key = syncKey(normalized)

                            val existing = entriesByKey[key]
                            if (existing == null || !existing.isReading) {
                                entriesByKey[key] = SyncEntry(
                                    manga = normalized,
                                    isReading = false
                                )
                            }
                        }
                    addSyncTotal(entriesByKey.size)
                    advanceSyncProgress(stageMessage = "Preparing local library entries")

                    entriesByKey.values.forEach { entry ->
                        updateSyncStage(buildSyncItemMessage("Uploading", entry.manga.title))
                        val mangaId = resolveMangaIdCached(
                            accessToken = refreshed.accessToken,
                            manga = entry.manga,
                            cache = mangaIdCache,
                        ) ?: run {
                            pendingImports += buildPendingMangaImportForLocal(entry, refreshed.accessToken)
                            advanceSyncProgress()
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
                        advanceSyncProgress()
                    }
                }
                uiState = uiState.copy(pendingImports = pendingImports)
            }.onSuccess {
                updateSyncStage("Finalizing sync")
                advanceSyncProgress()
                onLocalLibraryChanged?.invoke()
                updateMessage("Synced local library to MyAnimeList")
                if (pendingImports.isEmpty()) {
                    deferFinishToFollowUpSync = onCompleted != null
                    onCompleted?.invoke()
                } else {
                    pendingSyncContinuation = onCompleted
                }
            }.onFailure { throwable ->
                updateMessage(throwable.message ?: "Could not sync to MyAnimeList", error = true)
            }
            if (!deferFinishToFollowUpSync) {
                finishSync()
            }
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
        val count = if (read) resolveMalReadCountForSelection(chapters) else 0
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
                        val localReadCount = numChaptersRead?.coerceAtLeast(0) ?: 0
                        val mergedReadCount = max(
                            localReadCount,
                            remoteEntry?.listStatus?.numChaptersRead?.coerceAtLeast(0) ?: 0,
                        )
                        val mergedStatus = mergeStatus(
                            localStatus = status,
                            localReadCount = localReadCount,
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
        val remoteReadCount = remoteEntry?.listStatus?.numChaptersRead?.coerceAtLeast(0) ?: 0
        val readCount = max(localReadCount, remoteReadCount)
        val totalChapterCount = resolveMalReadCountForSelection(detail.chapters)
        val localStatus = when {
            isReading && totalChapterCount > 0 && localReadCount >= totalChapterCount -> "completed"
            isReading -> "reading"
            else -> "plan_to_read"
        }
        val status = mergeStatus(
            localStatus = localStatus,
            localReadCount = localReadCount,
            mergedReadCount = readCount,
            remoteStatus = remoteEntry?.listStatus?.status.orEmpty(),
        )
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
        detail: MangaDetail,
        readChapters: Set<String>,
    ): Int {
        manga.lastReadChapterNumber?.let { return it.coerceAtLeast(0) }
        val explicitReadCount = resolveMalReadCountForReadChapters(
            providerId = manga.providerId,
            detailPath = manga.detailPath,
            chapters = detail.chapters,
            readChapters = readChapters,
        )
        if (explicitReadCount > 0) return explicitReadCount

        val progressPath = manga.lastChapterPath.trim()

        val progressChapter = if (progressPath.isNotBlank()) {
            detail.chapters.firstOrNull { chapter ->
                canonicalChapterKey(manga.providerId, buildChapterPath(manga.detailPath, chapter)) ==
                        canonicalChapterKey(manga.providerId, progressPath)
            }
        } else null

        val progressValue = progressChapter?.let(::chapterValue)
            ?: parseChapterInput(manga.lastChapterTitle)
        val normalized = progressValue
            ?.let { normalizeMalChapterNumber(it) }
            ?: return 0

        val completedPointedChapter = progressChapter != null &&
                isCompletedChapterProgress(
                    providerId = manga.providerId,
                    chapterPath = progressPath,
                )

        if (completedPointedChapter) {
            return normalized.coerceAtLeast(1)
        }
        return if (isWholeChapterNumber(progressValue)) {
            (normalized - 1).coerceAtLeast(1)
        } else {
            normalized.coerceAtLeast(1)
        }
    }

    private fun isWholeChapterNumber(value: Double): Boolean =
        kotlin.math.abs(value - value.toInt().toDouble()) < 0.0001

    fun applyPendingMangaImports(providerId: String, selections: Map<String, String>) {
        if (selections.isEmpty()) return
        val pendingImports = uiState.pendingImports
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = refreshTokensIfNeeded(sessionStore.read())
                    val detailCache = mutableMapOf<String, MangaDetail?>()
                    val currentState = libraryStore.read(filterBySelectedProvider = false)
                    val pendingByKey = pendingImports.associateBy { it.pendingKey }
                    selections.forEach { (pendingKey, candidateKey) ->
                        val pending = pendingByKey[pendingKey] ?: return@forEach
                        val candidate = pending.candidates.firstOrNull { it.key == candidateKey } ?: return@forEach
                        when (pending.source) {
                            MyAnimeListPendingImportSource.FROM_REMOTE -> {
                                val detail = fetchDetailCached(
                                    SavedManga(
                                        providerId = candidate.providerId,
                                        title = candidate.title,
                                        detailPath = candidate.detailPath,
                                        coverUrl = candidate.coverUrl,
                                    ),
                                    detailCache,
                                ) ?: return@forEach
                                applyRemoteEntry(
                                    entry = MalUserMangaEntry(
                                        manga = com.paudinc.komastream.utils.MalMangaRecord(
                                            id = pending.malMangaId ?: return@forEach,
                                            title = pending.title,
                                            coverUrl = candidate.coverUrl,
                                            numChapters = detail.chapters.size,
                                            status = pending.status,
                                            alternativeTitles = pending.alternativeTitles,
                                        ),
                                        listStatus = com.paudinc.komastream.utils.MalListStatus(
                                            status = pending.status,
                                            numChaptersRead = pending.numChaptersRead,
                                        ),
                                    ),
                                    local = SavedManga(
                                        providerId = candidate.providerId,
                                        title = candidate.title,
                                        detailPath = candidate.detailPath,
                                        coverUrl = candidate.coverUrl,
                                    ),
                                    detail = detail,
                                    detailCache = detailCache,
                                )
                            }
                            MyAnimeListPendingImportSource.TO_REMOTE -> {
                                val malId = candidate.malMangaId ?: return@forEach
                                val local = SavedManga(
                                    providerId = pending.localProviderId.ifBlank { providerId },
                                    title = pending.title,
                                    detailPath = pending.localDetailPath,
                                    coverUrl = candidate.coverUrl,
                                )
                                linkStore.setMangaId(local.providerId, local.detailPath, malId)
                                val existingLocal = (currentState.reading + currentState.favorites).firstOrNull {
                                    it.providerId == local.providerId && sameMangaPath(local.providerId, it.detailPath, local.detailPath)
                                }
                                val updatedLocal = (existingLocal ?: local).copy(malMangaId = malId)
                                if (existingLocal != null && currentState.favorites.any {
                                        it.providerId == local.providerId && sameMangaPath(local.providerId, it.detailPath, local.detailPath)
                                    }) {
                                    libraryStore.upsertFavorite(updatedLocal)
                                } else {
                                    libraryStore.upsertReading(updatedLocal)
                                }
                                api.updateMangaStatus(
                                    accessToken = refreshed.accessToken,
                                    clientId = clientId,
                                    mangaId = malId,
                                    status = pending.status.ifBlank { "plan_to_read" },
                                    numChaptersRead = pending.numChaptersRead.coerceAtLeast(0),
                                )
                            }
                        }
                    }
                }
            }.onSuccess {
                uiState = uiState.copy(
                    pendingImports = uiState.pendingImports.filterNot { it.pendingKey in selections.keys },
                )
                onLocalLibraryChanged?.invoke()
                updateMessage("Imported selected MyAnimeList matches")
                if (uiState.pendingImports.isEmpty()) {
                    pendingSyncContinuation?.invoke()
                    pendingSyncContinuation = null
                }
            }.onFailure { throwable ->
                updateMessage(throwable.message ?: "Could not import selected MyAnimeList matches", error = true)
            }
        }
    }

    fun clearPendingMangaImports() {
        uiState = uiState.copy(
            pendingImports = emptyList(),
            lastMessage = "",
            errorMessage = "",
        )
        pendingSyncContinuation = null
    }

    private fun mergeRemoteEntriesIntoLocal(
        remoteEntries: List<MalUserMangaEntry>,
        providerIdFilter: String? = null,
        provider: com.paudinc.komastream.provider.MangaProvider,
        currentState: LibraryState,
        addToFavorites: Boolean = false,
        detailCache: MutableMap<String, MangaDetail?>,
        onItemStarted: ((MalUserMangaEntry) -> Unit)? = null,
        onItemProcessed: (() -> Unit)? = null,
        onPendingMatch: ((com.paudinc.komastream.ui.viewmodel.MyAnimeListPendingImport) -> Unit)? = null,
    ) {
        remoteEntries.forEach { entry ->
            try {
                onItemStarted?.invoke(entry)
                val linkedLocal = (currentState.reading + currentState.favorites)
                    .asSequence()
                    .filter { providerIdFilter == null || it.providerId == providerIdFilter }
                    .firstOrNull { it.malMangaId == entry.manga.id }
                if (linkedLocal != null) {
                    val detail = fetchDetailCached(linkedLocal, detailCache)
                    applyRemoteEntry(
                        entry = entry,
                        local = linkedLocal,
                        detail = detail,
                        detailCache = detailCache,
                        addToFavorites = addToFavorites,
                    )
                    return@forEach
                }
                val local = resolveLocalManga(
                    title = entry.manga.title,
                    alternativeTitles = entry.manga.alternativeTitles,
                    libraryState = currentState,
                    preferredProviderId = providerIdFilter,
                    provider = provider,
                )
                if (local == null) {
                    onPendingMatch?.invoke(buildPendingImport(entry, provider, detailCache))
                    return@forEach
                }

                val detail = fetchDetailCached(local, detailCache)
                applyRemoteEntry(
                    entry = entry,
                    local = local,
                    detail = detail,
                    detailCache = detailCache,
                    addToFavorites = addToFavorites,
                )
            } finally {
                onItemProcessed?.invoke()
            }
        }
    }

    private fun applyRemoteEntry(
        entry: MalUserMangaEntry,
        local: SavedManga,
        detail: MangaDetail?,
        detailCache: MutableMap<String, MangaDetail?>,
        addToFavorites: Boolean = false,
    ) {
        val currentState = libraryStore.read(filterBySelectedProvider = false)
        val existingEntry = (currentState.reading + currentState.favorites).firstOrNull {
            it.providerId == local.providerId && sameMangaPath(local.providerId, it.detailPath, local.detailPath)
        }
        val localReadCount = existingEntry?.lastReadChapterNumber
            ?: detail?.let {
                resolveMalReadCountForReadChapters(
                    providerId = local.providerId,
                    detailPath = local.detailPath,
                    chapters = it.chapters,
                    readChapters = libraryStore.readChaptersForProvider(local.providerId),
                )
            } ?: 0
        val remoteReadCount = entry.listStatus.numChaptersRead.coerceAtLeast(0)
        val mergedReadCount = max(localReadCount, remoteReadCount)
        val progressSnapshot = detail?.let {
            buildRemoteProgressSnapshot(
                detailPath = local.detailPath,
                detail = it,
                remoteReadCount = mergedReadCount,
            )
        }
        val remoteChapterLabel = mergedReadCount.takeIf { it > 0 }?.toString()
            ?: progressSnapshot?.lastChapterTitle.orEmpty()
        val imported = local.copy(
            malMangaId = entry.manga.id,
            title = local.title.ifBlank { detail?.title.orEmpty() }.ifBlank { entry.manga.title },
            coverUrl = local.coverUrl.ifBlank { detail?.coverUrl.orEmpty() }.ifBlank { entry.manga.coverUrl },
            lastChapterTitle = remoteChapterLabel,
            lastChapterPath = progressSnapshot?.lastChapterPath.orEmpty(),
            lastReadChapterNumber = mergedReadCount.takeIf { it > 0 },
        )
        linkStore.setMangaId(imported.providerId, imported.detailPath, entry.manga.id)
        if (progressSnapshot != null && progressSnapshot.readPaths.isNotEmpty()) {
            libraryStore.setChaptersRead(imported.providerId, progressSnapshot.readPaths, true)
        }
        if (addToFavorites || entry.listStatus.status == "plan_to_read") {
            libraryStore.upsertFavorite(imported)
        } else {
            libraryStore.upsertReading(imported)
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

    private fun beginSync(
        initialTotalUnits: Int,
        stageMessage: String,
    ) {
        syncStartedAtMs = System.currentTimeMillis()
        uiState = buildState().copy(
            isSyncing = true,
            syncStageMessage = stageMessage,
            syncItemsProcessed = 0,
            syncItemsTotal = initialTotalUnits.coerceAtLeast(1),
            syncEtaSeconds = null,
            errorMessage = "",
            lastMessage = "",
            pendingImports = emptyList(),
        )
    }

    private fun finishSync() {
        syncStartedAtMs = 0L
        uiState = uiState.copy(
            isSyncing = false,
            syncStageMessage = "",
            syncItemsProcessed = 0,
            syncItemsTotal = 0,
            syncEtaSeconds = null,
        )
    }

    private fun addSyncTotal(delta: Int) {
        if (delta <= 0) return
        val total = (uiState.syncItemsTotal + delta).coerceAtLeast(1)
        uiState = uiState.copy(
            syncItemsTotal = total,
            syncEtaSeconds = estimateRemainingSeconds(
                startedAtMs = syncStartedAtMs,
                processed = uiState.syncItemsProcessed,
                total = total,
            ),
        )
    }

    private fun advanceSyncProgress(
        units: Int = 1,
        stageMessage: String? = null,
    ) {
        val total = uiState.syncItemsTotal.coerceAtLeast(1)
        val processed = (uiState.syncItemsProcessed + units).coerceIn(0, total)
        uiState = uiState.copy(
            syncStageMessage = stageMessage ?: uiState.syncStageMessage,
            syncItemsProcessed = processed,
            syncItemsTotal = total,
            syncEtaSeconds = estimateRemainingSeconds(
                startedAtMs = syncStartedAtMs,
                processed = processed,
                total = total,
            ),
        )
    }

    private fun updateSyncStage(message: String) {
        uiState = uiState.copy(
            syncStageMessage = message,
        )
    }

    private fun buildSyncItemMessage(action: String, title: String): String {
        val trimmedTitle = title.trim()
        return if (trimmedTitle.isBlank()) action else "$action $trimmedTitle"
    }

    private fun isCompletedChapterProgress(
        providerId: String,
        chapterPath: String,
    ): Boolean {
        val pageCount = libraryStore.getChapterPageCount(providerId, chapterPath)
        if (pageCount <= 0) return false
        val pageIndex = libraryStore.getChapterProgress(providerId, chapterPath)
        return pageIndex >= pageCount - 1
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
        getMangaMalId(manga.providerId, manga.detailPath)?.let { storedMangaId ->
            linkStore.setMangaId(manga.providerId, manga.detailPath, storedMangaId)
            return storedMangaId
        }
        val matches = searchMalMatches(accessToken, manga)
        val exactMatch = matches.firstOrNull { remote ->
            isExactTitleMatch(manga.title, buildTitleCandidates(remote.title, remote.alternativeTitles))
        } ?: return null
        linkStore.setMangaId(manga.providerId, manga.detailPath, exactMatch.id)
        return exactMatch.id
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

    private fun syncKey(manga: SavedManga): String =
        "${manga.providerId}::${manga.detailPath.lowercase().trim()}"

    private data class SyncEntry(
        val manga: SavedManga,
        val isReading: Boolean,
    )

    private fun resolveLocalManga(
        title: String,
        alternativeTitles: List<String>,
        libraryState: LibraryState,
        preferredProviderId: String? = null,
        provider: com.paudinc.komastream.provider.MangaProvider,
    ): SavedManga? {
        val remoteTitleCandidates = buildTitleCandidates(title, alternativeTitles)
        val localCandidates = (libraryState.reading + libraryState.favorites)
            .distinctBy { it.providerId to it.detailPath }
        localCandidates.firstOrNull { candidate ->
            matchesAnyTitle(candidate.title, remoteTitleCandidates)
        }?.let { return it }

        return searchProviderMatches(provider, remoteTitleCandidates)
            .firstOrNull { candidate ->
                matchesAnyTitle(candidate.title, remoteTitleCandidates)
            }
            ?.let {
                SavedManga(
                    providerId = it.providerId,
                    title = it.title,
                    detailPath = it.detailPath,
                    coverUrl = it.coverUrl,
                )
            }
    }

    private fun buildPendingImport(
        entry: MalUserMangaEntry,
        provider: com.paudinc.komastream.provider.MangaProvider,
        detailCache: MutableMap<String, MangaDetail?>,
    ): com.paudinc.komastream.ui.viewmodel.MyAnimeListPendingImport {
        val candidates = searchProviderMatches(provider, buildTitleCandidates(entry.manga.title, entry.manga.alternativeTitles))
            .map { manga ->
                val detail = fetchDetailCached(
                    SavedManga(
                        providerId = manga.providerId,
                        title = manga.title,
                        detailPath = manga.detailPath,
                        coverUrl = manga.coverUrl,
                    ),
                    detailCache,
                )
                MyAnimeListMatchCandidate(
                    key = "local:${manga.providerId}:${manga.detailPath}",
                    title = manga.title,
                    displayTitle = detail?.title?.ifBlank { manga.title } ?: manga.title,
                    coverUrl = manga.coverUrl,
                    providerId = manga.providerId,
                    detailPath = manga.detailPath,
                    status = manga.status,
                    chaptersCount = manga.chaptersCount,
                )
            }
        return com.paudinc.komastream.ui.viewmodel.MyAnimeListPendingImport(
            source = MyAnimeListPendingImportSource.FROM_REMOTE,
            pendingKey = "remote:${entry.manga.id}",
            malMangaId = entry.manga.id,
            title = entry.manga.title,
            status = entry.listStatus.status,
            numChaptersRead = entry.listStatus.numChaptersRead.coerceAtLeast(0),
            alternativeTitles = entry.manga.alternativeTitles,
            candidates = candidates,
        )
    }

    private fun buildPendingMangaImportForLocal(
        entry: SyncEntry,
        accessToken: String,
    ): MyAnimeListPendingImport {
        val manga = entry.manga
        val searchResults = searchMalMatches(accessToken = accessToken, manga = manga)
        val candidates = searchResults
            .map { remote ->
                MyAnimeListMatchCandidate(
                    key = "mal:${remote.id}",
                    title = remote.title,
                    displayTitle = remote.title,
                    coverUrl = remote.coverUrl,
                    malMangaId = remote.id,
                    status = remote.status,
                )
            }
        return MyAnimeListPendingImport(
            source = MyAnimeListPendingImportSource.TO_REMOTE,
            pendingKey = "local:${manga.providerId}:${manga.detailPath}",
            localProviderId = manga.providerId,
            localDetailPath = manga.detailPath,
            title = manga.title,
            status = if (entry.isReading) "reading" else "plan_to_read",
            numChaptersRead = if (entry.isReading) (manga.lastReadChapterNumber ?: 0).coerceAtLeast(0) else 0,
            alternativeTitles = emptyList(),
            candidates = candidates,
        )
    }

    private fun searchMalMatches(
        accessToken: String,
        manga: SavedManga,
    ): List<com.paudinc.komastream.utils.MalMangaRecord> {
        val candidates = buildMalSearchCandidates(
            providerId = manga.providerId,
            title = manga.title,
            alternativeTitles = emptyList(),
        )
        val results = mutableListOf<com.paudinc.komastream.utils.MalMangaRecord>()
        candidates.forEach { candidate ->
            val searchResults = runCatching {
                api.searchManga(accessToken, clientId, candidate)
            }.getOrNull().orEmpty()
            results += searchResults
        }
        return results.distinctBy { it.id }
    }

    private fun searchProviderMatches(
        provider: com.paudinc.komastream.provider.MangaProvider,
        titleCandidates: List<String>,
    ): List<MangaSummary> {
        val results = mutableListOf<MangaSummary>()
        titleCandidates.forEach { query ->
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
            }.getOrNull() ?: return@forEach
            results += searchResult.items
        }
        return results
            .distinctBy { it.providerId to it.detailPath }
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
        return buildTitleCandidates(title, alternativeTitles)
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
        val semanticLocal = normalizeSemanticTitle(localTitle)
        val semanticRemote = normalizeSemanticTitle(remoteTitle)
        if (normalizedLocal.isBlank() || normalizedRemote.isBlank()) return false
        if (normalizedLocal == normalizedRemote) return true
        if (normalizedLocal in normalizedRemote || normalizedRemote in normalizedLocal) return true
        if (semanticLocal.isNotBlank() && semanticRemote.isNotBlank()) {
            if (semanticLocal == semanticRemote) return true
            if (semanticLocal in semanticRemote || semanticRemote in semanticLocal) return true
        }
        return alternativeTitles.any { candidate ->
            val normalizedCandidate = normalizeTitle(candidate)
            val semanticCandidate = normalizeSemanticTitle(candidate)
            normalizedCandidate == normalizedLocal ||
                (normalizedCandidate.isNotBlank() && (
                    normalizedLocal in normalizedCandidate || normalizedCandidate in normalizedLocal
                )) ||
                (semanticCandidate.isNotBlank() && semanticLocal.isNotBlank() && (
                    semanticCandidate == semanticLocal ||
                        semanticLocal in semanticCandidate || semanticCandidate in semanticLocal
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
