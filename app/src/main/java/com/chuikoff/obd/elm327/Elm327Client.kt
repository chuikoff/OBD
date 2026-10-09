package com.chuikoff.obd.elm327

import com.chuikoff.obd.connection.ObdTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thin ELM327 session: init AT sequence, send commands, return raw ASCII.
 * Tracks current ATSH header to skip redundant TCM preCommands while driving.
 */
class Elm327Client(
    private val transport: ObdTransport
) {
    private val mutex = Mutex()
    @Volatile private var initialized = false
    /** Last successful ATSH header (e.g. "7E1"), uppercase without prefix. */
    @Volatile private var currentHeader: String? = null

    /** True between write() and a received prompt: a cancelled/timed-out exchange leaves the ELM mid-reply. */
    @Volatile private var dirty = false
    private var badStreak = 0
    private var recoveryFailures = 0
    private var lastRecoveryMs = 0L
    private val _linkNote = MutableStateFlow<String?>(null)
    /** Non-null while the link is being re-initialised ("переподключение…"). */
    val linkNote: StateFlow<String?> = _linkNote

    companion object {
        const val BAD_STREAK_LIMIT = 5
        private const val BACKOFF_BASE_MS = 3_000L
        private const val BACKOFF_MAX_MS = 60_000L

        /** ELM replies that mean the adapter/bus is in a bad state (not just "ECU silent"). */
        fun isLinkError(response: String): Boolean {
            val c = Elm327Parser.clean(response)
            if (c.isEmpty()) return true
            return listOf("STOPPED", "BUFFERFULL", "CANERROR", "BUSERROR", "BUSBUSY",
                "UNABLETOCONNECT", "DATAERROR", "FBERROR", "LVRESET", "ACTALERT")
                .any { c.contains(it) } || c == "?"
        }

        fun isNoData(response: String): Boolean = Elm327Parser.clean(response).contains("NODATA")

        fun backoffMs(failures: Int): Long =
            minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS shl failures.coerceIn(0, 5))
    }

    suspend fun ensureInitialized() = mutex.withLock {
        if (initialized) return
        flushLocked(timeoutMs = 500)
        runInitLocked()
        initialized = true
    }

    private suspend fun runInitLocked() {
        currentHeader = null
        val initCmds = listOf(
            "ATZ",   // reset
            "ATE0",  // echo off
            "ATL0",  // linefeeds off
            "ATS0",  // spaces off
            "ATH0",  // headers off
            "ATAT1", // adaptive timing (helps while moving / bus load)
            "ATSP0"  // auto protocol
        )
        for (cmd in initCmds) {
            // ATAT1 may be unsupported on some clones — ignore ERROR/?
            if (cmd == "ATAT1") {
                runCatching { sendLocked(cmd, timeoutMs = 1500) }
            } else {
                sendLocked(cmd, timeoutMs = if (cmd == "ATZ") 5000 else 2000)
            }
        }
    }

    /**
     * Flush residual bus data: send empty CR and drain until prompt.
     */
    suspend fun flushReadBuffer(timeoutMs: Long = 800) = mutex.withLock {
        flushLocked(timeoutMs)
    }

    private suspend fun flushLocked(timeoutMs: Long) {
        runCatching { transport.write("\r".toByteArray(Charsets.US_ASCII)) }
        runCatching { transport.readUntilPrompt(timeoutMs) }
    }

    suspend fun send(command: String, timeoutMs: Long = 3000): String = mutex.withLock {
        if (!initialized) {
            flushLocked(timeoutMs = 500)
            runInitLocked()
            initialized = true
        }
        maybeRecoverLocked()
        val r = sendLocked(command, timeoutMs)
        trackLocked(command, r)
        r
    }

    /** Count only data requests (AT commands answer OK even when the bus is dead). */
    private fun trackLocked(command: String, response: String) {
        if (command.trim().uppercase().startsWith("AT")) return
        if (isLinkError(response) || isNoData(response)) badStreak++ else {
            badStreak = 0
            recoveryFailures = 0
            _linkNote.value = null
        }
    }

    /** After [BAD_STREAK_LIMIT] bad replies: flush, ATZ + full init exactly as at connect, with backoff. */
    private suspend fun maybeRecoverLocked() {
        if (badStreak < BAD_STREAK_LIMIT) return
        val now = System.currentTimeMillis()
        if (now - lastRecoveryMs < backoffMs(recoveryFailures)) return
        lastRecoveryMs = now
        recoveryFailures++
        _linkNote.value = "переподключение… (попытка $recoveryFailures)"
        dirty = false
        flushLocked(timeoutMs = 800)
        runCatching { runInitLocked() }
        initialized = true
        badStreak = 0
    }

    private suspend fun sendLocked(command: String, timeoutMs: Long): String {
        if (dirty) {
            // previous exchange never saw its prompt (timeout / cancel): resync instead of
            // typing into a busy ELM, which answers STOPPED
            dirty = false
            flushLocked(timeoutMs = 1000)
        }
        val trimmed = command.trim()
        val upper = trimmed.uppercase()

        // Skip redundant ATSH if we are already on that header (cuts bus load while driving)
        if (upper.startsWith("ATSH") && upper.length > 4) {
            val header = upper.removePrefix("ATSH")
            if (header.isNotEmpty() && header == currentHeader) {
                return "OK"
            }
        }

        val payload = (trimmed + "\r").toByteArray(Charsets.US_ASCII)
        dirty = true
        transport.write(payload)
        val response = transport.readUntilPrompt(timeoutMs)
        if (response.contains('>')) dirty = false

        if (upper.startsWith("ATSH") && upper.length > 4) {
            val header = upper.removePrefix("ATSH")
            val cleaned = Elm327Parser.clean(response)
            if (!cleaned.contains("ERROR") && cleaned != "?") {
                currentHeader = header
            }
        } else if (upper == "ATZ" || upper == "ATD") {
            currentHeader = null
        } else if (upper.startsWith("ATCRA") || upper.startsWith("ATFCSH")) {
            // addressing changed alongside headers — leave currentHeader as last ATSH
        }

        return response
    }

    suspend fun resetSession() = mutex.withLock {
        initialized = false
        currentHeader = null
    }

    /**
     * Prepare bus for standard OBD Mode 03/07/04 after CVT/climate addressing.
     */
    suspend fun prepareStandardObdBus() {
        send("ATH0", timeoutMs = 2000)
        runCatching { send("ATAL", timeoutMs = 1500) }
        send("ATSH7DF", timeoutMs = 2000)
        val ar = runCatching { send("ATAR", timeoutMs = 2000) }.getOrElse { "" }
        val arClean = Elm327Parser.clean(ar)
        if (arClean.contains("ERROR") || arClean.contains("?")) {
            runCatching { send("ATCRA000000", timeoutMs = 2000) }
        }
        currentHeader = "7DF"
    }

    /** Restore engine-ECU addressing typically used for sensor polling. */
    suspend fun restoreEngineEcuFilter() {
        runCatching { send("ATSH7E0", timeoutMs = 2000) }
        runCatching { send("ATFCSH7E0", timeoutMs = 1500) }
        runCatching { send("ATCRA7E8", timeoutMs = 2000) }
    }

    /** Mode 03 — stored DTCs. */
    suspend fun readStoredDtcs(): Pair<List<String>, String> {
        prepareStandardObdBus()
        val raw = send("03", timeoutMs = 5000)
        val codes = Elm327Parser.parseDtcList(raw)
        return codes to Elm327Parser.clean(raw)
    }

    /** Mode 07 — pending DTCs. */
    suspend fun readPendingDtcs(): Pair<List<String>, String> {
        prepareStandardObdBus()
        val raw = send("07", timeoutMs = 5000)
        val codes = Elm327Parser.parseDtcList(raw)
        return codes to Elm327Parser.clean(raw)
    }

    /** Mode 04 — clear DTCs / MIL. */
    suspend fun clearDtcs(): String {
        prepareStandardObdBus()
        return send("04", timeoutMs = 5000)
    }
}
