package com.chuikoff.obd

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.polling.Poller
import com.chuikoff.obd.sensors.EngineExtras
import com.chuikoff.obd.sensors.EngineMath
import com.chuikoff.obd.sensors.VoltageScale
import com.chuikoff.obd.vehicle.OutlanderGfGgProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineExtrasTest {
    @Test fun math() {
        assertEquals(850, EngineMath.rpm(0x0D, 0x48))
        assertEquals(2.5, EngineMath.mafGs(0x00, 0xFA), 1e-9)
        assertEquals(100.0, EngineMath.loadPercent(255), 1e-9)
        assertEquals(14.2, EngineMath.moduleVoltage(0x37, 0x78), 1e-9)
        // idle MAF 2.5 g/s → ≈0.82 L/h
        val lph = EngineMath.litersPerHour(2.5)
        assertEquals(0.8218, lph, 1e-3)
        assertNull(EngineMath.litersPer100km(lph, 0))
        assertNull(EngineMath.litersPer100km(lph, 4))
        assertNull(EngineMath.litersPer100km(lph, null))
        // 10 g/s at 60 km/h → 3.287 L/h → 5.48 L/100 km
        assertEquals(5.478, EngineMath.litersPer100km(EngineMath.litersPerHour(10.0), 60)!!, 1e-3)
    }

    @Test fun atrv() {
        assertEquals(12.6, EngineMath.parseAtrv("12.6V\r\r>")!!, 1e-9)
        assertEquals(14.1, EngineMath.parseAtrv("14,1 V\r>")!!, 1e-9)
        assertNull(EngineMath.parseAtrv("?\r>"))
        assertNull(EngineMath.parseAtrv("0.0V\r>"))
    }

    @Test fun voltageColors() {
        assertEquals(VoltageScale.RED, VoltageScale.argb(11.94, null))      // shows 11.9
        assertEquals(null, VoltageScale.argb(12.6, false))                  // parked, normal battery
        assertEquals(null, VoltageScale.argb(12.6, null))
        assertEquals(VoltageScale.ORANGE, VoltageScale.argb(12.6, true))    // running but not charging
        assertEquals(VoltageScale.ORANGE, VoltageScale.argb(13.2, true))
        assertEquals(null, VoltageScale.argb(13.3, true))
        assertEquals(VoltageScale.GREEN, VoltageScale.argb(13.5, true))
        assertEquals(VoltageScale.GREEN, VoltageScale.argb(14.8, true))
        assertEquals(null, VoltageScale.argb(14.9, true))
        assertEquals(VoltageScale.RED, VoltageScale.argb(15.1, true))
        assertNull(VoltageScale.argb(null, true))
    }

    @Test fun supportedPidList() {
        // 0100: BE 3F B8 11 → 01,03-07,0B-10,11,13-15,1C,20 ; 0120 → … 40 ; 0140 → 42
        val s = EngineMath.supportedPids(listOf("4100BE3FB811\r\r>", "4120A0000001\r\r>", "414040000000\r\r>"))!!
        assertTrue(s.containsAll(listOf(0x04, 0x0C, 0x0D, 0x10, 0x20, 0x21, 0x23, 0x40, 0x42)))
        assertTrue(0x46 !in s)
        assertNull(EngineMath.supportedPids(listOf("NO DATA\r\r>")))
        // stops when bit 0x20 is not set
        val only = EngineMath.supportedPids(listOf("4100BE3EB810\r\r>", "4120FFFFFFFF\r\r>"))!!
        assertTrue(0x21 !in only)
    }

    @Test fun onlySupportedCardsAreShown() {
        val x = EngineExtras()
        x.reset(null)
        assertEquals(listOf(EngineExtras.ID_VOLTAGE), x.sensors().map { it.id }) // ATRV needs no PID list
        x.reset(setOf(0x0C, 0x10))
        assertEquals(listOf(EngineExtras.ID_VOLTAGE, EngineExtras.ID_RPM, EngineExtras.ID_FUEL), x.sensors().map { it.id })
        x.reset(setOf(0x04, 0x0C, 0x10, 0x42))
        assertEquals(EngineExtras.IDS, x.sensors().map { it.id })
    }

    private fun elm(replies: Map<String, String>) = Elm327Client(ScriptedElm { c ->
        when {
            c == "ATZ" -> "\r\rELM327 v1.5\r\r>"
            c == "ATRV" -> replies["ATRV"] ?: "12.4V\r\r>"
            c.startsWith("AT") -> "OK\r\r>"
            else -> replies[c] ?: "NO DATA\r\r>"
        }
    })

    @Test fun readsAndColorsAndHidesDeadPids() = runBlocking {
        val x = EngineExtras()
        x.reset(setOf(0x04, 0x0C, 0x0D, 0x10, 0x42))
        val c = elm(mapOf("010C" to "410C0D48\r\r>", "0142" to "41423778\r\r>", "0110" to "411003E8\r\r>", "010D" to "410D3C\r\r>"))
        val byId = x.sensors().associateBy { it.id }
        val rpm = byId.getValue(EngineExtras.ID_RPM).read(c)
        assertEquals("850 об/мин", rpm.displayText)
        val v = byId.getValue(EngineExtras.ID_VOLTAGE).read(c)
        assertEquals("14.2 В", v.displayText)
        assertEquals(VoltageScale.GREEN, v.valueArgb)
        val f = byId.getValue(EngineExtras.ID_FUEL).read(c)
        assertEquals("5.5 л/100км", f.displayText) // MAF 10 g/s at 60 km/h
        // 0104 answers NO DATA → hidden after 3 tries
        repeat(EngineExtras.MAX_NO_DATA) { byId.getValue(EngineExtras.ID_LOAD).read(c) }
        assertTrue(EngineExtras.ID_LOAD !in x.sensors().map { it.id })
    }

    @Test fun idleFuelInLitersPerHourAndAtrvFallback() = runBlocking {
        val x = EngineExtras()
        x.reset(setOf(0x0C, 0x0D, 0x10)) // no 0142 → ATRV
        val c = elm(mapOf("0110" to "411000FA\r\r>", "010D" to "410D00\r\r>", "010C" to "410C0000\r\r>", "ATRV" to "12.1V\r\r>"))
        val byId = x.sensors().associateBy { it.id }
        assertEquals("0.8 л/ч", byId.getValue(EngineExtras.ID_FUEL).read(c).displayText)
        byId.getValue(EngineExtras.ID_RPM).read(c) // 0 rpm → engine off
        val v = byId.getValue(EngineExtras.ID_VOLTAGE).read(c)
        assertEquals("12.1 В", v.displayText)
        assertNull(v.valueArgb) // 12.1 V with engine off is neutral
        assertTrue(v.note!!.contains("ATRV"))
    }

    @Test fun extrasNeverTouchCvtOnlyTicks() {
        val x = EngineExtras()
        x.reset(setOf(0x04, 0x0C, 0x10, 0x42))
        val extras = x.sensors()
        val base = OutlanderGfGgProfile().sensors()
        for (tick in 1..24) {
            val e = Poller.extraForTick(extras, tick, outlander = true)
            val ids = Poller.withExtra(Poller.sensorsForTick(base, tick, true), e).map { it.id }
            if (tick % 2 == 1) {
                assertNull(e)
                assertEquals(Poller.sensorsForTick(base, tick, true).map { it.id }, ids)
            } else {
                assertEquals("cvt_temp", ids.first())
                assertEquals("coolant_temp", ids[1])
                assertEquals(e!!.id, ids[2])        // right after coolant (engine ECU)…
                if ("ambient_temp" in ids) assertEquals("ambient_temp", ids.last()) // …before AWC
            }
        }
        // RPM every other extra slot, the rest round-robin
        val seq = (2..24 step 2).map { Poller.extraForTick(extras, it, true)!!.id }
        assertEquals(listOf("rpm", "voltage", "rpm", "engine_load", "rpm", "fuel_rate"), seq.take(6))
        assertNull(Poller.extraForTick(emptyList(), 2, true))
    }
}
