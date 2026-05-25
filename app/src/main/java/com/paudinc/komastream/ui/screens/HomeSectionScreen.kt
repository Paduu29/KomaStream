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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.HomeFeedSection
import com.paudinc.komastream.data.model.HomeSectionType
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.ui.components.ChapterRow
import com.paudinc.komastream.ui.components.EmptyCard
import com.paudinc.komastream.ui.components.MangaCoverCard
import com.paudinc.komastream.ui.viewmodel.HomeSectionUiState
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.canonicalChapterKey
import com.paudinc.komastream.utils.sameChapterPath

@Composable
fun HomeSectionScreen(
    sectionId: String,
    providerId: String,
    providerName: String,
    feed: HomeFeed?,
    sectionState: HomeSectionUiState?,
    reading: List<SavedManga>,
    readChapters: Set<String>,
    chapterProgress: (String, String) -> Int,
    strings: AppStrings,
    onBindSection: (HomeFeedSection) -> Unit,
    onLoadMore: () -> Unit,
    onOpenManga: (String, String) -> Unit,
    onOpenChapter: (String, String) -> Unit,
    onAddToReading: (SavedManga) -> Unit,
    onToggleFavorite: (SavedManga) -> Unit,
    isFavorite: (String, String) -> Boolean,
) {
    val baseSection = remember(feed, sectionId) { feed?.sections?.firstOrNull { it.id == sectionId } }
    LaunchedEffect(providerId, sectionId, baseSection) {
        baseSection?.let(onBindSection)
    }

    val displaySection = remember(baseSection, sectionState) {
        when {
            sectionState != null -> {
                HomeFeedSection(
                    id = sectionState.sectionId,
                    title = sectionState.title,
                    type = sectionState.type,
                    chapters = sectionState.chapters,
                    mangas = sectionState.mangas,
                )
            }
            baseSection != null -> baseSection
            else -> null
        }
    }
    if (displaySection == null) {
        EmptyCard(strings.emptyProviderHome(""))
        return
    }

    val stateKey = "$providerId:$sectionId"
    val listState = rememberSaveable(stateKey, saver = LazyListState.Saver) {
        LazyListState()
    }
    val hasMore = sectionState?.hasMore ?: false
    val isLoadingMore = sectionState?.isLoadingMore ?: false
    val canonicalReadChapterKeys = remember(providerId, readChapters) {
        readChapters.map { canonicalChapterKey(providerId, it) }.toSet()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = homeSectionTitle(displaySection, strings),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Text(
                text = homeSectionSubtitle(displaySection, strings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (displaySection.type) {
            HomeSectionType.MANGAS -> {
                itemsIndexed(
                    displaySection.mangas,
                    key = { index, manga ->
                        "${manga.providerId}:${mangaSectionItemKey(providerId, sectionId, index, manga)}"
                    },
                ) { _, manga ->
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
                            onClick = onLoadMore,
                            enabled = !isLoadingMore,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text(if (isLoadingMore) strings.loadingProviderHome(providerName) else strings.loadMore)
                        }
                    }
                }
            }
            HomeSectionType.CHAPTERS -> {
                items(displaySection.chapters, key = { "${it.providerId}:${it.chapterPath}" }) { chapter ->
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
                            onClick = onLoadMore,
                            enabled = !isLoadingMore,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text(if (isLoadingMore) strings.loadingProviderHome(providerName) else strings.loadMore)
                        }
                    }
                }
            }
        }
    }
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
    manga: com.paudinc.komastream.data.model.MangaSummary,
): String {
    val baseKey = mangaIdentityKey(providerId, sectionId, manga)
    return when {
        providerId == "olympusbiblioteca-es" &&
            (sectionId == "nuevos-lanzamientos" || sectionId == "top-series") ->
            "$index|$baseKey"
        else -> baseKey
    }
}
