/*
 * Glukose-Chart und Hilfsfunktionen.
 *
 * Bewusst in eine eigene Datei gezogen, damit G7Screen und StatsTab ihn
 * jeweils einbinden können, ohne dass die Parent-Screens riesig werden.
 *
 * Der GRÜNE Normal-Bereich (TIR) wird von den Settings (tirLow/tirHigh)
 * gesteuert — wenn der User in den Einstellungen die Grenzen ändert,
 * folgt das Chart sofort. Die OK-Toleranzen (jeweils 20 mg/dL ober- und
 * unterhalb des Grünbereichs als gelbe Warn-Zone) bleiben relativ zur
 * grünen Zone und passen sich damit automatisch mit an.
 */
package com.g7monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.g7monitor.ble.ReadingsStore

/** Filtert auf das Zeitfenster und cached so lange, wie history und Stunden gleich bleiben. */
@Composable
fun rememberValuesInWindow(
    history: List<ReadingsStore.Point>,
    windowHours: Int,
): List<ReadingsStore.Point> = remember(history, windowHours) {
    val cutoff = System.currentTimeMillis() - windowHours * 3600L * 1000L
    history.filter { it.timeMs >= cutoff }
}

@Composable
fun GlucoseChart(
    points: List<ReadingsStore.Point>,
    windowHours: Int,
    lineColor: Color,
    tirLow: Int = 70,
    tirHigh: Int = 120,
) {
    val values = rememberValuesInWindow(points, windowHours)
    val now = System.currentTimeMillis()
    val windowMs = windowHours * 3600L * 1000L
    val tMin = now - windowMs

    val yMin = 10f
    val yMax = 300f

    // Grüne Zone = TIR-Grenzen aus den Settings, gelbe Toleranz jeweils
    // 20 mg/dL drumherum. Dadurch reagiert das Chart sofort wenn der User
    // in den Einstellungen die Grenzen ändert.
    val normalLow  = tirLow.toFloat()
    val normalHigh = tirHigh.toFloat()
    val okLow      = (tirLow - 20).coerceAtLeast(20).toFloat()
    val okHigh     = (tirHigh + 20).coerceAtMost(300).toFloat()

    val axisColor     = Color(0x22FFFFFF)
    val green         = Color(0xFF22C55E)
    val yellow        = Color(0xFFFBBF24)
    val red           = Color(0xFFF87171)
    val normalBand    = green.copy(alpha = 0.18f)
    val okBand        = yellow.copy(alpha = 0.10f)
    val pointColor    = Color.White

    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val w = size.width
        val h = size.height
        val padL = 32f
        val padR = 6f
        val padT = 6f
        val padB = 14f
        val plotW = w - padL - padR
        val plotH = h - padT - padB

        fun xOf(t: Long): Float {
            val rel = (t - tMin).coerceIn(0, windowMs).toFloat() / windowMs
            return padL + rel * plotW
        }
        fun yOf(v: Float): Float {
            val rel = ((v - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
            return padT + (1f - rel) * plotH
        }

        drawRect(
            color = okBand,
            topLeft = Offset(padL, yOf(normalLow)),
            size = androidx.compose.ui.geometry.Size(plotW, yOf(okLow) - yOf(normalLow))
        )
        drawRect(
            color = okBand,
            topLeft = Offset(padL, yOf(okHigh)),
            size = androidx.compose.ui.geometry.Size(plotW, yOf(normalHigh) - yOf(okHigh))
        )
        drawRect(
            color = normalBand,
            topLeft = Offset(padL, yOf(normalHigh)),
            size = androidx.compose.ui.geometry.Size(plotW, yOf(normalLow) - yOf(normalHigh))
        )

        listOf(20f, 50f, 70f, 120f, 200f, 300f).forEach { v ->
            val y = yOf(v)
            drawLine(axisColor, Offset(padL, y), Offset(w - padR, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(
                v.toInt().toString(),
                2f,
                y + 4f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(153, 255, 255, 255)
                    textSize = 22f
                    isAntiAlias = true
                }
            )
        }

        drawLine(axisColor, Offset(padL, h - padB), Offset(w - padR, h - padB), strokeWidth = 1f)

        if (values.size < 2) {
            values.firstOrNull()?.let { p ->
                drawCircle(pointColor, 4f, Offset(xOf(p.timeMs), yOf(p.mgdl.toFloat())))
            }
            return@Canvas
        }

        val path = Path()
        var started = false
        values.forEach { p ->
            val x = xOf(p.timeMs)
            val y = yOf(p.mgdl.toFloat())
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 3f))

        values.forEach { p ->
            val color = when {
                p.mgdl < okLow.toInt()   -> red
                p.mgdl > okHigh.toInt()  -> red
                p.mgdl < normalLow.toInt() || p.mgdl > normalHigh.toInt() -> yellow
                else -> green
            }
            drawCircle(color, 2.5f, Offset(xOf(p.timeMs), yOf(p.mgdl.toFloat())))
        }

        val last = values.last()
        drawCircle(pointColor, 5f, Offset(xOf(last.timeMs), yOf(last.mgdl.toFloat())))
    }
}
