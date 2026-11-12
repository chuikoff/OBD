package com.chuikoff.obd.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chuikoff.obd.BuildConfig
import com.chuikoff.obd.alerts.AlertSettings
import com.chuikoff.obd.vehicle.VehicleProfile
import com.chuikoff.obd.viewmodel.ObdUiState

@Composable
fun ProfileScreen(
    state: ObdUiState,
    onSelect: (VehicleProfile) -> Unit,
    onAlertSettings: (AlertSettings) -> Unit = {},
    onTestAlert: () -> Unit = {},
    onAutoConnect: (Boolean) -> Unit = {},
    onAutoConnectBluetooth: (Boolean) -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        state.profiles.forEach { profile ->
            val selected = profile.id == state.selectedProfile.id
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable { onSelect(profile) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = { onSelect(profile) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(profile.displayNameRu, style = MaterialTheme.typography.titleMedium)
                        Text("Годы: ${profile.years}", style = MaterialTheme.typography.bodySmall)
                        Text(profile.notesRu, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        AlertsCard(state.alertSettings, onAlertSettings, onTestAlert)
        Spacer(Modifier.height(16.dp))
        AutoConnectCard(state, onAutoConnect, onAutoConnectBluetooth)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AlertsCard(s: AlertSettings, onChange: (AlertSettings) -> Unit, onTest: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text("Предупреждения", style = MaterialTheme.typography.titleMedium)
            Text(
                "Звук/вибрация + уведомление (видно и в Android Auto). Без спама: повтор только после " +
                    "остывания на 3 °C и не чаще раза в 10 мин; гололёд — раз за поездку.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ThresholdRow("CVT ≥", s.cvtEnabled, s.cvtHotC, AlertSettings.CVT_RANGE,
                { onChange(s.copy(cvtEnabled = it)) }, { onChange(s.copy(cvtHotC = it)) })
            ThresholdRow("ОЖ ≥", s.coolantEnabled, s.coolantHotC, AlertSettings.COOLANT_RANGE,
                { onChange(s.copy(coolantEnabled = it)) }, { onChange(s.copy(coolantHotC = it)) })
            ThresholdRow("Снаружи ≤", s.iceEnabled, s.iceC, AlertSettings.ICE_RANGE,
                { onChange(s.copy(iceEnabled = it)) }, { onChange(s.copy(iceC = it)) })
            SwitchRow("Звук", s.sound) { onChange(s.copy(sound = it)) }
            SwitchRow("Вибрация", s.vibrate) { onChange(s.copy(vibrate = it)) }
            OutlinedButton(onClick = onTest) { Text("Проверить предупреждение") }
        }
    }
}

@Composable
private fun ThresholdRow(
    label: String,
    enabled: Boolean,
    value: Int,
    range: IntRange,
    onEnabled: (Boolean) -> Unit,
    onValue: (Int) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(88.dp))
        TextButton(onClick = { onValue((value - 1).coerceIn(range)) }, enabled = enabled) { Text("−") }
        Text(
            (if (value > 0 && label.startsWith("Снаружи")) "+" else "") + "$value °C",
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { onValue((value + 1).coerceIn(range)) }, enabled = enabled) { Text("+") }
        Spacer(Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onEnabled)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AutoConnectCard(state: ObdUiState, onAuto: (Boolean) -> Unit, onAutoBt: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text("Автоподключение", style = MaterialTheme.typography.titleMedium)
            val last = state.lastAdapter
            Text(
                if (last != null) "Последний адаптер: ${last.name ?: "без имени"} (${last.address}, ${if (last.transport.name == "BLE") "BLE" else "Classic"})"
                else "Адаптер ещё не подключался — подключи один раз вручную.",
                style = MaterialTheme.typography.bodySmall
            )
            SwitchRow("При запуске приложения и Android Auto", state.autoConnect, onAuto)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Когда телефон подключается к Bluetooth машины / адаптера",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = state.autoConnectOnBluetooth && state.autoConnect, enabled = state.autoConnect, onCheckedChange = onAutoBt)
            }
            Text(
                "До 10 попыток (пауза 2→30 с), потом стоп. Если ЭБУ молчит 3 мин (зажигание выключено) — " +
                    "приложение отключается само, чтобы не сажать батарею. После «Отключить» Bluetooth-события " +
                    "30 мин не переподключают.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
