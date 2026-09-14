package com.paudinc.komastream.utils

import com.paudinc.komastream.data.model.MangaDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaBakaMetadataResolverTest {
    private val providerDetail = MangaDetail(
        providerId = "test", identification = "manga", title = "One Piece", detailPath = "/one-piece",
        coverUrl = "provider-cover", bannerUrl = "provider-banner", description = "short", status = "Ongoing",
        publicationDate = "1997", periodicity = "", chapters = emptyList(),
    )

    @Test fun canonicalValuesReplaceUsableProviderValuesAndKeepChapters() = kotlinx.coroutines.runBlocking {
        val result = MangaBakaMetadataResolver(FakeSource(series)).resolve(providerDetail)
        assertEquals("ONE PIECE", result.title)
        assertEquals("complete synopsis", result.description)
        assertEquals("baka-cover", result.coverUrl)
        assertEquals(listOf("Eiichirou Oda"), result.authors)
        assertEquals(377L, result.mangaBakaId)
    }

    @Test fun ambiguousWeakTitlesAreRejected() = kotlinx.coroutines.runBlocking {
        val source = FakeSource(
            series.copy(title = "One Piece: Special", nativeTitle = null, romanizedTitle = null, alternativeTitles = emptyList()),
            second = series.copy(id = 99, title = "One Piece: Red", nativeTitle = null, romanizedTitle = null, alternativeTitles = emptyList()),
        )
        assertNull(MangaBakaMetadataResolver(source).resolve(providerDetail).mangaBakaId)
    }

    private class FakeSource(
        private val first: MangaBakaSeries,
        private val second: MangaBakaSeries? = null,
    ) : MangaBakaDataSource {
        override fun searchSeries(query: String, limit: Int) = Result.success(listOfNotNull(first, second))
        override fun getSeries(id: Long) = Result.success<MangaBakaSeries?>(null)
        override fun lookupByMalId(id: Long) = Result.success<MangaBakaSeries?>(null)
    }

    private companion object {
        val series = MangaBakaSeries(
            id = 377, title = "ONE PIECE", nativeTitle = "ONE PIECE", romanizedTitle = "ONE PIECE",
            alternativeTitles = listOf("Wanpiisu"), authors = listOf("Eiichirou Oda"), artists = listOf("Eiichirou Oda"),
            description = "complete synopsis", status = "releasing", type = "manga", year = 1997,
            publishedStart = "1997-07-22", coverUrl = "baka-cover", rating = 89.4, genres = listOf("action"),
            malId = 13, anilistId = 30013,
        )
    }
}
