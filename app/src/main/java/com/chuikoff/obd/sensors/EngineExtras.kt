package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import com.chuikoff.obd.passport.Passport
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** Pure math / parsing for the extra Mode 01 cards (unit-tested). */
object EngineMath {
    const val AFR_GASOLINE = 14.7
    const val GASOLINE_DENSITY_G_PER_L = 745.0
    /** Below this speed fuel is shown in L/h (idle / creeping), above in L/100 km. */
    const val MOVING_KMH = 5

    fun mafGs(a: Int, b: Int): Double = (a * 256 + b) / 100.0

    /** Assumed volumetric efficiency for the MAP-based estimate (no MAF sensor, e.g. Creta 1.6 MPI). */
    const val VE = 0.85

    /** Speed-density air mass, g/s: rpm/120 · Vd · VE · MAP / (R · T). */
    fun speedDensityGs(rpm: Int, mapKpa: Int, iatC: Int, displacementL: Double, ve: Double = VE): Double =
        rpm / 120.0 * displacementL / 1000.0 * ve * mapKpa * 1000.0 / (287.05 * (iatC + 273.15)) * 1000.0
    fun rpm(a: Int, b: Int): Int = (a * 256 + b) / 4
    fun moduleVoltage(a: Int, b: Int): Double = (a * 256 + b) / 1000.0

    fun litersPerHour(mafGs: Double): Double = mafGs * 3600.0 / (AFR_GASOLINE * GASOLINE_DENSITY_G_PER_L)

    /** L/100 km, or null while standing (then show L/h). */
    fun litersPer100km(lph: Double, kmh: Int?): Double? =
        if (kmh == null || kmh < MOVING_KMH) null else lph * 100.0 / kmh

    /** ELM `ATRV` → volts ("12.6V", "12,6 V", "14.1V\r>"). */
    fun parseAtrv(raw: String): Double? =
        Regex("""(\d{1,2}[.,]\d)\s*V""", RegexOption.IGNORE_CASE).find(raw)
            ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
            ?.takeIf { it in 5.0..20.0 }

    /** Supported PIDs (Mode 01) from replies to 0100 / 0120 / 0140; null if 0100 got no answer. */
    fun supportedPids(replies: List<String>): Set<Int>? {
        var out: MutableSet<Int>? = null
        var base = 0
        for (raw in replies) {
            val r = Passport.parseSupportReply(raw, 0x41, base) ?: break
            if (out == null) out = mutableSetOf()
            out.addAll(r)
            if (!r.contains(base + 0x20)) break
            base += 0x20
        }
        return out
    }
}

/**
 * Trip average fuel consumption: integrates L/h and km/h over real time between successive valid
 * fuel readings. Gaps longer than [maxGapMs] (disconnect, paused polling) and intervals with unknown
 * speed are skipped, so they add neither liters nor km. Idle (0 km/h) adds liters only — correct for
 * an average. The average is shown from [MIN_KM] on.
 */
class FuelTrip(private val maxGapMs: Long = 10_000) {
    var liters = 0.0
        private set
    var km = 0.0
        private set
    private var lastMs: Long? = null

    @Synchronized fun add(nowMs: Long, lph: Double, kmh: Int?) {
        val prev = lastMs
        lastMs = nowMs
        if (prev == null || kmh == null) return
        val dt = nowMs - prev
        if (dt <= 0 || dt > maxGapMs) return
        val h = dt / 3_600_000.0
        liters += lph * h
        km += kmh * h
    }

    /** L/100 km, or null until [MIN_KM] driven. */
    val averagePer100: Double? get() = if (km >= MIN_KM) liters / km * 100.0 else null

    @Synchronized fun reset() { liters = 0.0; km = 0.0; lastMs = null }

    companion object { const val MIN_KM = 1.0 }
}

