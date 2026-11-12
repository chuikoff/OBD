package com.chuikoff.obd

import com.chuikoff.obd.elm327.Elm327Parser
import org.junit.Assert.assertEquals
import org.junit.Test

class DtcParsingTest {
    @Test fun canMultiFrameWithCountByte() {
        // 43 04 | 0133 0240 0500 0111 — byte 2 is the DTC count on CAN
        val raw = "00A\r0:43040133024005\r1:00011100000000\r\r>"
        assertEquals(listOf("P0133", "P0240", "P0500", "P0111"), Elm327Parser.parseDtcList(raw))
    }

    @Test fun canSingleFrame() {
        assertEquals(listOf("P0420"), Elm327Parser.parseDtcList("43010420\r\r>"))
        assertEquals(listOf("P0420"), Elm327Parser.parseDtcList("43 01 04 20 \r\r>"))
    }

    @Test fun noCodes() {
        assertEquals(emptyList<String>(), Elm327Parser.parseDtcList("4300\r\r>"))
        assertEquals(emptyList<String>(), Elm327Parser.parseDtcList("NO DATA\r\r>"))
        assertEquals(emptyList<String>(), Elm327Parser.parseDtcList("\r>"))
    }

    @Test fun pendingMode07() {
        assertEquals(listOf("P0171", "C0035"), Elm327Parser.parseDtcList("470201714035\r\r>"))
    }

    @Test fun twoEcusHeadersOff() {
        // engine 7E8: P0420; TCM 7E9: P0700 + U0100
        val raw = "43010420\r43020700C1000000\r\r>"
        assertEquals(listOf("P0420", "P0700", "U0100"), Elm327Parser.parseDtcList(raw))
    }

    @Test fun headersOnSingleFrames() {
        val raw = "7E80443010420\r7E9044301C123\r\r>"
        assertEquals(listOf("P0420", "U0123"), Elm327Parser.parseDtcList(raw))
    }

    @Test fun dataBytesThatLookLikeMarkersAreNotMarkers() {
        // P0443 contains "43" in the data; old string search restarted there
        assertEquals(listOf("P0443", "P0147"), Elm327Parser.parseDtcList("430204430147\r\r>"))
    }
}
