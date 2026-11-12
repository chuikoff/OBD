package com.chuikoff.obd.vehicle

import kotlinx.coroutines.CancellationException
import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import com.chuikoff.obd.sensors.CoolantTempSensor
import com.chuikoff.obd.sensors.Mode01TempSensor
import com.chuikoff.obd.sensors.CvtTempSensor
import com.chuikoff.obd.sensors.SensorDefinition
import com.chuikoff.obd.sensors.SensorReading

/**
 * Hyundai Creta (2018). Many 2018 Creta use 6AT (A6GF1), not CVT.
 *
 * Coolant: SAE 0105.
 * Gearbox oil (community — ScanGauge Hyundai/Kia CANSF first):
 *  1) Mode 21A0, header 7E1 (TXD 07E121A0), °C = A − 40
 *  2) Mode 21A0, header 7E0 (alt TXD 07E021A0)
 *  3) Last resort Mode 22 DID 01A0 — still reject 0xFF/0xFE filler
 * Ambient: SAE 0146 only (may differ from cluster; no intake 010F as outdoor).
 */
class Creta2018Profile : VehicleProfile {

    override val id: String = "hyundai_creta_2018"
    override val displayNameRu: String = "Hyundai Creta (2018)"
    override val years: String = "2018"
    override val notesRu: String =
        "ОЖ: PID 05. Снаружи: SAE 0146 (может отличаться от приборки; TODO: cluster PID). " +
            "Коробка: ScanGauge 21A0@7E1 → 21A0@7E0 → Mode22 01A0; 0xFF/0xFE отвергаются; −20…150 °C."

    companion object {
        private val TCM_PRE_7E1 = Elm327Client.ecuAddressing("7E1", "7E9")
        private val TCM_PRE_7E0 = Elm327Client.ENGINE_ADDRESSING
        private val TCM_POST = Elm327Client.ENGINE_ADDRESSING

        /** Reject ELM/null filler bytes; sanity −20…150 °C; formula A−40. */
        fun decodeGearboxTempAMinus40(bytes: ByteArray): Double? {
            if (bytes.isEmpty()) return null
            val a = Elm327Parser.toUnsigned(bytes[0])
            if (a == 0xFF || a == 0xFE) return null
            val c = (a - 40).toDouble()
            if (c !in -20.0..150.0) return null
            return c
        }

        /** Primary ScanGauge Hyundai/Kia CANSF: Mode 21A0 @ 7E1, A−40. */
        val MODE21_A0_7E1 = CvtTempCommand(
            request = "21A0",
            pidOrDid = "A0",
            parseMode = CvtTempCommand.ParseMode.MODE21,
            preCommands = TCM_PRE_7E1,
            postCommands = TCM_POST,
            caveatRu = "ScanGauge CANSF TXD 07E121A0 / MTH≈A−40. Community, не официальный Hyundai docs.",
            decode = { decodeGearboxTempAMinus40(it) }
        )

        /** Alt header 7E0: TXD 07E021A0. */
        val MODE21_A0_7E0 = CvtTempCommand(
            request = "21A0",
            pidOrDid = "A0",
            parseMode = CvtTempCommand.ParseMode.MODE21,
            preCommands = TCM_PRE_7E0,
            postCommands = TCM_POST,
            caveatRu = "Alt header 7E0 TXD 07E021A0 / A−40. Community.",
            decode = { decodeGearboxTempAMinus40(it) }
        )

        /** Last resort: Mode 22 DID 01A0 — still reject FF/FE. */
        val MODE22_01A0 = CvtTempCommand(
            request = "2201A0",
            pidOrDid = "01A0",
            parseMode = CvtTempCommand.ParseMode.MODE22,
            preCommands = TCM_PRE_7E1,
            postCommands = TCM_POST,
            caveatRu = "⚠ Last-resort Mode22 01A0 (cintakc). 0xFF/0xFE = null, не температура.",
            decode = { decodeGearboxTempAMinus40(it) }
        )
    }


    override fun sensors(): List<SensorDefinition> = listOf(
        CoolantTempSensor(),
        CretaGearboxTempSensor(),
        CretaAmbientTempSensor()
    )
}

/**
 * Order: 21A0@7E1 → 21A0@7E0 → Mode22 01A0. Note which source worked.
 */
private class CretaGearboxTempSensor : SensorDefinition {
    override val id: String = "cvt_temp"
    override val displayNameRu: String = "Температура коробки"
    override val unit: String = "°C"
    override val caveatRu: String =
        "ScanGauge 21A0@7E1 → 21A0@7E0 → Mode22 01A0; FF/FE reject; часто 6AT A6GF1"

    private val try7e1 = CvtTempSensor(
        commandProvider = { Creta2018Profile.MODE21_A0_7E1 },
        displayNameRu = "Температура коробки",
        minMode21DataBytes = 1,
        retryTimeoutMs = 6000
    )
    private val try7e0 = CvtTempSensor(
        commandProvider = { Creta2018Profile.MODE21_A0_7E0 },
        displayNameRu = "Температура коробки",
        minMode21DataBytes = 1,
        retryTimeoutMs = 6000
    )
    private val tryMode22 = CvtTempSensor(
        commandProvider = { Creta2018Profile.MODE22_01A0 },
        displayNameRu = "Температура коробки",
        minMode21DataBytes = 1,
        retryTimeoutMs = 6000
    )

    override suspend fun read(client: Elm327Client): SensorReading {
        val r1 = try7e1.read(client)
        if (r1.value != null) {
            return r1.copy(note = "Источник: Mode21 A0 @7E1 (ScanGauge CANSF, A−40)")
        }
        val r2 = try7e0.read(client)
        if (r2.value != null) {
            return r2.copy(note = "Источник: Mode21 A0 @7E0 (alt, A−40)")
        }
        val r3 = tryMode22.read(client)
        if (r3.value != null) {
            // Note: A−38 sometimes used if twin of coolant — prefer A−40; mention only as note.
            return r3.copy(
                note = "Источник: Mode22 DID 01A0 (last resort, A−40; если похоже на ОЖ — см. A−38)"
            )
        }
        return SensorReading(
            sensorId = id,
            displayName = displayNameRu,
            value = null,
            unit = unit,
            rawHex = r3.rawHex ?: r2.rawHex ?: r1.rawHex,
            note = caveatRu,
            error = "Нет данных 21A0@7E1/7E0 / Mode22 01A0 (FF/FE отвергнуты) — raw для отладки"
        )
    }
}

/**
 * SAE 0146 only. Never show intake 010F as outdoor.
 * TODO: Creta cluster outdoor PID unknown — cannot invent one.
 */
private fun CretaAmbientTempSensor(): SensorDefinition {
    val caveat = "SAE 0146 (может отличаться от приборки). TODO: cluster outdoor PID."
    return Mode01TempSensor(
        id = "ambient_temp",
        displayNameRu = "Температура снаружи",
        pid = 0x46,
        noDataError = "Нет данных SAE 0146",
        validRange = -40.0..60.0,
        outOfRangeError = { c -> "0146 вне −40…60 (°C=$c)" },
        caveatRu = caveat,
        errorNote = caveat,
        okNote = "SAE 0146 (может отличаться от приборки). TODO: cluster PID"
    )
}
