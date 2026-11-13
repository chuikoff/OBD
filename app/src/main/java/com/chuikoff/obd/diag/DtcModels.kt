package com.chuikoff.obd.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a code came from. */
enum class DtcKind(val titleRu: String) {
    STORED("сохранённая (03)"),
    PENDING("текущая/pending (07)"),
    PERMANENT("постоянная (0A)"),
    UDS("блок, UDS 19 02"),
    KWP("блок, KWP 18")
}

data class DtcEntry(
    val code: String,
    /** CAN id of the answering ECU, e.g. "7E8"; null if unknown (headers off). */
    val ecuId: String?,
    val ecuName: String,
    val kind: DtcKind,
    /** Raw status byte (UDS / KWP), null for OBD modes. */
    val status: Int? = null,
    val statusRu: String? = null
)

/** One ECU in the full check. */
data class DiagModule(val name: String, val tx: String, val rx: String)

data class ModuleResult(
    val module: DiagModule,
    val answered: Boolean,
    /** «UDS 19 02», «KWP 18 02», or null if silent. */
    val protocol: String?,
    val codes: List<DtcEntry>,
    /** Codes the ECU listed but which are not active/confirmed (e.g. status 0x50). */
    val ignored: Int = 0,
    val note: String? = null
)

/** Mode 01 PID 01 per ECU. */
data class MilStatus(val ecuId: String?, val milOn: Boolean, val dtcCount: Int)

data class DtcReport(
    val timestampMs: Long,
    val mil: List<MilStatus>,
    /** Mode 03/07/0A codes (functional 7DF, all ECUs). */
    val obdCodes: List<DtcEntry>,
    /** Which OBD modes got any answer (positive or NRC) — «NO DATA» everywhere = no answer. */
    val obdAnswered: Boolean,
    /** Null until a full check ran. */
    val modules: List<ModuleResult>? = null
) {
    val milOn: Boolean? get() = if (mil.isEmpty()) null else mil.any { it.milOn }
    val milReportedCount: Int get() = mil.sumOf { it.dtcCount }
    val allCodes: List<DtcEntry> get() = obdCodes + (modules?.flatMap { it.codes } ?: emptyList())

    /** Short human summary for the screen. */
    fun summaryRu(): String = buildString {
        val codes = allCodes.map { it.code }.distinct()
        when (milOn) {
            true -> append("Check Engine: ГОРИТ (ЭБУ сообщает кодов: $milReportedCount). ")
            false -> append("Check Engine: не горит. ")
            null -> append("Статус Check Engine (0101) не получен. ")
        }
        if (codes.isEmpty()) {
            append("Кодов не найдено")
            if (modules == null) {
                append(if (milOn == true) " — но лампа горит: нажмите «Полная проверка»."
                else ". Если на панели горит другая лампа (ABS, ESP, подушки, руль…) — нажмите «Полная проверка».")
            } else {
                val silent = modules.count { !it.answered }
                append(" ни в одном из ${modules.size - silent} ответивших блоков")
                if (silent > 0) append(" ($silent не ответили)")
                append('.')
            }
        } else {
            append("Найдено кодов: ${codes.size}.")
            if (modules == null) append(" Для ABS/подушек/кузова — «Полная проверка».")
        }
    }
}

/** Every request/response of a DTC read, for the RAW log on the tab. */
data class DtcLogLine(val timestampMs: Long, val module: String?, val request: String, val rawVisible: String) {
    fun toText(): String {
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        return "[$t] ${module?.let { "$it: " } ?: ""}$request → $rawVisible"
    }
}
