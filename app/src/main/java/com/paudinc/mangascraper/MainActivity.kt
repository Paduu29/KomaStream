package com.paudinc.mangascraper

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("manga_library", MODE_PRIVATE)
        val appLanguage = prefs.getString("appLanguage", AppLanguage.EN.name) ?: AppLanguage.EN.name
        val languageTag = when (appLanguage) {
            AppLanguage.ES.name -> "es"
            else -> "en"
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        super.onCreate(savedInstanceState)
        setContent {
            MangaScraperApp()
        }
    }
}
