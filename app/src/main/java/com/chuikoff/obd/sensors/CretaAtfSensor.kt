package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import kotlinx.coroutines.CancellationException

/**
 * Hyundai/Kia 6AT (A6GF1 / A6MF1) ATF temperature — EXPERIMENTAL until confirmed in the car.
 *
 * Sources (ScanGauge X-Gauge "HYUNDAI – KIA – CANSF", all °C = X − 40; their MTH 9/5, −40 is °F):
 *  - i30 2016 (A6GF1, "confirmed working", scangauge.com.au): TXD 07E121A0, RXF 0322, RXD 2808
 *    → 2nd consecutive frame, byte 3 → 61 A0 payload index 13.
 *  - US list (older/longer TCM frame): TXD 07E121A0, RXF 0325, RXD 2008 → CF#5 byte 2 → index 33.
 *  - UDS alt (fw 4.22+): TXD 07E1222081, RXF C322, RXD 3008 → CF#2 byte 4 → 62 20 81 payload idx 13.
 *  - Alt header: TXD 07E021A0 (same layout as the US one).
 * (ScanGauge numbering: CAN ID = bytes 1–2, PCI = 3; RXD bit 0 = first ID bit.)
 *
 * Once per connection all candidates are probed and logged (RAW → «Диагностика» CVT log), the first
 * plausible one is then polled alone. If the TCM answers nothing at all the card is hidden
 * (manual gearbox / no TCM); a selected source that fails [REPROBE_AFTER] times is re-probed.
 */
object CretaAtf {
    const val SENSOR_ID = "atf_temp"
    const val REPROBE_AFTER = 10

    enum class Layout { MODE21_A0, MODE22_2081 }

    data class Candidate(
        val key: String,
        val tx: String,
        val rx: String,
        val request: String,
        val layout: Layout,
        val index: Int,
        val sourceRu: String
    ) {
        val label: String get() = "$request@$tx [${index}]"
    }

    val I30_21A0 = Candidate("21A0_7E1_13", "7E1", "7E9", "21A0", Layout.MODE21_A0, 13,
        "ScanGauge i30 2016 (A6GF1): 21A0@7E1, CF#2 → байт 13, X−40")
    val US_21A0 = Candidate("21A0_7E1_33", "7E1", "7E9", "21A0", Layout.MODE21_A0, 33,
        "ScanGauge Hyundai/Kia CAN: 21A0@7E1, CF#5 → байт 33, X−40")
    val UDS_2081 = Candidate("222081_7E1_13", "7E1", "7E9", "222081", Layout.MODE22_2081, 13,
        "ScanGauge alt: 22 2081@7E1, CF#2 → байт 13, X−40")
    val ENG_21A0 = Candidate("21A0_7E0_33", "7E0", "7E8", "21A0", Layout.MODE21_A0, 33,
        "ScanGauge alt: 21A0@7E0, CF#5 → байт 33, X−40")

    /** Probe order (requests de-duplicated: 21A0@7E1 answers both index candidates). */
    val CANDIDATES = listOf(I30_21A0, US_21A0, UDS_2081, ENG_21A0)

    /** Raw-only probes (once per connection) so the right byte can be found from the log. */
    val RAW_ONLY = listOf("7E1" to "2101", "7E1" to "2102")

    // ---- per-connection state
    @Volatile var selected: Candidate? = null
        private set
    @Volatile var probed: Boolean = false
        private set
    /** True after a probe in which the TCM did not answer any request → hide the card. */
    @Volatile var absent: Boolean = false
        private set
    private var failures = 0

    fun reset() { selected = null; probed = false; absent = false; failures = 0 }

    /** Payload after the positive-response header, or null (also when byte positions are unsafe). */
    fun payload(raw: String, layout: Layout): ByteArray? {
        val msgs = Elm327Parser.assemble(raw)
        val (hdr, len) = when (layout) {
            Layout.MODE21_A0 -> intArrayOf(0x61, 0xA0) to 2
            Layout.MODE22_2081 -> intArrayOf(0x62, 0x20, 0x81) to 3
        }
        val m = msgs.firstOrNull { msg ->
            msg.bytes.size >= len && (0 until len).all { Elm327Parser.toUnsigned(msg.bytes[it]) == hdr[it] }
        } ?: return null
        if (!m.positionsTrustworthy) return null
        return m.bytes.copyOfRange(len, m.bytes.size)
    }

    /** °C = X − 40; null for filler (00/FE/FF) or outside −30…150 °C. */
    fun decodeByte(x: Int): Double? {
        if (x == 0x00 || x == 0xFE || x == 0xFF) return null
        val c = (x - 40).toDouble()
        return c.takeIf { it in -30.0..150.0 }
    }

    fun decode(payload: ByteArray?, c: Candidate): Double? {
        val p = payload ?: return null
        if (p.size <= c.index) return null
        return decodeByte(Elm327Parser.toUnsigned(p[c.index]))
    }

    /**
     * Choose a candidate from probe results (request label → payload). 21A0: a long frame (≥34
     * bytes, the US layout) prefers index 33, a short one index 13; the other is the fallback.
     */
    fun choose(payloads: Map<Candidate, ByteArray?>): Candidate? {
        val i30 = payloads[I30_21A0]
        val order = if ((i30?.size ?: 0) > US_21A0.index) listOf(US_21A0, I30_21A0, UDS_2081, ENG_21A0)
        else CANDIDATES
        return order.firstOrNull { decode(payloads[it], it) != null }
    }

    fun describe(p: ByteArray?): String {
        if (p == null) return "нет кадра"
        fun at(i: Int) = if (p.size > i) Elm327Parser.toUnsigned(p[i]).let { "%02X→%d°".format(it, it - 40) } else "—"
        return "len=${p.size} · [13] ${at(13)} · [33] ${at(33)} · A ${at(0)}"
    }

