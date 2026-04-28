package com.paudinc.komastream.utils

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class MalTokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

data class MalMangaRecord(
    val id: Long,
    val title: String,
    val coverUrl: String,
    val numChapters: Int,
    val status: String,
    val alternativeTitles: List<String> = emptyList(),
)

data class MalListStatus(
    val status: String,
    val numChaptersRead: Int,
)

data class MalUserMangaEntry(
    val manga: MalMangaRecord,
    val listStatus: MalListStatus,
)

class MyAnimeListApi(
    private val client: OkHttpClient = OkHttpClient(),
) {
    fun buildAuthorizationUrl(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String,
    ): String {
        return malAuthorizationEndpoint().toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "plain")
            .addQueryParameter("state", state)
            .addQueryParameter("redirect_uri", redirectUri)
            .build()
            .toString()
    }

    fun exchangeCodeForToken(
        clientId: String,
        codeVerifier: String,
        code: String,
        redirectUri: String,
    ): MalTokenResponse {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .build()
        val request = Request.Builder()
            .url(malTokenEndpoint())
            .post(body)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        client.newCall(request).execute().use { response ->
            val json = response.bodyJsonOrThrow()
            return MalTokenResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresInSeconds = json.optLong("expires_in", 3600L),
            )
        }
    }

    fun refreshToken(
        clientId: String,
        refreshToken: String,
    ): MalTokenResponse {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder()
            .url(malTokenEndpoint())
            .post(body)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        client.newCall(request).execute().use { response ->
            val json = response.bodyJsonOrThrow()
            return MalTokenResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresInSeconds = json.optLong("expires_in", 3600L),
            )
        }
    }

    fun getMyInfo(accessToken: String, clientId: String): JSONObject {
        return getJson(
            url = "${malApiBaseUrl()}/users/@me",
            accessToken = accessToken,
            clientId = clientId,
        )
    }

    fun fetchUserMangaList(
        accessToken: String,
        clientId: String,
        limit: Int = 1000,
    ): List<MalUserMangaEntry> {
        val results = mutableListOf<MalUserMangaEntry>()
        var offset = 0
        while (true) {
            val url = malApiBaseUrl().toHttpUrl().newBuilder()
                .addPathSegments("users/@me/mangalist")
                .addQueryParameter("limit", limit.coerceIn(1, 1000).toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("fields", "list_status")
                .build()
                .toString()
            val json = getJson(url, accessToken, clientId)
            val data = json.optJSONArray("data") ?: JSONArray()
            for (index in 0 until data.length()) {
                val item = data.getJSONObject(index)
                val node = item.getJSONObject("node")
                val listStatus = item.optJSONObject("list_status") ?: JSONObject()
                results += MalUserMangaEntry(
                    manga = MalMangaRecord(
                        id = node.getLong("id"),
                        title = node.optString("title"),
                        coverUrl = node.optJSONObject("main_picture")?.optString("large").orEmpty()
                            .ifBlank { node.optJSONObject("main_picture")?.optString("medium").orEmpty() },
                        numChapters = node.optInt("num_chapters", 0),
                        status = node.optString("status"),
                        alternativeTitles = parseAlternativeTitles(node.optJSONObject("alternative_titles")),
                    ),
                    listStatus = MalListStatus(
                        status = listStatus.optString("status"),
                        numChaptersRead = listStatus.optInt("num_chapters_read", 0),
                    ),
                )
            }
            val paging = json.optJSONObject("paging") ?: break
            val nextUrl = paging.optString("next")
            if (nextUrl.isBlank() || data.length() < limit) break
            offset += limit
        }
        return results
    }

    fun searchManga(
        accessToken: String,
        clientId: String,
        query: String,
        limit: Int = 10,
    ): List<MalMangaRecord> {
        val url = malApiBaseUrl().toHttpUrl().newBuilder()
            .addPathSegments("manga")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .addQueryParameter("fields", "alternative_titles,num_chapters,status,main_picture")
            .build()
            .toString()
        val json = getJson(url, accessToken, clientId)
        val data = json.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (index in 0 until data.length()) {
                val node = data.getJSONObject(index).getJSONObject("node")
                add(
                    MalMangaRecord(
                        id = node.getLong("id"),
                        title = node.optString("title"),
                        coverUrl = node.optJSONObject("main_picture")?.optString("large").orEmpty()
                            .ifBlank { node.optJSONObject("main_picture")?.optString("medium").orEmpty() },
                        numChapters = node.optInt("num_chapters", 0),
                        status = node.optString("status"),
                        alternativeTitles = parseAlternativeTitles(node.optJSONObject("alternative_titles")),
                    )
                )
            }
        }
    }

    fun updateMangaStatus(
        accessToken: String,
        clientId: String,
        mangaId: Long,
        status: String,
        numChaptersRead: Int,
    ) {
        val body = FormBody.Builder()
            .add("status", status)
            .add("num_chapters_read", numChaptersRead.coerceAtLeast(0).toString())
            .build()
        val request = Request.Builder()
            .url("${malApiBaseUrl()}/manga/$mangaId/my_list_status")
            .put(body)
            .header("Authorization", "Bearer $accessToken")
            .header("X-MAL-Client-ID", clientId)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("MAL update failed: ${response.code}")
        }
    }

    fun deleteMangaStatus(
        accessToken: String,
        clientId: String,
        mangaId: Long,
    ) {
        val request = Request.Builder()
            .url("${malApiBaseUrl()}/manga/$mangaId/my_list_status")
            .delete()
            .header("Authorization", "Bearer $accessToken")
            .header("X-MAL-Client-ID", clientId)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) {
                throw IllegalStateException("MAL delete failed: ${response.code}")
            }
        }
    }

    private fun getJson(url: String, accessToken: String, clientId: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("X-MAL-Client-ID", clientId)
            .build()
        client.newCall(request).execute().use { response ->
            return response.bodyJsonOrThrow()
        }
    }

    private fun Response.bodyJsonOrThrow(): JSONObject {
        if (!isSuccessful) {
            val error = body?.string().orEmpty()
            throw IllegalStateException("MAL request failed: $code ${error.take(200)}")
        }
        val bodyText = body?.string().orEmpty()
        if (bodyText.isBlank()) return JSONObject()
        return JSONObject(bodyText)
    }

    private fun parseAlternativeTitles(json: JSONObject?): List<String> {
        if (json == null) return emptyList()
        val values = buildList {
            val english = json.optString("en")
            if (english.isNotBlank()) add(english)
            val japanese = json.optString("ja")
            if (japanese.isNotBlank()) add(japanese)
            val synonyms = json.optJSONArray("synonyms")
            if (synonyms != null) {
                for (index in 0 until synonyms.length()) {
                    val value = synonyms.optString(index)
                    if (value.isNotBlank()) add(value)
                }
            }
        }
        return values.distinct()
    }
}
