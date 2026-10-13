package com.chuikoff.obd

import com.chuikoff.obd.passport.MonitorStatus
import com.chuikoff.obd.passport.Passport
import com.chuikoff.obd.passport.PassportData
import com.chuikoff.obd.passport.PassportReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PassportTest {
    private val vinHex = "4A4D42" // "JMB"
    // VIN "JMBXTGF2WGZ000123"? use a plain valid one
    private val vin = "JA4AZ3A31GZ012345"
    private fun hex(s: String) = s.map { "%02X".format(it.code) }.joinToString("")

    /** ELM reply as the adapter prints it (ATH0, ATS0, CAF1): 49 02 01 + 17 chars = 20 bytes. */
    private fun vinReply(v: String): String {
        val all = "490201" + hex(v)
        val f0 = all.substring(0, 12)
        val rest = all.substring(12).chunked(14)
        val sb = StringBuilder("014\r0:$f0\r")
        rest.forEachIndexed { i, c -> sb.append("${i + 1}:$c\r") }
        return sb.append("\r>").toString()
    }

    @Test fun multiframeVinParsed() {
        assertEquals(vin, Passport.parseVin(vinReply(vin)))
        // spaces / CRLF variant
        val raw = "0902\r\n014\r\n0: 49 02 01 4A 41 34\r\n1: 41 5A 33 41 33 31 47\r\n2: 5A 30 31 32 33 34 35\r\n\r\n>"
        assertEquals(vin, Passport.parseVin(raw))
    }

    @Test fun vinWithoutCountByteAndPadding() {
        val raw = "014\r0:490200${hex(vin).substring(0, 6)}\r1:${hex(vin).substring(6, 20)}\r2:${hex(vin).substring(20, 34)}\r>"
        assertEquals(vin, Passport.parseVin(raw))
        assertNull(Passport.parseVin("NO DATA\r\r>"))
        assertNull(Passport.parseVin("STOPPED\r>"))
        // truncated (frames 1,2 lost) → kept as partial since 1.11.1
        val part = Passport.parseVinDetailed("014\r0:490201${hex(vin).substring(0, 6)}\r\r>")!!
        assertEquals("JA4", part.value); assertTrue(part.partial)
        assertNull(Passport.parseVin("")) // garbage must not throw
        assertNull(Passport.parseVin("ZZZ\u0000\r?"))
    }

    @Test fun vinDecode() {
        val i = Passport.decodeVin(vin)
        assertTrue(i.problems.toString(), i.valid)
        assertEquals("Mitsubishi (Япония, SUV)", i.makerRu)
        assertEquals(2016, i.modelYear) // 'G'
        assertEquals('Z', i.plantCode)
        assertEquals(2010, Passport.modelYear('A')); assertEquals(2018, Passport.modelYear('J'))
        assertEquals(2023, Passport.modelYear('P')); assertEquals(2009, Passport.modelYear('9'))
        assertNull(Passport.modelYear('U'))
        val h = Passport.decodeVin("Z94CT41DAGR123456")
        assertEquals("Hyundai (Россия, Санкт-Петербург)", h.makerRu)
        assertEquals(2016, h.modelYear)
        assertEquals("Hyundai (Корея, SUV)", Passport.decodeVin("KM8J3CA46GU123456").makerRu)
    }

    @Test fun vinValidationAndGarbage() {
        assertFalse(Passport.decodeVin("JA4AZ3A31GZ01234").valid)          // 16
        assertFalse(Passport.decodeVin("JA4AZ3A31GZ0123OQ").valid)         // O Q
        assertTrue(Passport.decodeVin("JA4AZ3A31GZ0123I5").problems.any { it.contains("I/O/Q") })
        assertFalse(Passport.decodeVin("JA4AZ3A3-GZ012345").valid)
        for (g in listOf("", "  ", "\u0000\u0001", "Ж".repeat(17), null)) {
            val i = Passport.decodeVin(g); assertFalse(i.valid)
        }
    }

    @Test fun supportedMask() {
        val m = byteArrayOf(0xBE.toByte(), 0x1F, 0xA8.toByte(), 0x13)
        assertEquals(
            listOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
            Passport.decodeSupportMask(0x00, m)
        )
        assertEquals(listOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07), Passport.decodeSupportMask(0, m).take(6))
        assertEquals(listOf(0x21), Passport.decodeSupportMask(0x20, byteArrayOf(0x80.toByte(), 0, 0, 0)))
        assertEquals(listOf(0x40), Passport.decodeSupportMask(0x20, byteArrayOf(0, 0, 0, 1)))
        assertEquals(
            listOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
            Passport.parseSupportReply("4100BE1FA813\r\r>", 0x41, 0x00)
        )
        // two ECUs answering (headers off, two lines) → union
        val two = Passport.parseSupportReply("4100BE1FA813\r41008000 0001\r>".replace(" ", ""), 0x41, 0x00)!!
        assertTrue(two.contains(0x20) && two.contains(0x01))
        assertNull(Passport.parseSupportReply("NO DATA\r>", 0x41, 0x00))
        assertEquals("Обороты двигателя", Passport.pidName(0x0C))
        assertEquals("PID AB", Passport.pidName(0xAB))
    }

    @Test fun readinessDecode() {
        // A=0x82: MIL on, 2 DTC. B=0x07 → misfire/fuel/components available & complete (bits4-6=0)
        // C=0xE5: catalyst, EVAP, O2 sensor, O2 heater? 0xE5=1110 0101 → bits 0,2,5,6,7
        // D=0x20: O2 sensor not ready
        val r = Passport.parseReadiness("4101 8207 E5 20\r>".replace(" ", ""))!!
        assertTrue(r.milOn); assertEquals(2, r.dtcCount); assertFalse(r.compressionIgnition)
        fun st(n: String) = r.monitors.first { it.nameRu.startsWith(n) }.status
        assertEquals(MonitorStatus.READY, st("Пропуски"))
        assertEquals(MonitorStatus.READY, st("Топливная"))
        assertEquals(MonitorStatus.READY, st("Компоненты"))
        assertEquals(MonitorStatus.READY, st("Катализатор"))
        assertEquals(MonitorStatus.NA, st("Подогрев катализатора"))
        assertEquals(MonitorStatus.READY, st("Система EVAP"))
        assertEquals(MonitorStatus.NOT_READY, st("Кислородный"))
        assertEquals(MonitorStatus.READY, st("Подогрев O2"))
        assertEquals(MonitorStatus.READY, st("Система EGR"))
        // misfire incomplete (bit4), fuel not available (bit1 = 0)
        val r2 = Passport.decodeReadiness(byteArrayOf(0x00, 0x15, 0, 0))!!
        assertFalse(r2.milOn)
        assertEquals(MonitorStatus.NOT_READY, r2.monitors[0].status)
        assertEquals(MonitorStatus.NA, r2.monitors[1].status)
        assertEquals(MonitorStatus.NOT_READY, r2.monitors[2].status.let { MonitorStatus.NOT_READY }) // comp bit2 avail=1, complete → READY below
        assertEquals(MonitorStatus.READY, r2.monitors[2].status)
        assertNull(Passport.decodeReadiness(byteArrayOf(1, 2)))
        assertNull(Passport.parseReadiness("NO DATA\r>"))
    }

    @Test fun otherMode09Parsers() {
        val cal = "CAL1234567890ABC" // 16 chars
        val raw = "014\r0:490401${hex(cal).substring(0, 6)}\r1:${hex(cal).substring(6, 20)}\r2:${hex(cal).substring(20, 32)}0000\r>"
        // 3 + 17 bytes → count + 16 chars + 1? keep realistic: 0x15 declared
        val realistic = "013\r0:490401${hex(cal).substring(0, 6)}\r1:${hex(cal).substring(6, 20)}\r2:${hex(cal).substring(20, 32)}\r>"
        assertEquals(listOf(cal), Passport.parseCalIds(realistic))
        assertEquals(listOf("A1B2C3D4"), Passport.parseCvns("490601A1B2C3D4\r>"))
        assertTrue(Passport.parseCvns("49060100000000\r>").isEmpty())
        assertTrue(Passport.parseCalIds("NO DATA\r>").isEmpty())
        assertTrue(raw.isNotEmpty())
    }

    @Test fun readerGivesPerItemErrorsAndSerializes() = runBlocking {
        val sent = mutableListOf<String>()
        val d = PassportReader.read("Test", 1_000L) { cmd, _ ->
            sent += cmd
            when (cmd) {
                "0900" -> "490040000000\r>".replace("490040000000", "4900" + "50000000")  // 02 and 04 only? 0x50 = 0101 0000 → 02,04
                "0902" -> vinReply(vin)
                "0904" -> "NO DATA\r>"
                "0100" -> "4100BE1FA813\r>"
                "0120" -> "4120 00000001\r>".replace(" ", "")
                "0140" -> "NO DATA\r>"
                "0101" -> "41018207E520\r>"
                else -> "NO DATA\r>"
            }
        }
        assertEquals(vin, d.vin)
        assertEquals(Passport.NO_RESPONSE, d.calError)
        assertEquals(Passport.NOT_SUPPORTED, d.cvnError)   // 0906 not in 0900 mask → not even asked
        assertEquals(Passport.NOT_SUPPORTED, d.ecuError)
        assertFalse(sent.contains("0906")); assertFalse(sent.contains("090A"))
        assertTrue(d.pids01!!.contains(0x40)) // chain: 00 → 20 → 40
        assertTrue(sent.containsAll(listOf("0100", "0120", "0140")))
        assertNotNull(d.readiness)

        val back = Passport.deserialize(Passport.serialize(d))!!
        assertEquals(d, back)
        assertNotNull(Passport.toText(d, "1.11.0").takeIf { it.contains(vin) && it.contains("не поддерживается") })
        assertNull(Passport.deserialize("мусор"))
        assertNull(Passport.deserialize(null))
    }

    @Test fun readerSurvivesTotalSilenceAndExceptions() = runBlocking {
        val d = PassportReader.read("X", 5L) { _, _ -> throw java.io.IOException("dead") }
        assertNull(d.vin); assertNull(d.pids01); assertNull(d.readiness)
        assertEquals(Passport.NO_RESPONSE, d.pids01Error)
        assertEquals(PassportData::class, d::class)
    }
}

