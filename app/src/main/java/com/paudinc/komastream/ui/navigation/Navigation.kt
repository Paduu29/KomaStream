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
    data class Detail(val providerId: String, val detailPath: String) : Screen
    data class Reader(val providerId: String, val chapterPath: String) : Screen
    data class HomeSection(val sectionId: String) : Screen
    data object ProviderPicker : Screen
    data object Settings : Screen
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
                is Screen.Detail -> listOf("detail", screen.providerId, screen.detailPath)
                is Screen.Reader -> listOf("reader", screen.providerId, screen.chapterPath)
                is Screen.HomeSection -> listOf("home-section", screen.sectionId)
                Screen.ProviderPicker -> listOf("provider-picker")
                Screen.Settings -> listOf("settings")
            }
        }
    },
    restore = { saved ->
        saved.map { item ->
            when (item.firstOrNull()) {
                "root" -> Screen.Root(RootTab.valueOf(item.getOrElse(1) { RootTab.Home.name }))
                "detail" -> Screen.Detail(item.getOrElse(1) { createDefaultProviderRegistry().defaultProvider().id }, item.getOrElse(2) { "/" })
                "reader" -> Screen.Reader(item.getOrElse(1) { createDefaultProviderRegistry().defaultProvider().id }, item.getOrElse(2) { "/" })
                "home-section" -> Screen.HomeSection(item.getOrElse(1) { "latest-updates" })
                "provider-picker" -> Screen.ProviderPicker
                "settings" -> Screen.Settings
                else -> Screen.Root(RootTab.Home)
            }
        }.ifEmpty { listOf(Screen.ProviderPicker) }
    },
)
