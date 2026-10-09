package com.chuikoff.obd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chuikoff.obd.connection.DiscoveredDevice
import com.chuikoff.obd.connection.TransportType
import com.chuikoff.obd.viewmodel.ConnectionState
import com.chuikoff.obd.viewmodel.ObdUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    state: ObdUiState,
    onTransport: (TransportType) -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onRefreshBonded: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Подключение ELM327", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.preferredTransport == TransportType.CLASSIC_SPP,
                onClick = { onTransport(TransportType.CLASSIC_SPP) },
                label = { Text("Classic SPP") }
            )
            FilterChip(
                selected = state.preferredTransport == TransportType.BLE,
                onClick = { onTransport(TransportType.BLE) },
                label = { Text("BLE") }
            )
        }

        Spacer(Modifier.height(12.dp))

        when (state.connectionState) {
            ConnectionState.Disconnected -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onScan) {
                        Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                        Text("  Поиск")
                    }
                    if (state.preferredTransport == TransportType.CLASSIC_SPP) {
                        OutlinedButton(onClick = onRefreshBonded) {
                            Text("Сопряжённые")
                        }
                    }
                    if (state.isScanning) {
                        OutlinedButton(onClick = onStopScan) { Text("Стоп") }
                    }
                }
            }
            ConnectionState.Connecting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("Подключение…")
                }
            }
            ConnectionState.Connected -> {
                Text(
                    "Подключено: ${state.connectedAddress}",
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onDisconnect) { Text("Отключить") }
            }
        }

        state.statusMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        state.errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Text("Устройства", style = MaterialTheme.typography.titleMedium)

        if (state.devices.isEmpty()) {
            Text(
                "Устройства не найдены. Для Classic сначала сопрягите адаптер в настройках Android.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(state.devices, key = { it.address + it.transportType }) { device ->
                DeviceRow(device) {
                    if (state.connectionState is ConnectionState.Disconnected) {
                        onConnect(device)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors()
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Bluetooth, contentDescription = null)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(device.name ?: "Без имени", style = MaterialTheme.typography.titleMedium)
                Text(device.address, style = MaterialTheme.typography.bodySmall)
                Text(
                    when (device.transportType) {
                        TransportType.CLASSIC_SPP -> "Bluetooth Classic"
                        TransportType.BLE -> "BLE${device.rssi?.let { " · $it dBm" } ?: ""}"
                    },
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