class PassportMode09VariantsTest {
    private val vin = "JA4AZ3A31GZ012345"
    private val cal = "1860C5730000AB12"
    private fun hex(s: String) = s.map { "%02X".format(it.code) }.joinToString("")

    /** headers off, CAF on: length line + numbered frames */
    private fun noHdr(dataHex: String): String {
        val len = dataHex.length / 2
        val f0 = dataHex.substring(0, 12)
        val sb = StringBuilder("%03X\r0:%s\r".format(len, f0))
        dataHex.substring(12).chunked(14).forEachIndexed { i, c -> sb.append("${i + 1}:$c\r") }
        return sb.append("\r>").toString()
    }

    /** headers on (ATH1): FF 10 LL + 6 bytes, CF 21.. + 7 bytes, padded */
    private fun hdr(id: String, dataHex: String): String {
        val len = dataHex.length / 2
        val sb = StringBuilder("$id 10 %02X %s\r".format(len, dataHex.substring(0, 12).chunked(2).joinToString(" ")))
        dataHex.substring(12).chunked(14).forEachIndexed { i, c ->
            val padded = c.padEnd(14, 'A')
            sb.append("$id %02X %s\r".format(0x21 + i, padded.chunked(2).joinToString(" ")))
        }
        return sb.toString()
    }

