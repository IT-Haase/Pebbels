/*
 * Tab "Statistik".
 *
 * Kennzahlen über das vom User konfigurierte Zeitfenster (SettingsStore.statsRangeHours):
 *   - Mittelwert, Standardabweichung, Min/Max, Anzahl Werte
 *   - TIR-Ring: Anteil Werte < low / in Range / > high (Grenzen aus Settings)
 *   - GMI (Glucose Management Indicator) — humanspezifische Formel,
 *     für Hunde nur grober Näherungswert, deshalb klein dargestellt.
 */
package com.g7monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.g7monitor.R
import com.g7monitor.ble.ReadingsStore
import com.g7monitor.vm.G7State
import com.g7monitor.vm.G7ViewModel
import com.g7monitor.vm.SettingsState
import kotlin.math.sqrt

@Composable
fun StatsTab(vm: G7ViewModel, state: G7State) {
    val settings by vm.settings.collectAsStateWithLifecycle()

    val bg    = Color(0x15FFFFFF)
    val dim   = Color(0x88FFFFFF)
    val faint = Color(0x55FFFFFF)
    val green = Color(0xFF22C55E)
    val yellow= Color(0xFFFBBF24)
    val red   = Color(0xFFF87171)

    val windowMs = settings.statsRangeHours * 3600L * 1000L
    val cutoff = System.currentTimeMillis() - windowMs
    val values = remember(state.history, settings.statsRangeHours) {
        state.history.filter { it.timeMs >= cutoff }
    }

    val stats = remember(values, settings.tirLow, settings.tirHigh) {
        computeStats(values, settings)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Range-Auswahl
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.stats_range), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf(6, 24, 72, 168, 336, 504).forEach { h ->
                    val selected = settings.statsRangeHours == h
                    val label = if (h < 24) "${h}h" else "${h / 24}d"
                    Box(
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) green.copy(alpha = 0.25f) else Color(0x10FFFFFF))
                            .clickable { vm.updateStatsRange(h) }
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

        if (stats == null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.stats_empty), color = dim, fontSize = 13.sp)
            }
            return@Column
        }

        // TIR-Ring
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("TIME IN RANGE", color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                TirRing(
                    lowFrac  = stats.fracLow,
                    inFrac   = stats.fracIn,
                    highFrac = stats.fracHigh,
                    modifier = Modifier.size(120.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LegendRow(red,    "< ${settings.tirLow}",   stats.fracLow)
                    LegendRow(green,  "${settings.tirLow}–${settings.tirHigh}", stats.fracIn)
                    LegendRow(yellow, "> ${settings.tirHigh}",  stats.fracHigh)
                }
            }
        }

        // Zahlen
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.stats_metrics), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            StatRow(stringResource(R.string.stats_mean), "${stats.mean.toInt()} mg/dL")
            StatRow(stringResource(R.string.stats_sd), "${stats.stdDev.toInt()} mg/dL")
            StatRow(stringResource(R.string.stats_cv), "%.1f %%".format(stats.cv))
            StatRow("Min / Max", "${stats.min} / ${stats.max} mg/dL")
            StatRow(stringResource(R.string.stats_count), "${stats.n}")
            StatRow(stringResource(R.string.stats_gmi), "%.2f %%".format(stats.gmi))
        }

        Text(
            stringResource(R.string.stats_gmi_note),
            color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun LegendRow(color: Color, label: String, frac: Float) {
    val dim = Color(0x88FFFFFF)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Text(label, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        Text("%.0f %%".format(frac * 100), color = dim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val dim = Color(0x88FFFFFF)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = dim, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun TirRing(
    lowFrac: Float,
    inFrac: Float,
    highFrac: Float,
    modifier: Modifier = Modifier,
) {
    val red   = Color(0xFFF87171)
    val green = Color(0xFF22C55E)
    val yellow= Color(0xFFFBBF24)
    val track = Color(0x22FFFFFF)

    Canvas(modifier) {
        val stroke = 18f
        val inset = stroke / 2f
        val topLeft = Offset(inset, inset)
        val boxSize = Size(size.width - stroke, size.height - stroke)

        // Track
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = boxSize,
            style = Stroke(width = stroke)
        )

        var start = -90f
        // Reihenfolge: low (rot), in (grün), high (gelb)
        val segs = listOf(
            lowFrac  to red,
            inFrac   to green,
            highFrac to yellow,
        )
        segs.forEach { (frac, color) ->
            if (frac <= 0f) return@forEach
            val sweep = frac * 360f
            drawArc(
                color = color,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = boxSize,
                style = Stroke(width = stroke)
            )
            start += sweep
        }
    }
}

private data class Stats(
    val n: Int,
    val mean: Double,
    val stdDev: Double,
    val cv: Double,
    val min: Int,
    val max: Int,
    val fracLow: Float,
    val fracIn: Float,
    val fracHigh: Float,
    val gmi: Double,
)

private fun computeStats(values: List<ReadingsStore.Point>, s: SettingsState): Stats? {
    if (values.isEmpty()) return null
    val mgdl = values.map { it.mgdl }
    val n = mgdl.size
    val mean = mgdl.sumOf { it.toDouble() } / n
    val variance = mgdl.sumOf { (it - mean) * (it - mean) } / n
    val stdDev = sqrt(variance)
    val cv = if (mean > 0) stdDev / mean * 100.0 else 0.0
    val min = mgdl.min()
    val max = mgdl.max()
    val below = mgdl.count { it < s.tirLow }
    val above = mgdl.count { it > s.tirHigh }
    val inRange = n - below - above
    // GMI-Formel nach Bergenstal et al. (human):
    //   GMI (%) = 3.31 + 0.02392 * mean(mg/dL)
    val gmi = 3.31 + 0.02392 * mean
    return Stats(
        n = n,
        mean = mean,
        stdDev = stdDev,
        cv = cv,
        min = min,
        max = max,
        fracLow  = below.toFloat()   / n,
        fracIn   = inRange.toFloat() / n,
        fracHigh = above.toFloat()   / n,
        gmi = gmi,
    )
}
