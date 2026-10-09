package com.chuikoff.obd.vehicle

import com.chuikoff.obd.elm327.Elm327Parser
import com.chuikoff.obd.sensors.CoolantTempSensor
import com.chuikoff.obd.sensors.CvtFormula
import com.chuikoff.obd.sensors.CvtSettings
import com.chuikoff.obd.sensors.CvtTempSensor
import com.chuikoff.obd.sensors.OutlanderAmbientTempSensor
import com.chuikoff.obd.sensors.SensorDefinition
import java.util.Locale
import kotlin.math.pow

/**
 * Mitsubishi Outlander GF/GG (3rd gen, ~2012–2021), Jatco CVT.
 *
 * Coolant: SAE Mode 01 PID 05.
 * Ambient: AWC Mode 21/210D (cluster outdoor).
 *
 * CVT fluid temperature — OUT-CLUB OUT3.csv (Torque), TCM 7E1/7E9:
 *  - Pre:  ATSH7E1, ATFCSH7E1, ATFCSD30080A, ATFCSM1, ATCRA7E9
 *  - Req:  2103 → `61 03` + 18 data bytes A…R (ISO-TP length 0x14 = 20)
 *    A=(A−2)*32 primary rpm, B=(B−2)*64 secondary rpm, C*32 engine rpm, D slip, E/100 ratio,
 *    F accel, G pedal, H/I/J torques, K torque ratio, L/M pressures, **N = CVT temp count**.
 *  - °C: "(Out3) CVT Temperature" polynomial of N, or "(Out3 CVT8) N−55" (user-selectable).
 *  - Post: restore engine ECU ATSH7E0 / ATCRA7E8.
 *
 * 1.7.0: count is again strictly Torque letter N (index 13) of a correctly reassembled frame.
 * The 1.6.0 "count from tail" (byte after 0x85) was wrong: 0x85 *is* N; the byte after it
 * (letter O) swings 32→43→59 within one minute and is not a temperature.
 */
class OutlanderGfGgProfile : VehicleProfile {

    override val id: String = "mitsubishi_outlander_gf_gg"
    override val displayNameRu: String = "Mitsubishi Outlander GF/GG"
    override val years: String = "2012–2021"
    override val notesRu: String =
        "ОЖ: PID 05 (0105). Снаружи: AWC 210D (приборка). " +
            "CVT: 2103@7E1, байт N (OUT-CLUB OUT3), формула выбирается в «Диагностика CVT»."

