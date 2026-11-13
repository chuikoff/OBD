package com.chuikoff.obd.elm327

import com.chuikoff.obd.connection.ObdTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thin ELM327 session: init AT sequence, send commands, return raw ASCII.
 * Remembers stateful AT settings (ATSH, ATFCSH, ATFCSD, ATFCSM, ATCRA, ATST, ATAT) and skips a
 * repeat with the same value — CVT-only ticks then cost one 2103 request instead of 6 AT round trips.
 */
class Elm327Client(
    private val transport: ObdTransport
) {
    private val mutex = Mutex()
    @Volatile private var initialized = false
    /**
     * Last accepted value per cached AT setting ("ATSH" → "7E1"). Cleared on ATZ/ATD/ATWS, recovery,
     * resync after a missing prompt and whenever the adapter reports a reboot (ELM327 banner / LV RESET).
     */
    private val atState = HashMap<String, String>()

    /** True between write() and a received prompt: a cancelled/timed-out exchange leaves the ELM mid-reply. */
    @Volatile private var dirty = false
    private var badStreak = 0
    /** While true, NO DATA (unsupported PID probing) does not count toward the recovery streak. */
    @Volatile var tolerateNoData = false
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

        /** Cached stateful AT settings; longest prefixes first. */
        /** Physical addressing of one ECU with explicit ISO-TP flow control (TX header, RX filter). */
        fun ecuAddressing(tx: String, rx: String): List<String> =
            listOf("ATSH$tx", "ATFCSH$tx", "ATFCSD30080A", "ATFCSM1", "ATCRA$rx")

        /** Back to the engine ECU after TCM / AWC addressing (repeats are free thanks to the AT cache). */
        val ENGINE_ADDRESSING: List<String> = ecuAddressing("7E0", "7E8")

        private val CACHED_AT = listOf("ATFCSH", "ATFCSD", "ATFCSM", "ATCRA", "ATSH", "ATST", "ATAT")

        /** "ATSH7E1" → ("ATSH", "7E1"); null for non-cached commands or a bare prefix (e.g. "ATCRA" = reset). */
        fun cacheKey(upperNoSpaces: String): Pair<String, String>? {
            val p = CACHED_AT.firstOrNull { upperNoSpaces.startsWith(it) } ?: return null
            val v = upperNoSpaces.removePrefix(p)
            return if (v.isEmpty()) null else p to v
        }
    }

    suspend fun ensureInitialized() = mutex.withLock {
        if (initialized) return
        flushLocked(timeoutMs = 500)
        runInitLocked()
        initialized = true
    }

    private suspend fun runInitLocked() {
        atState.clear()
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

    /**
     * Drain a late reply; only if the ELM stays silent (busy, no prompt) interrupt it with a SPACE.
     * Never a bare CR: on an idle ELM327 a lone CR repeats the last command (2103 again, or even 04).
     * Spaces are ignored by the ELM command parser, so an idle adapter is unaffected.
     */
    private suspend fun flushLocked(timeoutMs: Long) {
        if (readQuiet(timeoutMs).contains('>')) return
        try {
            transport.write(" ".toByteArray(Charsets.US_ASCII))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        readQuiet(timeoutMs)
    }

    private suspend fun readQuiet(timeoutMs: Long): String =
        try {
            transport.readUntilPrompt(timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }

    suspend fun send(command: String, timeoutMs: Long = 3000): String {
        // A cancelled poll must not touch the adapter (Mutex fast path does not check cancellation)
        currentCoroutineContext().ensureActive()
        return sendSerialized(command, timeoutMs)
    }

    private suspend fun sendSerialized(command: String, timeoutMs: Long): String = mutex.withLock {
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
        if (isLinkError(response) || (!tolerateNoData && isNoData(response))) badStreak++ else if (tolerateNoData && isNoData(response)) { /* probing */ } else {
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
        atState.clear()
        flushLocked(timeoutMs = 800)
        runCatching { runInitLocked() }
        initialized = true
        badStreak = 0
    }

    private suspend fun sendLocked(command: String, timeoutMs: Long): String {
        if (dirty) {
            // previous exchange never saw its prompt (timeout / cancel): resync instead of
            // typing into a busy ELM, which answers STOPPED; settings may be in doubt → forget cache
            dirty = false
            atState.clear()
            flushLocked(timeoutMs = 1000)
        }
        val trimmed = command.trim()
        val upper = trimmed.uppercase().replace(" ", "")
        val key = cacheKey(upper)
        // Same stateful AT setting already active → skip the round trip
        if (key != null && atState[key.first] == key.second) return "OK"

        val payload = (trimmed + "\r").toByteArray(Charsets.US_ASCII)
        transport.write(payload)
        dirty = true // only once the bytes really left; a cancelled/failed write leaves the ELM idle
        val response = transport.readUntilPrompt(timeoutMs)
        if (response.contains('>')) dirty = false
        updateAtStateLocked(upper, key, response)
        return response
    }

    private fun updateAtStateLocked(upper: String, key: Pair<String, String>?, response: String) {
        val c = Elm327Parser.clean(response)
        when {
            // adapter rebooted (brown-out) or reset → every setting is back to default
            c.contains("ELM327") || c.contains("LVRESET") ||
                upper == "ATZ" || upper == "ATD" || upper == "ATWS" -> atState.clear()
            upper == "ATAR" || upper == "ATCRA" -> atState.remove("ATCRA")
            key != null -> {
                if (c.contains("ERROR") || c.contains("?") || !response.contains('>')) atState.remove(key.first)
                else atState[key.first] = key.second
            }
        }
    }

    /**
     * Prepare bus for standard OBD Mode 03/07/04 after CVT/climate addressing.
     * ATFCSM0: default flow control, so multi-frame replies from 7E8 *and* 7E9 get their FC
     * (FCSM1 + FCSH7E0/7E1 left by the sensors would cut 3+ DTC replies after the first frame).
     */
    suspend fun prepareStandardObdBus() {
        send("ATH0", timeoutMs = 2000)
        runCatching { send("ATAL", timeoutMs = 1500) }
        runCatching { send("ATFCSM0", timeoutMs = 1500) }
        send("ATSH7DF", timeoutMs = 2000)
        val ar = runCatching { send("ATAR", timeoutMs = 2000) }.getOrElse { "" }
        val arClean = Elm327Parser.clean(ar)
        if (arClean.contains("ERROR") || arClean.contains("?")) {
            runCatching { send("ATCRA000000", timeoutMs = 2000) }
        }
    }

    /** Restore engine-ECU addressing typically used for sensor polling. */
    suspend fun restoreEngineEcuFilter() {
        runCatching { send("ATSH7E0", timeoutMs = 2000) }
        runCatching { send("ATFCSH7E0", timeoutMs = 1500) }
        runCatching { send("ATCRA7E8", timeoutMs = 2000) }
    }

    /** Mode 04 — clear DTCs / MIL. */
    suspend fun clearDtcs(): String {
        prepareStandardObdBus()
        return send("04", timeoutMs = 5000)
    }
}
