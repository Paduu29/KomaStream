package com.paudinc.komastream.utils

import android.content.Context
import org.json.JSONObject

class MyAnimeListLinkStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMangaId(providerId: String, detailPath: String): Long? {
        val json = JSONObject(prefs.getString(KEY_MANGA_LINKS, "{}").orEmpty())
        val key = mangaKey(providerId, detailPath)
        return when (val value = json.opt(key)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    fun setMangaId(providerId: String, detailPath: String, mangaId: Long) {
        val json = JSONObject(prefs.getString(KEY_MANGA_LINKS, "{}").orEmpty())
        json.put(mangaKey(providerId, detailPath), mangaId)
        prefs.edit().putString(KEY_MANGA_LINKS, json.toString()).apply()
    }

    fun removeMangaId(providerId: String, detailPath: String) {
        val json = JSONObject(prefs.getString(KEY_MANGA_LINKS, "{}").orEmpty())
        json.remove(mangaKey(providerId, detailPath))
        prefs.edit().putString(KEY_MANGA_LINKS, json.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_MANGA_LINKS).apply()
    }

    private fun mangaKey(providerId: String, detailPath: String): String =
        "$providerId::$detailPath"

    private companion object {
        const val PREFS_NAME = "manga_library"
        const val KEY_MANGA_LINKS = "mal_manga_links"
    }
}
