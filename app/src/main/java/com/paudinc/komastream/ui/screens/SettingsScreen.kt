package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.ui.components.cardBorder
import com.paudinc.komastream.updater.AppUpdateUiState
import com.paudinc.komastream.utils.AppStrings

@Composable
fun SettingsScreen(
    strings: AppStrings,
    appLanguage: AppLanguage,
    useDarkTheme: Boolean,
    autoJumpToUnread: Boolean,
    versionName: String,
    updateState: AppUpdateUiState,
    onLanguageChange: (AppLanguage) -> Unit,
    onThemeChange: (Boolean) -> Unit,
    onAutoJumpToUnreadChange: (Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
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
            ElevatedCard(
                modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.updates, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(strings.currentVersionLabel(versionName), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (val state = updateState) {
                        AppUpdateUiState.Disabled -> {
                            Text(strings.updaterNotConfigured, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = onCheckForUpdates, enabled = false) { Text(strings.checkForUpdates) }
                        }
                        AppUpdateUiState.Idle -> {
                            Button(onClick = onCheckForUpdates) { Text(strings.checkForUpdates) }
                        }
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
                            Button(onClick = onOpenReleasePage) { Text(strings.releasePage) }
                            if (state.release.body.isNotBlank()) {
                                Text(strings.releaseNotes, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(state.release.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            Button(onClick = onOpenReleasePage) { Text(strings.releasePage) }
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.languageLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onLanguageChange(AppLanguage.EN) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (appLanguage == AppLanguage.EN) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (appLanguage == AppLanguage.EN) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) { Text(strings.english) }
                        Button(
                            onClick = { onLanguageChange(AppLanguage.ES) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (appLanguage == AppLanguage.ES) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (appLanguage == AppLanguage.ES) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) { Text(strings.spanish) }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.theme, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onThemeChange(false) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!useDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (!useDarkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Icon(Icons.Default.LightMode, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.light)
                        }
                        Button(
                            onClick = { onThemeChange(true) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (useDarkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (useDarkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Icon(Icons.Default.DarkMode, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.dark)
                        }
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.reader, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = autoJumpToUnread, onCheckedChange = onAutoJumpToUnreadChange)
                        Text(strings.autoJumpToUnreadLabel)
                    }
                }
            }
        }
        item {
            ElevatedCard(
                modifier = Modifier.border(cardBorder(), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(strings.backup, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(strings.backupDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onExportBackup, modifier = Modifier.weight(1f)) { Text(strings.exportBackup) }
                        Button(onClick = onImportBackup, modifier = Modifier.weight(1f)) { Text(strings.importBackup) }
                    }
                }
            }
        }
    }
}
