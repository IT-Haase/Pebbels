/*
 * Tab "Einstellungen".
 *
 * Enthält:
 *   - Pairing-Card (PIN, Scan/Stoppen, Vergessen) — technisch identisch
 *     zur alten G7Screen-Card, nur verschoben.
 *   - TIR-Grenzen für die Statistik.
 *   - Hypo-/Hyper-Alarm-Konfiguration.
 *   - System-Status (Native Lib, Scan-Log).
 *
 * Die Sliders/Zahlen-Felder schreiben direkt in den SettingsStore
 * (über G7ViewModel), deshalb kein lokales "Apply"-Button.
 */
package com.g7monitor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.g7monitor.alarm.HypoAlarm
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.g7monitor.R
import com.g7monitor.vm.ConnectionState
import com.g7monitor.vm.G7State
import com.g7monitor.vm.G7ViewModel
import com.g7monitor.vm.SettingsStore
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
// OCR (ML-Kit) + CameraX — liest die aufgedruckte 4-stellige Dexcom-PIN.
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(vm: G7ViewModel, state: G7State) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showResetDialog by remember { mutableStateOf(false) }
    // Welcher Sensor-Verlauf soll gelöscht werden? null | "dexcom" | "aidex"
    var clearTarget by remember { mutableStateOf<String?>(null) }
    val scanCtx = LocalContext.current

    // Kamera-Scan IN-APP (kleine Vorschau, kein extra Fenster) für PIN/SN.
    var scanTarget by remember { mutableStateOf<String?>(null) }   // "pin" | "sn" | null
    var pendingTarget by remember { mutableStateOf<String?>(null) }
    // Roh-Inhalt des letzten Scans — bleibt im Popup sichtbar, damit wir genau
    // sehen, was im Code steckt (z. B. ob die Dexcom-PIN drin ist).
    var lastScanRaw by remember { mutableStateOf<String?>(null) }
    var lastScanInfo by remember { mutableStateOf<String?>(null) }
    val cameraPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) scanTarget = pendingTarget
        else Toast.makeText(scanCtx, "Kamera-Berechtigung nötig zum Scannen", Toast.LENGTH_SHORT).show()
        pendingTarget = null
    }
    fun requestScan(target: String) {
        if (ContextCompat.checkSelfPermission(scanCtx, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) scanTarget = target
        else { pendingTarget = target; cameraPerm.launch(Manifest.permission.CAMERA) }
    }
    fun onScanned(raw: String) {
        lastScanRaw = raw
        val digits = raw.filter { it.isDigit() }
        lastScanInfo = if (digits.length == 4) {
            vm.updatePin(digits); "PIN übernommen: $digits"
        } else {
            "Keine reine 4-stellige PIN — PIN unverändert."
        }
        // Popup bleibt offen und zeigt das Ergebnis an (zur Kontrolle).
    }

    val card  = Color(0x15FFFFFF)
    val dim   = Color(0x88FFFFFF)
    val faint = Color(0x55FFFFFF)
    val green = Color(0xFF22C55E)
    val red   = Color(0xFFF87171)

    val isActive = state.connection in setOf(
        ConnectionState.Scanning,
        ConnectionState.Connecting,
        ConnectionState.Bonded,
        ConnectionState.Authenticating,
        ConnectionState.Authenticated,
        ConnectionState.Receiving,
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Sprache / Language --------------------------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val langCtx = LocalContext.current
            var langTick by remember { mutableStateOf(0) }
            val currentLang = remember(langTick) { currentAppLanguageTag(langCtx) }
            Text(stringResource(R.string.set_language), color = faint, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LangChip(stringResource(R.string.lang_system), currentLang.isEmpty(), green) {
                    setAppLanguage(langCtx, ""); langTick++
                }
                LangChip("Deutsch", currentLang == "de", green) {
                    setAppLanguage(langCtx, "de"); langTick++
                }
                LangChip("English", currentLang == "en", green) {
                    setAppLanguage(langCtx, "en"); langTick++
                }
                LangChip("Español", currentLang == "es", green) {
                    setAppLanguage(langCtx, "es"); langTick++
                }
            }
            Text(stringResource(R.string.set_language_hint), color = dim, fontSize = 11.sp)
        }

        // --- Dexcom: Pairing / PIN -----------------------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.set_pair), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            OutlinedTextField(
                value = state.pin,
                onValueChange = vm::updatePin,
                label = { Text(stringResource(R.string.set_pin_label), color = dim) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { requestScan("pin") }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scannen", tint = green)
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = TextStyle(
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    color = Color.White
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = green,
                    unfocusedBorderColor = faint,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = green
                ),
                enabled = !isActive
            )

            if (state.deviceName != null || state.deviceAddress != null) {
                Column {
                    Text(stringResource(R.string.set_connection), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    state.deviceName?.let { Text(it, color = Color.White, fontSize = 15.sp) }
                    state.deviceAddress?.let {
                        Text(it, color = dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (state.handshakePhase >= 0) {
                        Text(
                            stringResource(R.string.set_handshake, state.handshakePhase),
                            color = dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Zwei Aktionen: Verbindung an/aus + neuer Sensor (= Neu-Kopplung
            // mit dem eingegebenen Code, Verlauf bleibt erhalten).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (isActive) vm.stopScan() else vm.startScan() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = green),
                    enabled = state.nativeLoaded
                ) {
                    Text(
                        if (isActive) stringResource(R.string.common_stop) else stringResource(R.string.common_start),
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1
                    )
                }
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, green.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = green),
                    enabled = state.nativeLoaded
                ) {
                    Text(stringResource(R.string.set_new_sensor), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        // --- Kalibrierung (manuelle Messung / Fingerstick) -----------------
        var calibInput by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.set_calib), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.set_calib_hint), color = dim, fontSize = 11.sp)
            Text(
                stringResource(
                    R.string.set_calib_status,
                    state.lastGlucose?.toString() ?: "—",
                    (if (settings.calibOffset >= 0) "+" else "") + settings.calibOffset
                ),
                color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace
            )
            OutlinedTextField(
                value = calibInput,
                onValueChange = { calibInput = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text(stringResource(R.string.set_calib_label), color = dim) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = green, unfocusedBorderColor = faint,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = green
                )
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { calibInput.toIntOrNull()?.let { if (vm.calibrate(it)) calibInput = "" } },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = green),
                    enabled = calibInput.toIntOrNull() != null && state.lastGlucose != null
                ) { Text(stringResource(R.string.set_calib_do), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) }
                OutlinedButton(
                    onClick = { vm.clearCalibration() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, green.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = green),
                    enabled = settings.calibOffset != 0
                ) { Text(stringResource(R.string.set_calib_reset), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) }
            }
        }

        // --- Verlauf pro Sensor löschen ------------------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("VERLAUF LÖSCHEN", color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(
                "Löscht den kompletten gespeicherten Verlauf. Nicht umkehrbar.",
                color = dim, fontSize = 11.sp
            )
            OutlinedButton(
                onClick = { clearTarget = "dexcom" },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, green.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = green),
            ) { Text("Verlauf leeren", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) }
        }

        // --- Hintergrund-Betrieb / Akku-Optimierung ------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val ctx = LocalContext.current
            // checkTick erzwingt eine Neu-Prüfung, nachdem der User aus dem
            // System-Dialog zurückkommt.
            var checkTick by remember { mutableStateOf(0) }
            val ignoring = remember(checkTick) {
                val pm = ctx.getSystemService(PowerManager::class.java)
                pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
            }
            val battLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { checkTick++ }

            Text(stringResource(R.string.set_bg), color = faint, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace)
            Text(
                stringResource(R.string.set_bg_text),
                color = dim, fontSize = 11.sp
            )
            if (ignoring) {
                Text(
                    stringResource(R.string.set_bg_ok),
                    color = green, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    stringResource(R.string.set_bg_warn),
                    color = Color(0xFFFBBF24), fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Button(
                    onClick = {
                        try {
                            val intent = Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                            ).apply { data = Uri.parse("package:${ctx.packageName}") }
                            battLauncher.launch(intent)
                        } catch (_: Throwable) {
                            // Fallback: allgemeine Akku-Einstellungen öffnen.
                            try {
                                battLauncher.launch(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            } catch (_: Throwable) {}
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = green),
                ) {
                    Text(stringResource(R.string.set_bg_btn),
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        // --- TIR-Grenzen ---------------------------------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.set_tir), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.set_tir_low, settings.tirLow),
                color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = settings.tirLow.toFloat(),
                onValueChange = { v ->
                    val low = v.toInt().coerceIn(40, settings.tirHigh - 10)
                    vm.updateTir(low, settings.tirHigh)
                },
                valueRange = 40f..200f,
                steps = 15,
            )
            Text(stringResource(R.string.set_tir_high, settings.tirHigh),
                color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = settings.tirHigh.toFloat(),
                onValueChange = { v ->
                    val high = v.toInt().coerceIn(settings.tirLow + 10, 300)
                    vm.updateTir(settings.tirLow, high)
                },
                valueRange = 100f..300f,
                steps = 19,
            )
        }

        // --- Hypo-Alarm ----------------------------------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.set_hypo), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Switch(
                    checked = settings.hypoEnabled,
                    onCheckedChange = { vm.updateHypo(it, settings.hypoThreshold) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = green,
                    )
                )
            }
            Text(stringResource(R.string.set_threshold, settings.hypoThreshold),
                color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = settings.hypoThreshold.toFloat(),
                onValueChange = { v ->
                    vm.updateHypo(settings.hypoEnabled, v.toInt().coerceIn(40, 100))
                },
                valueRange = 40f..100f,
                steps = 11,
                enabled = settings.hypoEnabled,
            )
        }

        // --- Hyper-Alarm ---------------------------------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.set_hyper), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Switch(
                    checked = settings.hyperEnabled,
                    onCheckedChange = { vm.updateHyper(it, settings.hyperThreshold) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = green,
                    )
                )
            }
            Text(stringResource(R.string.set_threshold, settings.hyperThreshold),
                color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = settings.hyperThreshold.toFloat(),
                onValueChange = { v ->
                    vm.updateHyper(settings.hyperEnabled, v.toInt().coerceIn(150, 400))
                },
                valueRange = 150f..400f,
                steps = 24,
                enabled = settings.hyperEnabled,
            )
        }

        // --- Alarm-Verhalten -----------------------------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.set_alarm_behavior), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(stringResource(R.string.set_repeat, settings.alarmRepeatMin),
                color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Slider(
                value = settings.alarmRepeatMin.toFloat(),
                onValueChange = { v ->
                    vm.updateAlarmBehavior(v.toInt().coerceIn(1, 60),
                        settings.alarmSound, settings.alarmVibrate)
                },
                valueRange = 1f..60f,
                steps = 11,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.set_sound), color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = settings.alarmSound,
                    onCheckedChange = {
                        vm.updateAlarmBehavior(settings.alarmRepeatMin, it, settings.alarmVibrate)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = green,
                    )
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.set_vibration), color = Color.White, fontSize = 13.sp)
                Switch(
                    checked = settings.alarmVibrate,
                    onCheckedChange = {
                        vm.updateAlarmBehavior(settings.alarmRepeatMin, settings.alarmSound, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = green,
                    )
                )
            }
            val alarmCtx = LocalContext.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val msg = vm.runAlarmSelfTest()
                        Toast.makeText(alarmCtx, msg, Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = green),
                ) {
                    Text(stringResource(R.string.set_alarm_test), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = { HypoAlarm.stop() },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = green),
                ) {
                    Text(stringResource(R.string.set_stop_alarm), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                }
            }
            Text(
                stringResource(R.string.set_alarm_test_hint),
                color = dim, fontSize = 11.sp
            )
        }

        // --- Cloud-Upload (Pebbels-Dashboard) ------------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val context = LocalContext.current
            val dashUrl = SettingsStore.dashboardUrl(settings.cloudUuid)

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.set_cloud), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Switch(
                    checked = settings.uploadEnabled,
                    onCheckedChange = { vm.updateUpload(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = green,
                    )
                )
            }
            Text(
                stringResource(R.string.set_cloud_text),
                color = dim, fontSize = 11.sp
            )
            // QR-Code der Dashboard-URL — mittig, auf weißem Grund.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp)
                    ) {
                        QrCode(content = dashUrl, sizeDp = 200.dp)
                    }
                }
            }
            // Dashboard-URL als Text — auch zum Abtippen/Kontrollieren.
            Text(
                dashUrl,
                color = dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            // Öffnen + Teilen
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(dashUrl))
                            )
                        } catch (_: Throwable) {}
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = green),
                ) {
                    Text(stringResource(R.string.set_dash_open), fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                        maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Pebbels Dashboard")
                            putExtra(Intent.EXTRA_TEXT, dashUrl)
                        }
                        try {
                            context.startActivity(Intent.createChooser(send, context.getString(R.string.set_dash_share_title)))
                        } catch (_: Throwable) {}
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = green),
                ) {
                    Text(stringResource(R.string.set_share_link), fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                        maxLines = 1)
                }
            }
            // Status des letzten Upload-Versuchs.
            val uploadAt = state.lastUploadAt
            if (uploadAt != null) {
                val t = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(uploadAt))
                Text(
                    stringResource(R.string.set_last_upload, t, state.lastUploadMsg ?: "?"),
                    color = dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    if (settings.uploadEnabled) stringResource(R.string.set_no_upload)
                    else stringResource(R.string.set_upload_off),
                    color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        // --- Daten sichern / importieren -----------------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val ctx = LocalContext.current
            var dataMsg by remember { mutableStateOf<String?>(null) }

            // Export: System-Dialog zum Wählen des Speicherorts. Die App
            // bekommt einen OutputStream auf die gewählte Datei.
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json")
            ) { uri: Uri? ->
                if (uri != null) {
                    dataMsg = try {
                        val n = ctx.contentResolver.openOutputStream(uri)
                            ?.use { vm.exportReadings(it) } ?: 0
                        ctx.getString(R.string.set_exported, n)
                    } catch (t: Throwable) {
                        ctx.getString(R.string.set_export_err, t.message ?: "?")
                    }
                }
            }
            // Import: System-Dialog zum Wählen einer Datei.
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    dataMsg = try {
                        val n = ctx.contentResolver.openInputStream(uri)
                            ?.use { vm.importReadings(it) } ?: 0
                        ctx.getString(R.string.set_imported, n)
                    } catch (t: Throwable) {
                        ctx.getString(R.string.set_import_err, t.message ?: "?")
                    }
                }
            }

            Text(stringResource(R.string.set_backup), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(
                stringResource(R.string.set_backup_text),
                color = dim, fontSize = 11.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm")
                            .format(java.util.Date())
                        exportLauncher.launch("pebbels-backup-$stamp.jsonl")
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = green),
                ) {
                    Text(stringResource(R.string.set_export_btn), fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                        maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "application/json", "text/plain",
                                "application/octet-stream", "*/*"
                            )
                        )
                    },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = green),
                ) {
                    Text(stringResource(R.string.set_import_btn), fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                        maxLines = 1)
                }
            }
            if (dataMsg != null) {
                Text(
                    dataMsg!!,
                    color = dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
            }
        }

        // --- System --------------------------------------------------------
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp)
        ) {
            Text("SYSTEM", color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(6.dp))
            Text(
                "Native Library: " + if (state.nativeLoaded) "geladen" else "FEHLER",
                color = if (state.nativeLoaded) green else red,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            state.nativeError?.let {
                Text(it, color = red, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            state.lastGlucoseAt?.let {
                Text(
                    "Letzter Wert: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(it))}",
                    color = dim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            if (state.scanLog.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("BLE-SCAN", color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                state.scanLog.forEach { line ->
                    Text(line, color = dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // --- Debug / Protokoll (früher eigener Tab) ------------------------
        DebugTab(vm, state)
    }

    // --- Kamera-Scan-Popup (zentriert) ------------------------------------
    if (scanTarget != null) {
        val closeScan = { scanTarget = null; lastScanRaw = null; lastScanInfo = null }
        Dialog(onDismissRequest = closeScan) {
            Column(
                Modifier.width(300.dp).clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF111511)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "PIN scannen",
                    color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                if (lastScanRaw == null) {
                    // Dexcom-PIN steht nur aufgedruckt → OCR liest die große Zahl.
                    Text(
                        "Große 4-stellige Zahl auf den Applikator richten",
                        color = dim, fontSize = 11.sp, textAlign = TextAlign.Center
                    )
                    PinOcrScanner(onPin = { onScanned(it) })
                    TextButton(onClick = closeScan) {
                        Text(stringResource(R.string.common_cancel), color = green)
                    }
                } else {
                    Text("Erkannte PIN:", color = dim, fontSize = 11.sp)
                    Text(
                        lastScanRaw!!,
                        color = Color.White, fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center
                    )
                    lastScanInfo?.let {
                        Text(it, color = green, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { lastScanRaw = null; lastScanInfo = null }) {
                            Text("Nochmal", color = dim)
                        }
                        TextButton(onClick = closeScan) { Text("OK", color = green) }
                    }
                }
            }
        }
    }

    // --- Verlauf-löschen-Bestätigung -------------------------------------
    if (clearTarget != null) {
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text("Verlauf leeren?") },
            text = {
                Text(
                    "Der komplette gespeicherte Verlauf wird gelöscht. Nicht umkehrbar.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearSensorHistory(clearTarget!!)
                    clearTarget = null
                }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { clearTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // --- „Neuer Sensor"-Bestätigung (= Hard-Reset, Verlauf bleibt) ---------
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.set_new_sensor)) },
            text = { Text(stringResource(R.string.set_new_sensor_text), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { showResetDialog = false; vm.hardReset() }) {
                    Text(stringResource(R.string.set_new_sensor))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** Eine Sprach-Auswahl-Kachel in der Sprach-Zeile. */
@Composable
private fun RowScope.LangChip(
    label: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier.weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.25f) else Color(0x10FFFFFF))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) accent else Color(0x88FFFFFF),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/** Eingebettete Kamera-Vorschau mit OCR (ML-Kit) — liest die aufgedruckte
 *  4-stellige Dexcom-PIN. Pickt das 4-Ziffern-Textelement mit der GRÖSSTEN
 *  Schrift (= die große Zahl auf dem Applikator, z. B. 6558) und ignoriert so
 *  Datumsangaben, GTIN und Seriennummer. Liefert genau einmal. */
@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun PinOcrScanner(onPin: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    val providerHolder = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val done = remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            providerHolder.value?.unbindAll()
            recognizer.close()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                providerHolder.value = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                    val media = proxy.image
                    if (media == null || done.value) { proxy.close(); return@setAnalyzer }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    recognizer.process(image)
                        .addOnSuccessListener { text ->
                            var best: String? = null
                            var bestH = 0
                            for (block in text.textBlocks)
                                for (line in block.lines)
                                    for (el in line.elements) {
                                        val t = el.text
                                        if (t.length == 4 && t.all { c -> c.isDigit() }) {
                                            val h = el.boundingBox?.height() ?: 0
                                            if (h > bestH) { bestH = h; best = t }
                                        }
                                    }
                            val b = best
                            if (b != null && !done.value) { done.value = true; onPin(b) }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                } catch (_: Throwable) {}
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

/** Aktuell gesetzte App-Sprache als Tag ("de"/"en"/"es"); "" = System-Standard.
 *  Die per-App-Sprache gibt es als Framework-API ab Android 13 (API 33). */
private fun currentAppLanguageTag(ctx: Context): String {
    if (Build.VERSION.SDK_INT >= 33) {
        val locales = ctx.getSystemService(LocaleManager::class.java)?.applicationLocales
        if (locales != null && !locales.isEmpty) return locales[0].language
    }
    return ""
}

/** Setzt die App-Sprache (leeres Tag = System-Standard). Android startet die
 *  Activity neu, danach zeigt die UI sofort die neue Sprache. Ab Android 13. */
private fun setAppLanguage(ctx: Context, tag: String) {
    if (Build.VERSION.SDK_INT >= 33) {
        val lm = ctx.getSystemService(LocaleManager::class.java) ?: return
        lm.applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(tag)
    }
}
