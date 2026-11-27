package com.paudinc.komastream

import android.content.Context
import androidx.work.WorkManager
import com.paudinc.komastream.updater.GitHubReleaseUpdater
import com.paudinc.komastream.utils.LibrarySettingsState
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.MyAnimeListApi
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.ProviderRegistry
import com.paudinc.komastream.utils.createDefaultProviderRegistry
import okhttp3.OkHttpClient

class AppGraph internal constructor(
    appContext: Context,
    val sharedHttpClient: OkHttpClient,
) {
    constructor(appContext: Context) : this(appContext, OkHttpClient())

    val appContext: Context = appContext.applicationContext
    val librarySettingsState: LibrarySettingsState =
        LibrarySettingsState.fromPreferences(this.appContext.getSharedPreferences("manga_library", Context.MODE_PRIVATE))
    val providerRegistry: ProviderRegistry =
        createDefaultProviderRegistry(this.appContext, sharedHttpClient, librarySettingsState)
    val libraryStore: LibraryStore = LibraryStore(this.appContext, providerRegistry, librarySettingsState)
    val offlineStore: OfflineChapterStore = OfflineChapterStore(this.appContext)
    val workManager: WorkManager = WorkManager.getInstance(this.appContext)
    val myAnimeListApi: MyAnimeListApi = MyAnimeListApi(sharedHttpClient)
    val updater: GitHubReleaseUpdater = GitHubReleaseUpdater(this.appContext, sharedHttpClient)
}
