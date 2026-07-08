package com.paudinc.komastream

import android.content.Context
import androidx.work.WorkManager
import com.paudinc.komastream.updater.GitHubReleaseUpdater
import com.paudinc.komastream.utils.LibrarySettingsState
import com.paudinc.komastream.utils.LibraryStore
import com.paudinc.komastream.utils.MangaBakaApi
import com.paudinc.komastream.utils.MangaBakaMetadataResolver
import com.paudinc.komastream.utils.MyAnimeListApi
import com.paudinc.komastream.utils.OfflineChapterStore
import com.paudinc.komastream.utils.ProviderRegistry
import com.paudinc.komastream.utils.createDefaultProviderRegistry
import okhttp3.OkHttpClient

class AppGraph(
    appContext: Context,
) {
    val appContext: Context = appContext.applicationContext
    val sharedHttpClient: OkHttpClient = OkHttpClient()
    val librarySettingsState: LibrarySettingsState =
        LibrarySettingsState.fromPreferences(this.appContext.getSharedPreferences("manga_library", Context.MODE_PRIVATE))
    val providerRegistry: ProviderRegistry =
        createDefaultProviderRegistry(this.appContext, sharedHttpClient, librarySettingsState)
    val libraryStore: LibraryStore = LibraryStore(this.appContext, providerRegistry, librarySettingsState)
    val offlineStore: OfflineChapterStore = OfflineChapterStore(this.appContext)
    val workManager: WorkManager = WorkManager.getInstance(this.appContext)
    val mangaBakaApi: MangaBakaApi = MangaBakaApi(sharedHttpClient)
    val mangaBakaMetadataResolver: MangaBakaMetadataResolver = MangaBakaMetadataResolver(mangaBakaApi)
    val myAnimeListApi: MyAnimeListApi = MyAnimeListApi(sharedHttpClient)
    val updater: GitHubReleaseUpdater = GitHubReleaseUpdater(this.appContext, sharedHttpClient)
}
