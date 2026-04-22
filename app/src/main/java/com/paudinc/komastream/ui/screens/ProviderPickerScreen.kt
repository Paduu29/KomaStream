package com.paudinc.komastream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paudinc.komastream.data.model.AppLanguage
import com.paudinc.komastream.provider.MangaProvider
import com.paudinc.komastream.ui.components.cardBorder
import com.paudinc.komastream.utils.AppStrings

@Composable
fun ProviderPickerScreen(
    strings: AppStrings,
    selectedProviderId: String,
    providersByLanguage: Map<AppLanguage, List<MangaProvider>>,
    onSelectProvider: (String) -> Unit,
    onOpenProviderSite: (String) -> Unit,
) {
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
                    Text(strings.chooseProvider, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(strings.chooseProviderDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        AppLanguage.entries.forEach { language ->
            val providers = providersByLanguage[language].orEmpty()
            if (providers.isEmpty()) return@forEach
            item {
                Text(
                    text = if (language == AppLanguage.EN) strings.english else strings.spanish,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            items(providers) { provider ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = if (selectedProviderId == provider.id) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(26.dp),
                        )
                        .clickable { onSelectProvider(provider.id) },
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (selectedProviderId == provider.id) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AsyncImage(
                            model = provider.logoUrl,
                            contentDescription = provider.displayName,
                            modifier = Modifier.size(44.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(provider.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                provider.websiteUrl,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            AssistChip(
                                onClick = { onOpenProviderSite(provider.websiteUrl) },
                                label = { Text(strings.openProviderSite) },
                            )
                        }
                    }
                }
            }
        }
    }
}