/** Battery / module voltage colors. */
object VoltageScale {
    const val RED = 0xFFE53935
    const val ORANGE = 0xFFFB8C00
    const val GREEN = 0xFF43A047
    const val LEGEND = "<12.0 В красный · 12.0–13.2 оранжевый при работающем моторе (нет зарядки) · " +
        "13.5–14.8 зелёный (зарядка) · >15 красный"

    /** ARGB, or null = neutral. [engineRunning] null = unknown. */
    fun argb(volts: Double?, engineRunning: Boolean?): Long? {
        if (volts == null || volts.isNaN()) return null
        val v = Math.round(volts * 10) / 10.0 // as displayed
        return when {
            v < 12.0 -> RED
            v > 15.0 -> RED
            v in 13.5..14.8 -> GREEN
            v <= 13.2 && engineRunning == true -> ORANGE
            else -> null
        }
    }
}

/**
 * Extra engine cards (voltage, RPM, fuel) — shared state for one connection: which PIDs the
 * engine ECU supports, the last RPM (engine running?) and speed. Only supported PIDs are shown;
 * a PID that answers NO DATA [MAX_NO_DATA] times in a row is hidden until the next connection.
 */
class EngineExtras {
    @Volatile var supported: Set<Int>? = null
        private set
    private val hidden = mutableSetOf<String>()
    private val noData = mutableMapOf<String, Int>()
    private var lastRpm: Int? = null
    private var lastRpmMs = 0L
    private var lastKmh: Int? = null
    private var lastKmhMs = 0L

    /** Engine displacement for the MAP estimate when there is no MAF (set per profile). */
    @Volatile var displacementL = 1.6

    val trip = FuelTrip()

    /** Creta: tank level from the cluster (7C6 22 B002, byte 4 /2 = liters; OBDb Kona/Seltos/Elantra,
     *  Creta 13.11.2026: 0x43 → 33.5 л next to odometer 83 991 km in the same reply). Read every 30 s. */
    @Volatile var clusterTank = false
    @Volatile var tankLiters: Double? = null
        private set
    private var tankMs = 0L

    fun reset(supported: Set<Int>?) {
        this.supported = supported
        hidden.clear(); noData.clear()
        lastRpm = null; lastKmh = null
        trip.reset()
        tankLiters = null; tankMs = 0L
    }

    private fun hasMaf(s: Set<Int>?) = s?.contains(0x10) == true
    private fun hasMap(s: Set<Int>?) = s != null && 0x0B in s && 0x0C in s && 0x0F in s

    fun engineRunning(now: Long = System.currentTimeMillis()): Boolean? =
        lastRpm?.takeIf { now - lastRpmMs < 20_000 }?.let { it > 400 }

    /** Kmh from the fuel card's own 010D, or [external] (ambient filter speed reader). */
    fun speed(now: Long = System.currentTimeMillis()): Int? = lastKmh?.takeIf { now - lastKmhMs < 10_000 }

    private val voltage = Extra(ID_VOLTAGE, "Напряжение", "В", pid = null) { c -> readVoltage(c) }
    private val rpm = Extra(ID_RPM, "Обороты", "об/мин", pid = 0x0C) { c -> readRpm(c) }
    private val fuel = Extra(ID_FUEL, "Средний расход (поездка)", "л/100км", pid = 0x10) { c -> readFuel(c) }
    private val all = listOf(voltage, rpm, fuel)

    /** Sensors to poll now (supported and not hidden), in card order. */
    fun sensors(): List<SensorDefinition> {
        val s = supported ?: return listOf(voltage).filter { it.id !in hidden } // ATRV works without PID list
        return all.filter {
            (it.pid == null || it.pid in s || (it.id == ID_FUEL && hasMap(s))) && it.id !in hidden
        }
    }

