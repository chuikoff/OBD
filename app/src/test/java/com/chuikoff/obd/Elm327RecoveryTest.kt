package com.chuikoff.obd

import com.chuikoff.obd.connection.ObdTransport
import com.chuikoff.obd.elm327.Elm327Client
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class Elm327RecoveryTest {
    private class Fake(val replyFor: (String) -> String) : ObdTransport {
        override val name = "fake"
        override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)
        val sent = mutableListOf<String>()
        private var last = ""
        override suspend fun connect(deviceAddress: String) {}
        override suspend fun disconnect() {}
        override suspend fun write(data: ByteArray) { last = String(data).trim(); sent += last }
        override suspend fun readUntilPrompt(timeoutMs: Long) = replyFor(last)
    }

    @Test fun linkErrorClassification() {
        assertTrue(Elm327Client.isLinkError("STOPPED\r\r>"))
        assertTrue(Elm327Client.isLinkError("BUFFER FULL\r>"))
        assertTrue(Elm327Client.isLinkError("CAN ERROR\r>"))
        assertTrue(Elm327Client.isLinkError("\r>"))
        assertFalse(Elm327Client.isLinkError("410D3C\r>"))
        assertTrue(Elm327Client.isNoData("NO DATA\r>"))
        assertEquals(3000L, Elm327Client.backoffMs(0))
        assertEquals(60000L, Elm327Client.backoffMs(9))
    }

    @Test fun streakOfStoppedTriggersReinit() = runBlocking {
        var broken = true
        val t = Fake { c -> if (c.startsWith("AT")) "OK\r>" else if (broken) "STOPPED\r>" else "410D3C\r>" }
        val c = Elm327Client(t)
        c.ensureInitialized()
        repeat(Elm327Client.BAD_STREAK_LIMIT) { c.send("0105") }
        val before = t.sent.count { it == "ATZ" }
        broken = false
        val r = c.send("010D") // streak reached → ATZ + init first
        assertEquals(before + 1, t.sent.count { it == "ATZ" })
        assertTrue(t.sent.contains("ATE0") && t.sent.contains("ATSP0"))
        assertTrue(r.contains("410D3C"))
        assertEquals(null, c.linkNote.value)
    }

    @Test fun missingPromptForcesResyncBeforeNextCommand() = runBlocking {
        var first = true
        val t = Fake { c -> if (c == "0105" && first) { first = false; "" } else "OK\r>" }
        val c = Elm327Client(t)
        c.ensureInitialized()
        c.send("0105")       // no prompt → dirty
        t.sent.clear()
        c.send("010D")
        assertEquals(listOf("", "010D"), t.sent) // lone CR flush first
    }
}