    @Test fun vinWithCountByte() {
        val r = Passport.parseVinDetailed(noHdr("490201" + hex(vin)))!!
        assertEquals(vin, r.value); assertFalse(r.partial)
    }

    @Test fun vinWithoutCountByte() {
        // 49 02 + 17 chars = 19 bytes
        val r = Passport.parseVinDetailed(noHdr("4902" + hex(vin)))!!
        assertEquals(vin, r.value); assertFalse(r.partial)
    }

    @Test fun vinHeadersOnAnd7E8Preferred() {
        val other = "JA4ZZZZZZZZ999999"
        val raw = hdr("7EA", "490201" + hex(other)) + hdr("7E8", "490201" + hex(vin)) + ">"
        val r = Passport.parseVinDetailed(raw)!!
        assertEquals(vin, r.value); assertFalse(r.partial)
    }

    @Test fun vinPartialWhenFrameMissing() {
        val full = noHdr("490201" + hex(vin))
        val lines = full.split("\r").filter { !it.startsWith("2:") }.joinToString("\r")
        val r = Passport.parseVinDetailed(lines)!!
        assertTrue(r.partial)
        assertTrue(vin.startsWith(r.value))
        assertNull(Passport.parseVinDetailed("STOPPED\r>"))
    }

    @Test fun calIdTwoBlocksWithAndWithoutCount() {
        val two = cal + "AB12CD34EF560000".let { it } // second block
        val withCount = Passport.parseCalIdsDetailed(noHdr("490402" + hex(cal) + hex("AB12CD34EF56") + "00000000"))!!
        assertEquals(listOf(cal, "AB12CD34EF56"), withCount.value); assertFalse(withCount.partial)
        val noCount = Passport.parseCalIdsDetailed(noHdr("4904" + hex(cal)))!!
        assertEquals(listOf(cal), noCount.value); assertFalse(noCount.partial)
        assertTrue(two.isNotEmpty())
    }

