package com.g7monitor.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.g7monitor.shared.platform.currentTimeMillis
import com.g7monitor.shared.platform.formatLocalTime
import com.g7monitor.shared.platform.ensureNotificationPermission
import com.g7monitor.shared.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.sqrt

private val GREEN = Color(0xFF22C55E)
private val AMBER = Color(0xFFFBBF24)
private val RED   = Color(0xFFF87171)
// Distinct Farben je Ereignis-Typ (vom Namen abgeleitet, stabil)
private val EVENT_COLORS = listOf(
    Color(0xFF8AB4FF), Color(0xFFC084FC), Color(0xFFF472B6),
    Color(0xFF22D3EE), Color(0xFFFB923C), Color(0xFFA3E635))
private fun eventColor(name: String): Color = EVENT_COLORS[abs(name.hashCode()) % EVENT_COLORS.size]

// Hell/Dunkel: dunkle Palette = exakt die bisherigen Farben (kein Unterschied im
// Dunkelmodus); helle Palette neu. Folgt der System-Einstellung.
private object Theme {
    var bg    by mutableStateOf(Color(0xFF060A06))
    var card  by mutableStateOf(Color(0x15FFFFFF))
    var text  by mutableStateOf(Color.White)
    var dim   by mutableStateOf(Color(0x88FFFFFF))
    var faint by mutableStateOf(Color(0x55FFFFFF))
    var navBg by mutableStateOf(Color(0xFF0D120D))
    fun apply(dark: Boolean) {
        if (dark) {
            bg = Color(0xFF060A06); card = Color(0x15FFFFFF); text = Color.White
            dim = Color(0x88FFFFFF); faint = Color(0x55FFFFFF); navBg = Color(0xFF0D120D)
        } else {
            bg = Color(0xFFF2F3EF); card = Color(0x0F101010); text = Color(0xFF0B0F0B)
            dim = Color(0xAA101010); faint = Color(0x55101010); navBg = Color(0xFFE7E9E3)
        }
    }
}
private val BG: Color    get() = Theme.bg
private val CARD: Color  get() = Theme.card
private val DIM: Color   get() = Theme.dim
private val FAINT: Color get() = Theme.faint
private val TEXT: Color  get() = Theme.text
private val NAVBG: Color get() = Theme.navBg

private enum class Tab { Values, Stats, Settings, Info }

private fun fmtRate(r: Double): String {
    val s = if (r >= 0) "+" else "-"; val a = abs(r)
    return "$s${a.toInt()}.${((a - a.toInt()) * 10).toInt()} mg/dL/min"
}
private fun relTime(ms: Long): String {
    if (ms <= 0) return ""
    val d = (currentTimeMillis() - ms) / 1000
    return when { d < 60 -> "gerade eben"; d < 3600 -> "vor ${d / 60} Min"; else -> "vor ${d / 3600} Std" }
}
private fun winLabel(h: Int) = if (h < 24) "${h}h" else "${h / 24}d"

@Composable
fun PebbelsApp() {
    var tab by remember { mutableStateOf(Tab.Values) }
    val focusManager = LocalFocusManager.current
    val dark = when (AppState.themeMode) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    LaunchedEffect(dark) { Theme.apply(dark) }
    LaunchedEffect(Unit) { I18n.preload() }
    LaunchedEffect(Unit) { Persistence.loadMeds() }
    LaunchedEffect(Unit) { Persistence.loadSensorStart() }
    LaunchedEffect(Unit) { ensureNotificationPermission() }
    LaunchedEffect(Unit) {
        snapshotFlow {
            listOf(AppState.language, AppState.themeMode, AppState.windowHours, AppState.statsRangeHours, AppState.tirLow, AppState.tirHigh,
                AppState.hypoEnabled, AppState.hypoThreshold, AppState.hyperEnabled, AppState.hyperThreshold,
                AppState.alarmRepeatMin, AppState.alarmSound, AppState.alarmVibrate, AppState.calibOffset,
                AppState.uploadEnabled, AppState.cloudUuid, AppState.pin,
                AppState.sensorType, AppState.aidexSerial)
        }.collect { Persistence.saveSettings() }
    }
    val live = AppState.live && AppState.lastGlucose != null
    val lamp = if (live) GREEN else if (AppState.connected) AMBER else FAINT
    Scaffold(
        containerColor = BG,
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(BG).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        tr(when (tab) {
                            Tab.Values -> "app_name"; Tab.Stats -> "tab_stats"
                            Tab.Settings -> "tab_settings"; Tab.Info -> "info_title"
                        }),
                        color = TEXT, fontSize = 22.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text("CGM-Monitor", color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(lamp))
            }
        },
        bottomBar = {
            NavigationBar(containerColor = NAVBG, contentColor = TEXT) {
                navItem(tab == Tab.Values, { tab = Tab.Values }, Icons.Filled.ShowChart, tr("nav_values"))
                navItem(tab == Tab.Stats, { tab = Tab.Stats }, Icons.Outlined.BarChart, tr("tab_stats"))
                navItem(tab == Tab.Settings, { tab = Tab.Settings }, Icons.Filled.Settings, tr("tab_settings"))
                navItem(tab == Tab.Info, { tab = Tab.Info }, Icons.Filled.Pets, tr("nav_info"))
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(BG)
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
            when (tab) { Tab.Values -> ValuesTab(); Tab.Stats -> StatsTab(); Tab.Settings -> SettingsTab(); Tab.Info -> InfoTab() }
        }
    }
}

