package com.paudinc.komastream.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.paudinc.komastream.data.model.ReaderData
import com.paudinc.komastream.data.model.ReaderPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class OfflineChapterStore(val context: Context) {
    private val rootDir = File(context.filesDir, "offline_chapters").apply { mkdirs() }
    private val cryptoLock = Any()

    fun getDownloadedChapterPaths(): Set<String> {
        return rootDir.listFiles()
            ?.mapNotNull { directory ->
                readManifest(directory)?.let { manifest ->
                    val providerId = manifest.optString("providerId")
                    val chapterPath = manifest.optString("chapterPath")
                    if (providerId.isBlank() || chapterPath.isBlank()) null else qualifyProviderValue(providerId, chapterPath)
                }
            }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    fun isChapterDownloaded(providerId: String, chapterPath: String): Boolean {
        return manifestFile(providerId, chapterPath).exists()
    }

    fun saveChapter(readerData: ReaderData, pageBytes: List<ByteArray>) {
        require(readerData.pages.size == pageBytes.size) { "Page payload count does not match page metadata" }
        val chapterDir = chapterDir(readerData.providerId, readerData.chapterPath).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val pageJson = JSONArray()
        readerData.pages.forEachIndexed { index, page ->
            val fileName = "${index + 1}.bin"
            writeEncrypted(File(chapterDir, fileName), pageBytes[index])
            pageJson.put(
                JSONObject()
                    .put("id", page.id)
                    .put("numberLabel", page.numberLabel)
                    .put("fileName", fileName)
            )
        }

        val manifest = JSONObject()
            .put("providerId", readerData.providerId)
            .put("chapterPath", readerData.chapterPath)
            .put("mangaTitle", readerData.mangaTitle)
            .put("mangaDetailPath", readerData.mangaDetailPath)
            .put("chapterTitle", readerData.chapterTitle)
            .put("previousChapterPath", readerData.previousChapterPath ?: JSONObject.NULL)
            .put("nextChapterPath", readerData.nextChapterPath ?: JSONObject.NULL)
            .put("pages", pageJson)

        File(chapterDir, "manifest.json").writeText(manifest.toString(), StandardCharsets.UTF_8)
    }

    fun loadChapter(providerId: String, chapterPath: String): ReaderData? {
        val manifest = readManifest(chapterDir(providerId, chapterPath)) ?: return null
        val pages = manifest.optJSONArray("pages") ?: JSONArray()
        return ReaderData(
            providerId = manifest.optString("providerId").ifBlank { providerId },
            mangaTitle = manifest.optString("mangaTitle"),
            mangaDetailPath = manifest.optString("mangaDetailPath"),
            chapterTitle = manifest.optString("chapterTitle"),
            chapterPath = manifest.optString("chapterPath"),
            previousChapterPath = manifest.optString("previousChapterPath").takeIf { it.isNotBlank() && it != "null" },
            nextChapterPath = manifest.optString("nextChapterPath").takeIf { it.isNotBlank() && it != "null" },
            pages = buildList(pages.length()) {
                for (index in 0 until pages.length()) {
                    val item = pages.getJSONObject(index)
                    add(
                        ReaderPage(
                            id = item.optString("id"),
                            numberLabel = item.optString("numberLabel"),
                            imageUrl = "",
                            offlineFileName = item.optString("fileName"),
                        )
                    )
                }
            },
        )
    }

    fun loadPageBytes(providerId: String, chapterPath: String, page: ReaderPage): ByteArray? {
        val fileName = page.offlineFileName.takeIf { it.isNotBlank() } ?: return null
        val file = File(chapterDir(providerId, chapterPath), fileName)
        if (!file.exists()) return null
        return readEncrypted(file)
    }

    fun removeChapter(providerId: String, chapterPath: String) {
        chapterDir(providerId, chapterPath).deleteRecursively()
    }

    private fun readManifest(directory: File): JSONObject? {
        val file = File(directory, "manifest.json")
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }.getOrNull()
    }

    private fun chapterDir(providerId: String, chapterPath: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(qualifyProviderValue(providerId, chapterPath).toByteArray(StandardCharsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(rootDir, name)
    }

    private fun manifestFile(providerId: String, chapterPath: String): File = File(chapterDir(providerId, chapterPath), "manifest.json")

    private fun writeEncrypted(target: File, raw: ByteArray) {
        val payload = synchronized(cryptoLock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(raw)
            iv to encrypted
        }
        target.outputStream().use { output ->
            output.write(payload.first.size)
            output.write(payload.first)
            output.write(payload.second)
        }
    }

    private fun readEncrypted(source: File): ByteArray {
        val payload = source.readBytes()
        if (payload.size < 2) return ByteArray(0)
        val ivSize = payload.first().toInt()
        if (ivSize <= 0 || payload.size <= 1 + ivSize) return ByteArray(0)
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val data = payload.copyOfRange(1 + ivSize, payload.size)
        return synchronized(cryptoLock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(data)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "komastream_offline_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
