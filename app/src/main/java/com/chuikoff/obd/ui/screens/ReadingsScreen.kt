package com.chuikoff.obd.ui.screens

import com.chuikoff.obd.ui.copyText
import com.chuikoff.obd.ui.shareText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.chuikoff.obd.sensors.CvtFormula
import com.chuikoff.obd.sensors.SensorReading
import com.chuikoff.obd.ui.TempColorScale
import com.chuikoff.obd.viewmodel.ConnectionState
import com.chuikoff.obd.viewmodel.ObdUiState


@Composable
fun ReadingsScreen(
    state: ObdUiState,
    onCvtFormula: (CvtFormula) -> Unit = {},
    onCvtDebugExtra: (Boolean) -> Unit = {},
    onClearCvtLog: () -> Unit = {},
    cvtExport: () -> String = { "" },
    onSaveDiag: () -> Unit = {},
    onShareDiag: () -> Unit = {}
) {
    val showCvtDebug = state.selectedProfile.id == "mitsubishi_outlander_gf_gg"
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Показания", style = MaterialTheme.typography.headlineSmall)
        Text(
            state.selectedProfile.displayNameRu,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(12.dp))

        when (state.connectionState) {
            is ConnectionState.Disconnected -> {
                Text("Подключите адаптер на вкладке «Подключение».")
            }
            is ConnectionState.Connecting -> {
                Text("Ожидание подключения…")
            }
            is ConnectionState.Connected -> {
                state.linkNote?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (state.readings.isEmpty()) {
                    Text(if (state.isPolling) "Опрос датчиков…" else "Нет данных")
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.readings, key = { it.sensorId }) { reading ->
                        ReadingCard(reading)
                    }
                    item(key = "diagnostics") {
                        DiagnosticsBlock(state, showCvtDebug, onCvtFormula, onCvtDebugExtra, onClearCvtLog, cvtExport, onSaveDiag, onShareDiag)
                    }
                }
            }
        }
        if (showCvtDebug && state.connectionState !is ConnectionState.Connected && state.cvtLog.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            DiagnosticsBlock(state, showCvtDebug, onCvtFormula, onCvtDebugExtra, onClearCvtLog, cvtExport, onSaveDiag, onShareDiag)
        }
    }
}

/** Collapsible «Диагностика»: CVT card + per-sensor technical info (legend, source, RAW, filter). */
@Composable
private fun DiagnosticsBlock(
    state: ObdUiState,
    showCvtDebug: Boolean,
    onCvtFormula: (CvtFormula) -> Unit,
    onCvtDebugExtra: (Boolean) -> Unit,
    onClearCvtLog: () -> Unit,
    cvtExport: () -> String,
    onSaveDiag: () -> Unit,
    onShareDiag: () -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Диагностика", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Свернуть" else "Развернуть") }
            }
            DiagSaveCard(state, onSaveDiag, onShareDiag)
            if (open) {
                if (showCvtDebug) {
                    Spacer(Modifier.height(8.dp))
                    CvtDebugCard(state, onCvtFormula, onCvtDebugExtra, onClearCvtLog, cvtExport)
                } else if (state.cvtLog.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    GearboxLogCard(state, onClearCvtLog, cvtExport)
                }
                state.readings.forEach { r ->
                    Spacer(Modifier.height(8.dp))
                    SensorTechInfo(r)
                }
                if (showCvtDebug) {
                    Spacer(Modifier.height(8.dp))
                    CvtOilTechInfo(state.cvtOilAttempt ?: state.cvtOil)
                }
            }
        }
    }
}

