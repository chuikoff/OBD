package com.chuikoff.obd

import com.chuikoff.obd.elm327.Elm327Client
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327ClientIoTest {
    private fun ok(c: String): String = when {
        c == "ATZ" -> "\r\rELM327 v1.5\r\r>"
        c.startsWith("AT") -> "OK\r\r>"
        c == "03" -> "4300\r\r>"
        else -> "410D00\r\r>"
    }

    @Test fun lateTailIsDrainedWithoutAnyWrite() = runBlocking {
        val t = ScriptedElm(latencyFor = { if (it == "0105") 200L else 0L }) { ok(it) }
        val c = Elm327Client(t)
        c.ensureInitialized()
        c.send("0105", timeoutMs = 50)   // tail comes later → dirty
        val mark = t.sent.size
        c.send("010D")
        assertEquals(listOf("010D"), t.sentAfter(mark))
    }

    @Test fun silentBusyElmIsInterruptedWithSpaceNeverBareCr() = runBlocking {
        var busyOnce = true
        val t = ScriptedElm { cmd -> if (cmd == "0105" && busyOnce) { busyOnce = false; "" } else ok(cmd) }
        val c = Elm327Client(t)
        c.ensureInitialized()
        c.send("0105", timeoutMs = 50)   // ELM busy, no prompt
        val mark = t.sent.size
        val r = c.send("010D")
        assertEquals(listOf(" ", "010D"), t.sentAfter(mark))
        assertTrue(r.contains("410D"))
        assertFalse(t.sent.any { it.isEmpty() }) // a bare CR would repeat the last command
    }

    @Test fun unchangedAtSettingsAreSkipped() = runBlocking {
        val t = ScriptedElm { ok(it) }
        val c = Elm327Client(t)
        c.ensureInitialized()
        val pre = listOf("ATAT1", "ATSH7E1", "ATFCSH7E1", "ATFCSD30080A", "ATFCSM1", "ATCRA7E9")
        pre.forEach { c.send(it) }
        c.send("2103")
        val mark = t.sent.size
        pre.forEach { c.send(it) }      // CVT-only tick: same addressing again
        c.send("2103")
        assertEquals(listOf("2103"), t.sentAfter(mark))
    }

    @Test fun cacheForgetsOnAtzAtarAndResync() = runBlocking {
        val t = ScriptedElm(latencyFor = { if (it == "0105") 200L else 0L }) { ok(it) }
        val c = Elm327Client(t)
        c.ensureInitialized()
        c.send("ATSH7E1"); c.send("ATCRA7E9")
        c.send("ATAR")                       // clears the receive filter
        var mark = t.sent.size
        c.send("ATSH7E1"); c.send("ATCRA7E9")
        assertEquals(listOf("ATCRA7E9"), t.sentAfter(mark))

        c.send("ATZ")                        // reset → everything default again
        mark = t.sent.size
        c.send("ATSH7E1")
        assertEquals(listOf("ATSH7E1"), t.sentAfter(mark))

        c.send("0105", timeoutMs = 50)       // missing prompt → settings in doubt
        mark = t.sent.size
        c.send("ATSH7E1")
        assertEquals(listOf("ATSH7E1"), t.sentAfter(mark))
    }

    @Test fun standardBusUsesDefaultFlowControl() = runBlocking {
        val t = ScriptedElm { ok(it) }
        val c = Elm327Client(t)
        c.ensureInitialized()
        c.send("ATFCSH7E1"); c.send("ATFCSM1")
        val mark = t.sent.size
        com.chuikoff.obd.diag.DtcScanner(c) {}.quick()
        val after = t.sentAfter(mark)
        assertTrue(after.contains("ATFCSM0"))
        assertTrue(after.indexOf("ATFCSM0") < after.indexOf("03"))
    }
}