@Composable
private fun RowScope.navItem(sel: Boolean, onClick: () -> Unit, icon: ImageVector, label: String) {
    NavigationBarItem(
        selected = sel, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color.White, selectedTextColor = Color.White,
            indicatorColor = GREEN.copy(alpha = 0.35f), unselectedIconColor = DIM, unselectedTextColor = DIM
        )
    )
}

@Composable private fun card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CARD).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
@Composable private fun label(t: String) = Text(t, color = FAINT, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
@Composable private fun RowScope.greenButton(text: String, enabled: Boolean = true, color: Color = GREEN, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f).height(48.dp),
        shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = color,
            disabledContainerColor = Color(0x3322C55E), disabledContentColor = Color(0x99FFFFFF))) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
    }
}
@Composable private fun ThemedSlider(value: Float, onValueChange: (Float) -> Unit, valueRange: ClosedFloatingPointRange<Float>, enabled: Boolean = true, steps: Int = 0) {
    Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, enabled = enabled, steps = steps,
        colors = SliderDefaults.colors(thumbColor = GREEN, activeTrackColor = GREEN,
            inactiveTrackColor = FAINT, disabledThumbColor = Color(0x55FFFFFF),
            disabledActiveTrackColor = Color(0x33FFFFFF), disabledInactiveTrackColor = Color(0x22FFFFFF)))
}

// ---------------- Werte ----------------
/** Dezenter Sensor-Ablauf-Hinweis direkt unter dem aktuellen Wert — kein Alarm,
 *  kein Popup. G7/ONE+: 10 Tage + 12 h Ersatzzeit; AiDEX: 14 Tage. 0 = unbekannt → nichts. */
