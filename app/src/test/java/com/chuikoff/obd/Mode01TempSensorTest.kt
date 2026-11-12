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

    @Test fun cretaAmbientRangeAndNotes() = runBlocking {
        val amb = Creta2018Profile().sensors().first { it.id == "ambient_temp" }
        val ok = amb.read(client("", "41463C\r\r>"))
        assertEquals(20.0, ok.value!!, 0.0)
        assertEquals("SAE 0146 (может отличаться от приборки). TODO: cluster PID", ok.note)
        val hot = amb.read(client("", "4146FF\r\r>"))
        assertNull(hot.value)
        assertEquals("0146 вне −40…60 (°C=215.0)", hot.error)
        assertEquals(amb.caveatRu, hot.note)
        assertEquals("Нет данных SAE 0146", amb.read(client("", "NO DATA\r\r>")).error)
    }
}
