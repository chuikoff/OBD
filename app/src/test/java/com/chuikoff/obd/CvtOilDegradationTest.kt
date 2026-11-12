package com.chuikoff.obd

import com.chuikoff.obd.sensors.CvtOilDegradation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CvtOilDegradationTest {
    /** ELM CAF/headers-off multi-frame text for `61 10` + [data]. */
    private fun frame(data: List<Int>): String {
        val all = listOf(0x61, 0x10) + data
        val sb = StringBuilder("%03X\r".format(all.size))
        sb.append("0:").append(all.take(6).joinToString("") { "%02X".format(it) }).append("\r")
        var rest = all.drop(6); var idx = 1
        while (rest.isNotEmpty()) {
            val chunk = rest.take(7) + List((7 - rest.size).coerceAtLeast(0)) { 0x00 }
            sb.append("%X:".format(idx % 16)).append(chunk.joinToString("") { "%02X".format(it) }).append("\r")
            rest = rest.drop(7); idx++
        }
        return sb.append("\r>").toString()
    }

    private fun data(n: Int = 32, set: Map<Int, Int>) = List(n) { set[it] ?: 0x11 }

    @Test fun acAdFromElm3() {
        val r = CvtOilDegradation.parse(frame(data(set = mapOf(27 to 0x00, 28 to 0x3C, 29 to 0xF0, 24 to 0x01, 25 to 0x02))))
        assertEquals(15600, r.value)
        assertEquals(15600, r.v16)
        assertEquals(15600, r.v24)
        assertEquals(0x0102, r.vOut3)
        assertNull(r.error)
        assertTrue(r.source.contains("AC·256+AD"))
        assertEquals(32, r.payloadSize)
    }

    @Test fun highByteUsedWhenPlausible() {
        val r = CvtOilDegradation.parse(frame(data(set = mapOf(27 to 0x01, 28 to 0x86, 29 to 0xA0))))
        assertEquals(65536 + 0x86A0, r.value) // 100 000
        assertTrue(r.source.contains("старший байт"))
    }

    @Test fun implausibleHighByteFallsBackTo16Bit() {
        val r = CvtOilDegradation.parse(frame(data(set = mapOf(27 to 0x11, 28 to 0x10, 29 to 0x00))))
        assertEquals(0x1000, r.value)
    }

    @Test fun shortOrMissingReplies() {
        val short = CvtOilDegradation.parse(frame(data(n = 20, set = emptyMap())))
        assertNull(short.value)
        assertNotNull(short.error)
        val nodata = CvtOilDegradation.parse("NO DATA\r\r>")
        assertNull(nodata.value)
        assertNotNull(nodata.error)
        val refused = CvtOilDegradation.parse("7F2112\r\r>")
        assertNull(refused.value)
        assertTrue(refused.error!!, refused.error!!.contains("7F"))
    }

    @Test fun serializeRoundTrip() {
        val r = CvtOilDegradation.parse(frame(data(set = mapOf(28 to 0x3C, 29 to 0xF0))), atMs = 1234L)
        val back = CvtOilDegradation.deserialize(CvtOilDegradation.serialize(r))!!
        assertEquals(r.value, back.value); assertEquals(r.atMs, back.atMs); assertEquals(r.source, back.source)
        assertEquals(r.payloadHex, back.payloadHex); assertEquals(r.vOut3, back.vOut3)
        assertNull(CvtOilDegradation.deserialize(null))
        assertNull(CvtOilDegradation.deserialize("garbage"))
    }
}
