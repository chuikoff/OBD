package com.chuikoff.obd

import com.chuikoff.obd.sensors.AmbientDisplayFilter
import com.chuikoff.obd.sensors.OutlanderAmbientTempSensor
import com.chuikoff.obd.sensors.VehicleSpeedReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Real 210D frames from the 2026-10-08 session (dashboard showed a constant 19 °C,
 * coolant 86–91 °C, mostly idle). Data bytes after `61 0D`, byte 8 = raw bumper sensor.
 */
class AmbientFilterTest {

    private data class Sample(val hhmm: String, val dataHex: String, val appShowed: Int)

    private val session = listOf(
        Sample("13:54", "01010491 0018A29F76FEFF", 19),
        Sample("13:55", "01000490 0018A2A275FEFF", 19),
        Sample("13:57", "01000491 0018A2A775FEFF", 19),
        Sample("14:10", "0100048F 0018A2D07C02FF", 22),
        Sample("14:16", "0100048E 0018A2D48003FF", 24),
        Sample("14:29", "0000048D 0018A3067D03FF", 23),
        Sample("14:31", "0101048C 0018A30B7C03FF", 22),
        Sample("14:32", "0001048A 0018A30B7F03FF", 24),
        Sample("14:32", "0000048D 0018A30B8003FF", 24)
    )

    /** ELM ISO-TP text exactly as the adapter sends it (ATS0, ATH0, CAF1). */
    private fun elm(dataHex: String): String {
        val d = dataHex.replace(" ", "")
        val all = "610D$d"
        val len = all.length / 2
        val f0 = all.substring(0, 12)
        val rest = all.substring(12).chunked(14)
        val sb = StringBuilder("%03X\r0:%s\r".format(len, f0))
        rest.forEachIndexed { i, c -> sb.append("${i + 1}:$c\r") }
        return sb.append("\r>").toString()
    }

    private fun minutes(hhmm: String): Long {
        val (h, m) = hhmm.split(":").map { it.toLong() }
        return h * 60 + m
    }

    /** What the card shows: %.0f uses HALF_UP. */
    private fun shown(c: Double): Int = BigDecimal(c).setScale(0, RoundingMode.HALF_UP).toInt()

    private val t0 = minutes("13:54")
    private fun msOf(hhmm: String) = (minutes(hhmm) - t0) * 60_000L + BASE

    private class MemStore(var saved: AmbientDisplayFilter.Saved? = null) : AmbientDisplayFilter.Store {
        override fun load() = saved
        override fun save(saved: AmbientDisplayFilter.Saved) { this.saved = saved }
    }

    /** Poll every 2 s from 13:54 to 14:33, raw = last sample at or before t. */
    private fun runTimeline(
        filter: AmbientDisplayFilter,
        speedAt: (Long) -> Int?,
        onStep: (tMs: Long, raw: Double, out: AmbientDisplayFilter.Output) -> Unit
    ) {
        val raws = session.map { msOf(it.hhmm) to OutlanderAmbientTempSensor.decodeRaw(elm(it.dataHex))!! }
        var t = msOf("13:54")
        val end = msOf("14:33")
        while (t <= end) {
            val raw = raws.last { it.first <= t }.second
            onStep(t, raw, filter.update(raw, t, speedAt(t)))
            t += 2_000L
        }
    }

    // ---- decode ----

    @Test fun realFramesDecodeByte8Only() {
        val expected = listOf(19.0, 18.5, 18.5, 22.0, 24.0, 22.5, 22.0, 23.5, 24.0)
        session.zip(expected).forEach { (s, exp) ->
            val c = OutlanderAmbientTempSensor.decodeRaw(elm(s.dataHex))
            assertEquals(s.hhmm, exp, c!!, 0.001)
            // matches what 1.7.0 printed (it rounded the same byte)
            assertEquals(s.hhmm, s.appShowed.toDouble(), exp, 0.5)
        }
    }