    private inner class Extra(
        override val id: String,
        override val displayNameRu: String,
        override val unit: String,
        val pid: Int?,
        val block: suspend (Elm327Client) -> SensorReading
    ) : SensorDefinition {
        override suspend fun read(client: Elm327Client): SensorReading = try {
            runCatching { client.restoreEngineEcuFilter() }
            block(client).also { r ->
                if (r.value == null) {
                    val n = (noData[id] ?: 0) + 1
                    noData[id] = n
                    if (n >= MAX_NO_DATA) hidden += id
                } else noData[id] = 0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reading(this, null, null, null, error = e.message ?: "Ошибка чтения")
        }
    }

    private fun reading(
        s: SensorDefinition, value: Double?, text: String?, raw: String?,
        argb: Long? = null, detail: String? = null, error: String? = null, note: String? = null
    ) = SensorReading(
        sensorId = s.id, displayName = s.displayNameRu, value = value, unit = s.unit,
        rawHex = raw?.let { Elm327Parser.clean(it) }, note = note, detail = detail,
        error = error ?: if (value == null) "Нет данных" else null,
        displayText = text, valueArgb = argb
    )

    private fun bytes(raw: String, pid: Int, n: Int): IntArray? {
        val p = Elm327Parser.parseMode01Payload(raw, pid) ?: return null
        if (p.size < n) return null
        return IntArray(n) { Elm327Parser.toUnsigned(p[it]) }
    }

    private suspend fun readVoltage(c: Elm327Client): SensorReading {
        val viaPid = supported?.contains(0x42) == true
        val raw = c.send(if (viaPid) "0142" else "ATRV", timeoutMs = 1500)
        val v = if (viaPid) bytes(raw, 0x42, 2)?.let { EngineMath.moduleVoltage(it[0], it[1]) }
        else EngineMath.parseAtrv(raw)
        val running = engineRunning()
        return reading(
            voltage, v, v?.let { String.format(Locale.US, "%.1f В", it) }, raw,
            argb = VoltageScale.argb(v, running),
            note = if (viaPid) "SAE 0142 (напряжение модуля ЭБУ)" else "ATRV (напряжение на OBD-разъёме, замер адаптера)",
            detail = "Мотор: " + when (running) { true -> "работает"; false -> "не работает"; null -> "нет данных об оборотах" } +
                "\nШкала: " + VoltageScale.LEGEND
        )
    }

    private suspend fun readRpm(c: Elm327Client): SensorReading {
        val raw = c.send("010C", timeoutMs = 1500)
        val r = bytes(raw, 0x0C, 2)?.let { EngineMath.rpm(it[0], it[1]) }
        if (r != null) { lastRpm = r; lastRpmMs = System.currentTimeMillis() }
        return reading(rpm, r?.toDouble(), r?.let { "$it об/мин" }, raw, note = "SAE 010C")
    }

    private suspend fun readFuel(c: Elm327Client): SensorReading {
        val viaMaf = hasMaf(supported) || !hasMap(supported)
        val raws = mutableListOf<String>()
        var maf: Double? = null
        var src = ""
        if (viaMaf) {
            val raw = c.send("0110", timeoutMs = 1500); raws += raw
            maf = bytes(raw, 0x10, 2)?.let { EngineMath.mafGs(it[0], it[1]) }
            src = "MAF 0110"
        } else {
            val rMap = c.send("010B", timeoutMs = 1500); raws += rMap
            val rIat = c.send("010F", timeoutMs = 1500); raws += rIat
            val map = bytes(rMap, 0x0B, 1)?.get(0)
            val iat = bytes(rIat, 0x0F, 1)?.get(0)?.minus(40)
            val now = System.currentTimeMillis()
            var r = lastRpm?.takeIf { now - lastRpmMs < 3_000 }
            if (r == null) {
                val rr = c.send("010C", timeoutMs = 1500); raws += rr
                r = bytes(rr, 0x0C, 2)?.let { EngineMath.rpm(it[0], it[1]) }
                if (r != null) { lastRpm = r; lastRpmMs = now }
            }
            if (map != null && iat != null && r != null) maf = EngineMath.speedDensityGs(r, map, iat, displacementL)
            src = String.format(Locale.US, "оценка по MAP/IAT/RPM (ДАД 010B=%s кПа, IAT=%s °C, VE %.2f, %.1f л)",
                map ?: "—", iat ?: "—", EngineMath.VE, displacementL)
        }
        if (maf != null && supported?.contains(0x0D) != false) {
            val rs = runCatching { c.send("010D", timeoutMs = 1500) }.getOrNull()
            if (rs != null) {
                raws += rs
                VehicleSpeedReader.parse(rs)?.let { lastKmh = it; lastKmhMs = System.currentTimeMillis() }
            }
        }
        val rawAll = raws.joinToString(" | ")
        if (maf == null) return reading(fuel, null, null, rawAll, note = src)
        val lph = EngineMath.litersPerHour(maf)
        val kmh = speed()
        trip.add(System.currentTimeMillis(), lph, kmh)
        val per100 = EngineMath.litersPer100km(lph, kmh)
        val instant = if (per100 != null) String.format(Locale.US, "%.1f л/100км", per100)
        else String.format(Locale.US, "%.1f л/ч", lph)
        if (clusterTank && System.currentTimeMillis() - tankMs > TANK_EVERY_MS) readTank(c)
        val tank = tankLiters?.let { String.format(Locale.US, " · в баке %.1f л", it) } ?: ""
        val avg = trip.averagePer100
        val text = avg?.let { String.format(Locale.US, "%.1f л/100км", it) } ?: "копится…"
        val status = if (avg != null)
            String.format(Locale.US, "сейчас %s · %.1f км · %.2f л", instant, trip.km, trip.liters) + tank
        else String.format(Locale.US, "средний после 1 км: пройдено %.1f км · сейчас %s", trip.km, instant) + tank
        return reading(
            fuel, avg ?: (per100 ?: lph), text, rawAll,
            note = "Средний за поездку (с подключения): литры ÷ км. Литры по $src: бензин, AFR 14.7, 0.745 кг/л — ориентировочно" +
                (if (clusterTank) ". В баке — приборка 7C6 22 B002" else ""),
            detail = String.format(Locale.US, "Поездка: %.2f км, %.3f л · воздух %.2f г/с · %.2f л/ч · скорость %s",
                trip.km, trip.liters, maf, lph, kmh?.let { "$it км/ч" } ?: "—")
        ).copy(statusLine = status)
    }

    private suspend fun readTank(c: Elm327Client) {
        tankMs = System.currentTimeMillis()
        try {
            c.probing {
                Elm327Client.ecuAddressing("7C6", "7CE").forEach { runCatching { c.send(it, timeoutMs = 1500) } }
                tankLiters = parseClusterTank(c.send("22B002", timeoutMs = 2500)) ?: tankLiters
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            runCatching { c.restoreEngineEcuFilter() }
        }
    }

    companion object {
        const val TANK_EVERY_MS = 30_000L

        /** 62 B0 02 | b0 b1 b2 b3 FUEL(/2 л) … ; FF = no value. */
        fun parseClusterTank(raw: String): Double? {
            val m = Elm327Parser.assemble(raw).firstOrNull {
                it.bytes.size > 7 && Elm327Parser.toUnsigned(it.bytes[0]) == 0x62 &&
                    Elm327Parser.toUnsigned(it.bytes[1]) == 0xB0 && Elm327Parser.toUnsigned(it.bytes[2]) == 0x02
            } ?: return null
            if (!m.positionsTrustworthy) return null
            val x = Elm327Parser.toUnsigned(m.bytes[7])
            return if (x == 0xFF) null else x / 2.0
        }

        const val ID_VOLTAGE = "voltage"
        const val ID_RPM = "rpm"
        const val ID_FUEL = "fuel_rate"
        const val MAX_NO_DATA = 3
        val IDS = listOf(ID_VOLTAGE, ID_RPM, ID_FUEL)
    }
}
