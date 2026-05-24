package com.paudinc.komastream

import android.content.Context
import androidx.work.WorkManager
import com.paudinc.komastream.updater.GitHubReleaseUpdater
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.ProviderRegistry
import com.paudinc.komastream.utils.createDefaultProviderRegistry

class AppGraph(
    appContext: Context,
) {
    val appContext: Context = appContext.applicationContext
    val providerRegistry: ProviderRegistry = createDefaultProviderRegistry(this.appContext)
    val libraryStore: LibraryStore = LibraryStore(this.appContext)
    val offlineStore: OfflineChapterStore = OfflineChapterStore(this.appContext)
    val workManager: WorkManager = WorkManager.getInstance(this.appContext)
    val updater: GitHubReleaseUpdater = GitHubReleaseUpdater(this.appContext)
}
