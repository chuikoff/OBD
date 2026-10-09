package com.chuikoff.obd

import com.chuikoff.obd.ui.TempBand
import com.chuikoff.obd.ui.TempColorScale
import com.chuikoff.obd.ui.TempColorScale.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TempColorScaleTest {

    private fun b(k: Kind, c: Double) = TempColorScale.band(k, c)

    @Test fun cvtBandsUnchanged() {
        assertEquals(TempBand.COLD, b(Kind.CVT, 19.0))
        assertEquals(TempBand.NORMAL, b(Kind.CVT, 20.0))
        assertEquals(TempBand.NORMAL, b(Kind.CVT, 99.0))
        assertEquals(TempBand.OVERHEAT, b(Kind.CVT, 100.0))
        // colors identical to 1.8.0 CvtCold / CvtNormal / CvtHot
        assertEquals(0xFF1E88E5, TempBand.COLD.argb)
        assertEquals(0xFF43A047, TempBand.NORMAL.argb)
        assertEquals(0xFFE53935, TempBand.OVERHEAT.argb)
    }

    @Test fun coolantBoundaries() {
        assertEquals(TempBand.COLD, b(Kind.COOLANT, 69.0))
        assertEquals(TempBand.NORMAL, b(Kind.COOLANT, 70.0))
        assertEquals(TempBand.NORMAL, b(Kind.COOLANT, 104.0))
        assertEquals(TempBand.HOT, b(Kind.COOLANT, 105.0))
        assertEquals(TempBand.HOT, b(Kind.COOLANT, 109.0))
        assertEquals(TempBand.OVERHEAT, b(Kind.COOLANT, 110.0))
        assertEquals(TempBand.COLD, b(Kind.COOLANT, -20.0))
    }

    @Test fun ambientBoundaries() {
        assertEquals(TempBand.FROST, b(Kind.AMBIENT, -10.0))
        assertEquals(TempBand.ICE, b(Kind.AMBIENT, -9.0))
        assertEquals(TempBand.ICE, b(Kind.AMBIENT, 3.0))
        assertEquals(TempBand.COOL, b(Kind.AMBIENT, 4.0))
        assertEquals(TempBand.COOL, b(Kind.AMBIENT, 15.0))
        assertEquals(TempBand.NORMAL, b(Kind.AMBIENT, 16.0))
        assertEquals(TempBand.NORMAL, b(Kind.AMBIENT, 25.0))
        assertEquals(TempBand.HOT, b(Kind.AMBIENT, 26.0))
        assertEquals(TempBand.HOT, b(Kind.AMBIENT, 30.0))
        assertEquals(TempBand.OVERHEAT, b(Kind.AMBIENT, 31.0))
    }

    @Test fun bandFollowsDisplayedRoundedNumber() {
        // AWC gives 0.5 °C steps; %.0f rounds HALF_UP → 3.5 shows "4" → cool, not ice
        assertEquals("4 °C", TempColorScale.valueText(Kind.AMBIENT, 3.5))
        assertEquals(TempBand.COOL, b(Kind.AMBIENT, 3.5))
        assertEquals("❄ 3 °C", TempColorScale.valueText(Kind.AMBIENT, 3.4))
        assertEquals(TempBand.ICE, b(Kind.AMBIENT, 3.4))
        assertEquals("-10 °C", TempColorScale.valueText(Kind.AMBIENT, -9.5))
        assertEquals(TempBand.FROST, b(Kind.AMBIENT, -9.5))
        assertEquals(TempBand.OVERHEAT, b(Kind.AMBIENT, 30.5))
        assertEquals(TempBand.NORMAL, b(Kind.COOLANT, 104.4))
        assertEquals(TempBand.HOT, b(Kind.COOLANT, 104.5))
        assertEquals(TempBand.NORMAL, b(Kind.CVT, 19.5))
        assertEquals("0 °C", TempColorScale.valueText(Kind.COOLANT, -0.4)) // no "-0"
    }

    @Test fun noColorForMissingValue() {
        assertNull(TempColorScale.band(Kind.COOLANT, null))
        assertNull(TempColorScale.band(Kind.AMBIENT, Double.NaN))
        assertEquals("—", TempColorScale.valueText(Kind.AMBIENT, null))
    }

    @Test fun kindsAndDistinctOrange() {
        assertEquals(Kind.CVT, TempColorScale.kindFor("cvt_temp"))
        assertEquals(Kind.CVT, TempColorScale.kindFor("x", "Температура коробки"))
        assertEquals(Kind.COOLANT, TempColorScale.kindFor("coolant_temp"))
        assertEquals(Kind.AMBIENT, TempColorScale.kindFor("ambient_temp"))
        assertNull(TempColorScale.kindFor("rpm", "Обороты"))
        assertNotEquals(TempBand.HOT.argb, TempBand.OVERHEAT.argb)
    }
}
