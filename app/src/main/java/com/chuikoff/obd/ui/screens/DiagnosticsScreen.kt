package com.chuikoff.obd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chuikoff.obd.diag.DtcEntry
import com.chuikoff.obd.diag.DtcReport
import com.chuikoff.obd.diag.ModuleResult
import com.chuikoff.obd.ui.copyText
import com.chuikoff.obd.ui.shareText
import com.chuikoff.obd.viewmodel.ConnectionState
import com.chuikoff.obd.viewmodel.ObdUiState

@Composable
fun DiagnosticsScreen(
    state: ObdUiState,
    onRead: () -> Unit,
    onFull: () -> Unit,
    exportText: () -> String,
    onClearConfirmed: () -> Unit,
    onSaveDiag: () -> Unit = {},
    onShareDiag: () -> Unit = {}
) {
    var showClearDialog by remember { mutableStateOf(false) }
    val connected = state.connectionState is ConnectionState.Connected
    val report = state.dtcReport

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Text("Ошибки (DTC)", style = MaterialTheme.typography.headlineSmall) }
        if (!connected) item {
            Text("Подключите адаптер на вкладке «Подключение».", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Button(onClick = onRead, enabled = connected && !state.dtcBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.dtcBusy) "Чтение…" else "Быстрая проверка (OBD)", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Button(onClick = onFull, enabled = connected && !state.dtcBusy, modifier = Modifier.fillMaxWidth()) {
                Text("Полная проверка (все блоки)", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Быстрая — коды двигателя/коробки по OBD (Check Engine). Полная — ещё ABS/ESP, подушки, руль, " +
                    "приборка, кузов, климат, TPMS и перебор всех адресов Hyundai/Kia (~1 мин).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item { DiagSaveCard(state, onSaveDiag, onShareDiag) }
        state.dtcStatus?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) } }
        state.dtcError?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        if (report != null) {
            item { MilCard(report) }
            val byEcu = report.allCodes.groupBy { it.ecuName }
            if (byEcu.isEmpty()) {
                item { Text("Ошибок не найдено", style = MaterialTheme.typography.titleMedium) }
            }
            byEcu.forEach { (ecu, codes) ->
                item { Text(ecu, style = MaterialTheme.typography.titleMedium) }
                items(codes) { DtcCard(it) }
            }
            report.modules?.let { mods ->
                item { Text("Блоки (полная проверка)", style = MaterialTheme.typography.titleMedium) }
                items(mods) { ModuleRow(it) }
            }
        } else if (!state.dtcBusy) {
            item { Text("Ещё не читали — нажмите «Быстрая проверка» или «Полная проверка».", style = MaterialTheme.typography.bodyMedium) }
        }

        item {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { showClearDialog = true },
                enabled = connected && !state.dtcBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Сбросить ошибки (Mode 04)") }
        }
        if (state.dtcLog.isNotEmpty()) item { RawLogCard(state, exportText) }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Сбросить ошибки?") },
            text = {
                Text(
                    "Mode 04 очистит сохранённые коды ошибок двигателя/коробки. " +
                        "Индикатор Check Engine может погаснуть, мониторы готовности (readiness) сбросятся. " +
                        "Ошибки ABS, подушек и других блоков этим не стираются."
                )
            },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false; onClearConfirmed() }) {
                    Text("Сбросить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun MilCard(r: DtcReport) {
    val on = r.milOn
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (on == true) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when (on) {
                    true -> "Check Engine: ГОРИТ"
                    false -> "Check Engine: не горит"
                    null -> "Check Engine: нет ответа 0101"
                },
                style = MaterialTheme.typography.titleMedium
            )
            r.mil.forEach {
                Text("${it.ecuId ?: "ЭБУ"}: MIL ${if (it.milOn) "вкл" else "выкл"}, кодов по OBD: ${it.dtcCount}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (on == true && r.allCodes.isEmpty()) {
                Text("Лампа горит, но коды не получены — нажмите «Полная проверка» и пришлите RAW лог.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!r.obdAnswered) {
                Text("Ни один ЭБУ не ответил на OBD-запросы (зажигание включено?).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun DtcCard(e: DtcEntry) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(e.code, style = MaterialTheme.typography.titleLarge)
            Text(e.kind.titleRu + (e.statusRu?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModuleRow(m: ModuleResult) {
    val text = when {
        !m.answered -> "не ответил"
        m.codes.isNotEmpty() -> "ОШИБОК: ${m.codes.size}"
        m.protocol != null -> "ошибок нет" + if (m.ignored > 0) " (неактивных в памяти: ${m.ignored})" else ""
        else -> "ответил, коды не прочитаны"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${m.module.name} · ${m.module.tx}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text, style = MaterialTheme.typography.bodyMedium,
            color = when {
                m.codes.isNotEmpty() -> MaterialTheme.colorScheme.error
                !m.answered -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            }
        )
    }
    m.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun RawLogCard(state: ObdUiState, exportText: () -> String) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RAW лог (${state.dtcLog.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Свернуть" else "Развернуть") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { copyText(context, "DTC log", "Лог ошибок скопирован", exportText()) },
                    modifier = Modifier.weight(1f)
                ) { Text("Копировать") }
                OutlinedButton(
                    onClick = { shareText(context, "OBD — ошибки (DTC)", "Поделиться логом ошибок", exportText()) },
                    modifier = Modifier.weight(1f)
                ) { Text("Поделиться") }
            }
            if (open) {
                state.dtcLog.forEach {
                    Text(it.toText(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
