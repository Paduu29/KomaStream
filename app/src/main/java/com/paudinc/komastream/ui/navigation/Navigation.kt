package com.paudinc.komastream.ui.navigation

import androidx.compose.runtime.saveable.Saver
import com.paudinc.komastream.utils.createDefaultProviderRegistry

enum class RootTab(val label: String) {
    Home("Home"),
    Library("Library"),
    Catalog("Catalog"),
    Favorites("Favorites"),
    Settings("Settings"),
}

enum class LibraryTab(val label: String) { 
    ContinueReading("Continue Reading"), 
    Favorites("Favorites") 
}

sealed interface Screen {
    data class Root(val tab: RootTab) : Screen
    data class Detail(
        val providerId: String,
        val detailPath: String,
        val isMalIdEditorOpen: Boolean = false,
    ) : Screen
    data class Reader(val providerId: String, val chapterPath: String) : Screen
    data class HomeSection(val sectionId: String) : Screen
    data class Community(val providerId: String, val communityPath: String) : Screen
    data object ProviderPicker : Screen
    data object Settings : Screen
    data object SettingsLanguage : Screen
    data object SettingsTheme : Screen
    data object SettingsChapterLanguage : Screen
    data object SettingsReader : Screen
    data object SettingsContent : Screen
    data object SettingsUpdates : Screen
    data object SettingsMyAnimeList : Screen
    data object SettingsBackup : Screen
}

enum class CatalogMode {
    Basic,
    Advanced,
}

val ScreenStackSaver = Saver<List<Screen>, List<List<String>>>(
    save = { stack ->
        stack.map { screen ->
            when (screen) {
                is Screen.Root -> listOf("root", screen.tab.name)
                is Screen.Detail -> listOf("detail", screen.providerId, screen.detailPath, screen.isMalIdEditorOpen.toString())
                is Screen.Reader -> listOf("reader", screen.providerId, screen.chapterPath)
                is Screen.HomeSection -> listOf("home-section", screen.sectionId)
                is Screen.Community -> listOf("community", screen.providerId, screen.communityPath)
                Screen.ProviderPicker -> listOf("provider-picker")
                Screen.Settings -> listOf("settings")
                Screen.SettingsLanguage -> listOf("settings-language")
                Screen.SettingsTheme -> listOf("settings-theme")
                Screen.SettingsChapterLanguage -> listOf("settings-chapter-language")
                Screen.SettingsReader -> listOf("settings-reader")
                Screen.SettingsContent -> listOf("settings-content")
                Screen.SettingsUpdates -> listOf("settings-updates")
                Screen.SettingsMyAnimeList -> listOf("settings-mal")
                Screen.SettingsBackup -> listOf("settings-backup")
            }
        }
    },
    restore = { saved ->
        saved.map { item ->
            when (item.firstOrNull()) {
                "root" -> Screen.Root(RootTab.valueOf(item.getOrElse(1) { RootTab.Home.name }))
                "detail" -> Screen.Detail(
                    providerId = item.getOrElse(1) { createDefaultProviderRegistry().defaultProvider().id },
                    detailPath = item.getOrElse(2) { "/" },
                    isMalIdEditorOpen = item.getOrNull(3)?.toBooleanStrictOrNull() ?: false,
                )
                "reader" -> Screen.Reader(item.getOrElse(1) { createDefaultProviderRegistry().defaultProvider().id }, item.getOrElse(2) { "/" })
                "home-section" -> Screen.HomeSection(item.getOrElse(1) { "latest-updates" })
                "community" -> Screen.Community(
                    providerId = item.getOrElse(1) { createDefaultProviderRegistry().defaultProvider().id },
                    communityPath = item.getOrElse(2) { "/" },
                )
                "provider-picker" -> Screen.ProviderPicker
                "settings" -> Screen.Settings
                "settings-language" -> Screen.SettingsLanguage
                "settings-theme" -> Screen.SettingsTheme
                "settings-chapter-language" -> Screen.SettingsChapterLanguage
                "settings-reader" -> Screen.SettingsReader
                "settings-content" -> Screen.SettingsContent
                "settings-updates" -> Screen.SettingsUpdates
                "settings-mal" -> Screen.SettingsMyAnimeList
                "settings-backup" -> Screen.SettingsBackup
                else -> Screen.Root(RootTab.Home)
            }
        }.ifEmpty { listOf(Screen.ProviderPicker) }
    },
)