/** EXPERIMENTAL 2110: every decode candidate + exact raw, to confirm the right bytes in the car. */
@Composable
private fun CvtOilTechInfo(r: com.chuikoff.obd.sensors.CvtOilDegradation.Result?) {
    val small = MaterialTheme.typography.labelSmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Text("Деградация масла CVT (2110 @7E1, экспериментально)", style = MaterialTheme.typography.titleSmall)
        if (r == null) {
            Text("Ещё не читалось (читается раз за подключение, ~30 с после старта, или кнопкой на вкладке «Авто»)", style = small, color = dim)
            return@Column
        }
        Text("Показано: ${r.value ?: "—"} · ${r.source}", style = small, color = dim)
        Text("Варианты: ${r.candidatesText}", style = small, color = dim, fontFamily = FontFamily.Monospace)
        r.error?.let { Text(it, style = small, color = MaterialTheme.colorScheme.error) }
        r.payloadHex?.let { Text("Данные после 61 10: $it", style = small, fontFamily = FontFamily.Monospace) }
        if (r.rawVisible.isNotBlank()) Text("RAW: ${r.rawVisible}", style = small, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SensorTechInfo(reading: SensorReading) {
    val kind = TempColorScale.kindFor(reading.sensorId, reading.displayName)
    val small = MaterialTheme.typography.labelSmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Text(reading.displayName, style = MaterialTheme.typography.titleSmall)
        reading.rawValue?.let { Text("Сырой датчик: %.1f °C".format(it), style = small, color = dim) }
        reading.detail?.let { Text(it, style = small, color = dim) }
        if (kind != null) Text("Шкала: " + TempColorScale.legend(kind), style = small, color = dim)
        if (reading.sensorId == "cvt_temp" && reading.statusLine == null) {
            Text("Блокировка ГТ: — (нет данных 2103)", style = small, color = dim)
        }
        reading.note?.let { Text("Источник: $it", style = small, color = dim) }
        reading.error?.let { Text(it, style = small, color = MaterialTheme.colorScheme.error) }
        reading.rawHex?.let { Text("RAW: $it", style = small, fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun ReadingCard(reading: SensorReading) {
    val kind = TempColorScale.kindFor(reading.sensorId, reading.displayName)
    // No color for "—": band only when a value is actually shown
    val band = if (kind != null && reading.value != null)
        TempColorScale.band(kind, reading.value) else null
    val valueColor = (band?.argb ?: reading.valueArgb)?.let { Color(it) }
    val cardTint = when {
        valueColor != null -> valueColor.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardTint)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(reading.displayName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            val valueText = when {
                reading.value == null -> "—"
                reading.displayText != null -> reading.displayText
                kind != null -> TempColorScale.valueText(kind, reading.value, reading.unit)
                else -> String.format("%.0f %s", reading.value, reading.unit)
            }
            Text(
                valueText,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alpha(if (reading.isStale) 0.55f else 1f)
            )
            reading.statusLine?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = reading.statusArgb?.let { Color(it) }
                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(if (reading.isStale) 0.55f else 1f)
                )
            }
            if (reading.value == null) {
                Text(
                    "нет данных",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}


/** 6AT (Creta) ATF probe log: every candidate's RAW, copy/share to pick the right byte. */
@Composable
private fun GearboxLogCard(state: ObdUiState, onClearCvtLog: () -> Unit, cvtExport: () -> String) {
    val context = LocalContext.current
    val log = state.cvtLog
    val small = MaterialTheme.typography.labelSmall
    Column(Modifier.fillMaxWidth()) {
        Text("Лог коробки (экспериментально)", style = MaterialTheme.typography.titleMedium)
        log.filter { it.request.contains("probe") || it.request.contains("raw ") }.takeLast(6).forEach { e ->
            Text(
                "${e.request} → ${e.valueC?.let { "%.0f °C".format(it) } ?: "—"} · ${e.candidates ?: e.frameSummary}",
                style = small, fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
            OutlinedButton(onClick = { copyText(context, "Gearbox log", "Лог коробки скопирован", cvtExport()) }, modifier = Modifier.weight(1f), contentPadding = pad) {
                Text("Копировать (${log.size})", maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(onClick = { shareText(context, "OBD — лог коробки", "Поделиться логом коробки", cvtExport()) }, modifier = Modifier.weight(1f), contentPadding = pad) {
                Text("Поделиться", maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = onClearCvtLog, modifier = Modifier.weight(0.8f), contentPadding = pad) {
                Text("Очистить", maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** CVT diagnostics: last frame decode, formula choice, copy/share of the last ~50 raw replies. */
@Composable
private fun CvtDebugCard(
    state: ObdUiState,
    onCvtFormula: (CvtFormula) -> Unit,
    onCvtDebugExtra: (Boolean) -> Unit,
    onClearCvtLog: () -> Unit,
    cvtExport: () -> String
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val log = state.cvtLog
    val last2103 = log.lastOrNull { it.request == "2103" }
    val last2102 = log.lastOrNull { it.request == "2102" }

    Column(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Диагностика CVT (2103)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Свернуть" else "Развернуть")
                }
            }
            if (last2103 != null) {
                Text(
                    "Кадр: ${last2103.frameSummary}",
                    style = MaterialTheme.typography.labelSmall
                )
                last2103.candidates?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                last2103.error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("Пока нет ответов 2103", style = MaterialTheme.typography.labelSmall)
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text("Формула count → °C", style = MaterialTheme.typography.labelLarge)
                CvtFormula.entries.forEach { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = state.cvtFormula == f, onClick = { onCvtFormula(f) }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = state.cvtFormula == f, onClick = { onCvtFormula(f) })
                        Text(f.titleRu, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "Проверка: после ночи стоянки (холодный старт) CVT ≈ температуре снаружи. " +
                        "Правильная формула та, что на холодную показывает ≈ улицу.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Также читать 2102 (напряжение датчика)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = state.cvtDebugExtra, onCheckedChange = onCvtDebugExtra)
                }
                last2102?.let { e ->
                    Text(e.candidates ?: e.frameSummary, style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace)
                }
                last2103?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("RAW как есть: ${it.rawVisible}", style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                OutlinedButton(onClick = { copyText(context, "CVT log", "CVT лог скопирован", cvtExport()) }, modifier = Modifier.weight(1f), contentPadding = pad) {
                    Text("Копировать (${log.size})", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = { shareText(context, "OBD Outlander — CVT лог", "Поделиться CVT логом", cvtExport()) }, modifier = Modifier.weight(1f), contentPadding = pad) {
                    Text("Поделиться", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onClearCvtLog, modifier = Modifier.weight(0.8f), contentPadding = pad) {
                    Text("Очистить", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}


