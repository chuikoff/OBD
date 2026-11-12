package com.chuikoff.obd

import com.chuikoff.obd.alerts.AlertEngine
import com.chuikoff.obd.alerts.AlertKind
import com.chuikoff.obd.alerts.AlertSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {
    private val s = AlertSettings()
    private val min = 60_000L

    private fun AlertEngine.cvt(c: Double?, t: Long) = update(s, c, null, null, t).map { it.kind }
    private fun AlertEngine.cool(c: Double?, t: Long) = update(s, null, c, null, t).map { it.kind }
    private fun AlertEngine.amb(c: Double?, t: Long, st: AlertSettings = s) = update(st, null, null, c, t).map { it.kind }

    @Test fun cvtNeedsTwoSamplesAndUsesDisplayedRounding() {
        val e = AlertEngine()
        assertEquals(emptyList<AlertKind>(), e.cvt(99.4, 0))      // shows 99
        assertEquals(emptyList<AlertKind>(), e.cvt(99.5, 1000))   // shows 100, 1st sample
        assertEquals(listOf(AlertKind.CVT_HOT), e.cvt(100.2, 2000))
        assertEquals(emptyList<AlertKind>(), e.cvt(104.0, 3000))  // still hot: no spam
    }

    @Test fun singleSpikeDoesNotFire() {
        val e = AlertEngine()
        assertTrue(e.cvt(101.0, 0).isEmpty())
        assertTrue(e.cvt(97.0, 1000).isEmpty())
        assertTrue(e.cvt(101.0, 2000).isEmpty())
        assertTrue(e.cvt(null, 3000).isEmpty()) // no fresh sample: nothing
    }

    @Test fun hysteresisAndCooldown() {
        val e = AlertEngine()
        e.cvt(100.0, 0); assertEquals(1, e.cvt(100.0, 1000).size)
        // dips only to 98 (> 100-3): not re-armed
        e.cvt(98.0, 2 * min); e.cvt(100.0, 3 * min)
        assertTrue(e.cvt(100.0, 30 * min).isEmpty())
        // cools to 97 → re-armed, but inside the 10-min cooldown of the first alert? (first at 1 s, now 31 min → ok)
        e.cvt(97.0, 31 * min)
        e.cvt(101.0, 32 * min)
        assertEquals(listOf(AlertKind.CVT_HOT), e.cvt(101.0, 32 * min + 1000))
        // re-armed again quickly, but cooldown 10 min holds it back…
        e.cvt(90.0, 33 * min); e.cvt(101.0, 34 * min)
        assertTrue(e.cvt(101.0, 35 * min).isEmpty())
        // …and fires once the cooldown is over if it's still hot
        assertEquals(listOf(AlertKind.CVT_HOT), e.cvt(101.0, 43 * min))
    }

    @Test fun coolantThresholdEditableAndDisable() {
        val e = AlertEngine()
        e.cool(105.0, 0)
        assertEquals(listOf(AlertKind.COOLANT_HOT), e.cool(105.0, 1000))
        val e2 = AlertEngine()
        val custom = s.copy(coolantHotC = 110)
        e2.update(custom, null, 105.0, null, 0)
        assertTrue(e2.update(custom, null, 107.0, null, 1000).isEmpty())
        val off = s.copy(coolantEnabled = false)
        val e3 = AlertEngine()
        e3.update(off, null, 120.0, null, 0)
        assertTrue(e3.update(off, null, 120.0, null, 1000).isEmpty())
    }

    @Test fun iceOncePerTripWithHysteresis() {
        val e = AlertEngine()
        assertTrue(e.amb(3.4, 0).isEmpty())                    // shows 3, 1st sample
        assertEquals(listOf(AlertKind.ICE), e.amb(2.0, 1000))
        // flickers around +3…+4: nothing new
        assertTrue(e.amb(4.0, 2 * min).isEmpty())
        assertTrue(e.amb(2.0, 3 * min).isEmpty())
        assertTrue(e.amb(2.0, 4 * min).isEmpty())
        // warm (≥ 3+2) but only 5 min → not re-armed
        e.amb(5.0, 5 * min); e.amb(5.0, 10 * min)
        e.amb(2.0, 11 * min)
        assertTrue(e.amb(2.0, 12 * min).isEmpty())
        // warm ≥ 10 min → re-armed, cold again → one more alert
        e.amb(6.0, 20 * min); e.amb(6.0, 31 * min)
        e.amb(1.0, 32 * min)
        assertEquals(listOf(AlertKind.ICE), e.amb(1.0, 33 * min))
        // new trip: again once
        e.resetTrip()
        e.amb(0.0, 40 * min)
        assertEquals(listOf(AlertKind.ICE), e.amb(0.0, 41 * min))
    }

    @Test fun eventTextMentionsValueAndThreshold() {
        val e = AlertEngine()
        e.update(s, 101.0, null, null, 0)
        val ev = e.update(s, 101.0, null, null, 1000).single()
        assertTrue(ev.textRu, ev.textRu.contains("101 °C") && ev.textRu.contains("100 °C"))
    }
}
