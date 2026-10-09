package com.chuikoff.obd

import com.chuikoff.obd.elm327.Elm327Parser
import com.chuikoff.obd.sensors.CvtFormula
import com.chuikoff.obd.vehicle.OutlanderGfGgProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds realistic ELM327 text through the real parser + Outlander decoder.
 *
 * R1/R2/R3 below are the *reconstructed* raw ELM replies behind the user's screenshots.
 * The bytes the old 1.6.0 `clean()` swallowed (shown as `40`, `05`, line 1 of R2) are
 * unknowable — any byte whose hex is two decimal digits reproduces the screenshot exactly;
 * the values chosen are physically plausible (K torque ratio ≈1.0 → 0x40, D slip 0x05).
 */
class CvtParsingTest {

    // ATS0 / ATH0 / CAF1 multi-frame format, \r line endings, prompt
    private val r1 = "014\r0:6103262126FF\r1:38FD00FCFCEC40\r2:3400852B2A006C\r\r>"
    private val r2 = "014\r0:61032419251C\r1:44003000006040\r2:1300852020006C\r\r>"
    private val r3 = "014\r0:61033E2E3F05\r1:43016414151A40\r2:2500853B3B006C\r\r>"
    private val awc = "00C\r0:610D0100048E\r1:00187B777006FF\r\r>"

    /** Exact copy of the 1.6.0 clean() — to prove it reproduces the screenshots. */
    private fun oldClean(raw: String): String {
        var s = raw.replace("SEARCHING...", "", ignoreCase = true)
            .replace("\r", "").replace("\n", "").replace(">", "").replace(" ", "")
            .trim().uppercase()
        s = s.replace(Regex("""\d+:"""), "")
        return s
    }

    @Test fun oldCleanReproducesUserScreenshots() {
        assertEquals("6103262126FF38FD00FCFCEC3400852B2A006C", oldClean(r1))
        assertEquals("61032419251C1300852020006C", oldClean(r2))
        assertEquals("61033E2E3F43016414151A2500853B3B006C", oldClean(r3))
        assertEquals("00C610D0100048E00187B777006FF", oldClean(awc))
    }

    @Test fun newCleanKeepsAllBytes() {
        assertEquals("6103262126FF38FD00FCFCEC403400852B2A006C", Elm327Parser.clean(r1))
        assertEquals("61033E2E3F0543016414151A402500853B3B006C", Elm327Parser.clean(r3))
    }

    @Test fun reassemblesFullFrames() {
        for (raw in listOf(r1, r2, r3)) {
            val f = Elm327Parser.parseMode21Frame(raw, 0x03)
            assertNotNull(f); f!!
            assertTrue(f.complete)
            assertEquals(20, f.message.declaredLength)
            assertEquals(18, f.payload.size)
            assertEquals(0x85, f.payload[13].toInt() and 0xFF) // Torque letter N
            assertNull(Elm327Parser.validateMode21Response(raw, 0x03))
        }
    }

    @Test fun decodeNIsStableAcrossFrames() {
        for (raw in listOf(r1, r2, r3)) {
            val p = Elm327Parser.parseMode21PayloadHardened(raw, 0x03, minDataBytes = 14)!!
            val poly = OutlanderGfGgProfile.decodeCvtCelsius(p, CvtFormula.OUT3_POLY)!!
            val cvt8 = OutlanderGfGgProfile.decodeCvtCelsius(p, CvtFormula.CVT8_MINUS_55)!!
            assertEquals(65.0, poly, 0.2)
            assertEquals(78.0, cvt8, 0.001)
        }
    }

    @Test fun otherFieldsAreConsistent() {
        // primary/secondary speed vs speed ratio E/100 — sanity check of byte alignment
        val p = Elm327Parser.parseMode21Frame(r3, 0x03)!!.payload
        fun u(i: Int) = p[i].toInt() and 0xFF
        val prim = (u(0) - 2) * 32.0
        val sec = (u(1) - 2) * 64.0
        assertEquals(u(4) / 100.0, prim / sec, 0.02)
    }

    @Test fun spacesAndCrLf() {
        val raw = "2103\r\n014\r\n0: 61 03 26 21 26 FF \r\n1: 38 FD 00 FC FC EC 40 \r\n2: 34 00 85 2B 2A 00 6C \r\n\r\n>"
        val f = Elm327Parser.parseMode21Frame(raw, 0x03)!!
        assertTrue(f.complete)
        assertEquals(0x85, f.payload[13].toInt() and 0xFF)
    }

