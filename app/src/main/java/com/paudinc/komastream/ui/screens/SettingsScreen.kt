package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.provider.providers.MangaBallProvider
import com.paudinc.komastream.provider.providers.ManhwaLatinoProvider
import com.paudinc.komastream.ui.components.MarkdownReleaseNotes
import com.paudinc.komastream.ui.components.cardBorder
import com.paudinc.komastream.ui.viewmodel.MyAnimeListUiState
import com.paudinc.komastream.updater.AppUpdateUiState
import com.paudinc.komastream.utils.AppStrings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    strings: AppStrings,
    onOpenLanguage: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenChapterLanguage: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenContent: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenMyAnimeList: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SettingsNavigationItem(
                        icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                        title = strings.languageLabel,
                        onClick = onOpenLanguage,
                    )
                    SettingsNavigationItem(
                        icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                        title = strings.theme,
                        onClick = onOpenTheme,
                    )
                    SettingsNavigationItem(
                        icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                        title = strings.preferredChapterLanguage,
                        onClick = onOpenChapterLanguage,
                    )
                    SettingsNavigationItem(
                        icon = { Icon(Icons.Default.MenuBook, contentDescription = null) },
                        title = strings.reader,
                        onClick = onOpenReader,
                    )
                    SettingsNavigationItem(
                        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                        title = strings.contentAccess,
                        onClick = onOpenContent,
                    )
                    SettingsNavigationItem(
                        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                        title = strings.updates,
                        onClick = onOpenUpdates,
                    )
                    SettingsNavigationItem(
                        icon = { Icon(Icons.Default.CloudSync, contentDescription = null) },
                        title = strings.myAnimeList,
                        onClick = onOpenMyAnimeList,
                    )
                    SettingsNavigationItem(
                        icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                        title = strings.backup,
                        onClick = onOpenBackup,
                    )
                }
            }
        }
    }
}

@Composable
fun LanguageSettingsScreen(
    strings: AppStrings,
    appLanguage: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
) {
    SettingsCardScreen {
        Text(strings.languageLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageButton(strings.english, appLanguage == AppLanguage.EN) { onLanguageChange(AppLanguage.EN) }
            LanguageButton(strings.spanish, appLanguage == AppLanguage.ES) { onLanguageChange(AppLanguage.ES) }
            LanguageButton(strings.german, appLanguage == AppLanguage.DE) { onLanguageChange(AppLanguage.DE) }
        }
    }
}

