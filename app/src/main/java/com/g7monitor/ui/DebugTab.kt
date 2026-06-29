/*
 * Debug-Tab — Ereignis-Log + Sensor-Status auf einen Blick.
 *
 * Zweck:
 *   - User sieht die wichtigen Ereignisse: neuer Wert, Verbindung OK,
 *     Daten gesendet — sowie jeden Fehler/Warnung.
 *   - Das ausführliche BLE-/Crypto-Protokoll landet bewusst NICHT hier,
 *     sondern nur in logcat (DebugLog.i). Der Tab bleibt dadurch lesbar.
 *
 * Layout:
 *   - oben: Status-Karte mit Connection-State und aktuellem Wert.
 *   - mitte: Log-Liste (nur Events + Warnungen/Fehler), Monospace,
 *           farbcodiert nach Level. Auto-scrollt zum neuesten Eintrag
 *           (scrollt der User hoch, bleibt die Position — siehe shouldFollow).
 *   - unten: Knöpfe "Löschen" + "Teilen" + Hard-Reset.
 */
package com.g7monitor.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.g7monitor.util.DebugLog
import com.g7monitor.vm.ConnectionState
import com.g7monitor.vm.G7State
import com.g7monitor.vm.G7ViewModel

@Composable
fun DebugTab(vm: G7ViewModel, state: G7State) {
    val entries by DebugLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // Auto-Follow nur wenn der User unten ist. Sobald er nach oben scrollt,
    // bleibt die Position — damit man Logs zurückscrollen kann ohne dass
    // der nächste Eintrag den weghüpft.
    val shouldFollow by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= entries.size - 2  // Wir gelten als "am Ende"
        }
    }

    // Bei neuen Einträgen scrollen — aber nur wenn der User folgen will.
    LaunchedEffect(entries.size, shouldFollow) {
        if (entries.isNotEmpty() && shouldFollow) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Column(
        Modifier.fillMaxWidth()
    ) {
        Text(
            "DEBUG / PROTOKOLL", color = Color(0x55FFFFFF),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
        // --- Status-Karte oben --------------------------------------------
        StatusCard(state)

        Spacer(Modifier.height(8.dp))

        // --- Log-Liste -----------------------------------------------------
        Box(
            Modifier
                .height(300.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0A0F0A))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
        ) {
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Noch keine Log-Einträge",
                        color = Color(0x77FFFFFF),
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    items(entries, key = { "${it.timestamp}-${it.message.hashCode()}" }) { e ->
                        LogRow(e)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- Knöpfe unten --------------------------------------------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { DebugLog.clear() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFE5E7EB),
                ),
            ) { Text("Löschen") }

            OutlinedButton(
                onClick = {
                    val text = entries.joinToString("\n") {
                        "${it.timeString()} ${it.level.name.first()} ${it.tag}: ${it.message}"
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "G7Monitor Debug Log")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, "Log teilen")
                    )
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFE5E7EB),
                ),
            ) { Text("Teilen") }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            "${entries.size} Einträge",
            color = Color(0x66FFFFFF),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.End)
                .padding(top = 4.dp)
        )
    }

}

@Composable
private fun StatusCard(state: G7State) {
    val mgdl = state.lastGlucose
    val tsMs = state.lastGlucoseAt
    val ageMin = tsMs?.let { ((System.currentTimeMillis() - it) / 60_000L).toInt() }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0D120D))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Zeile 1: Verbindungsstatus mit Farb-Indikator
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (col, label) = when (state.connection) {
                    ConnectionState.Idle -> Color(0x55FFFFFF) to "Idle"
                    ConnectionState.Scanning -> Color(0xFFFBBF24) to "Scannen"
                    ConnectionState.Found -> Color(0xFFFBBF24) to "Gefunden"
                    ConnectionState.Connecting -> Color(0xFFFBBF24) to "Verbinde"
                    ConnectionState.Bonded -> Color(0xFFFBBF24) to "Bonded"
                    ConnectionState.Authenticating -> Color(0xFFFBBF24) to "Auth läuft"
                    ConnectionState.Authenticated -> Color(0xFF22C55E) to "Auth ok"
                    ConnectionState.Receiving -> Color(0xFF22C55E) to "Empfange"
                    ConnectionState.Error -> Color(0xFFEF4444) to "Fehler"
                }
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(col)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // Zeile 2: aktueller Wert + Alter
            Text(
                buildString {
                    append("Wert: ")
                    if (mgdl != null && mgdl > 0) {
                        append("$mgdl mg/dL")
                        if (ageMin != null) append("  (vor $ageMin Min)")
                        if (state.rateMgdlPerMin != null) {
                            val sign = if (state.rateMgdlPerMin!! >= 0f) "+" else ""
                            append(
                                "  $sign${
                                    String.format(java.util.Locale.GERMAN,
                                        "%.1f",
                                        state.rateMgdlPerMin)
                                } mg/dL/min"
                            )
                        }
                    } else {
                        append("—")
                    }
                },
                color = Color(0xFFE5E7EB),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            // Zeile 3: Sensor + History
            Text(
                "Sensor: ${state.deviceName ?: "—"}   History: ${state.history.size} Punkte",
                color = Color(0xCCFFFFFF),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            // Zeile 4: Status-Meldung des Repositories
            if (state.statusMessage.isNotEmpty()) {
                Text(
                    state.statusMessage,
                    color = Color(0x99FFFFFF),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun LogRow(e: DebugLog.LogEntry) {
    val color = when (e.level) {
        DebugLog.Level.INFO -> Color(0xFFE5E7EB)
        DebugLog.Level.WARN -> Color(0xFFFBBF24)
        DebugLog.Level.ERROR -> Color(0xFFEF4444)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Text(
            e.timeString(),
            color = Color(0x77FFFFFF),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "${e.tag}: ",
            color = Color(0xAACCCCCC),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
        Text(
            e.message,
            color = color,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
