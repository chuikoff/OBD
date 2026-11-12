package com.chuikoff.obd

import androidx.compose.ui.res.painterResource
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.chuikoff.obd.ui.screens.ConnectScreen
import com.chuikoff.obd.ui.screens.DiagnosticsScreen
import com.chuikoff.obd.ui.screens.PassportScreen
import com.chuikoff.obd.ui.screens.ProfileScreen
import com.chuikoff.obd.ui.screens.ReadingsScreen
import com.chuikoff.obd.ui.theme.ObdTheme
import com.chuikoff.obd.viewmodel.ObdController
import com.chuikoff.obd.connection.AutoTrigger

class MainActivity : ComponentActivity() {

    private val vm: ObdController by lazy { ObdController.get(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.autoConnect(AutoTrigger.APP_START) } // just granted «Устройства поблизости» → try now

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()
        if (savedInstanceState == null) vm.autoConnect(AutoTrigger.APP_START)
        setContent {
            ObdTheme {
                ObdApp(vm, onEnsurePermissions = ::ensurePermissions)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) vm.onUiDestroyed()
        super.onDestroy()
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ).forEach {
                if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                    needed += it
                }
            }
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ).forEach {
                if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                    needed += it
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

@Composable
fun ObdApp(vm: ObdController, onEnsurePermissions: () -> Unit = {}) {
    val state by vm.ui.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    alwaysShowLabel = true,
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null) },
                    label = {
                        Text("Подключ.", fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, letterSpacing = 0.sp)
                    }
                )
                NavigationBarItem(
                    alwaysShowLabel = true,
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(painterResource(R.drawable.ic_speed), contentDescription = null) },
                    label = {
                        Text("Показания", fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, letterSpacing = 0.sp)
                    }
                )
                NavigationBarItem(
                    alwaysShowLabel = true,
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(painterResource(R.drawable.ic_build), contentDescription = null) },
                    label = {
                        Text("Ошибки", fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, letterSpacing = 0.sp)
                    }
                )
                NavigationBarItem(
                    alwaysShowLabel = true,
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(painterResource(R.drawable.ic_badge), contentDescription = null) },
                    label = {
                        Text("Авто", fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, letterSpacing = 0.sp)
                    }
                )
                NavigationBarItem(
                    alwaysShowLabel = true,
                    selected = tab == 4,
                    onClick = { tab = 4 },
                    icon = { Icon(painterResource(R.drawable.ic_directions_car), contentDescription = null) },
                    label = {
                        Text("Профиль", fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible, letterSpacing = 0.sp)
                    }
                )
            }
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> ConnectScreen(
                    state = state,
                    onTransport = vm::setTransport,
                    // re-ask for «Устройства поблизости» if it was denied; VM shows a hint meanwhile
                    onScan = { onEnsurePermissions(); vm.startScan() },
                    onStopScan = vm::stopScan,
                    onRefreshBonded = { onEnsurePermissions(); vm.refreshBondedClassic() },
                    onConnect = vm::connect,
                    onDisconnect = vm::disconnect
                )
                1 -> ReadingsScreen(
                    state = state,
                    onCvtFormula = vm::setCvtFormula,
                    onCvtDebugExtra = vm::setCvtDebugExtra,
                    onClearCvtLog = vm::clearCvtLog,
                    cvtExport = vm::cvtLogExportText
                )
                2 -> DiagnosticsScreen(
                    state = state,
                    onRead = vm::readDtcs,
                    onClearConfirmed = { vm.clearDtcs(confirmed = true) }
                )
                3 -> PassportScreen(state, onRefresh = vm::readPassport, exportText = vm::passportExportText,
                    onReadCvtOil = vm::readCvtOil)
                else -> ProfileScreen(
                    state,
                    onSelect = vm::selectProfile,
                    onAlertSettings = vm::setAlertSettings,
                    onTestAlert = vm::testAlert,
                    onAutoConnect = vm::setAutoConnect,
                    onAutoConnectBluetooth = vm::setAutoConnectOnBluetooth
                )
            }
        }
    }
}
