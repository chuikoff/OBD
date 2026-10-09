package com.chuikoff.obd.elm327

/**
 * Helpers to clean ELM327 ASCII responses and extract Mode 01 / 21 / 22 / DTC payloads.
 *
 * 1.7.0: line-aware ISO-TP reassembly.
 *
 * Bug fixed in 1.7.0: the old [clean] glued all lines together first and then removed
 * frame-index prefixes with `Regex("\\d+:")`. With ATS0 a multi-frame reply looks like
 *
 *     014\r0:6103262126FF\r1:38FD00FCFCEC40\r2:3400852B2A006C\r
 *
 * After gluing: `0140:6103262126FF1:38FD00FCFCEC402:3400852B2A006C`. The greedy `\d+:`
 * also swallowed every *data* nibble made of decimal digits right before the next index
 * (`402:` ate the byte `40`). So bytes vanished whenever a CAN frame ended in 0x00–0x99-ish
 * nibbles — the "random byte loss" was ours, not the adapter's, and every field after the
 * hole shifted. Now each line is handled on its own; prefixes are stripped per line only.
 */
object Elm327Parser {

    /** `0:` … `F:` frame-index line from ELM CAN auto-formatting (headers off). */
    private val INDEXED_LINE = Regex("^([0-9A-F]):(.*)$")
    /** `014` — total ISO-TP length line printed before indexed frames. */
    private val LENGTH_LINE = Regex("^[0-9A-F]{3}$")
    private val HEX_ONLY = Regex("^[0-9A-F]+$")

