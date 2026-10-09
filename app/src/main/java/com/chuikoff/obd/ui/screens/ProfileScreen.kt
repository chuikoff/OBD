package com.chuikoff.obd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chuikoff.obd.BuildConfig
import com.chuikoff.obd.vehicle.VehicleProfile
import com.chuikoff.obd.viewmodel.ObdUiState

@Composable
fun ProfileScreen(
    state: ObdUiState,
    onSelect: (VehicleProfile) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Профиль автомобиля", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Версия приложения: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "По умолчанию выбран Outlander GF/GG. Добавляйте модели через VehicleProfileRegistry.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn {
            items(state.profiles, key = { it.id }) { profile ->
                val selected = profile.id == state.selectedProfile.id
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSelect(profile) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { onSelect(profile) })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(profile.displayNameRu, style = MaterialTheme.typography.titleMedium)
                            Text("Годы: ${profile.years}", style = MaterialTheme.typography.bodySmall)
                            Text(profile.notesRu, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
