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
 * Full = quick + per module (known Hyundai/Kia ids + address sweep, [DtcDecode.HYUNDAI_MODULES]):
 * physical 03/07/0A for 7Ex, UDS 19 02 FF → 08 → 09 → (10 03) retry, then KWP 18 02 FF 00 → 18 00 FF 00
 * → 17 FF 00, with ISO-TP flow control; a silent module costs two short NO DATA.
 * NO DATA is tolerated ([Elm327Client.probing]) so the sweep never triggers an ATZ recovery.
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
        log(DtcLogLine(System.currentTimeMillis(), module, cmd,
            Elm327Parser.visibleRaw(raw).ifEmpty { "(пусто)" } + if (raw.contains('>') || raw == "OK") "" else "  [ТАЙМАУТ]"))
        return raw
    }

    private suspend fun at(cmd: String, module: String? = null) {
        runCatching { tx(cmd, module, timeoutMs = 1500) }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun quick(): DtcReport = client.probing {
        try {
            quickOnce(headers = true).takeIf { it.obdAnswered }
                ?: quickOnce(headers = false) // some clones misbehave with ATH1 — retry like 1.14.0
        } finally {
            restore()
        }
    }

    private suspend fun quickOnce(headers: Boolean): DtcReport {
        log(DtcLogLine(System.currentTimeMillis(), null, "== OBD 7DF, заголовки ${if (headers) "вкл (ATH1)" else "выкл (ATH0)"} ==", ""))
        at(if (headers) "ATH1" else "ATH0"); at("ATCAF1"); at("ATAL"); at("ATFCSM0"); at("ATSH7DF"); at("ATAR")
        val mil = DtcDecode.parseMil(tx("0101", timeoutMs = 4000))
        val r03 = tx("03", timeoutMs = 5000)
        val r07 = tx("07", timeoutMs = 5000)
        val r0a = tx("0A", timeoutMs = 5000)
        val codes = DtcDecode.parseObd(r03, DtcKind.STORED) + DtcDecode.parseObd(r07, DtcKind.PENDING) +
            DtcDecode.parseObd(r0a, DtcKind.PERMANENT)
        val answered = mil.isNotEmpty() || listOf(r03, r07, r0a).any { Elm327Parser.assemble(it).isNotEmpty() }
        return DtcReport(System.currentTimeMillis(), mil, codes, answered)
    }

    /** @param onProgress «7D1 (12/50)» for the status line. */
    suspend fun full(onProgress: (String) -> Unit = {}): DtcReport {
        val q = quick()
        val results = mutableListOf<ModuleResult>()
        client.probing {
            try {
                val mods = DtcDecode.HYUNDAI_MODULES
                mods.forEachIndexed { i, m ->
                    onProgress("${m.name} ${m.tx} (${i + 1}/${mods.size})")
                    val r = scanModule(m)
                    results += r
                    log(DtcLogLine(System.currentTimeMillis(), "${m.name} ${m.tx}", "== ИТОГ",
                        if (!r.answered) "не ответил" else "ответил: ${r.protocol ?: "без кодов"}, кодов ${r.codes.size}" +
                            (if (r.ignored > 0) ", неактивных ${r.ignored}" else "") + (r.note?.let { " ($it)" } ?: "")))
                }
            } finally {
                restore()
            }
        }
        // physical OBD modes of the engine/TCM may list codes the functional request missed
        val merged = (physicalObd + q.obdCodes).sortedBy { if (it.ecuId == null) 1 else 0 }
            .distinctBy { it.code to it.kind }
        onProgress("давление в шинах (TPMS)")
        val tpms = client.probing { try { Tpms.probe(client) { l -> log(l) } } finally { restore() } }
        return q.copy(timestampMs = System.currentTimeMillis(), modules = results, obdCodes = merged, tpms = tpms)
    }

    private val physicalObd = mutableListOf<DtcEntry>()

    private suspend fun scanModule(m: DiagModule): ModuleResult {
        val tag = "${m.name} ${m.tx}"
        Elm327Client.ecuAddressing(m.tx, m.rx).forEach { at(it, tag) }
        var answered = false
        val notes = mutableListOf<String>()

        suspend fun uds(req: String): DtcDecode.Reply {
            var x = DtcDecode.parseUds1902(safeTx(req, tag), m)
            // 78 = response pending, 21 = busy, repeat request (seen on the Creta MDPS 7D4)
            for (attempt in 1..3) {
                val retry = x == DtcDecode.Reply.Pending || (x as? DtcDecode.Reply.Negative)?.nrc == 0x21
                if (!retry) return x
                delay(300L * attempt)
                x = DtcDecode.parseUds1902(safeTx(req, tag), m)
            }
            return x
        }
        suspend fun kwp(req: String, sid: Int): DtcDecode.Reply {
            var x = DtcDecode.parseKwp(safeTx(req, tag), m, sid)
            for (attempt in 1..3) {
                val retry = x == DtcDecode.Reply.Pending || (x as? DtcDecode.Reply.Negative)?.nrc == 0x21
                if (!retry) return x
                delay(300L * attempt)
                x = DtcDecode.parseKwp(safeTx(req, tag), m, sid)
            }
            return x
        }
        fun rejected(r: DtcDecode.Reply) = r is DtcDecode.Reply.Negative || r == DtcDecode.Reply.Junk || r == DtcDecode.Reply.Pending

        // Engine/TCM also answer OBD modes physically
        if (m.tx.startsWith("7E")) {
            for ((req, kind) in listOf("03" to DtcKind.STORED, "07" to DtcKind.PENDING, "0A" to DtcKind.PERMANENT)) {
                val raw = safeTx(req, tag)
                if (Elm327Parser.assemble(raw).isNotEmpty()) answered = true
                physicalObd += DtcDecode.parseObd(raw, kind).map { it.copy(ecuId = m.rx, ecuName = "${m.name} (${m.rx})") }
            }
        }

        var r = uds("1902FF")
        if (rejected(r)) {
            answered = true
            (r as? DtcDecode.Reply.Negative)?.let { notes += "19 02 FF: ${DtcDecode.nrcRu(it.nrc)}" }
            for (mask in listOf("190208", "190209")) {
                r = uds(mask)
                if (r is DtcDecode.Reply.Codes) break
            }
        }
        if (rejected(r)) {
            safeTx("1003", tag) // extended session, then once more
            r = uds("1902FF")
            if (rejected(r)) r = uds("190209")
        }
        if (r is DtcDecode.Reply.Codes) return ModuleResult(m, true, "UDS 19 02", r.codes, r.ignored, notes.joinToString("; ").ifEmpty { null }, r.inactive)
        if (r is DtcDecode.Reply.Negative) notes += "UDS: ${DtcDecode.nrcRu(r.nrc)}"
        if (r != DtcDecode.Reply.None) answered = true

        // Older Hyundai/Kia modules speak KWP2000 over CAN; also try when UDS was silent
        var k = kwp("1802FF00", 0x18)
        if (k == DtcDecode.Reply.None && !answered) return ModuleResult(m, false, null, emptyList(), 0, null)
        if (rejected(k)) k = kwp("1800FF00", 0x18)
        if (rejected(k)) k = kwp("17FF00", 0x17)
        return when (k) {
            is DtcDecode.Reply.Codes -> ModuleResult(m, true, "KWP", k.codes, 0, notes.joinToString("; ").ifEmpty { null })
            is DtcDecode.Reply.Negative -> ModuleResult(m, true, null, emptyList(), 0,
                (notes + "KWP: ${DtcDecode.nrcRu(k.nrc)}").joinToString("; "))
            DtcDecode.Reply.Junk, DtcDecode.Reply.Pending -> ModuleResult(m, true, null, emptyList(), 0,
                (notes + "KWP: непонятный ответ").joinToString("; "))
            DtcDecode.Reply.None -> ModuleResult(m, answered, null, emptyList(), 0, notes.joinToString("; ").ifEmpty { null })
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
