package com.paudinc.komastream.utils

import android.content.Context
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.provider.providers.AkaiComicProvider
import com.paudinc.komastream.provider.providers.InMangaProvider
import com.paudinc.komastream.provider.providers.LeerMangaEspProvider
import com.paudinc.komastream.provider.providers.MangaBallProvider
import com.paudinc.komastream.provider.providers.MarmotaProvider
import com.paudinc.komastream.provider.providers.ManhwaLatinoProvider
import com.paudinc.komastream.provider.providers.MangadotProvider
import com.paudinc.komastream.provider.providers.MangaFireProvider
import com.paudinc.komastream.provider.providers.MangaTubeProvider
import com.paudinc.komastream.provider.providers.Manhwa18Provider
import com.paudinc.komastream.provider.providers.MkissaMangaProvider
import com.paudinc.komastream.provider.providers.OlympusBibliotecaProvider
import okhttp3.OkHttpClient

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

    fun isSelectable(providerId: String, disabledProviderIds: Set<String>, adultOnlyProvidersEnabled: Boolean): Boolean {
        val provider = providersById[providerId] ?: return false
        if (providerId in disabledProviderIds) return false
        if (provider.isAdultOnly && !adultOnlyProvidersEnabled) return false
        return true
    }

    fun firstSelectableProvider(disabledProviderIds: Set<String>, adultOnlyProvidersEnabled: Boolean): MangaProvider? =
        orderedProviders.firstOrNull { isSelectable(it.id, disabledProviderIds, adultOnlyProvidersEnabled) }

    fun selectableProviderId(disabledProviderIds: Set<String>, adultOnlyProvidersEnabled: Boolean): String =
        firstSelectableProvider(disabledProviderIds, adultOnlyProvidersEnabled)?.id.orEmpty()
}

fun createDefaultProviderRegistry(): ProviderRegistry =
    createDefaultProviderRegistry(context = null, sharedHttpClient = OkHttpClient(), settingsState = LibrarySettingsState())

fun createDefaultProviderRegistry(
    context: Context?,
    sharedHttpClient: OkHttpClient = OkHttpClient(),
    settingsState: LibrarySettingsState = LibrarySettingsState(),
): ProviderRegistry =
    ProviderRegistry(
        buildList {
            add(InMangaProvider(sharedHttpClient))
            add(LeerMangaEspProvider(sharedHttpClient))
            add(OlympusBibliotecaProvider(sharedHttpClient))
            add(MangaTubeProvider(context, sharedHttpClient))
            add(MangaFireProvider(context, sharedHttpClient))
            add(MarmotaProvider(sharedHttpClient))
            add(MangadotProvider(sharedHttpClient))
            context?.let { add(Manhwa18Provider(it, sharedHttpClient)) }
            context?.let { add(ManhwaLatinoProvider(it, settingsState, sharedHttpClient)) }
            add(MangaBallProvider(settingsState, sharedHttpClient))
//            context?.let { add(AkaiComicProvider(it, sharedHttpClient)) }
            add(MkissaMangaProvider(sharedHttpClient, settingsState))
        }
    )
