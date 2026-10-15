package com.chuikoff.obd

import com.chuikoff.obd.sensors.CvtLockupDetector
import com.chuikoff.obd.sensors.CvtLockupDetector.State
import com.chuikoff.obd.vehicle.OutlanderGfGgProfile
import org.junit.Assert.*
import org.junit.Test

class CvtLockupTest {

    private fun s(primary: Int, engine: Int, secondary: Int = 1000) =
        CvtLockupDetector.Sample(primary, secondary, engine)

    /** Inverse of (A-2)*32 / C*32 for building a synthetic 2103 payload. */
    private fun payload(primary: Int, secondary: Int, engine: Int, d: Int = 0): ByteArray {
        val a = primary / 32 + 2
        val b = secondary / 64 + 2
        val c = engine / 32
        return ByteArray(18) { 0 }.also {
            it[0] = a.toByte(); it[1] = b.toByte(); it[2] = c.toByte(); it[3] = d.toByte()
            it[13] = 0x85.toByte() // plausible N
        }
    }

    @Test fun decodesUserSamples() {
        // «перв 1152 / двиг 1248» → slip 96
        val p = payload(1152, 1920, 1248)
        val sample = CvtLockupDetector.sampleFrom2103(p)!!
        assertEquals(1152, sample.primaryRpm)
        assertEquals(1248, sample.engineRpm)
        assertEquals(96, sample.slip)
        // «перв 1248 / двиг 1920» → slip 672
        assertEquals(672, CvtLockupDetector.sampleFrom2103(payload(1248, 2560, 1920))!!.slip)
        // idle «перв 0 / двиг 672»
        val idle = CvtLockupDetector.sampleFrom2103(payload(0, 0, 672))!!
        assertEquals(0, idle.primaryRpm); assertEquals(672, idle.engineRpm)
        // scales match describe2103
        val desc = OutlanderGfGgProfile.describe2103(payload(1152, 1920, 1248))
        assertTrue(desc, desc.contains("перв 1152") && desc.contains("двиг 1248") && desc.contains("slip 96"))
    }

    @Test fun userSamplesAreUnlockedOrIdle() {
        val d = CvtLockupDetector()
        assertEquals(State.UNLOCKED, d.update(s(1152, 1248)).state) // slip 96 → unlock band / unlock
        assertEquals(State.UNLOCKED, d.update(s(1248, 1920)).state) // slip 672
        assertEquals(State.IDLE, d.update(s(0, 672)).state)
        assertEquals("выкл (стоим)", d.update(s(0, 672)).state.labelRu)
    }

    @Test fun locksAfterOneTightOrTwoBorderPolls() {
        val d = CvtLockupDetector()
        // |slip|=0 ≤32 → вкл on the first poll
        val r = d.update(s(1856, 1856))
        assertEquals(State.LOCKED, r.state)
        assertEquals("Блокировка ГТ: вкл", r.cardLine)
        assertTrue(r.techLine.contains("slip 0"))
        assertTrue(r.techLine.contains("по проскальзыванию"))
        // stays locked on residual slip within tight band
        assertEquals(State.LOCKED, d.update(s(1856, 1856 + 32)).state)

        // Borderline |slip|=64 needs 2 polls after unlock
        d.reset()
        assertEquals(State.UNKNOWN, d.update(s(1856, 1856 + 64)).state) // 1st border — not yet
        assertEquals(State.LOCKED, d.update(s(1856, 1856 + 64)).state)
    }

    @Test fun hysteresisUnlockRequiresLargeSlip() {
        val d = CvtLockupDetector()
        repeat(2) { d.update(s(1856, 1856)) }
        assertEquals(State.LOCKED, d.update(s(1856, 1856)).state)
        // slip 96 — between 64 and 160: keep LOCKED
        assertEquals(State.LOCKED, d.update(s(1152, 1248)).state)
        // slip 672 — unlock
        assertEquals(State.UNLOCKED, d.update(s(1248, 1920)).state)
        // back to low slip (|slip|=0 ≤32): locks on the first poll
        assertEquals(State.LOCKED, d.update(s(1856, 1856)).state)
    }

    @Test fun invalidFrameIsUnknown() {
        val d = CvtLockupDetector()
        repeat(2) { d.update(s(1856, 1856)) }
        assertEquals(State.UNKNOWN, d.update(null).state)
        assertEquals("Блокировка ГТ: —", d.update(null).cardLine)
        assertNull(CvtLockupDetector.sampleFrom2103(ByteArray(2)))
    }

    @Test fun engineBelow900DoesNotLock() {
        val d = CvtLockupDetector()
        // slip 0 but engine 896
        assertEquals(State.UNLOCKED, d.update(s(896, 896)).state)
        assertEquals(State.UNLOCKED, d.update(s(896, 896)).state)
    }
}
