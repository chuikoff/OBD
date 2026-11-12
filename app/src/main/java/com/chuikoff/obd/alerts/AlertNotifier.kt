package com.chuikoff.obd.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.car.app.model.CarColor
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chuikoff.obd.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Shows an [AlertEvent]: high-importance notification (heads-up on the phone, and in Android Auto
 * via [CarAppExtender]) + optional vibration, and emits it on [events] so the open Android Auto
 * screen shows an in-car Alert / toast.
 * Sound on/off = two channels (a channel's sound can't be changed after creation).
 */
object AlertNotifier {
    private const val CH_SOUND = "obd_alerts"
    private const val CH_SILENT = "obd_alerts_silent"
    private const val BASE_ID = 5300

    private val _events = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AlertEvent> = _events

    fun show(context: Context, event: AlertEvent, settings: AlertSettings) {
        _events.tryEmit(event)
        runCatching { notify(context, event, settings) }
        if (settings.vibrate) runCatching { vibrate(context) }
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        val sound = NotificationChannel(CH_SOUND, "Предупреждения (со звуком)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Перегрев CVT / ОЖ, риск гололёда"
            enableVibration(false) // vibration is ours (toggle in Профиль)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }
        val silent = NotificationChannel(CH_SILENT, "Предупреждения (без звука)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Перегрев CVT / ОЖ, риск гололёда — без звука"
            enableVibration(false)
            setSound(null, null)
        }
        mgr.createNotificationChannel(sound)
        mgr.createNotificationChannel(silent)
    }

    private fun notify(context: Context, e: AlertEvent, s: AlertSettings) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(context)
        val channel = if (s.sound) CH_SOUND else CH_SILENT
        val open = PendingIntent.getActivity(
            context, 10, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val color = if (e.kind == AlertKind.ICE) 0xFF1E88E5.toInt() else 0xFFE53935.toInt()
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(e.kind.titleRu)
            .setContentText(e.textRu)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.textRu))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(color)
            .setAutoCancel(true)
            .setContentIntent(open)
            .apply { if (!s.sound) setSilent(true) }
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(e.kind.titleRu)
                    .setContentText(e.textRu)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setColor(CarColor.createCustom(color, color))
                    .setChannelId(channel)
                    .build()
            )
            .build()
        NotificationManagerCompat.from(context).notify(BASE_ID + e.kind.ordinal, n)
    }

    private fun vibrate(context: Context) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
    }
}