    @Test fun noOtherByteTracksConstantPanel() {
        // Panel was 19 °C all session. Bytes that stay constant (2, 5, 10) don't map to 19 with
        // the usual x/2−40 or x−40 scales; byte 8 is the only one that is 19 at session start.
        val payloads = session.map { s -> s.dataHex.replace(" ", "").chunked(2).map { it.toInt(16) } }
        for (idx in 0 until 11) {
            val values = payloads.map { it[idx] }
            val constant = values.distinct().size == 1
            val half = values.map { it / 2.0 - 40 }
            val minus = values.map { it - 40.0 }
            val tracksPanel = half.all { it in 18.5..19.5 } || minus.all { it in 18.5..19.5 }
            assertTrue("byte $idx unexpectedly tracks the panel", !tracksPanel)
            if (idx == 8) assertTrue(!constant)
        }
    }

    @Test fun oldFallbackIndicesAreGone() {
        // byte 8 = FF (invalid) while byte 5 = 0x76 (19 °C) — 1.7.0 would have shown 19 from byte 5
        assertNull(OutlanderAmbientTempSensor.decodeRaw(elm("01010491 0076A29FFFFEFF")))
        // byte 8 = 0x00 (sensor open)
        assertNull(OutlanderAmbientTempSensor.decodeRaw(elm("01010491 0018A29F00FEFF")))
    }

    @Test fun shortOrHoledFrameRejected() {
        // only 8 data bytes → byte 8 missing
        assertNull(OutlanderAmbientTempSensor.decodeRaw("00A\r0:610D01010491\r1:0018A29F\r\r>"))
        // declared 13 bytes, frame 1 lost — positions not trustworthy
        assertNull(OutlanderAmbientTempSensor.decodeRaw("00D\r0:610D01010491\r\r>"))
        assertNull(OutlanderAmbientTempSensor.decodeRaw("NO DATA\r\r>"))
    }

    @Test fun speedParse() {
        assertEquals(60, VehicleSpeedReader.parse("410D3C\r\r>"))
        assertEquals(0, VehicleSpeedReader.parse("41 0D 00 \r\r>"))
        assertNull(VehicleSpeedReader.parse("NO DATA\r\r>"))
    }

    // ---- filter on the real timeline ----

    @Test fun standingStillPanelStays19WhileRawGoesTo24() {
        val f = AmbientDisplayFilter()
        var maxRaw = 0.0
        runTimeline(f, speedAt = { 0 }) { t, raw, out ->
            maxRaw = maxOf(maxRaw, raw)
            assertEquals("t=${(t - BASE) / 60000} min raw=$raw", 19, shown(out.displayC))
            assertTrue(out.displayC <= raw)
        }
        assertEquals(24.0, maxRaw, 0.001)
    }

    @Test fun drivingAt60RisesAtMostOneDegreePerMinute() {
        val f = AmbientDisplayFilter()
        var prev: Pair<Long, Double>? = null
        val at = mutableMapOf<String, Double>()
        runTimeline(f, speedAt = { 60 }) { t, raw, out ->
            prev?.let { (pt, pv) ->
                if (out.displayC > pv) {
                    // never more than 1 °C per poll, and cumulative rate ≤ 1 °C / 60 s
                    assertTrue(out.displayC - pv <= 1.0 + 1e-9)
                }
            }
            prev = t to out.displayC
            at[String.format("%d", (t - BASE) / 1000)] = out.displayC
            assertTrue(out.displayC <= raw + 1e-9)
        }
        fun d(hhmm: String, plusSec: Int = 0) = at[(((msOf(hhmm) - BASE) / 1000) + plusSec).toString()]!!
        // 14:10 raw jumps 18.5 → 22: not instant
        assertEquals(18.5, d("14:10"), 1e-9)
        assertEquals(19.5, d("14:11"), 1e-9)
        assertEquals(20.5, d("14:12"), 1e-9)
        assertEquals(21.5, d("14:13"), 1e-9)
        assertEquals(22.0, d("14:14"), 1e-9)
        // 14:16 raw 24 → +1 °C/min again
        assertEquals(22.0, d("14:16"), 1e-9)
        assertEquals(23.0, d("14:17"), 1e-9)
        assertEquals(24.0, d("14:18"), 1e-9)
        // 14:29 raw 22.5 → drops at once
        assertEquals(22.5, d("14:29"), 1e-9)
        assertEquals(22.0, d("14:31"), 1e-9)
    }

