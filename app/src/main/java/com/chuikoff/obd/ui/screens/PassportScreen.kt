package com.chuikoff.obd.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.chuikoff.obd.passport.MonitorStatus
import com.chuikoff.obd.passport.Passport
import com.chuikoff.obd.passport.PassportData
import com.chuikoff.obd.viewmodel.ConnectionState
import com.chuikoff.obd.viewmodel.ObdUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PassportScreen(state: ObdUiState, onRefresh: () -> Unit, exportText: () -> String) {
    val context = LocalContext.current
    val connected = state.connectionState is ConnectionState.Connected
    val d = state.passport
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Авто — паспорт", style = MaterialTheme.typography.headlineSmall)
            Text(
                state.selectedProfile.displayNameRu,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            d?.let {
                Text(
                    "Обновлено: " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(it.timestampMs)) +
                        if (it.profileName != state.selectedProfile.displayNameRu) " (профиль: ${it.profileName})" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onRefresh,
                    enabled = connected && !state.passportBusy,
                    modifier = Modifier.weight(1.2f)
                ) { Text(if (state.passportBusy) "Чтение…" else "Обновить", maxLines = 1) }
                OutlinedButton(
                    onClick = { copy(context, exportText()) },
                    enabled = d != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Копировать", maxLines = 1) }
                OutlinedButton(
                    onClick = { share(context, exportText()) },
                    enabled = d != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Поделиться", maxLines = 1) }
            }
            if (!connected) {
                Text(
                    if (d == null) "Подключите адаптер и нажмите «Обновить»." else "Нет подключения — показан сохранённый паспорт.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.passportBusy) Text("Опрос датчиков приостановлен на время чтения…", style = MaterialTheme.typography.bodySmall)
            state.passportStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
        if (d != null) {
            item { VinCard(d) }
            item { IdsCard(d) }
            item { ReadinessCard(d) }
            item { PidsCard(d) }
        } else {
            item { Text("Данных пока нет.", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Err(text: String?) {
    Text(text ?: Passport.NO_RESPONSE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun VinCard(d: PassportData) = Section("VIN") {
    if (d.vin == null) {
        Err(d.vinError)
    } else {
        val i = Passport.decodeVin(d.vin)
        Text(d.vin, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
        if (!i.valid) Text("Внимание: " + i.problems.joinToString("; "), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        (i.makerRu ?: i.countryRu)?.let { Text("Производитель/страна: $it", style = MaterialTheme.typography.bodyMedium) }
        i.modelYear?.let { Text("Модельный год: $it (или ${it - 30})", style = MaterialTheme.typography.bodyMedium) }
        i.plantCode?.let { Text("Код завода: $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun IdsCard(d: PassportData) = Section("Идентификаторы ЭБУ") {
    @Composable fun row(title: String, items: List<String>, err: String?) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (items.isEmpty()) Err(err) else items.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }
    row("Calibration ID", d.calIds, d.calError)
    row("CVN", d.cvns, d.cvnError)
    row("Имя ЭБУ", d.ecuNames, d.ecuError)
}

@Composable
private fun ReadinessCard(d: PassportData) = Section("Готовность мониторов") {
    val r = d.readiness
    if (r == null) { Err(d.readinessError); return@Section }
    Text(
        "MIL: ${if (r.milOn) "горит" else "не горит"} · DTC: ${r.dtcCount}",
        style = MaterialTheme.typography.bodyMedium,
        color = if (r.milOn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    )
    r.monitors.forEach { m ->
        val color = when (m.status) {
            MonitorStatus.READY -> Color(0xFF43A047)
            MonitorStatus.NOT_READY -> Color(0xFFFB8C00)
            MonitorStatus.NA -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(Modifier.fillMaxWidth()) {
            Text(m.nameRu, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(m.status.labelRu, style = MaterialTheme.typography.bodyMedium, color = color)
        }
    }
}

@Composable
private fun PidsCard(d: PassportData) = Section("Поддерживаемые PID (Mode 01)") {
    val p = d.pids01
    if (p == null) { Err(d.pids01Error); return@Section }
    Text("Всего: ${p.size}" + (d.pids09?.let { " · Mode 09: ${it.size}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
    p.forEach { Text(Passport.pidLabel(it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Паспорт авто", text))
    Toast.makeText(context, "Паспорт скопирован", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Паспорт авто")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Поделиться паспортом").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
