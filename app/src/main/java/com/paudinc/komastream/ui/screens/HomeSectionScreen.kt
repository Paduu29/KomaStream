package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.HomeSectionType
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
    val scope = rememberCoroutineScope()
    val mangaItems = remember(sectionId) { mutableStateListOf(*section.mangas.toTypedArray()) }
    val chapterItems = remember(sectionId) { mutableStateListOf(*section.chapters.toTypedArray()) }
    var currentPage by remember(sectionId) { mutableStateOf(1) }
    var isLoadingMore by remember(sectionId) { mutableStateOf(false) }
    var hasMore by remember(sectionId) {
        mutableStateOf(
            providerSupportsHomePaging(provider.id, sectionId)
        )
    }

    LaunchedEffect(sectionId, section) {
        mangaItems.clear()
        mangaItems.addAll(section.mangas)
        chapterItems.clear()
        chapterItems.addAll(section.chapters)
        currentPage = 1
        hasMore = when {
            provider.id == "leermangaesp-es" && sectionId == "populares" -> section.mangas.size >= 20
            provider.id == "leermangaesp-es" && sectionId == "capitulos-recientes" -> section.chapters.size >= 20
            provider.id == "mangatube-de" && sectionId == "latest-updates" -> section.chapters.size >= 40
            else -> false
        }
    }

    val canonicalReadChapterKeys = remember(sectionId, readChapters) {
        readChapters.map { canonicalChapterKey(section.chapters.firstOrNull()?.providerId ?: "", it) }.toSet()
    }

    LazyColumn(
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
                items(mangaItems, key = { "${it.providerId}:${it.detailPath}" }) { manga ->
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
                                        .filterNot { incoming -> mangaItems.any { it.detailPath == incoming.detailPath } }
                                    mangaItems.addAll(newItems)
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
                                    lastChapterTitle = chapter.chapterLabel,
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
                                    chapterItems.addAll(newItems)
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
        else -> false
    }
