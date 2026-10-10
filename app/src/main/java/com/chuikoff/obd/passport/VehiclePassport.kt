package com.chuikoff.obd.passport

import com.chuikoff.obd.elm327.Elm327Parser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure-Kotlin data + parsers for the «Авто» (vehicle passport) tab. */

enum class MonitorStatus(val labelRu: String) { READY("готов"), NOT_READY("не готов"), NA("н/д") }

data class Monitor(val nameRu: String, val status: MonitorStatus)

data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    val compressionIgnition: Boolean,
    val monitors: List<Monitor>
)

data class PassportData(
    val timestampMs: Long,
    val profileName: String,
    val vin: String? = null,
    val vinError: String? = null,
    val calIds: List<String> = emptyList(),
    val calError: String? = null,
    val cvns: List<String> = emptyList(),
    val cvnError: String? = null,
    val ecuNames: List<String> = emptyList(),
    val ecuError: String? = null,
    val pids01: List<Int>? = null,
    val pids01Error: String? = null,
    val pids09: List<Int>? = null,
    val readiness: Readiness? = null,
    val readinessError: String? = null
)

data class VinInfo(
    val valid: Boolean,
    val problems: List<String>,
    val makerRu: String?,
    val countryRu: String?,
    val modelYear: Int?,
    val plantCode: Char?
)

object Passport {
    const val NOT_SUPPORTED = "не поддерживается"
    const val NO_RESPONSE = "нет ответа ЭБУ"

    // ---------- VIN ----------
    private val WMI = mapOf(
        "JMB" to "Mitsubishi (Япония)", "JMY" to "Mitsubishi (Япония)", "JMP" to "Mitsubishi (Япония)",
        "JA3" to "Mitsubishi (Япония, легковые)", "JA4" to "Mitsubishi (Япония, SUV)",
        "JA7" to "Mitsubishi (Япония, грузовые)",
        "KMH" to "Hyundai (Корея)", "KM8" to "Hyundai (Корея, SUV)", "KMF" to "Hyundai (Корея, фургоны)",
        "Z94" to "Hyundai (Россия, Санкт-Петербург)", "TMA" to "Hyundai (Чехия)",
        "Z8T" to "PSA/Mitsubishi (Россия, Калуга)"
    )
    private val WMI2 = mapOf("JM" to "Mitsubishi (Япония)", "KM" to "Hyundai (Корея)", "KN" to "Kia (Корея)")

    private fun countryByFirst(c: Char): String? = when (c) {
        'J' -> "Япония"; 'K' -> "Корея"; 'L' -> "Китай"; 'S' -> "Великобритания"
        'T' -> "Чехия/Европа"; 'W' -> "Германия"; 'X' -> "Россия"; 'Z' -> "Италия/Россия"
        '1', '4', '5' -> "США"; '2' -> "Канада"; '3' -> "Мексика"; '6' -> "Австралия"
        'V' -> "Франция/Испания"; 'Y' -> "Швеция/Финляндия"
        else -> null
    }

    private const val YEAR_LETTERS = "ABCDEFGHJKLMNPRSTVWXY" // 2010..2030 (no I O Q U Z 0)

    fun modelYear(c: Char): Int? {
        val u = c.uppercaseChar()
        if (u in '1'..'9') return 2000 + (u - '0')
        val i = YEAR_LETTERS.indexOf(u)
        return if (i >= 0) 2010 + i else null
    }

    fun decodeVin(vinRaw: String?): VinInfo {
        val vin = vinRaw?.trim()?.uppercase().orEmpty()
        val problems = mutableListOf<String>()
        if (vin.length != 17) problems += "длина ${vin.length}, нужно 17"
        if (vin.any { it == 'I' || it == 'O' || it == 'Q' }) problems += "содержит I/O/Q"
        if (vin.any { !(it in 'A'..'Z' || it in '0'..'9') }) problems += "недопустимые символы"
        val maker = if (vin.length >= 3) (WMI[vin.substring(0, 3)] ?: WMI2[vin.substring(0, 2)]) else null
        val country = vin.firstOrNull()?.let { countryByFirst(it) }
        val year = if (vin.length >= 10) modelYear(vin[9]) else null
        val plant = if (vin.length >= 11) vin[10] else null
        return VinInfo(problems.isEmpty(), problems, maker, country, year, plant)
    }

