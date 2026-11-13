package com.chuikoff.obd.vehicle

import com.chuikoff.obd.sensors.CoolantTempSensor
import com.chuikoff.obd.sensors.CretaAtf
import com.chuikoff.obd.sensors.CretaAtfSensor
import com.chuikoff.obd.sensors.Mode01TempSensor
import com.chuikoff.obd.sensors.SensorDefinition

/**
 * Hyundai Creta (2018), 1.6 Gamma MPI + 6AT A6GF1.
 *
 * Coolant: SAE 0105.
 * Gearbox (ATF) temperature: [CretaAtfSensor] — EXPERIMENTAL, ScanGauge candidates probed once
 *  per connection (21A0@7E1 byte 13 / 33, 22 2081@7E1, 21A0@7E0), RAW of every probe in the CVT log.
 *  Hidden when the TCM does not answer at all.
 * Ambient: SAE 0146 only (may differ from cluster; no intake 010F as outdoor).
 */
class Creta2018Profile : VehicleProfile {

    override val id: String = "hyundai_creta_2018"
    override val displayNameRu: String = "Hyundai Creta (2018)"
    override val years: String = "2018"
    override val notesRu: String =
        "ОЖ: PID 05. Снаружи: SAE 0146 (может отличаться от приборки). " +
            "АКПП A6GF1 (экспериментально): 21A0@7E1 (ScanGauge, X−40), запасные 22 2081 и 21A0@7E0."

    override fun sensors(): List<SensorDefinition> = listOfNotNull(
        CoolantTempSensor(),
        CretaAtfSensor().takeIf { !CretaAtf.absent },
        CretaAmbientTempSensor()
    )
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
