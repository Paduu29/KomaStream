package com.paudinc.mangascraper

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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

class OfflineChapterStore(private val context: Context) {
    private val rootDir = File(context.filesDir, "offline_chapters").apply { mkdirs() }

    fun getDownloadedChapterPaths(): Set<String> {
        return rootDir.listFiles()
            ?.mapNotNull { directory -> readManifest(directory)?.optString("chapterPath").orEmpty() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    fun isChapterDownloaded(chapterPath: String): Boolean {
        return manifestFile(chapterPath).exists()
    }

    fun saveChapter(readerData: ReaderData, pageBytes: List<ByteArray>) {
        require(readerData.pages.size == pageBytes.size) { "Page payload count does not match page metadata" }
        val chapterDir = chapterDir(readerData.chapterPath).apply {
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
            .put("chapterPath", readerData.chapterPath)
            .put("mangaTitle", readerData.mangaTitle)
            .put("mangaDetailPath", readerData.mangaDetailPath)
            .put("chapterTitle", readerData.chapterTitle)
            .put("previousChapterPath", readerData.previousChapterPath ?: JSONObject.NULL)
            .put("nextChapterPath", readerData.nextChapterPath ?: JSONObject.NULL)
            .put("pages", pageJson)

        File(chapterDir, "manifest.json").writeText(manifest.toString(), StandardCharsets.UTF_8)
    }

    fun loadChapter(chapterPath: String): ReaderData? {
        val manifest = readManifest(chapterDir(chapterPath)) ?: return null
        val pages = manifest.optJSONArray("pages") ?: JSONArray()
        return ReaderData(
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

    fun loadPageBytes(chapterPath: String, page: ReaderPage): ByteArray? {
        val fileName = page.offlineFileName.takeIf { it.isNotBlank() } ?: return null
        val file = File(chapterDir(chapterPath), fileName)
        if (!file.exists()) return null
        return readEncrypted(file)
    }

    fun removeChapter(chapterPath: String) {
        chapterDir(chapterPath).deleteRecursively()
    }

    private fun readManifest(directory: File): JSONObject? {
        val file = File(directory, "manifest.json")
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }.getOrNull()
    }

    private fun chapterDir(chapterPath: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(chapterPath.toByteArray(StandardCharsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(rootDir, name)
    }

    private fun manifestFile(chapterPath: String): File = File(chapterDir(chapterPath), "manifest.json")

    private fun writeEncrypted(target: File, raw: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(raw)
        target.outputStream().use { output ->
            output.write(iv.size)
            output.write(iv)
            output.write(encrypted)
        }
    }

    private fun readEncrypted(source: File): ByteArray {
        val payload = source.readBytes()
        val ivSize = payload.first().toInt()
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val data = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
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
