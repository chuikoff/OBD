package com.chuikoff.obd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chuikoff.obd.viewmodel.ConnectionState
import com.chuikoff.obd.viewmodel.ObdUiState

@Composable
fun DiagnosticsScreen(
    state: ObdUiState,
    onRead: () -> Unit,
    onClearConfirmed: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }
    val connected = state.connectionState is ConnectionState.Connected

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Ошибки (DTC)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        if (!connected) {
            Text(
                "Подключите адаптер на вкладке «Подключение», затем нажмите «Найти ошибки».",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
        }

        // Always show buttons; disable when disconnected / busy
        Button(
            onClick = onRead,
            enabled = connected && !state.dtcBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    state.dtcBusy -> "Поиск…"
                    else -> "Найти ошибки"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showClearDialog = true },
            enabled = connected && !state.dtcBusy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Сбросить ошибки")
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "NODATA от ELM обычно = кодов нет",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        state.dtcStatus?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        state.dtcError?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))
        Text("Сохранённые (Mode 03)", style = MaterialTheme.typography.titleMedium)
        when {
            !state.dtcScanned -> {
                Text("Ещё не читали — нажмите «Найти ошибки»", style = MaterialTheme.typography.bodyMedium)
            }
            state.dtcCodes.isEmpty() -> {
                Text("Ошибок не найдено", style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                LazyColumn(
                    Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.dtcCodes) { code ->
                        DtcCard(code)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Текущие / pending (Mode 07)", style = MaterialTheme.typography.titleMedium)
        when {
            !state.dtcScanned -> {
                Text("—", style = MaterialTheme.typography.bodyMedium)
            }
            state.dtcPending.isEmpty() -> {
                Text("Pending-кодов нет", style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                LazyColumn(
                    Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.dtcPending) { code ->
                        DtcCard(code, pending = true)
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Сбросить ошибки?") },
            text = {
                Text(
                    "Mode 04 очистит сохранённые коды ошибок. " +
                        "Индикатор Check Engine может погаснуть, мониторы готовности (readiness) сбросятся. " +
                        "Продолжить только если вы понимаете последствия."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearConfirmed()
                    }
                ) { Text("Сбросить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Отмена") }
            }
        )
    }
}

@Composable
private fun DtcCard(code: String, pending: Boolean = false) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (pending)
                MaterialTheme.colorScheme.tertiaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            code,
            Modifier.padding(12.dp),
            style = MaterialTheme.typography.titleMedium
        )
    }
}
