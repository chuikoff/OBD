package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser

/**
 * SAE standard Mode 01 PID 05 — Engine Coolant Temperature.
 * Formula: A - 40 (°C)
 */
class CoolantTempSensor : SensorDefinition {
    override val id: String = "coolant_temp"
    override val displayNameRu: String = "Температура ОЖ"
    override val unit: String = "°C"

    override suspend fun read(client: Elm327Client): SensorReading {
        return try {
            // Ensure engine ECU filter after TCM / AWC / climate addressing
            runCatching { client.restoreEngineEcuFilter() }
            val raw = client.send("0105")
            val payload = Elm327Parser.parseMode01Payload(raw, 0x05)
            if (payload == null || payload.isEmpty()) {
                SensorReading(
                    sensorId = id,
                    displayName = displayNameRu,
                    value = null,
                    unit = unit,
                    rawHex = Elm327Parser.clean(raw),
                    error = "Нет данных (NO DATA / parse)"
                )
            } else {
                val a = Elm327Parser.toUnsigned(payload[0])
                val celsius = (a - 40).toDouble()
                SensorReading(
                    sensorId = id,
                    displayName = displayNameRu,
                    value = celsius,
                    unit = unit,
                    rawHex = Elm327Parser.clean(raw)
                )
            }
        } catch (e: Exception) {
            SensorReading(
                sensorId = id,
                displayName = displayNameRu,
                value = null,
                unit = unit,
                error = e.message ?: "Ошибка чтения"
            )
        }
    }
}
