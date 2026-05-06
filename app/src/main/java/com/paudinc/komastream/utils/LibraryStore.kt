package com.paudinc.komastream.utils

import android.content.Context
import com.paudinc.komastream.data.local.AppSettingsEntity
import com.paudinc.komastream.data.local.ChapterPageCountEntity
import com.paudinc.komastream.data.local.ChapterProgressEntity
import com.paudinc.komastream.data.local.FavoriteMangaEntity
import com.paudinc.komastream.data.local.MangaDetailCacheEntity
import com.paudinc.komastream.data.local.LibraryDatabase
import com.paudinc.komastream.data.local.ReadChapterEntity
import com.paudinc.komastream.data.local.ReadingMangaEntity
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.data.model.FavoriteMangaStatus
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.MangaDetail
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.data.repository.LibraryBackupPayloadCodec
import com.paudinc.komastream.data.repository.LibraryJsonCodec
import com.paudinc.komastream.data.repository.MangaDetailCacheCodec
import com.paudinc.komastream.utils.toProgressChapterNumber
import org.json.JSONArray
import org.json.JSONObject

data class CachedMangaDetailSnapshot(
    val detail: MangaDetail,
    val updatedAt: Long,
)

class LibraryStore(context: Context) {
    private val legacyPrefs = context.getSharedPreferences("manga_library", Context.MODE_PRIVATE)
    private val database = LibraryDatabase.getInstance(context)
    private val dao = database.libraryDao()
    private val defaultProviderId = createDefaultProviderRegistry().defaultProvider().id
    private val jsonCodec = LibraryJsonCodec(defaultProviderId = defaultProviderId)
    private val backupPayloadCodec = LibraryBackupPayloadCodec()
    private val mangaDetailCacheCodec = MangaDetailCacheCodec()
    private val initLock = Any()

    @Volatile
    private var initialized = false

    fun read(filterBySelectedProvider: Boolean = true): LibraryState {
        ensureInitialized()
        val settings = readSettings()
        val parsedFavorites = dao.readFavorites().map { it.toSavedManga() }
        val parsedReading = dao.readReading().map { it.toSavedManga() }
        val (allFavorites, allReading) = canonicalizeSavedEntries(parsedFavorites, parsedReading)
        val selectedProviderId = settings.selectedProviderId
        return LibraryState(
            favorites = if (filterBySelectedProvider) allFavorites.filter { it.providerId == selectedProviderId } else allFavorites,
            reading = if (filterBySelectedProvider) allReading.filter { it.providerId == selectedProviderId } else allReading,
            readChapters = dao.readChaptersForProvider(selectedProviderId).map { it.chapterPath }.toSet(),
            useDarkTheme = settings.useDarkTheme,
            autoJumpToUnread = settings.autoJumpToUnread,
            mangaBallAdultContentEnabled = settings.mangaBallAdultContentEnabled,
            preferredChapterLanguage = AppLanguage.fromStored(settings.preferredChapterLanguage).takeIf {
                it != AppLanguage.MULTI
            } ?: AppLanguage.EN,
            selectedProviderId = selectedProviderId,
            appLanguage = AppLanguage.fromStored(settings.appLanguage),
        )
    }

    fun toggleFavorite(manga: SavedManga) {
        ensureInitialized()
        val current = dao.readFavorites()
        val existing = current.firstOrNull { sameStoredManga(it.toSavedManga(), manga) }
        if (existing != null) {
            dao.deleteFavorite(existing.providerId, existing.detailPath)
            return
        }
        dao.upsertFavorite(
            manga.toFavoriteEntity(orderIndex = nextOrderIndex(current.map { it.orderIndex }))
        )
    }

    fun upsertFavorite(manga: SavedManga) {
        ensureInitialized()
        val current = dao.readFavorites()
        val existingFavorite = current.firstOrNull { sameStoredManga(it.toSavedManga(), manga) }
        val existingReading = dao.readReading().firstOrNull { sameStoredManga(it.toSavedManga(), manga) }?.toSavedManga()
        val resolvedStatus = when {
            manga.favoriteStatus != FavoriteMangaStatus.COMPLETED -> manga.favoriteStatus
            existingFavorite != null -> FavoriteMangaStatus.fromStored(existingFavorite.favoriteStatus)
            existingReading != null -> existingReading.favoriteStatus
            else -> manga.favoriteStatus
        }
        val mergedFavorite = manga.copy(
            favoriteStatus = resolvedStatus,
            title = manga.title.ifBlank { existingFavorite?.title ?: existingReading?.title.orEmpty() },
            coverUrl = manga.coverUrl.ifBlank { existingFavorite?.coverUrl ?: existingReading?.coverUrl.orEmpty() },
            lastChapterTitle = manga.lastChapterTitle.ifBlank { existingFavorite?.lastChapterTitle ?: existingReading?.lastChapterTitle.orEmpty() },
            lastChapterPath = manga.lastChapterPath.ifBlank { existingFavorite?.lastChapterPath ?: existingReading?.lastChapterPath.orEmpty() },
            lastProgressChapterNumber = manga.lastProgressChapterNumber ?: existingFavorite?.lastProgressChapterNumber ?: existingReading?.lastProgressChapterNumber,
            malMangaId = manga.malMangaId ?: existingFavorite?.malMangaId ?: existingReading?.malMangaId,
            lastReadChapterNumber = manga.lastReadChapterNumber ?: existingFavorite?.lastReadChapterNumber ?: existingReading?.lastReadChapterNumber,
        )
        dao.upsertFavorite(
            mergedFavorite.toFavoriteEntity(
                orderIndex = nextOrderIndex(current.map { it.orderIndex })
            )
        )

        val reading = dao.readReading().map { saved ->
            if (sameStoredManga(saved.toSavedManga(), mergedFavorite)) {
                saved.toSavedManga().copy(
                    title = saved.title.ifBlank { mergedFavorite.title },
                    coverUrl = saved.coverUrl.ifBlank { mergedFavorite.coverUrl },
                    detailPath = preferCanonicalDetailPath(saved.toSavedManga(), mergedFavorite),
                    lastChapterTitle = mergedFavorite.lastChapterTitle.ifBlank { saved.lastChapterTitle },
                    lastChapterPath = mergedFavorite.lastChapterPath.ifBlank { saved.lastChapterPath },
                    lastProgressChapterNumber = mergedFavorite.lastProgressChapterNumber ?: saved.lastProgressChapterNumber,
                    malMangaId = mergedFavorite.malMangaId ?: saved.malMangaId,
                    lastReadChapterNumber = mergedFavorite.lastReadChapterNumber ?: saved.lastReadChapterNumber,
                ).toReadingEntity(orderIndex = saved.orderIndex)
            } else {
                saved
            }
        }
        replaceReadingEntities(reading)
        synchronizeLinkedProgress(mergedFavorite)
    }

