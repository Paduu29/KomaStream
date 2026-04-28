package com.paudinc.komastream.utils

import android.content.Context
import org.json.JSONObject

data class MyAnimeListSession(
    val accessToken: String = "",
    val refreshToken: String = "",
    val accessTokenExpiresAtMs: Long = 0L,
    val username: String = "",
    val pendingState: String = "",
    val codeVerifier: String = "",
)

class MyAnimeListSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): MyAnimeListSession = MyAnimeListSession(
        accessToken = prefs.getString(KEY_ACCESS_TOKEN, "").orEmpty(),
        refreshToken = prefs.getString(KEY_REFRESH_TOKEN, "").orEmpty(),
        accessTokenExpiresAtMs = prefs.getLong(KEY_ACCESS_TOKEN_EXPIRES_AT_MS, 0L),
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        pendingState = prefs.getString(KEY_PENDING_STATE, "").orEmpty(),
        codeVerifier = prefs.getString(KEY_CODE_VERIFIER, "").orEmpty(),
    )

    fun beginAuthorization(codeVerifier: String, state: String) {
        prefs.edit()
            .putString(KEY_CODE_VERIFIER, codeVerifier)
            .putString(KEY_PENDING_STATE, state)
            .apply()
    }

    fun saveConnectedAccount(
        accessToken: String,
        refreshToken: String,
        expiresInSeconds: Long,
        username: String,
    ) {
        val expiresAt = System.currentTimeMillis() + expiresInSeconds.coerceAtLeast(0L) * 1000L
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_ACCESS_TOKEN_EXPIRES_AT_MS, expiresAt)
            .putString(KEY_USERNAME, username)
            .remove(KEY_PENDING_STATE)
            .remove(KEY_CODE_VERIFIER)
            .apply()
    }

    fun updateTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_ACCESS_TOKEN_EXPIRES_AT_MS, System.currentTimeMillis() + expiresInSeconds.coerceAtLeast(0L) * 1000L)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "manga_library"
        private const val KEY_ACCESS_TOKEN = "mal_access_token"
        private const val KEY_REFRESH_TOKEN = "mal_refresh_token"
        private const val KEY_ACCESS_TOKEN_EXPIRES_AT_MS = "mal_access_token_expires_at_ms"
        private const val KEY_USERNAME = "mal_username"
        private const val KEY_PENDING_STATE = "mal_pending_state"
        private const val KEY_CODE_VERIFIER = "mal_code_verifier"
    }
}

fun generateMalCodeVerifier(): String {
    val bytes = ByteArray(64)
    java.security.SecureRandom().nextBytes(bytes)
    return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
}

fun generateMalCodeChallenge(codeVerifier: String): String {
    return codeVerifier
}

fun generateMalState(): String {
    val bytes = ByteArray(24)
    java.security.SecureRandom().nextBytes(bytes)
    return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
}

fun malRedirectUri(): String = "komastream://mal-callback"

fun malAuthorizationEndpoint(): String = "https://myanimelist.net/v1/oauth2/authorize"

fun malTokenEndpoint(): String = "https://myanimelist.net/v1/oauth2/token"

fun malApiBaseUrl(): String = "https://api.myanimelist.net/v2"

fun malJsonEscape(value: String): String = JSONObject.quote(value)
