package com.chuikoff.obd

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.sensors.CretaAtf
import com.chuikoff.obd.sensors.CretaAtfSensor
import com.chuikoff.obd.sensors.CvtDebugLog
import com.chuikoff.obd.vehicle.Creta2018Profile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CretaAtfTest {
    /** ELM headers-off multi-frame text for one ISO-TP message (`01C` + `0:`… lines). */
    private fun isoTp(msg: List<Int>): String {
        val sb = StringBuilder("%03X\r".format(msg.size))
        sb.append("0:").append(msg.take(6).joinToString("") { "%02X".format(it) }).append('\r')
        var i = 6; var n = 1
        while (i < msg.size) {
            val chunk = msg.drop(i).take(7).let { it + List(7 - it.size) { 0xAA } }
            sb.append("%X:".format(n and 0xF)).append(chunk.joinToString("") { "%02X".format(it) }).append('\r')
            i += 7; n++
        }
        return sb.append("\r>").toString()
    }

    /** 61 A0 + [len] payload bytes, payload[idx] = value, the rest 0x11. */
    private fun a0(len: Int, vararg at: Pair<Int, Int>): List<Int> {
        val p = MutableList(len) { 0x11 }
        at.forEach { (i, v) -> p[i] = v }
        return listOf(0x61, 0xA0) + p
    }

    private fun elm(reply: (header: String, cmd: String) -> String): Pair<Elm327Client, ScriptedElm> {
        var header = "7E0"
        val t = ScriptedElm { c ->
            when {
                c == "ATZ" -> "\r\rELM327 v1.5\r\r>"
                c.startsWith("ATSH") -> { header = c.removePrefix("ATSH"); "OK\r\r>" }
                c.startsWith("AT") -> "OK\r\r>"
                else -> reply(header, c)
            }
        }
        return Elm327Client(t) to t
    }

    @Before fun setUp() { CretaAtf.reset(); CvtDebugLog.clear() }

    @Test fun scanGaugeI30LayoutIsIndex13() {
        // RXF 03 22 → PCI of the 2nd consecutive frame; RXD 0x28 → frame byte 5 (ID=0,1 PCI=2) → data byte 3
        // CF#2 carries message bytes 13…19 → message byte 15 → payload (after 61 A0) index 13
        val raw = isoTp(a0(20, 13 to 0x6E))
        val p = CretaAtf.payload(raw, CretaAtf.Layout.MODE21_A0)!!
        assertEquals(70.0, CretaAtf.decode(p, CretaAtf.I30_21A0)!!, 0.0)
        assertEquals(CretaAtf.I30_21A0, CretaAtf.choose(mapOf(CretaAtf.I30_21A0 to p, CretaAtf.US_21A0 to p)))
    }

    @Test fun longFramePrefersUsIndex33() {
        val p = CretaAtf.payload(isoTp(a0(40, 13 to 0x30, 33 to 0x5A)), CretaAtf.Layout.MODE21_A0)!!
        assertEquals(CretaAtf.US_21A0, CretaAtf.choose(mapOf(CretaAtf.I30_21A0 to p, CretaAtf.US_21A0 to p)))
        assertEquals(50.0, CretaAtf.decode(p, CretaAtf.US_21A0)!!, 0.0)
    }

    @Test fun fillerAndImplausibleRejected() {
        assertNull(CretaAtf.decodeByte(0xFF)); assertNull(CretaAtf.decodeByte(0xFE)); assertNull(CretaAtf.decodeByte(0x00))
        assertNull(CretaAtf.decodeByte(0xF0)) // 200 °C
        assertEquals(-10.0, CretaAtf.decodeByte(30)!!, 0.0)
        // short frame: index 13 not present
        val p = CretaAtf.payload(isoTp(a0(10)), CretaAtf.Layout.MODE21_A0)
        assertNull(CretaAtf.decode(p, CretaAtf.I30_21A0))
    }

    @Test fun udsFallback2081() {
        val msg = listOf(0x62, 0x20, 0x81) + MutableList(20) { 0x11 }.also { it[13] = 0x64 }
        val p = CretaAtf.payload(isoTp(msg), CretaAtf.Layout.MODE22_2081)
        assertEquals(60.0, CretaAtf.decode(p, CretaAtf.UDS_2081)!!, 0.0)
    }

    @Test fun probesOnceThenPollsOnlyTheWinnerAndLogsRaw() = runBlocking {
        val (c, t) = elm { h, cmd ->
            when {
                h == "7E1" && cmd == "21A0" -> isoTp(a0(20, 13 to 0x6E))
                h == "7E1" && cmd == "2101" -> isoTp(listOf(0x61, 0x01) + List(12) { 0x22 })
                else -> "NO DATA\r\r>"
            }
        }
        c.ensureInitialized()
        val s = CretaAtfSensor()
        val r1 = s.read(c)
        assertEquals(70.0, r1.value!!, 0.0)
        assertTrue(r1.note!!.contains("ЭКСПЕРИМЕНТАЛЬНО"))
        assertEquals(CretaAtf.I30_21A0, CretaAtf.selected)
        assertTrue(t.sent.contains("222081")) // all candidates probed and logged
        assertTrue(CvtDebugLog.entries.value.any { it.request.contains("raw 2101") && it.rawVisible.contains("6101") })
        val mark = t.sent.size
        val r2 = s.read(c)
        assertEquals(70.0, r2.value!!, 0.0)
        val after = t.sentAfter(mark).filter { !it.startsWith("AT") }
        assertEquals(listOf("21A0"), after)
        assertEquals("ATSH7E0", t.sent.last { it.startsWith("ATSH") }) // engine addressing restored
    }

    @Test fun silentTcmHidesCard() = runBlocking {
        val (c, _) = elm { _, _ -> "NO DATA\r\r>" }
        c.ensureInitialized()
        val r = CretaAtfSensor().read(c)
        assertNull(r.value)
        assertTrue(CretaAtf.absent)
        assertFalse(Creta2018Profile().sensors().any { it.id == CretaAtf.SENSOR_ID })
        CretaAtf.reset()
        assertNotNull(Creta2018Profile().sensors().firstOrNull { it.id == CretaAtf.SENSOR_ID })
    }

    @Test fun answeringTcmWithoutPlausibleValueKeepsCardWithError() = runBlocking {
        val (c, _) = elm { h, cmd -> if (h == "7E1" && cmd == "21A0") "7F2112\r\r>" else "NO DATA\r\r>" }
        c.ensureInitialized()
        val r = CretaAtfSensor().read(c)
        assertNull(r.value)
        assertFalse(CretaAtf.absent)
        assertTrue(r.error!!.contains("АКПП отвечает"))
    }
}
