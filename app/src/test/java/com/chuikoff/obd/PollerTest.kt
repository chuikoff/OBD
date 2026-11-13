package com.chuikoff.obd

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.polling.Poller
import com.chuikoff.obd.sensors.SensorReading
import com.chuikoff.obd.vehicle.OutlanderGfGgProfile
import com.chuikoff.obd.vehicle.VehicleProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollerTest {
    private val cvt = "014\r0:61033E2E3F05\r1:43016414151A40\r2:2500853B3B006C\r\r>"
    private val awc = "00C\r0:610D0100048E\r1:00187B777006FF\r\r>"

    private fun reply(c: String): String = when {
        c == "ATZ" -> "\r\rELM327 v1.5\r\r>"
        c.startsWith("AT") -> "OK\r\r>"
        c == "2103" -> cvt
        c == "0105" -> "41057D\r\r>"
        c == "210D" -> awc
        c == "010D" -> "410D00\r\r>"
        c == "03" -> "4300\r\r>"
        c == "07" -> "4700\r\r>"
        else -> "NO DATA\r\r>"
    }

    private class Host(val c: Elm327Client, val t: ScriptedElm, val reconnectOk: Boolean = true) : Poller.Host {
        override val client: Elm327Client get() = c
        override val profile: VehicleProfile = OutlanderGfGgProfile()
        override fun transportAlive() = t.isConnected.value
        var last: List<SensorReading> = emptyList()
        val published = mutableListOf<List<SensorReading>>()
        val failures = mutableListOf<String>()
        val reconnects = mutableListOf<Int>()
        var gaveUp: String? = null
        override fun previousReadings() = last
        override suspend fun process(client: Elm327Client, reading: SensorReading, outlander: Boolean) = reading
        override fun publish(readings: List<SensorReading>) { last = readings; published += readings }
        override fun tickFailed(message: String) { failures += message; last = emptyList() }
        override suspend fun reconnect(attempt: Int, reason: String): Boolean {
            reconnects += attempt
            if (reconnectOk) t.isConnected.value = true
            return reconnectOk
        }
        override fun reconnectGaveUp(reason: String) { gaveUp = reason }
        override fun pollingChanged(running: Boolean) {}
        override fun canResume() = true
    }

    private suspend fun waitFor(cond: () -> Boolean) = withTimeout(10_000) { while (!cond()) delay(2) }

    @Test fun cancelDuringCvtOnlyTickThenDtcReadIsClean() = runBlocking {
        val t = ScriptedElm(latencyFor = { if (it == "2103") 80L else 0L }) { reply(it) }
        val c = Elm327Client(t)
        c.ensureInitialized()
        val host = Host(c, t)
        val p = Poller(this, host)
        p.start()
        waitFor { t.sent.contains("2103") } // tick 1 = CVT only, reply still in flight
        val mark = t.sent.size

        val codes = p.paused(c) { com.chuikoff.obd.diag.DtcScanner(c) {}.quick().obdCodes }

        val after = t.sentAfter(mark)
        assertTrue("$codes", codes.isEmpty())
        // the late 2103 reply was drained: no bare CR (would repeat 2103) and no interrupt needed
        assertTrue("$after", after.none { it.isBlank() })
        // engine addressing restored first (tick left TCM 7E1), default flow control before Mode 03
        assertEquals("ATSH7E0", after.first())
        val fcsm0 = after.indexOf("ATFCSM0")
        assertTrue("$after", fcsm0 >= 0 && fcsm0 < after.indexOf("03"))
        // a pause is not an error: cards were not wiped
        assertTrue(host.failures.isEmpty())
        assertFalse(host.published.any { it.isEmpty() })
        assertTrue(p.isRunning) // resumed afterwards
        p.stop()
    }

    @Test fun deadLinkReconnectsEveryTime() = runBlocking {
        val t = ScriptedElm { reply(it) }
        val c = Elm327Client(t)
        c.ensureInitialized()
        val host = Host(c, t)
        val p = Poller(this, host, backoffMs = { 1L })
        p.start()
        repeat(2) { n ->
            waitFor { host.published.size > n * 10 + 2 }
            t.isConnected.value = false          // adapter lost power / BT dropped
            waitFor { host.reconnects.size == n + 1 }
            val before = t.sent.count { it == "2103" }
            waitFor { t.sent.count { it == "2103" } > before } // polling goes on
        }
        assertEquals(listOf(1, 1), host.reconnects) // no once-per-session limit
        p.stop()
    }

    @Test fun givesUpAfterMaxAttempts() = runBlocking {
        val t = ScriptedElm { reply(it) }
        val c = Elm327Client(t)
        c.ensureInitialized()
        val host = Host(c, t, reconnectOk = false)
        val p = Poller(this, host, backoffMs = { 1L })
        p.start()
        waitFor { host.published.isNotEmpty() }
        t.isConnected.value = false
        waitFor { host.gaveUp != null }
        assertNotNull(host.gaveUp)
        assertEquals((1..Poller.MAX_RECONNECT_ATTEMPTS).toList(), host.reconnects)
        assertFalse(p.isRunning)
        p.stop()
    }

    @Test fun tickSchedule() {
        val all = OutlanderGfGgProfile().sensors()
        fun ids(tick: Int) = Poller.sensorsForTick(all, tick, outlander = true).map { it.id }
        assertEquals(listOf("cvt_temp"), ids(1))
        assertEquals(listOf("cvt_temp", "coolant_temp"), ids(2))
        assertEquals(listOf("cvt_temp", "ambient_temp"), ids(3))
        assertEquals(listOf("cvt_temp", "coolant_temp", "ambient_temp"), ids(6))
        assertEquals(all.map { it.id }, Poller.sensorsForTick(all, 1, outlander = false).map { it.id })
    }
}
