package com.chuikoff.obd.diag

import com.chuikoff.obd.elm327.Elm327Parser
import com.chuikoff.obd.elm327.Elm327Parser.toUnsigned

/** Pure parsing of OBD Mode 03/07/0A/0101 and UDS 19 02 / KWP 18 replies (unit-tested). */
object DtcDecode {

    /** Known Hyundai/Kia CAN addresses (GDS), request id → reply id = +8. */
    private val KNOWN = linkedMapOf(
        "7E0" to "Двигатель (ECM)",
        "7E1" to "АКПП (TCM)",
        "7E2" to "Блок 7E2 (гибрид/доп.)",
        "7D1" to "ABS / ESC",
        "7D2" to "Подушки (SRS)",
        "7D4" to "Электроусилитель руля (MDPS)",
        "7C6" to "Приборная панель",
        "7C4" to "Парктроник / камера",
        "7A0" to "Кузов (BCM) / TPMS",
        "770" to "Кузов / IPS",
        "7B3" to "Климат",
        "7D6" to "Датчики давления шин (TPMS)",
        "7A5" to "Блок 7A5 (TPMS/иммобилайзер)",
        "7D0" to "Блок 7D0",
        "7A6" to "Блок 7A6",
        "7B7" to "Блок 7B7 (сиденья/двери)",
        "7C2" to "Блок 7C2 (подрулевой)",
        "7D3" to "Блок 7D3"
    )

    /**
     * Full-check address list: the known modules first, then a sweep of the usual Hyundai/Kia
     * diagnostic ranges (7A0–7A7, 7B0–7B7, 7C0–7C7, 7D0–7D7, 7E0–7E5, 770–777). Silent ones cost
     * two short NO DATA.
     */
    val HYUNDAI_MODULES: List<DiagModule> = run {
        val ids = LinkedHashSet<String>(KNOWN.keys)
        listOf(0x7A0, 0x7B0, 0x7C0, 0x7D0, 0x770).forEach { base -> (0..7).forEach { ids += "%03X".format(base + it) } }
        (0..5).forEach { ids += "%03X".format(0x7E0 + it) }
        ids.map { tx -> DiagModule(KNOWN[tx] ?: "Блок $tx", tx, "%03X".format(tx.toInt(16) + 8)) }
    }

    /** Quick check, Hyundai Creta GS: proven addresses first (7E0/7E1/7D1/7A0 answered on the car). */
    val QUICK_CRETA: List<DiagModule> = listOf(
        DiagModule("Двигатель (ECM)", "7E0", "7E8"),
        DiagModule("АКПП A6GF1 (TCM)", "7E1", "7E9"),
        DiagModule("ABS / ESC", "7D1", "7D9"),
        DiagModule("ABS / ESC #2 (EPB)", "7D5", "7DD"),
        DiagModule("Полный привод 4WD", "7E7", "7EF"),
        DiagModule("Полный привод 4WD #2", "740", "748"),
        DiagModule("TPMS (кузов BCM)", "7A0", "7A8"),
        DiagModule("TPMS (отдельный блок)", "7D6", "7DE")
    )

    /** Quick check, Mitsubishi Outlander GF/GG (OBDb Mitsubishi-Outlander addressing, rx ≠ tx+8). */
    val QUICK_OUTLANDER: List<DiagModule> = listOf(
        DiagModule("Двигатель (ECM)", "7E0", "7E8"),
        DiagModule("CVT (TCM)", "7E1", "7E9"),
        DiagModule("ABS / ASC", "784", "785"),
        DiagModule("ABS / ASC #2", "7D1", "7D9"),
        DiagModule("AWC / 4WD", "7B6", "7B7"),
        DiagModule("AWC / 4WD #2", "7B5", "7BD"),
        DiagModule("TPMS", "743", "763"),
        DiagModule("TPMS #2", "773", "774"),
        DiagModule("TPMS #3 (ETACS)", "600", "500")
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
        if (s and 0x40 != 0 && parts.isEmpty()) parts += "тест в этой поездке ещё не выполнялся"
        if (s and 0x10 != 0 && s and 0x01 == 0 && s and 0x08 == 0) parts += "не проверялась после сброса"
        if (parts.isEmpty()) parts += "не активна"
        return parts.joinToString(", ") + " (статус %02X)".format(s)
    }