    internal fun noteFor(c: Candidate) = "ЭКСПЕРИМЕНТАЛЬНО: ${c.sourceRu}"

    internal fun markProbed(sel: Candidate?, tcmAnswered: Boolean) {
        selected = sel; probed = true; absent = sel == null && !tcmAnswered; failures = 0
    }

    internal fun markResult(ok: Boolean) {
        if (ok) { failures = 0; return }
        failures++
        if (failures >= REPROBE_AFTER) { probed = false; selected = null; failures = 0 }
    }
}

class CretaAtfSensor : SensorDefinition {
    override val id: String = CretaAtf.SENSOR_ID
    override val displayNameRu: String = "Температура коробки"
    override val unit: String = "°C"
    override val caveatRu: String = "Экспериментально: A6GF1, ScanGauge 21A0@7E1 / 22 2081, X−40"

    override suspend fun read(client: Elm327Client): SensorReading = client.probing { readTolerant(client) }

    private suspend fun readTolerant(client: Elm327Client): SensorReading {
        try {
            return if (!CretaAtf.probed) probe(client) else poll(client, CretaAtf.selected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CretaAtf.markResult(false)
            return error(e.message ?: "Ошибка чтения", null)
        } finally {
            runCatching { client.restoreEngineEcuFilter() }
        }
    }

    private suspend fun exchange(client: Elm327Client, tx: String, rx: String, request: String): String {
        Elm327Client.ecuAddressing(tx, rx).forEach { runCatching { client.send(it, timeoutMs = 1500) } }
        return client.send(request, timeoutMs = 4000)
    }

    private suspend fun probe(client: Elm327Client): SensorReading {
        val rawByReq = LinkedHashMap<String, String>()
        val payloads = LinkedHashMap<CretaAtf.Candidate, ByteArray?>()
        var tcmAnswered = false
        for (c in CretaAtf.CANDIDATES) {
            val reqKey = "${c.request}@${c.tx}"
            val raw = rawByReq.getOrPut(reqKey) { exchange(client, c.tx, c.rx, c.request) }
            val p = CretaAtf.payload(raw, c.layout)
            payloads[c] = p
            if (answered(raw)) tcmAnswered = true
            log("probe ${c.label}", raw, p, CretaAtf.decode(p, c), c.sourceRu)
        }
        for ((tx, req) in CretaAtf.RAW_ONLY) {
            val raw = runCatching { exchange(client, tx, "7E9", req) }.getOrDefault("")
            if (answered(raw)) tcmAnswered = true
            log("raw $req@$tx", raw, null, null, "только RAW (для подбора байта)")
        }
        val sel = CretaAtf.choose(payloads)
        CretaAtf.markProbed(sel, tcmAnswered)
        if (sel == null) {
            return error(
                if (tcmAnswered) "АКПП отвечает, но ни один вариант не дал правдоподобной температуры — пришлите лог CVT из «Диагностики»"
                else "Блок АКПП (7E1/7E0) не ответил — нажмите «Сохранить диагностику» и пришлите файл",
                rawByReq.values.joinToString(" | ") { Elm327Parser.clean(it) }
            )
        }
        val raw = rawByReq.getValue("${sel.request}@${sel.tx}")
        return ok(sel, CretaAtf.decode(payloads[sel], sel)!!, raw)
    }

    private suspend fun poll(client: Elm327Client, c: CretaAtf.Candidate?): SensorReading {
        if (c == null) {
            // nothing found this connection: no bus traffic, re-probe after REPROBE_AFTER ticks
            CretaAtf.markResult(false)
            return error("Температура не найдена — «Сохранить диагностику» (вкладка «Ошибки») и пришлите файл", null)
        }
        val raw = exchange(client, c.tx, c.rx, c.request)
        val p = CretaAtf.payload(raw, c.layout)
        val v = CretaAtf.decode(p, c)
        log(c.label, raw, p, v, null)
        CretaAtf.markResult(v != null)
        return if (v != null) ok(c, v, raw) else error("Нет данных ${c.label}", Elm327Parser.clean(raw))
    }

    private fun answered(raw: String): Boolean {
        val msgs = Elm327Parser.assemble(raw)
        return msgs.any { it.bytes.isNotEmpty() }
    }

    private fun ok(c: CretaAtf.Candidate, v: Double, raw: String) = SensorReading(
        sensorId = id, displayName = displayNameRu, value = v, unit = unit,
        rawHex = Elm327Parser.clean(raw), note = CretaAtf.noteFor(c),
        detail = "Источник: ${c.label} · экспериментально"
    )

    private fun error(msg: String, raw: String?) = SensorReading(
        sensorId = id, displayName = displayNameRu, value = null, unit = unit,
        rawHex = raw, note = caveatRu, error = msg
    )

    private fun log(request: String, raw: String, p: ByteArray?, v: Double?, extra: String?) {
        val msg = Elm327Parser.assemble(raw).firstOrNull()
        CvtDebugLog.add(
            CvtDebugEntry(
                timestampMs = System.currentTimeMillis(),
                request = "АКПП $request",
                rawVisible = Elm327Parser.visibleRaw(raw),
                frameSummary = msg?.let {
                    "байт ${it.bytes.size}" + (it.declaredLength?.let { d -> ", заявлено $d" } ?: "") +
                        if (it.positionsTrustworthy) ", OK" else ", ДЫРА/обрезан"
                } ?: "нет кадра",
                payloadHex = p?.joinToString(" ") { "%02X".format(it) },
                candidates = listOfNotNull(CretaAtf.describe(p), extra).joinToString(" · "),
                valueC = v,
                error = if (v == null) "нет правдоподобного значения" else null
            )
        )
    }
}

