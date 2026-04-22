package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.HomeFeedSection
import com.paudinc.komastream.data.model.HomeSectionType
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.ui.components.ChapterRow
import com.paudinc.komastream.ui.components.EmptyCard
import com.paudinc.komastream.ui.components.LoadingPlaceholder
import com.paudinc.komastream.ui.components.MangaCoverCard
import com.paudinc.komastream.ui.components.cardBorder
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.canonicalChapterKey
import com.paudinc.komastream.utils.sameChapterPath

@Composable
fun HomeScreen(
    providerId: String,
    providerName: String,
    feed: HomeFeed?,
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
    if (feed == null) {
        LoadingPlaceholder(strings.loadingProviderHome(providerName))
        return
    }
    val sections = remember(feed) { feed.sections.filter { it.chapters.isNotEmpty() || it.mangas.isNotEmpty() } }
    if (sections.isEmpty()) {
        EmptyCard(strings.emptyProviderHome(providerName))
        return
    }

    var selectedSectionId by rememberSaveable(providerId, sections.map { it.id }.joinToString("|")) {
        mutableStateOf(sections.first().id)
    }
    val selectedSection = sections.firstOrNull { it.id == selectedSectionId } ?: sections.first()
    val canonicalReadChapterKeys = remember(providerId, readChapters) {
        readChapters.map { canonicalChapterKey(providerId, it) }.toSet()
    }
    when (selectedSection.type) {
        HomeSectionType.CHAPTERS -> {
            val listState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    HomeSectionHero(
                        title = selectedSectionTitle(selectedSection, strings),
                        subtitle = selectedSectionSubtitle(selectedSection, strings),
                        itemCountLabel = strings.itemCount(selectedSectionItemCount(selectedSection)),
                    )
                }
                if (sections.size > 1) {
                    item {
                        HomeSectionTabs(
                            sections = sections,
                            selectedSectionId = selectedSection.id,
                            strings = strings,
                            onSelect = { selectedSectionId = it },
                        )
                    }
                }
                items(
                    items = selectedSection.chapters,
                    key = { "${it.providerId}:${it.chapterPath}" },
                ) {
                    val progress = chapterProgress(it.providerId, it.chapterPath)
                    val isCurrentReadingEntry = reading.any { saved ->
                        saved.providerId == it.providerId &&
                            sameChapterPath(it.providerId, saved.lastChapterPath, it.chapterPath)
                    }
                    val isRead = canonicalChapterKey(it.providerId, it.chapterPath) in canonicalReadChapterKeys
                    ChapterRow(
                        item = it,
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
                                    providerId = it.providerId,
                                    title = it.mangaTitle,
                                    detailPath = it.mangaPath,
                                    coverUrl = it.coverUrl,
                                    lastChapterTitle = it.chapterLabel,
                                    lastChapterPath = it.chapterPath,
                                )
                            )
                        },
                        onOpenManga = {
                            val targetPath = if (
                                it.providerId == "inmanga-es" &&
                                it.mangaPath.trim('/').split("/").size <= 3
                            ) {
                                it.chapterPath
                            } else {
                                it.mangaPath
                            }
                            onOpenManga(it.providerId, targetPath)
                        },
                    )
                }
            }
        }

        HomeSectionType.MANGAS -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 168.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    HomeSectionHero(
                        title = selectedSectionTitle(selectedSection, strings),
                        subtitle = selectedSectionSubtitle(selectedSection, strings),
                        itemCountLabel = strings.itemCount(selectedSectionItemCount(selectedSection)),
                    )
                }
                if (sections.size > 1) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        HomeSectionTabs(
                            sections = sections,
                            selectedSectionId = selectedSection.id,
                            strings = strings,
                            onSelect = { selectedSectionId = it },
                        )
                    }
                }
                items(
                    items = selectedSection.mangas,
                    key = { "${it.providerId}:${it.detailPath}" },
                ) {
                    MangaCoverCard(
                        manga = it,
                        strings = strings,
                        constrained = true,
                        favoriteActionLabel = if (isFavorite(it.providerId, it.detailPath)) strings.removeFromFavorites else strings.addToFavorites,
                        onClick = { onOpenManga(it.providerId, it.detailPath) },
                        onFavoriteAction = {
                            onToggleFavorite(
                                SavedManga(
                                    providerId = it.providerId,
                                    title = it.title,
                                    detailPath = it.detailPath,
                                    coverUrl = it.coverUrl,
                                )
                            )
                        },
                        onOpenMangaAction = { onOpenManga(it.providerId, it.detailPath) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionHero(
    title: String,
    subtitle: String,
    itemCountLabel: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = cardBorder(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = itemCountLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HomeSectionTabs(
    sections: List<HomeFeedSection>,
    selectedSectionId: String,
    strings: AppStrings,
    onSelect: (String) -> Unit,
) {
    PrimaryScrollableTabRow(
        selectedTabIndex = sections.indexOfFirst { it.id == selectedSectionId }.coerceAtLeast(0),
    ) {
        sections.forEach { section ->
            val label = selectedSectionTitle(section, strings)
            Tab(
                selected = selectedSectionId == section.id,
                onClick = { onSelect(section.id) },
                text = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

private fun selectedSectionTitle(
    section: HomeFeedSection,
    strings: AppStrings,
): String = when (section.id) {
    "latest-updates" -> strings.latestUpdates
    "popular-chapters" -> strings.popularChapters
    "popular-mangas" -> strings.popularMangas
    "recommended-titles" -> strings.recommended
    "top-viewed-titles" -> strings.topViewed
    "by-origin" -> strings.byOrigin
    "recent-chapter-read" -> strings.recentReads
    "popular-this-season" -> strings.seasonPicks
    else -> section.title
}

private fun selectedSectionSubtitle(section: HomeFeedSection, strings: AppStrings): String = when (section.id) {
    "latest-updates" -> strings.homeLatestSubtitle
    "popular-chapters" -> strings.homePopularChaptersSubtitle
    "popular-mangas" -> strings.homePopularMangasSubtitle
    "recommended-titles" -> strings.homeRecommendedSubtitle
    "top-viewed-titles" -> strings.homeTopViewedSubtitle
    "by-origin" -> strings.homeByOriginSubtitle
    "recent-chapter-read" -> strings.homeRecentReadsSubtitle
    "popular-this-season" -> strings.homeSeasonPicksSubtitle
    else -> if (section.type == HomeSectionType.CHAPTERS) {
        strings.homeChaptersFallbackSubtitle
    } else {
        strings.homeMangasFallbackSubtitle
    }
}

private fun selectedSectionItemCount(section: HomeFeedSection): Int =
    when (section.type) {
        HomeSectionType.CHAPTERS -> section.chapters.size
        HomeSectionType.MANGAS -> section.mangas.size
    }