@Composable private fun SensorBadge() {
    val start = AppState.sensorStartMs
    if (start <= 0L) return
    val elapsed = currentTimeMillis() - start
    if (elapsed < 0L) return
    val dayMs = 86_400_000L
    val isAidex = AppState.sensorType == "aidex"
    val totalDays = if (isAidex) 14 else 10             // AiDEX 14 Tage, G7/ONE+ 10 Tage
    val totalMs = totalDays * dayMs
    val graceMs = if (isAidex) 0L else 12L * 3_600_000L // AiDEX: keine Ersatzzeit
    val (txt, col) = when {
        elapsed >= totalMs + graceMs -> tr("sensor_expired") to RED
        elapsed >= totalMs           -> tr("sensor_grace") to RED
        else -> {
            val day = (elapsed / dayMs).toInt() + 1
            tr("sensor_day", day, totalDays) to (if (elapsed >= totalMs - dayMs) AMBER else DIM)
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(txt, color = col, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun ValuesTab() {
    val windowMs = AppState.windowHours * 3600L * 1000L
    val now = currentTimeMillis()
    val inWin = AppState.history.filter { it.t >= now - windowMs }
    val medsInWin = AppState.medEvents.filter { it.t >= now - windowMs }
    var showMed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CARD).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(AppState.lastGlucose?.toString() ?: "---",
                color = if (AppState.lastGlucose != null) TEXT else FAINT,
                fontSize = 64.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Thin)
            Text("mg/dL", color = DIM, fontSize = 12.sp)
            AppState.rate?.let { Spacer(Modifier.height(4.dp)); Text(fmtRate(it), color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            if (AppState.status != "Bereit") { Spacer(Modifier.height(2.dp)); Text(AppState.status, color = Color(0x99FFFFFF), fontSize = 11.sp) }
            SensorBadge()
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CARD).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                label(tr("values_history"))
                if (inWin.isNotEmpty()) Text(tr("values_count", inWin.size, AppState.windowHours),
                    color = DIM, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            GlucoseChart(inWin, medsInWin, windowMs, AppState.tirLow, AppState.tirHigh)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(6, 24, 72, 168, 336, 504).forEach { h -> windowChip(h, AppState.windowHours == h) { AppState.windowHours = h } }
            }
        }
        card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                label(tr("med_section"))
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(GREEN.copy(alpha = 0.25f))
                    .clickable { showMed = true }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(tr("med_add"), color = GREEN, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (AppState.medEvents.isEmpty()) Text(tr("med_none"), color = DIM, fontSize = 12.sp)
            else AppState.medEvents.sortedByDescending { it.t }.take(2).forEach { e -> medRow(e) }
        }
        if (AppState.lastGlucoseAt > 0) Text(tr("values_last", relTime(AppState.lastGlucoseAt)),
            color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    if (showMed) MedDialog { showMed = false }
}

// ---------------- Medikamente ----------------
@Composable private fun medRow(e: MedEvent) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(eventColor(e.name)))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(if (e.dose.isBlank()) e.name else "${e.name} · ${e.dose}", color = TEXT, fontSize = 13.sp)
                Text(formatLocalTime(e.t), color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Text("✕", color = FAINT, fontSize = 16.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { AppState.removeMed(e) }.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable private fun medChip(text: String, sel: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (sel) GREEN.copy(alpha = 0.30f) else Color(0x18FFFFFF))
        .clickable { onClick() }.padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(text, color = if (sel) GREEN else DIM, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = GREEN, unfocusedBorderColor = FAINT,
    focusedTextColor = TEXT, unfocusedTextColor = TEXT, cursorColor = GREEN)

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MedDialog(onDismiss: () -> Unit) {
    var freeName by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(AppState.medKinds.firstOrNull()?.name ?: "") }
    var dose by remember { mutableStateOf("") }
    var offsetMin by remember { mutableStateOf(0) }
    var customMin by remember { mutableStateOf("") }
    val selKind = AppState.medKinds.firstOrNull { it.name == name }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BG,
        title = { Text(tr("med_add"), color = TEXT, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                label(tr("med_which"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppState.medKinds.forEach { k -> medChip(k.name, !freeName && name == k.name) { freeName = false; name = k.name; dose = "" } }
                    medChip(tr("med_custom"), freeName) { freeName = true; name = ""; dose = "" }
                }
                if (freeName) OutlinedTextField(name, { name = it }, singleLine = true,
                    label = { Text(tr("med_name"), color = DIM) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                label(tr("med_dose"))
                if (selKind != null && selKind.doses.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    selKind.doses.forEach { d -> medChip(d, dose == d) { dose = d } }
                }
                OutlinedTextField(dose, { dose = it }, singleLine = true,
                    label = { Text(tr("med_dose"), color = DIM) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                label(tr("med_when"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    medChip(tr("med_now"), offsetMin == 0 && customMin.isBlank()) { offsetMin = 0; customMin = "" }
                    listOf(15, 30, 60, 120).forEach { mo -> medChip(tr("med_min_ago", mo), offsetMin == mo && customMin.isBlank()) { offsetMin = mo; customMin = "" } }
                }
                OutlinedTextField(customMin, { v -> customMin = v.filter { it.isDigit() } }, singleLine = true,
                    label = { Text(tr("med_custom_min"), color = DIM) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val off = customMin.toIntOrNull() ?: offsetMin
                AppState.addMed(name, dose, currentTimeMillis() - off * 60_000L)
                onDismiss()
            }, enabled = name.isNotBlank()) { Text(tr("med_save"), color = GREEN, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("common_cancel"), color = DIM) } }
    )
}

@Composable private fun RowScope.windowChip(h: Int, sel: Boolean, onClick: () -> Unit) {
    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) GREEN.copy(alpha = 0.25f) else Color(0x10FFFFFF))
        .clickable { onClick() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(winLabel(h), color = if (sel) GREEN else DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun GlucoseChart(points: List<GlucosePoint>, meds: List<MedEvent>, windowMs: Long, tirLow: Int, tirHigh: Int) {
    val now = currentTimeMillis(); val tMin = now - windowMs
    val yMin = 10f; val yMax = 300f
    val nLow = tirLow.toFloat(); val nHigh = tirHigh.toFloat()
    val okLow = (tirLow - 20).coerceAtLeast(20).toFloat(); val okHigh = (tirHigh + 20).coerceAtMost(300).toFloat()
    val tm = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val w = size.width; val h = size.height; val padL = 28f; val padR = 6f; val padT = 6f; val padB = 14f
        val plotW = w - padL - padR; val plotH = h - padT - padB
        fun xOf(t: Long): Float = padL + ((t - tMin).coerceIn(0, windowMs)).toFloat() / windowMs.toFloat() * plotW
        fun yOf(v: Float): Float = padT + (1f - ((v - yMin) / (yMax - yMin)).coerceIn(0f, 1f)) * plotH
        drawRect(AMBER.copy(alpha = 0.10f), Offset(padL, yOf(nLow)), Size(plotW, yOf(okLow) - yOf(nLow)))
        drawRect(AMBER.copy(alpha = 0.10f), Offset(padL, yOf(okHigh)), Size(plotW, yOf(nHigh) - yOf(okHigh)))
        drawRect(GREEN.copy(alpha = 0.18f), Offset(padL, yOf(nHigh)), Size(plotW, yOf(nLow) - yOf(nHigh)))
        listOf(50, 100, 150, 200, 250, 300).forEach { v ->
            val y = yOf(v.toFloat())
            drawLine(FAINT, Offset(padL, y), Offset(w - padR, y), 1f)
            val m = tm.measure("$v", TextStyle(color = DIM, fontSize = 9.sp))
            drawText(m, topLeft = Offset(2f, y - m.size.height / 2f))
        }
        meds.forEach { mev ->
            val x = xOf(mev.t); val c = eventColor(mev.name)
            drawLine(c.copy(alpha = 0.55f), Offset(x, padT), Offset(x, h - padB), 1.5f)
            drawCircle(c, 3.5f, Offset(x, padT + 2f))
        }
        if (points.size < 2) return@Canvas
        val path = androidx.compose.ui.graphics.Path(); var started = false
        points.forEach { p -> val x = xOf(p.t); val y = yOf(p.v.toFloat()); if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y) }
        drawPath(path, GREEN, style = Stroke(width = 3f))
        points.forEach { p ->
            val c = when { p.v < okLow.toInt() || p.v > okHigh.toInt() -> RED; p.v < nLow.toInt() || p.v > nHigh.toInt() -> AMBER; else -> GREEN }
            drawCircle(c, 2.5f, Offset(xOf(p.t), yOf(p.v.toFloat())))
        }
    }
}

// ---------------- Statistik ----------------
@Composable
private fun StatsTab() {
    val windowMs = AppState.statsRangeHours * 3600L * 1000L
    val v = AppState.history.filter { it.t >= currentTimeMillis() - windowMs }.map { it.v }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        card {
            label(tr("stats_range"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(6, 24, 72, 168, 336, 504).forEach { h -> windowChip(h, AppState.statsRangeHours == h) { AppState.statsRangeHours = h } }
            }
        }
        if (v.isEmpty()) {
            card { Text(tr("stats_empty"), color = DIM, fontSize = 13.sp) }
        } else {
            val n = v.size; val mean = v.sum().toDouble() / n
            val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / n)
            val cv = if (mean > 0) sd / mean * 100 else 0.0
            val below = v.count { it < AppState.tirLow }; val above = v.count { it > AppState.tirHigh }; val inR = n - below - above
            val gmi = 3.31 + 0.02392 * mean
            card {
                label("TIME IN RANGE")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TirRing(below.toFloat() / n, inR.toFloat() / n, above.toFloat() / n, Modifier.size(120.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        legend(RED, "< ${AppState.tirLow}", below * 100 / n)
                        legend(GREEN, "${AppState.tirLow}–${AppState.tirHigh}", inR * 100 / n)
                        legend(AMBER, "> ${AppState.tirHigh}", above * 100 / n)
                    }
                }
            }
            card {
                label(tr("stats_metrics"))
                statRow(tr("stats_mean"), "${mean.toInt()} mg/dL")
                statRow(tr("stats_sd"), "${sd.toInt()} mg/dL")
                statRow(tr("stats_cv"), "${(cv * 10).toInt() / 10.0} %")
                statRow("Min / Max", "${v.min()} / ${v.max()} mg/dL")
                statRow(tr("stats_count"), "$n")
                statRow(tr("stats_gmi"), "${(gmi * 100).toInt() / 100.0} %")
            }
            Text(tr("stats_gmi_note"), color = FAINT, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        // Ereignisse — ganz unten
        card {
            label(tr("med_section"))
            if (AppState.medEvents.isEmpty()) Text(tr("med_none"), color = DIM, fontSize = 12.sp)
            else AppState.medEvents.sortedByDescending { it.t }.forEach { e -> medRow(e) }
        }
    }
}

@Composable private fun legend(c: Color, lbl: String, pct: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c))
        Text(lbl, color = TEXT, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Text("$pct %", color = DIM, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
@Composable private fun statRow(lbl: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(lbl, color = DIM, fontSize = 13.sp); Text(value, color = TEXT, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
@Composable private fun TirRing(low: Float, inR: Float, high: Float, modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 18f; val tl = Offset(stroke / 2f, stroke / 2f); val bs = Size(size.width - stroke, size.height - stroke)
        drawArc(Color(0x22FFFFFF), 0f, 360f, false, tl, bs, style = Stroke(stroke))
        var start = -90f
        listOf(low to RED, inR to GREEN, high to AMBER).forEach { (f, c) ->
            if (f > 0f) { drawArc(c, start, f * 360f, false, tl, bs, style = Stroke(stroke)); start += f * 360f }
        }
    }
}

// ---------------- Einstellungen ----------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab() {
    var calibInput by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Sprache
        card {
            label(tr("set_language"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                langChip(tr("lang_system"), AppState.language == "") { AppState.language = "" }
                langChip("Deutsch", AppState.language == "de") { AppState.language = "de" }
                langChip("English", AppState.language == "en") { AppState.language = "en" }
                langChip("Español", AppState.language == "es") { AppState.language = "es" }
            }
            Text(tr("set_language_hint"), color = DIM, fontSize = 11.sp)
        }
        // Darstellung (Hell/Dunkel/System)
        card {
            label(tr("theme_title"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                langChip(tr("theme_system"), AppState.themeMode == "") { AppState.themeMode = "" }
                langChip(tr("theme_light"), AppState.themeMode == "light") { AppState.themeMode = "light" }
                langChip(tr("theme_dark"), AppState.themeMode == "dark") { AppState.themeMode = "dark" }
            }
        }
        // Sensor koppeln
        card {
            label(tr("set_pair"))
            // Sensor-Umschalter (Dexcom / AiDEX) — nur wechselbar, solange nichts läuft.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                langChip("Dexcom", AppState.sensorType != "aidex") {
                    if (!AppState.active) { AppState.sensorType = "dexcom"; AppState.onSensorTypeChange("dexcom") }
                }
                langChip("AiDEX", AppState.sensorType == "aidex") {
                    if (!AppState.active) { AppState.sensorType = "aidex"; AppState.onSensorTypeChange("aidex") }
                }
            }
            if (AppState.sensorType == "aidex") {
                // AiDEX: Seriennummer (SN) mit Kamera-Scan als Symbol IM Feld — keine PIN.
                OutlinedTextField(
                    value = AppState.aidexSerial,
                    onValueChange = { AppState.aidexSerial = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) },
                    label = { Text(tr("set_aidex_sn_label"), color = DIM) }, singleLine = true, enabled = !AppState.active,
                    trailingIcon = {
                        IconButton(onClick = { AppState.onScanSerial() }, enabled = !AppState.active) {
                            Icon(Icons.Filled.PhotoCamera, contentDescription = "SN scannen", tint = GREEN)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GREEN, unfocusedBorderColor = FAINT,
                        focusedTextColor = TEXT, unfocusedTextColor = TEXT, cursorColor = GREEN)
                )
            } else {
                OutlinedTextField(
                    value = AppState.pin, onValueChange = { AppState.pin = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text(tr("set_pin_label"), color = DIM) }, singleLine = true, enabled = !AppState.active,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GREEN, unfocusedBorderColor = FAINT,
                        focusedTextColor = TEXT, unfocusedTextColor = TEXT, cursorColor = GREEN)
                )
            }
            AppState.deviceName?.let { Text(it, color = TEXT, fontSize = 15.sp) }
            if (AppState.handshakePhase >= 0) Text(tr("set_handshake", AppState.handshakePhase), color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(AppState.status, color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                greenButton(tr(if (AppState.active) "common_stop" else "common_start"), color = if (AppState.active) RED else GREEN) {
                    if (AppState.active) AppState.onDisconnect() else AppState.onConnect()
                }
                greenButton(tr("set_new_sensor")) { AppState.onHardReset() }
            }
        }
        // Kalibrierung
        card {
            label(tr("set_calib"))
            Text(tr("set_calib_hint"), color = DIM, fontSize = 11.sp)
            Text(tr("set_calib_status", AppState.lastGlucose?.toString() ?: "—",
                (if (AppState.calibOffset >= 0) "+" else "") + AppState.calibOffset), color = TEXT, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            OutlinedTextField(value = calibInput, onValueChange = { calibInput = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text(tr("set_calib_label"), color = DIM) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GREEN, unfocusedBorderColor = FAINT,
                    focusedTextColor = TEXT, unfocusedTextColor = TEXT, cursorColor = GREEN))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                greenButton(tr("set_calib_do"), calibInput.toIntOrNull() != null && AppState.lastGlucose != null) {
                    calibInput.toIntOrNull()?.let { AppState.onCalibrate(it); calibInput = "" }
                }
                greenButton(tr("set_calib_reset"), AppState.calibOffset != 0) { AppState.onCalibrateReset() }
            }
        }
        // TIR
        card {
            label(tr("set_tir"))
            Text(tr("set_tir_low", AppState.tirLow), color = TEXT, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            ThemedSlider(value = AppState.tirLow.toFloat(), onValueChange = { AppState.tirLow = it.toInt().coerceIn(40, AppState.tirHigh - 10) }, valueRange = 40f..200f, steps = 31)
            Text(tr("set_tir_high", AppState.tirHigh), color = TEXT, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            ThemedSlider(value = AppState.tirHigh.toFloat(), onValueChange = { AppState.tirHigh = it.toInt().coerceIn(AppState.tirLow + 10, 300) }, valueRange = 100f..300f, steps = 39)
        }
        // Hypo
        card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                label(tr("set_hypo"))
                Switch(checked = AppState.hypoEnabled, onCheckedChange = { AppState.hypoEnabled = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GREEN))
            }
            Text(tr("set_threshold", AppState.hypoThreshold), color = TEXT, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            ThemedSlider(value = AppState.hypoThreshold.toFloat(), onValueChange = { AppState.hypoThreshold = it.toInt() }, valueRange = 40f..100f, enabled = AppState.hypoEnabled, steps = 11)
        }
        // Hyper
        card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                label(tr("set_hyper"))
                Switch(checked = AppState.hyperEnabled, onCheckedChange = { AppState.hyperEnabled = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GREEN))
            }
            Text(tr("set_threshold", AppState.hyperThreshold), color = TEXT, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            ThemedSlider(value = AppState.hyperThreshold.toFloat(), onValueChange = { AppState.hyperThreshold = it.toInt() }, valueRange = 150f..400f, enabled = AppState.hyperEnabled, steps = 49)
        }
        // Alarm-Verhalten
        card {
            label(tr("set_alarm_behavior"))
            Text(tr("set_repeat", AppState.alarmRepeatMin), color = TEXT, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            ThemedSlider(value = AppState.alarmRepeatMin.toFloat(), onValueChange = { AppState.alarmRepeatMin = it.toInt() }, valueRange = 1f..60f)
            switchRow(tr("set_sound"), AppState.alarmSound) { AppState.alarmSound = it }
            switchRow(tr("set_vibration"), AppState.alarmVibrate) { AppState.alarmVibrate = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { greenButton(tr("set_alarm_test")) { AppState.onAlarmTest() } }
            Text(tr("set_alarm_test_hint"), color = DIM, fontSize = 11.sp)
        }
        // Cloud-Upload
        card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                label(tr("set_cloud"))
                Switch(checked = AppState.uploadEnabled, onCheckedChange = { AppState.uploadEnabled = it; if (it) CloudUploader.uploadNow() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GREEN))
            }
            Text(tr("set_cloud_text"), color = DIM, fontSize = 11.sp)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrView(AppState.dashboardUrl(), Modifier.size(180.dp).clip(RoundedCornerShape(8.dp)))
            }
            Text(AppState.dashboardUrl(), color = DIM, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                greenButton(tr("set_dash_open")) { AppState.onOpenUrl(AppState.dashboardUrl()) }
                greenButton(tr("set_share_link")) { AppState.onShareUrl(AppState.dashboardUrl()) }
            }
        }
        // Medikamente verwalten
        card {
            label(tr("med_manage"))
            Text(tr("med_manage_hint"), color = DIM, fontSize = 11.sp)
            AppState.medKinds.forEach { k ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.name, color = TEXT, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(k.doses.joinToString(" · "), color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text("✕", color = FAINT, fontSize = 16.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { AppState.replaceMedKinds(AppState.medKinds.filter { it !== k }) }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            var newName by remember { mutableStateOf("") }
            var newDoses by remember { mutableStateOf("") }
            OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text(tr("med_name"), color = DIM) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            OutlinedTextField(newDoses, { newDoses = it }, singleLine = true, label = { Text(tr("med_doses"), color = DIM) }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                greenButton(tr("med_add_kind"), newName.isNotBlank()) {
                    val ds = newDoses.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    AppState.replaceMedKinds(AppState.medKinds + MedKind(newName.trim(), ds))
                    newName = ""; newDoses = ""
                }
            }
        }
        // Daten sichern
        card {
            label(tr("set_backup"))
            Text(tr("set_backup_text"), color = DIM, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                greenButton(tr("set_export_btn")) { AppState.onExport() }
                greenButton(tr("set_import_btn")) { AppState.onImport() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { greenButton(tr("set_clear_history"), color = RED) { AppState.onClearHistory() } }
        }
        // Debug / Protokoll
        card {
            label("DEBUG / PROTOKOLL")
            Text("Status: ${AppState.status}", color = TEXT, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("Wert: ${AppState.lastGlucose ?: "—"} mg/dL · Sensor: ${AppState.deviceName ?: "—"} · History: ${AppState.history.size}",
                color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0F0A)).padding(8.dp)) {
                if (AppState.log.isEmpty()) Text("Noch keine Log-Einträge", color = DIM, fontSize = 12.sp)
                else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    AppState.log.takeLast(120).forEach { Text(it, color = Color(0xCCE5E7EB), fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { greenButton("Löschen") { AppState.log.clear() } }
        }
        if (AppState.backfillCount > 0) Text("History: ${AppState.backfillCount}", color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable private fun RowScope.langChip(lbl: String, sel: Boolean, onClick: () -> Unit) {
    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) GREEN.copy(alpha = 0.25f) else Color(0x10FFFFFF))
        .clickable { onClick() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(lbl, color = if (sel) GREEN else DIM, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}
@Composable private fun switchRow(lbl: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(lbl, color = TEXT, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GREEN))
    }
}

// ---------------- Info ----------------
@Composable
private fun InfoTab() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(tr("info_hello"), color = TEXT, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(tr("info_dates"), color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Image(painterResource(Res.drawable.pebbels), "Pebbels", Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.FillWidth)
        card {
            Text(tr("info_story1"), color = TEXT, fontSize = 14.sp, lineHeight = 20.sp)
            Text(tr("info_story2"), color = TEXT, fontSize = 14.sp, lineHeight = 20.sp)
        }
        card {
            label(tr("info_more"))
            Text(tr("info_more_text"), color = TEXT, fontSize = 13.sp)
            Button(onClick = { AppState.onOpenUrl("https://sa1.de/pebbels-website") },
                modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GREEN)) {
                Text(tr("info_open_website"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Text("sa1.de/pebbels-website", color = DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        card {
            label(tr("info_disclaimer_title"))
            Text(tr("info_no_med"), color = TEXT, fontSize = 13.sp, lineHeight = 19.sp)
            Text(tr("info_optimized"), color = DIM, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text("Pebbels APP · v0.2 · GPL-3.0\n" + tr("info_free"), color = FAINT, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
    }
}
