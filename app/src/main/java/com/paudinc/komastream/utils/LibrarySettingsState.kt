package com.paudinc.komastream.utils

import android.content.SharedPreferences
import com.paudinc.komastream.data.local.AppSettingsEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LibrarySettingsSnapshot(
    val selectedProviderId: String = "",
    val useDarkTheme: Boolean = false,
    val autoJumpToUnread: Boolean = true,
    val adultContentEnabled: Boolean = false,
    val adultContentPinHash: String = "",
    val adultOnlyProvidersEnabled: Boolean = false,
    val disabledProviderIdsJson: String = "[]",
    val preferredChapterLanguage: String = "EN",
    val appLanguage: String = "EN",
    val hasSeenProviderPicker: Boolean = false,
)

class LibrarySettingsState(
    initialSnapshot: LibrarySettingsSnapshot = LibrarySettingsSnapshot(),
) {
    private val state = MutableStateFlow(initialSnapshot)

    val flow: StateFlow<LibrarySettingsSnapshot> = state.asStateFlow()

    val current: LibrarySettingsSnapshot
        get() = state.value

    fun update(snapshot: LibrarySettingsSnapshot) {
        state.value = snapshot
    }

    companion object {
        fun fromPreferences(prefs: SharedPreferences): LibrarySettingsState {
            return LibrarySettingsState(
                LibrarySettingsSnapshot(
                    selectedProviderId = prefs.getString("selectedProviderId", "").orEmpty(),
                    adultContentEnabled = prefs.getBoolean("adultContentEnabled", false),
                    adultContentPinHash = prefs.getString("adultContentPinHash", "").orEmpty(),
                    adultOnlyProvidersEnabled = prefs.getBoolean("adultOnlyProvidersEnabled", false),
                    preferredChapterLanguage = prefs.getString("preferredChapterLanguage", "EN").orEmpty(),
                    appLanguage = prefs.getString("appLanguage", "EN").orEmpty(),
                    hasSeenProviderPicker = prefs.getBoolean("hasSeenProviderPicker", false),
                )
            )
        }
    }
}

fun AppSettingsEntity.toSettingsSnapshot(): LibrarySettingsSnapshot =
    LibrarySettingsSnapshot(
        selectedProviderId = selectedProviderId,
        useDarkTheme = useDarkTheme,
        autoJumpToUnread = autoJumpToUnread,
        adultContentEnabled = adultContentEnabled,
        adultContentPinHash = adultContentPinHash,
        adultOnlyProvidersEnabled = adultOnlyProvidersEnabled,
        disabledProviderIdsJson = disabledProviderIdsJson,
        preferredChapterLanguage = preferredChapterLanguage,
        appLanguage = appLanguage,
        hasSeenProviderPicker = hasSeenProviderPicker,
    )
