package com.chuikoff.obd.sensors

import com.chuikoff.obd.elm327.Elm327Client
import com.chuikoff.obd.elm327.Elm327Parser
import com.chuikoff.obd.vehicle.CvtTempCommand

/**
 * CVT / gearbox fluid temperature — manufacturer / ECU specific.
 * Command sequence comes from the active [com.chuikoff.obd.vehicle.VehicleProfile].
 *
 * Mode 21 path (Outlander 2103):
 *  - flush, TCM addressing (pre-commands), send request
 *  - line-aware ISO-TP reassembly ([Elm327Parser.parseMode21Frame]); frames with a lost
 *    ISO-TP frame (index gap) are rejected — byte offsets would be shifted
 *  - decode only from a frame whose leading [minMode21DataBytes] bytes are at true offsets
 *  - every response (exact raw text + reassembly + candidates) goes to [CvtDebugLog]
 *  - always restore engine filter in finally
 */
class CvtTempSensor(
    private val commandProvider: () -> CvtTempCommand,
    override val displayNameRu: String = "Температура CVT",
    /** Minimum data bytes after Mode 21 marker that must be present at true offsets. */
    private val minMode21DataBytes: Int = 14,
    private val retryTimeoutMs: Long = 6000,
    /** Extra attempts after the first send on invalid frame. */
    private val maxRetries: Int = 1
) : SensorDefinition {
    override val id: String = "cvt_temp"
    override val unit: String = "°C"
    override val caveatRu: String
        get() = commandProvider().caveatRu

    override suspend fun read(client: Elm327Client): SensorReading {
        val cmd = commandProvider()
        var rawForError: String? = null
        return try {
            runCatching { client.flushReadBuffer() }

            for (pre in cmd.preCommands) {
                client.send(pre, timeoutMs = 2000)
            }

            var raw = client.send(cmd.request, timeoutMs = 5000)
            rawForError = raw
            var parsed = parseAndDecode(cmd, raw)

            var attempt = 0
            while ((parsed == null || parsed.value == null) && attempt < maxRetries) {
                attempt++
                runCatching { client.flushReadBuffer() }
                raw = client.send(cmd.request, timeoutMs = retryTimeoutMs)
                rawForError = raw
                parsed = parseAndDecode(cmd, raw)
            }

            if (CvtSettings.debugExtraRequests) {
                for (dbg in cmd.debugRequests) {
                    runCatching {
                        val r = client.send(dbg.request, timeoutMs = 4000)
                        logDebugExtra(dbg, r)
                    }
                }
            }

            parsed ?: SensorReading(
                sensorId = id,
                displayName = displayNameRu,
                value = null,
                unit = unit,
                rawHex = Elm327Parser.displayRaw(raw),
                note = noteFor(cmd),
                isPlaceholder = cmd.isPlaceholder,
                error = "Нет данных — ECU не ответил / кадр неполный (raw для отладки)"
            )
        } catch (e: Exception) {
            SensorReading(
                sensorId = id,
                displayName = displayNameRu,
                value = null,
                unit = unit,
                rawHex = rawForError?.let { Elm327Parser.displayRaw(it) },
                note = noteFor(cmd),
                isPlaceholder = cmd.isPlaceholder,
                error = e.message ?: "Ошибка чтения"
            )
        } finally {
            for (post in cmd.postCommands) {
                runCatching { client.send(post, timeoutMs = 2000) }
            }
            runCatching { client.restoreEngineEcuFilter() }
        }
    }

    private fun noteFor(cmd: CvtTempCommand): String =
        if (cmd.describeCandidates != null) "${cmd.caveatRu} · ${CvtSettings.formula.titleRu}"
        else cmd.caveatRu

    private fun logDebugExtra(dbg: CvtTempCommand.DebugRequest, raw: String) {
        val frame = Elm327Parser.parseMode21Frame(raw, dbg.pidByte)
        CvtDebugLog.add(
            CvtDebugEntry(
                timestampMs = System.currentTimeMillis(),
                request = dbg.request,
                rawVisible = Elm327Parser.visibleRaw(raw),
                frameSummary = frame?.summary() ?: (Elm327Parser.validateMode21Response(raw, dbg.pidByte)
                    ?: "нет кадра"),
                payloadHex = frame?.payload?.toSpacedHex(),
                candidates = frame?.takeIf { it.positionsTrustworthy }?.payload?.let(dbg.describe),
                valueC = null,
                error = null
            )
        )
    }

    private fun logMode21(
        cmd: CvtTempCommand,
        raw: String,
        frame: Elm327Parser.Mode21Frame?,
        value: Double?,
        error: String?
    ) {
        val desc = cmd.describeCandidates
        CvtDebugLog.add(
            CvtDebugEntry(
                timestampMs = System.currentTimeMillis(),
                request = cmd.request,
                rawVisible = Elm327Parser.visibleRaw(raw),
                frameSummary = frame?.summary() ?: "нет кадра 61${cmd.pidOrDid.uppercase()}",
                payloadHex = frame?.payload?.toSpacedHex(),
                candidates = if (desc != null && frame != null && frame.positionsTrustworthy) {
                    desc(frame.payload)
                } else null,
                valueC = value,
                error = error
            )
        )
    }

    private fun parseAndDecode(cmd: CvtTempCommand, raw: String): SensorReading? {
        when (cmd.parseMode) {
            CvtTempCommand.ParseMode.MODE21 -> {
                val pid = cmd.pidOrDid.toInt(16)
                val frame = Elm327Parser.parseMode21Frame(raw, pid)
                val validationErr = Elm327Parser.validateMode21Response(raw, pid)
                if (validationErr != null) {
                    logMode21(cmd, raw, frame, null, validationErr)
                    return SensorReading(
                        sensorId = id,
                        displayName = displayNameRu,
                        value = null,
                        unit = unit,
                        rawHex = Elm327Parser.displayRaw(raw),
                        note = noteFor(cmd),
                        isPlaceholder = cmd.isPlaceholder,
                        error = validationErr
                    )
                }
                val payload = Elm327Parser.parseMode21PayloadHardened(
                    raw, pid, minDataBytes = minMode21DataBytes
                )
                if (payload == null) {
                    val err = "Кадр короткий: ${frame?.payload?.size ?: 0} байт < $minMode21DataBytes"
                    logMode21(cmd, raw, frame, null, err)
                    return SensorReading(
                        sensorId = id,
                        displayName = displayNameRu,
                        value = null,
                        unit = unit,
                        rawHex = Elm327Parser.displayRaw(raw),
                        note = noteFor(cmd),
                        isPlaceholder = cmd.isPlaceholder,
                        error = err
                    )
                }
                val reading = decodeReading(cmd, raw, payload)
                logMode21(cmd, raw, frame, reading.value, reading.error)
                return reading
            }
            CvtTempCommand.ParseMode.MODE01 -> {
                val payload = Elm327Parser.parseMode01Payload(raw, cmd.pidOrDid.toInt(16))
                    ?: return null
                if (payload.isEmpty()) return null
                return decodeReading(cmd, raw, payload)
            }
            CvtTempCommand.ParseMode.MODE22 -> {
                val payload = Elm327Parser.parseMode22Payload(raw, cmd.pidOrDid)
                    ?: return null
                if (payload.isEmpty()) return null
                return decodeReading(cmd, raw, payload)
            }
            CvtTempCommand.ParseMode.RAW_FIRST_BYTE -> {
                val cleaned = Elm327Parser.clean(raw)
                val digits = cleaned.filter { it in '0'..'9' || it in 'A'..'F' }
                if (digits.length < 2) return null
                val payload = byteArrayOf(digits.takeLast(2).toInt(16).toByte())
                return decodeReading(cmd, raw, payload)
            }
        }
    }

    private fun decodeReading(
        cmd: CvtTempCommand,
        raw: String,
        payload: ByteArray
    ): SensorReading {
        val value = cmd.decode(payload)
        return SensorReading(
            sensorId = id,
            displayName = displayNameRu,
            value = value,
            unit = unit,
            rawHex = Elm327Parser.displayRaw(raw),
            note = noteFor(cmd),
            isPlaceholder = cmd.isPlaceholder,
            error = if (value == null) "Декод не удался (байт 00/FF или вне −40…160 °C)" else null
        )
    }

    private fun ByteArray.toSpacedHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