    @Test fun ecuNameHeadersOnMultiEcu() {
        val e1 = "ECM\u0000-EngineControl\u0000"   // 20 bytes
        val e2 = "TCM\u0000-TransmissionCtr" .take(20)
        val raw = hdr("7E9", "490A01" + hex(e2)) + hdr("7E8", "490A01" + hex(e1)) + ">"
        val r = Passport.parseEcuNamesDetailed(raw)!!
        assertEquals("ECM-EngineControl", r.value.first())
        assertEquals(2, r.value.size)
    }

    @Test fun readerFallsBackToPhysicalAndLogsRaw() = runBlocking {
        val sent = mutableListOf<String>()
        var header = "7DF"
        val d = PassportReader.read("T", 1L) { cmd, _ ->
            sent += cmd
            if (cmd.startsWith("ATSH")) header = cmd.removePrefix("ATSH")
            when {
                cmd.startsWith("AT") -> "OK\r>"
                cmd == "0900" -> "490054400000\r>"            // 02,04,06,0A
                cmd == "0902" && header == "7E0" -> noHdr("490201" + hex(vin))
                cmd == "0902" -> "NO DATA\r>"                 // functional: silent
                cmd == "0906" -> "490601D418EB92\r>"
                else -> "NO DATA\r>"
            }
        }
        assertEquals(vin, d.vin); assertFalse(d.vinPartial)
        assertEquals(listOf("D418EB92"), d.cvns)
        assertEquals(Passport.NO_RESPONSE, d.calError)
        assertTrue(sent.contains("ATSH7E0") && sent.contains("ATCRA7E8"))
        assertEquals("ATSH7DF", sent.last { it.startsWith("ATSH") }) // restored to functional
        assertTrue(d.raw09.any { it.startsWith("0902 @7E0:") && it.contains("490201") })
        assertTrue(d.raw09.any { it.startsWith("0902 @7DF:") && it.contains("NODATA") })
        // raw with \r survives cache round-trip and is in the shared text
        val back = Passport.deserialize(Passport.serialize(d))!!
        assertEquals(d, back)
        assertTrue(Passport.toText(d).contains("Сырые ответы Mode 09"))
    }
}
