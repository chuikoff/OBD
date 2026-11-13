package com.chuikoff.obd.diag

import com.chuikoff.obd.elm327.Elm327Parser
import com.chuikoff.obd.elm327.Elm327Parser.toUnsigned

/** Pure parsing of OBD Mode 03/07/0A/0101 and UDS 19 02 / KWP 18 replies (unit-tested). */
object DtcDecode {

    /** Hyundai/Kia CAN addressing (GDS), request id → reply id = +8. */
    val HYUNDAI_MODULES = listOf(
        DiagModule("Двигатель (ECM)", "7E0", "7E8"),
        DiagModule("АКПП (TCM)", "7E1", "7E9"),
        DiagModule("ABS / ESC", "7D1", "7D9"),
        DiagModule("Подушки (SRS)", "7D2", "7DA"),
        DiagModule("Электроусилитель руля (MDPS)", "7D4", "7DC"),
        DiagModule("Приборная панель", "7C6", "7CE"),
        DiagModule("Кузов (BCM)", "7A0", "7A8"),
        DiagModule("Кузов / IPS", "770", "778"),
        DiagModule("Климат", "7B3", "7BB"),
        DiagModule("Датчики давления шин (TPMS)", "7D6", "7DE"),
        DiagModule("Блок 7D0", "7D0", "7D8")
    )

    fun ecuName(id: String?): String = when (id?.uppercase()) {
        null -> "ЭБУ (адрес неизвестен)"
        "7E8" -> "Двигатель (7E8)"
        "7E9" -> "АКПП (7E9)"
        "7EA" -> "Блок 7EA"
        else -> HYUNDAI_MODULES.firstOrNull { it.rx == id.uppercase() }?.let { "${it.name} ($id)" } ?: "ЭБУ $id"
    }

    /** OBD Mode 03 / 07 / 0A reply (functional, several ECUs). */
    fun parseObd(raw: String, kind: DtcKind): List<DtcEntry> {
        val sid = when (kind) { DtcKind.STORED -> 0x43; DtcKind.PENDING -> 0x47; DtcKind.PERMANENT -> 0x4A; else -> return emptyList() }
        return Elm327Parser.assemble(raw)
            .filter { it.bytes.size >= 2 && toUnsigned(it.bytes[0]) == sid }
            .flatMap { m ->
                val count = toUnsigned(m.bytes[1])
                m.bytes.drop(2).take(count * 2).chunked(2).filter { it.size == 2 }
                    .mapNotNull { Elm327Parser.formatDtc(toUnsigned(it[0]), toUnsigned(it[1])) }
                    .map { DtcEntry(it, m.headerId, ecuName(m.headerId), kind) }
            }
            .distinctBy { it.code to it.ecuId }
    }

    /** 41 01 A B C D: A bit 7 = MIL, A & 0x7F = number of emission DTCs. */
    fun parseMil(raw: String): List<MilStatus> =
        Elm327Parser.assemble(raw)
            .filter { it.bytes.size >= 3 && toUnsigned(it.bytes[0]) == 0x41 && toUnsigned(it.bytes[1]) == 0x01 }
            .map { val a = toUnsigned(it.bytes[2]); MilStatus(it.headerId, a and 0x80 != 0, a and 0x7F) }
            .distinctBy { it.ecuId }

    /** 3-byte UDS DTC → «P0133», with the failure-type byte as a suffix when non-zero («C1611-01»). */
    fun formatUds(hi: Int, mid: Int, ftb: Int): String? {
        val base = Elm327Parser.formatDtc(hi, mid) ?: return null
        return if (ftb == 0) base else "%s-%02X".format(base, ftb)
    }

    /** UDS status byte (ISO 14229): only these bits mean «there is a fault». */
    const val UDS_ACTIVE_MASK = 0x8F // testFailed | thisCycle | pending | confirmed | warningIndicator

    fun udsStatusRu(s: Int): String {
        val parts = mutableListOf<String>()
        if (s and 0x01 != 0) parts += "активна сейчас"
        if (s and 0x08 != 0) parts += "подтверждена"
        if (s and 0x04 != 0) parts += "pending"
        if (s and 0x80 != 0) parts += "лампа"
        if (s and 0x01 == 0 && s and 0x08 != 0) parts += "в памяти"
        if (parts.isEmpty()) parts += "не активна"
        return parts.joinToString(", ") + " (статус %02X)".format(s)
    }

