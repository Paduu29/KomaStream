package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.ChapterSummary
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.HomeSectionType
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.ui.components.ChapterRow
import com.paudinc.komastream.ui.components.EmptyCard
import com.paudinc.komastream.ui.components.MangaCoverCard
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.canonicalChapterKey
import com.paudinc.komastream.utils.sameChapterPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeSectionScreen(
    sectionId: String,
    feed: HomeFeed?,
    provider: MangaProvider,
    reading: List<SavedManga>,
    readChapters: Set<String>,
    chapterProgress: (String, String) -> Int,
    strings: AppStrings,
    onOpenManga: (String, String) -> Unit,
    onOpenChapter: (String, String) -> Unit,
    onAddToReading: (SavedManga) -> Unit,
    onToggleFavorite: (SavedManga) -> Unit,
    isFavorite: (String, String) -> Boolean,
) {
    val section = remember(feed, sectionId) { feed?.sections?.firstOrNull { it.id == sectionId } }
    if (section == null) {
        EmptyCard(strings.emptyProviderHome(""))
        return
    }
    val stateKey = "${provider.id}:$sectionId"
    val scope = rememberCoroutineScope()
    val listState = rememberSaveable(stateKey, saver = LazyListState.Saver) {
        LazyListState()
    }
    var mangaItems by rememberSaveable(stateKey, stateSaver = MangaSummaryListSaver) {
        mutableStateOf(section.mangas)
    }
    var chapterItems by rememberSaveable(stateKey, stateSaver = ChapterSummaryListSaver) {
        mutableStateOf(section.chapters)
    }
    var currentPage by rememberSaveable(stateKey) { mutableStateOf(1) }
    var isLoadingMore by rememberSaveable(stateKey) { mutableStateOf(false) }
    var hasMore by rememberSaveable(stateKey) {
        mutableStateOf(
            providerSupportsHomePaging(provider.id, sectionId)
        )
    }
    var initializedSectionSignature by rememberSaveable(stateKey) { mutableStateOf("") }
    val sectionSignature = remember(provider.id, sectionId, section) {
        buildHomeSectionSignature(provider.id, sectionId, section)
    }

    LaunchedEffect(sectionSignature) {
        if (initializedSectionSignature == sectionSignature) return@LaunchedEffect
        val initialHasMore = when {
            provider.id == "leermangaesp-es" && sectionId == "populares" -> section.mangas.size >= 20
            provider.id == "leermangaesp-es" && sectionId == "capitulos-recientes" -> section.chapters.size >= 20
            provider.id == "mangatube-de" && sectionId == "latest-updates" -> section.chapters.size >= 40
            provider.id == "manhwa-latino-es" && sectionId == "featured" -> section.mangas.size >= 20
            provider.id == "manhwa-latino-es" && sectionId == "latest-updates" -> section.chapters.size >= 20
            provider.id == "olympusbiblioteca-es" && sectionId == "nuevos-lanzamientos" -> section.mangas.size >= 15
            provider.id == "olympusbiblioteca-es" && sectionId == "top-series" -> section.mangas.size >= 15
            else -> false
        }
        mangaItems = section.mangas
        chapterItems = section.chapters
        currentPage = 1
        isLoadingMore = false
        hasMore = initialHasMore
        listState.scrollToItem(0)

        val shouldRefreshFromPagedSource =
            provider.id == "olympusbiblioteca-es" &&
                (sectionId == "nuevos-lanzamientos" || sectionId == "top-series")
        if (shouldRefreshFromPagedSource) {
            val refreshed = runCatching {
                withContext(Dispatchers.IO) { provider.fetchHomeSectionPage(sectionId, 1) }
            }.getOrNull()
            if (refreshed != null && (refreshed.mangas.isNotEmpty() || refreshed.chapters.isNotEmpty())) {
                mangaItems = refreshed.mangas
                chapterItems = refreshed.chapters
                currentPage = 1
                hasMore = refreshed.hasMore
            }
        }
        initializedSectionSignature = sectionSignature
    }

    val canonicalReadChapterKeys = remember(sectionId, readChapters) {
        readChapters.map { canonicalChapterKey(section.chapters.firstOrNull()?.providerId ?: "", it) }.toSet()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = homeSectionTitle(section, strings),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Text(
                text = homeSectionSubtitle(section, strings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (section.type) {
            HomeSectionType.MANGAS -> {
                itemsIndexed(
                    mangaItems,
                    key = { index: Int, manga: MangaSummary ->
                        "${manga.providerId}:${mangaSectionItemKey(provider.id, sectionId, index, manga)}"
                    },
                ) { _: Int, manga: MangaSummary ->
                    MangaCoverCard(
                        manga = manga,
                        strings = strings,
                        constrained = false,
                        favoriteActionLabel = if (isFavorite(manga.providerId, manga.detailPath)) strings.removeFromFavorites else strings.addToFavorites,
                        onClick = { onOpenManga(manga.providerId, manga.detailPath) },
                        onFavoriteAction = {
                            onToggleFavorite(
                                SavedManga(
                                    providerId = manga.providerId,
                                    title = manga.title,
                                    detailPath = manga.detailPath,
                                    coverUrl = manga.coverUrl,
                                )
                            )
                        },
                        onOpenMangaAction = { onOpenManga(manga.providerId, manga.detailPath) },
                    )
                }
                if (hasMore) {
                    item {
                        Button(
                            onClick = {
                                if (isLoadingMore) return@Button
                                isLoadingMore = true
                                scope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            provider.fetchHomeSectionPage(sectionId, currentPage + 1)
                                        }
                                    }
                                        .getOrNull()
                                    val newItems = result?.mangas.orEmpty()
                                        .let { incomingItems ->
                                            if (provider.id == "olympusbiblioteca-es" &&
                                                (sectionId == "nuevos-lanzamientos" || sectionId == "top-series")
                                            ) {
                                                incomingItems
                                            } else {
                                                incomingItems.filterNot { incoming ->
                                                    mangaItems.any { existing ->
                                                        mangaIdentityKey(provider.id, sectionId, existing) ==
                                                            mangaIdentityKey(provider.id, sectionId, incoming)
                                                    }
                                                }
                                            }
                                        }
                                    mangaItems = mangaItems + newItems
                                    if (result != null) currentPage += 1
                                    hasMore = result?.hasMore == true && newItems.isNotEmpty()
                                    isLoadingMore = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text(if (isLoadingMore) strings.loadingProviderHome(provider.displayName) else strings.loadMore)
                        }
                    }
                }
            }
            HomeSectionType.CHAPTERS -> {
                items(chapterItems, key = { "${it.providerId}:${it.chapterPath}" }) { chapter ->
                    val progress = chapterProgress(chapter.providerId, chapter.chapterPath)
                    val isCurrentReadingEntry = reading.any { saved ->
                        saved.providerId == chapter.providerId &&
                            sameChapterPath(chapter.providerId, saved.lastChapterPath, chapter.chapterPath)
                    }
                    val isRead = canonicalChapterKey(chapter.providerId, chapter.chapterPath) in canonicalReadChapterKeys
                    ChapterRow(
                        item = chapter,
                        strings = strings,
                        actionLabel = when {
                            isCurrentReadingEntry && progress <= 0 -> strings.continueReadingAction
                            progress > 0 && !isRead -> strings.continueReadingAction
                            isRead -> strings.chapterReadAction
                            else -> strings.read
                        },
                        onOpenChapter = onOpenChapter,
                        onAddToReading = {
                            onAddToReading(
                                SavedManga(
                                    providerId = chapter.providerId,
                                    title = chapter.mangaTitle,
                                    detailPath = chapter.mangaPath,
                                    coverUrl = chapter.coverUrl,
                                    lastChapterTitle = strings.chapterLabelWithNumber(chapter),
                                    lastChapterPath = chapter.chapterPath,
                                )
                            )
                        },
                        onOpenManga = { onOpenManga(chapter.providerId, chapter.mangaPath) },
                    )
                }
                if (hasMore) {
                    item {
                        Button(
                            onClick = {
                                if (isLoadingMore) return@Button
                                isLoadingMore = true
                                scope.launch {
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            provider.fetchHomeSectionPage(sectionId, currentPage + 1)
                                        }
                                    }
                                        .getOrNull()
                                    val newItems = result?.chapters.orEmpty()
                                        .filterNot { incoming -> chapterItems.any { it.chapterPath == incoming.chapterPath } }
                                    chapterItems = chapterItems + newItems
                                    if (result != null) currentPage += 1
                                    hasMore = result?.hasMore == true && newItems.isNotEmpty()
                                    isLoadingMore = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text(if (isLoadingMore) strings.loadingProviderHome(provider.displayName) else strings.loadMore)
                        }
                    }
                }
            }
        }
    }
}

private fun providerSupportsHomePaging(providerId: String, sectionId: String): Boolean =
    when (providerId) {
        "leermangaesp-es" -> sectionId == "populares" || sectionId == "capitulos-recientes"
        "mangatube-de" -> sectionId == "latest-updates"
        "manhwa-latino-es" -> sectionId == "featured" || sectionId == "latest-updates"
        "olympusbiblioteca-es" -> sectionId == "nuevos-lanzamientos" || sectionId == "top-series"
        else -> false
    }

private fun mangaIdentityKey(providerId: String, sectionId: String, manga: com.paudinc.komastream.data.model.MangaSummary): String =
    when {
        providerId == "olympusbiblioteca-es" && sectionId == "nuevos-lanzamientos" ->
            listOf(manga.detailPath, manga.latestPublication, manga.status).joinToString("|")
        else -> manga.detailPath
    }

private fun mangaSectionItemKey(
    providerId: String,
    sectionId: String,
    index: Int,
    manga: MangaSummary,
): String {
    val baseKey = mangaIdentityKey(providerId, sectionId, manga)
    return when {
        providerId == "olympusbiblioteca-es" &&
            (sectionId == "nuevos-lanzamientos" || sectionId == "top-series") ->
            "$index|$baseKey"
        else -> baseKey
    }
}

private fun buildHomeSectionSignature(
    providerId: String,
    sectionId: String,
    section: com.paudinc.komastream.data.model.HomeFeedSection,
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

private val MangaSummaryListSaver = listSaver<List<MangaSummary>, String>(
    save = { items ->
        buildList(items.size * 10 + 1) {
            add(items.size.toString())
            items.forEach { manga ->
                add(manga.providerId)
                add(manga.title)
                add(manga.detailPath)
                add(manga.coverUrl)
                add(manga.contentType)
                add(manga.status)
                add(manga.periodicity)
                add(manga.latestPublication)
                add(manga.chaptersCount)
                add(listOf(manga.rating, manga.views).joinToString("\u0001"))
            }
        }
    },
    restore = { saved ->
        val count = saved.firstOrNull()?.toIntOrNull() ?: return@listSaver emptyList()
        val values = saved.drop(1)
        val itemWidth = 10
        List(count) { index ->
            val start = index * itemWidth
            val pair = values.getOrElse(start + 9) { "" }.split("\u0001", limit = 2)
            MangaSummary(
                providerId = values.getOrElse(start) { "" },
                title = values.getOrElse(start + 1) { "" },
                detailPath = values.getOrElse(start + 2) { "" },
                coverUrl = values.getOrElse(start + 3) { "" },
                contentType = values.getOrElse(start + 4) { "" },
                status = values.getOrElse(start + 5) { "" },
                periodicity = values.getOrElse(start + 6) { "" },
                latestPublication = values.getOrElse(start + 7) { "" },
                chaptersCount = values.getOrElse(start + 8) { "" },
                rating = pair.getOrElse(0) { "" },
                views = pair.getOrElse(1) { "" },
            )
        }
    },
)

private val ChapterSummaryListSaver = listSaver<List<ChapterSummary>, String>(
    save = { items ->
        buildList(items.size * 8 + 1) {
            add(items.size.toString())
            items.forEach { chapter ->
                add(chapter.providerId)
                add(chapter.mangaTitle)
                add(chapter.chapterLabel)
                add(chapter.chapterNumberUrl)
                add(chapter.chapterId)
                add(chapter.mangaPath)
                add(chapter.chapterPath)
                add(listOf(chapter.coverUrl, chapter.registrationLabel).joinToString("\u0001"))
            }
        }
    },
    restore = { saved ->
        val count = saved.firstOrNull()?.toIntOrNull() ?: return@listSaver emptyList()
        val values = saved.drop(1)
        val itemWidth = 8
        List(count) { index ->
            val start = index * itemWidth
            val pair = values.getOrElse(start + 7) { "" }.split("\u0001", limit = 2)
            ChapterSummary(
                providerId = values.getOrElse(start) { "" },
                mangaTitle = values.getOrElse(start + 1) { "" },
                chapterLabel = values.getOrElse(start + 2) { "" },
                chapterNumberUrl = values.getOrElse(start + 3) { "" },
                chapterId = values.getOrElse(start + 4) { "" },
                mangaPath = values.getOrElse(start + 5) { "" },
                chapterPath = values.getOrElse(start + 6) { "" },
                coverUrl = pair.getOrElse(0) { "" },
                registrationLabel = pair.getOrElse(1) { "" },
            )
        }
    },
)
