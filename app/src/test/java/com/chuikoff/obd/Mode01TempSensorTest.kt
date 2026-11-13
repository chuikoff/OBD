package com.chuikoff.obd

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.sensors.CoolantTempSensor
import com.chuikoff.obd.vehicle.Creta2018Profile
import com.chuikoff.obd.vehicle.OutlanderGfGgProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 1.13.1 dedup must keep the exact commands and card texts of 1.13.0. */
class Mode01TempSensorTest {
    private fun client(r0105: String, r0146: String) = Elm327Client(ScriptedElm { c ->
        when {
            c == "ATZ" -> "\r\rELM327 v1.5\r\r>"
            c.startsWith("AT") -> "OK\r\r>"
            c == "0105" -> r0105
            c == "0146" -> r0146
            else -> "NO DATA\r\r>"
        }
    })

    @Test fun engineAddressingIsTheOld5CommandList() {
        assertEquals(listOf("ATSH7E0", "ATFCSH7E0", "ATFCSD30080A", "ATFCSM1", "ATCRA7E8"), Elm327Client.ENGINE_ADDRESSING)
        val cvt = OutlanderGfGgProfile.OUT3_CVT
        assertEquals(listOf("ATAT1", "ATSH7E1", "ATFCSH7E1", "ATFCSD30080A", "ATFCSM1", "ATCRA7E9"), cvt.preCommands)
        assertEquals(Elm327Client.ENGINE_ADDRESSING, cvt.postCommands)
    }

    @Test fun coolant() = runBlocking {
        val c = client("41057D\r\r>", "NO DATA\r\r>")
        val ok = CoolantTempSensor().read(c)
        assertEquals("coolant_temp", ok.sensorId)
        assertEquals(85.0, ok.value!!, 0.0)
        assertNull(ok.error); assertNull(ok.note)
        val bad = CoolantTempSensor().read(client("NO DATA\r\r>", ""))
        assertEquals("Нет данных (NO DATA / parse)", bad.error)
    }

    @Test fun cretaAmbientFallsBackTo0146() = runBlocking {
        com.chuikoff.obd.sensors.CretaAmbient.reset()
        val amb = Creta2018Profile().sensors().first { it.id == "ambient_temp" }
        val ok = amb.read(client("", "41463C\r\r>"))
        assertEquals(20.0, ok.value!!, 0.0)
        assertEquals("ЭБУ SAE 0146 (приборка может показывать на 1–2° ниже)", ok.note)
        assertEquals(false, com.chuikoff.obd.sensors.CretaAmbient.useClimate)
        val hot = amb.read(client("", "4146FF\r\r>"))
        assertNull(hot.value)
        assertEquals("Нет данных (0146 и климат 7B3)", hot.error)
    }

    @Test fun cretaAmbientPrefersClimateWhenPlausible() = runBlocking {
        com.chuikoff.obd.sensors.CretaAmbient.reset()
        // real Creta 13.11.2026: 0146 = 6 °C, 7B3 21 01 byte 6 = 0x58 → 4 °C = dashboard
        val c = Elm327Client(ScriptedElm { cmd ->
            when {
                cmd.startsWith("AT") -> "OK\r\r>"
                cmd == "0146" -> "41462E\r\r>"
                cmd == "2101" -> "025\r0:61017E100068\r1:FF8458570AE48C\r2:FFFFFFFF1CFFFF\r3:FFFFFFFFFFFFFF\r4:FFFFFFFF0097FF\r5:01FFFF00000000\r\r>"
                else -> "NO DATA\r\r>"
            }
        })
        val r = Creta2018Profile().sensors().first { it.id == "ambient_temp" }.read(c)
        assertEquals(4.0, r.value!!, 0.0)
        assertEquals(true, com.chuikoff.obd.sensors.CretaAmbient.useClimate)
        // implausible (+30 vs 0146) → rejected
        assertNull(com.chuikoff.obd.sensors.CretaAmbient.decodeClimate("61017E100068\r\r>"))
        assertEquals(false, com.chuikoff.obd.sensors.CretaAmbient.plausible(30.0, 6.0))
        com.chuikoff.obd.sensors.CretaAmbient.reset()
    }
}