    // ---------- generic response helpers ----------
    private fun u(b: Byte) = b.toInt() and 0xFF

    /** Payloads (bytes after `svc pid`) of every complete message in the reply that answers svc/pid. */
    fun payloads(raw: String, svc: Int, pid: Int): List<ByteArray> {
        if (Elm327Parser.clean(raw).contains("NODATA")) return emptyList()
        return Elm327Parser.assemble(raw)
            .filter { it.bytes.size >= 2 && u(it.bytes[0]) == svc && u(it.bytes[1]) == pid && it.complete }
            .map { it.bytes.copyOfRange(2, it.bytes.size) }
    }

    private fun asciiClean(b: ByteArray): String =
        String(b.filter { u(it) in 0x20..0x7E }.toByteArray(), Charsets.US_ASCII).trim()

    /** Mode 09 PID 02: [count] + 17 ASCII (some ECUs omit the count byte or pad with 00). */
    fun parseVin(raw: String): String? {
        val p = payloads(raw, 0x49, 0x02).firstOrNull() ?: return null
        val data = when {
            p.size >= 18 && u(p[0]) <= 0x05 -> p.copyOfRange(1, p.size)
            else -> p
        }
        val s = asciiClean(data)
        return s.takeIf { it.isNotEmpty() }
    }

    /** Mode 09 PID 04: [count] + N×16 ASCII. */
    fun parseCalIds(raw: String): List<String> =
        payloads(raw, 0x49, 0x04).flatMap { p -> chunks(p, 16).map { asciiClean(it) }.filter { it.isNotEmpty() } }

    /** Mode 09 PID 06: [count] + N×4 bytes, shown as hex. */
    fun parseCvns(raw: String): List<String> =
        payloads(raw, 0x49, 0x06).flatMap { p ->
            chunks(p, 4).filter { it.size == 4 }.map { c -> c.joinToString("") { "%02X".format(u(it)) } }
        }.filter { it != "00000000" }

    /** Mode 09 PID 0A: [count] + 20 ASCII ECU name. */
    fun parseEcuNames(raw: String): List<String> =
        payloads(raw, 0x49, 0x0A).map { p ->
            asciiClean(if (p.size >= 21 || (p.isNotEmpty() && u(p[0]) <= 0x05 && p.size % 20 == 1)) p.copyOfRange(1, p.size) else p)
        }.filter { it.isNotEmpty() }

    /** Drops the leading count byte and splits the rest in [size]-byte blocks. */
    private fun chunks(p: ByteArray, size: Int): List<ByteArray> {
        if (p.isEmpty()) return emptyList()
        val body = if ((p.size - 1) % size == 0) p.copyOfRange(1, p.size) else p
        return body.toList().chunked(size).map { it.toByteArray() }
    }

    // ---------- supported PIDs ----------
    /** Bitmask of 4 bytes → PIDs base+1 … base+32 (MSB first). */
    fun decodeSupportMask(base: Int, mask: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        for (i in 0 until 32) {
            val byte = i / 8
            if (byte >= mask.size) break
            if ((u(mask[byte]) shr (7 - i % 8)) and 1 == 1) out += base + i + 1
        }
        return out
    }

    /** Union over all answering ECUs for one `svc 00/20/40…` request. */
    fun parseSupportReply(raw: String, svc: Int, base: Int): List<Int>? {
        val ps = payloads(raw, svc, base).filter { it.size >= 4 }
        if (ps.isEmpty()) return null
        return ps.flatMap { decodeSupportMask(base, it) }.distinct().sorted()
    }

