package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.HomeFeedSection
import com.paudinc.komastream.data.model.HomeSectionType
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.ui.components.*
import com.paudinc.komastream.utils.AppStrings

@Composable
fun HomeScreen(
    feed: HomeFeed?,
    strings: AppStrings,
    onOpenManga: (String, String) -> Unit,
    onOpenChapter: (String, String) -> Unit,
    onAddToReading: (SavedManga) -> Unit,
    onToggleFavorite: (SavedManga) -> Unit,
    isFavorite: (String, String) -> Boolean,
) {
    if (feed == null) {
        LoadingPlaceholder()
        return
    }
    val sections = remember(feed) { feed.sections.filter { it.chapters.isNotEmpty() || it.mangas.isNotEmpty() } }
    if (sections.isEmpty()) {
        LoadingPlaceholder()
        return
    }
    var selectedSectionId by rememberSaveable(feed.sections.map { it.id }.joinToString("|")) {
        mutableStateOf(sections.first().id)
    }
    val selectedSection = sections.firstOrNull { it.id == selectedSectionId } ?: sections.first()
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            SectionTitle(selectedSectionTitle(selectedSection, strings))
        }
        if (sections.size > 1) {
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sections.forEach { section ->
                        FilterChip(
                            selected = selectedSection.id == section.id,
                            onClick = { selectedSectionId = section.id },
                            label = { Text(selectedSectionTitle(section, strings)) },
                        )
                    }
                }
            }
        }
        when (selectedSection.type) {
            HomeSectionType.CHAPTERS -> items(selectedSection.chapters) {
                ChapterRow(
                    item = it,
                    strings = strings,
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
            HomeSectionType.MANGAS -> items(selectedSection.mangas) {
                MangaCoverCard(
                    manga = it,
                    strings = strings,
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

private fun selectedSectionTitle(
    section: HomeFeedSection,
    strings: AppStrings,
): String = when (section.id) {
    "latest-updates" -> strings.latestUpdates
    "popular-chapters" -> strings.popularChapters
    "popular-mangas" -> strings.popularMangas
    else -> section.title
}
