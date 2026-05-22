package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paudinc.komastream.R
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.ui.components.MangadotAwareAsyncImage
import com.paudinc.komastream.ui.components.cardBorder
import com.paudinc.komastream.utils.AppStrings
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun ProviderPickerScreen(
    strings: AppStrings,
    selectedProviderId: String,
    adultOnlyProvidersEnabled: Boolean,
    adultContentPinIsConfigured: Boolean,
    disabledProviderIds: Set<String>,
    providersByLanguage: Map<AppLanguage, List<MangaProvider>>,
    onSelectProvider: (String) -> Unit,
    onToggleProviderEnabled: (String, Boolean) -> Unit,
    onVerifyAdultContentPin: (String) -> Boolean,
) {
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinValue by rememberSaveable { mutableStateOf("") }
    var pinError by rememberSaveable { mutableStateOf("") }
    var pendingProviderId by rememberSaveable { mutableStateOf("") }
    var pendingProviderEnabled by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                        MaterialTheme.colorScheme.background,
                    )
                )
            ),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(cardBorder(), RoundedCornerShape(28.dp)),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        strings.chooseProvider,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(strings.chooseProviderDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AppLanguage.entries.forEach { language ->
            val providers = providersByLanguage[language].orEmpty()
            if (providers.isEmpty()) return@forEach
            item {
                Text(
                    text = when (language) {
                        AppLanguage.EN -> strings.english
                        AppLanguage.ES -> strings.spanish
                        AppLanguage.DE -> strings.german
                        AppLanguage.MULTI -> strings.multilingual
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(providers) { provider ->
                val providerEnabled = provider.id !in disabledProviderIds
                val providerAccessLocked = provider.isAdultOnly && !adultOnlyProvidersEnabled
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = when {
                                selectedProviderId == provider.id && providerEnabled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                providerEnabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                            },
                            shape = RoundedCornerShape(26.dp),
                        )
                        .clickable(enabled = providerEnabled && !providerAccessLocked) { onSelectProvider(provider.id) },
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = when {
                            selectedProviderId == provider.id && providerEnabled -> MaterialTheme.colorScheme.primaryContainer
                            providerEnabled -> MaterialTheme.colorScheme.surface
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                        }
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        MangadotAwareAsyncImage(
                            model = provider.logoUrl,
                            contentDescription = provider.displayName,
                            modifier = Modifier.size(44.dp),
                            contentScale = ContentScale.Fit,
                            placeholder = painterResource(android.R.drawable.ic_menu_gallery),
                            error = painterResource(
                                when (provider.id) {
                                    "mangatube-de" -> {
                                        R.drawable.mt_logo
                                    }
                                    "akaicomic-en" -> {
                                        R.drawable.akai_comic
                                    }
                                    else -> {
                                        R.drawable.app_logo
                                    }
                                }
                            ),
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    provider.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (provider.isAdultContent) {
                                    Icon(
                                        imageVector = Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        Switch(
                            checked = providerEnabled,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    onToggleProviderEnabled(provider.id, false)
                                } else if (adultContentPinIsConfigured) {
                                    pinError = ""
                                    pinValue = ""
                                    pendingProviderId = provider.id
                                    pendingProviderEnabled = true
                                    showPinDialog = true
                                } else {
                                    onToggleProviderEnabled(provider.id, true)
                                }
                            },
                            enabled = !providerAccessLocked,
                        )
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
                            onToggleProviderEnabled(pendingProviderId, pendingProviderEnabled)
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
