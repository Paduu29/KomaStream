package com.paudinc.komastream

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.decode.SvgDecoder
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.utils.AppCacheMaintenance
import java.io.File

class KomaStreamApp : Application(), ImageLoaderFactory {
    lateinit var appGraph: AppGraph
        private set

    override fun attachBaseContext(base: Context) {
        val prefs = base.getSharedPreferences("manga_library", MODE_PRIVATE)
        val appLanguageStr = prefs.getString("appLanguage", null)
            ?: AppLanguage.defaultForSystem(base.resources.configuration.locales[0]).name
        val appLanguage = AppLanguage.fromStored(appLanguageStr)
        prefs.edit().putString("appLanguage", appLanguage.name).commit()
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(appLanguage.toLanguageTag())
        )
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        appGraph = AppGraph(this)
        Thread {
            AppCacheMaintenance.trimAll(this)
        }.start()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(SvgDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(MAX_IMAGE_CACHE_BYTES)
                    .build()
            }
            .build()
    }

    companion object {
        private const val MAX_IMAGE_CACHE_BYTES = 64L * 1024L * 1024L
    }
}