    fun removeFavorite(providerId: String, detailPath: String) {
        ensureInitialized()
        dao.deleteFavorite(providerId, detailPath)
    }

    fun upsertReading(manga: SavedManga) {
        ensureInitialized()
        val current = dao.readReading()
        val existingReading = current.firstOrNull { sameStoredManga(it.toSavedManga(), manga) }
        val existingFavorite = dao.readFavorites().firstOrNull { sameStoredManga(it.toSavedManga(), manga) }?.toSavedManga()
        val mergedReading = manga.copy(
            title = manga.title.ifBlank { existingReading?.title ?: existingFavorite?.title.orEmpty() },
            coverUrl = manga.coverUrl.ifBlank { existingReading?.coverUrl ?: existingFavorite?.coverUrl.orEmpty() },
            lastChapterTitle = manga.lastChapterTitle.ifBlank { existingReading?.lastChapterTitle ?: existingFavorite?.lastChapterTitle.orEmpty() },
            lastChapterPath = manga.lastChapterPath.ifBlank { existingReading?.lastChapterPath ?: existingFavorite?.lastChapterPath.orEmpty() },
            lastProgressChapterNumber = manga.lastProgressChapterNumber ?: existingReading?.lastProgressChapterNumber ?: existingFavorite?.lastProgressChapterNumber,
            malMangaId = manga.malMangaId ?: existingReading?.malMangaId ?: existingFavorite?.malMangaId,
            lastReadChapterNumber = manga.lastReadChapterNumber ?: existingReading?.lastReadChapterNumber ?: existingFavorite?.lastReadChapterNumber,
        )
        dao.upsertReading(
            mergedReading.toReadingEntity(
                orderIndex = nextOrderIndex(current.map { it.orderIndex })
            )
        )

        val favorites = dao.readFavorites().map { saved ->
            if (sameStoredManga(saved.toSavedManga(), mergedReading)) {
                saved.toSavedManga().copy(
                    title = saved.title.ifBlank { mergedReading.title },
                    coverUrl = saved.coverUrl.ifBlank { mergedReading.coverUrl },
                    detailPath = preferCanonicalDetailPath(saved.toSavedManga(), mergedReading),
                    lastChapterTitle = mergedReading.lastChapterTitle.ifBlank { saved.lastChapterTitle },
                    lastChapterPath = mergedReading.lastChapterPath.ifBlank { saved.lastChapterPath },
                    lastProgressChapterNumber = mergedReading.lastProgressChapterNumber ?: saved.lastProgressChapterNumber,
                    malMangaId = mergedReading.malMangaId ?: saved.malMangaId,
                    lastReadChapterNumber = mergedReading.lastReadChapterNumber ?: saved.lastReadChapterNumber,
                ).toFavoriteEntity(orderIndex = saved.orderIndex)
            } else {
                saved
            }
        }
        replaceFavoriteEntities(favorites)
        synchronizeLinkedProgress(mergedReading)
    }

    fun setFavoriteStatus(providerId: String, detailPath: String, status: FavoriteMangaStatus) {
        ensureInitialized()
        dao.readFavorites().forEach { entity ->
            if (sameStoredManga(entity.toSavedManga(), SavedManga(providerId, "", detailPath, "")) && entity.favoriteStatus != status.name) {
                dao.upsertFavorite(entity.copy(favoriteStatus = status.name))
            }
        }
    }

    fun removeReading(providerId: String, detailPath: String) {
        ensureInitialized()
        dao.deleteReading(providerId, detailPath)
    }

    fun replaceReading(items: List<SavedManga>) {
        ensureInitialized()
        replaceReadingEntities(
            items.mapIndexed { index, item ->
                item.toReadingEntity(orderIndex = (items.size - index).toLong())
            }
        )
    }

    fun isFavorite(providerId: String, detailPath: String): Boolean {
        ensureInitialized()
        val target = SavedManga(providerId, "", detailPath, "")
        return dao.readFavorites().any { sameStoredManga(it.toSavedManga(), target) }
    }

    fun getMangaMalId(providerId: String, detailPath: String): Long? {
        ensureInitialized()
        val target = SavedManga(providerId, "", detailPath, "")
        dao.readFavorites().firstOrNull { sameStoredManga(it.toSavedManga(), target) }?.malMangaId?.let { return it }
        return dao.readReading().firstOrNull { sameStoredManga(it.toSavedManga(), target) }?.malMangaId
    }

