package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser

/**
 * Outlander outdoor / ambient temperature — AWC cluster only.
 *
 * AWC CAN (dashboard outdoor, OUT-CLUB Out3):
 *  Pre: ATSH7B6, ATFCSH7B6, ATFCSD30080A, ATFCSM1, ATCRA7B7
 *  Request: 210D → Mode 21 PID 0x0D
 *  Decode (1.8.0): strictly Torque letter I = data byte index 8: (I/2)−40.
 *    The old fallback indices 5/6/7/4/9 were workarounds for the pre-1.7.0 parser bug that
 *    shifted bytes; with line-by-line ISO-TP assembly they only risked picking a wrong byte.
 *    The frame must be reassembled without gaps and carry ≥ 9 data bytes.
 *  Post: restore engine ATSH7E0 / ATCRA7E8 …
 *
 * Byte 8 is the *raw* bumper sensor (heat-soaks at idle). What the dashboard shows is produced
 * by [AmbientDisplayFilter], applied in the ViewModel; this class returns the raw value.
 */
class OutlanderAmbientTempSensor : SensorDefinition {
    override val id: String = "ambient_temp"
    override val displayNameRu: String = "Температура снаружи"
    override val unit: String = "°C"
    override val caveatRu: String = "AWC 210D — датчик с приборной панели"

    companion object {
        private val AWC_PRE = listOf(
            "ATSH7B6",
            "ATFCSH7B6",
            "ATFCSD30080A",
            "ATFCSM1",
            "ATCRA7B7"
        )
        private val ENGINE_POST = listOf(
            "ATSH7E0",
            "ATFCSH7E0",
            "ATFCSD30080A",
            "ATFCSM1",
            "ATCRA7E8"
        )

        /** Torque letter I (0-based data index after `61 0D`). */
        const val AMBIENT_BYTE_INDEX = 8
        /** Need at least bytes 0..8 at their true offsets. */
        const val MIN_DATA_BYTES = AMBIENT_BYTE_INDEX + 1

        const val SOURCE_NOTE = "AWC 210D (приборка), фильтр как у панели"

        /** Physically possible for a bumper sensor (incl. heat-soak on hot asphalt). */
        fun isPlausible(c: Double): Boolean = c >= -39.5 && c <= 70.0

        /** °C from a reassembled 210D payload, or null (short frame / 00 / FF / implausible). */
        fun decodeOutdoor(payload: ByteArray): Double? {
            if (payload.size < MIN_DATA_BYTES) return null
            val v = Elm327Parser.toUnsigned(payload[AMBIENT_BYTE_INDEX])
            if (v == 0x00 || v == 0xFF) return null
            val celsius = v / 2.0 - 40.0
            return if (isPlausible(celsius)) celsius else null
        }

        /** Full raw ELM text → °C (null if frame invalid). */
        fun decodeRaw(raw: String): Double? =
            Elm327Parser.parseMode21PayloadHardened(raw, 0x0D, minDataBytes = MIN_DATA_BYTES)
                ?.let { decodeOutdoor(it) }
    }

    override suspend fun read(client: Elm327Client): SensorReading {
        runCatching { client.restoreEngineEcuFilter() }
        return tryAwc(client)
    }

    private suspend fun tryAwc(client: Elm327Client): SensorReading {
        var raw: String? = null
        return try {
            for (pre in AWC_PRE) {
                client.send(pre, timeoutMs = 2000)
            }
            raw = client.send("210D", timeoutMs = 4000)
            val cleaned = Elm327Parser.clean(raw)
            val payload = Elm327Parser.parseMode21PayloadHardened(raw, 0x0D, minDataBytes = MIN_DATA_BYTES)
            val decoded = payload?.let { decodeOutdoor(it) }
            if (decoded != null) {
                SensorReading(
                    sensorId = id,
                    displayName = displayNameRu,
                    value = decoded,
                    unit = unit,
                    rawHex = cleaned,
                    note = SOURCE_NOTE,
                    rawValue = decoded
                )
            } else {
                SensorReading(
                    sensorId = id,
                    displayName = displayNameRu,
                    value = null,
                    unit = unit,
                    rawHex = cleaned,
                    note = SOURCE_NOTE,
                    error = if (payload == null) {
                        "AWC 210D: кадр неполный (<$MIN_DATA_BYTES байт данных) или с дырой"
                    } else {
                        "AWC 210D: байт 8 = 0x%02X вне диапазона".format(
                            Elm327Parser.toUnsigned(payload[AMBIENT_BYTE_INDEX])
                        )
                    }
                )
            }
        } catch (e: Exception) {
            SensorReading(
                sensorId = id,
                displayName = displayNameRu,
                value = null,
                unit = unit,
                rawHex = raw?.let { Elm327Parser.clean(it) },
                note = SOURCE_NOTE,
                error = e.message ?: "Ошибка чтения AWC 210D"
            )
        } finally {
            for (post in ENGINE_POST) {
                runCatching { client.send(post, timeoutMs = 2000) }
            }
        }
    }
}
