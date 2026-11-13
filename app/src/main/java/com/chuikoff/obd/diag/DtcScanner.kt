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
                val found = runCatching { discover(onProgress) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyMap())
                val known = DtcDecode.HYUNDAI_MODULES.map { k -> found[k.tx]?.let { rx -> k.copy(rx = rx) } ?: k }
                val extra = found.filterKeys { t -> known.none { it.tx == t } }
                    .map { (t, rx) -> DiagModule("Блок $t (найден поиском)", t, rx) }
                val mods = known + extra
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
    private var lastAvail: Int? = null

    private fun merge(a: DtcDecode.Reply.Codes, b: DtcDecode.Reply.Codes, how: String, notes: MutableList<String>): DtcDecode.Reply.Codes {
        val newCodes = b.codes.filter { c -> a.codes.none { it.code == c.code } }
        if (newCodes.isNotEmpty()) notes += "коды нашлись только через $how"
        val codes = a.codes + newCodes
        val inactive = (a.inactive + b.inactive).distinctBy { it.code }.filter { i -> codes.none { it.code == i.code } }
        return DtcDecode.Reply.Codes(codes, inactive.size, inactive)
    }

    companion object {
        /** Body/TPMS modules: also ask KWP when UDS listed nothing. */
        val KWP_TOO = setOf("7A0", "7A5", "7D6", "770", "771", "7C6")
    }

    /**
     * Bus discovery: tester present (3E 00) to every 11-bit 7x0…7x7 id, receive filter 7XX, headers on.
     * Returns request id → response id for everyone who answered anything.
     */
    suspend fun discover(onProgress: (String) -> Unit = {}): Map<String, String> {
        val found = linkedMapOf<String, String>()
        at("ATH1", "поиск"); at("ATCAF1", "поиск"); at("ATFCSM0", "поиск"); at("ATST19", "поиск")
        val wildcard = runCatching { tx("ATCRA7XX", "поиск", 1500) }.getOrDefault("").contains("OK")
        try {
            for (base in 0x700..0x7F0 step 0x10) {
                onProgress("поиск блоков %03X…".format(base))
                for (i in 0..7) {
                    val t = "%03X".format(base + i)
                    if (t == "7DF") continue
                    at("ATSH$t", "поиск")
                    if (!wildcard) at("ATCRA%03X".format(base + i + 8), "поиск")
                    val raw = safeTxQuiet("3E00")
                    val ids = DiscoverParse.responders(raw, t)
                    if (ids.isNotEmpty()) {
                        found[t] = ids.first()
                        log(DtcLogLine(System.currentTimeMillis(), "поиск", "$t 3E00", Elm327Parser.visibleRaw(raw)))
                    }
                }
            }
        } finally {
            at("ATST32", "поиск"); at("ATAR", "поиск")
        }
        log(DtcLogLine(System.currentTimeMillis(), "поиск", "== ОТВЕТИЛИ",
            if (found.isEmpty()) "никто" else found.entries.joinToString(", ") { "${it.key}→${it.value}" }))
        return found
    }

    private suspend fun safeTxQuiet(cmd: String): String = try {
        client.send(cmd, timeoutMs = 1200)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ""
    }

    private suspend fun scanModule(m: DiagModule): ModuleResult {
        val tag = "${m.name} ${m.tx}"
        Elm327Client.ecuAddressing(m.tx, m.rx).forEach { at(it, tag) }
        lastAvail = null
        var answered = false
        val notes = mutableListOf<String>()

        suspend fun uds(req: String): DtcDecode.Reply {
            val first = safeTx(req, tag)
            DtcDecode.udsAvailabilityMask(first)?.let { lastAvail = it }
            var x = DtcDecode.parseUds1902(first, m)
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
        if (r is DtcDecode.Reply.Codes) {
            // 1.14.5: the Creta BCM/TPMS (7A0) answered «59 02 09» with no records to 19 02 FF while
            // Car Scanner showed C1313/C1662/C1663. Some ECUs match the mask exactly or only in the
            // extended session → when empty, ask again with the ECU's own mask, 0A (all), 10 03.
            var acc: DtcDecode.Reply.Codes = r
            if (acc.codes.isEmpty()) {
                val avail = lastAvail
                val extra = linkedSetOf<String>()
                if (avail != null && avail != 0xFF && avail != 0) extra += "1902%02X".format(avail)
                extra += listOf("190209", "190208", "190201", "190A")
                for (req in extra) {
                    val x = uds(req)
                    if (x is DtcDecode.Reply.Codes) acc = merge(acc, x, req, notes)
                    if (acc.codes.isNotEmpty()) break // Creta 7A0: 19 02 09 (its own mask) works
                }
                if (acc.codes.isEmpty()) {
                    safeTx("1003", tag)
                    for (req in listOf("1902FF", "190209")) {
                        val x = uds(req)
                        if (x is DtcDecode.Reply.Codes) acc = merge(acc, x, "10 03 + $req", notes)
                    }
                    safeTx("1001", tag)
                }
                if (acc.codes.isEmpty() && m.tx in KWP_TOO) {
                    for ((req, sid) in listOf("1802FF00" to 0x18, "1800FF00" to 0x18, "17FF00" to 0x17)) {
                        val k = kwp(req, sid)
                        if (k is DtcDecode.Reply.Codes && k.codes.isNotEmpty()) { acc = merge(acc, k, "KWP $req", notes); break }
                    }
                }
            }
            return ModuleResult(m, true, "UDS 19 02", acc.codes, acc.ignored, notes.joinToString("; ").ifEmpty { null }, acc.inactive)
        }
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

    /**
     * Clear codes in every module that listed any: UDS 14 FF FF FF (→ 54; NRC 22/7F → 10 03 and again),
     * KWP 14 FF 00 / 14 FF FF; then the module is read again so the UI can say «стёрто» or «вернулся».
     * Engine/TCM also get OBD Mode 04 by the caller.
     */
    suspend fun clear(targets: List<ModuleResult>, onProgress: (String) -> Unit = {}): List<ClearOutcome> = client.probing {
        val out = mutableListOf<ClearOutcome>()
        try {
            for (t in targets) {
                val m = t.module
                val tag = "${m.name} ${m.tx}"
                onProgress("сброс ${m.name}")
                Elm327Client.ecuAddressing(m.tx, m.rx).forEach { at(it, tag) }
                val kwpFirst = t.protocol == "KWP"
                val tries = if (kwpFirst) listOf("14FF00", "14FFFF", "14FFFFFF") else listOf("14FFFFFF", "14FF00")
                var ok = false
                var why: String? = null
                suspend fun attempt(req: String): Boolean {
                    val raw = try { tx(req, tag, timeoutMs = 6000) } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
                    val msgs = Elm327Parser.assemble(raw).filter { it.bytes.isNotEmpty() }
                    fun b(m: Elm327Parser.ObdMessage, i: Int) = if (i < m.bytes.size) Elm327Parser.toUnsigned(m.bytes[i]) else -1
                    if (msgs.any { b(it, 0) == 0x54 }) return true
                    val nrc = msgs.firstOrNull { b(it, 0) == 0x7F && b(it, 1) == 0x14 && b(it, 2) != 0x78 }?.let { b(it, 2) }
                    if (nrc == null && msgs.any { b(it, 0) == 0x7F && b(it, 1) == 0x14 }) { delay(1500); return true } // only «78 pending»
                    why = when {
                        nrc != null -> DtcDecode.nrcRu(nrc)
                        msgs.isEmpty() -> "нет ответа"
                        else -> "ответ ${Elm327Parser.clean(raw)}"
                    }
                    return false
                }
                for (req in tries) { if (attempt(req)) { ok = true; break } }
                if (!ok) {
                    safeTx("1003", tag)
                    ok = attempt(tries.first())
                    safeTx("1001", tag)
                }
                delay(300)
                val after = scanModule(m)
                log(DtcLogLine(System.currentTimeMillis(), tag, "== СБРОС",
                    (if (ok) "принят" else "не принят: $why") + ", после: кодов ${after.codes.size}"))
                out += ClearOutcome(m, ok, if (ok) null else why, t.codes, after)
            }
        } finally {
            restore()
        }
        out
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
