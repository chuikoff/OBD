package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import kotlinx.coroutines.CancellationException

/**
 * Creta outside temperature. The engine ECU's SAE 0146 read +6 °C while the cluster showed +4 °C
 * (13.11.2026), so the cluster does not use it. EXPERIMENTAL source tried first, once per connection:
 * climate unit 7B3, UDS 22 0100, byte 6, X/2 − 40 (OBDb Hyundai Kona / Kia Seltos «TEMP_EXT»).
 * Accepted only if plausible next to 0146 (−12…+4 °C of it); otherwise 0146 stays the source.
 * Both go through the dashboard-like display filter in the controller.
 */
object CretaAmbient {
    const val TX = "7B3"
    const val RX = "7BB"
    const val REQUEST = "220100"
    const val INDEX = 6

    @Volatile var probed = false
        private set
    @Volatile var useClimate = false
        private set

    fun reset() { probed = false; useClimate = false }

    fun decodeClimate(raw: String): Double? {
        val m = Elm327Parser.assemble(raw).firstOrNull {
            it.bytes.size > 3 + INDEX && Elm327Parser.toUnsigned(it.bytes[0]) == 0x62 &&
                Elm327Parser.toUnsigned(it.bytes[1]) == 0x01 && Elm327Parser.toUnsigned(it.bytes[2]) == 0x00
        } ?: return null
        if (!m.positionsTrustworthy) return null
        val x = Elm327Parser.toUnsigned(m.bytes[3 + INDEX])
        if (x == 0xFF || x == 0x00) return null
        return (x / 2.0 - 40.0).takeIf { it in -40.0..60.0 }
    }

    fun plausible(climate: Double, ecu: Double?): Boolean = ecu == null || (climate - ecu) in -12.0..4.0

    internal fun mark(climateOk: Boolean) { probed = true; useClimate = climateOk }
}

class CretaAmbientSensor : SensorDefinition {
    override val id = "ambient_temp"
    override val displayNameRu = "Температура снаружи"
    override val unit = "°C"
    override val caveatRu = "Климат 7B3 22 0100 (эксп.) или ЭБУ 0146; фильтр как у приборки"

    override suspend fun read(client: Elm327Client): SensorReading = client.probing {
        val ecuRaw = try {
            runCatching { client.restoreEngineEcuFilter() }
            client.send("0146", timeoutMs = 1500)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
        val ecu = Elm327Parser.parseMode01Payload(ecuRaw, 0x46)?.takeIf { it.isNotEmpty() }
            ?.let { (Elm327Parser.toUnsigned(it[0]) - 40).toDouble() }?.takeIf { it in -40.0..60.0 }

        var climate: Double? = null
        var climateRaw: String? = null
        if (!CretaAmbient.probed || CretaAmbient.useClimate) {
            try {
                Elm327Client.ecuAddressing(CretaAmbient.TX, CretaAmbient.RX).forEach { runCatching { client.send(it, timeoutMs = 1500) } }
                climateRaw = client.send(CretaAmbient.REQUEST, timeoutMs = 2500)
                climate = CretaAmbient.decodeClimate(climateRaw)?.takeIf { CretaAmbient.plausible(it, ecu) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                runCatching { client.restoreEngineEcuFilter() }
            }
            if (!CretaAmbient.probed) {
                CretaAmbient.mark(climate != null)
                CvtDebugLog.add(CvtDebugEntry(System.currentTimeMillis(), "Снаружи 7B3 220100",
                    Elm327Parser.visibleRaw(climateRaw ?: ""), "проба климата", null,
                    "климат=${climate ?: "—"} · ЭБУ 0146=${ecu ?: "—"}", climate, if (climate == null) "климат не дал значения" else null))
            }
        }
        val value = climate ?: ecu
        SensorReading(
            sensorId = id, displayName = displayNameRu, value = value, unit = unit,
            rawHex = listOfNotNull(climateRaw?.let { Elm327Parser.clean(it) }, Elm327Parser.clean(ecuRaw)).joinToString(" | "),
            rawValue = value,
            note = if (climate != null) "Климат 7B3 22 0100 (эксп.) · ЭБУ 0146: ${ecu?.let { "%.0f °C".format(it) } ?: "—"}"
            else "ЭБУ SAE 0146 (приборка может показывать на 1–2° ниже)",
            error = if (value == null) "Нет данных (0146 и климат 7B3)" else null
        )
    }
}
