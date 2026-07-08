package com.paudinc.komastream.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class MangaBakaSeries(
    val id: Long,
    val state: String,
    val title: String,
    val nativeTitle: String?,
    val romanizedTitle: String?,
    val authors: List<String>,
    val artists: List<String>,
    val description: String?,
    val status: String,
    val type: String,
    val year: Int?,
    val publishedStart: String?,
    val publishedEnd: String?,
    val coverUrl: String?,
    val rating: Double?,
    val contentRating: String,
    val genres: List<String>,
    val malId: Long?,
    val anilistId: Long?,
    val kitsuId: Long?,
    val mangaUpdatesId: String?,
    val totalChapters: String?,
)

class MangaBakaApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "KomaStream")
                .build()
            chain.proceed(request)
        }
        .build(),
) {
    companion object {
        private const val BASE_URL = "https://api.mangabaka.org"
    }

    fun searchSeries(query: String, limit: Int = 10): Result<List<MangaBakaSeries>> = runCatching {
        val url = "$BASE_URL/v1/series/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit&sort_by=relevance_desc"
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@runCatching emptyList()
        val json = JSONObject(body)
        if (json.optInt("status") != 200) return@runCatching emptyList()
        val data = json.optJSONArray("data") ?: return@runCatching emptyList()
        parseSeriesList(data)
    }

    fun getSeries(id: Long): Result<MangaBakaSeries?> = runCatching {
        val url = "$BASE_URL/v1/series/$id"
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@runCatching null
        val json = JSONObject(body)
        if (json.optInt("status") != 200) return@runCatching null
        val data = json.optJSONObject("data") ?: return@runCatching null
        parseSeries(data)
    }

    fun lookupByMalId(malId: Long): Result<MangaBakaSeries?> = runCatching {
        val url = "$BASE_URL/v1/source/my-anime-list/$malId?with_internal=false&with_series=true&with_merged_series=false&with_source_response=false"
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@runCatching null
        val json = JSONObject(body)
        if (json.optInt("status") != 200) return@runCatching null
        val data = json.optJSONObject("data") ?: return@runCatching null
        val series = data.optJSONObject("series") ?: return@runCatching null
        parseSeries(series)
    }

    fun lookupByAnilistId(anilistId: Long): Result<MangaBakaSeries?> = runCatching {
        val url = "$BASE_URL/v1/source/anilist/$anilistId?with_internal=false&with_series=true&with_merged_series=false&with_source_response=false"
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@runCatching null
        val json = JSONObject(body)
        if (json.optInt("status") != 200) return@runCatching null
        val data = json.optJSONObject("data") ?: return@runCatching null
        val series = data.optJSONObject("series") ?: return@runCatching null
        parseSeries(series)
    }

    private fun parseSeriesList(array: JSONArray): List<MangaBakaSeries> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            parseSeries(item)?.let { add(it) }
        }
    }

    private fun parseSeries(json: JSONObject): MangaBakaSeries? {
        val id = json.optLong("id", -1L)
        if (id < 0) return null
        val state = json.optString("state", "active")
        if (state == "deleted") return null
        val resolvedId = if (state == "merged") {
            json.optLong("merged_with", id)
        } else {
            id
        }

        val authors = parseStringList(json.optJSONArray("authors"))
        val artists = parseStringList(json.optJSONArray("artists"))

        val genres = mutableListOf<String>()
        val genresV2 = json.optJSONArray("genres_v2")
        if (genresV2 != null) {
            for (i in 0 until genresV2.length()) {
                val tag = genresV2.optJSONObject(i) ?: continue
                if (tag.optBoolean("is_genre", false)) {
                    tag.optString("name")?.let { genres.add(it) }
                }
            }
        }
        if (genres.isEmpty()) {
            val genresDeprecated = json.optJSONArray("genres")
            if (genresDeprecated != null) {
                for (i in 0 until genresDeprecated.length()) {
                    genresDeprecated.optString(i)?.let { genres.add(formatGenre(it)) }
                }
            }
        }

        val source = json.optJSONObject("source")
        val malId = source?.optJSONObject("my_anime_list")?.optLong("id")
        val anilistId = source?.optJSONObject("anilist")?.optLong("id")
        val kitsuId = source?.optJSONObject("kitsu")?.optLong("id")
        val mangaUpdatesId = source?.optJSONObject("manga_updates")?.optString("id")

        val published = json.optJSONObject("published")
        val cover = json.optJSONObject("cover")
        val rawCover = cover?.optJSONObject("raw")
        val coverUrl = rawCover?.optString("url")?.let { url ->
            if (url.startsWith("http")) url else null
        }

        return MangaBakaSeries(
            id = resolvedId,
            state = state,
            title = json.optString("title", ""),
            nativeTitle = json.optString("native_title", null),
            romanizedTitle = json.optString("romanized_title", null),
            authors = authors,
            artists = artists,
            description = json.optString("description", null),
            status = json.optString("status", "unknown"),
            type = json.optString("type", "unknown"),
            year = json.optInt("year", 0).takeIf { it > 0 },
            publishedStart = published?.optString("start_date", null),
            publishedEnd = published?.optString("end_date", null),
            coverUrl = coverUrl,
            rating = json.optDouble("rating", 0.0).takeIf { it > 0 },
            contentRating = json.optString("content_rating", "safe"),
            genres = genres,
            malId = malId,
            anilistId = anilistId,
            kitsuId = kitsuId,
            mangaUpdatesId = mangaUpdatesId,
            totalChapters = json.optString("total_chapters", null),
        )
    }

    private fun parseStringList(array: JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) {
            array.optString(i)?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }

    private fun formatGenre(genre: String): String {
        return genre.split("_").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }
        }
    }
}
