package com.chuikoff.obd

import com.chuikoff.obd.diag.DiagLog
import com.chuikoff.obd.diag.DiagLog.toText
import com.chuikoff.obd.diag.DiagSweep
import com.chuikoff.obd.elm327.Elm327Client
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagLogTest {
    @Test fun everyElmExchangeIsRecordedIncludingTimeouts() = runBlocking {
        DiagLog.clear()
        val t = ScriptedElm { c ->
            when {
                c == "ATZ" -> "\r\rELM327 v1.5\r\r>"
                c.startsWith("AT") -> "OK\r\r>"
                c == "21A0" -> "" // busy, never prompts
                else -> "7F2112\r\r>"
            }
        }
        val c = Elm327Client(t)
        c.ensureInitialized()
        c.send("2101")
        c.send("21A0", timeoutMs = 50)
        val log = DiagLog.trafficSnapshot().map { it.toText() }
        assertTrue(log.toString(), log.any { it.contains("2101 → 7F2112") })
        assertTrue(log.toString(), log.any { it.contains("21A0") && it.contains("ТАЙМАУТ") })
    }

    @Test fun gearboxSweepAsksBothEcusAndRestoresEngine() = runBlocking {
        val t = ScriptedElm { c -> if (c == "ATZ") "\r\rELM327 v1.5\r\r>" else if (c.startsWith("AT")) "OK\r\r>" else "NO DATA\r\r>" }
        val c = Elm327Client(t)
        c.ensureInitialized()
        val lines = DiagSweep(c).gearbox()
        assertTrue(lines.any { it.label == "АКПП@7E1" && it.cmd == "2105" })
        assertTrue(lines.any { it.label == "АКПП@7E0" && it.cmd == "21A3" })
        assertTrue(lines.any { it.cmd == "222081" })
        assertTrue(t.sent.last { it.startsWith("ATSH") } == "ATSH7E0")
        assertTrue(t.sent.count { it == "ATZ" } == 1)
    }
}
