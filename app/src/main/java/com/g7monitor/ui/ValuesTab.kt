/*
 * Tab "Werte" — große Glukose-Karte + Verlaufs-Chart.
 *
 * Das ist das, was der User bei geöffneter App die meiste Zeit sieht,
 * deshalb keine Einstellungs-Controls hier drin, nur Status und Chart.
 */
package com.g7monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.g7monitor.R
import com.g7monitor.vm.G7State
import com.g7monitor.vm.G7ViewModel

@Composable
fun ValuesTab(vm: G7ViewModel, state: G7State) {
    val card  = Color(0x15FFFFFF)
    val dim   = Color(0x88FFFFFF)
    val faint = Color(0x55FFFFFF)
    val green = Color(0xFF22C55E)

    // Settings live mitlesen — sobald der User in Einstellungen Untere/Obere
    // Grenze ändert, soll die grüne Zone im Chart sofort folgen.
    val settings by vm.settings.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Glucose Card — kompakt gehalten, damit das Chart ohne Scrollen
        // sichtbar bleibt. Schriftgröße und vertikale Padding sind deutlich
        // reduziert gegenüber dem ursprünglichen 96sp/40dp-Layout.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(card).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                state.lastGlucose?.toString() ?: "---",
                color = if (state.lastGlucose != null) Color.White else Color(0x40FFFFFF),
                fontSize = 64.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Thin,
                // Compose-Default: Text-Höhe wird mit einer extra Zeile berechnet.
                // Setzt das lineHeight runter, damit der große Wert nicht mehr Raum
                // beansprucht als nötig.
            )
            Text("mg/dL", color = dim, fontSize = 12.sp)
            val rate = state.rateMgdlPerMin
            if (rate != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "%+.1f mg/dL/min".format(rate),
                    color = dim,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (state.statusMessage.isNotEmpty() &&
                state.statusMessage != "Bereit") {
                Spacer(Modifier.height(2.dp))
                Text(state.statusMessage, color = Color(0x99FFFFFF), fontSize = 11.sp)
            }
        }

        // History / Chart
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val valuesInWindow = rememberValuesInWindow(state.history, state.windowHours)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.values_history), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (state.history.isNotEmpty()) {
                    Text(
                        stringResource(R.string.values_count, valuesInWindow.size, state.windowHours),
                        color = dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GlucoseChart(
                    points = state.history,
                    windowHours = state.windowHours,
                    lineColor = green,
                    tirLow = settings.tirLow,
                    tirHigh = settings.tirHigh,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // 6h, 24h, 3d, 7d, 14d, 21d — 21d deckt eine komplette
                // Extended-Use-Session bis Sensor-Ende ab.
                listOf(6, 24, 72, 168, 336, 504).forEach { h ->
                    val selected = state.windowHours == h
                    val label = if (h < 24) "${h}h" else "${h / 24}d"
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) green.copy(alpha = 0.25f) else Color(0x10FFFFFF))
                            .clickable { vm.setWindow(h) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (selected) green else dim,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        state.lastGlucoseAt?.let {
            val t = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(it))
            Text(
                stringResource(R.string.values_last, t),
                color = dim,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
