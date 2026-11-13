package com.chuikoff.obd.diag

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * DTC reading. Quick = OBD Mode 01 PID 01 + 03 + 07 + 0A, functional 7DF with headers ON (so every
 * code is attributed to its ECU), receive filter cleared (ATAR), default flow control.
 * Full = quick + UDS 19 02 (fallback KWP 18 02 FF 00) physically to every [DtcDecode.HYUNDAI_MODULES]
 * address with ISO-TP flow control; silent modules cost one short NO DATA.
 * Every request/response goes to [log]. The bus is always restored to the polling setup (ATH0 +
 * engine addressing) afterwards. Never clears anything.
 */
class DtcScanner(
    private val client: Elm327Client,
    private val log: (DtcLogLine) -> Unit
) {
    private suspend fun tx(cmd: String, module: String? = null, timeoutMs: Long = 3000): String {
        val raw = try {
            client.send(cmd, timeoutMs = timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log(DtcLogLine(System.currentTimeMillis(), module, cmd, "ОШИБКА: ${e.message}"))
            throw e
        }
        log(DtcLogLine(System.currentTimeMillis(), module, cmd, Elm327Parser.visibleRaw(raw)))
        return raw
    }

    private suspend fun at(cmd: String, module: String? = null) {
        runCatching { tx(cmd, module, timeoutMs = 1500) }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun quick(): DtcReport = try {
        at("ATH1"); at("ATAL"); at("ATFCSM0"); at("ATSH7DF"); at("ATAR")
        val mil = DtcDecode.parseMil(tx("0101", timeoutMs = 4000))
        val r03 = tx("03", timeoutMs = 5000)
        val r07 = tx("07", timeoutMs = 5000)
        val r0a = tx("0A", timeoutMs = 5000)
        val codes = DtcDecode.parseObd(r03, DtcKind.STORED) + DtcDecode.parseObd(r07, DtcKind.PENDING) +
            DtcDecode.parseObd(r0a, DtcKind.PERMANENT)
        val answered = mil.isNotEmpty() || listOf(r03, r07, r0a).any { Elm327Parser.assemble(it).isNotEmpty() }
        DtcReport(System.currentTimeMillis(), mil, codes, answered)
    } finally {
        restore()
    }

    suspend fun full(): DtcReport {
        val q = quick()
        val results = mutableListOf<ModuleResult>()
        try {
            for (m in DtcDecode.HYUNDAI_MODULES) results += scanModule(m)
        } finally {
            restore()
        }
        return q.copy(timestampMs = System.currentTimeMillis(), modules = results)
    }

    private suspend fun scanModule(m: DiagModule): ModuleResult {
        val tag = "${m.name} ${m.tx}"
        Elm327Client.ecuAddressing(m.tx, m.rx).forEach { at(it, tag) }
        var answered = false
        var note: String? = null

        suspend fun uds(req: String): DtcDecode.Reply {
            val first = DtcDecode.parseUds1902(safeTx(req, tag), m)
            if (first != DtcDecode.Reply.Pending) return first
            delay(300)
            return DtcDecode.parseUds1902(safeTx(req, tag), m)
        }

        var r = uds("1902FF")
        if (r is DtcDecode.Reply.Negative && r.nrc in setOf(0x12, 0x13, 0x31)) { answered = true; r = uds("190209") }
        if (r is DtcDecode.Reply.Negative && r.nrc in setOf(0x22, 0x7E, 0x7F)) {
            answered = true
            safeTx("1003", tag)
            r = uds("1902FF")
        }
        when (r) {
            is DtcDecode.Reply.Codes -> return ModuleResult(m, true, "UDS 19 02", r.codes, r.ignored)
            is DtcDecode.Reply.Negative -> { answered = true; note = "UDS: ${DtcDecode.nrcRu(r.nrc)}" }
            DtcDecode.Reply.Junk, DtcDecode.Reply.Pending -> { answered = true; note = "UDS: непонятный ответ" }
            DtcDecode.Reply.None -> Unit
        }
        // Older Hyundai/Kia modules speak KWP2000 over CAN; also try when UDS was silent
        var k = DtcDecode.parseKwp18(safeTx("1802FF00", tag), m)
        if (k == DtcDecode.Reply.Pending) { delay(300); k = DtcDecode.parseKwp18(safeTx("1802FF00", tag), m) }
        return when (k) {
            is DtcDecode.Reply.Codes -> ModuleResult(m, true, "KWP 18 02", k.codes, 0, note)
            is DtcDecode.Reply.Negative -> ModuleResult(m, true, null, emptyList(), 0,
                listOfNotNull(note, "KWP: ${DtcDecode.nrcRu(k.nrc)}").joinToString("; "))
            DtcDecode.Reply.Junk, DtcDecode.Reply.Pending -> ModuleResult(m, true, null, emptyList(), 0,
                listOfNotNull(note, "KWP: непонятный ответ").joinToString("; "))
            DtcDecode.Reply.None -> ModuleResult(m, answered, null, emptyList(), 0, note)
        }
    }

    /** A failed request in one module must not abort the whole scan. */
    private suspend fun safeTx(cmd: String, tag: String): String = try {
        tx(cmd, tag, timeoutMs = 3000)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ""
    }

    /** Back to what sensor polling expects: headers off, engine ECU addressing. */
    private suspend fun restore() = withContext(NonCancellable) {
        runCatching { client.send("ATH0", timeoutMs = 1500) }
        runCatching { client.restoreEngineEcuFilter() }
    }
}
