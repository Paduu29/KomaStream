package com.paudinc.komastream.utils

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
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
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

data class CachedMangaDetailSnapshot(
    val detail: MangaDetail,
    val updatedAt: Long,
)

data class LibraryStoreSnapshot(
    val state: LibraryState,
    val allProvidersState: LibraryState,
    val favoriteKeys: Set<String>,
    val chapterProgressByKey: Map<String, Int>,
    val cachedDetailByKey: Map<String, MangaDetail>,
    val readChaptersByProvider: Map<String, Set<String>>,
)

class LibraryStore(
    context: Context,
    providerRegistry: ProviderRegistry = createDefaultProviderRegistry(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val legacyPrefs = context.getSharedPreferences("manga_library", Context.MODE_PRIVATE)
    private val database = LibraryDatabase.getInstance(appContext)
    private val dao = database.libraryDao()
    private val providerRegistry = providerRegistry
    private val defaultProviderId: String = providerRegistry.defaultProvider().id
    private val jsonCodec = LibraryJsonCodec(defaultProviderId = defaultProviderId)
    private val backupPayloadCodec = LibraryBackupPayloadCodec()
    private val mangaDetailCacheCodec = MangaDetailCacheCodec()
    private val initLock = Any()

    @Volatile
    private var initialized = false

    suspend fun read(filterBySelectedProvider: Boolean = true): LibraryState =
        readSnapshot(filterBySelectedProvider).state

    suspend fun readSnapshot(filterBySelectedProvider: Boolean = true): LibraryStoreSnapshot {
        ensureInitialized()
        val settings = readSettings()
        val disabledProviderIds = parseDisabledProviderIds(settings.disabledProviderIdsJson)
        val resolvedSelectedProviderId = resolveSelectedProviderId(settings, disabledProviderIds)
        val favoriteEntities = dao.readFavorites()
        val readingEntities = dao.readReading()
        val readChapterEntries = dao.readChapters()
        val chapterProgressEntries = dao.readChapterProgress()
        val cachedDetailEntries = dao.readMangaDetailCaches()
        val parsedFavorites = favoriteEntities.map { it.toSavedManga() }
        val parsedReading = readingEntities.map { it.toSavedManga() }
        val (allFavorites, allReading) = canonicalizeSavedEntries(parsedFavorites, parsedReading)
        val selectedReadChapters = readChapterEntries
            .asSequence()
            .filter { it.providerId == resolvedSelectedProviderId }
            .map { it.chapterPath }
            .toSet()
        val allProvidersState = buildLibraryState(
            favorites = allFavorites,
            reading = allReading,
            readChapters = selectedReadChapters,
            settings = settings,
            disabledProviderIds = disabledProviderIds,
            selectedProviderId = resolvedSelectedProviderId,
        )
        val filteredState = if (filterBySelectedProvider) {
            allProvidersState.copy(
                favorites = allFavorites.filter { it.providerId == resolvedSelectedProviderId },
                reading = allReading.filter { it.providerId == resolvedSelectedProviderId },
            )
        } else {
            allProvidersState
        }
        return LibraryStoreSnapshot(
            state = filteredState,
            allProvidersState = allProvidersState,
            favoriteKeys = allFavorites.mapTo(linkedSetOf()) { favorite ->
                qualifyMangaLookupKey(favorite.providerId, favorite.detailPath)
            },
            chapterProgressByKey = chapterProgressEntries.associate { entry ->
                qualifyProviderValue(entry.providerId, entry.chapterPath) to entry.pageIndex.coerceAtLeast(0)
            },
            cachedDetailByKey = cachedDetailEntries
                .sortedBy { it.updatedAt }
                .mapNotNull { entity ->
                    runCatching { mangaDetailCacheCodec.deserialize(entity.detailJson) }.getOrNull()
                        ?.let { detail -> qualifyMangaLookupKey(detail.providerId, detail.detailPath) to detail }
                }
                .toMap(),
            readChaptersByProvider = readChapterEntries
                .groupBy { it.providerId }
                .mapValues { (_, entries) -> entries.map { it.chapterPath }.toSet() },
        )
    }

    suspend fun toggleFavorite(manga: SavedManga) {
        ensureInitialized()
        database.withTransaction {
            val current = dao.readFavorites()
            val existing = current.firstOrNull { sameStoredManga(it.toSavedManga(), manga) }
            if (existing != null) {
                dao.deleteFavorite(existing.providerId, existing.detailPath)
                return@withTransaction
            }
            dao.upsertFavorite(
                manga.toFavoriteEntity(orderIndex = nextOrderIndex(dao.readMaxFavoriteOrder()))
            )
        }
    }

    suspend fun upsertFavorite(manga: SavedManga) {
        ensureInitialized()
        database.withTransaction {
            val current = dao.readFavorites()
            val existingFavorite = current.firstOrNull { sameStoredManga(it.toSavedManga(), manga) }
            val matchingReadingEntities = dao.readReading().filter { sameStoredManga(it.toSavedManga(), manga) }
            val existingReading = matchingReadingEntities.firstOrNull()?.toSavedManga()
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
                    orderIndex = existingFavorite?.orderIndex ?: nextOrderIndex(dao.readMaxFavoriteOrder())
                )
            )

            val readingUpdates = matchingReadingEntities.map { saved ->
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
            }
            if (readingUpdates.isNotEmpty()) {
                dao.upsertReadings(readingUpdates)
            }
            synchronizeLinkedProgress(mergedFavorite)
        }
    }

    suspend fun removeFavorite(providerId: String, detailPath: String) {
        ensureInitialized()
        dao.deleteFavorite(providerId, detailPath)
    }

    suspend fun upsertReading(manga: SavedManga) {
        ensureInitialized()
        database.withTransaction {
            val current = dao.readReading()
            val existingReading = current.firstOrNull { sameStoredManga(it.toSavedManga(), manga) }
            val matchingFavoriteEntities = dao.readFavorites().filter { sameStoredManga(it.toSavedManga(), manga) }
            val existingFavorite = matchingFavoriteEntities.firstOrNull()?.toSavedManga()
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
                    orderIndex = existingReading?.orderIndex ?: nextOrderIndex(dao.readMaxReadingOrder())
                )
            )

            val favoriteUpdates = matchingFavoriteEntities.map { saved ->
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
            }
            if (favoriteUpdates.isNotEmpty()) {
                dao.upsertFavorites(favoriteUpdates)
            }
            synchronizeLinkedProgress(mergedReading)
        }
    }

    suspend fun setFavoriteStatus(providerId: String, detailPath: String, status: FavoriteMangaStatus) {
        ensureInitialized()
        database.withTransaction {
            dao.readFavorites().forEach { entity ->
                if (sameStoredManga(entity.toSavedManga(), SavedManga(providerId, "", detailPath, "")) && entity.favoriteStatus != status.name) {
                    dao.upsertFavorite(entity.copy(favoriteStatus = status.name))
                }
            }
        }
    }

    suspend fun removeReading(providerId: String, detailPath: String) {
        ensureInitialized()
        database.withTransaction {
            dao.deleteReading(providerId, detailPath)
        }
    }

    suspend fun replaceReading(items: List<SavedManga>) {
        ensureInitialized()
        replaceReadingEntities(
            items.mapIndexed { index, item ->
                item.toReadingEntity(orderIndex = (items.size - index).toLong())
            }
        )
    }

    suspend fun isFavorite(providerId: String, detailPath: String): Boolean {
        ensureInitialized()
        val target = SavedManga(providerId, "", detailPath, "")
        return dao.readFavorites().any { sameStoredManga(it.toSavedManga(), target) }
    }

    suspend fun getMangaMalId(providerId: String, detailPath: String): Long? {
        ensureInitialized()
        val target = SavedManga(providerId, "", detailPath, "")
        dao.readFavorites().firstOrNull { sameStoredManga(it.toSavedManga(), target) }?.malMangaId?.let { return it }
        return dao.readReading().firstOrNull { sameStoredManga(it.toSavedManga(), target) }?.malMangaId
    }

    suspend fun setMangaMalId(providerId: String, detailPath: String, malMangaId: Long?): Boolean {
        ensureInitialized()
        return database.withTransaction {
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
            changed
        }
    }

    suspend fun markChapterRead(providerId: String, chapterPath: String) {
        setChaptersRead(providerId, listOf(chapterPath), true)
    }

    suspend fun toggleChapterRead(providerId: String, chapterPath: String) {
        ensureInitialized()
        database.withTransaction {
            val canonicalPath = canonicalChapterKey(providerId, chapterPath)
            if (canonicalPath.isBlank()) return@withTransaction
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
    }

    suspend fun isChapterRead(providerId: String, chapterPath: String): Boolean {
        ensureInitialized()
        val canonicalPath = canonicalChapterKey(providerId, chapterPath)
        if (canonicalPath.isBlank()) return false
        return dao.hasReadChapter(providerId, canonicalPath)
    }

    suspend fun readAllReadChapters(): Set<String> {
        ensureInitialized()
        return dao.readChapters().map { it.toQualifiedPath() }.toSet()
    }

    suspend fun readChaptersForProvider(providerId: String): Set<String> {
        ensureInitialized()
        return dao.readChaptersForProvider(providerId).map { it.chapterPath }.toSet()
    }

    suspend fun readAllChapterProgress(): Map<String, Int> {
        ensureInitialized()
        return dao.readChapterProgress().associate { entry ->
            qualifyProviderValue(entry.providerId, entry.chapterPath) to entry.pageIndex.coerceAtLeast(0)
        }
    }

    suspend fun readAllCachedMangaDetails(): Map<String, MangaDetail> {
        ensureInitialized()
        return dao.readMangaDetailCaches()
            .sortedBy { it.updatedAt }
            .mapNotNull { entity ->
                runCatching { mangaDetailCacheCodec.deserialize(entity.detailJson) }.getOrNull()
                    ?.let { detail -> qualifyMangaLookupKey(detail.providerId, detail.detailPath) to detail }
            }
            .toMap()
    }

    suspend fun readAllReadChaptersByProvider(): Map<String, Set<String>> {
        ensureInitialized()
        return dao.readChapters()
            .groupBy { it.providerId }
            .mapValues { (_, entries) -> entries.map { it.chapterPath }.toSet() }
    }

    suspend fun cacheMangaDetail(detail: MangaDetail): Boolean {
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
                    chapterCount = chapterCountForProvider(detail.providerId, canonicalDetail.chapters),
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
                chapterCount = chapterCountForProvider(detail.providerId, canonicalDetail.chapters),
                updatedAt = now,
            )
        )
        return true
    }

    suspend fun getCachedMangaDetailSnapshot(providerId: String, detailPath: String): CachedMangaDetailSnapshot? {
        ensureInitialized()
        val detailKey = mangaKey(providerId, detailPath)
        val cached = dao.readMangaDetailCache(providerId, detailKey)
            ?: dao.readMangaDetailCacheByPath(providerId, normalizeStoredPath(detailPath))
        val detail = cached?.detailJson?.let { runCatching { mangaDetailCacheCodec.deserialize(it) }.getOrNull() }
            ?: return null
        return CachedMangaDetailSnapshot(
            detail = detail,
            updatedAt = cached.updatedAt,
        )
    }

    suspend fun getCachedMangaDetail(providerId: String, detailPath: String): MangaDetail? {
        return getCachedMangaDetailSnapshot(providerId, detailPath)?.detail
    }

    suspend fun getCachedMangaChapterCount(providerId: String, detailPath: String): Int? {
        ensureInitialized()
        val detailKey = mangaKey(providerId, detailPath)
        val cached = dao.readMangaDetailCache(providerId, detailKey)
            ?: dao.readMangaDetailCacheByPath(providerId, normalizeStoredPath(detailPath))
        return cached?.chapterCount?.takeIf { it > 0 }
    }

    suspend fun setChaptersRead(providerId: String, chapterPaths: Collection<String>, read: Boolean) {
        ensureInitialized()
        database.withTransaction {
            val normalized = chapterPaths.filter { it.isNotBlank() }.map { canonicalChapterKey(providerId, it) }.distinct()
            if (normalized.isEmpty()) return@withTransaction
            dao.deleteReadChapters(providerId, normalized)
            if (read) {
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
            }
        }
    }

    suspend fun setDarkTheme(enabled: Boolean) {
        updateSettings { it.copy(useDarkTheme = enabled) }
    }

    suspend fun setAutoJumpToUnread(enabled: Boolean) {
        updateSettings { it.copy(autoJumpToUnread = enabled) }
    }

    suspend fun setMangaBallAdultContentEnabled(enabled: Boolean) {
        setAdultContentEnabled(enabled)
    }

    suspend fun setManhwaLatinoAdultContentEnabled(enabled: Boolean) {
        setAdultContentEnabled(enabled)
    }

    suspend fun setAdultContentEnabled(enabled: Boolean) {
        updateSettings {
            it.copy(
                adultContentEnabled = enabled,
                mangaBallAdultContentEnabled = enabled,
                manhwaLatinoAdultContentEnabled = enabled,
            )
        }
    }

    suspend fun setAdultOnlyProvidersEnabled(enabled: Boolean) {
        updateSettings {
            val updated = it.copy(adultOnlyProvidersEnabled = enabled)
            updated.copy(
                selectedProviderId = resolveSelectedProviderId(
                    updated,
                    parseDisabledProviderIds(updated.disabledProviderIdsJson),
                )
            )
        }
    }

    fun adultOnlyProvidersEnabled(): Boolean = legacyPrefs.getBoolean("adultOnlyProvidersEnabled", false)

    fun adultContentPinIsConfigured(): Boolean =
        legacyPrefs.getString("adultContentPinHash", "").orEmpty().isNotBlank()

    suspend fun setAdultContentPin(pin: String) {
        updateSettings { it.copy(adultContentPinHash = hashPin(pin)) }
    }

    suspend fun clearAdultContentPin() {
        updateSettings { it.copy(adultContentPinHash = "") }
    }

    fun verifyAdultContentPin(pin: String): Boolean {
        val storedHash = legacyPrefs.getString("adultContentPinHash", "").orEmpty()
        if (storedHash.isBlank()) return true
        return storedHash == hashPin(pin)
    }

    suspend fun setPreferredChapterLanguage(language: AppLanguage) {
        updateSettings { it.copy(preferredChapterLanguage = language.name) }
    }

    fun preferredChapterLanguage(): AppLanguage {
        return AppLanguage.fromStored(
            legacyPrefs.getString("preferredChapterLanguage", AppLanguage.EN.name).orEmpty()
        ).takeIf {
            it != AppLanguage.MULTI
        } ?: AppLanguage.EN
    }

    fun isMangaBallAdultContentEnabled(): Boolean = legacyPrefs.getBoolean("adultContentEnabled", false)

    fun isManhwaLatinoAdultContentEnabled(): Boolean = legacyPrefs.getBoolean("adultContentEnabled", false)

    suspend fun setAppLanguage(language: AppLanguage) {
        updateSettings { it.copy(appLanguage = language.name) }
    }

    suspend fun selectedProviderId(): String {
        val settings = readSettings()
        return resolveSelectedProviderId(settings, parseDisabledProviderIds(settings.disabledProviderIdsJson))
    }

    suspend fun setSelectedProviderId(providerId: String) {
        updateSettings {
            val disabledProviderIds = parseDisabledProviderIds(it.disabledProviderIdsJson)
            val resolved = if (providerRegistry.isSelectable(providerId, disabledProviderIds, it.adultOnlyProvidersEnabled)) providerId else resolveSelectedProviderId(it, disabledProviderIds)
            it.copy(selectedProviderId = resolved)
        }
    }

    suspend fun setProviderEnabled(providerId: String, enabled: Boolean) {
        updateSettings {
            val currentDisabled = parseDisabledProviderIds(it.disabledProviderIdsJson).toMutableSet()
            if (enabled) currentDisabled.remove(providerId) else currentDisabled.add(providerId)
            val resolvedSelectedProviderId = resolveSelectedProviderId(
                it.copy(disabledProviderIdsJson = encodeDisabledProviderIds(currentDisabled)),
                currentDisabled,
            )
            it.copy(
                disabledProviderIdsJson = encodeDisabledProviderIds(currentDisabled),
                selectedProviderId = resolvedSelectedProviderId,
            )
        }
    }

    fun hasSeenProviderPicker(): Boolean = legacyPrefs.getBoolean("hasSeenProviderPicker", false)

    fun hasSeenProviderPickerFast(): Boolean = legacyPrefs.getBoolean("hasSeenProviderPicker", false)

    fun selectedProviderIdFast(): String = legacyPrefs.getString("selectedProviderId", "").orEmpty()

    fun appLanguageFast(): AppLanguage =
        AppLanguage.fromStored(legacyPrefs.getString("appLanguage", AppLanguage.EN.name).orEmpty())

    suspend fun setHasSeenProviderPicker(seen: Boolean) {
        updateSettings { it.copy(hasSeenProviderPicker = seen) }
    }

    suspend fun exportBackup(): String {
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

    suspend fun importBackup(
        payload: String,
        selectedProviderIdFallback: String = selectedProviderIdFast(),
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ) {
        ensureInitialized()
        val importedPayload = backupPayloadCodec.importPayload(payload, selectedProviderIdFallback)
        database.withTransaction {
            replaceFromImportedPayload(importedPayload, onProgress)
            val importedSettings = importedPayload.settings?.let(::JSONObject)
            onProgress(99, "Applying settings")
            updateSettings {
                val restoredHasSeenProviderPicker =
                    importedSettings?.optBoolean("hasSeenProviderPicker", it.hasSeenProviderPicker)
                        ?: it.hasSeenProviderPicker
                val importedAdultContentEnabled = importedSettings?.optBoolean("adultContentEnabled", it.adultContentEnabled) ?: it.adultContentEnabled
                val importedDisabledProviderIds = importedSettings?.optString("disabledProviderIdsJson").orEmpty().takeIf { value -> value.isNotBlank() }
                    ?.let(::parseDisabledProviderIds)
                    ?: parseDisabledProviderIds(it.disabledProviderIdsJson)
                val updatedSettings = it.copy(
                    selectedProviderId = importedPayload.selectedProviderId,
                    useDarkTheme = importedSettings?.optBoolean("useDarkTheme", it.useDarkTheme) ?: it.useDarkTheme,
                    autoJumpToUnread = importedSettings?.optBoolean("autoJumpToUnread", it.autoJumpToUnread) ?: it.autoJumpToUnread,
                    adultContentEnabled = importedAdultContentEnabled,
                    adultContentPinHash = importedSettings?.optString("adultContentPinHash").orEmpty().ifBlank { it.adultContentPinHash },
                    adultOnlyProvidersEnabled = importedSettings?.optBoolean("adultOnlyProvidersEnabled", it.adultOnlyProvidersEnabled) ?: it.adultOnlyProvidersEnabled,
                    disabledProviderIdsJson = encodeDisabledProviderIds(importedDisabledProviderIds),
                    mangaBallAdultContentEnabled = importedAdultContentEnabled,
                    manhwaLatinoAdultContentEnabled = importedAdultContentEnabled,
                    preferredChapterLanguage = importedSettings?.optString("preferredChapterLanguage").orEmpty().ifBlank { it.preferredChapterLanguage },
                    appLanguage = importedSettings?.optString("appLanguage").orEmpty().ifBlank { it.appLanguage },
                    hasSeenProviderPicker = restoredHasSeenProviderPicker || importedPayload.selectedProviderId.isNotBlank(),
                    legacyPrefsMigrated = true,
                )
                it.copy(
                    selectedProviderId = resolveSelectedProviderId(updatedSettings, importedDisabledProviderIds),
                    useDarkTheme = updatedSettings.useDarkTheme,
                    autoJumpToUnread = updatedSettings.autoJumpToUnread,
                    adultContentEnabled = updatedSettings.adultContentEnabled,
                    adultContentPinHash = updatedSettings.adultContentPinHash,
                    adultOnlyProvidersEnabled = updatedSettings.adultOnlyProvidersEnabled,
                    disabledProviderIdsJson = updatedSettings.disabledProviderIdsJson,
                    mangaBallAdultContentEnabled = updatedSettings.mangaBallAdultContentEnabled,
                    manhwaLatinoAdultContentEnabled = updatedSettings.manhwaLatinoAdultContentEnabled,
                    preferredChapterLanguage = updatedSettings.preferredChapterLanguage,
                    appLanguage = updatedSettings.appLanguage,
                    hasSeenProviderPicker = updatedSettings.hasSeenProviderPicker,
                    legacyPrefsMigrated = true,
                )
            }
        }
        onProgress(100, "Backup restored")
    }

    suspend fun exportDatabaseBackup(
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): ByteArray {
        ensureInitialized()
        val tempFile = createTempDatabaseBackupFile()
        try {
            onProgress(5, "Preparing database backup")
            SQLiteDatabase.openOrCreateDatabase(tempFile, null).use { backupDb ->
                backupDb.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        // Consume pragma result row on Android's SQLite wrapper.
                    }
                }
                createDatabaseBackupSchema(backupDb)
                backupDb.beginTransaction()
                try {
                    onProgress(12, "Writing backup metadata")
                    backupDb.insertOrThrow(
                        DATABASE_BACKUP_METADATA_TABLE,
                        null,
                        ContentValues().apply {
                            put("id", 0)
                            put("backup_version", DATABASE_BACKUP_VERSION)
                            put("format", "komastream-room")
                        },
                    )

                    onProgress(20, "Exporting favorites")
                    dao.readFavorites().forEach { entity ->
                        backupDb.insertOrThrow(FAVORITES_TABLE, null, entity.toContentValues())
                    }
                    onProgress(32, "Exporting reading list")
                    dao.readReading().forEach { entity ->
                        backupDb.insertOrThrow(READING_TABLE, null, entity.toContentValues())
                    }
                    onProgress(44, "Exporting read chapters")
                    dao.readChapters().forEach { entity ->
                        backupDb.insertOrThrow(READ_CHAPTERS_TABLE, null, entity.toContentValues())
                    }
                    onProgress(56, "Exporting chapter progress")
                    dao.readChapterProgress().forEach { entity ->
                        backupDb.insertOrThrow(CHAPTER_PROGRESS_TABLE, null, entity.toContentValues())
                    }
                    onProgress(68, "Exporting page counts")
                    dao.readChapterPageCounts().forEach { entity ->
                        backupDb.insertOrThrow(CHAPTER_PAGE_COUNTS_TABLE, null, entity.toContentValues())
                    }
                    onProgress(78, "Exporting settings")
                    backupDb.insertOrThrow(APP_SETTINGS_TABLE, null, readSettings().toContentValues())
                    onProgress(88, "Exporting detail cache")
                    dao.readMangaDetailCaches().forEach { entity ->
                        backupDb.insertOrThrow(MANGA_DETAIL_CACHE_TABLE, null, entity.toContentValues())
                    }
                    backupDb.setTransactionSuccessful()
                } finally {
                    backupDb.endTransaction()
                }
            }
            return tempFile.readBytes()
        } finally {
            tempFile.delete()
        }
    }

    suspend fun importDatabaseBackup(
        payload: ByteArray,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ) {
        ensureInitialized()
        val tempFile = createTempDatabaseBackupFile()
        try {
            tempFile.writeBytes(payload)
            SQLiteDatabase.openDatabase(tempFile.path, null, SQLiteDatabase.OPEN_READONLY).use { backupDb ->
                validateDatabaseBackup(backupDb)
                onProgress(60, "Reading database backup")
                val favorites = backupDb.readFavoritesBackup()
                val reading = backupDb.readReadingBackup()
                val readChapters = backupDb.readReadChaptersBackup()
                val chapterProgress = backupDb.readChapterProgressBackup()
                val pageCounts = backupDb.readChapterPageCountsBackup()
                val settings = backupDb.readAppSettingsBackup()
                val detailCache = backupDb.readMangaDetailCacheBackup()

                onProgress(82, "Applying database backup")
                database.withTransaction {
                    dao.clearFavorites()
                    dao.clearReading()
                    dao.clearReadChapters()
                    dao.clearChapterProgress()
                    dao.clearChapterPageCounts()
                    dao.clearMangaDetailCache()

                    dao.upsertFavorites(favorites)
                    dao.upsertReadings(reading)
                    readChapters.forEach { dao.upsertReadChapter(it) }
                    chapterProgress.forEach { dao.upsertChapterProgress(it) }
                    pageCounts.forEach { dao.upsertChapterPageCount(it) }
                    detailCache.forEach { dao.upsertMangaDetailCache(it) }
                    val restoredSettings = settings.copy(
                        hasSeenProviderPicker = settings.hasSeenProviderPicker || settings.selectedProviderId.isNotBlank(),
                        legacyPrefsMigrated = true,
                    )
                    dao.upsertSettings(restoredSettings)
                    syncBootstrapPrefs(restoredSettings)
                }
                onProgress(100, "Database restored")
            }
        } finally {
            tempFile.delete()
        }
    }

    suspend fun saveChapterProgress(providerId: String, chapterPath: String, pageIndex: Int) {
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

    suspend fun getChapterProgress(providerId: String, chapterPath: String): Int {
        ensureInitialized()
        val canonicalPath = canonicalChapterKey(providerId, chapterPath)
        if (canonicalPath.isBlank()) return 0
        return dao.readChapterProgress(providerId, canonicalPath)?.pageIndex?.coerceAtLeast(0) ?: 0
    }

    suspend fun saveChapterPageCount(providerId: String, chapterPath: String, pageCount: Int) {
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

    suspend fun getChapterPageCount(providerId: String, chapterPath: String): Int {
        ensureInitialized()
        val canonicalPath = canonicalChapterKey(providerId, chapterPath)
        if (canonicalPath.isBlank()) return 0
        return dao.readChapterPageCount(providerId, canonicalPath)?.pageCount?.coerceAtLeast(0) ?: 0
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            runBlocking {
                database.withTransaction {
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
                    dao.readSettings()?.let(::syncBootstrapPrefs)
                }
            }
            initialized = true
        }
    }

    private suspend fun migrateFromLegacyPrefs() {
        val settingsJson = JSONObject()
            .put("selectedProviderId", legacyPrefs.getString("selectedProviderId", "").orEmpty())
            .put("useDarkTheme", legacyPrefs.getBoolean("useDarkTheme", false))
            .put("autoJumpToUnread", legacyPrefs.getBoolean("autoJumpToUnread", true))
            .put("adultContentEnabled", legacyPrefs.getBoolean(KEY_MANGABALL_ADULT_CONTENT, false))
            .put("adultContentPinHash", "")
            .put("adultOnlyProvidersEnabled", false)
            .put("disabledProviderIdsJson", "[]")
            .put("mangaBallAdultContentEnabled", legacyPrefs.getBoolean(KEY_MANGABALL_ADULT_CONTENT, false))
            .put("manhwaLatinoAdultContentEnabled", legacyPrefs.getBoolean(KEY_MANGABALL_ADULT_CONTENT, false))
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
        replaceFromImportedPayload(
            backupPayloadCodec.importPayload(payload, defaultProviderId),
            onProgress = { _, _ -> },
        )
        dao.upsertSettings(
            defaultSettings(
                selectedProviderId = legacyPrefs.getString("selectedProviderId", "").orEmpty(),
                useDarkTheme = legacyPrefs.getBoolean("useDarkTheme", false),
                autoJumpToUnread = legacyPrefs.getBoolean("autoJumpToUnread", true),
                adultContentEnabled = legacyPrefs.getBoolean(KEY_MANGABALL_ADULT_CONTENT, false),
                adultContentPinHash = "",
                adultOnlyProvidersEnabled = false,
                disabledProviderIdsJson = "[]",
                mangaBallAdultContentEnabled = legacyPrefs.getBoolean(KEY_MANGABALL_ADULT_CONTENT, false),
                manhwaLatinoAdultContentEnabled = legacyPrefs.getBoolean(KEY_MANGABALL_ADULT_CONTENT, false),
                preferredChapterLanguage = AppLanguage.EN.name,
                appLanguage = legacyPrefs.getString("appLanguage", AppLanguage.EN.name).orEmpty(),
                hasSeenProviderPicker = legacyPrefs.getBoolean("hasSeenProviderPicker", false),
                legacyPrefsMigrated = true,
            )
        )
    }

    private suspend fun replaceFromImportedPayload(
        importedPayload: com.paudinc.komastream.data.repository.ImportedLibraryPayload,
        onProgress: (Int, String) -> Unit,
    ) {
        onProgress(10, "Clearing existing data")
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

        onProgress(20, "Importing favorites")
        favorites.mapIndexed { index, manga ->
            manga.toFavoriteEntity(orderIndex = (favorites.size - index).toLong())
        }.forEach { dao.upsertFavorite(it) }
        onProgress(30, "Importing reading list")
        reading.mapIndexed { index, manga ->
            manga.toReadingEntity(orderIndex = (reading.size - index).toLong())
        }.forEach { dao.upsertReading(it) }

        onProgress(40, "Restoring linked progress")
        (favorites + reading).forEach { synchronizeLinkedProgress(it) }

        onProgress(50, "Importing read chapters")
        importQualifiedChapterPaths(importedPayload.readChapters)
        onProgress(60, "Importing chapter progress")
        importProgressMap(importedPayload.readProgress)
        onProgress(70, "Importing page counts")
        importPageCountMap(importedPayload.chapterPageCounts)
        onProgress(85, "Importing cached details")
        importMangaDetailCache(importedPayload.mangaDetailCache)
        onProgress(95, "Reconciling statuses")
        reconcileImportedFavoriteStatuses(legacyBackup)
    }

    private suspend fun importMangaDetailCache(serializedValue: String) {
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

    private suspend fun reconcileImportedFavoriteStatuses(legacyBackup: Boolean) {
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
            val totalChapterCount = chapterCountForProvider(entity.providerId, cachedDetail.chapters)
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

    private suspend fun importQualifiedChapterPaths(serializedValue: String) {
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

    private suspend fun importProgressMap(serializedValue: String) {
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

    private suspend fun importPageCountMap(serializedValue: String) {
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

    private suspend fun replaceFavoriteEntities(items: List<FavoriteMangaEntity>) {
        dao.clearFavorites()
        items.forEach { dao.upsertFavorite(it) }
    }

    private suspend fun replaceReadingEntities(items: List<ReadingMangaEntity>) {
        dao.clearReading()
        items.forEach { dao.upsertReading(it) }
    }

    private suspend fun synchronizeLinkedProgress(source: SavedManga) {
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

    private suspend fun replaceReadChapterEntries(qualifiedPaths: List<String>) {
        dao.clearReadChapters()
        qualifiedPaths.mapIndexed { index, qualified ->
            val providerId = qualified.substringBefore("::", missingDelimiterValue = defaultProviderId)
            val chapterPath = qualified.substringAfter("::", missingDelimiterValue = qualified)
            ReadChapterEntity(
                providerId = providerId,
                chapterPath = canonicalChapterKey(providerId, chapterPath),
                readOrder = (qualifiedPaths.size - index).toLong(),
            )
        }.forEach { dao.upsertReadChapter(it) }
    }

    private suspend fun updateSettings(transform: (AppSettingsEntity) -> AppSettingsEntity) {
        ensureInitialized()
        val current = readSettings()
        dao.upsertSettings(transform(current).also(::syncBootstrapPrefs))
    }

    private suspend fun readSettings(): AppSettingsEntity {
        val settings = dao.readSettings()
        return settings ?: defaultSettings(legacyPrefsMigrated = false)
    }

    private fun syncBootstrapPrefs(settings: AppSettingsEntity) {
        legacyPrefs.edit()
            .putString("selectedProviderId", settings.selectedProviderId)
            .putBoolean("hasSeenProviderPicker", settings.hasSeenProviderPicker)
            .putString("appLanguage", settings.appLanguage)
            .putString("preferredChapterLanguage", settings.preferredChapterLanguage)
            .putBoolean("adultContentEnabled", settings.adultContentEnabled)
            .putBoolean("adultOnlyProvidersEnabled", settings.adultOnlyProvidersEnabled)
            .putString("adultContentPinHash", settings.adultContentPinHash)
            .commit()
    }

    private fun resolveSelectedProviderId(settings: AppSettingsEntity, disabledProviderIds: Set<String>): String {
        val selected = settings.selectedProviderId
        if (providerRegistry.isSelectable(selected, disabledProviderIds, settings.adultOnlyProvidersEnabled)) {
            return selected
        }
        return providerRegistry.selectableProviderId(disabledProviderIds, settings.adultOnlyProvidersEnabled)
    }

    private fun parseDisabledProviderIds(value: String): Set<String> {
        return runCatching {
            JSONArray(value).let { array ->
                buildSet {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun encodeDisabledProviderIds(ids: Set<String>): String =
        JSONArray().apply {
            ids.sorted().forEach(::put)
        }.toString()

    private fun hashPin(pin: String): String {
        val normalized = pin.trim()
        if (normalized.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun defaultSettings(
        selectedProviderId: String = "",
        useDarkTheme: Boolean = false,
        autoJumpToUnread: Boolean = true,
        adultContentEnabled: Boolean = false,
        adultContentPinHash: String = "",
        adultOnlyProvidersEnabled: Boolean = false,
        disabledProviderIdsJson: String = "[]",
        mangaBallAdultContentEnabled: Boolean = false,
        manhwaLatinoAdultContentEnabled: Boolean = false,
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
            adultContentEnabled = adultContentEnabled,
            adultContentPinHash = adultContentPinHash,
            adultOnlyProvidersEnabled = adultOnlyProvidersEnabled,
            disabledProviderIdsJson = disabledProviderIdsJson,
            mangaBallAdultContentEnabled = mangaBallAdultContentEnabled,
            manhwaLatinoAdultContentEnabled = manhwaLatinoAdultContentEnabled,
            preferredChapterLanguage = preferredChapterLanguage,
            appLanguage = appLanguage,
            hasSeenProviderPicker = hasSeenProviderPicker,
            legacyPrefsMigrated = legacyPrefsMigrated,
            favoriteStatusBackfillDone = favoriteStatusBackfillDone,
        )
    }

    private fun buildLibraryState(
        favorites: List<SavedManga>,
        reading: List<SavedManga>,
        readChapters: Set<String>,
        settings: AppSettingsEntity,
        disabledProviderIds: Set<String>,
        selectedProviderId: String,
    ): LibraryState {
        return LibraryState(
            favorites = favorites,
            reading = reading,
            readChapters = readChapters,
            useDarkTheme = settings.useDarkTheme,
            autoJumpToUnread = settings.autoJumpToUnread,
            adultContentEnabled = settings.adultContentEnabled,
            adultOnlyProvidersEnabled = settings.adultOnlyProvidersEnabled,
            disabledProviderIds = disabledProviderIds,
            mangaBallAdultContentEnabled = settings.adultContentEnabled,
            manhwaLatinoAdultContentEnabled = settings.adultContentEnabled,
            preferredChapterLanguage = AppLanguage.fromStored(settings.preferredChapterLanguage).takeIf {
                it != AppLanguage.MULTI
            } ?: AppLanguage.EN,
            selectedProviderId = selectedProviderId,
            appLanguage = AppLanguage.fromStored(settings.appLanguage),
        )
    }

    private suspend fun runFavoriteStatusBackfillIfNeeded() {
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
            .put("adultContentEnabled", settings.adultContentEnabled)
            .put("adultContentPinHash", settings.adultContentPinHash)
            .put("adultOnlyProvidersEnabled", settings.adultOnlyProvidersEnabled)
            .put("disabledProviderIdsJson", settings.disabledProviderIdsJson)
            .put("mangaBallAdultContentEnabled", settings.mangaBallAdultContentEnabled)
            .put("manhwaLatinoAdultContentEnabled", settings.manhwaLatinoAdultContentEnabled)
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

    private fun nextOrderIndex(currentMaxOrder: Long?): Long = (currentMaxOrder ?: 0L) + 1L

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

    private fun qualifyMangaLookupKey(providerId: String, detailPath: String): String {
        return "$providerId::${canonicalMangaPathKey(providerId, detailPath)}"
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

    private fun createTempDatabaseBackupFile(): File =
        File.createTempFile("komastream_backup_", ".db", appContext.cacheDir)

    private fun createDatabaseBackupSchema(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $DATABASE_BACKUP_METADATA_TABLE (
                id INTEGER PRIMARY KEY NOT NULL,
                backup_version INTEGER NOT NULL,
                format TEXT NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $FAVORITES_TABLE (
                provider_id TEXT NOT NULL,
                detail_path TEXT NOT NULL COLLATE NOCASE,
                title TEXT NOT NULL,
                cover_url TEXT NOT NULL,
                favorite_status TEXT NOT NULL,
                last_chapter_title TEXT NOT NULL,
                last_chapter_path TEXT NOT NULL,
                last_progress_chapter_number REAL,
                mal_manga_id INTEGER,
                last_read_chapter_number INTEGER,
                order_index INTEGER NOT NULL,
                PRIMARY KEY(provider_id, detail_path)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $READING_TABLE (
                provider_id TEXT NOT NULL,
                detail_path TEXT NOT NULL COLLATE NOCASE,
                title TEXT NOT NULL,
                cover_url TEXT NOT NULL,
                last_chapter_title TEXT NOT NULL,
                last_chapter_path TEXT NOT NULL,
                last_progress_chapter_number REAL,
                mal_manga_id INTEGER,
                last_read_chapter_number INTEGER,
                order_index INTEGER NOT NULL,
                PRIMARY KEY(provider_id, detail_path)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $READ_CHAPTERS_TABLE (
                provider_id TEXT NOT NULL,
                chapter_path TEXT NOT NULL,
                read_order INTEGER NOT NULL,
                PRIMARY KEY(provider_id, chapter_path)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $CHAPTER_PROGRESS_TABLE (
                provider_id TEXT NOT NULL,
                chapter_path TEXT NOT NULL,
                page_index INTEGER NOT NULL,
                PRIMARY KEY(provider_id, chapter_path)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $CHAPTER_PAGE_COUNTS_TABLE (
                provider_id TEXT NOT NULL,
                chapter_path TEXT NOT NULL,
                page_count INTEGER NOT NULL,
                PRIMARY KEY(provider_id, chapter_path)
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $APP_SETTINGS_TABLE (
                id INTEGER PRIMARY KEY NOT NULL,
                selected_provider_id TEXT NOT NULL,
                use_dark_theme INTEGER NOT NULL,
                auto_jump_to_unread INTEGER NOT NULL,
                mangaball_adult_content_enabled INTEGER NOT NULL,
                manhwa_latino_adult_content_enabled INTEGER NOT NULL,
                app_language TEXT NOT NULL,
                preferred_chapter_language TEXT NOT NULL,
                has_seen_provider_picker INTEGER NOT NULL,
                legacy_prefs_migrated INTEGER NOT NULL,
                favorite_status_backfill_done INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $MANGA_DETAIL_CACHE_TABLE (
                provider_id TEXT NOT NULL,
                detail_key TEXT NOT NULL,
                detail_path TEXT NOT NULL COLLATE NOCASE,
                detail_json TEXT NOT NULL,
                chapter_count INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(provider_id, detail_key)
            )
            """.trimIndent()
        )
    }

    private fun validateDatabaseBackup(database: SQLiteDatabase) {
        val requiredTables = setOf(
            FAVORITES_TABLE,
            READING_TABLE,
            READ_CHAPTERS_TABLE,
            CHAPTER_PROGRESS_TABLE,
            CHAPTER_PAGE_COUNTS_TABLE,
            APP_SETTINGS_TABLE,
            MANGA_DETAIL_CACHE_TABLE,
        )
        val availableTables = buildSet {
            database.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }
        if (!requiredTables.all { it in availableTables }) {
            error("Invalid database backup")
        }
    }

    private companion object {
        private const val KEY_MANGABALL_ADULT_CONTENT = "mangaballAdultContentEnabled"
        private const val MAX_CACHED_DETAIL_PAYLOAD_SIZE = 900_000
        private const val DATABASE_BACKUP_VERSION = 1
        private const val DATABASE_BACKUP_METADATA_TABLE = "backup_metadata"
        private const val FAVORITES_TABLE = "favorite_manga"
        private const val READING_TABLE = "reading_manga"
        private const val READ_CHAPTERS_TABLE = "read_chapters"
        private const val CHAPTER_PROGRESS_TABLE = "chapter_progress"
        private const val CHAPTER_PAGE_COUNTS_TABLE = "chapter_page_counts"
        private const val APP_SETTINGS_TABLE = "app_settings"
        private const val MANGA_DETAIL_CACHE_TABLE = "manga_detail_cache"
    }
}

private fun FavoriteMangaEntity.toContentValues(): ContentValues = ContentValues().apply {
    put("provider_id", providerId)
    put("detail_path", detailPath)
    put("title", title)
    put("cover_url", coverUrl)
    put("favorite_status", favoriteStatus)
    put("last_chapter_title", lastChapterTitle)
    put("last_chapter_path", lastChapterPath)
    put("last_progress_chapter_number", lastProgressChapterNumber)
    put("mal_manga_id", malMangaId)
    put("last_read_chapter_number", lastReadChapterNumber)
    put("order_index", orderIndex)
}

private fun ReadingMangaEntity.toContentValues(): ContentValues = ContentValues().apply {
    put("provider_id", providerId)
    put("detail_path", detailPath)
    put("title", title)
    put("cover_url", coverUrl)
    put("last_chapter_title", lastChapterTitle)
    put("last_chapter_path", lastChapterPath)
    put("last_progress_chapter_number", lastProgressChapterNumber)
    put("mal_manga_id", malMangaId)
    put("last_read_chapter_number", lastReadChapterNumber)
    put("order_index", orderIndex)
}

private fun ReadChapterEntity.toContentValues(): ContentValues = ContentValues().apply {
    put("provider_id", providerId)
    put("chapter_path", chapterPath)
    put("read_order", readOrder)
}

private fun ChapterProgressEntity.toContentValues(): ContentValues = ContentValues().apply {
    put("provider_id", providerId)
    put("chapter_path", chapterPath)
    put("page_index", pageIndex)
}

private fun ChapterPageCountEntity.toContentValues(): ContentValues = ContentValues().apply {
    put("provider_id", providerId)
    put("chapter_path", chapterPath)
    put("page_count", pageCount)
}

private fun AppSettingsEntity.toContentValues(): ContentValues = ContentValues().apply {
    put("id", id)
    put("selected_provider_id", selectedProviderId)
    put("use_dark_theme", useDarkTheme)
    put("auto_jump_to_unread", autoJumpToUnread)
    put("adult_content_enabled", adultContentEnabled)
    put("adult_content_pin_hash", adultContentPinHash)
    put("adult_only_providers_enabled", adultOnlyProvidersEnabled)
    put("disabled_provider_ids_json", disabledProviderIdsJson)
    put("mangaball_adult_content_enabled", mangaBallAdultContentEnabled)
    put("manhwa_latino_adult_content_enabled", manhwaLatinoAdultContentEnabled)
    put("app_language", appLanguage)
    put("preferred_chapter_language", preferredChapterLanguage)
    put("has_seen_provider_picker", hasSeenProviderPicker)
    put("legacy_prefs_migrated", legacyPrefsMigrated)
    put("favorite_status_backfill_done", favoriteStatusBackfillDone)
}

private fun MangaDetailCacheEntity.toContentValues(): ContentValues = ContentValues().apply {
    put("provider_id", providerId)
    put("detail_key", detailKey)
    put("detail_path", detailPath)
    put("detail_json", detailJson)
    put("chapter_count", chapterCount)
    put("updated_at", updatedAt)
}

private fun SQLiteDatabase.readFavoritesBackup(): List<FavoriteMangaEntity> =
    query("favorite_manga", null, null, null, null, null, "order_index DESC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    FavoriteMangaEntity(
                        providerId = cursor.getString(cursor.getColumnIndexOrThrow("provider_id")),
                        detailPath = cursor.getString(cursor.getColumnIndexOrThrow("detail_path")),
                        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")),
                        favoriteStatus = cursor.getString(cursor.getColumnIndexOrThrow("favorite_status")),
                        lastChapterTitle = cursor.getString(cursor.getColumnIndexOrThrow("last_chapter_title")),
                        lastChapterPath = cursor.getString(cursor.getColumnIndexOrThrow("last_chapter_path")),
                        lastProgressChapterNumber = cursor.getDoubleOrNull("last_progress_chapter_number"),
                        malMangaId = cursor.getLongOrNull("mal_manga_id"),
                        lastReadChapterNumber = cursor.getIntOrNull("last_read_chapter_number"),
                        orderIndex = cursor.getLong(cursor.getColumnIndexOrThrow("order_index")),
                    )
                )
            }
        }
    }

private fun SQLiteDatabase.readReadingBackup(): List<ReadingMangaEntity> =
    query("reading_manga", null, null, null, null, null, "order_index DESC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ReadingMangaEntity(
                        providerId = cursor.getString(cursor.getColumnIndexOrThrow("provider_id")),
                        detailPath = cursor.getString(cursor.getColumnIndexOrThrow("detail_path")),
                        title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                        coverUrl = cursor.getString(cursor.getColumnIndexOrThrow("cover_url")),
                        lastChapterTitle = cursor.getString(cursor.getColumnIndexOrThrow("last_chapter_title")),
                        lastChapterPath = cursor.getString(cursor.getColumnIndexOrThrow("last_chapter_path")),
                        lastProgressChapterNumber = cursor.getDoubleOrNull("last_progress_chapter_number"),
                        malMangaId = cursor.getLongOrNull("mal_manga_id"),
                        lastReadChapterNumber = cursor.getIntOrNull("last_read_chapter_number"),
                        orderIndex = cursor.getLong(cursor.getColumnIndexOrThrow("order_index")),
                    )
                )
            }
        }
    }

private fun SQLiteDatabase.readReadChaptersBackup(): List<ReadChapterEntity> =
    query("read_chapters", null, null, null, null, null, "read_order DESC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ReadChapterEntity(
                        providerId = cursor.getString(cursor.getColumnIndexOrThrow("provider_id")),
                        chapterPath = cursor.getString(cursor.getColumnIndexOrThrow("chapter_path")),
                        readOrder = cursor.getLong(cursor.getColumnIndexOrThrow("read_order")),
                    )
                )
            }
        }
    }

private fun SQLiteDatabase.readChapterProgressBackup(): List<ChapterProgressEntity> =
    query("chapter_progress", null, null, null, null, null, null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ChapterProgressEntity(
                        providerId = cursor.getString(cursor.getColumnIndexOrThrow("provider_id")),
                        chapterPath = cursor.getString(cursor.getColumnIndexOrThrow("chapter_path")),
                        pageIndex = cursor.getInt(cursor.getColumnIndexOrThrow("page_index")),
                    )
                )
            }
        }
    }

private fun SQLiteDatabase.readChapterPageCountsBackup(): List<ChapterPageCountEntity> =
    query("chapter_page_counts", null, null, null, null, null, null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    ChapterPageCountEntity(
                        providerId = cursor.getString(cursor.getColumnIndexOrThrow("provider_id")),
                        chapterPath = cursor.getString(cursor.getColumnIndexOrThrow("chapter_path")),
                        pageCount = cursor.getInt(cursor.getColumnIndexOrThrow("page_count")),
                    )
                )
            }
        }
    }

private fun SQLiteDatabase.readAppSettingsBackup(): AppSettingsEntity =
    query("app_settings", null, "id = 0", null, null, null, null).use { cursor ->
        if (!cursor.moveToFirst()) {
            return@use AppSettingsEntity()
        }
        AppSettingsEntity(
            id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
            selectedProviderId = cursor.getString(cursor.getColumnIndexOrThrow("selected_provider_id")),
            useDarkTheme = cursor.getInt(cursor.getColumnIndexOrThrow("use_dark_theme")) != 0,
            autoJumpToUnread = cursor.getInt(cursor.getColumnIndexOrThrow("auto_jump_to_unread")) != 0,
            adultContentEnabled = cursor.getColumnIndex("adult_content_enabled").takeIf { it >= 0 }
                ?.let { cursor.getInt(it) != 0 }
                ?: false,
            adultContentPinHash = cursor.getColumnIndex("adult_content_pin_hash").takeIf { it >= 0 }
                ?.let { cursor.getString(it).orEmpty() }
                ?: "",
            adultOnlyProvidersEnabled = cursor.getColumnIndex("adult_only_providers_enabled").takeIf { it >= 0 }
                ?.let { cursor.getInt(it) != 0 }
                ?: false,
            disabledProviderIdsJson = cursor.getColumnIndex("disabled_provider_ids_json").takeIf { it >= 0 }
                ?.let { cursor.getString(it).orEmpty() }
                ?: "[]",
            mangaBallAdultContentEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("mangaball_adult_content_enabled")) != 0,
            manhwaLatinoAdultContentEnabled = cursor.getColumnIndex("manhwa_latino_adult_content_enabled").takeIf { it >= 0 }
                ?.let { cursor.getInt(it) != 0 }
                ?: false,
            appLanguage = cursor.getString(cursor.getColumnIndexOrThrow("app_language")),
            preferredChapterLanguage = cursor.getString(cursor.getColumnIndexOrThrow("preferred_chapter_language")),
            hasSeenProviderPicker = cursor.getInt(cursor.getColumnIndexOrThrow("has_seen_provider_picker")) != 0,
            legacyPrefsMigrated = cursor.getInt(cursor.getColumnIndexOrThrow("legacy_prefs_migrated")) != 0,
            favoriteStatusBackfillDone = cursor.getInt(cursor.getColumnIndexOrThrow("favorite_status_backfill_done")) != 0,
        )
    }

private fun SQLiteDatabase.readMangaDetailCacheBackup(): List<MangaDetailCacheEntity> =
    query("manga_detail_cache", null, null, null, null, null, "updated_at DESC").use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    MangaDetailCacheEntity(
                        providerId = cursor.getString(cursor.getColumnIndexOrThrow("provider_id")),
                        detailKey = cursor.getString(cursor.getColumnIndexOrThrow("detail_key")),
                        detailPath = cursor.getString(cursor.getColumnIndexOrThrow("detail_path")),
                        detailJson = cursor.getString(cursor.getColumnIndexOrThrow("detail_json")),
                        chapterCount = cursor.getInt(cursor.getColumnIndexOrThrow("chapter_count")),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                    )
                )
            }
        }
    }

private fun android.database.Cursor.getDoubleOrNull(columnName: String): Double? {
    val index = getColumnIndexOrThrow(columnName)
    return if (isNull(index)) null else getDouble(index)
}

private fun android.database.Cursor.getLongOrNull(columnName: String): Long? {
    val index = getColumnIndexOrThrow(columnName)
    return if (isNull(index)) null else getLong(index)
}

private fun android.database.Cursor.getIntOrNull(columnName: String): Int? {
    val index = getColumnIndexOrThrow(columnName)
    return if (isNull(index)) null else getInt(index)
}
