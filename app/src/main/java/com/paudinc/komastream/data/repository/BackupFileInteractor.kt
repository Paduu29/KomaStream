package com.paudinc.komastream.data.repository

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class BackupFileInteractor(
    private val contentResolver: ContentResolver,
) {
    suspend fun exportBackup(
        uri: Uri,
        payload: ByteArray,
        onProgress: (Int) -> Unit,
    ) {
        contentResolver.openOutputStream(uri)?.use { output ->
            writeWithProgress(output, payload, onProgress)
        } ?: error("Could not open output stream")
    }

    suspend fun importBackup(
        uri: Uri,
        onProgress: (Int) -> Unit,
    ): ByteArray {
        val totalBytes = contentResolver.openAssetFileDescriptor(uri, "r")
            ?.use { it.length }
            ?.takeIf { it > 0 }
        return contentResolver.openInputStream(uri)?.use { input ->
            readAllBytesWithProgress(input, totalBytes, onProgress)
        } ?: error("Could not open input stream")
    }

    private fun readAllBytesWithProgress(
        input: InputStream,
        totalBytes: Long?,
        onProgress: (Int) -> Unit,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_CHUNK_SIZE)
        var bytesReadTotal = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            bytesReadTotal += read
            onProgress(progressPercent(bytesReadTotal, totalBytes))
        }
        onProgress(if (totalBytes == null) 95 else 100)
        return output.toByteArray()
    }

    private fun writeWithProgress(
        output: OutputStream,
        bytes: ByteArray,
        onProgress: (Int) -> Unit,
    ) {
        val totalBytes = bytes.size.toLong().coerceAtLeast(1L)
        var offset = 0
        while (offset < bytes.size) {
            val nextOffset = (offset + BUFFER_CHUNK_SIZE).coerceAtMost(bytes.size)
            output.write(bytes, offset, nextOffset - offset)
            offset = nextOffset
            onProgress(progressPercent(offset.toLong(), totalBytes))
        }
        output.flush()
        onProgress(100)
    }

    private fun progressPercent(processedBytes: Long, totalBytes: Long?): Int {
        if (totalBytes == null || totalBytes <= 0L) return 0
        return ((processedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
    }

    private companion object {
        private const val BUFFER_CHUNK_SIZE = 8 * 1024
    }
}
