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
}
