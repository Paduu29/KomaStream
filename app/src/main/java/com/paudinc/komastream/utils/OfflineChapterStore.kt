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
    private val readerCacheDir = File(context.cacheDir, "offline_reader_pages").apply { mkdirs() }
    private val cryptoLock = Any()
    private val readerCacheLock = Any()
    private val migrationLock = Any()
    @Volatile
    private var legacyMigrationComplete = false

    fun getDownloadedChapterPaths(): Set<String> {
        ensureMigrated()
        return rootDir.listFiles()
            ?.mapNotNull { directory ->
                readManifest(directory)?.let { manifest ->
                    val providerId = manifest.optString("providerId")
                    val chapterPath = canonicalChapterPathKey(providerId, manifest.optString("chapterPath"))
                    if (providerId.isBlank() || chapterPath.isBlank()) null else qualifyProviderValue(providerId, chapterPath)
                }
            }
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    fun isChapterDownloaded(providerId: String, chapterPath: String): Boolean {
        ensureMigrated()
        return manifestFile(providerId, chapterPath).exists()
    }

    fun saveChapter(readerData: ReaderData, pageBytes: List<ByteArray>) {
        ensureMigrated()
        require(readerData.pages.size == pageBytes.size) { "Page payload count does not match page metadata" }
        val session = openChapterWriteSession(readerData)
        try {
            readerData.pages.forEachIndexed { index, page ->
                session.writePage(index, page, pageBytes[index])
            }
            session.commit()
        } catch (error: Throwable) {
            session.abort()
            throw error
        }
    }

    fun openChapterWriteSession(readerData: ReaderData): ChapterWriteSession {
        ensureMigrated()
        return ChapterWriteSession(
            readerData = readerData,
            stagingDir = chapterStagingDir(readerData.providerId, readerData.chapterPath),
            finalDir = chapterDir(readerData.providerId, readerData.chapterPath),
            backupDir = chapterBackupDir(readerData.providerId, readerData.chapterPath),
        )
    }

    fun loadChapter(providerId: String, chapterPath: String): ReaderData? {
        ensureMigrated()
        val manifest = readManifest(chapterDir(providerId, chapterPath)) ?: return null
        val pages = manifest.optJSONArray("pages") ?: JSONArray()
        return ReaderData(
            providerId = manifest.optString("providerId").ifBlank { providerId },
            mangaTitle = manifest.optString("mangaTitle"),
            mangaDetailPath = normalizeStoredPath(manifest.optString("mangaDetailPath")),
            chapterTitle = manifest.optString("chapterTitle"),
            chapterPath = canonicalChapterPathKey(providerId, manifest.optString("chapterPath")),
            previousChapterPath = manifest.optString("previousChapterPath").takeIf { it.isNotBlank() && it != "null" }?.let(::normalizeStoredPath),
            nextChapterPath = manifest.optString("nextChapterPath").takeIf { it.isNotBlank() && it != "null" }?.let(::normalizeStoredPath),
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
        ensureMigrated()
        val fileName = page.offlineFileName.takeIf { it.isNotBlank() } ?: return null
        val file = File(chapterDir(providerId, chapterPath), fileName)
        if (!file.exists()) return null
        return readEncrypted(file)
    }

    fun getReadablePageFile(providerId: String, chapterPath: String, page: ReaderPage): File? {
        ensureMigrated()
        val fileName = page.offlineFileName.takeIf { it.isNotBlank() } ?: return null
        val encryptedFile = File(chapterDir(providerId, chapterPath), fileName)
        if (!encryptedFile.exists()) return null

        val targetDir = File(readerCacheDir, chapterDirectoryName(providerId, chapterPath)).apply { mkdirs() }
        val targetFile = File(targetDir, fileName.substringBeforeLast('.', fileName) + ".img")
        if (targetFile.exists() && targetFile.length() > 0L && targetFile.lastModified() >= encryptedFile.lastModified()) {
            return targetFile
        }

        synchronized(readerCacheLock) {
            if (targetFile.exists() && targetFile.length() > 0L && targetFile.lastModified() >= encryptedFile.lastModified()) {
                return targetFile
            }
            val tmpFile = File(targetDir, "${targetFile.name}.tmp")
            tmpFile.outputStream().use { output ->
                output.write(readEncrypted(encryptedFile))
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tmpFile.renameTo(targetFile)) {
                tmpFile.copyTo(targetFile, overwrite = true)
                tmpFile.delete()
            }
            targetFile.setLastModified(encryptedFile.lastModified())
        }
        return targetFile
    }

    fun removeChapter(providerId: String, chapterPath: String) {
        ensureMigrated()
        chapterStagingDir(providerId, chapterPath).deleteRecursively()
        chapterBackupDir(providerId, chapterPath).deleteRecursively()
        readerCacheChapterDir(providerId, chapterPath).deleteRecursively()
        chapterDir(providerId, chapterPath).deleteRecursively()
    }

    private fun readManifest(directory: File): JSONObject? {
        val file = File(directory, "manifest.json")
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText(StandardCharsets.UTF_8)) }.getOrNull()
    }

    private fun chapterDir(providerId: String, chapterPath: String): File {
        return File(rootDir, chapterDirectoryName(providerId, chapterPath))
    }

    private fun chapterStagingDir(providerId: String, chapterPath: String): File {
        return File(rootDir, "${chapterDirectoryName(providerId, chapterPath)}.staging")
    }

    private fun chapterBackupDir(providerId: String, chapterPath: String): File {
        return File(rootDir, "${chapterDirectoryName(providerId, chapterPath)}.backup")
    }

    private fun readerCacheChapterDir(providerId: String, chapterPath: String): File {
        return File(readerCacheDir, chapterDirectoryName(providerId, chapterPath))
    }

    private fun chapterDirectoryName(providerId: String, chapterPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(qualifyProviderValue(providerId, chapterPath).toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun manifestFile(providerId: String, chapterPath: String): File = File(chapterDir(providerId, chapterPath), "manifest.json")

    private fun buildManifest(readerData: ReaderData, pageJson: JSONArray): JSONObject {
        return JSONObject()
            .put("providerId", readerData.providerId)
            .put("chapterPath", canonicalChapterPathKey(readerData.providerId, readerData.chapterPath))
            .put("mangaTitle", readerData.mangaTitle)
            .put("mangaDetailPath", normalizeStoredPath(readerData.mangaDetailPath))
            .put("chapterTitle", readerData.chapterTitle)
            .put("previousChapterPath", readerData.previousChapterPath?.let { normalizeStoredPath(it) } ?: JSONObject.NULL)
            .put("nextChapterPath", readerData.nextChapterPath?.let { normalizeStoredPath(it) } ?: JSONObject.NULL)
            .put("pages", pageJson)
    }

    private fun replaceChapterDirectory(stagingDir: File, finalDir: File, backupDir: File) {
        backupDir.deleteRecursively()
        val hadExistingFinal = finalDir.exists()
        if (hadExistingFinal) {
            moveDirectory(finalDir, backupDir)
        }
        try {
            moveDirectory(stagingDir, finalDir)
            if (hadExistingFinal) {
                backupDir.deleteRecursively()
            }
        } catch (error: Throwable) {
            if (hadExistingFinal && !finalDir.exists() && backupDir.exists()) {
                moveDirectory(backupDir, finalDir)
            }
            throw error
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
        }
    }

    private fun moveDirectory(source: File, target: File) {
        if (!source.exists()) return
        target.parentFile?.mkdirs()
        if (target.exists()) {
            target.deleteRecursively()
        }
        if (source.renameTo(target)) return
        source.copyRecursively(target, overwrite = true)
        source.deleteRecursively()
    }

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

    private fun migrateLegacyChapterDirs() {
        rootDir.listFiles()?.forEach { directory ->
            if (!directory.isDirectory) return@forEach
            val manifest = readManifest(directory) ?: return@forEach
            val providerId = manifest.optString("providerId")
            val chapterPath = canonicalChapterPathKey(providerId, manifest.optString("chapterPath"))
            if (providerId.isBlank() || chapterPath.isBlank()) return@forEach
            val targetDir = chapterDir(providerId, chapterPath)
            if (directory.absolutePath == targetDir.absolutePath) return@forEach
            if (targetDir.exists()) {
                directory.deleteRecursively()
            } else {
                directory.renameTo(targetDir)
            }
        }
    }

    private fun ensureMigrated() {
        if (legacyMigrationComplete) return
        synchronized(migrationLock) {
            if (legacyMigrationComplete) return
            migrateLegacyChapterDirs()
            legacyMigrationComplete = true
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "komastream_offline_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    inner class ChapterWriteSession internal constructor(
        private val readerData: ReaderData,
        private val stagingDir: File,
        private val finalDir: File,
        private val backupDir: File,
    ) {
        private val pageJson = JSONArray()
        private var nextPageIndex = 0
        private var closed = false

        init {
            stagingDir.deleteRecursively()
            stagingDir.mkdirs()
        }

        fun writePage(index: Int, page: ReaderPage, raw: ByteArray) {
            check(!closed) { "Chapter write session already closed" }
            require(index == nextPageIndex) { "Pages must be written sequentially" }
            val fileName = "${index + 1}.bin"
            writeEncrypted(File(stagingDir, fileName), raw)
            pageJson.put(
                JSONObject()
                    .put("id", page.id)
                    .put("numberLabel", page.numberLabel)
                    .put("fileName", fileName)
            )
            nextPageIndex += 1
        }

        fun commit() {
            check(!closed) { "Chapter write session already closed" }
            require(nextPageIndex == readerData.pages.size) { "Chapter write incomplete" }
            val manifest = buildManifest(readerData, pageJson)
            File(stagingDir, "manifest.json").writeText(manifest.toString(), StandardCharsets.UTF_8)
            readerCacheChapterDir(readerData.providerId, readerData.chapterPath).deleteRecursively()
            replaceChapterDirectory(stagingDir, finalDir, backupDir)
            closed = true
        }

        fun abort() {
            if (closed) return
            stagingDir.deleteRecursively()
            closed = true
        }
    }
}