    private val PID_NAMES = mapOf(
        0x01 to "Статус мониторов", 0x03 to "Статус топливной системы", 0x04 to "Расчётная нагрузка",
        0x05 to "Температура ОЖ", 0x06 to "Коррекция топлива STFT B1", 0x07 to "Коррекция топлива LTFT B1",
        0x08 to "Коррекция топлива STFT B2", 0x09 to "Коррекция топлива LTFT B2",
        0x0A to "Давление топлива", 0x0B to "Давление во впуске (MAP)", 0x0C to "Обороты двигателя",
        0x0D to "Скорость", 0x0E to "Угол опережения зажигания", 0x0F to "Температура впуска",
        0x10 to "Расход воздуха (MAF)", 0x11 to "Положение дросселя", 0x12 to "Вторичный воздух",
        0x13 to "Расположение O2-датчиков", 0x14 to "O2 B1S1", 0x15 to "O2 B1S2", 0x16 to "O2 B1S3",
        0x17 to "O2 B1S4", 0x18 to "O2 B2S1", 0x19 to "O2 B2S2", 0x1C to "Стандарт OBD",
        0x1F to "Время работы двигателя", 0x20 to "Поддержка PID 21–40", 0x21 to "Пробег с горящим MIL",
        0x2C to "Команда EGR", 0x2E to "Команда продувки EVAP", 0x2F to "Уровень топлива",
        0x30 to "Прогревов после сброса", 0x31 to "Пробег после сброса", 0x33 to "Барометрическое давление",
        0x3C to "Температура катализатора B1S1", 0x3D to "Температура катализатора B2S1",
        0x40 to "Поддержка PID 41–60", 0x41 to "Статус мониторов цикла", 0x42 to "Напряжение бортсети",
        0x43 to "Абсолютная нагрузка", 0x44 to "Команда λ (воздух/топливо)", 0x45 to "Относительное положение дросселя",
        0x46 to "Температура наружного воздуха", 0x47 to "Положение дросселя B", 0x49 to "Педаль акселератора D",
        0x4A to "Педаль акселератора E", 0x4C to "Команда дросселя", 0x51 to "Тип топлива",
        0x5C to "Температура масла", 0x60 to "Поддержка PID 61–80"
    )

    fun pidName(pid: Int): String = PID_NAMES[pid] ?: "PID %02X".format(pid)
    fun pidLabel(pid: Int): String = "%02X — %s".format(pid, pidName(pid))

    // ---------- readiness (Mode 01 PID 01) ----------
    fun decodeReadiness(p: ByteArray): Readiness? {
        if (p.size < 4) return null
        val a = u(p[0]); val b = u(p[1]); val c = u(p[2]); val d = u(p[3])
        val compression = (b shr 3) and 1 == 1
        fun cont(availBit: Int, incompleteBit: Int, name: String): Monitor {
            val avail = (b shr availBit) and 1 == 1
            val inc = (b shr incompleteBit) and 1 == 1
            return Monitor(name, if (!avail) MonitorStatus.NA else if (inc) MonitorStatus.NOT_READY else MonitorStatus.READY)
        }
        fun nonCont(bit: Int, name: String): Monitor {
            val avail = (c shr bit) and 1 == 1
            val notReady = (d shr bit) and 1 == 1
            return Monitor(name, if (!avail) MonitorStatus.NA else if (notReady) MonitorStatus.NOT_READY else MonitorStatus.READY)
        }
        val monitors = mutableListOf(
            cont(0, 4, "Пропуски зажигания"),
            cont(1, 5, "Топливная система"),
            cont(2, 6, "Компоненты")
        )
        if (!compression) {
            monitors += nonCont(0, "Катализатор")
            monitors += nonCont(1, "Подогрев катализатора")
            monitors += nonCont(2, "Система EVAP")
            monitors += nonCont(3, "Вторичный воздух")
            monitors += nonCont(4, "Кондиционер")
            monitors += nonCont(5, "Кислородный датчик (O2)")
            monitors += nonCont(6, "Подогрев O2-датчика")
            monitors += nonCont(7, "Система EGR")
        } else {
            monitors += nonCont(0, "Катализатор NMHC")
            monitors += nonCont(1, "NOx/SCR")
            monitors += nonCont(3, "Наддув")
            monitors += nonCont(5, "Датчик выхлопных газов")
            monitors += nonCont(6, "Сажевый фильтр")
            monitors += nonCont(7, "EGR/VVT")
        }
        return Readiness((a shr 7) and 1 == 1, a and 0x7F, compression, monitors)
    }

