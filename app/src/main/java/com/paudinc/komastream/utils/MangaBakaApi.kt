package com.paudinc.komastream.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class MangaBakaSeries(
    val id: Long,
    val title: String,
    val nativeTitle: String?,
    val romanizedTitle: String?,
    val alternativeTitles: List<String>,
    val authors: List<String>,
    val artists: List<String>,
    val description: String?,
    val status: String,
    val type: String,
    val year: Int?,
    val publishedStart: String?,
    val coverUrl: String?,
    val rating: Double?,
    val genres: List<String>,
    val malId: Long?,
    val anilistId: Long?,
)

interface MangaBakaDataSource {
    fun searchSeries(query: String, limit: Int = 10): Result<List<MangaBakaSeries>>
    fun getSeries(id: Long): Result<MangaBakaSeries?>
    fun lookupByMalId(id: Long): Result<MangaBakaSeries?>
}

class MangaBakaApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "KomaStream").build()) }
        .build(),
) : MangaBakaDataSource {
    override fun searchSeries(query: String, limit: Int): Result<List<MangaBakaSeries>> = request(
        "/v1/series/search?q=${URLEncoder.encode(query, "UTF-8")}&limit=$limit&sort_by=relevance_desc"
    ) { it.optJSONArray("data")?.let(::parseList).orEmpty() }

    override fun getSeries(id: Long): Result<MangaBakaSeries?> = request("/v1/series/$id") {
        it.optJSONObject("data")?.let(::parseSeries)
    }

    override fun lookupByMalId(id: Long): Result<MangaBakaSeries?> = request(
        "/v1/source/my-anime-list/$id?with_internal=false&with_series=true&with_merged_series=false&with_source_response=false"
    ) { it.optJSONObject("data")?.optJSONArray("series")?.optJSONObject(0)?.let(::parseSeries) }

    private fun <T> request(path: String, parse: (JSONObject) -> T): Result<T> = runCatching {
        val request = Request.Builder().url(BASE_URL + path).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("MangaBaka HTTP ${response.code}")
            val body = response.body?.string() ?: error("Empty MangaBaka response")
            val json = JSONObject(body)
            if (json.optInt("status") !in 200..299) error("MangaBaka API error")
            parse(json)
        }
    }

    private fun parseList(array: JSONArray): List<MangaBakaSeries> = buildList {
        for (i in 0 until array.length()) array.optJSONObject(i)?.let(::parseSeries)?.let(::add)
    }

    private fun parseSeries(json: JSONObject): MangaBakaSeries? {
        val id = json.optLong("id", -1L)
        if (id < 0 || json.optString("state") == "deleted") return null
        val source = json.optJSONObject("source")
        val published = json.optJSONObject("published")
        val cover = json.optJSONObject("cover")?.optJSONObject("raw")?.optString("url")
            ?.takeIf { it.startsWith("http") }
        val genres = buildList {
            val v2 = json.optJSONArray("genres_v2")
            if (v2 != null) for (i in 0 until v2.length()) {
                v2.optJSONObject(i)?.takeIf { it.optBoolean("is_genre") }?.optString("name")
                    ?.takeIf { it.isNotBlank() }?.let(::add)
            }
            if (isEmpty()) json.optJSONArray("genres")?.let { old ->
                for (i in 0 until old.length()) old.optString(i).takeIf { it.isNotBlank() }?.let { add(it.replace('_', ' ')) }
            }
        }
        val alternativeTitles = buildList {
            val secondary = json.optJSONObject("secondary_titles") ?: return@buildList
            val languages = secondary.keys()
            while (languages.hasNext()) {
                val titles = secondary.optJSONArray(languages.next()) ?: continue
                for (i in 0 until titles.length()) {
                    titles.optJSONObject(i)?.optString("title")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
        fun list(name: String) = buildList { json.optJSONArray(name)?.let { a ->
            for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        } }
        fun sourceId(name: String): Long? = source?.optJSONObject(name)?.optLong("id")?.takeIf { it > 0 }
        return MangaBakaSeries(
            id = if (json.optString("state") == "merged") json.optLong("merged_with", id) else id,
            title = json.optString("title"), nativeTitle = json.optString("native_title").takeIf { it.isNotBlank() },
            romanizedTitle = json.optString("romanized_title").takeIf { it.isNotBlank() }, alternativeTitles = alternativeTitles,
            authors = list("authors"), artists = list("artists"),
            description = json.optString("description").takeIf { it.isNotBlank() }, status = json.optString("status"),
            type = json.optString("type"), year = json.optInt("year").takeIf { it > 0 },
            publishedStart = published?.optString("start_date")?.takeIf { it.isNotBlank() }, coverUrl = cover,
            rating = json.optDouble("rating").takeIf { it > 0 }, genres = genres,
            malId = sourceId("my_anime_list"), anilistId = sourceId("anilist"),
        )
    }

    private companion object { const val BASE_URL = "https://api.mangabaka.org" }
}
