package com.paudinc.komastream.data.repository

import android.util.Base64
import com.paudinc.komastream.data.model.ChapterSourceOption
import com.paudinc.komastream.data.model.MangaChapter
import com.paudinc.komastream.data.model.MangaDetail
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class MangaDetailCacheCodec {
    fun serialize(detail: MangaDetail): String {
        val rawPayload = JSONObject()
            .put("providerId", detail.providerId)
            .put("identification", detail.identification)
            .put("title", detail.title)
            .put("detailPath", detail.detailPath)
            .put("coverUrl", detail.coverUrl)
            .put("bannerUrl", detail.bannerUrl)
            .put("description", detail.description)
            .put("status", detail.status)
            .put("publicationDate", detail.publicationDate)
            .put("periodicity", detail.periodicity)
            .put("selectedChapterSourceId", detail.selectedChapterSourceId)
            .put("needsCloudflareClearance", detail.needsCloudflareClearance)
            .put("chapters", serializeChapters(detail.chapters))
            .put("chapterSources", serializeChapterSources(detail.chapterSources))
            .toString()
        return normalizeStoragePayload(rawPayload)
    }

    fun deserialize(value: String): MangaDetail {
        val json = JSONObject(decodeStoragePayload(value))
        return MangaDetail(
            providerId = json.optString("providerId"),
            identification = json.optString("identification"),
            title = json.optString("title"),
            detailPath = json.optString("detailPath"),
            coverUrl = json.optString("coverUrl"),
            bannerUrl = json.optString("bannerUrl"),
            description = json.optString("description"),
            status = json.optString("status"),
            publicationDate = json.optString("publicationDate"),
            periodicity = json.optString("periodicity"),
            chapters = parseChapters(json.optJSONArray("chapters") ?: JSONArray()),
            chapterSources = parseChapterSources(json.optJSONArray("chapterSources") ?: JSONArray()),
            selectedChapterSourceId = json.optString("selectedChapterSourceId"),
            needsCloudflareClearance = json.optBoolean("needsCloudflareClearance", false),
        )
    }

    fun sameChapterSignature(left: MangaDetail, right: MangaDetail): Boolean {
        return chapterSignature(left) == chapterSignature(right) &&
            chapterSourceSignature(left) == chapterSourceSignature(right) &&
            left.title == right.title &&
            left.detailPath == right.detailPath &&
            left.coverUrl == right.coverUrl &&
            left.bannerUrl == right.bannerUrl &&
            left.description == right.description &&
            left.status == right.status &&
            left.publicationDate == right.publicationDate &&
            left.periodicity == right.periodicity &&
            left.selectedChapterSourceId == right.selectedChapterSourceId &&
            left.needsCloudflareClearance == right.needsCloudflareClearance
    }

    fun normalizeStoragePayload(value: String): String {
        if (value.startsWith(GZIP_PREFIX)) return value
        val compressed = compress(value)
        return if (compressed.length < value.length) compressed else value
    }

    fun chapterSignature(detail: MangaDetail): String {
        return detail.chapters.joinToString("|") { chapter ->
            listOf(
                chapter.id,
                chapter.chapterLabel,
                chapter.chapterNumberUrl,
                chapter.path,
                chapter.pagesCount.toString(),
                chapter.registrationDate,
                chapter.languageCode,
                chapter.languageLabel,
                chapter.uploaderLabel,
            ).joinToString("~")
        }
    }

    fun chapterSourceSignature(detail: MangaDetail): String {
        return detail.chapterSources.joinToString("|") { source ->
            listOf(
                source.id,
                source.name,
                source.detailPath,
            ).joinToString("~")
        }
    }

    private fun serializeChapters(chapters: List<MangaChapter>): JSONArray {
        return JSONArray().apply {
            chapters.forEach { chapter ->
                put(
                    JSONObject()
                        .put("id", chapter.id)
                        .put("chapterLabel", chapter.chapterLabel)
                        .put("chapterNumberUrl", chapter.chapterNumberUrl)
                        .put("path", chapter.path)
                        .put("pagesCount", chapter.pagesCount)
                        .put("registrationDate", chapter.registrationDate)
                        .put("languageCode", chapter.languageCode)
                        .put("languageLabel", chapter.languageLabel)
                        .put("uploaderLabel", chapter.uploaderLabel)
                )
            }
        }
    }

    private fun serializeChapterSources(chapterSources: List<ChapterSourceOption>): JSONArray {
        return JSONArray().apply {
            chapterSources.forEach { source ->
                put(
                    JSONObject()
                        .put("id", source.id)
                        .put("name", source.name)
                        .put("detailPath", source.detailPath)
                )
            }
        }
    }

    private fun parseChapters(array: JSONArray): List<MangaChapter> = buildList(array.length()) {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                MangaChapter(
                    id = item.optString("id"),
                    chapterLabel = item.optString("chapterLabel"),
                    chapterNumberUrl = item.optString("chapterNumberUrl"),
                    path = item.optString("path"),
                    pagesCount = item.optInt("pagesCount", 0),
                    registrationDate = item.optString("registrationDate"),
                    languageCode = item.optString("languageCode"),
                    languageLabel = item.optString("languageLabel"),
                    uploaderLabel = item.optString("uploaderLabel"),
                )
            )
        }
    }

    private fun parseChapterSources(array: JSONArray): List<ChapterSourceOption> = buildList(array.length()) {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                ChapterSourceOption(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    detailPath = item.optString("detailPath"),
                )
            )
        }
    }

    private fun decodeStoragePayload(value: String): String {
        if (!value.startsWith(GZIP_PREFIX)) return value
        val compressed = Base64.decode(value.removePrefix(GZIP_PREFIX), Base64.NO_WRAP)
        return GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun compress(value: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(value)
        }
        return GZIP_PREFIX + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private companion object {
        private const val GZIP_PREFIX = "gz:"
    }
}
