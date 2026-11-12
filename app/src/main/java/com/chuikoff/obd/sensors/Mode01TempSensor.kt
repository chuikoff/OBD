package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import kotlinx.coroutines.CancellationException

/**
 * SAE Mode 01 temperature PID, formula A − 40 (°C), read from the engine ECU.
 * [validRange]: values outside are shown as an error (null = accept everything).
 * [errorNote] goes on error cards, [okNote] on cards with a value.
 */
class Mode01TempSensor(
    override val id: String,
    override val displayNameRu: String,
    private val pid: Int,
    private val noDataError: String,
    private val validRange: ClosedFloatingPointRange<Double>? = null,
    private val outOfRangeError: (Double) -> String = { "вне диапазона (°C=$it)" },
    override val caveatRu: String? = null,
    private val errorNote: String? = null,
    private val okNote: String? = null
) : SensorDefinition {
    override val unit: String = "°C"
    private val request = "01%02X".format(pid)

    override suspend fun read(client: Elm327Client): SensorReading {
        fun reading(value: Double?, raw: String?, error: String?) = SensorReading(
            sensorId = id,
            displayName = displayNameRu,
            value = value,
            unit = unit,
            rawHex = raw?.let { Elm327Parser.clean(it) },
            note = if (error == null) okNote else errorNote,
            error = error
        )
        return try {
            // Ensure engine ECU filter after TCM / AWC / climate addressing
            runCatching { client.restoreEngineEcuFilter() }
            val raw = client.send(request)
            val payload = Elm327Parser.parseMode01Payload(raw, pid)
            if (payload == null || payload.isEmpty()) return reading(null, raw, noDataError)
            val c = (Elm327Parser.toUnsigned(payload[0]) - 40).toDouble()
            if (validRange != null && c !in validRange) reading(null, raw, outOfRangeError(c))
            else reading(c, raw, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reading(null, null, e.message ?: "Ошибка чтения")
        }
    }
}

/** SAE 0105 — engine coolant temperature. */
fun CoolantTempSensor() = Mode01TempSensor(
    id = "coolant_temp",
    displayNameRu = "Температура ОЖ",
    pid = 0x05,
    noDataError = "Нет данных (NO DATA / parse)"
)
