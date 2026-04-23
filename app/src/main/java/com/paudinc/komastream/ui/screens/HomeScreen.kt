package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paudinc.komastream.data.model.ChapterSummary
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.HomeFeedSection
import com.paudinc.komastream.data.model.HomeSectionType
import com.paudinc.komastream.data.model.MangaSummary
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.ui.components.ChapterRow
import com.paudinc.komastream.ui.components.EmptyCard
import com.paudinc.komastream.ui.components.LoadingPlaceholder
import com.paudinc.komastream.ui.components.MangaCoverCard
import com.paudinc.komastream.ui.components.TagChip
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
    onOpenSection: (String) -> Unit,
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

    val canonicalReadChapterKeys = remember(providerId, readChapters) {
        readChapters.map { canonicalChapterKey(providerId, it) }.toSet()
    }
    val topCarouselSection = sections.firstOrNull { it.type == HomeSectionType.MANGAS && it.mangas.isNotEmpty() }
    val sectionsToRender = if (topCarouselSection != null) {
        sections.filterNot { it.id == topCarouselSection.id }
    } else {
        sections
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (topCarouselSection != null) {
            item {
                FeaturedCarousel(
                    section = topCarouselSection,
                    strings = strings,
                    onOpenManga = onOpenManga,
                    onOpenSection = onOpenSection,
                )
            }
        } else {
            val featuredChapter = feed.latestUpdates.firstOrNull()
            if (featuredChapter != null) {
            item {
                FeaturedBanner(
                    imageUrl = featuredChapter.coverUrl,
                    eyebrow = strings.latestUpdates.uppercase(),
                    title = featuredChapter.mangaTitle,
                    subtitle = featuredChapter.chapterLabel,
                    supportingText = strings.homeLatestSubtitle,
                    actionLabel = strings.read,
                    onClick = { onOpenChapter(featuredChapter.providerId, featuredChapter.chapterPath) },
                )
            }
            }
        }

        sectionsToRender.forEach { section ->
            item(key = "header:${section.id}") {
                HomeRailHeader(
                    title = homeSectionTitle(section, strings),
                    actionLabel = "View all",
                    onAction = { onOpenSection(section.id) },
                )
            }
            when (section.type) {
                HomeSectionType.MANGAS -> {
                    item(key = "rail:${section.id}") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(end = 4.dp),
                        ) {
                            items(section.mangas.take(10), key = { "${it.providerId}:${it.detailPath}" }) { manga ->
                                MangaCoverCard(
                                    manga = manga,
                                    strings = strings,
                                    constrained = true,
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
                        }
                    }
                }
                HomeSectionType.CHAPTERS -> {
                    items(
                        items = section.chapters.take(5),
                        key = { "${section.id}:${it.providerId}:${it.chapterPath}" },
                    ) { chapter ->
                        HomeChapterRow(
                            chapter = chapter,
                            reading = reading,
                            canonicalReadChapterKeys = canonicalReadChapterKeys,
                            chapterProgress = chapterProgress,
                            strings = strings,
                            onOpenChapter = onOpenChapter,
                            onOpenManga = onOpenManga,
                            onAddToReading = onAddToReading,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedCarousel(
    section: HomeFeedSection,
    strings: AppStrings,
    onOpenManga: (String, String) -> Unit,
    onOpenSection: (String) -> Unit,
) {
    val mangas = section.mangas.take(8)
    val pagerState = rememberPagerState(pageCount = { mangas.size })
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val manga = mangas[page]
            FeaturedBanner(
                imageUrl = manga.coverUrl,
                eyebrow = homeSectionTitle(section, strings).uppercase(),
                title = manga.title,
                subtitle = manga.latestPublication.ifBlank { manga.status },
                supportingText = homeSectionSubtitle(section, strings),
                actionLabel = strings.openManga,
                onClick = { onOpenManga(manga.providerId, manga.detailPath) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mangas.forEachIndexed { index, _ ->
                    Surface(
                        modifier = Modifier.size(width = if (index == pagerState.currentPage) 18.dp else 6.dp, height = 6.dp),
                        shape = RoundedCornerShape(999.dp),
                        color = if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ) {}
                }
            }
            Row(
                modifier = Modifier.clickable { onOpenSection(section.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "View all",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun HomeChapterRow(
    chapter: ChapterSummary,
    reading: List<SavedManga>,
    canonicalReadChapterKeys: Set<String>,
    chapterProgress: (String, String) -> Int,
    strings: AppStrings,
    onOpenChapter: (String, String) -> Unit,
    onOpenManga: (String, String) -> Unit,
    onAddToReading: (SavedManga) -> Unit,
) {
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
        onOpenManga = {
            val targetPath = if (
                chapter.providerId == "inmanga-es" &&
                chapter.mangaPath.trim('/').split("/").size <= 3
            ) {
                chapter.chapterPath
            } else {
                chapter.mangaPath
            }
            onOpenManga(chapter.providerId, targetPath)
        },
    )
}

@Composable
private fun FeaturedBanner(
    imageUrl: String,
    eyebrow: String,
    title: String,
    subtitle: String,
    supportingText: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(212.dp)
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        border = cardBorder(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
    ) {
        Box {
            AsyncImage(
                model = imageUrl,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xF20A0813), Color(0xA90A0813), Color(0x330A0813)),
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp)
                    .fillMaxWidth(0.72f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                FilledTonalButton(
                    onClick = onClick,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun HomeRailHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.clickable(onClick = onAction),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = actionLabel,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

internal fun homeSectionTitle(section: HomeFeedSection, strings: AppStrings): String = when (section.id) {
    else -> section.title
}

internal fun homeSectionSubtitle(section: HomeFeedSection, strings: AppStrings): String = when (section.id) {
    "latest-updates", "ultimas-actualizaciones", "recently-updated" -> strings.homeLatestSubtitle
    "popular-chapters", "capitulos-populares" -> strings.homePopularChaptersSubtitle
    "popular-mangas", "mangas-populares", "populares" -> strings.homePopularMangasSubtitle
    "recommended-titles" -> strings.homeRecommendedSubtitle
    "top-viewed-titles" -> strings.homeTopViewedSubtitle
    "by-origin" -> strings.homeByOriginSubtitle
    "recent-chapter-read", "capitulos-recientes" -> strings.homeRecentReadsSubtitle
    "popular-this-season", "trending" -> strings.homeSeasonPicksSubtitle
    else -> if (section.chapters.isNotEmpty()) strings.homeChaptersFallbackSubtitle else strings.homeMangasFallbackSubtitle
}
