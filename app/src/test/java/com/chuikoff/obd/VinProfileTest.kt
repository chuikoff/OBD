package com.chuikoff.obd

import com.chuikoff.obd.vehicle.VehicleProfileRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VinProfileTest {
    @Test fun vinPicksProfile() {
        assertEquals("hyundai_creta_2018", VehicleProfileRegistry.forVin("Z94G2811BJR113546")?.id) // the user's Creta
        assertEquals("hyundai_creta_2018", VehicleProfileRegistry.forVin("KMHJ381ABJU000000")?.id)
        assertEquals("mitsubishi_outlander_gf_gg", VehicleProfileRegistry.forVin("JMBXTGF3WGZ000000")?.id)
        assertNull(VehicleProfileRegistry.forVin("WVWZZZ1KZ6W000000"))
        assertNull(VehicleProfileRegistry.forVin(null))
    }
}
