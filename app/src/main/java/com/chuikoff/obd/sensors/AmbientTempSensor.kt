package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser

/**
 * SAE standard Mode 01 PID 46 — Ambient Air Temperature.
 * Formula: A − 40 (°C). Russian label: «Температура снаружи».
 */
class AmbientTempSensor : SensorDefinition {
    override val id: String = "ambient_temp"
    override val displayNameRu: String = "Температура снаружи"
    override val unit: String = "°C"

    override suspend fun read(client: Elm327Client): SensorReading {
        return try {
            val raw = client.send("0146")
            val payload = Elm327Parser.parseMode01Payload(raw, 0x46)
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
