package com.g7monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.g7monitor.alarm.HypoAlarm
import com.g7monitor.shared.platform.androidPersistDir
import com.g7monitor.shared.ui.AppState
import com.g7monitor.shared.ui.PebbelsApp
import com.g7monitor.ui.SnScanDialog
import com.g7monitor.vm.G7Repository
import com.g7monitor.vm.G7ViewModel
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val vm: G7ViewModel by viewModels()
    private val showScanner = mutableStateOf(false)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        G7Repository.onPermissionsGranted()
        requestBatteryExemption()   // erst NACH dem Rechte-Dialog → nacheinander
    }

    // Export/Import über das System-Datei-UI (SAF) — die geteilte UI ruft die
    // Callbacks, die Engine schreibt/liest den Stream.
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { out ->
                vm.exportReadings(out)
                out.write(AppState.medBackupSuffix().toByteArray())   // Ereignisse anhängen
            }
        }
    }
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val t = input.readBytes().decodeToString()
                vm.importReadings(t.substringBefore("#PEBBELS_MEDS").byteInputStream())  // nur Glukose an die Engine
                AppState.importMeds(t)                                                   // Ereignisse separat
            }
        }
    }
    private val battLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        G7Repository.init(applicationContext)
        androidPersistDir = filesDir.path   // echte Persistenz für geteilten Code (Medikamente)
        requestRuntimePermissions()

        // Geteilte UI an die Android-Engine anbinden.
        AndroidBridge.wire(
            vm = vm,
            scope = lifecycleScope,
            onExport = {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm").format(Date())
                exportLauncher.launch("pebbels-backup-$stamp.jsonl")
            },
            onImport = {
                importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
            },
            onOpenUrl = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            onShareUrl = { url ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url)
                }
                runCatching { startActivity(Intent.createChooser(send, "Teilen")) }
            },
        )

        // Kamera-Scan der AiDEX-SN, ausgelöst aus der geteilten UI.
        AppState.onScanSerial = { showScanner.value = true }

        // Dieselbe geteilte Oberfläche wie auf iOS.
        setContent {
            MaterialTheme {
                PebbelsApp()
                if (showScanner.value) {
                    SnScanDialog(
                        onResult = { sn -> AppState.aidexSerial = sn; showScanner.value = false },
                        onDismiss = { showScanner.value = false },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // App im Vordergrund = Alarm gilt als gesehen → direkten Ton stoppen.
        HypoAlarm.stop()
    }

    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).forEach {
                if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) need += it
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                need += Manifest.permission.POST_NOTIFICATIONS
        }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
        else requestBatteryExemption()   // keine Rechte nötig → Akku-Dialog direkt
    }

    /** Akku-Optimierung-Ausnahme erbitten, damit Android den Hintergrund-Dienst
     *  nicht killt. Nur falls noch nicht erlaubt (sonst kein erneuter Dialog). */
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            battLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .apply { data = Uri.parse("package:$packageName") }
            )
        }
    }
}
