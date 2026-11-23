package com.chuikoff.obd

import com.chuikoff.obd.diag.DiagModule
import com.chuikoff.obd.diag.DtcDecode
import com.chuikoff.obd.diag.DtcEntry
import com.chuikoff.obd.diag.DtcKind
import com.chuikoff.obd.diag.DtcScanner
import com.chuikoff.obd.diag.ModuleResult
import com.chuikoff.obd.diag.Tpms
import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.sensors.CretaAmbient
import com.chuikoff.obd.sensors.EngineExtras
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixes from the Open Code Review pass (1.15.2). */
class Review1152Test {

    private fun bcm7a0() = ModuleResult(DiagModule("Кузов", "7A0", "7A8"), true, "UDS 19 02",
        listOf(DtcEntry("C1313", "7A8", "x", DtcKind.UDS, 0x89)))

    private fun clearWith(replies: (Int) -> String): Pair<Boolean, String?> = runBlocking {
        var header = "7E0"
        var n = 0
        val t = ScriptedElm { c ->
            when {
                c == "ATZ" -> "\r\rELM327 v1.5\r\r>"
                c.startsWith("ATSH") -> { header = c.removePrefix("ATSH"); "OK\r\r>" }
                c.startsWith("AT") -> "OK\r\r>"
                header == "7A0" && c == "14FFFFFF" -> replies(n++)
                header == "7A0" && c == "1902FF" -> "5902FF\r\r>"
                else -> "NO DATA\r\r>"
            }
        }
        val c = Elm327Client(t); c.ensureInitialized()
        val out = DtcScanner(c) {}.clear(listOf(bcm7a0())).single()
        out.accepted to out.reasonRu
    }

    @Test fun clearPendingThenPositiveIsAccepted() {
        val (ok, _) = clearWith { i -> if (i == 0) "7F1478\r\r>" else "54\r\r>" }
        assertTrue(ok)
    }

    @Test fun clearPendingForeverIsNotAccepted() {
        val (ok, why) = clearWith { "7F1478\r\r>" }
        assertFalse(ok)
        assertTrue(why!!.contains("78"))
    }

    @Test fun clearPendingThenNegativeIsNotAccepted() {
        val (ok, _) = clearWith { i -> if (i == 0) "7F1478\r\r>" else "7F1422\r\r>" }
        assertFalse(ok)
    }

    @Test fun mergeKeepsSameCodeFromTwoEcusAndDropsAnonymousDuplicate() {
        val a = DtcEntry("P0700", "7E8", "Двигатель", DtcKind.STORED)
        val b = DtcEntry("P0700", "7E9", "АКПП", DtcKind.STORED)
        val anon = DtcEntry("P0700", null, "?", DtcKind.STORED)
        val dupe = a.copy(ecuName = "Двигатель (7E8)")
        val other = DtcEntry("P0420", null, "?", DtcKind.STORED)
        val m = DtcDecode.mergeObdCodes(listOf(dupe, a, b, anon, other))
        assertEquals(listOf("P0700" to "7E8", "P0700" to "7E9", "P0420" to null), m.map { it.code to it.ecuId })
    }

    @Test fun outlanderModuleTableIsMitsubishi() {
        val tx = DtcDecode.OUTLANDER_MODULES.map { it.tx }
        assertTrue(tx.containsAll(listOf("7E0", "7E1", "784", "7B6", "743", "773", "600", "688", "6A0")))
        assertFalse("7D2" in tx) // Hyundai SRS id is not in the Outlander table
        assertEquals("500", DtcDecode.OUTLANDER_MODULES.first { it.tx == "600" }.rx)
    }

    @Test fun mitsubishiTpmsParsers() {
        // 600 21 23: bix 64 → byte 8 after «61 23» = 0xAF → 35 psi = 2.41 bar, byte 9 = 0x46 → 20 °C
        val w = Tpms.parseMitsuWheel("00C\r0:61230000000000\r1:000000AF460000\r\r>", 0x23, "ПЛ")!!
        assertEquals(35.0 * Tpms.PSI_TO_BAR, w.bar!!, 1e-9)
        assertEquals(20, w.tempC)
        // 743 22 0E26: X/4 psi
        val b = Tpms.parseMitsuBcm("620E268C\r\r>", 0x0E26, "ПЛ")!!
        assertEquals(35.0 * Tpms.PSI_TO_BAR, b.bar!!, 1e-9)
    }

    @Test fun outlanderFullScanUsesItsTableAndMitsubishiTpms() = runBlocking {
        var header = "7E0"
        val sent = mutableListOf<Pair<String, String>>()
        val t = ScriptedElm { c ->
            if (!c.startsWith("AT")) sent += header to c
            when {
                c == "ATZ" -> "\r\rELM327 v1.5\r\r>"
                c.startsWith("ATSH") -> { header = c.removePrefix("ATSH"); "OK\r\r>" }
                c.startsWith("AT") -> "OK\r\r>"
                header == "7DF" && c == "0101" -> "41010007E500\r\r>"
                header == "600" && c == "2123" -> "00C\r0:61230000000000\r1:000000AF460000\r\r>"
                else -> "NO DATA\r\r>"
            }
        }
        val c = Elm327Client(t); c.ensureInitialized()
        val rep = DtcScanner(c) {}.full(DtcDecode.OUTLANDER_MODULES, outlander = true)
        assertTrue(rep.full)
        assertEquals(DtcDecode.OUTLANDER_MODULES.map { it.tx }, rep.modules!!.map { it.module.tx })
        assertFalse(sent.any { it.second == "22C00B" }) // no Hyundai TPMS request
        val tp = rep.tpms.single()
        assertTrue(tp.sourceRu.startsWith("ETACS 600"))
        assertNotNull(tp.wheels.first { it.pos == "ПЛ" }.bar)
    }

    @Test fun cretaClimateIsProbedAgainAfterAFailure() {
        CretaAmbient.reset()
        assertTrue(CretaAmbient.shouldAskClimate(0))
        CretaAmbient.mark(false, 1_000)
        assertFalse(CretaAmbient.shouldAskClimate(30_000))
        assertTrue(CretaAmbient.shouldAskClimate(1_000 + CretaAmbient.REPROBE_EVERY_MS))
        CretaAmbient.mark(true, 70_000)
        assertTrue(CretaAmbient.shouldAskClimate(70_001))
        CretaAmbient.reset()
    }

    @Test fun hiddenFuelCardIsStillPolledOthersAreNot() {
        val x = EngineExtras()
        x.reset(setOf(0x0C, 0x0D, 0x10))
        x.userHidden = setOf(EngineExtras.ID_FUEL, EngineExtras.ID_RPM)
        val ids = x.sensors().map { it.id }
        assertTrue(EngineExtras.ID_FUEL in ids)
        assertFalse(EngineExtras.ID_RPM in ids)
    }
}
