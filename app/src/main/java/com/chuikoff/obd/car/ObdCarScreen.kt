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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.chuikoff.obd.data.ObdLiveData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.chuikoff.obd.ui.TempColorScale
import androidx.car.app.AppManager
import androidx.car.app.CarToast
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.CarText
import androidx.car.app.versioning.CarAppApiLevels
import com.chuikoff.obd.alerts.AlertEvent
import com.chuikoff.obd.alerts.AlertNotifier
import com.chuikoff.obd.viewmodel.ObdController

/**
 * Android Auto projected screen: connection + live temps from [ObdLiveData].
 * Full BT/ELM connect is done in the phone app. Re-renders on every snapshot change while visible,
 * at most once per [MIN_REFRESH_MS] (refreshing the same PaneTemplate does not count as a new step).
 */
class ObdCarScreen(carContext: CarContext) : Screen(carContext) {

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ObdLiveData.snapshot.collect { // StateFlow is conflated: a slow collector sees only the latest
                    invalidate()
                    delay(MIN_REFRESH_MS)
                }
            }
        }
        // Threshold alerts (also posted as a notification with CarAppExtender) → in-car Alert / toast
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlertNotifier.events.collect { showInCar(it) }
            }
        }
    }

    private fun showInCar(e: AlertEvent) {
        runCatching {
            if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_5) {
                val alert = Alert.Builder(ALERT_ID_BASE + e.kind.ordinal, CarText.create(e.kind.titleRu), ALERT_DURATION_MS)
                    .setSubtitle(CarText.create(e.textRu))
                    .setCallback(object : AlertCallback {
                        override fun onCancel(reason: Int) {}
                        override fun onDismiss() {}
                    })
                    .build()
                carContext.getCarService(AppManager::class.java).showAlert(alert)
            } else {
                CarToast.makeText(carContext, "${e.kind.titleRu}: ${e.textRu}", CarToast.LENGTH_LONG).show()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val snap = ObdLiveData.snapshot.value

        val statusLine = if (snap.connected) {
            "Подключено (телефон)"
        } else {
            "Нет связи — ${snap.statusText}"
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
                    .apply { snap.cvtLockup?.let { addText(it) } }
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Снаружи")
                    .addText(fmt(TempColorScale.Kind.AMBIENT, snap.ambientC))
                    .apply { snap.ambientNote?.let { addText(it) } }
                    .build()
            )
            .addRow(engineRow(snap))
            .addAction(
                if (snap.connected) {
                    Action.Builder()
                        .setTitle("Обновить")
                        .setOnClickListener { invalidate() }
                        .build()
                } else {
                    Action.Builder()
                        .setTitle("Подключить")
                        .setOnClickListener {
                            val ok = ObdController.get(carContext).connectLast()
                            if (!ok) CarToast.makeText(carContext, "Адаптер ещё не подключался — подключи на телефоне", CarToast.LENGTH_LONG).show()
                            invalidate()
                        }
                        .build()
                }
            )

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("OBD Outlander")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    /** «Двигатель: 14.1 В · 850 об/мин · 1.1 л/ч» while connected, otherwise the old hint. */
    private fun engineRow(snap: ObdLiveData.Snapshot): Row {
        val e = snap.engine
        if (!snap.connected || e == null) {
            return Row.Builder()
                .setTitle("Подсказка")
                .addText("Подключение — в приложении OBD на телефоне или кнопкой «Подключить».")
                .build()
        }
        val parts = listOfNotNull(e.voltageText, e.rpmText, e.fuelText)
        val text = parts.joinToString(" · ")
        val span = SpannableString(text)
        val v = e.voltageText
        val argb = e.voltageArgb
        if (v != null && argb != null) {
            span.setSpan(
                ForegroundCarColorSpan.create(CarColor.createCustom(argb.toInt(), argb.toInt())),
                0, v.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }
        return Row.Builder().setTitle("Двигатель").addText(span).build()
    }

    private companion object {
        const val MIN_REFRESH_MS = 1_000L
        const val ALERT_ID_BASE = 700
        const val ALERT_DURATION_MS = 10_000L
    }
}
