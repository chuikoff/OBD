package com.chuikoff.obd.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import com.chuikoff.obd.connection.AutoTrigger
import com.chuikoff.obd.viewmodel.ObdController

class ObdCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        // Android Auto started: connect the remembered adapter (if enabled in «Профиль»)
        runCatching { ObdController.get(carContext).autoConnect(AutoTrigger.ANDROID_AUTO) }
        return ObdCarScreen(carContext)
    }
}
