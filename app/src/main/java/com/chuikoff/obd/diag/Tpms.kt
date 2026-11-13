package com.chuikoff.obd.diag

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import com.chuikoff.obd.elm327.Elm327Parser.toUnsigned
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Tyre pressure (TPMS) on Hyundai/Kia — EXPERIMENTAL, layouts from OBDb (Kia Rio 4 / Seltos /
 * Hyundai Elantra / Kona). A steadily lit TPMS lamp usually means LOW PRESSURE, which is a warning,
 * not a fault: no DTC exists for it, so a DTC scan alone can never «see» it.
 *  - BCM-integrated receiver: 22 C00B @7A0 → 4 wheels: pressure = X/5 psi, temperature = X−50 °C
 *    (Rio layout: 4 bytes per wheel from byte 4, order FL FR RR RL; Seltos/Kona-24: 5 bytes, FL FR RL RR).
 *  - Separate TPMS module: 21 06 @7D6 → FL FR RL RR, 4 bytes each, pressure X·0.0138 bar, temp X−50.
 *  - Indirect (ABS wheel-speed based, Rio 4 platform): 22 C104 @7D1 → last deflation detection odometer.
 */
object Tpms {
    data class Wheel(val pos: String, val bar: Double?, val tempC: Int?, val warn: Boolean? = null) {
        fun text(): String = "$pos: " + (bar?.let { String.format(Locale.US, "%.2f бар", it) } ?: "—") +
            (tempC?.let { ", $it °C" } ?: "") + if (warn == true) " ⚠" else ""
    }

    data class Result(val sourceRu: String, val wheels: List<Wheel>, val noteRu: String?, val raw: String)

    const val PSI_TO_BAR = 0.0689476

    private fun payload(raw: String, vararg hdr: Int): ByteArray? {
        val m = Elm327Parser.assemble(raw).firstOrNull { msg ->
            msg.bytes.size >= hdr.size && hdr.indices.all { toUnsigned(msg.bytes[it]) == hdr[it] }
        } ?: return null
        if (!m.positionsTrustworthy) return null
        return m.bytes.copyOfRange(hdr.size, m.bytes.size)
    }

    private fun u(p: ByteArray, i: Int): Int? = if (i < p.size) toUnsigned(p[i]) else null
    private fun psiBar(x: Int?): Double? = x?.takeIf { it in 1..254 }?.let { it / 5.0 * PSI_TO_BAR }
    private fun temp(x: Int?): Int? = x?.takeIf { it in 1..254 }?.let { it - 50 }

    /** 7A0 22 C00B. */
    fun parseBcm(raw: String): Result? {
        val p = payload(raw, 0x62, 0xC0, 0x0B) ?: return null
        val wheels = if (p.size >= 24) {
            listOf("ПЛ" to 4, "ПП" to 9, "ЗЛ" to 14, "ЗП" to 19).map { (n, o) ->
                Wheel(n, psiBar(u(p, o)), temp(u(p, o + 1)))
            }
        } else if (p.size >= 20) {
            listOf("ПЛ" to 4, "ПП" to 8, "ЗП" to 12, "ЗЛ" to 16).map { (n, o) ->
                Wheel(n, psiBar(u(p, o)), temp(u(p, o + 1)))
            }
        } else return Result("Кузов/BCM 7A0 (22 C00B)", emptyList(), "ответ ${p.size} байт — раскладка неизвестна", Elm327Parser.clean(raw))
        return Result("Кузов/BCM 7A0 (22 C00B)", wheels, null, Elm327Parser.clean(raw))
    }

    /** 7D6 21 06. */
    fun parseModule(raw: String): Result? {
        val p = payload(raw, 0x61, 0x06) ?: return null
        val wheels = listOf("ПЛ" to 0, "ПП" to 4, "ЗЛ" to 8, "ЗП" to 12).map { (n, o) ->
            Wheel(n, u(p, o)?.takeIf { it in 1..254 }?.let { it * 0.01378952 }, temp(u(p, o + 1)))
        }
        return Result("Блок TPMS 7D6 (21 06)", wheels, null, Elm327Parser.clean(raw))
    }

    /** 7D1 22 C104 (indirect TPMS in ABS). */
    fun parseAbs(raw: String): Result? {
        val p = payload(raw, 0x62, 0xC1, 0x04) ?: return null
        fun u32(i: Int): Long? = if (i + 3 < p.size) (0..3).fold(0L) { a, k -> (a shl 8) or toUnsigned(p[i + k]).toLong() } else null
        val last = u32(8)?.takeIf { it != 0xFFFFFFFFL }
        val set = u32(48)?.takeIf { it != 0xFFFFFFFFL }
        val note = "Непрямой TPMS (через ABS): последнее падение давления на пробеге ${last ?: "—"} км, " +
            "последняя калибровка (TPMS SET) на ${set ?: "—"} км"
        return Result("ABS 7D1 (22 C104)", emptyList(), note, Elm327Parser.clean(raw))
    }

    /** Ask every known TPMS source; each exchange is logged. */
    suspend fun probe(client: Elm327Client, log: (DtcLogLine) -> Unit): List<Result> {
        val out = mutableListOf<Result>()
        suspend fun ask(tx: String, rx: String, req: String, tag: String, parse: (String) -> Result?) {
            Elm327Client.ecuAddressing(tx, rx).forEach { runCatching { client.send(it, timeoutMs = 1500) } }
            var raw = send(client, req, tag, log)
            var r = parse(raw)
            if (r == null && Elm327Parser.hexDigits(raw).startsWith("7F")) {
                send(client, "1003", tag, log)
                raw = send(client, req, tag, log)
                r = parse(raw)
            }
            r?.let { out += it }
        }
        ask("7A0", "7A8", "22C00B", "TPMS 7A0", ::parseBcm)
        ask("7D6", "7DE", "2106", "TPMS 7D6", ::parseModule)
        ask("7D1", "7D9", "22C104", "TPMS ABS 7D1", ::parseAbs)
        return out
    }

    private suspend fun send(client: Elm327Client, cmd: String, tag: String, log: (DtcLogLine) -> Unit): String {
        val raw = try {
            client.send(cmd, timeoutMs = 3000)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "ОШИБКА: ${e.message}"
        }
        log(DtcLogLine(System.currentTimeMillis(), tag, cmd, Elm327Parser.visibleRaw(raw).ifEmpty { "(пусто)" }))
        return raw
    }

    const val HINT_RU =
        "Лампа давления в шинах горит постоянно → низкое давление (это предупреждение, кода ошибки нет): " +
            "проверьте давление во всех шинах на холодную и подкачайте до нормы с таблички на стойке водителя, " +
            "проехать 10–20 мин — лампа погаснет. Мигает ~1 мин после включения зажигания, затем горит → " +
            "неисправность системы (датчик/батарейка), тогда должен быть код в блоке кузова/TPMS."
}