    fun setMangaMalId(providerId: String, detailPath: String, malMangaId: Long?): Boolean {
        ensureInitialized()
        val target = SavedManga(providerId, "", detailPath, "")
        var changed = false
        dao.readFavorites().forEach { entity ->
            if (sameStoredManga(entity.toSavedManga(), target) && entity.malMangaId != malMangaId) {
                dao.upsertFavorite(entity.copy(malMangaId = malMangaId))
                changed = true
            }
        }
        dao.readReading().forEach { entity ->
            if (sameStoredManga(entity.toSavedManga(), target) && entity.malMangaId != malMangaId) {
                dao.upsertReading(entity.copy(malMangaId = malMangaId))
                changed = true
            }
        }
        return changed
    }

    fun markChapterRead(providerId: String, chapterPath: String) {
        setChaptersRead(providerId, listOf(chapterPath), true)
    }

    fun toggleChapterRead(providerId: String, chapterPath: String) {
        ensureInitialized()
        val canonicalPath = canonicalChapterKey(providerId, chapterPath)
        if (canonicalPath.isBlank()) return
        if (dao.hasReadChapter(providerId, canonicalPath)) {
            dao.deleteReadChapter(providerId, canonicalPath)
        } else {
            val nextReadOrder = (dao.readMaxReadOrderForProvider(providerId) ?: 0L) + 1L
            dao.upsertReadChapter(
                ReadChapterEntity(
                    providerId = providerId,
                    chapterPath = canonicalPath,
                    readOrder = nextReadOrder,
                )
            )
        }
    }

    fun isChapterRead(providerId: String, chapterPath: String): Boolean {
        ensureInitialized()
        val canonicalPath = canonicalChapterKey(providerId, chapterPath)
        if (canonicalPath.isBlank()) return false
        return dao.hasReadChapter(providerId, canonicalPath)
    }

    fun readAllReadChapters(): Set<String> {
        ensureInitialized()
        return dao.readChapters().map { it.toQualifiedPath() }.toSet()
    }

    fun readChaptersForProvider(providerId: String): Set<String> {
        ensureInitialized()
        return dao.readChaptersForProvider(providerId).map { it.chapterPath }.toSet()
    }

    fun cacheMangaDetail(detail: MangaDetail): Boolean {
        ensureInitialized()
        val detailKey = mangaKey(detail.providerId, detail.detailPath)
        val existing = dao.readMangaDetailCache(detail.providerId, detailKey)
            ?: dao.readMangaDetailCacheByPath(detail.providerId, normalizeStoredPath(detail.detailPath))
        val storedDetail = existing?.detailJson?.let { runCatching { mangaDetailCacheCodec.deserialize(it) }.getOrNull() }
        val canonicalDetailPath = when {
            existing?.detailPath.isNullOrBlank() -> normalizeStoredPath(detail.detailPath)
            detailPathScore(detail.providerId, detail.detailPath) >= detailPathScore(detail.providerId, existing!!.detailPath) -> normalizeStoredPath(detail.detailPath)
            else -> normalizeStoredPath(existing.detailPath)
        }
        val canonicalDetail = detail.copy(detailPath = canonicalDetailPath)
        val detailJson = mangaDetailCacheCodec.serialize(canonicalDetail)
        if (detailJson.length > MAX_CACHED_DETAIL_PAYLOAD_SIZE) {
            dao.deleteMangaDetailCache(detail.providerId, detailKey)
            dao.deleteMangaDetailCacheByPath(detail.providerId, canonicalDetailPath)
            return false
        }
        val now = System.currentTimeMillis()
        if (storedDetail != null && mangaDetailCacheCodec.sameChapterSignature(storedDetail, canonicalDetail)) {
            dao.upsertMangaDetailCache(
                MangaDetailCacheEntity(
                    providerId = detail.providerId,
                    detailKey = detailKey,
                    detailPath = canonicalDetailPath,
                    detailJson = detailJson,
                    chapterCount = canonicalDetail.chapters.size,
                    updatedAt = now,
                )
            )
            return false
        }
        dao.upsertMangaDetailCache(
            MangaDetailCacheEntity(
                providerId = detail.providerId,
                detailKey = detailKey,
                detailPath = canonicalDetailPath,
                detailJson = detailJson,
                chapterCount = canonicalDetail.chapters.size,
                updatedAt = now,
            )
        )
        return true
    }

    fun getCachedMangaDetailSnapshot(providerId: String, detailPath: String): CachedMangaDetailSnapshot? {
        ensureInitialized()
        val detailKey = mangaKey(providerId, detailPath)
        val cached = dao.readMangaDetailCache(providerId, detailKey)
            ?: dao.readMangaDetailCacheByPath(providerId, normalizeStoredPath(detailPath))
        val detail = cached?.detailJson?.let { runCatching { mangaDetailCacheCodec.deserialize(it) }.getOrNull() } ?: return null
        return CachedMangaDetailSnapshot(
            detail = detail,
            updatedAt = cached.updatedAt,
        )
    }

    fun getCachedMangaDetail(providerId: String, detailPath: String): MangaDetail? {
        return getCachedMangaDetailSnapshot(providerId, detailPath)?.detail
    }

    fun getCachedMangaChapterCount(providerId: String, detailPath: String): Int? {
        ensureInitialized()
        val detailKey = mangaKey(providerId, detailPath)
        val cached = dao.readMangaDetailCache(providerId, detailKey)
            ?: dao.readMangaDetailCacheByPath(providerId, normalizeStoredPath(detailPath))
        return cached?.chapterCount?.takeIf { it > 0 }
    }

