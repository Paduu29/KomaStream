package com.paudinc.komastream.updater

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.paudinc.komastream.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GitHubReleaseAsset(
    val name: String,
    val downloadUrl: String,
)

data class GitHubRelease(
    val tagName: String,
    val title: String,
    val body: String,
    val htmlUrl: String,
    val publishedAt: String,
    val asset: GitHubReleaseAsset,
) {
    val versionLabel: String
        get() = tagName.removePrefix("v")
}

sealed interface UpdateCheckResult {
    data object Disabled : UpdateCheckResult
    data object NoUpdate : UpdateCheckResult
    data class UpdateAvailable(val release: GitHubRelease) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

sealed interface AppUpdateUiState {
    data object Disabled : AppUpdateUiState
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data class Available(val release: GitHubRelease) : AppUpdateUiState
    data class Downloading(val release: GitHubRelease, val progressPercent: Int) : AppUpdateUiState
    data class Downloaded(val release: GitHubRelease, val file: File) : AppUpdateUiState
    data class UpToDate(val checkedVersion: String) : AppUpdateUiState
    data class Error(val message: String) : AppUpdateUiState
}

enum class InstallUpdateResult {
    Started,
    PermissionRequired,
    Failed,
}

class GitHubReleaseUpdater(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient(),
) {
    private val releaseRepo = BuildConfig.GITHUB_RELEASE_REPO.trim()
    private val assetRegex = BuildConfig.GITHUB_RELEASE_ASSET_PATTERN
        .takeIf { it.isNotBlank() }
        ?.toRegex(RegexOption.IGNORE_CASE)
    private val releaseToken = BuildConfig.GITHUB_RELEASE_TOKEN.trim()

    fun isEnabled(): Boolean = releaseRepo.isNotBlank()

    suspend fun checkForUpdate(
        currentVersionName: String = BuildConfig.VERSION_NAME,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext UpdateCheckResult.Disabled

        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$releaseRepo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .apply {
                    if (releaseToken.isNotBlank()) {
                        header("Authorization", "Bearer $releaseToken")
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("GitHub release check failed with HTTP ${response.code}")
                }

                val json = JSONObject(response.body?.string().orEmpty())
                val release = parseRelease(json)
                if (release == null || !isNewerVersion(release.tagName, currentVersionName, currentVersionCode)) {
                    UpdateCheckResult.NoUpdate
                } else {
                    UpdateCheckResult.UpdateAvailable(release)
                }
            }
        }.getOrElse { UpdateCheckResult.Error(it.message ?: "Could not check for updates") }
    }

    suspend fun fetchReleaseHistory(limit: Int = 20): Result<List<GitHubRelease>> = withContext(Dispatchers.IO) {
        if (!isEnabled()) return@withContext Result.success(emptyList())

        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$releaseRepo/releases?per_page=$limit")
                .header("Accept", "application/vnd.github+json")
                .apply {
                    if (releaseToken.isNotBlank()) {
                        header("Authorization", "Bearer $releaseToken")
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("GitHub release history failed with HTTP ${response.code}")
                }

                val json = JSONArray(response.body?.string().orEmpty())
                buildList(json.length()) {
                    for (index in 0 until json.length()) {
                        parseRelease(json.optJSONObject(index) ?: continue)?.let(::add)
                    }
                }
            }
        }
    }

    suspend fun downloadRelease(
        release: GitHubRelease,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updateDir.listFiles()?.forEach { existing ->
            if (existing.name != release.asset.name) {
                existing.delete()
            }
        }
        val destination = File(updateDir, release.asset.name)

        val request = Request.Builder()
            .url(release.asset.downloadUrl)
            .apply {
                if (releaseToken.isNotBlank()) {
                    header("Authorization", "Bearer $releaseToken")
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Download failed with HTTP ${response.code}")
            }
            val body = response.body ?: error("Missing download body")
            val totalBytes = body.contentLength()
            destination.outputStream().buffered().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress(((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100))
                        }
                        read = input.read(buffer)
                    }
                }
            }
        }

        onProgress(100)
        destination
    }

    fun installDownloadedUpdate(file: File): InstallUpdateResult {
        if (!file.exists()) return InstallUpdateResult.Failed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(settingsIntent)
                InstallUpdateResult.PermissionRequired
            } catch (_: ActivityNotFoundException) {
                InstallUpdateResult.Failed
            }
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file,
        )

        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = apkUri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, false)
        }

        return try {
            context.startActivity(installIntent)
            InstallUpdateResult.Started
        } catch (_: ActivityNotFoundException) {
            InstallUpdateResult.Failed
        }
    }

    fun openReleasePage(release: GitHubRelease): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openReleasePage(versionName: String): Boolean {
        val releaseUrl = releasePageUrl(versionName) ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun releasePageUrl(versionName: String): String? {
        if (!isEnabled()) return null
        val normalizedVersion = versionName.trim().removePrefix("v")
        if (normalizedVersion.isBlank()) return "https://github.com/$releaseRepo/releases"
        return "https://github.com/$releaseRepo/releases/tag/v$normalizedVersion"
    }

    private fun parseRelease(json: JSONObject): GitHubRelease? {
        if (json.optBoolean("draft")) return null

        val assets = json.optJSONArray("assets") ?: return null
        var selectedAsset: GitHubReleaseAsset? = null
        for (index in 0 until assets.length()) {
            val assetJson = assets.optJSONObject(index) ?: continue
            val assetName = assetJson.optString("name")
            val downloadUrl = assetJson.optString("browser_download_url")
            val matchesConfiguredPattern = assetRegex?.matches(assetName) ?: false
            val isApk = assetName.endsWith(".apk", ignoreCase = true)
            if ((matchesConfiguredPattern || isApk) && downloadUrl.isNotBlank()) {
                selectedAsset = GitHubReleaseAsset(assetName, downloadUrl)
                break
            }
        }
        val asset = selectedAsset ?: return null

        return GitHubRelease(
            tagName = json.optString("tag_name"),
            title = json.optString("name").ifBlank { json.optString("tag_name") },
            body = json.optString("body"),
            htmlUrl = json.optString("html_url"),
            publishedAt = json.optString("published_at"),
            asset = asset,
        )
    }

    private fun isNewerVersion(tagName: String, currentVersionName: String, currentVersionCode: Int): Boolean {
        val releaseVersion = parseNumericVersion(tagName)
        val currentVersion = parseNumericVersion(currentVersionName)
        if (releaseVersion.isNotEmpty() && currentVersion.isNotEmpty()) {
            val segmentCount = maxOf(releaseVersion.size, currentVersion.size)
            for (index in 0 until segmentCount) {
                val releasePart = releaseVersion.getOrElse(index) { 0 }
                val currentPart = currentVersion.getOrElse(index) { 0 }
                if (releasePart != currentPart) {
                    return releasePart > currentPart
                }
            }
            return false
        }

        val releaseCodeFallback = tagName.filter(Char::isDigit).toIntOrNull()
        if (releaseCodeFallback != null) {
            return releaseCodeFallback > currentVersionCode
        }

        return tagName.removePrefix("v") != currentVersionName.removePrefix("v")
    }

    private fun parseNumericVersion(raw: String): List<Int> =
        raw.removePrefix("v")
            .split(Regex("[^0-9]+"))
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
}
