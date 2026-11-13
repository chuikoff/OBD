package com.chuikoff.obd

import com.chuikoff.obd.diag.DiagModule
import com.chuikoff.obd.diag.DtcDecode
import com.chuikoff.obd.diag.DtcKind
import com.chuikoff.obd.diag.DtcLogLine
import com.chuikoff.obd.diag.DtcScanner
import com.chuikoff.obd.elm327.Elm327Client
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Realistic Hyundai (Creta / Solaris era, 11-bit CAN 500k) replies as an ELM327 prints them. */
class DtcScanTest {
    private val abs = DiagModule("ABS / ESC", "7D1", "7D9")

    @Test fun headersOnAttributesCodesToEcus() {
        // ATH1, ATS0: engine 7E8 has P0133 + P0134, TCM 7E9 answers «no codes» (padding AA)
        val raw = "7E806430201330134AA\r7E9024300AAAAAAAAAA\r\r>"
        val codes = DtcDecode.parseObd(raw, DtcKind.STORED)
        assertEquals(listOf("P0133", "P0134"), codes.map { it.code })
        assertTrue(codes.all { it.ecuId == "7E8" && it.ecuName.startsWith("Двигатель") })
    }

    @Test fun headersOnMultiFrameMode03() {
        val raw = "7E8100A430401330240\r7E82105000111AAAAAA\r\r>"
        assertEquals(listOf("P0133", "P0240", "P0500", "P0111"), DtcDecode.parseObd(raw, DtcKind.STORED).map { it.code })
    }

    @Test fun permanentMode0A() {
        assertEquals(listOf("P0420"), DtcDecode.parseObd("7E8044A010420AAAAAA\r\r>", DtcKind.PERMANENT).map { it.code })
    }

    @Test fun milStatus() {
        val mil = DtcDecode.parseMil("7E8064101810765E5AA\r7E906410100040000AA\r\r>")
        assertEquals(2, mil.size)
        assertTrue(mil[0].milOn); assertEquals(1, mil[0].dtcCount)
        assertFalse(mil[1].milOn)
    }

    @Test fun uds1902MultiFrameWithStatusFilter() {
        // 59 02 FF | 56 11 00 09 (C1611 active+confirmed) | 50 01 00 50 (inactive) | 96 02 00 08 (B1602 stored)
        val raw = "00F\r0:5902FF561100\r1:09500100509602\r2:0008AAAAAAAAAA\r\r>"
        val r = DtcDecode.parseUds1902(raw, abs) as DtcDecode.Reply.Codes
        assertEquals(listOf("C1611", "B1602"), r.codes.map { it.code })
        assertEquals(1, r.ignored)
        assertTrue(r.codes[0].statusRu!!.contains("активна"))
        assertEquals("7D9", r.codes[0].ecuId)
    }

    @Test fun udsFtbSuffixAndNegative() {
        val r = DtcDecode.parseUds1902("5902FFC1610109\r\r>", abs) as DtcDecode.Reply.Codes
        assertEquals("U0161-01", r.codes.single().code)
        assertEquals(DtcDecode.Reply.Negative(0x11), DtcDecode.parseUds1902("7F1911\r\r>", abs))
        assertEquals(DtcDecode.Reply.Pending, DtcDecode.parseUds1902("7F1978\r\r>", abs))
        assertEquals(DtcDecode.Reply.None, DtcDecode.parseUds1902("NO DATA\r\r>", abs))
        // response-pending then the real answer in the same read
        val ok = DtcDecode.parseUds1902("7F1978\r59024C\r\r>", abs)
        assertTrue(ok is DtcDecode.Reply.Codes)
    }

    @Test fun moduleSweepCoversHyundaiRanges() {
        val ids = DtcDecode.HYUNDAI_MODULES.map { it.tx }
        assertEquals(ids.size, ids.toSet().size)
        listOf("7E0", "7E1", "7D1", "7D2", "7D4", "7C6", "7C4", "7A0", "770", "7B3", "7D6", "7A3", "775").forEach {
            assertTrue(it, it in ids)
        }
        assertTrue(DtcDecode.HYUNDAI_MODULES.all { it.rx.toInt(16) == it.tx.toInt(16) + 8 })
    }

    @Test fun kwp17() {
        val r = DtcDecode.parseKwp("5701433501\r\r>", abs, 0x17) as DtcDecode.Reply.Codes
        assertEquals("C0335", r.codes.single().code)
    }

