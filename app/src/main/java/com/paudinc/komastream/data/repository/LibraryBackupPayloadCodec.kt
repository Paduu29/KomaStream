package com.paudinc.komastream.data.repository

import org.json.JSONArray
import org.json.JSONObject

class LibraryBackupPayloadCodec {
    fun exportPayload(
        favorites: String,
        reading: String,
        readChapters: String,
        readProgress: String,
        chapterPageCounts: String,
        selectedProviderId: String,
        settings: String,
        mangaDetailCache: String,
    ): String {
        return JSONObject()
            .put("favorites", JSONArray(favorites))
            .put("reading", JSONArray(reading))
            .put("readChapters", JSONArray(readChapters))
            .put("readProgress", JSONObject(readProgress))
            .put("chapterPageCounts", JSONObject(chapterPageCounts))
            .put("selectedProviderId", selectedProviderId)
            .put("settings", JSONObject(settings))
            .put("mangaDetailCache", JSONArray(mangaDetailCache))
            .toString()
    }

    fun importPayload(
        payload: String,
        selectedProviderIdFallback: String,
    ): ImportedLibraryPayload {
        val json = JSONObject(payload)
        return ImportedLibraryPayload(
            favorites = json.optJSONArray("favorites")?.toString() ?: "[]",
            reading = json.optJSONArray("reading")?.toString() ?: "[]",
            readChapters = json.optJSONArray("readChapters")?.toString() ?: "[]",
            readProgress = json.optJSONObject("readProgress")?.toString() ?: "{}",
            chapterPageCounts = json.optJSONObject("chapterPageCounts")?.toString() ?: "{}",
            selectedProviderId = json.optString("selectedProviderId").ifBlank { selectedProviderIdFallback },
            settings = json.optJSONObject("settings")?.toString(),
            mangaDetailCache = json.optJSONArray("mangaDetailCache")?.toString() ?: "[]",
        )
    }
}

data class ImportedLibraryPayload(
    val favorites: String,
    val reading: String,
    val readChapters: String,
    val readProgress: String,
    val chapterPageCounts: String,
    val selectedProviderId: String,
    val settings: String? = null,
    val mangaDetailCache: String = "[]",
)
