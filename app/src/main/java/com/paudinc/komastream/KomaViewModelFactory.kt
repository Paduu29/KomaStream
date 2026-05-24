package com.paudinc.komastream

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.work.WorkManager
import com.paudinc.komastream.ui.navigation.Screen
import com.paudinc.komastream.ui.viewmodel.KomaViewModel
import com.paudinc.komastream.updater.GitHubReleaseUpdater
import com.paudinc.komastream.utils.AppStrings
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.ProviderRegistry

class KomaViewModelFactory(
    private val appContext: Context,
    private val providerRegistry: ProviderRegistry,
    private val libraryStore: LibraryStore,
    private val offlineStore: OfflineChapterStore,
    private val workManager: WorkManager,
    private val updater: GitHubReleaseUpdater,
    private val strings: AppStrings,
    private val initialNavigationStack: List<Screen>?,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(KomaViewModel::class.java)) {
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
        return KomaViewModel(
            context = appContext,
            providerRegistry = providerRegistry,
            libraryStore = libraryStore,
            offlineStore = offlineStore,
            workManager = workManager,
            updater = updater,
            strings = strings,
            initialNavigationStack = initialNavigationStack,
        ) as T
    }
}
