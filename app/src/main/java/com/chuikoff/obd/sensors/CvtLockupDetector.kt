package com.chuikoff.obd.sensors

import kotlin.math.abs

/**
 * Torque-converter lock-up estimate for Jatco JF011E/JF016E from the 2103 frame we already poll.
 *
 * OUT-CLUB OUT3.csv / elm3.ru PID list for Outlander:
 *  - Primary (turbine / input) rpm = (A − 2) · 32   → 32-rpm steps
 *  - Secondary rpm                 = (B − 2) · 64
 *  - Engine rpm                    = C · 32           → 32-rpm steps
 *  - SIGNED(D) is labelled «CVT Slip RPM» (−127…127) — ECU-reported, different scale; not used for state
 *
 * No verified «lock-up ON/OFF» status bit was found in 2103 / 2102 / 2104. Mutual service-manual
 * lock-up items (MUT-III 30/31, PID 2105 LU solenoid current) need a second TCM request —
 * out of scope. State is inferred from slip = engine − primary:
 *  - «вкл» when primary > 0, engine > [minEngineRpm] and |slip| ≤ [lockMaxSlip] for [lockPolls] polls
 *  - «выкл (стоим)» when primary == 0
 *  - «выкл» when slip > [unlockMinSlip] (or after leaving lock without meeting lock criteria)
 *
 * Thresholds sit above the 32-rpm quantization: 64 ≈ 2 steps, 160 ≈ 5 steps.
 */
class CvtLockupDetector(
    private val lockMaxSlip: Int = 64,
    private val unlockMinSlip: Int = 160,
    private val minEngineRpm: Int = 900,
    private val lockPolls: Int = 2
) {
    enum class State(val labelRu: String, val argb: Long) {
        LOCKED("вкл", 0xFF43A047),            // green
        UNLOCKED("выкл", 0xFF9E9E9E),         // grey
        IDLE("выкл (стоим)", 0xFF9E9E9E),
        UNKNOWN("—", 0xFF9E9E9E)
    }

    data class Sample(
        val primaryRpm: Int,
        val secondaryRpm: Int,
        val engineRpm: Int,
        /** ECU byte D as signed (−128…127), for diagnostics only. */
        val ecuSlipByte: Int? = null
    ) {
        val slip: Int get() = engineRpm - primaryRpm
    }

    data class Result(
        val state: State,
        val sample: Sample?,
        val methodRu: String = METHOD,
        val thresholdsRu: String = THRESHOLDS
    ) {
        val cardLine: String get() = "Блокировка ГТ: ${state.labelRu}"
        val techLine: String get() {
            val s = sample ?: return "$cardLine · нет кадра · метод: $methodRu · $thresholdsRu"
            return "$cardLine · перв ${s.primaryRpm} / втор ${s.secondaryRpm} / двиг ${s.engineRpm} · " +
                "slip ${s.slip} об/мин" +
                (s.ecuSlipByte?.let { " (SIGNED(D)=$it)" } ?: "") +
                " · метод: $methodRu · $thresholdsRu"
        }
    }

    private var state: State = State.UNKNOWN
    private var lowSlipStreak = 0

    fun update(sample: Sample?): Result {
        if (sample == null) {
            state = State.UNKNOWN
            lowSlipStreak = 0
            return Result(state, null)
        }
        val slip = sample.slip
        when {
            sample.primaryRpm <= 0 -> {
                lowSlipStreak = 0
                state = State.IDLE
            }
            abs(slip) <= lockMaxSlip && sample.engineRpm > minEngineRpm -> {
                lowSlipStreak++
                if (lowSlipStreak >= lockPolls) state = State.LOCKED
                // else keep previous (UNKNOWN / UNLOCKED / IDLE) until confirmed
            }
            slip > unlockMinSlip -> {
                lowSlipStreak = 0
                state = State.UNLOCKED
            }
            else -> {
                // hysteresis band (lockMaxSlip … unlockMinSlip): keep last unless we were UNKNOWN
                lowSlipStreak = 0
                if (state == State.UNKNOWN || state == State.IDLE) state = State.UNLOCKED
            }
        }
        return Result(state, sample)
    }

    fun reset() {
        state = State.UNKNOWN
        lowSlipStreak = 0
    }

    companion object {
        const val METHOD = "по проскальзыванию (двиг − перв. шкив) из 2103"
        const val THRESHOLDS =
            "вкл: |slip|≤64 об/мин × 2 опроса, двиг>900; выкл: slip>160 или перв=0"
        const val EXPLAIN_RU =
            "Блокировка ГТ (lock-up) — смыкание сцепления гидротрансформатора: двигатель и " +
                "первичный шкив CVT вращаются почти одинаково, меньше нагрев масла и расход. " +
                "Явного байта «вкл/выкл» в 2103 нет (OUT-CLUB OUT3 / elm3.ru) — считаем по slip."

        /** Decode kinematics from a 2103 payload (Torque letters A…E at indices 0…4). */
        fun sampleFrom2103(bytes: ByteArray): Sample? {
            if (bytes.size < 3) return null
            fun u(i: Int) = bytes[i].toInt() and 0xFF
            val a = u(0); val b = u(1); val c = u(2)
            val primary = (a - 2) * 32
            val secondary = if (bytes.size > 1) (b - 2) * 64 else 0
            val engine = c * 32
            if (primary < 0 || secondary < 0 || engine < 0) return null
            val ecuSlip = if (bytes.size > 3) bytes[3].toInt() else null // signed byte as-is
            return Sample(primary, secondary, engine, ecuSlip)
        }
    }
}