    fun setChaptersRead(providerId: String, chapterPaths: Collection<String>, read: Boolean) {
        ensureInitialized()
        val normalized = chapterPaths.filter { it.isNotBlank() }.map { canonicalChapterKey(providerId, it) }.distinct()
        if (normalized.isEmpty()) return
        if (read) {
            dao.deleteReadChapters(providerId, normalized)
            var nextReadOrder = (dao.readMaxReadOrderForProvider(providerId) ?: 0L) + 1L
            dao.upsertReadChapters(
                normalized.map { chapterPath ->
                    ReadChapterEntity(
                        providerId = providerId,
                        chapterPath = chapterPath,
                        readOrder = nextReadOrder++,
                    )
                }
            )
        } else {
            dao.deleteReadChapters(providerId, normalized)
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        updateSettings { it.copy(useDarkTheme = enabled) }
    }

    fun setAutoJumpToUnread(enabled: Boolean) {
        updateSettings { it.copy(autoJumpToUnread = enabled) }
    }

    fun setMangaBallAdultContentEnabled(enabled: Boolean) {
        updateSettings { it.copy(mangaBallAdultContentEnabled = enabled) }
    }

    fun setPreferredChapterLanguage(language: AppLanguage) {
        updateSettings { it.copy(preferredChapterLanguage = language.name) }
    }

    fun preferredChapterLanguage(): AppLanguage {
        return AppLanguage.fromStored(readSettings().preferredChapterLanguage).takeIf {
            it != AppLanguage.MULTI
        } ?: AppLanguage.EN
    }

    fun isMangaBallAdultContentEnabled(): Boolean = readSettings().mangaBallAdultContentEnabled

    fun setAppLanguage(language: AppLanguage) {
        updateSettings { it.copy(appLanguage = language.name) }
    }

    fun selectedProviderId(): String = readSettings().selectedProviderId

    fun setSelectedProviderId(providerId: String) {
        updateSettings { it.copy(selectedProviderId = providerId) }
    }

    fun hasSeenProviderPicker(): Boolean = readSettings().hasSeenProviderPicker

    fun setHasSeenProviderPicker(seen: Boolean) {
        updateSettings { it.copy(hasSeenProviderPicker = seen) }
    }

    fun exportBackup(): String {
        ensureInitialized()
        return backupPayloadCodec.exportPayload(
            favorites = jsonCodec.serializeSavedMangaList(dao.readFavorites().map { it.toSavedManga() }),
            reading = jsonCodec.serializeSavedMangaList(dao.readReading().map { it.toSavedManga() }),
            readChapters = serializeQualifiedChapterPaths(dao.readChapters().map { it.toQualifiedPath() }),
            readProgress = serializeProgressMap(dao.readChapterProgress()),
            chapterPageCounts = serializePageCountMap(dao.readChapterPageCounts()),
            selectedProviderId = selectedProviderId(),
            settings = serializeSettings(readSettings()),
            mangaDetailCache = serializeMangaDetailCache(dao.readMangaDetailCaches()),
        )
    }

    fun importBackup(
        payload: String,
        selectedProviderIdFallback: String = selectedProviderId(),
    ) {
        ensureInitialized()
        val importedPayload = backupPayloadCodec.importPayload(payload, selectedProviderIdFallback)
        replaceFromImportedPayload(importedPayload)
        val importedSettings = importedPayload.settings?.let(::JSONObject)
        updateSettings {
            it.copy(
                selectedProviderId = importedPayload.selectedProviderId,
                useDarkTheme = importedSettings?.optBoolean("useDarkTheme", it.useDarkTheme) ?: it.useDarkTheme,
                autoJumpToUnread = importedSettings?.optBoolean("autoJumpToUnread", it.autoJumpToUnread) ?: it.autoJumpToUnread,
                mangaBallAdultContentEnabled = importedSettings?.optBoolean("mangaBallAdultContentEnabled", it.mangaBallAdultContentEnabled) ?: it.mangaBallAdultContentEnabled,
                preferredChapterLanguage = importedSettings?.optString("preferredChapterLanguage").orEmpty().ifBlank { it.preferredChapterLanguage },
                appLanguage = importedSettings?.optString("appLanguage").orEmpty().ifBlank { it.appLanguage },
                hasSeenProviderPicker = importedSettings?.optBoolean("hasSeenProviderPicker", it.hasSeenProviderPicker) ?: it.hasSeenProviderPicker,
                legacyPrefsMigrated = true,
            )
        }
    }

    fun saveChapterProgress(providerId: String, chapterPath: String, pageIndex: Int) {
        ensureInitialized()
        val canonicalPath = canonicalChapterKey(providerId, chapterPath)
        if (canonicalPath.isBlank()) return
        dao.upsertChapterProgress(
            ChapterProgressEntity(
                providerId = providerId,
                chapterPath = canonicalPath,
                pageIndex = pageIndex.coerceAtLeast(0),
            )
        )
    }

    fun getChapterProgress(providerId: String, chapterPath: String): Int {
        ensureInitialized()
        val canonicalPath = canonicalChapterKey(providerId, chapterPath)
        if (canonicalPath.isBlank()) return 0
        return dao.readChapterProgress(providerId, canonicalPath)?.pageIndex?.coerceAtLeast(0) ?: 0
    }

    fun saveChapterPageCount(providerId: String, chapterPath: String, pageCount: Int) {
        ensureInitialized()
        val canonicalPath = canonicalChapterKey(providerId, chapterPath)
        if (canonicalPath.isBlank() || pageCount <= 0) return
        dao.upsertChapterPageCount(
            ChapterPageCountEntity(
                providerId = providerId,
                chapterPath = canonicalPath,
                pageCount = pageCount,
            )
        )
    }

    fun getChapterPageCount(providerId: String, chapterPath: String): Int {
        ensureInitialized()
        val canonicalPath = canonicalChapterKey(providerId, chapterPath)
        if (canonicalPath.isBlank()) return 0
        return dao.readChapterPageCount(providerId, canonicalPath)?.pageCount?.coerceAtLeast(0) ?: 0
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            dao.deleteOversizedMangaDetailCaches(MAX_CACHED_DETAIL_PAYLOAD_SIZE)
            val settings = dao.readSettings()
            if (settings == null) {
                if (legacyPrefs.getAll().isNotEmpty()) {
                    migrateFromLegacyPrefs()
                } else {
                    dao.upsertSettings(defaultSettings(legacyPrefsMigrated = true))
                }
            } else if (!settings.legacyPrefsMigrated) {
                if (legacyPrefs.getAll().isNotEmpty()) {
                    migrateFromLegacyPrefs()
                } else {
                    dao.upsertSettings(settings.copy(legacyPrefsMigrated = true))
                }
            }
            runFavoriteStatusBackfillIfNeeded()
            initialized = true
        }
    }

