/*
 * QR-Code-Composable.
 *
 * Erzeugt aus einem String (hier: die Dashboard-URL) einen QR-Code und
 * zeichnet ihn in ein Compose-Canvas.
 *
 * Aufteilung der Arbeit:
 *  - ZXing (com.google.zxing:core) erledigt das Heikle: Reed-Solomon-
 *    Fehlerkorrektur, Masken-Wahl, Modul-Platzierung. Ergebnis ist eine
 *    BitMatrix (1 Bit pro Modul).
 *  - Das Zeichnen machen wir selbst im Canvas — ein schwarzes Rechteck pro
 *    gesetztem Modul auf weißem Grund. So bleibt der QR scharf bei jeder
 *    Anzeigegröße und wir brauchen kein Bitmap.
 *
 * Wichtig: QR-Codes brauchen einen HELLEN Hintergrund und eine Ruhezone
 * (quiet zone) zum Scannen — beides ist hier berücksichtigt.
 */
package com.g7monitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.min

@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 220.dp,
) {
    // BitMatrix einmal berechnen und merken, solange der Inhalt gleich bleibt.
    // Breite/Höhe = 1 übergeben → ZXing liefert die natürliche Modul-Matrix
    // (Module + Ruhezone), die wir dann im Canvas frei skalieren.
    val matrix = remember(content) {
        try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,            // Ruhezone in Modulen
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, hints)
        } catch (t: Throwable) {
            null
        }
    }

    Canvas(modifier.size(sizeDp)) {
        val m = matrix
        // Weißer Grund über die ganze Fläche — auch als Fallback, falls die
        // Matrix mal nicht erzeugt werden konnte.
        drawRect(Color.White, size = size)
        if (m == null) return@Canvas
        val cols = m.width
        val rows = m.height
        // Quadratische Zellgröße, an die kleinere Kante angepasst.
        val cell = min(size.width / cols, size.height / rows)
        // QR mittig in der Canvas-Fläche platzieren.
        val offX = (size.width - cell * cols) / 2f
        val offY = (size.height - cell * rows) / 2f
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (m.get(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(offX + x * cell, offY + y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}
