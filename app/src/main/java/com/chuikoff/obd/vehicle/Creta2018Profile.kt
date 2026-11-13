package com.chuikoff.obd.vehicle

import com.chuikoff.obd.sensors.CoolantTempSensor
import com.chuikoff.obd.sensors.CretaAtfSensor
import com.chuikoff.obd.sensors.CretaAmbientSensor
import com.chuikoff.obd.sensors.SensorDefinition

/**
 * Hyundai Creta (2018), 1.6 Gamma MPI + 6AT A6GF1.
 *
 * Coolant: SAE 0105.
 * Gearbox (ATF) temperature: [CretaAtfSensor] — EXPERIMENTAL, ScanGauge candidates probed once
 *  per connection (21A0@7E1 byte 13 / 33, 22 2081@7E1, 21A0@7E0), RAW of every probe in the CVT log.
 *  Always shown (with an error text when nothing was found).
 * Ambient: climate 7B3 22 0100 (experimental) → fallback SAE 0146, dashboard-like filter.
 */
class Creta2018Profile : VehicleProfile {

    override val id: String = "hyundai_creta_2018"
    override val displayNameRu: String = "Hyundai Creta (2018)"
    override val years: String = "2018"
    override val notesRu: String =
        "ОЖ: PID 05. Снаружи: климат 7B3 (эксп.) или SAE 0146, фильтр как у приборки. " +
            "АКПП A6GF1 (экспериментально): 21A0@7E1 (ScanGauge, X−40), запасные 22 2081 и 21A0@7E0."

    override fun sensors(): List<SensorDefinition> = listOfNotNull(
        CoolantTempSensor(),
        CretaAtfSensor(), // always shown on the Creta (6AT): an error text beats a missing card
        CretaAmbientSensor()
    )
}

