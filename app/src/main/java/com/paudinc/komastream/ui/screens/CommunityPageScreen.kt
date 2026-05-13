package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import com.paudinc.komastream.data.model.CommunityPage
import com.paudinc.komastream.data.model.CommunityPageType
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.ui.components.ChapterRow
import com.paudinc.komastream.ui.components.EmptyCard
import com.paudinc.komastream.ui.components.MangaCoverCard
import com.paudinc.komastream.ui.components.TagChip
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.canonicalChapterKey

@Composable
fun CommunityPageScreen(
    strings: AppStrings,
    page: CommunityPage,
    readChapters: Set<String>,
    chapterProgress: (String, String) -> Int,
    onOpenManga: (String, String) -> Unit,
    onOpenChapter: (String, String) -> Unit,
    onAddToReading: (SavedManga) -> Unit,
    onToggleFavorite: (SavedManga) -> Unit,
    isFavorite: (String, String) -> Boolean,
) {
    val chapterKeys = remember(page.providerId, readChapters) {
        readChapters.map { canonicalChapterKey(page.providerId, it) }.toSet()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(328.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val bannerModel = page.bannerUrl
                        .takeIf { it.isNotBlank() }

                    if (bannerModel != null) {
                        AsyncImage(
                            model = bannerModel,
                            contentDescription = page.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                        )
                                    )
                                )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x22000000),
                                        Color(0x8C090812),
                                        Color(0xEE090812),
                                    )
                                )
                            )
                    )
                    page.avatarUrl.takeIf { it.isNotBlank() }?.let { avatarUrl ->
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = page.title,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(18.dp)
                                .size(72.dp)
                                .clip(RoundedCornerShape(20.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = page.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (page.subtitle.isNotBlank()) {
                                    Text(
                                        text = page.subtitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (page.description.isNotBlank()) {
                            Text(
                                text = page.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        page.stats.takeIf { it.isNotEmpty() }?.let { stats ->
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                stats.take(4).forEach { stat ->
                                    TagChip(
                                        label = "${stat.label}: ${stat.value}",
                                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f),
                                        labelColor = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (page.memberNames.isNotEmpty()) {
            item {
                SectionHeader(title = "Members")
            }
            item {
                FlowChipRow(items = page.memberNames, labelColor = MaterialTheme.colorScheme.primary)
            }
        }

        if (page.achievementNames.isNotEmpty()) {
            item {
                SectionHeader(title = "Achievements")
            }
            item {
                FlowChipRow(items = page.achievementNames, labelColor = MaterialTheme.colorScheme.secondary)
            }
        }

        if (page.chapterItems.isNotEmpty()) {
            item {
                SectionHeader(title = when (page.type) {
                    CommunityPageType.GROUP -> "Recent uploads"
                    else -> "Recent activity"
                })
            }
            items(page.chapterItems, key = { "${it.providerId}:${it.chapterPath}" }) { chapter ->
                val progress = chapterProgress(chapter.providerId, chapter.chapterPath)
                val isCurrentReadingEntry = false
                val isRead = canonicalChapterKey(chapter.providerId, chapter.chapterPath) in chapterKeys
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
        }

        if (page.mangaItems.isNotEmpty()) {
            item {
                SectionHeader(
                    title = when (page.type) {
                        CommunityPageType.GROUP -> "Recent uploads"
                        CommunityPageType.COLLECTION -> "Titles"
                        CommunityPageType.PROFILE -> "Tracked titles"
                    }
                )
            }
            items(page.mangaItems, key = { "${it.providerId}:${it.detailPath}" }) { manga ->
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
        }

        if (page.mangaItems.isEmpty() && page.chapterItems.isEmpty() && page.memberNames.isEmpty() && page.achievementNames.isEmpty()) {
            item {
                EmptyCard(strings.emptyProviderHome(page.title))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChipRow(
    items: List<String>,
    labelColor: Color,
) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { label ->
            AssistChip(
                onClick = { },
                label = {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = labelColor,
                ),
            )
        }
    }
}
