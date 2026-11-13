package com.chuikoff.obd.vehicle

/**
 * Central registry of supported vehicle profiles.
 * To add a model: implement [VehicleProfile], then add an instance here.
 */
object VehicleProfileRegistry {
    private val profiles: List<VehicleProfile> = listOf(
        OutlanderGfGgProfile(),
        Creta2018Profile()
    )

    val default: VehicleProfile
        get() = profiles.first { it.id == "mitsubishi_outlander_gf_gg" }

    fun all(): List<VehicleProfile> = profiles

    fun byId(id: String): VehicleProfile? = profiles.find { it.id == id }

    private val HYUNDAI_KIA_WMI = setOf(
        "KMH", "KM8", "KMF", "KNA", "KNC", "KND", "KNE", "Z94", "XWE", "TMA", "MAL", "NLH", "5NP", "5XY", "U5Y", "U6Y"
    )
    private val MITSUBISHI_WMI = setOf("JA3", "JA4", "JMB", "JMY", "JMA", "JMP", "4A3", "4A4", "Z8T", "MMB", "MMC")

    /** Profile matching the VIN's manufacturer code (first 3 chars), or null if unknown. */
    fun forVin(vin: String?): VehicleProfile? {
        val wmi = vin?.trim()?.uppercase()?.take(3)?.takeIf { it.length == 3 } ?: return null
        return when (wmi) {
            in HYUNDAI_KIA_WMI -> byId("hyundai_creta_2018")
            in MITSUBISHI_WMI -> byId("mitsubishi_outlander_gf_gg")
            else -> null
        }
    }
}
