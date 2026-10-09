package com.chuikoff.obd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Bluetooth
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
import com.chuikoff.obd.ui.screens.ProfileScreen
import com.chuikoff.obd.ui.screens.ReadingsScreen
import com.chuikoff.obd.ui.theme.ObdTheme
import com.chuikoff.obd.viewmodel.ObdViewModel

class MainActivity : ComponentActivity() {

    private val vm: ObdViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* UI will re-enable actions after grant */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensurePermissions()
        setContent {
            ObdTheme {
                ObdApp(vm)
            }
        }
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
fun ObdApp(vm: ObdViewModel) {
    val state by vm.ui.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                    label = {
                        Text("Подключ.", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Clip)
                    }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Speed, contentDescription = null) },
                    label = {
                        Text("Показания", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Clip)
                    }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = {
                        Text("Ошибки", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Clip)
                    }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.DirectionsCar, contentDescription = null) },
                    label = {
                        Text("Профиль", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Clip)
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
                    onScan = vm::startScan,
                    onStopScan = vm::stopScan,
                    onRefreshBonded = vm::refreshBondedClassic,
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
                else -> ProfileScreen(state, onSelect = vm::selectProfile)
            }
        }
    }
}