    fun parseReadiness(raw: String): Readiness? =
        payloads(raw, 0x41, 0x01).firstNotNullOfOrNull { decodeReadiness(it) }

    // ---------- export ----------
    fun toText(d: PassportData, appVersion: String? = null): String = buildString {
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru"))
        append("Паспорт авто")
        if (appVersion != null) append(" (OBD $appVersion)")
        append("\nПрофиль: ${d.profileName}\nОбновлено: ${fmt.format(Date(d.timestampMs))}\n\n")
        append("VIN: ${d.vin ?: "— (${d.vinError ?: NO_RESPONSE})"}\n")
        if (d.vin != null) {
            val i = decodeVin(d.vin)
            append("  ${if (i.valid) "VIN корректен" else "ВНИМАНИЕ: " + i.problems.joinToString("; ")}\n")
            i.makerRu?.let { append("  Производитель: $it\n") } ?: i.countryRu?.let { append("  Страна: $it\n") }
            i.modelYear?.let { append("  Модельный год: $it (или ${it - 30})\n") }
            i.plantCode?.let { append("  Код завода: $it\n") }
        }
        fun list(title: String, items: List<String>, err: String?) {
            append("$title: ")
            if (items.isEmpty()) append("— (${err ?: NO_RESPONSE})\n") else append(items.joinToString(", ")).append("\n")
        }
        list("Calibration ID", d.calIds, d.calError)
        list("CVN", d.cvns, d.cvnError)
        list("ЭБУ", d.ecuNames, d.ecuError)
        append("\nПоддерживаемые PID (Mode 01): ")
        if (d.pids01 == null) append("— (${d.pids01Error ?: NO_RESPONSE})\n") else {
            append("${d.pids01.size}\n")
            d.pids01.forEach { append("  ${pidLabel(it)}\n") }
        }
        d.pids09?.let { append("Mode 09: ${it.joinToString(", ") { p -> "%02X".format(p) }}\n") }
        append("\nГотовность мониторов: ")
        val r = d.readiness
        if (r == null) append("— (${d.readinessError ?: NO_RESPONSE})\n") else {
            append("MIL ${if (r.milOn) "горит" else "не горит"}, DTC: ${r.dtcCount}\n")
            r.monitors.forEach { append("  ${it.nameRu}: ${it.status.labelRu}\n") }
        }
    }

    // ---------- cache serialization (no org.json: unit-testable on JVM) ----------
    private const val SEP = "\u001F"
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { sb.append(if (s[i + 1] == 'n') '\n' else s[i + 1]); i += 2 } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    fun serialize(d: PassportData): String {
        val m = linkedMapOf<String, String>()
        m["ts"] = d.timestampMs.toString(); m["profile"] = d.profileName
        d.vin?.let { m["vin"] = it }; d.vinError?.let { m["vinErr"] = it }
        m["cal"] = d.calIds.joinToString(SEP); d.calError?.let { m["calErr"] = it }
        m["cvn"] = d.cvns.joinToString(SEP); d.cvnError?.let { m["cvnErr"] = it }
        m["ecu"] = d.ecuNames.joinToString(SEP); d.ecuError?.let { m["ecuErr"] = it }
        d.pids01?.let { m["p01"] = it.joinToString(",") }; d.pids01Error?.let { m["p01Err"] = it }
        d.pids09?.let { m["p09"] = it.joinToString(",") }
        d.readiness?.let { r ->
            m["rdy"] = "${if (r.milOn) 1 else 0};${r.dtcCount};${if (r.compressionIgnition) 1 else 0};" +
                r.monitors.joinToString(SEP) { "${it.nameRu}=${it.status.name}" }
        }
        d.readinessError?.let { m["rdyErr"] = it }
        return m.entries.joinToString("\n") { "${it.key}=${esc(it.value)}" }
    }

