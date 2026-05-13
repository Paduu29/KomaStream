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

    override fun attachBaseContext(base: Context) {

        val prefs = base.getSharedPreferences("manga_library", MODE_PRIVATE)
        val storedLang = prefs.getString("appLanguage", null)


        if (storedLang.isNullOrBlank()) {
            val systemLanguage = AppLanguage.defaultForSystem(base.resources.configuration.locales[0])
            prefs.edit().putString("appLanguage", systemLanguage.name).commit()
        }

        val appLanguageStr = prefs.getString("appLanguage", AppLanguage.EN.name)

        val appLanguage = AppLanguage.fromStored(appLanguageStr)
        val languageTag = appLanguage.toLanguageTag()

        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))

        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
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
