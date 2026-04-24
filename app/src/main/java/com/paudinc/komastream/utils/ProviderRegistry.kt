package com.paudinc.komastream.utils

import android.content.Context
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.provider.providers.AkaiComicProvider
import com.paudinc.komastream.provider.providers.InMangaProvider
import com.paudinc.komastream.provider.providers.LeerMangaEspProvider
import com.paudinc.komastream.provider.providers.MangaBallProvider
import com.paudinc.komastream.provider.providers.MangaFireProvider
import com.paudinc.komastream.provider.providers.MangaTubeProvider

class ProviderRegistry(
    providers: List<MangaProvider>,
) {
    private val providersById = providers.associateBy { it.id }
    private val orderedProviders = providers

    fun all(): List<MangaProvider> = orderedProviders

    fun groupedByLanguage(): Map<AppLanguage, List<MangaProvider>> =
        orderedProviders.groupBy { it.language }

    fun get(providerId: String): MangaProvider =
        providersById[providerId] ?: orderedProviders.first()

    fun defaultProvider(): MangaProvider = orderedProviders.first()
}

fun createDefaultProviderRegistry(): ProviderRegistry =
    createDefaultProviderRegistry(context = null)

fun createDefaultProviderRegistry(context: Context?): ProviderRegistry =
    ProviderRegistry(
        buildList {
            add(InMangaProvider())
            add(LeerMangaEspProvider())
            add(MangaTubeProvider(context))
            add(MangaFireProvider(context))
            context?.let { add(MangaBallProvider(it)) }
            context?.let { add(AkaiComicProvider(it)) }
        }
    )