    @Test fun searchingPrefix() {
        val f = Elm327Parser.parseMode21Frame("SEARCHING...\r$r1", 0x03)!!
        assertTrue(f.complete)
        assertEquals(0x85, f.payload[13].toInt() and 0xFF)
    }

    @Test fun headersOnCanFrames() {
        val raw = "7E9 10 14 61 03 26 21 26 FF\r7E9 21 38 FD 00 FC FC EC 40\r7E9 22 34 00 85 2B 2A 00 6C\r\r>"
        val f = Elm327Parser.parseMode21Frame(raw, 0x03)!!
        assertTrue(f.complete)
        assertEquals(18, f.payload.size)
        assertEquals(0x85, f.payload[13].toInt() and 0xFF)
        // same without spaces (ATS0)
        val raw2 = "7E910146103262126FF\r7E92138FD00FCFCEC40\r7E9223400852B2A006C\r>"
        assertEquals(0x85, Elm327Parser.parseMode21Frame(raw2, 0x03)!!.payload[13].toInt() and 0xFF)
    }

    @Test fun headersOnWithPadding() {
        val raw = "7E9 10 0E 61 03 01 02 03 04\r7E9 21 05 06 07 08 09 0A 0B\r7E9 22 0C AA AA AA AA AA AA\r>"
        val f = Elm327Parser.parseMode21Frame(raw, 0x03)!!
        assertEquals(12, f.payload.size)
        assertEquals(0x0C, f.payload[11].toInt())
    }

    @Test fun lostMiddleFrameIsRejected() {
        val raw = "014\r0:6103262126FF\r2:3400852B2A006C\r\r>"
        val f = Elm327Parser.parseMode21Frame(raw, 0x03)!!
        assertFalse(f.positionsTrustworthy)
        assertNull(Elm327Parser.parseMode21PayloadHardened(raw, 0x03, minDataBytes = 14))
        assertNotNull(Elm327Parser.validateMode21Response(raw, 0x03))
    }

    @Test fun truncatedTailBeforeNIsRejected() {
        val raw = "014\r0:6103262126FF\r1:38FD00FCFCEC40\r\r>"
        assertNull(Elm327Parser.parseMode21PayloadHardened(raw, 0x03, minDataBytes = 14))
    }

    @Test fun legitimate41And61BytesInsidePayloadAreKept() {
        // Old parser truncated/rejected frames containing 0x41/0x61 data bytes
        val raw = "014\r0:610341612641\r1:38FD00FCFCEC40\r2:3400852B2A006C\r\r>"
        val p = Elm327Parser.parseMode21PayloadHardened(raw, 0x03, minDataBytes = 14)!!
        assertEquals(18, p.size)
        assertEquals(0x85, p[13].toInt() and 0xFF)
    }

    @Test fun noDataAndNegativeResponse() {
        assertNull(Elm327Parser.parseMode21Frame("NO DATA\r\r>", 0x03))
        assertNotNull(Elm327Parser.validateMode21Response("NO DATA\r\r>", 0x03))
        val nrc = Elm327Parser.validateMode21Response("7F2112\r\r>", 0x03)!!
        assertTrue(nrc, nrc.contains("7F"))
    }

    @Test fun twoEcusOnlyMatchingMessageUsed() {
        val raw = "41057D\r014\r0:6103262126FF\r1:38FD00FCFCEC40\r2:3400852B2A006C\r\r>"
        assertEquals(0x85, Elm327Parser.parseMode21Frame(raw, 0x03)!!.payload[13].toInt() and 0xFF)
    }

    @Test fun mode01AndAwcStillWork() {
        assertEquals(0x7D, Elm327Parser.parseMode01Payload("41057D\r\r>", 0x05)!![0].toInt())
        val p = Elm327Parser.parseMode21PayloadHardened(awc, 0x0D, minDataBytes = 5)!!
        assertEquals(10, p.size) // 0x00C = 12 incl. 61 0D; trailing FF padding trimmed
        val i = p[8].toInt() and 0xFF
        assertEquals(16.0, i / 2.0 - 40.0, 0.01)
    }

    @Test fun dtcMultiFrameNotCorrupted() {
        // 43 03 01 33 02 40 05 00 … (old regex ate "40" before "1:")
        val raw = "00A\r0:43040133024005\r1:00011100000000\r\r>"
        assertEquals("43040133024005000111", Elm327Parser.hexDigits(raw))
        assertEquals("4304013302400500011100000000", Elm327Parser.clean(raw))
        // old regex: "…0240051:" is all decimal digits → whole first frame swallowed
        assertEquals("00A00011100000000", oldClean(raw))
    }
}