    sealed class Reply {
        data class Codes(val codes: List<DtcEntry>, val ignored: Int) : Reply()
        data class Negative(val nrc: Int) : Reply()
        object Pending : Reply()
        object None : Reply()
        /** Something answered but not parseable (still: the ECU is there). */
        object Junk : Reply()
    }

    /** UDS 59 02 [mask] (DTC hi mid ftb status)* — or 7F 19 NRC. */
    fun parseUds1902(raw: String, module: DiagModule): Reply {
        val msgs = Elm327Parser.assemble(raw).filter { it.bytes.isNotEmpty() }
        msgs.firstOrNull { it.bytes.size >= 2 && toUnsigned(it.bytes[0]) == 0x59 && toUnsigned(it.bytes[1]) == 0x02 }?.let { m ->
            val body = m.bytes.drop(3) // skip 59 02 availabilityMask
            var ignored = 0
            val codes = body.chunked(4).filter { it.size == 4 }.mapNotNull { g ->
                val st = toUnsigned(g[3])
                val code = formatUds(toUnsigned(g[0]), toUnsigned(g[1]), toUnsigned(g[2])) ?: return@mapNotNull null
                if (st and UDS_ACTIVE_MASK == 0) { ignored++; return@mapNotNull null }
                DtcEntry(code, module.rx, "${module.name} (${module.rx})", DtcKind.UDS, st, udsStatusRu(st))
            }.distinctBy { it.code }
            return Reply.Codes(codes, ignored)
        }
        return negativeOr(msgs, 0x19)
    }

    /** KWP2000 58 N (hi lo status)* — or 7F 18 NRC. All listed codes are reported. */
    fun parseKwp18(raw: String, module: DiagModule): Reply {
        val msgs = Elm327Parser.assemble(raw).filter { it.bytes.isNotEmpty() }
        msgs.firstOrNull { it.bytes.size >= 2 && toUnsigned(it.bytes[0]) == 0x58 }?.let { m ->
            val n = toUnsigned(m.bytes[1])
            val codes = m.bytes.drop(2).take(n * 3).chunked(3).filter { it.size == 3 }.mapNotNull { g ->
                val st = toUnsigned(g[2])
                val code = Elm327Parser.formatDtc(toUnsigned(g[0]), toUnsigned(g[1])) ?: return@mapNotNull null
                DtcEntry(code, module.rx, "${module.name} (${module.rx})", DtcKind.KWP, st, kwpStatusRu(st))
            }.distinctBy { it.code }
            return Reply.Codes(codes, 0)
        }
        return negativeOr(msgs, 0x18)
    }

    fun kwpStatusRu(s: Int): String {
        val active = s and 0x40 != 0 || (s shr 5) and 0x03 == 0x01 || (s shr 5) and 0x03 == 0x03
        val lamp = s and 0x80 != 0
        return listOfNotNull(if (active) "активна" else "в памяти", if (lamp) "лампа" else null)
            .joinToString(", ") + " (статус %02X)".format(s)
    }

    private fun negativeOr(msgs: List<Elm327Parser.ObdMessage>, sid: Int): Reply {
        val neg = msgs.filter { it.bytes.size >= 3 && toUnsigned(it.bytes[0]) == 0x7F && toUnsigned(it.bytes[1]) == sid }
        val final = neg.lastOrNull { toUnsigned(it.bytes[2]) != 0x78 }
        if (final != null) return Reply.Negative(toUnsigned(final.bytes[2]))
        if (neg.isNotEmpty()) return Reply.Pending
        return if (msgs.isEmpty()) Reply.None else Reply.Junk
    }

    fun nrcRu(nrc: Int): String = when (nrc) {
        0x11 -> "сервис не поддерживается"
        0x12 -> "подфункция не поддерживается"
        0x13 -> "неверная длина запроса"
        0x22 -> "условия не выполнены"
        0x31 -> "запрос вне диапазона"
        0x33 -> "нужен доступ (security)"
        0x7E, 0x7F -> "не в этой сессии"
        else -> "NRC %02X".format(nrc)
    }
}