    private fun migrateFromLegacyPrefs() {
        val settingsJson = JSONObject()
            .put("selectedProviderId", legacyPrefs.getString("selectedProviderId", "").orEmpty())
            .put("useDarkTheme", legacyPrefs.getBoolean("useDarkTheme", false))
            .put("autoJumpToUnread", legacyPrefs.getBoolean("autoJumpToUnread", true))
            .put("mangaBallAdultContentEnabled", legacyPrefs.getBoolean(KEY_MANGABALL_ADULT_CONTENT, false))
            .put("preferredChapterLanguage", AppLanguage.EN.name)
            .put("appLanguage", legacyPrefs.getString("appLanguage", AppLanguage.EN.name).orEmpty())
            .put("hasSeenProviderPicker", legacyPrefs.getBoolean("hasSeenProviderPicker", false))
        val payload = backupPayloadCodec.exportPayload(
            favorites = legacyPrefs.getString("favorites", "[]").orEmpty(),
            reading = legacyPrefs.getString("reading", "[]").orEmpty(),
            readChapters = legacyPrefs.getString("readChapters", "[]").orEmpty(),
            readProgress = legacyPrefs.getString("readProgress", "{}").orEmpty(),
            chapterPageCounts = legacyPrefs.getString("chapterPageCounts", "{}").orEmpty(),
            selectedProviderId = legacyPrefs.getString("selectedProviderId", "").orEmpty(),
            settings = settingsJson.toString(),
            mangaDetailCache = "[]",
        )
        replaceFromImportedPayload(backupPayloadCodec.importPayload(payload, defaultProviderId))
        dao.upsertSettings(
            defaultSettings(
                selectedProviderId = legacyPrefs.getString("selectedProviderId", "").orEmpty(),
                useDarkTheme = legacyPrefs.getBoolean("useDarkTheme", false),
                autoJumpToUnread = legacyPrefs.getBoolean("autoJumpToUnread", true),
                mangaBallAdultContentEnabled = legacyPrefs.getBoolean(KEY_MANGABALL_ADULT_CONTENT, false),
                preferredChapterLanguage = AppLanguage.EN.name,
                appLanguage = legacyPrefs.getString("appLanguage", AppLanguage.EN.name).orEmpty(),
                hasSeenProviderPicker = legacyPrefs.getBoolean("hasSeenProviderPicker", false),
                legacyPrefsMigrated = true,
            )
        )
    }

    private fun replaceFromImportedPayload(importedPayload: com.paudinc.komastream.data.repository.ImportedLibraryPayload) {
        dao.clearFavorites()
        dao.clearReading()
        dao.clearReadChapters()
        dao.clearChapterProgress()
        dao.clearChapterPageCounts()
        dao.clearMangaDetailCache()

        val parsedFavorites = jsonCodec.parseSavedMangaList(
            value = importedPayload.favorites,
            fallbackProviderId = defaultProviderId,
            missingFavoriteStatus = FavoriteMangaStatus.READING,
        )
        val parsedReading = jsonCodec.parseSavedMangaList(
            value = importedPayload.reading,
            fallbackProviderId = defaultProviderId,
            missingFavoriteStatus = FavoriteMangaStatus.READING,
        )
        val legacyBackup = importedPayload.backupVersion < 2
        val (favorites, reading) = canonicalizeSavedEntries(parsedFavorites, parsedReading)

        favorites.mapIndexed { index, manga ->
            manga.toFavoriteEntity(orderIndex = (favorites.size - index).toLong())
        }.forEach(dao::upsertFavorite)
        reading.mapIndexed { index, manga ->
            manga.toReadingEntity(orderIndex = (reading.size - index).toLong())
        }.forEach(dao::upsertReading)

        (favorites + reading).forEach(::synchronizeLinkedProgress)

        importQualifiedChapterPaths(importedPayload.readChapters)
        importProgressMap(importedPayload.readProgress)
        importPageCountMap(importedPayload.chapterPageCounts)
        importMangaDetailCache(importedPayload.mangaDetailCache)
        reconcileImportedFavoriteStatuses(legacyBackup)
    }

