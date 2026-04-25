package com.paudinc.komastream

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.paudinc.komastream.data.model.AppLanguage

class KomaStreamApp : Application() {

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
}