    @Test fun dropsInstantly() {
        val f = AmbientDisplayFilter()
        f.update(24.0, BASE, 60)
        val out = f.update(12.5, BASE + 2_000, 0)
        assertEquals(12.5, out.displayC, 0.0)
        assertEquals(AmbientDisplayFilter.State.TRACKING, out.state)
    }

    @Test fun standingTimeDoesNotBankARise() {
        val f = AmbientDisplayFilter()
        f.update(19.0, BASE, 0)
        var t = BASE
        repeat(15 * 30) { t += 2_000; assertEquals(19.0, f.update(24.0, t, 0).displayC, 0.0) } // 15 min idle
        // start driving: first step only after a full minute at speed
        repeat(29) { t += 2_000; assertEquals(19.0, f.update(24.0, t, 60).displayC, 0.0) }
        t += 2_000
        assertEquals(20.0, f.update(24.0, t, 60).displayC, 0.0)
        // slow city driving below 20 km/h does not count
        repeat(60) { t += 2_000; assertEquals(20.0, f.update(24.0, t, 15).displayC, 0.0) }
    }

    @Test fun noSpeedRisesOnlyAfterTenMinutesHigher() {
        val f = AmbientDisplayFilter()
        f.update(19.0, BASE, null)
        var t = BASE
        while (t < BASE + 10 * 60_000L - 2_000) {
            t += 2_000
            assertEquals(19.0, f.update(24.0, t, null).displayC, 0.0)
        }
        // past 10 min continuously higher → slow rise
        while (t < BASE + 11 * 60_000L + 2_000) { t += 2_000; f.update(24.0, t, null) }
        val v = f.displayedC!!
        assertTrue("v=$v", v in 19.5..20.0)
        // a dip resets the 10-min timer
        f.update(18.0, t + 2_000, null)
        assertEquals(18.0, f.update(23.0, t + 4_000, null).displayC, 0.0)
        assertEquals(18.0, f.update(23.0, t + 4_000 + 9 * 60_000L, null).displayC, 0.0)
    }

    @Test fun hotRestartStartsFromStoredValue() {
        val store = MemStore()
        val a = AmbientDisplayFilter(store = store)
        a.update(19.0, BASE, 0)
        a.update(21.0, BASE + 60_000, 0) // heat-soak, stays 19
        a.reset(BASE + 120_000)
        assertEquals(19.0, store.saved!!.valueC, 0.0)

        // 40 min later, engine bay hot: raw 26 → start from stored 19, keep it while standing
        val b = AmbientDisplayFilter(store = store)
        val out = b.update(26.0, BASE + 40 * 60_000L, 0)
        assertEquals(19.0, out.displayC, 0.0)
        assertTrue(out.restored)
        assertEquals(19.0, b.update(26.0, BASE + 45 * 60_000L, 0).displayC, 0.0)
    }

    @Test fun restartColderOrStaleUsesRaw() {
        val store = MemStore(AmbientDisplayFilter.Saved(19.0, BASE))
        // colder now → raw
        assertEquals(12.0, AmbientDisplayFilter(store = store).update(12.0, BASE + 30 * 60_000L, 0).displayC, 0.0)
        // > 2 h → raw even if higher
        store.saved = AmbientDisplayFilter.Saved(19.0, BASE)
        val out = AmbientDisplayFilter(store = store).update(24.0, BASE + 3 * 60 * 60_000L, 0)
        assertEquals(24.0, out.displayC, 0.0)
        assertTrue(!out.restored)
    }

    @Test fun persistsWhileRunning() {
        val store = MemStore()
        val f = AmbientDisplayFilter(store = store)
        f.update(19.0, BASE, 0)
        f.update(24.0, BASE + 20_000, 0)
        assertEquals(19.0, store.saved!!.valueC, 0.0)
        assertEquals(BASE + 20_000, store.saved!!.atMs) // timestamp refreshed (≥15 s)
        f.update(17.0, BASE + 22_000, 0)
        assertEquals(17.0, store.saved!!.valueC, 0.0)
    }

    companion object {
        private const val BASE = 1_791_460_440_000L // 2026-10-08 13:54 MSK, arbitrary
    }
}
