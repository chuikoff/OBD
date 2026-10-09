package com.chuikoff.obd.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.chuikoff.obd.data.ObdLiveData
import com.chuikoff.obd.ui.TempColorScale

/**
 * Android Auto projected screen: connection + live temps from [ObdLiveData].
 * Full BT/ELM connect is done in the phone app.
 */
class ObdCarScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val mem = ObdLiveData.snapshot.value
        val snap = if (mem.updatedAtMs > 0L) mem else ObdLiveData.loadFromPrefs(carContext)

        val statusLine = if (snap.connected) {
            "Подключено (телефон)"
        } else {
            "Нет связи — откройте приложение на телефоне и подключите ELM"
        }

        /**
         * Same scale as the phone cards. ForegroundCarColorSpan is allowed in Row text
         * (TEXT_WITH_COLORS_AND_ICON); custom colors carry light/dark variants and the host may
         * fall back to its default text color if it deems the contrast insufficient.
         */
        fun fmt(kind: TempColorScale.Kind, v: Double?): CharSequence {
            val text = TempColorScale.valueText(kind, v)
            val band = TempColorScale.band(kind, v) ?: return text
            val argb = band.argb.toInt()
            return SpannableString(text).apply {
                setSpan(
                    ForegroundCarColorSpan.create(CarColor.createCustom(argb, argb)),
                    0, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                )
            }
        }

        val paneBuilder = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("Статус")
                    .addText(statusLine)
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("ОЖ")
                    .addText(fmt(TempColorScale.Kind.COOLANT, snap.coolantC))
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("CVT")
                    .addText(fmt(TempColorScale.Kind.CVT, snap.cvtC))
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Снаружи")
                    .addText(fmt(TempColorScale.Kind.AMBIENT, snap.ambientC))
                    .apply { snap.ambientNote?.let { addText(it) } }
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Подсказка")
                    .addText("Полное BT-подключение — в телефонном приложении OBD Outlander.")
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Обновить")
                    .setOnClickListener { invalidate() }
                    .build()
            )

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("OBD Outlander")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