    companion object {
        /** Torque letter N = 14th data byte after `61 03` (0-based 13). */
        const val CVT_BYTE_INDEX = 13
        /** Full 2103 payload on Outlander 3: 18 data bytes (A…R). */
        const val FULL_PAYLOAD = 18

        /** OUT-CLUB OUT3.csv "(Out3) CVT Temperature": °C from unsigned count N. */
        fun cvtPolyCelsius(n: Int): Double {
            val x = n.toDouble()
            return 0.000000002344 * x.pow(5) +
                (-0.000001387) * x.pow(4) +
                0.0003193 * x.pow(3) +
                (-0.03501) * x.pow(2) +
                2.302 * x +
                (-36.6)
        }

        /** OUT-CLUB OUT3.csv "(Out3 CVT8) CVT Temperature": N − 55. */
        fun cvt8Celsius(n: Int): Double = (n - 55).toDouble()

        /** Physically possible window only — wide on purpose, must not hide a wrong byte. */
        fun isPlausibleCvtTemp(c: Double): Boolean = c in -40.0..160.0

        /** Torque letter N from a correctly reassembled 2103 payload, or null. */
        fun extractCvtCountFromPayload(bytes: ByteArray): Int? =
            if (bytes.size > CVT_BYTE_INDEX) Elm327Parser.toUnsigned(bytes[CVT_BYTE_INDEX]) else null

        fun celsiusFor(n: Int, formula: CvtFormula): Double = when (formula) {
            CvtFormula.OUT3_POLY -> cvtPolyCelsius(n)
            CvtFormula.CVT8_MINUS_55 -> cvt8Celsius(n)
        }

        fun decodeCvtCelsius(bytes: ByteArray, formula: CvtFormula = CvtSettings.formula): Double? {
            val n = extractCvtCountFromPayload(bytes) ?: return null
            if (n == 0x00 || n == 0xFF) return null // sensor open/short or "not available"
            val c = celsiusFor(n, formula)
            return if (isPlausibleCvtTemp(c)) c else null
        }

        /** Candidates for the debug log, so the user can compare against reality. */
        fun describe2103(bytes: ByteArray): String {
            fun u(i: Int) = if (bytes.size > i) Elm327Parser.toUnsigned(bytes[i]) else null
            val n = u(CVT_BYTE_INDEX)
            val sb = StringBuilder()
            if (n != null) {
                sb.append(String.format(Locale.US,
                    "N[13]=0x%02X(%d) → полином %.1f°C · N−55 %d°C", n, n, cvtPolyCelsius(n), n - 55))
            } else {
                sb.append("N[13] нет (payload ${bytes.size} байт)")
            }
            u(14)?.let { o ->
                sb.append(String.format(Locale.US,
                    " | O[14]=0x%02X(%d) (1.6.0 брала его) → %.1f°C", o, o, cvtPolyCelsius(o)))
            }
            val a = u(0); val b = u(1); val c = u(2); val e = u(4)
            if (a != null && b != null && c != null) {
                sb.append(" | перв ${(a - 2) * 32} / втор ${(b - 2) * 64} / двиг ${c * 32} об/мин")
            }
            if (e != null) sb.append(String.format(Locale.US, " · ratio %.2f", e / 100.0))
            return sb.toString()
        }

        /** 2102 letter E = "CVT Temp. Signal" (E/100 V); OUT3 "of voltage" poly(254−E). */
        fun describe2102(bytes: ByteArray): String {
            if (bytes.size <= 4) return "2102: payload ${bytes.size} байт (<5)"
            val e = Elm327Parser.toUnsigned(bytes[4])
            return String.format(Locale.US,
                "2102 E[4]=0x%02X(%d) → %.2f В · poly(254−E) %.1f°C", e, e, e / 100.0, cvtPolyCelsius(254 - e))
        }

        val OUT3_CVT = CvtTempCommand(
            request = "2103",
            pidOrDid = "03",
            parseMode = CvtTempCommand.ParseMode.MODE21,
            preCommands = listOf(
                "ATAT1",
                "ATST32",
                "ATSH7E1",
                "ATFCSH7E1",
                "ATFCSD30080A",
                "ATFCSM1",
                "ATCRA7E9"
            ),
            postCommands = listOf(
                "ATSH7E0",
                "ATFCSH7E0",
                "ATFCSD30080A",
                "ATFCSM1",
                "ATCRA7E8"
            ),
            isPlaceholder = false,
            caveatRu = "OUT-CLUB OUT3: 2103@7E1, байт N",
            decode = { bytes -> decodeCvtCelsius(bytes) },
            describeCandidates = { bytes -> describe2103(bytes) },
            debugRequests = listOf(
                CvtTempCommand.DebugRequest("2102", 0x02) { bytes -> describe2102(bytes) }
            )
        )
    }

    override fun cvtTempCommand(): CvtTempCommand = OUT3_CVT

    override fun sensors(): List<SensorDefinition> = listOf(
        CoolantTempSensor(),
        CvtTempSensor(
            commandProvider = { cvtTempCommand() },
            displayNameRu = "Температура CVT",
            // N is index 13 → need ≥14 data bytes at their true offsets
            minMode21DataBytes = CVT_BYTE_INDEX + 1,
            retryTimeoutMs = 6000,
            maxRetries = 2
        ),
        OutlanderAmbientTempSensor()
    )
}
