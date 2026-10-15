package com.chuikoff.obd.sensors

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which count→°C conversion to use for Outlander 2103 byte N (both from OUT-CLUB OUT3.csv). */
enum class CvtFormula(val titleRu: String) {
    /** "(Out3) CVT Temperature" — 5th-order polynomial of N. */
    OUT3_POLY("OUT3 полином (N)"),
    /** "(Out3 CVT8) CVT Temperature" — N − 55. */
    CVT8_MINUS_55("CVT8: N − 55")
}

/** Runtime CVT settings (persisted by the ViewModel in SharedPreferences). */
object CvtSettings {
    @Volatile var formula: CvtFormula = CvtFormula.OUT3_POLY
    /** Also request 2102 (sensor voltage byte E) for diagnostics — costs extra bus time. */
    @Volatile var debugExtraRequests: Boolean = false
}

/** One CVT request/response as seen by the app. */
data class CvtDebugEntry(
    val timestampMs: Long,
    val request: String,
    /** Exact ELM text before any normalization, `\r` shown as ⏎. */
    val rawVisible: String,
    /** Reassembly status: OK / ДЫРА / обрезан, byte count, declared length, frame indices. */
    val frameSummary: String,
    /** Reassembled payload after `61 PID` (hex, space separated). */
    val payloadHex: String?,
    /** Decoded candidates for comparison (count N, both formulas, old 1.6.0 tail byte…). */
    val candidates: String?,
    /** Value the app actually produced from this frame (before median/hold). */
    val valueC: Double?,
    val error: String?
) {
    fun toText(): String {
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
        return buildString {
            append("[$t] $request → ")
            append(valueC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—")
            error?.let { append("  ERR: $it") }
            append("\n  RAW: ").append(rawVisible)
            append("\n  frame: ").append(frameSummary)
            payloadHex?.let { append("\n  payload: ").append(it) }
            candidates?.let { append("\n  cand: ").append(it) }
        }
    }
}

/** Ring buffer of the last [CAPACITY] CVT responses, exposed as a StateFlow for the UI. */
object CvtDebugLog {
    const val CAPACITY = 50
    private val lock = Any()
    private val ring = ArrayDeque<CvtDebugEntry>(CAPACITY)
    private val _entries = MutableStateFlow<List<CvtDebugEntry>>(emptyList())
    val entries: StateFlow<List<CvtDebugEntry>> = _entries.asStateFlow()

    fun add(e: CvtDebugEntry) {
        synchronized(lock) {
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(e)
            _entries.value = ring.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            _entries.value = emptyList()
        }
    }

    fun exportText(header: String): String {
        val list = entries.value
        return buildString {
            append(header).append('\n')
            append("Записей: ${list.size} (последние $CAPACITY)\n\n")
            list.forEach { append(it.toText()).append("\n\n") }
        }
    }
}


/**
 * Cross-cycle pacing for CVT reads: short timeouts after a healthy streak, and optionally
 * leave the adapter on TCM addressing so the next CVT-only tick skips post/restore.
 */
object CvtPollPace {
    @Volatile var consecutiveOk: Int = 0
        private set
    /** When true, [CvtTempSensor] skips postCommands + engine restore (next tick is CVT again). */
    @Volatile var skipPostRestore: Boolean = false

    fun markOk() { consecutiveOk = (consecutiveOk + 1).coerceAtMost(100) }
    fun markBad() { consecutiveOk = 0 }
    fun reset() { consecutiveOk = 0; skipPostRestore = false }
}
