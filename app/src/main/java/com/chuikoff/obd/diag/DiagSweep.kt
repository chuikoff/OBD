package com.chuikoff.obd.diag

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Active part of «Сохранить диагностику» (polling paused): adapter identity, protocol, voltage,
 * which ECUs answer, supported PIDs, and a wide gearbox (A6GF1) data sweep on 7E1 and 7E0 —
 * every request with its exact reply goes into the text, so the ATF byte can be located offline.
 */
class DiagSweep(private val client: Elm327Client, private val onProgress: (String) -> Unit = {}) {

    data class Line(val label: String, val cmd: String, val raw: String) {
        fun toText(): String {
            val msgs = Elm327Parser.assemble(raw).filter { it.bytes.isNotEmpty() }
            val payload = msgs.joinToString(" | ") { m ->
                (m.headerId?.let { "$it: " } ?: "") + m.bytes.joinToString(" ") { "%02X".format(it) } +
                    if (!m.positionsTrustworthy) " (ДЫРА)" else ""
            }
            val vis = Elm327Parser.visibleRaw(raw).ifEmpty { "(пусто)" } + if (raw.contains('>') || raw == "OK") "" else "  [ТАЙМАУТ]"
            return "$label $cmd → $vis" + if (payload.isNotEmpty()) "\n    байты: $payload" else ""
        }
    }

    private val out = mutableListOf<Line>()

    private suspend fun tx(label: String, cmd: String, timeoutMs: Long = 2500): String {
        val raw = try {
            client.send(cmd, timeoutMs = timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "ОШИБКА: ${e.message}"
        }
        out += Line(label, cmd, raw)
        return raw
    }

    suspend fun adapterInfo(): List<Line> = client.probing {
        out.clear()
        try {
            onProgress("адаптер")
            for (c in listOf("ATI", "AT@1", "ATDP", "ATDPN", "ATRV")) tx("адаптер", c, 2000)
            onProgress("кто отвечает (7DF, заголовки вкл)")
            for (c in listOf("ATH1", "ATCAF1", "ATFCSM0", "ATSH7DF", "ATAR")) tx("OBD", c, 1500)
            for (c in listOf("0100", "0120", "0140", "0160", "0101", "011C", "0902")) tx("OBD 7DF", c, 4000)
        } finally {
            restore()
        }
        out.toList()
    }

    suspend fun gearbox(): List<Line> = client.probing {
        out.clear()
        try {
            for ((tx, rx) in listOf("7E1" to "7E9", "7E0" to "7E8")) {
                val label = "АКПП@$tx"
                onProgress("коробка: $tx")
                Elm327Client.ecuAddressing(tx, rx).forEach { tx(label, it, 1500) }
                tx(label, "3E00")
                tx(label, "0100")
                (0x01..0x0F).forEach { tx(label, "21%02X".format(it), 3000) }
                (0xA0..0xA5).forEach { tx(label, "21%02X".format(it), 3000) }
                (0x01..0x06).forEach { tx(label, "2201%02X".format(it), 3000) }
                (0x01..0x05).forEach { tx(label, "2220%02X".format(it), 3000) }
                listOf("222081", "222082", "22E001", "22E002", "22F190").forEach { tx(label, it, 3000) }
                onProgress("коробка: $tx, сессия 10 03")
                tx(label, "1003")
                listOf("2101", "2102", "2103", "21A0", "220101", "222081").forEach { tx("$label 10 03", it, 3000) }
                tx(label, "1001")
            }
            // Same TCM with the adapter's default flow control (some clones mishandle ATFCSM1)
            onProgress("коробка: 7E1 без FCSM1")
            val label = "АКПП@7E1 FC по умолч."
            listOf("ATSH7E1", "ATCRA7E9", "ATFCSM0").forEach { tx(label, it, 1500) }
            listOf("2101", "21A0", "222081").forEach { tx(label, it, 3000) }
        } finally {
            restore()
        }
        out.toList()
    }

    private suspend fun restore() = withContext(NonCancellable) {
        runCatching { client.send("ATH0", timeoutMs = 1500) }
        runCatching { client.restoreEngineEcuFilter() }
    }
}