@Composable
fun ThemeSettingsScreen(
    strings: AppStrings,
    useDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
) {
    SettingsCardScreen {
        Text(strings.theme, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onThemeChange(false) }, colors = segmentedButtonColors(selected = !useDarkTheme)) {
                Icon(Icons.Default.LightMode, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(strings.light)
            }
            Button(onClick = { onThemeChange(true) }, colors = segmentedButtonColors(selected = useDarkTheme)) {
                Icon(Icons.Default.DarkMode, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(strings.dark)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChapterLanguageSettingsScreen(
    strings: AppStrings,
    preferredChapterLanguage: AppLanguage,
    onPreferredChapterLanguageChange: (AppLanguage) -> Unit,
) {
    SettingsCardScreen {
        Text(strings.preferredChapterLanguage, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(strings.preferredChapterLanguageDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            preferredLanguageButtons.forEach { option ->
                Button(
                    onClick = { onPreferredChapterLanguageChange(option) },
                    colors = segmentedButtonColors(selected = preferredChapterLanguage == option),
                ) {
                    Text(preferredLanguageLabel(strings, option), maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun ReaderSettingsScreen(
    strings: AppStrings,
    autoJumpToUnread: Boolean,
    onAutoJumpToUnreadChange: (Boolean) -> Unit,
) {
    SettingsCardScreen {
        Text(strings.reader, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = strings.autoJumpToUnreadLabel,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = autoJumpToUnread,
                onCheckedChange = onAutoJumpToUnreadChange,
            )
        }
    }
}

@Composable
fun ContentSettingsScreen(
    strings: AppStrings,
    adultContentEnabled: Boolean,
    adultOnlyProvidersEnabled: Boolean,
    adultContentPinIsConfigured: Boolean,
    providersByLanguage: Map<AppLanguage, List<MangaProvider>>,
    disabledProviderIds: Set<String>,
    onAdultContentEnabledChange: (Boolean) -> Unit,
    onAdultOnlyProvidersEnabledChange: (Boolean) -> Unit,
    onSetAdultContentPin: (String) -> Unit,
    onVerifyAdultContentPin: (String) -> Boolean,
    onProviderEnabledChange: (String, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AdultAccessSettingCard(
            strings = strings,
            enabled = adultContentEnabled,
            adultOnlyProvidersEnabled = adultOnlyProvidersEnabled,
            pinConfigured = adultContentPinIsConfigured,
            onEnabledChange = onAdultContentEnabledChange,
            onAdultOnlyProvidersEnabledChange = onAdultOnlyProvidersEnabledChange,
            onSetAdultContentPin = onSetAdultContentPin,
            onVerifyAdultContentPin = onVerifyAdultContentPin,
        )
        ProviderAccessSettingCard(
            strings = strings,
            providersByLanguage = providersByLanguage,
            disabledProviderIds = disabledProviderIds,
            adultOnlyProvidersEnabled = adultOnlyProvidersEnabled,
            adultContentPinIsConfigured = adultContentPinIsConfigured,
            onSetAdultContentPin = onSetAdultContentPin,
            onVerifyAdultContentPin = onVerifyAdultContentPin,
            onProviderEnabledChange = onProviderEnabledChange,
        )
    }
}

@Composable
private fun AdultAccessSettingCard(
    strings: AppStrings,
    enabled: Boolean,
    adultOnlyProvidersEnabled: Boolean,
    pinConfigured: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onAdultOnlyProvidersEnabledChange: (Boolean) -> Unit,
    onSetAdultContentPin: (String) -> Unit,
    onVerifyAdultContentPin: (String) -> Boolean,
) {
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinMode by remember { mutableStateOf(PinMode.NONE) }
    var pendingTarget by remember { mutableStateOf(ContentPinTarget.NONE) }
    var pinValue by rememberSaveable { mutableStateOf("") }
    var pinConfirmation by rememberSaveable { mutableStateOf("") }
    var pinError by rememberSaveable { mutableStateOf("") }

    SettingsCardScreen {
        Text(strings.contentAccess, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(strings.contentAccessDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (enabled) strings.on else strings.off,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { nextEnabled ->
                    if (!nextEnabled) {
                        onEnabledChange(false)
                    } else {
                        pinError = ""
                        pinValue = ""
                        pinConfirmation = ""
                        pendingTarget = ContentPinTarget.ADULT_CONTENT
                        if (pinConfigured) {
                            pinMode = PinMode.VERIFY
                            showPinDialog = true
                        } else {
                            onEnabledChange(true)
                        }
                    }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = strings.adultOnlyProviders,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = adultOnlyProvidersEnabled,
                onCheckedChange = { nextEnabled ->
                    if (!nextEnabled) {
                        onAdultOnlyProvidersEnabledChange(false)
                    } else {
                        pinError = ""
                        pinValue = ""
                        pinConfirmation = ""
                        pendingTarget = ContentPinTarget.ADULT_ONLY_PROVIDERS
                        if (pinConfigured) {
                            pinMode = PinMode.VERIFY
                            showPinDialog = true
                        } else {
                            onAdultOnlyProvidersEnabledChange(true)
                        }
                    }
                },
            )
        }
        Text(
            strings.adultOnlyProvidersDescription,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = {
                Text(
                    when (pinMode) {
                        PinMode.CREATE -> strings.setParentalPin
                        PinMode.VERIFY -> strings.enterParentalPin
                        PinMode.NONE -> strings.setParentalPin
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when (pinMode) {
                            PinMode.CREATE -> strings.setParentalPinDescription
                            PinMode.VERIFY -> strings.enterParentalPinDescription
                            PinMode.NONE -> strings.setParentalPinDescription
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = pinValue,
                        onValueChange = { pinValue = it; pinError = "" },
                        label = { Text(strings.parentalPin) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (pinMode == PinMode.CREATE) {
                        OutlinedTextField(
                            value = pinConfirmation,
                            onValueChange = { pinConfirmation = it; pinError = "" },
                            label = { Text(strings.confirmParentalPin) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                    }
                    if (pinError.isNotBlank()) {
                        Text(pinError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (pinMode) {
                            PinMode.CREATE -> {
                                val trimmedPin = pinValue.trim()
                                if (trimmedPin.length < 4) {
                                    pinError = strings.parentalPinTooShort
                                    return@TextButton
                                }
                                if (trimmedPin != pinConfirmation.trim()) {
                                    pinError = strings.parentalPinMismatch
                                    return@TextButton
                                }
                                onSetAdultContentPin(trimmedPin)
                                when (pendingTarget) {
                                    ContentPinTarget.ADULT_CONTENT -> onEnabledChange(true)
                                    ContentPinTarget.ADULT_ONLY_PROVIDERS -> onAdultOnlyProvidersEnabledChange(true)
                                    ContentPinTarget.NONE -> Unit
                                }
                                showPinDialog = false
                            }
                            PinMode.VERIFY -> {
                                if (onVerifyAdultContentPin(pinValue)) {
                                    when (pendingTarget) {
                                        ContentPinTarget.ADULT_CONTENT -> onEnabledChange(true)
                                        ContentPinTarget.ADULT_ONLY_PROVIDERS -> onAdultOnlyProvidersEnabledChange(true)
                                        ContentPinTarget.NONE -> Unit
                                    }
                                    showPinDialog = false
                                } else {
                                    pinError = strings.parentalPinInvalid
                                }
                            }
                            PinMode.NONE -> showPinDialog = false
                        }
                    },
                ) {
                    Text(strings.enableAdultContent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text(strings.cancel)
                }
            },
        )
    }
}

private enum class ContentPinTarget {
    NONE,
    ADULT_CONTENT,
    ADULT_ONLY_PROVIDERS,
}

@Composable
private fun ProviderAccessSettingCard(
    strings: AppStrings,
    providersByLanguage: Map<AppLanguage, List<MangaProvider>>,
    disabledProviderIds: Set<String>,
    adultOnlyProvidersEnabled: Boolean,
    adultContentPinIsConfigured: Boolean,
    onSetAdultContentPin: (String) -> Unit,
    onVerifyAdultContentPin: (String) -> Boolean,
    onProviderEnabledChange: (String, Boolean) -> Unit,
) {
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinMode by remember { mutableStateOf(PinMode.NONE) }
    var pendingProviderId by rememberSaveable { mutableStateOf("") }
    var pendingProviderEnabled by rememberSaveable { mutableStateOf(false) }
    var pinValue by rememberSaveable { mutableStateOf("") }
    var pinConfirmation by rememberSaveable { mutableStateOf("") }
    var pinError by rememberSaveable { mutableStateOf("") }

    SettingsCardScreen {
        Text(strings.providerAccess, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(strings.providerAccessDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            AppLanguage.entries.forEach { language ->
                val providers = providersByLanguage[language].orEmpty()
                if (providers.isEmpty()) return@forEach
                Text(
                    text = when (language) {
                        AppLanguage.EN -> strings.english
                        AppLanguage.ES -> strings.spanish
                        AppLanguage.DE -> strings.german
                        AppLanguage.MULTI -> strings.multilingual
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    providers.forEach { provider ->
                        val providerEnabled = provider.id !in disabledProviderIds
                        val providerAccessLocked = provider.isAdultOnly && !adultOnlyProvidersEnabled
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = provider.displayName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (provider.isAdultOnly) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = providerEnabled,
                                enabled = !providerAccessLocked,
                                onCheckedChange = { nextEnabled ->
                                    if (!nextEnabled) {
                                        onProviderEnabledChange(provider.id, false)
                                    } else {
                                        pinError = ""
                                        pinValue = ""
                                        pinConfirmation = ""
                                        pendingProviderId = provider.id
                                        pendingProviderEnabled = true
                                        pinMode = if (adultContentPinIsConfigured) PinMode.VERIFY else PinMode.CREATE
                                        showPinDialog = true
                                    }
                                },
                            )
                        }
                        if (providerAccessLocked) {
                            Text(
                                text = strings.adultOnlyProvidersDescription,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text(strings.enterParentalPin) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.enterParentalPinDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = pinValue,
                        onValueChange = { pinValue = it; pinError = "" },
                        label = { Text(strings.parentalPin) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (pinError.isNotBlank()) {
                        Text(pinError, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (onVerifyAdultContentPin(pinValue)) {
                            onProviderEnabledChange(pendingProviderId, pendingProviderEnabled)
                            showPinDialog = false
                        } else {
                            pinError = strings.parentalPinInvalid
                        }
                    }
                ) {
                    Text(strings.save)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text(strings.cancel)
                }
            },
        )
    }
}

private enum class PinMode {
    NONE,
    CREATE,
    VERIFY,
}

@Composable
fun UpdatesSettingsScreen(
    strings: AppStrings,
    versionName: String,
    updateState: AppUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            UpdatesSettingsSection(
                strings = strings,
                versionName = versionName,
                updateState = updateState,
                onCheckForUpdates = onCheckForUpdates,
                onDownloadUpdate = onDownloadUpdate,
                onInstallUpdate = onInstallUpdate,
                onOpenReleasePage = onOpenReleasePage,
            )
        }
    }
}

@Composable
fun MyAnimeListSettingsScreen(
    strings: AppStrings,
    malUiState: MyAnimeListUiState,
    onMalConnect: () -> Unit,
    onMalSync: () -> Unit,
    onMalDisconnect: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            MyAnimeListSettingsSection(
                strings = strings,
                malUiState = malUiState,
                onMalConnect = onMalConnect,
                onMalSync = onMalSync,
                onMalDisconnect = onMalDisconnect,
            )
        }
    }
}

@Composable
fun BackupSettingsScreen(
    strings: AppStrings,
    onExportJsonBackup: () -> Unit,
    onImportJsonBackup: () -> Unit,
    onExportDatabaseBackup: () -> Unit,
    onImportDatabaseBackup: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            BackupSettingsSection(
                strings = strings,
                onExportJsonBackup = onExportJsonBackup,
                onImportJsonBackup = onImportJsonBackup,
                onExportDatabaseBackup = onExportDatabaseBackup,
                onImportDatabaseBackup = onImportDatabaseBackup,
            )
        }
    }
}

@Composable
private fun SettingsCardScreen(
    content: @Composable ColumnScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun UpdatesSettingsSection(
    strings: AppStrings,
    versionName: String,
    updateState: AppUpdateUiState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenReleasePage: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.updates, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(strings.currentVersionLabel(versionName), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (updateState != AppUpdateUiState.Disabled) {
                Button(onClick = onOpenReleasePage) { Text(strings.releasePage) }
            }
            when (val state = updateState) {
                AppUpdateUiState.Disabled -> {
                    Text(strings.updaterNotConfigured, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onCheckForUpdates, enabled = false) { Text(strings.checkForUpdates) }
                }
                AppUpdateUiState.Idle -> Button(onClick = onCheckForUpdates) { Text(strings.checkForUpdates) }
                AppUpdateUiState.Checking -> {
                    Button(onClick = onCheckForUpdates, enabled = false) { Text(strings.checkForUpdates) }
                    CircularProgressIndicator()
                }
                is AppUpdateUiState.UpToDate -> {
                    Text(strings.noUpdateAvailable, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onCheckForUpdates) { Text(strings.checkForUpdates) }
                }
                is AppUpdateUiState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onCheckForUpdates) { Text(strings.checkForUpdates) }
                }
                is AppUpdateUiState.Available -> {
                    Text(strings.updateAvailableLabel(state.release.versionLabel), color = MaterialTheme.colorScheme.primary)
                    Button(onClick = onDownloadUpdate) { Text(strings.downloadUpdate) }
                    if (state.release.body.isNotBlank()) {
                        Text(strings.releaseNotes, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        MarkdownReleaseNotes(
                            markdown = state.release.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is AppUpdateUiState.Downloading -> {
                    Text(strings.updateAvailableLabel(state.release.versionLabel), color = MaterialTheme.colorScheme.primary)
                    Text("${state.progressPercent}% ${strings.downloading.lowercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CircularProgressIndicator(progress = { state.progressPercent / 100f })
                }
                is AppUpdateUiState.Downloaded -> {
                    Text(strings.updateAvailableLabel(state.release.versionLabel), color = MaterialTheme.colorScheme.primary)
                    Button(onClick = onInstallUpdate) { Text(strings.installUpdate) }
                }
            }
        }
    }
}

@Composable
private fun MyAnimeListSettingsSection(
    strings: AppStrings,
    malUiState: MyAnimeListUiState,
    onMalConnect: () -> Unit,
    onMalSync: () -> Unit,
    onMalDisconnect: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.myAnimeList, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(strings.myAnimeListDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    strings.myAnimeListDisclaimer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (malUiState.isConnected) {
                Text(
                    "${strings.malConnected}${if (malUiState.username.isNotBlank()) ": ${malUiState.username}" else ""}",
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (malUiState.isConfigured) {
                Text(strings.malDisconnected, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(strings.malNotConfigured, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onMalConnect,
                    enabled = malUiState.isConfigured && !malUiState.isConnected,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.malConnect)
                }
                Button(
                    onClick = onMalSync,
                    enabled = malUiState.isConnected && !malUiState.isSyncing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.malSyncNow)
                }
                OutlinedButton(
                    onClick = onMalDisconnect,
                    enabled = malUiState.isConfigured,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.LinkOff, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.malDisconnect)
                }
            }
            if (malUiState.isSyncing) {
                if (malUiState.syncItemsTotal > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (malUiState.syncItemsProcessed.toFloat() / malUiState.syncItemsTotal.toFloat()).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (malUiState.syncStageMessage.isNotBlank()) {
                    Text(malUiState.syncStageMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (malUiState.syncItemsTotal > 0) {
                    Text(
                        buildMalSyncProgressDetail(
                            processed = malUiState.syncItemsProcessed,
                            total = malUiState.syncItemsTotal,
                            etaSeconds = malUiState.syncEtaSeconds,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (malUiState.errorMessage.isNotBlank()) {
                Text(malUiState.errorMessage, color = MaterialTheme.colorScheme.error)
            } else if (malUiState.lastMessage.isNotBlank()) {
                Text(malUiState.lastMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BackupSettingsSection(
    strings: AppStrings,
    onExportJsonBackup: () -> Unit,
    onImportJsonBackup: () -> Unit,
    onExportDatabaseBackup: () -> Unit,
    onImportDatabaseBackup: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.backup, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(strings.backupDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(strings.jsonBackup, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            BackupActionRow(
                leftLabel = strings.exportJsonBackup,
                rightLabel = strings.importJsonBackup,
                onLeftClick = onExportJsonBackup,
                onRightClick = onImportJsonBackup,
            )
            Text(strings.databaseBackup, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            BackupActionRow(
                leftLabel = strings.exportDatabaseBackup,
                rightLabel = strings.importDatabaseBackup,
                onLeftClick = onExportDatabaseBackup,
                onRightClick = onImportDatabaseBackup,
            )
        }
    }
}

@Composable
private fun SettingsNavigationItem(
    icon: @Composable () -> Unit,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            icon()
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LanguageButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = segmentedButtonColors(selected = selected),
    ) {
        Text(label)
    }
}

private val preferredLanguageButtons = listOf(AppLanguage.EN, AppLanguage.ES, AppLanguage.DE)

private fun preferredLanguageLabel(strings: AppStrings, language: AppLanguage): String = when (language) {
    AppLanguage.EN -> strings.english
    AppLanguage.ES -> strings.spanish
    AppLanguage.DE -> strings.german
    AppLanguage.MULTI -> strings.multilingual
}

private fun buildMalSyncProgressDetail(
    processed: Int,
    total: Int,
    etaSeconds: Int?,
): String {
    val safeProcessed = processed.coerceAtLeast(0)
    val safeTotal = total.coerceAtLeast(0)
    val percent = if (safeTotal > 0) ((safeProcessed.toFloat() / safeTotal.toFloat()) * 100f).toInt().coerceIn(0, 100) else 0
    val progress = "$safeProcessed/$safeTotal  $percent%"
    val eta = etaSeconds?.takeIf { it > 0 }?.let(::formatMalEta)
    return if (eta != null) "$progress  $eta" else progress
}

private fun formatMalEta(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "~${minutes}m ${seconds}s" else "~${seconds}s"
}

@Composable
private fun BackupActionRow(
    leftLabel: String,
    rightLabel: String,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onLeftClick,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(text = leftLabel, textAlign = TextAlign.Center, maxLines = 2)
        }
        Button(
            onClick = onRightClick,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(text = rightLabel, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
private fun segmentedButtonColors(selected: Boolean): ButtonColors =
    ButtonDefaults.buttonColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    )
