package com.paudinc.komastream.provider

import com.paudinc.komastream.models.AppLanguage
import com.paudinc.komastream.models.CatalogFilterOptions
import com.paudinc.komastream.models.CatalogSearchResult
import com.paudinc.komastream.models.HomeFeed
import com.paudinc.komastream.models.MangaDetail
import com.paudinc.komastream.models.ReaderData

interface MangaProvider {
    val id: String
    val displayName: String
    val language: AppLanguage
    val websiteUrl: String
    val logoUrl: String

    fun fetchHomeFeed(): HomeFeed
    fun fetchCatalogFilterOptions(): CatalogFilterOptions
    fun searchCatalog(
        query: String,
        categoryIds: List<String>,
        sortBy: String,
        broadcastStatus: String,
        onlyFavorites: Boolean,
        skip: Int = 0,
        take: Int = 10,
    ): CatalogSearchResult

    fun fetchMangaDetail(detailPath: String): MangaDetail
    fun fetchReaderData(chapterPath: String): ReaderData
    fun downloadBytes(url: String, referer: String?): ByteArray
}