    fun deserialize(s: String?): PassportData? {
        if (s.isNullOrBlank()) return null
        return try {
            val m = s.lines().filter { it.contains('=') }.associate {
                it.substringBefore('=') to unesc(it.substringAfter('='))
            }
            fun l(k: String) = m[k]?.takeIf { it.isNotEmpty() }?.split(SEP) ?: emptyList()
            fun ints(k: String) = m[k]?.let { v -> if (v.isEmpty()) emptyList() else v.split(",").map { it.toInt() } }
            val rdy = m["rdy"]?.let { v ->
                val parts = v.split(";", limit = 4)
                Readiness(parts[0] == "1", parts[1].toInt(), parts[2] == "1",
                    parts[3].split(SEP).filter { it.contains('=') }.map {
                        Monitor(it.substringBeforeLast('='), MonitorStatus.valueOf(it.substringAfterLast('=')))
                    })
            }
            PassportData(
                m["ts"]!!.toLong(), m["profile"] ?: "", m["vin"], m["vinErr"],
                l("cal"), m["calErr"], l("cvn"), m["cvnErr"], l("ecu"), m["ecuErr"],
                ints("p01"), m["p01Err"], ints("p09"), rdy, m["rdyErr"]
            )
        } catch (_: Exception) { null }
    }
}

/** Runs the passport requests through a caller-supplied (mutex-serialized) send function. */
object PassportReader {
    /** [send] = Elm327Client.send; bus must already be prepared (ATSH7DF / ATAR). */
    suspend fun read(
        profileName: String,
        nowMs: Long,
        send: suspend (cmd: String, timeoutMs: Long) -> String
    ): PassportData {
        suspend fun ask(cmd: String, t: Long = 5000): String =
            try { send(cmd, t) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "" }

        // Mode 09 support
        val p09 = Passport.parseSupportReply(ask("0900"), 0x49, 0x00)
        fun sup09(pid: Int) = p09?.contains(pid) ?: true // unknown support → just try

        // VIN
        var vin: String? = null; var vinErr: String? = null
        if (!sup09(0x02)) vinErr = Passport.NOT_SUPPORTED else {
            vin = Passport.parseVin(ask("0902"))
            if (vin == null) vinErr = if (p09 == null) Passport.NO_RESPONSE else Passport.NO_RESPONSE
        }
        var cal = emptyList<String>(); var calErr: String? = null
        if (!sup09(0x04)) calErr = Passport.NOT_SUPPORTED else {
            cal = Passport.parseCalIds(ask("0904")); if (cal.isEmpty()) calErr = Passport.NO_RESPONSE
        }
        var cvn = emptyList<String>(); var cvnErr: String? = null
        if (!sup09(0x06)) cvnErr = Passport.NOT_SUPPORTED else {
            cvn = Passport.parseCvns(ask("0906")); if (cvn.isEmpty()) cvnErr = Passport.NO_RESPONSE
        }
        var ecu = emptyList<String>(); var ecuErr: String? = null
        if (!sup09(0x0A)) ecuErr = Passport.NOT_SUPPORTED else {
            ecu = Passport.parseEcuNames(ask("090A")); if (ecu.isEmpty()) ecuErr = Passport.NO_RESPONSE
        }

        // Mode 01 supported PIDs: 00, then 20/40/60/… while bit 32 of the previous range is set
        var pids: MutableList<Int>? = null; var pidsErr: String? = null
        var base = 0x00
        while (base <= 0xC0) {
            val r = Passport.parseSupportReply(ask("01%02X".format(base)), 0x41, base)
            if (r == null) { if (base == 0) pidsErr = Passport.NO_RESPONSE; break }
            if (pids == null) pids = mutableListOf()
            pids.addAll(r)
            if (!r.contains(base + 0x20)) break
            base += 0x20
        }
        val pidList = pids?.distinct()?.sorted()

        val rdyRaw = ask("0101")
        val rdy = Passport.parseReadiness(rdyRaw)
        val rdyErr = if (rdy != null) null else if (pidList != null && !pidList.contains(1)) Passport.NOT_SUPPORTED else Passport.NO_RESPONSE

        return PassportData(nowMs, profileName, vin, vinErr, cal, calErr, cvn, cvnErr, ecu, ecuErr,
            pidList, pidsErr, p09, rdy, rdyErr)
    }
}
