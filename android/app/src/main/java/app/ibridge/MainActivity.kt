package app.ibridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ibridge.core.Link
import app.ibridge.core.Pairing
import app.ibridge.service.BridgeService
import app.ibridge.ui.C
import app.ibridge.ui.ChecklistScreen
import app.ibridge.ui.HomeScreen
import app.ibridge.ui.Item
import app.ibridge.ui.PairingSheet
import app.ibridge.ui.Row
import app.ibridge.ui.RowState
import app.ibridge.ui.Setup

class MainActivity : ComponentActivity() {
    /** Bumped on resume so rows re-read permission state after a trip to Settings. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        Link.init(this)
        BridgeService.start(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = C.accent, background = C.bg, surface = C.bg)) {
                // The mockups use Roboto with no tracking; Material's body styles add 0.25–0.5sp.
                ProvideTextStyle(LocalTextStyle.current.copy(letterSpacing = 0.sp)) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        BridgeService.start(this)
    }

    private fun openAppSettings() = startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    )

    @SuppressLint("BatteryLife")
    private fun askBattery() = startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
    )

    private fun bluetoothOn() = getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true

    @Composable
    private fun App() {
        val ui by Link.ui.collectAsStateWithLifecycle()
        val window by Pairing.window.collectAsStateWithLifecycle()
        val tick = resumes.intValue
        var permissionTick by remember { mutableIntStateOf(0) }
        val rows = remember(tick, permissionTick) { Setup.rows(this) }
        var showChecklist by rememberSaveable { mutableStateOf(false) }
        var sheetOpen by rememberSaveable { mutableStateOf(false) }
        var confirmUnlink by remember { mutableStateOf(false) }
        var pairAfterGrant by remember { mutableStateOf(false) }

        val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothOn()) { BridgeService.start(this); Pairing.open(); sheetOpen = true }
        }
        fun openPairing() {
            if (!bluetoothOn()) { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
            BridgeService.start(this)
            Pairing.open()
            sheetOpen = true
        }
        val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionTick++
            BridgeService.start(this)
            if (pairAfterGrant) {
                pairAfterGrant = false
                if (BridgeService.canStart(this)) openPairing()
            }
        }

        fun act(r: Row) {
            when {
                r.item == Item.BATTERY -> askBattery()
                r.state == RowState.DENIED && !r.askAgain -> openAppSettings()
                else -> { Setup.markAsked(this, r.item); request.launch(r.item.permissions.toTypedArray()) }
            }
        }

        fun pair() {
            if (!BridgeService.canStart(this)) {
                val nearby = rows.first { it.item == Item.NEARBY }
                if (nearby.state == RowState.DENIED && !nearby.askAgain) { openAppSettings(); return }
                Setup.markAsked(this, Item.NEARBY)
                pairAfterGrant = true
                request.launch(Item.NEARBY.permissions.toTypedArray())
                return
            }
            openPairing()
        }

        // A new pairing closes the sheet and lands on the home screen.
        val peerKey = ui.peer?.keyId?.contentToString()
        LaunchedEffect(peerKey) {
            if (sheetOpen && peerKey != null) { sheetOpen = false; showChecklist = false }
        }

        BackHandler(enabled = showChecklist && ui.peer != null) { showChecklist = false }

        if (ui.peer == null || showChecklist) {
            ChecklistScreen(rows, onAction = ::act, onPair = ::pair)
        } else {
            HomeScreen(
                ui = ui,
                warnings = rows.filter { it.state == RowState.DENIED || (it.item == Item.BATTERY && it.state != RowState.DONE) },
                onWarning = ::act,
                onPermissions = { showChecklist = true },
                onPairAnother = ::pair,
                onUnlink = { confirmUnlink = true },
            )
        }

        if (sheetOpen) {
            PairingSheet(
                window = window,
                deviceName = BridgeService.deviceName(this@MainActivity),
                onRenew = { Pairing.open() },
                onDismiss = { sheetOpen = false; Pairing.close() },
            )
        }

        if (confirmUnlink) {
            val name = ui.peer?.name ?: ""
            AlertDialog(
                onDismissRequest = { confirmUnlink = false },
                title = { Text(stringResource(R.string.unlink_title, name)) },
                text = { Text(stringResource(R.string.unlink_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUnlink = false
                        BridgeService.start(this, BridgeService.ACTION_UNLINK)
                        if (!BridgeService.canStart(this)) Link.setPeer(null)
                    }) { Text(stringResource(R.string.unlink_confirm), color = C.danger) }
                },
                dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
