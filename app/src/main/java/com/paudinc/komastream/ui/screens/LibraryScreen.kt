package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.LibraryState
import com.paudinc.komastream.data.model.SavedManga
import com.paudinc.komastream.ui.components.*
import com.paudinc.komastream.ui.navigation.LibraryTab
import com.paudinc.komastream.utils.AppStrings

@Composable
fun LibraryScreen(
    libraryState: LibraryState,
    strings: AppStrings,
    selectedTab: LibraryTab,
    onSelectTab: (LibraryTab) -> Unit,
    onOpenManga: (String, String) -> Unit,
    onOpenChapter: (String, String) -> Unit,
    onRemoveFromContinueReading: (SavedManga) -> Unit,
    onRemoveFromFavorites: (SavedManga) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionTitle(strings.library)
        }
        when (selectedTab) {
            LibraryTab.Favorites -> {
                item {
                    Text(
                        strings.favoritesCount(libraryState.favorites.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (libraryState.favorites.isEmpty()) {
                    item { EmptyCard(strings.addMangaHint) }
                } else {
                    items(libraryState.favorites) { saved ->
                        FavoriteMangaCard(
                            manga = saved,
                            strings = strings,
                            onOpen = { onOpenManga(saved.providerId, saved.detailPath) },
                            onRemove = { onRemoveFromFavorites(saved) },
                        )
                    }
                }
            }
            LibraryTab.ContinueReading -> {
                item {
                    Text(
                        strings.activeSeriesCount(libraryState.reading.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (libraryState.reading.isEmpty()) {
                    item { EmptyCard(strings.readingHint) }
                } else {
                    items(libraryState.reading) { saved ->
                        ContinueReadingCard(
                            manga = saved,
                            strings = strings,
                            onOpen = { onOpenManga(saved.providerId, saved.detailPath) },
                            onResume = { if (saved.lastChapterPath.isNotBlank()) onOpenChapter(saved.providerId, saved.lastChapterPath) },
                            onRemove = { onRemoveFromContinueReading(saved) },
                        )
                    }
                }
            }
        }
    }
}
