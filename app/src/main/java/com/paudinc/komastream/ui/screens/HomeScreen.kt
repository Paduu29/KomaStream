package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.HomeFeed
import com.paudinc.komastream.ui.components.*
import com.paudinc.komastream.utils.AppStrings

@Composable
fun HomeScreen(
    feed: HomeFeed?,
    strings: AppStrings,
    onOpenManga: (String, String) -> Unit,
    onOpenChapter: (String, String) -> Unit,
) {
    if (feed == null) {
        LoadingPlaceholder()
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { SectionTitle(strings.latestUpdates) }
        items(feed.latestUpdates) { ChapterRow(it, strings, onOpenChapter) }
        if (feed.popularChapters.isNotEmpty()) {
            item { SectionTitle(strings.popularChapters) }
            items(feed.popularChapters) { ChapterRow(it, strings, onOpenChapter) }
        }
        if (feed.popularMangas.isNotEmpty()) {
            item { SectionTitle(strings.popularMangas) }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    items(feed.popularMangas) { MangaCoverCard(it, strings, constrained = true) { onOpenManga(it.providerId, it.detailPath) } }
                }
            }
        }
    }
}