    /**
     * One reassembled ECU message (single frame or multi-frame).
     * @param bytes data bytes (service byte first, e.g. 61 03 …), padding trimmed to [declaredLength]
     * @param declaredLength ISO-TP length (from `014` line or First Frame PCI), null if unknown
     * @param frameIndices line/sequence indices seen (multi-frame only)
     * @param gap true if a frame index was skipped or the message did not start at index 0 —
     *        byte positions after the gap are NOT trustworthy
     */
    data class ObdMessage(
        val bytes: ByteArray,
        val declaredLength: Int?,
        val frameIndices: List<Int>,
        val gap: Boolean,
        val issues: List<String>,
        val headerId: String? = null
    ) {
        val isMultiFrame: Boolean get() = frameIndices.isNotEmpty()
        /** All bytes present at correct offsets. */
        val complete: Boolean
            get() = !gap && (declaredLength == null || bytes.size >= declaredLength)
        /** Leading bytes are at their real offsets (no hole before the end). */
        val positionsTrustworthy: Boolean get() = !gap
        fun hex(): String = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    /**
     * Raw ELM text → trimmed, uppercased, space-less lines. Drops prompt, SEARCHING..., empty lines.
     */
    fun responseLines(raw: String): List<String> =
        raw.uppercase()
            .replace(">", "\n")
            .split('\r', '\n')
            .map { it.replace(" ", "").replace("\t", "").replace("SEARCHING...", "").trim() }
            .filter { it.isNotEmpty() }

    /** Human/debug view of raw ELM text with line structure kept: `014 | 0:6103… | 1:… `. */
    fun displayRaw(raw: String): String = responseLines(raw).joinToString(" | ")

    /** Raw text with control chars made visible, for logs: `\r` → `⏎`. */
    fun visibleRaw(raw: String): String =
        raw.replace("\r\n", "⏎").replace("\r", "⏎").replace("\n", "⏎")

    /**
     * Clean ELM ASCII line-by-line: drop SEARCHING/prompt/whitespace, drop the ISO-TP length
     * line, strip `N:` frame prefixes **per line** (never across lines), then concatenate.
     */
    fun clean(raw: String): String {
        val lines = responseLines(raw)
        val sb = StringBuilder()
        lines.forEachIndexed { i, line ->
            if (LENGTH_LINE.matches(line) &&
                lines.getOrNull(i + 1)?.let { INDEXED_LINE.matches(it) } == true
            ) return@forEachIndexed
            val m = INDEXED_LINE.matchEntire(line)
            sb.append(m?.groupValues?.get(2) ?: line)
        }
        return sb.toString()
    }

    private fun hasErrorText(s: String): Boolean =
        s.contains("NODATA") || s.contains("ERROR") || s.contains("?") ||
            s.contains("STOPPED") || s.contains("UNABLETOCONNECT") || s.contains("BUFFERFULL")

    /**
     * Reassemble ELM327 output into ECU messages. Supports:
     *  - headers off, CAF on: `014` + `0:…` `1:…` `2:…` (multi-frame), or a plain line (single frame)
     *  - headers on (ATH1), 11-bit IDs: `7E9 10 14 61 03 …`, `7E9 21 …`, `7E9 06 61 …`
     * Several ECUs / several messages in one reply become several [ObdMessage]s.
     */
    fun assemble(raw: String): List<ObdMessage> {
        val lines = responseLines(raw)
        val out = mutableListOf<ObdMessage>()

        // ---- headers-off multi-frame state
        var curBytes: MutableList<Byte>? = null
        var curDeclared: Int? = null
        var curIdx = mutableListOf<Int>()
        var curGap = false
        var curIssues = mutableListOf<String>()

        fun flushMulti() {
            val b = curBytes ?: return
            val declared = curDeclared
            val trimmed = if (declared != null && b.size > declared) b.subList(0, declared) else b
            val issues = curIssues.toMutableList()
            if (declared != null && b.size < declared) issues += "короче заявленного: ${b.size}/$declared"
            out += ObdMessage(trimmed.toByteArray(), declared, curIdx.toList(), curGap, issues)
            curBytes = null; curDeclared = null; curIdx = mutableListOf(); curGap = false
            curIssues = mutableListOf()
        }

        // ---- headers-on state per CAN id
        data class CanAcc(val bytes: MutableList<Byte>, val declared: Int?, val idx: MutableList<Int>,
                          var gap: Boolean, var lastSeq: Int, val issues: MutableList<String>)
        val canAcc = LinkedHashMap<String, CanAcc>()
        fun flushCan(id: String) {
            val a = canAcc.remove(id) ?: return
            val d = a.declared
            val trimmed = if (d != null && a.bytes.size > d) a.bytes.subList(0, d) else a.bytes
            if (d != null && a.bytes.size < d) a.issues += "короче заявленного: ${a.bytes.size}/$d"
            out += ObdMessage(trimmed.toByteArray(), d, a.idx.toList(), a.gap, a.issues.toList(), id)
        }

        for ((i, line) in lines.withIndex()) {
            // Length line before indexed frames
            if (LENGTH_LINE.matches(line) &&
                lines.getOrNull(i + 1)?.let { INDEXED_LINE.matches(it) } == true
            ) {
                flushMulti()
                curBytes = mutableListOf()
                curDeclared = line.toInt(16)
                continue
            }
            val m = INDEXED_LINE.matchEntire(line)
            if (m != null) {
                val index = m.groupValues[1].toInt(16)
                val body = m.groupValues[2]
                val hexPart = body.takeWhile { it in '0'..'9' || it in 'A'..'F' }
                if (curBytes == null) {
                    curBytes = mutableListOf()
                    curIssues += "нет строки длины"
                } else if (curIdx.isNotEmpty() && index == 0) {
                    // new message from (another) ECU without its own length line
                    flushMulti(); curBytes = mutableListOf(); curIssues += "нет строки длины"
                }
                val expected = if (curIdx.isEmpty()) 0 else (curIdx.last() + 1) and 0xF
                if (index != expected) {
                    curGap = true
                    curIssues += "пропуск кадра: ждали $expected, пришёл $index"
                }
                if (hexPart.length != body.length) {
                    curIssues += "мусор в кадре $index: ${body.substring(hexPart.length)}"
                    if (hasErrorText(body)) curGap = true
                }
                if (hexPart.length % 2 != 0) {
                    curIssues += "нечётное число символов в кадре $index"
                    curGap = true
                }
                curIdx += index
                hexPart.chunked(2).filter { it.length == 2 }
                    .forEach { curBytes!!.add(it.toInt(16).toByte()) }
                continue
            }

            // Not an indexed line → finish any pending headers-off multi-frame
            flushMulti()
            if (!HEX_ONLY.matches(line)) continue // NO DATA, ERROR, OK, echo junk…

            // Headers on: 11-bit id (3 hex) + PCI → odd number of hex chars
            if (line.length >= 5 && line.length % 2 == 1 && line[0] in '0'..'7') {
                val id = line.substring(0, 3)
                val pciType = line[3]
                val rest = line.substring(4)
                when (pciType) {
                    '0' -> { // single frame: 0L + data
                        flushCan(id)
                        val len = line[4].toString().toInt(16)
                        val data = line.substring(5).chunked(2).filter { it.length == 2 }
                            .map { it.toInt(16).toByte() }
                        out += ObdMessage(data.take(len).toByteArray(), len, emptyList(),
                            gap = data.size < len, issues = emptyList(), headerId = id)
                        continue
                    }
                    '1' -> { // first frame: 1LLL + 6 data bytes
                        flushCan(id)
                        if (rest.length >= 3) {
                            val len = rest.substring(0, 3).toInt(16)
                            val data = rest.substring(3).chunked(2).filter { it.length == 2 }
                                .map { it.toInt(16).toByte() }
                            canAcc[id] = CanAcc(data.toMutableList(), len, mutableListOf(0), false, 0,
                                mutableListOf())
                            continue
                        }
                    }
                    '2' -> { // consecutive frame: 2N + 7 data bytes
                        val seq = line[4].toString().toInt(16)
                        val acc = canAcc[id]
                        val data = line.substring(5).chunked(2).filter { it.length == 2 }
                            .map { it.toInt(16).toByte() }
                        if (acc == null) {
                            out += ObdMessage(data.toByteArray(), null, listOf(seq), gap = true,
                                issues = listOf("CF без First Frame"), headerId = id)
                        } else {
                            val expected = (acc.lastSeq + 1) and 0xF
                            if (seq != expected) {
                                acc.gap = true
                                acc.issues += "пропуск CF: ждали $expected, пришёл $seq"
                            }
                            acc.lastSeq = seq
                            acc.idx += seq
                            acc.bytes.addAll(data)
                            if (acc.declared != null && acc.bytes.size >= acc.declared) flushCan(id)
                        }
                        continue
                    }
                    '3' -> continue // flow control echo
                }
            }

            // Plain single-frame data line (headers off)
            if (line.length % 2 == 0) {
                val data = line.chunked(2).map { it.toInt(16).toByte() }
                out += ObdMessage(data.toByteArray(), null, emptyList(), false, emptyList())
            } else {
                // odd hex: something is off — keep but flag positions as untrustworthy
                val data = line.dropLast(1).chunked(2).map { it.toInt(16).toByte() }
                out += ObdMessage(data.toByteArray(), null, emptyList(), true,
                    listOf("нечётная строка: $line"))
            }
        }
        flushMulti()
        canAcc.keys.toList().forEach { flushCan(it) }
        return out
    }

    /**
     * Hex-only concatenation of reassembled messages (no index prefixes, no length line,
     * padding trimmed). Falls back to filtering [clean] if nothing parsed.
     */
    fun hexDigits(raw: String): String {
        val msgs = assemble(raw)
        if (msgs.isNotEmpty()) return msgs.joinToString("") { it.hex() }
        return clean(raw).filter { it in '0'..'9' || it in 'A'..'F' }
    }

    /**
     * Normalize OBD hex for Mode 21: reassembled hex starting at the first message whose first
     * byte is `61`. Legacy PCI/odd-nibble fallbacks kept for unformatted (CAF0) dumps.
     */
    fun normalizeObdHex(raw: String): String {
        val msgs = assemble(raw)
        val i61 = msgs.indexOfFirst { it.bytes.isNotEmpty() && (it.bytes[0].toInt() and 0xFF) == 0x61 }
        if (i61 >= 0) return msgs.drop(i61).joinToString("") { it.hex() }

        var hex = hexDigits(raw)
        if (hex.isEmpty()) return hex
        var guard = 0
        while (hex.length >= 4 && guard++ < 8) {
            if (hex.length >= 6 && hex.startsWith("10") && hex.substring(4, 6) == "61") {
                hex = hex.substring(4); continue
            }
            val sf = Regex("^0*0([1-9A-F])61").find(hex)
            if (sf != null) { hex = hex.substring(sf.value.length - 2); continue }
            break
        }
        val idx = indexOfMarkerAtEvenIndex(hex, "61")
        return if (idx > 0) hex.substring(idx) else hex
    }

    /**
     * Parse Mode 01 response for a given PID.
     * Example: request "0105" -> response contains "4105XX"
     */
    fun parseMode01Payload(raw: String, pid: Int): ByteArray? {
        val cleaned = clean(raw)
        if (cleaned.contains("NODATA") || cleaned.contains("ERROR") || cleaned.contains("?")) {
            return null
        }
        val pidByte = pid and 0xFF
        assemble(raw).firstOrNull {
            it.bytes.size >= 2 && (it.bytes[0].toInt() and 0xFF) == 0x41 &&
                (it.bytes[1].toInt() and 0xFF) == pidByte
        }?.let { return it.bytes.copyOfRange(2, it.bytes.size) }
        // Legacy fallback (unformatted text)
        val marker = "41%02X".format(pidByte)
        val idx = cleaned.indexOf(marker)
        if (idx < 0) return null
        val hex = cleaned.substring(idx + marker.length)
        val digits = hex.takeWhile { it in '0'..'9' || it in 'A'..'F' }
        val usable = if (digits.length % 2 == 0) digits else digits.dropLast(1)
        if (usable.isEmpty()) return ByteArray(0)
        return usable.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /** Result of locating a Mode 21 `61 PID …` message. */
    data class Mode21Frame(
        val pid: Int,
        /** Data bytes after `61 PID` (Torque letters A, B, C… = index 0, 1, 2…). */
        val payload: ByteArray,
        val message: ObdMessage
    ) {
        val complete: Boolean get() = message.complete
        val positionsTrustworthy: Boolean get() = message.positionsTrustworthy
        fun summary(): String {
            val m = message
            val len = m.declaredLength?.let { "заявлено $it" } ?: "длина ?"
            val frames = if (m.isMultiFrame) "кадры ${m.frameIndices.joinToString(",")}" else "SF"
            val st = when {
                m.complete -> "OK"
                m.gap -> "ДЫРА"
                else -> "обрезан"
            }
            val issues = if (m.issues.isEmpty()) "" else " · " + m.issues.joinToString("; ")
            return "$st · ${m.bytes.size} байт ($len) · $frames$issues"
        }
    }

    /** Find the `61 PID` message in a raw ELM reply (null if none). */
    fun parseMode21Frame(raw: String, pidByte: Int): Mode21Frame? {
        val pid = pidByte and 0xFF
        val msg = assemble(raw).firstOrNull {
            it.bytes.size >= 2 && (it.bytes[0].toInt() and 0xFF) == 0x61 &&
                (it.bytes[1].toInt() and 0xFF) == pid
        } ?: return null
        return Mode21Frame(pid, msg.bytes.copyOfRange(2, msg.bytes.size), msg)
    }

    /**
     * Validate a Mode 21 response before decode.
     * @return error message, or null if the response looks usable.
     */
    fun validateMode21Response(raw: String, expectedPidByte: Int): String? {
        val cleaned = clean(raw)
        val msgs = assemble(raw)
        val pid = expectedPidByte and 0xFF
        val frame = parseMode21Frame(raw, pid)
        if (frame == null) {
            if (cleaned.contains("NODATA")) return "Нет данных (NO DATA)"
            if (hasErrorText(cleaned) && msgs.isEmpty()) return "Нет данных / ERROR"
            msgs.firstOrNull { it.bytes.size >= 3 && (it.bytes[0].toInt() and 0xFF) == 0x7F }?.let {
                return "Отказ ЭБУ 7F %02X NRC %02X".format(
                    it.bytes[1].toInt() and 0xFF, it.bytes[2].toInt() and 0xFF
                )
            }
            if (msgs.any { it.bytes.isNotEmpty() && (it.bytes[0].toInt() and 0xFF) == 0x43 }) {
                return "Mode 03 DTC ответ (отклонено)"
            }
            msgs.firstOrNull { it.bytes.size >= 2 && (it.bytes[0].toInt() and 0xFF) == 0x61 }?.let {
                return "Неверный PID ответ 61%02X (ожидали 61%02X)".format(it.bytes[1].toInt() and 0xFF, pid)
            }
            if (msgs.isEmpty()) return "Нет данных / ERROR"
            return "Нет маркера 61%02X".format(pid)
        }
        if (frame.message.gap) {
            return "Потерян кадр ISO-TP (" + frame.message.issues.joinToString("; ") + ")"
        }
        return null
    }

    fun parseMode21Payload(raw: String, pidByte: Int): ByteArray? =
        parseMode21PayloadHardened(raw, pidByte, minDataBytes = 0)

    /**
     * Mode 21 payload (bytes after `61 PID`) from a properly reassembled message.
     * Returns null if marker missing, a frame was lost mid-message (positions shifted), or
     * fewer than [minDataBytes] bytes. A truncated *tail* is allowed if enough leading bytes
     * remain — their offsets are still correct.
     */
    fun parseMode21PayloadHardened(
        raw: String,
        pidByte: Int,
        minDataBytes: Int = 0
    ): ByteArray? {
        val cleaned = clean(raw)
        if (cleaned.contains("NODATA")) return null
        val frame = parseMode21Frame(raw, pidByte) ?: return null
        if (!frame.positionsTrustworthy) return null
        if (minDataBytes > 0 && frame.payload.size < minDataBytes) return null
        return frame.payload
    }

    private fun indexOfMarkerAtEvenIndex(hex: String, marker: String): Int {
        var i = 0
        while (i + marker.length <= hex.length) {
            if (hex.regionMatches(i, marker, 0, marker.length)) return i
            i += 2
        }
        return -1
    }

    /**
     * Best-effort Mode 22 (enhanced diagnostics) payload parser.
     * Uses [normalizeObdHex]-style cleanup and finds `62`+DID at an even nibble index
     * so messy RAW like `01F6201A0FFFF…` still locates `6201A0` correctly.
     */
    fun parseMode22Payload(raw: String, did: String): ByteArray? {
        val cleaned = clean(raw)
        if (cleaned.contains("NODATA") || cleaned.contains("ERROR") || cleaned.contains("?")) {
            return null
        }
        val marker = "62${did.uppercase()}"
        val hex = normalizeMode22Hex(raw, marker)
        val idx = indexOfMarkerAtEvenIndex(hex, marker)
        if (idx < 0) return null
        var digits = hex.substring(idx + marker.length)
        // Stop at another 62xx on byte boundary (glued frame)
        var i = 2
        while (i + 1 < digits.length) {
            if (i % 2 == 0 && digits[i] == '6' && digits[i + 1] == '2') {
                digits = digits.substring(0, i)
                break
            }
            i += 2
        }
        val usable = if (digits.length % 2 == 0) digits else digits.dropLast(1)
        if (usable.isEmpty()) return emptyArray<Byte>().toByteArray()
        return usable.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Mode 22 sibling of [normalizeObdHex]: strip ISO-TP PCI / junk so `62XX…` sits
     * on an even char index (handles leftovers like `01F6201A0…`).
     */
    fun normalizeMode22Hex(raw: String, marker: String = "62"): String {
        var hex = hexDigits(raw)
        if (hex.isEmpty()) return hex
        var guard = 0
        while (hex.length >= 4 && guard++ < 8) {
            // First-frame PCI: 10xx then 62… → drop 4 hex chars
            if (hex.length >= 6 &&
                hex.startsWith("10") &&
                hex.substring(4, 6) == "62"
            ) {
                hex = hex.substring(4)
                continue
            }
            // SF / leftover PCI before 62
            val sf = Regex("^0*0([1-9A-F])62").find(hex)
            if (sf != null) {
                val keepFrom = sf.value.length - 2
                hex = hex.substring(keepFrom)
                continue
            }
            break
        }
        val evenIdx = indexOfMarkerAtEvenIndex(hex, marker)
        if (evenIdx >= 0) {
            return if (evenIdx == 0) hex else hex.substring(evenIdx)
        }
        // Odd-nibble shift: skip 1 hex digit and retry
        if (hex.length >= 2) {
            val skipped = hex.substring(1)
            val oddIdx = indexOfMarkerAtEvenIndex(skipped, marker)
            if (oddIdx >= 0) {
                return if (oddIdx == 0) skipped else skipped.substring(oddIdx)
            }
        }
        return hex
    }

    /**
     * Parse Mode 03 (stored) or Mode 07 (pending) DTC list.
     * Response marker: 43 (mode 03) or 47 (mode 07). Each DTC is two bytes (SAE packing).
     */
    fun parseDtcList(raw: String): List<String> {
        val cleaned = clean(raw)
        if (cleaned.contains("NODATA")) return emptyList()
        if (cleaned.contains("ERROR") || cleaned == "?") return emptyList()

        val hexOnly = cleaned.filter { it in '0'..'9' || it in 'A'..'F' }
        val codes = mutableListOf<String>()

        // May contain multiple ECU replies / frames — find each 43 / 47 marker
        var searchFrom = 0
        while (searchFrom < hexOnly.length - 1) {
            val idx43 = hexOnly.indexOf("43", searchFrom)
            val idx47 = hexOnly.indexOf("47", searchFrom)
            val idx = when {
                idx43 < 0 && idx47 < 0 -> -1
                idx43 < 0 -> idx47
                idx47 < 0 -> idx43
                else -> minOf(idx43, idx47)
            }
            if (idx < 0) break

            var pos = idx + 2
            while (pos + 3 < hexOnly.length) {
                val peek = hexOnly.substring(pos, pos + 2)
                if ((peek == "43" || peek == "47") && pos > idx + 2) break
                val b1 = hexOnly.substring(pos, pos + 2).toIntOrNull(16) ?: break
                val b2 = hexOnly.substring(pos + 2, pos + 4).toIntOrNull(16) ?: break
                pos += 4
                if (b1 == 0 && b2 == 0) continue
                formatDtc(b1, b2)?.let { codes += it }
            }
            searchFrom = pos.coerceAtLeast(idx + 2)
        }
        return codes.distinct()
    }

    /**
     * SAE J2012 packing of two DTC bytes into P0xxx / C0xxx / B0xxx / U0xxx.
     */
    fun formatDtc(high: Int, low: Int): String? {
        if (high == 0 && low == 0) return null
        val typeBits = (high shr 6) and 0x03
        val prefix = when (typeBits) {
            0 -> "P"
            1 -> "C"
            2 -> "B"
            else -> "U"
        }
        val d1 = (high shr 4) and 0x03
        val d2 = high and 0x0F
        val d3 = (low shr 4) and 0x0F
        val d4 = low and 0x0F
        return "$prefix$d1${d2.toString(16).uppercase()}${d3.toString(16).uppercase()}${d4.toString(16).uppercase()}"
    }

    fun toUnsigned(b: Byte): Int = b.toInt() and 0xFF
}