    private fun importMangaDetailCache(serializedValue: String) {
        val json = JSONArray(serializedValue)
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val providerId = item.optString("providerId").orEmpty()
            val detailPath = normalizeStoredPath(item.optString("detailPath"))
            val detailKey = mangaKey(providerId, detailPath)
            val detailJson = mangaDetailCacheCodec.normalizeStoragePayload(item.optString("detailJson").orEmpty())
            if (providerId.isBlank() || detailKey.isBlank() || detailJson.isBlank()) continue
            if (detailJson.length > MAX_CACHED_DETAIL_PAYLOAD_SIZE) continue
            dao.upsertMangaDetailCache(
                MangaDetailCacheEntity(
                    providerId = providerId,
                    detailKey = detailKey,
                    detailPath = detailPath,
                    detailJson = detailJson,
                    chapterCount = item.optInt("chapterCount", 0),
                    updatedAt = item.optLong("updatedAt", System.currentTimeMillis()),
                )
            )
        }
    }

    private fun reconcileImportedFavoriteStatuses(legacyBackup: Boolean) {
        dao.readFavorites().forEach { entity ->
            val currentStatus = FavoriteMangaStatus.fromStored(entity.favoriteStatus)
            val readingMatch = if (legacyBackup) {
                dao.readReading().firstOrNull { sameStoredManga(it.toSavedManga(), entity.toSavedManga()) }
            } else {
                null
            }
            val detail = dao.readMangaDetailCache(entity.providerId, mangaKey(entity.providerId, entity.detailPath))
                ?: dao.readMangaDetailCacheByPath(entity.providerId, normalizeStoredPath(entity.detailPath))
                ?: return@forEach
            val cachedDetail = detail.detailJson.let { runCatching { mangaDetailCacheCodec.deserialize(it) }.getOrNull() } ?: return@forEach
            val totalChapterCount = cachedDetail.chapters.size
            if (totalChapterCount <= 0) return@forEach
            val readCount = entity.lastReadChapterNumber?.takeIf { it > 0 }
                ?: resolveMalReadCountForReadChapters(
                    providerId = entity.providerId,
                    detailPath = entity.detailPath,
                    chapters = cachedDetail.chapters,
                    readChapters = dao.readChaptersForProvider(entity.providerId).map { it.chapterPath }.toSet(),
                )
            val inferredStatus = when {
                readCount >= totalChapterCount -> FavoriteMangaStatus.COMPLETED
                legacyBackup -> FavoriteMangaStatus.READING
                readingMatch != null -> FavoriteMangaStatus.READING
                readCount > 0 || entity.lastProgressChapterNumber != null || entity.lastChapterPath.isNotBlank() -> FavoriteMangaStatus.READING
                else -> currentStatus
            }
            if (inferredStatus != currentStatus) {
                dao.upsertFavorite(entity.copy(favoriteStatus = inferredStatus.name))
            }
        }
    }

    private fun importQualifiedChapterPaths(serializedValue: String) {
        val json = JSONArray(serializedValue)
        val entries = buildList(json.length()) {
            for (index in 0 until json.length()) {
                val raw = json.optString(index).trim()
                if (raw.isNotBlank()) add(raw)
            }
        }
        entries.forEachIndexed { index, qualified ->
            val providerId = qualified.substringBefore("::", missingDelimiterValue = defaultProviderId)
            val chapterPath = qualified.substringAfter("::", missingDelimiterValue = qualified)
            dao.upsertReadChapter(
                ReadChapterEntity(
                    providerId = providerId,
                    chapterPath = canonicalChapterKey(providerId, chapterPath),
                    readOrder = (entries.size - index).toLong(),
                )
            )
        }
    }

    private fun importProgressMap(serializedValue: String) {
        val json = JSONObject(serializedValue)
        json.keys().forEach { key ->
            val providerId = key.substringBefore("::", missingDelimiterValue = defaultProviderId)
            val chapterPath = key.substringAfter("::", missingDelimiterValue = key)
            val pageIndex = json.optInt(key, 0)
            dao.upsertChapterProgress(
                ChapterProgressEntity(
                    providerId = providerId,
                    chapterPath = canonicalChapterKey(providerId, chapterPath),
                    pageIndex = pageIndex.coerceAtLeast(0),
                )
            )
        }
    }

    private fun importPageCountMap(serializedValue: String) {
        val json = JSONObject(serializedValue)
        json.keys().forEach { key ->
            val providerId = key.substringBefore("::", missingDelimiterValue = defaultProviderId)
            val chapterPath = key.substringAfter("::", missingDelimiterValue = key)
            val pageCount = json.optInt(key, 0)
            if (pageCount > 0) {
                dao.upsertChapterPageCount(
                    ChapterPageCountEntity(
                        providerId = providerId,
                        chapterPath = canonicalChapterKey(providerId, chapterPath),
                        pageCount = pageCount,
                    )
                )
            }
        }
    }

    private fun replaceFavoriteEntities(items: List<FavoriteMangaEntity>) {
        dao.clearFavorites()
        items.forEach { dao.upsertFavorite(it) }
    }

    private fun replaceReadingEntities(items: List<ReadingMangaEntity>) {
        dao.clearReading()
        items.forEach { dao.upsertReading(it) }
    }

    private fun synchronizeLinkedProgress(source: SavedManga) {
        val sourceMalId = source.malMangaId ?: return
        val sourceReadCount = source.lastReadChapterNumber?.takeIf { it > 0 }
        val sourceProgressChapterNumber = source.lastProgressChapterNumber ?: source.lastChapterTitle.toProgressChapterNumber()
        if (sourceReadCount == null && sourceProgressChapterNumber == null) return

        dao.readFavorites().forEach { entity ->
            if (entity.malMangaId != sourceMalId) return@forEach
            val current = entity.toSavedManga()
            if (sameStoredManga(current, source)) return@forEach
            val resolvedChapterPath = resolveChapterPathForProgressReference(
                providerId = entity.providerId,
                detailPath = entity.detailPath,
                chapters = getCachedMangaDetail(entity.providerId, entity.detailPath)?.chapters.orEmpty(),
                progressChapterNumber = sourceProgressChapterNumber,
                fallbackChapterPath = source.lastChapterPath,
            ) ?: current.lastChapterPath
            val shouldUpdate = sourceReadCount != null && (current.lastReadChapterNumber ?: 0) < sourceReadCount ||
                sourceProgressChapterNumber != null && (
                    current.lastProgressChapterNumber == null ||
                        kotlin.math.abs((current.lastProgressChapterNumber ?: 0.0) - sourceProgressChapterNumber) > 0.0001 ||
                        current.lastChapterPath != resolvedChapterPath
                )
            if (!shouldUpdate) return@forEach
            dao.upsertFavorite(
                entity.copy(
                    lastChapterTitle = source.lastChapterTitle.ifBlank { entity.lastChapterTitle },
                    lastChapterPath = resolvedChapterPath,
                    lastProgressChapterNumber = sourceProgressChapterNumber ?: entity.lastProgressChapterNumber,
                    lastReadChapterNumber = sourceReadCount ?: entity.lastReadChapterNumber,
                )
            )
        }

        dao.readReading().forEach { entity ->
            if (entity.malMangaId != sourceMalId) return@forEach
            val current = entity.toSavedManga()
            if (sameStoredManga(current, source)) return@forEach
            val resolvedChapterPath = resolveChapterPathForProgressReference(
                providerId = entity.providerId,
                detailPath = entity.detailPath,
                chapters = getCachedMangaDetail(entity.providerId, entity.detailPath)?.chapters.orEmpty(),
                progressChapterNumber = sourceProgressChapterNumber,
                fallbackChapterPath = source.lastChapterPath,
            ) ?: current.lastChapterPath
            val shouldUpdate = sourceReadCount != null && (current.lastReadChapterNumber ?: 0) < sourceReadCount ||
                sourceProgressChapterNumber != null && (
                    current.lastProgressChapterNumber == null ||
                        kotlin.math.abs((current.lastProgressChapterNumber ?: 0.0) - sourceProgressChapterNumber) > 0.0001 ||
                        current.lastChapterPath != resolvedChapterPath
                )
            if (!shouldUpdate) return@forEach
            dao.upsertReading(
                entity.copy(
                    lastChapterTitle = source.lastChapterTitle.ifBlank { entity.lastChapterTitle },
                    lastChapterPath = resolvedChapterPath,
                    lastProgressChapterNumber = sourceProgressChapterNumber ?: entity.lastProgressChapterNumber,
                    lastReadChapterNumber = sourceReadCount ?: entity.lastReadChapterNumber,
                )
            )
        }
    }

    private fun replaceReadChapterEntries(qualifiedPaths: List<String>) {
        dao.clearReadChapters()
        qualifiedPaths.mapIndexed { index, qualified ->
            val providerId = qualified.substringBefore("::", missingDelimiterValue = defaultProviderId)
            val chapterPath = qualified.substringAfter("::", missingDelimiterValue = qualified)
            ReadChapterEntity(
                providerId = providerId,
                chapterPath = canonicalChapterKey(providerId, chapterPath),
                readOrder = (qualifiedPaths.size - index).toLong(),
            )
        }.forEach(dao::upsertReadChapter)
    }

    private fun updateSettings(transform: (AppSettingsEntity) -> AppSettingsEntity) {
        ensureInitialized()
        val current = readSettings()
        dao.upsertSettings(transform(current))
    }

    private fun readSettings(): AppSettingsEntity {
        val settings = dao.readSettings()
        return settings ?: defaultSettings(legacyPrefsMigrated = false)
    }

    private fun defaultSettings(
        selectedProviderId: String = "",
        useDarkTheme: Boolean = false,
        autoJumpToUnread: Boolean = true,
        mangaBallAdultContentEnabled: Boolean = false,
        preferredChapterLanguage: String = AppLanguage.EN.name,
        appLanguage: String = AppLanguage.EN.name,
        hasSeenProviderPicker: Boolean = false,
        legacyPrefsMigrated: Boolean = false,
        favoriteStatusBackfillDone: Boolean = false,
    ): AppSettingsEntity {
        return AppSettingsEntity(
            id = 0,
            selectedProviderId = selectedProviderId,
            useDarkTheme = useDarkTheme,
            autoJumpToUnread = autoJumpToUnread,
            mangaBallAdultContentEnabled = mangaBallAdultContentEnabled,
            preferredChapterLanguage = preferredChapterLanguage,
            appLanguage = appLanguage,
            hasSeenProviderPicker = hasSeenProviderPicker,
            legacyPrefsMigrated = legacyPrefsMigrated,
            favoriteStatusBackfillDone = favoriteStatusBackfillDone,
        )
    }

    private fun runFavoriteStatusBackfillIfNeeded() {
        val settings = dao.readSettings() ?: return
        if (settings.favoriteStatusBackfillDone) return
        reconcileImportedFavoriteStatuses(legacyBackup = true)
        dao.upsertSettings(settings.copy(favoriteStatusBackfillDone = true))
    }

    private fun serializeQualifiedChapterPaths(items: List<String>): String {
        val json = JSONArray()
        items.forEach { json.put(it) }
        return json.toString()
    }

    private fun serializeProgressMap(items: List<ChapterProgressEntity>): String {
        val json = JSONObject()
        items.forEach { item ->
            json.put(qualifyProviderValue(item.providerId, item.chapterPath), item.pageIndex.coerceAtLeast(0))
        }
        return json.toString()
    }

    private fun serializePageCountMap(items: List<ChapterPageCountEntity>): String {
        val json = JSONObject()
        items.forEach { item ->
            json.put(qualifyProviderValue(item.providerId, item.chapterPath), item.pageCount.coerceAtLeast(0))
        }
        return json.toString()
    }

    private fun serializeSettings(settings: AppSettingsEntity): String {
        return JSONObject()
            .put("selectedProviderId", settings.selectedProviderId)
            .put("useDarkTheme", settings.useDarkTheme)
            .put("autoJumpToUnread", settings.autoJumpToUnread)
            .put("mangaBallAdultContentEnabled", settings.mangaBallAdultContentEnabled)
            .put("preferredChapterLanguage", settings.preferredChapterLanguage)
            .put("appLanguage", settings.appLanguage)
            .put("hasSeenProviderPicker", settings.hasSeenProviderPicker)
            .toString()
    }

    private fun serializeMangaDetailCache(items: List<MangaDetailCacheEntity>): String {
        return JSONArray().apply {
            items.forEach { item ->
                val detailPath = normalizeStoredPath(item.detailPath)
                put(
                    JSONObject()
                        .put("providerId", item.providerId)
                        .put("detailKey", mangaKey(item.providerId, detailPath))
                        .put("detailPath", detailPath)
                        .put("detailJson", item.detailJson)
                        .put("chapterCount", item.chapterCount)
                        .put("updatedAt", item.updatedAt)
                )
            }
        }.toString()
    }

    private fun nextOrderIndex(orderIndexes: List<Long>): Long {
        val max = orderIndexes.maxOrNull() ?: 0L
        return max + 1L
    }

    private fun SavedManga.toFavoriteEntity(orderIndex: Long): FavoriteMangaEntity {
        return FavoriteMangaEntity(
            providerId = providerId,
            detailPath = normalizeStoredPath(detailPath),
            title = title,
            coverUrl = coverUrl,
            favoriteStatus = favoriteStatus.name,
            lastChapterTitle = lastChapterTitle,
            lastChapterPath = canonicalChapterKey(providerId, lastChapterPath),
            lastProgressChapterNumber = lastProgressChapterNumber,
            malMangaId = malMangaId,
            lastReadChapterNumber = lastReadChapterNumber,
            orderIndex = orderIndex,
        )
    }

    private fun SavedManga.toReadingEntity(orderIndex: Long): ReadingMangaEntity {
        return ReadingMangaEntity(
            providerId = providerId,
            detailPath = normalizeStoredPath(detailPath),
            title = title,
            coverUrl = coverUrl,
            lastChapterTitle = lastChapterTitle,
            lastChapterPath = canonicalChapterKey(providerId, lastChapterPath),
            lastProgressChapterNumber = lastProgressChapterNumber,
            malMangaId = malMangaId,
            lastReadChapterNumber = lastReadChapterNumber,
            orderIndex = orderIndex,
        )
    }

    private fun FavoriteMangaEntity.toSavedManga(): SavedManga {
        return SavedManga(
            providerId = providerId,
            title = title,
            detailPath = detailPath,
            coverUrl = coverUrl,
            favoriteStatus = FavoriteMangaStatus.fromStored(favoriteStatus),
            lastChapterTitle = lastChapterTitle,
            lastChapterPath = lastChapterPath,
            lastProgressChapterNumber = lastProgressChapterNumber,
            malMangaId = malMangaId,
            lastReadChapterNumber = lastReadChapterNumber,
        )
    }

    private fun ReadingMangaEntity.toSavedManga(): SavedManga {
        return SavedManga(
            providerId = providerId,
            title = title,
            detailPath = detailPath,
            coverUrl = coverUrl,
            lastChapterTitle = lastChapterTitle,
            lastChapterPath = lastChapterPath,
            lastProgressChapterNumber = lastProgressChapterNumber,
            malMangaId = malMangaId,
            lastReadChapterNumber = lastReadChapterNumber,
        )
    }

    private fun ReadChapterEntity.toQualifiedPath(): String = qualifyProviderValue(providerId, chapterPath)

    private fun sameStoredManga(left: SavedManga, right: SavedManga): Boolean {
        return left.providerId == right.providerId &&
            mangaKey(left.providerId, left.detailPath) == mangaKey(right.providerId, right.detailPath)
    }

    private fun canonicalizeSavedEntries(
        favorites: List<SavedManga>,
        reading: List<SavedManga>,
    ): Pair<List<SavedManga>, List<SavedManga>> {
        val canonicalByKey = (favorites + reading)
            .groupBy { mangaKey(it.providerId, it.detailPath) }
            .mapValues { (_, items) ->
                items.maxWithOrNull(
                    compareBy<SavedManga> { detailPathScore(it.providerId, it.detailPath) }
                        .thenBy { it.detailPath.length }
                )
            }

        fun normalize(items: List<SavedManga>): List<SavedManga> {
            return items.map { item ->
                val canonical = canonicalByKey[mangaKey(item.providerId, item.detailPath)]
                item.copy(detailPath = normalizeStoredPath(canonical?.detailPath ?: item.detailPath))
            }.distinctBy { it.providerId to it.detailPath }
        }

        return normalize(favorites) to normalize(reading)
    }

    private fun preferCanonicalDetailPath(left: SavedManga, right: SavedManga): String {
        return if (detailPathScore(right.providerId, right.detailPath) >= detailPathScore(left.providerId, left.detailPath)) {
            normalizeStoredPath(right.detailPath)
        } else {
            normalizeStoredPath(left.detailPath)
        }
    }

    private fun mangaKey(providerId: String, detailPath: String): String {
        return canonicalMangaPathKey(providerId, detailPath)
    }

    private fun detailPathScore(providerId: String, detailPath: String): Int {
        val normalized = normalizeStoredPath(detailPath).substringBefore("?").trim('/')
        return when (providerId) {
            "inmanga-es" -> normalized.split("/").size
            else -> normalized.length
        }
    }

    private fun sameStoredChapter(providerId: String, storedQualifiedPath: String, chapterPath: String): Boolean {
        val storedPath = when {
            "::" in storedQualifiedPath -> {
                val prefix = "$providerId::"
                if (!storedQualifiedPath.startsWith(prefix)) return false
                storedQualifiedPath.removePrefix(prefix)
            }
            providerId == defaultProviderId -> storedQualifiedPath
            else -> return false
        }
        return canonicalChapterKey(providerId, storedPath) == canonicalChapterKey(providerId, chapterPath)
    }

    private fun canonicalChapterKey(providerId: String, chapterPath: String): String {
        return canonicalChapterPathKey(providerId, chapterPath)
    }

    private companion object {
        private const val KEY_MANGABALL_ADULT_CONTENT = "mangaballAdultContentEnabled"
        private const val MAX_CACHED_DETAIL_PAYLOAD_SIZE = 900_000
    }
}
