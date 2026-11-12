package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import kotlinx.coroutines.CancellationException

/**
 * EXPERIMENTAL: Outlander CVT (Jatco JF011E/JF016E) «CVT oil degradation level» counter, the value
 * MUT-III shows and clears after a fluid change. Not verified on this car yet.
 *
 * Sources (Torque-style letters, A = first byte after `61 10`):
 *  - elm3.ru Mitsubishi_Outlander_2008-2015.csv: `2110 @7E1`, `AC*256+AD` (and a «test» 24-bit
 *    `AB*65536+AC*256+AD`), range 0…220000;
 *  - Pelican (clutch.engineering): `21 10 @7E1`, bits 224–239 (= AC,AD), «output 3» variant bits
 *    192–207 (= Y,Z).
 *  - OUT-CLUB «Деградация масла в вариаторе Outlander 2013-2015»: owners' values ~0.2–1.1 per km,
 *    ~210 000 is quoted as the replacement point.
 * All candidates are logged (CVT log, request «2110») so the right one can be confirmed in the car.
 */
object CvtOilDegradation {
    const val REQUEST = "2110"
    const val PID = 0x10
    /** Unofficial guide from OUT-CLUB. */
    const val GUIDE_LIMIT = 210_000

    private const val IDX_Y = 24
    private const val IDX_AB = 27
    private const val IDX_AC = 28

    data class Result(
        val value: Int?,
        val source: String,
        val v16: Int?,
        val v24: Int?,
        val vOut3: Int?,
        val payloadSize: Int,
        val rawVisible: String,
        val error: String? = null,
        val atMs: Long = System.currentTimeMillis(),
        val payloadHex: String? = null
    ) {
        val percentOfGuide: Double? get() = value?.let { it * 100.0 / GUIDE_LIMIT }
        val candidatesText: String
            get() = buildString {
                append("AC·256+AD=${v16 ?: "—"} · AB·65536+AC·256+AD=${v24 ?: "—"} · Y·256+Z (Pelican out3)=${vOut3 ?: "—"}")
                append(" · байт данных: $payloadSize")
            }
    }

    private fun u(b: ByteArray, i: Int) = if (b.size > i) b[i].toInt() and 0xFF else null

    /** Decode bytes after `61 10`. */
    fun decode(payload: ByteArray, rawVisible: String = "", atMs: Long = System.currentTimeMillis()): Result {
        val ab = u(payload, IDX_AB)
        val ac = u(payload, IDX_AC)
        val ad = u(payload, IDX_AC + 1)
        val y = u(payload, IDX_Y)
        val z = u(payload, IDX_Y + 1)
        val v16 = if (ac != null && ad != null) ac * 256 + ad else null
        val v24 = if (ab != null && v16 != null) ab * 65536 + v16 else null
        val out3 = if (y != null && z != null) y * 256 + z else null
        val (value, source) = when {
            v16 == null -> null to "нет байт AC/AD"
            // 24-bit form only when the high byte is plausible (counter can pass 65535 after ~100 тыс. км)
            v24 != null && ab != 0 && v24 <= 250_000 -> v24 to "AB·65536+AC·256+AD (elm3 test) — старший байт ≠ 0, проверь"
            else -> v16 to "AC·256+AD (elm3.ru)"
        }
        return Result(
            value, source, v16, v24, out3, payload.size, rawVisible,
            error = if (v16 == null) "Короткий ответ 2110: ${payload.size} байт (нужно ≥ 30)" else null,
            atMs = atMs,
            payloadHex = payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        )
    }

    fun parse(raw: String, atMs: Long = System.currentTimeMillis()): Result {
        val visible = Elm327Parser.visibleRaw(raw)
        val err = Elm327Parser.validateMode21Response(raw, PID)
        val payload = Elm327Parser.parseMode21PayloadHardened(raw, PID, minDataBytes = 0)
        if (payload == null) {
            return Result(null, "—", null, null, null, 0, visible, err ?: "Нет кадра 6110", atMs)
        }
        val r = decode(payload, visible, atMs)
        return if (err != null && r.error == null) r.copy(error = err, value = null) else r
    }

    /** One read on TCM 7E1 (caller holds exclusive bus access, polling paused). */
    suspend fun read(client: Elm327Client): Result {
        var raw = ""
        return try {
            for (c in listOf("ATAT1") + Elm327Client.ecuAddressing("7E1", "7E9")) {
                client.send(c, timeoutMs = 1500)
            }
            raw = client.send(REQUEST, timeoutMs = 4000)
            var r = parse(raw)
            if (r.value == null) {
                raw = client.send(REQUEST, timeoutMs = 5000)
                r = parse(raw)
            }
            log(r)
            r
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(null, "—", null, null, null, 0, Elm327Parser.visibleRaw(raw), e.message ?: "Ошибка чтения").also(::log)
        } finally {
            for (c in Elm327Client.ENGINE_ADDRESSING) runCatching { client.send(c, timeoutMs = 1500) }
            runCatching { client.restoreEngineEcuFilter() }
        }
    }

    private fun log(r: Result) {
        CvtDebugLog.add(
            CvtDebugEntry(
                timestampMs = r.atMs,
                request = REQUEST,
                rawVisible = r.rawVisible,
                frameSummary = "деградация масла (эксп.): ${r.value ?: "—"} [${r.source}]",
                payloadHex = r.payloadHex,
                candidates = r.candidatesText,
                valueC = null,
                error = r.error
            )
        )
    }

    // ---- persistence (single line, for the «Авто» tab while offline) ----
    fun serialize(r: Result): String =
        listOf(r.value, r.v16, r.v24, r.vOut3, r.payloadSize, r.atMs).joinToString(",") { it?.toString() ?: "" } +
            "\n" + r.source + "\n" + (r.error ?: "") + "\n" + r.rawVisible.replace("\n", " ") +
            "\n" + (r.payloadHex ?: "")

    fun deserialize(s: String?): Result? = runCatching {
        if (s.isNullOrBlank()) return null
        val lines = s.split("\n")
        val n = lines[0].split(",").map { it.toIntOrNullOrLong() }
        Result(
            value = n[0]?.toInt(), v16 = n[1]?.toInt(), v24 = n[2]?.toInt(), vOut3 = n[3]?.toInt(),
            payloadSize = n[4]?.toInt() ?: 0, atMs = n[5] ?: 0L,
            source = lines.getOrElse(1) { "" }, error = lines.getOrElse(2) { "" }.ifBlank { null },
            rawVisible = lines.getOrElse(3) { "" },
            payloadHex = lines.getOrElse(4) { "" }.ifBlank { null }
        )
    }.getOrNull()

    private fun String.toIntOrNullOrLong(): Long? = if (isBlank()) null else toLong()
}