    sealed class Reply {
        /** [inactive]: listed by the ECU but not failed/confirmed/pending (e.g. status 40 «not tested yet»). */
        data class Codes(val codes: List<DtcEntry>, val ignored: Int, val inactive: List<DtcEntry> = emptyList()) : Reply()
        data class Negative(val nrc: Int) : Reply()
        object Pending : Reply()
        object None : Reply()
        /** Something answered but not parseable (still: the ECU is there). */
        object Junk : Reply()
    }

    /** Availability mask from «59 02 MM …» (null if not a positive 19 02 reply). */
    fun udsAvailabilityMask(raw: String): Int? =
        Elm327Parser.assemble(raw).firstOrNull {
            it.bytes.size >= 3 && toUnsigned(it.bytes[0]) == 0x59 && toUnsigned(it.bytes[1]) in setOf(0x02, 0x0A)
        }?.let { toUnsigned(it.bytes[2]) }

    /** UDS 59 02 [mask] (DTC hi mid ftb status)* — or 7F 19 NRC. Also 59 0A (all supported DTCs). */
    fun parseUds1902(raw: String, module: DiagModule): Reply {
        val msgs = Elm327Parser.assemble(raw).filter { it.bytes.isNotEmpty() }
        msgs.firstOrNull { it.bytes.size >= 2 && toUnsigned(it.bytes[0]) == 0x59 && toUnsigned(it.bytes[1]) in setOf(0x02, 0x0A) }?.let { m ->
            val body = m.bytes.drop(3) // skip 59 02 availabilityMask
            var ignored = 0
            val inactive = mutableListOf<DtcEntry>()
            val codes = body.chunked(4).filter { it.size == 4 }.mapNotNull { g ->
                val st = toUnsigned(g[3])
                val code = formatUds(toUnsigned(g[0]), toUnsigned(g[1]), toUnsigned(g[2])) ?: return@mapNotNull null
                val e = DtcEntry(code, module.rx, "${module.name} (${module.rx})", DtcKind.UDS, st, udsStatusRu(st))
                if (st and UDS_ACTIVE_MASK == 0) { ignored++; inactive += e; return@mapNotNull null }
                e
            }.distinctBy { it.code }
            return Reply.Codes(codes, ignored, inactive.distinctBy { it.code })
        }
        return negativeOr(msgs, 0x19)
    }

    /** KWP2000 58 N (hi lo status)* — or 7F 18 NRC. All listed codes are reported. */
    fun parseKwp18(raw: String, module: DiagModule): Reply = parseKwp(raw, module, 0x18)

    /** KWP2000 18 (readDTCByStatus → 58) or 17 (readStatusOfDTC → 57): N, then (hi lo status)*. */
    fun parseKwp(raw: String, module: DiagModule, sid: Int): Reply {
        val msgs = Elm327Parser.assemble(raw).filter { it.bytes.isNotEmpty() }
        msgs.firstOrNull { it.bytes.size >= 2 && toUnsigned(it.bytes[0]) == sid + 0x40 }?.let { m ->
            val n = toUnsigned(m.bytes[1])
            val codes = m.bytes.drop(2).take(n * 3).chunked(3).filter { it.size == 3 }.mapNotNull { g ->
                val st = toUnsigned(g[2])
                val code = Elm327Parser.formatDtc(toUnsigned(g[0]), toUnsigned(g[1])) ?: return@mapNotNull null
                DtcEntry(code, module.rx, "${module.name} (${module.rx})", DtcKind.KWP, st, kwpStatusRu(st))
            }.distinctBy { it.code }
            return Reply.Codes(codes, 0)
        }
        return negativeOr(msgs, sid)
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
        0x21 -> "блок занят (повторяли)"
        0x22 -> "условия не выполнены"
        0x31 -> "запрос вне диапазона"
        0x33 -> "нужен доступ (security)"
        0x7E, 0x7F -> "не в этой сессии"
        else -> "NRC %02X".format(nrc)
    }
}
