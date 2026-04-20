package com.paudinc.komastream

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
    ProviderRegistry(
        listOf(
            InMangaService(),
        )
    )
