package com.paudinc.komastream

import android.content.Context
import androidx.work.WorkManager
import com.paudinc.komastream.updater.GitHubReleaseUpdater
import com.paudinc.komastream.utils.LibraryStore
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
    val providerRegistry: ProviderRegistry = createDefaultProviderRegistry(this.appContext, sharedHttpClient)
    val libraryStore: LibraryStore = LibraryStore(this.appContext, providerRegistry)
    val offlineStore: OfflineChapterStore = OfflineChapterStore(this.appContext)
    val workManager: WorkManager = WorkManager.getInstance(this.appContext)
    val myAnimeListApi: MyAnimeListApi = MyAnimeListApi(sharedHttpClient)
    val updater: GitHubReleaseUpdater = GitHubReleaseUpdater(this.appContext, sharedHttpClient)
}
