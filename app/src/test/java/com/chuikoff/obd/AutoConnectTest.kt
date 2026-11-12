package com.chuikoff.obd

import com.chuikoff.obd.connection.AutoConnectPolicy
import com.chuikoff.obd.connection.AutoTrigger
import com.chuikoff.obd.connection.IgnitionWatch
import com.chuikoff.obd.connection.LastAdapter
import com.chuikoff.obd.connection.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectTest {
    private val last = LastAdapter("00:11:22:33:44:55", "OBDII", TransportType.CLASSIC_SPP)
    private fun input(
        trigger: AutoTrigger = AutoTrigger.APP_START, enabled: Boolean = true, bt: Boolean = true,
        last: LastAdapter? = this.last, disconnected: Boolean = true, perm: Boolean = true,
        manual: Long? = null, lastTry: Long? = null, now: Long = 10_000_000L
    ) = AutoConnectPolicy.Input(enabled, bt, trigger, last, disconnected, perm, manual, lastTry, now)

    @Test fun goesWhenEverythingFits() {
        assertNull(AutoConnectPolicy.skipReason(input()))
        assertNull(AutoConnectPolicy.skipReason(input(AutoTrigger.ANDROID_AUTO)))
        assertNull(AutoConnectPolicy.skipReason(input(AutoTrigger.BLUETOOTH)))
    }

    @Test fun skips() {
        assertNotNull(AutoConnectPolicy.skipReason(input(enabled = false)))
        assertNotNull(AutoConnectPolicy.skipReason(input(last = null)))
        assertNotNull(AutoConnectPolicy.skipReason(input(perm = false)))
        assertNotNull(AutoConnectPolicy.skipReason(input(disconnected = false)))
        assertNotNull(AutoConnectPolicy.skipReason(input(AutoTrigger.BLUETOOTH, bt = false)))
        // BT toggle only affects BT events
        assertNull(AutoConnectPolicy.skipReason(input(AutoTrigger.APP_START, bt = false)))
    }

    @Test fun manualDisconnectQuietsOnlyBluetoothEvents() {
        val now = 10_000_000L
        assertNotNull(AutoConnectPolicy.skipReason(input(AutoTrigger.BLUETOOTH, manual = now - 60_000)))
        assertNull(AutoConnectPolicy.skipReason(input(AutoTrigger.ANDROID_AUTO, manual = now - 60_000)))
        assertNull(AutoConnectPolicy.skipReason(input(AutoTrigger.BLUETOOTH, manual = now - AutoConnectPolicy.MANUAL_QUIET_MS)))
    }

    @Test fun debounce() {
        val now = 10_000_000L
        assertNotNull(AutoConnectPolicy.skipReason(input(lastTry = now - 5_000)))
        assertNull(AutoConnectPolicy.skipReason(input(lastTry = now - AutoConnectPolicy.DEBOUNCE_MS)))
    }

    @Test fun relevantBluetoothDevices() {
        assertTrue(AutoConnectPolicy.relevantDevice("00:11:22:33:44:55", null, last))
        assertTrue(AutoConnectPolicy.relevantDevice("aa:bb", AutoConnectPolicy.CLASS_CAR_AUDIO, last))
        assertTrue(AutoConnectPolicy.relevantDevice("aa:bb", AutoConnectPolicy.CLASS_HANDSFREE, last))
        assertFalse(AutoConnectPolicy.relevantDevice("aa:bb", 0x0418 /* headphones */, last))
        assertFalse(AutoConnectPolicy.relevantDevice("aa:bb", null, last))
        assertFalse(AutoConnectPolicy.relevantDevice("00:11:22:33:44:55", null, null))
    }

    @Test fun ignitionWatch() {
        val w = IgnitionWatch(timeoutMs = 180_000)
        assertFalse(w.update(false, 0)) // not started
        w.start(0)
        assertFalse(w.update(false, 170_000))
        assertFalse(w.update(true, 175_000))
        assertFalse(w.update(false, 300_000))
        assertTrue(w.update(false, 355_000))
        w.stop()
        assertFalse(w.update(false, 999_000))
        assertEquals(Unit, w.start(1_000_000))
        assertTrue(w.update(false, 1_180_000))
    }
}
