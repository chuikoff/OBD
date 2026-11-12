package com.chuikoff.obd.connection

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.chuikoff.obd.viewmodel.ObdController

/**
 * ACL_CONNECTED (exempt from the implicit-broadcast ban): the phone just connected to the car's
 * head unit / hands-free, or to the adapter. Auto-connect decides if it is worth trying
 * ([AutoConnectPolicy]); the toggle lives in «Профиль».
 */
class BtAutoConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = runCatching { dev?.address }.getOrNull()
        val cls = runCatching { dev?.bluetoothClass?.deviceClass }.getOrNull() // SecurityException without permission
        runCatching { ObdController.get(context).autoConnect(AutoTrigger.BLUETOOTH, address, cls) }
    }
}
