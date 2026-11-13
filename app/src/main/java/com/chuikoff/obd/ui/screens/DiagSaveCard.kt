package com.chuikoff.obd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chuikoff.obd.viewmodel.ObdUiState

/** «Сохранить диагностику в файл» — one .txt in Загрузки/OBD with every log, then the share sheet. */
@Composable
fun DiagSaveCard(state: ObdUiState, onSave: () -> Unit, onShare: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = onSave, enabled = !state.diagBusy, modifier = Modifier.weight(1f)) {
                Text(if (state.diagBusy) "Сбор…" else "Сохранить диагностику в файл")
            }
            if (state.diagSaved != null && !state.diagBusy) {
                OutlinedButton(onClick = onShare) { Text("Отправить") }
            }
        }
        Text(
            state.diagStatus ?: "Файл .txt со всеми логами (ошибки, коробка, обмен с адаптером) → «Загрузки/OBD». " +
                "При подключении сначала дополнительно опрашивает адаптер и коробку (~30 с).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
