package com.chuikoff.obd

import com.chuikoff.obd.sensors.EngineExtras
import com.chuikoff.obd.sensors.FuelResetMode
import com.chuikoff.obd.sensors.FuelTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FuelTripPersistenceTest {
    private val msk = ZoneId.of("Europe/Moscow")
    private fun at(day: Int, h: Int, m: Int = 0) =
        ZonedDateTime.of(2026, 11, day, h, m, 0, 0, msk).toInstant().toEpochMilli()

    private fun drive(t: FuelTrip, start: Long, seconds: Int): Long {
        var now = start
        t.add(now, 6.0, 60)
        repeat(seconds) { now += 1000; t.add(now, 6.0, 60) }
        return now
    }

    private fun trip(mode: FuelResetMode, start: Long) = FuelTrip(clock = { start }).also { it.mode = mode; it.zone = msk; it.reset(start) }

    @Test fun serializeRestoreRoundTrip() {
        val t = trip(FuelResetMode.MANUAL, at(19, 10))
        drive(t, at(19, 10), 120) // 2 km, 0.2 L
        val saved = FuelTrip.parse(t.serialize())!!
        val u = FuelTrip()
        u.restore(saved.first, saved.second, saved.third)
        assertEquals(t.km, u.km, 1e-12)
        assertEquals(t.liters, u.liters, 1e-12)
        assertEquals(at(19, 10), u.startedAtMs)
        assertEquals(10.0, u.averagePer100!!, 1e-9)
        assertNull(FuelTrip.parse(null))
        assertNull(FuelTrip.parse("garbage"))
        assertNull(FuelTrip.parse("1;x;3"))
    }

    @Test fun manualKeepsAcrossConnectionsAndDays() {
        val t = trip(FuelResetMode.MANUAL, at(19, 10))
        drive(t, at(19, 10), 120)
        assertFalse(t.onConnect(at(19, 11)))
        assertFalse(t.onConnect(at(25, 9)))
        assertEquals(2.0, t.km, 1e-9)
        // the gap between connections is not integrated
        t.add(at(25, 9) + 1000, 6.0, 60)
        assertEquals(2.0, t.km, 1e-9)
        t.reset(at(25, 9, 30))
        assertEquals(0.0, t.km, 0.0)
        assertEquals(at(25, 9, 30), t.startedAtMs)
    }

    @Test fun eachConnectResets() {
        val t = trip(FuelResetMode.EACH_CONNECT, at(19, 10))
        drive(t, at(19, 10), 120)
        assertTrue(t.onConnect(at(19, 10, 30)))
        assertEquals(0.0, t.km, 0.0)
        assertEquals(at(19, 10, 30), t.startedAtMs)
    }

    @Test fun dailyResetsOnNewLocalDayOnly() {
        val t = trip(FuelResetMode.DAILY, at(19, 8))
        drive(t, at(19, 8), 120)
        assertFalse(t.onConnect(at(19, 23, 50))) // same day: kept
        assertEquals(2.0, t.km, 1e-9)
        assertTrue(t.onConnect(at(20, 0, 10)))   // after local midnight
        assertEquals(0.0, t.km, 0.0)
    }

    @Test fun dailyResetsWhileDrivingThroughMidnight() {
        val t = trip(FuelResetMode.DAILY, at(19, 23, 50))
        val end = drive(t, at(19, 23, 50), 300) // till 23:55
        assertEquals(5.0, t.km, 1e-9)
        t.add(at(20, 0, 0) + 500, 6.0, 60) // first sample of the new day
        assertEquals(0.0, t.km, 0.0)
        assertTrue(end < at(20, 0))
    }

    @Test fun modeParsingDefaultsToManual() {
        assertEquals(FuelResetMode.MANUAL, FuelResetMode.of(null))
        assertEquals(FuelResetMode.MANUAL, FuelResetMode.of("bogus"))
        assertEquals(FuelResetMode.DAILY, FuelResetMode.of("DAILY"))
        assertEquals(FuelResetMode.MANUAL, FuelTrip().mode)
    }

    @Test fun newConnectionDoesNotClearTrip() {
        val x = EngineExtras()
        drive(x.trip, 0, 120)
        x.reset(setOf(0x0C, 0x10))
        assertEquals(2.0, x.trip.km, 1e-9)
    }
}
