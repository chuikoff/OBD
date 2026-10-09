package com.chuikoff.obd.ui

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One color scale for all temperature cards (phone UI + Android Auto).
 *
 * Pure Kotlin (ARGB as Long, no Compose/Car types) so the band boundaries are unit-tested on
 * the JVM. Band selection uses the value **as displayed** (rounded like `%.0f`, HALF_UP), so
 * the number and its color always agree.
 */
enum class TempBand(val argb: Long, val labelRu: String) {
    FROST(0xFF7E57C2, "мороз"),          // deep purple 400
    ICE(0xFF1E88E5, "гололёд"),          // blue 600 (= CVT cold)
    COOL(0xFF0097A7, "прохладно"),       // cyan 700
    COLD(0xFF1E88E5, "холодно"),         // blue 600 — CVT <20 / coolant warm-up
    NORMAL(0xFF43A047, "норма"),         // green 600
    HOT(0xFFFB8C00, "горячо"),           // orange 600 (distinct from red)
    OVERHEAT(0xFFE53935, "перегрев")     // red 600
}

object TempColorScale {

    enum class Kind { CVT, COOLANT, AMBIENT }

    const val LEGEND_CVT = "<20° синий · 20–99° зелёный · ≥100° красный"
    const val LEGEND_COOLANT = "<70° синий · 70–104° зелёный · 105–109° оранжевый · ≥110° красный"
    const val LEGEND_AMBIENT =
        "≤−10° мороз · −9…+3° гололёд · +4…+15° прохладно · +16…+25° комфорт · +26…+30° жарко · >30° жара"

    /** Which scale applies to a reading (all profiles). */
    fun kindFor(sensorId: String, displayName: String = ""): Kind? {
        when (sensorId) {
            "cvt_temp" -> return Kind.CVT
            "coolant_temp" -> return Kind.COOLANT
            "ambient_temp" -> return Kind.AMBIENT
        }
        val name = displayName.lowercase()
        return if (name.contains("cvt") || name.contains("коробк")) Kind.CVT else null
    }

    fun legend(kind: Kind): String = when (kind) {
        Kind.CVT -> LEGEND_CVT
        Kind.COOLANT -> LEGEND_COOLANT
        Kind.AMBIENT -> LEGEND_AMBIENT
    }

    /** Integer exactly as `String.format("%.0f", c)` shows it. */
    fun displayed(c: Double): Int = BigDecimal(c).setScale(0, RoundingMode.HALF_UP).toInt()

    fun band(kind: Kind, celsius: Double?): TempBand? {
        if (celsius == null || celsius.isNaN()) return null
        val t = displayed(celsius)
        return when (kind) {
            Kind.CVT -> when {
                t < 20 -> TempBand.COLD
                t < 100 -> TempBand.NORMAL
                else -> TempBand.OVERHEAT
            }
            Kind.COOLANT -> when {
                t < 70 -> TempBand.COLD
                t <= 104 -> TempBand.NORMAL
                t <= 109 -> TempBand.HOT
                else -> TempBand.OVERHEAT
            }
            Kind.AMBIENT -> when {
                t <= -10 -> TempBand.FROST
                t <= 3 -> TempBand.ICE
                t <= 15 -> TempBand.COOL
                t <= 25 -> TempBand.NORMAL
                t <= 30 -> TempBand.HOT
                else -> TempBand.OVERHEAT
            }
        }
    }

    /** Value text shared by phone and car: "21 °C", "❄ 2 °C" (ice risk), or "—". */
    fun valueText(kind: Kind?, celsius: Double?, unit: String = "°C"): String {
        if (celsius == null || celsius.isNaN()) return "—"
        val num = "${displayed(celsius)} $unit"
        return if (kind == Kind.AMBIENT && band(kind, celsius) == TempBand.ICE) "❄ $num" else num
    }
}