    @Test fun kwp18() {
        val r = DtcDecode.parseKwp18("58019602E0\r\r>", abs) as DtcDecode.Reply.Codes
        assertEquals("B1602", r.codes.single().code)
        assertEquals(DtcKind.KWP, r.codes.single().kind)
    }

    @Test fun fullScanFindsAbsAndAirbagAndRestoresBus() = runBlocking {
        var header = "7E0"
        var headersOn = false
        val t = ScriptedElm { c ->
            when {
                c == "ATZ" -> "\r\rELM327 v1.5\r\r>"
                c == "ATH1" -> { headersOn = true; "OK\r\r>" }
                c == "ATH0" -> { headersOn = false; "OK\r\r>" }
                c.startsWith("ATSH") -> { header = c.removePrefix("ATSH"); "OK\r\r>" }
                c.startsWith("AT") -> "OK\r\r>"
                header == "7DF" && c == "0101" -> if (headersOn) "7E80641010007E500AA\r\r>" else "41010007E500\r\r>"
                header == "7DF" && c == "03" -> if (headersOn) "7E8024300AAAAAAAAAA\r\r>" else "4300\r\r>"
                header == "7DF" && c == "07" -> if (headersOn) "7E8024700AAAAAAAAAA\r\r>" else "4700\r\r>"
                header == "7DF" && c == "0A" -> "NO DATA\r\r>"
                header == "7E0" && c == "1902FF" -> "7F1911\r\r>"
                header == "7E0" && c == "1802FF00" -> "5800\r\r>"
                header == "7D1" && c == "1902FF" -> "00F\r0:5902FF561100\r1:09500100509602\r2:0008AAAAAAAAAA\r\r>"
                header == "7D2" && c == "1902FF" -> "7F1911\r\r>"
                header == "7D2" && c == "1802FF00" -> "58019602E0\r\r>"
                else -> "NO DATA\r\r>"
            }
        }
        val c = Elm327Client(t)
        c.ensureInitialized()
        val atz = t.sent.count { it == "ATZ" }
        val log = mutableListOf<DtcLogLine>()
        val rep = DtcScanner(c) { log += it }.full()
        // dozens of NO DATA from silent addresses must not trigger ATZ recovery mid-scan
        assertEquals(atz, t.sent.count { it == "ATZ" })
        // rejected 19 02 FF → masks 08/09 tried, then KWP
        assertTrue(log.any { it.request == "190209" && it.module!!.contains("7D2") })
        assertTrue(log.any { it.request == "== ИТОГ" && it.module!!.contains("7C6") && it.rawVisible == "не ответил" })
        assertEquals(false, rep.milOn)
        assertTrue(rep.obdCodes.isEmpty())
        val mods = rep.modules!!.associateBy { it.module.tx }
        assertEquals(listOf("C1611", "B1602"), mods.getValue("7D1").codes.map { it.code })
        assertEquals(listOf("B1602"), mods.getValue("7D2").codes.map { it.code })
        assertTrue(mods.getValue("7E0").answered && mods.getValue("7E0").codes.isEmpty())
        assertFalse(mods.getValue("7C6").answered)
        assertEquals(3, rep.allCodes.size)
        assertTrue(rep.summaryRu().contains("Найдено кодов: 2")) // distinct codes
        // every request logged with its raw reply
        assertTrue(log.any { it.request == "1902FF" && it.module!!.contains("7D1") && it.rawVisible.contains("5902FF") })
        assertTrue(log.any { it.request == "03" })
        // bus restored for polling: headers off, engine addressing last
        assertFalse(headersOn)
        assertEquals("7E0", header)
        assertEquals("ATH0", t.sent.last { it == "ATH0" || it == "ATH1" })
    }

    @Test fun milOnWithoutCodesPointsToFullCheck() = runBlocking {
        val t = ScriptedElm { c ->
            when {
                c == "ATZ" -> "\r\rELM327 v1.5\r\r>"
                c.startsWith("AT") -> "OK\r\r>"
                c == "0101" -> "7E80641018007E500AA\r\r>"
                c == "03" -> "7E8024300AAAAAAAAAA\r\r>"
                else -> "NO DATA\r\r>"
            }
        }
        val c = Elm327Client(t); c.ensureInitialized()
        val rep = DtcScanner(c) {}.quick()
        assertEquals(true, rep.milOn)
        assertNull(rep.modules)
        assertTrue(rep.summaryRu(), rep.summaryRu().contains("Полная проверка"))
        assertTrue(rep.summaryRu().contains("ГОРИТ"))
    }
}
