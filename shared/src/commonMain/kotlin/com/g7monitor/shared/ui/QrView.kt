package com.g7monitor.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import qrcode.QRCode

/** QR-Code aus einem String, gezeichnet mit Compose-Canvas — Android + iOS.
 *  Weißer Grund + Ruhezone sind durch den Encoder/Canvas abgedeckt. */
@Composable
fun QrView(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        runCatching {
            QRCode.ofSquares().build(content).rawData.map { row -> row.map { it.dark } }
        }.getOrNull()
    }
    Canvas(modifier) {
        drawRect(Color.White, size = size)
        val m = matrix ?: return@Canvas
        val rows = m.size
        if (rows == 0) return@Canvas
        val cols = m[0].size
        val cell = minOf(size.width / cols, size.height / rows)
        val offX = (size.width - cell * cols) / 2f
        val offY = (size.height - cell * rows) / 2f
        for (y in 0 until rows) for (x in 0 until cols) {
            if (m[y][x]) drawRect(Color.Black, topLeft = Offset(offX + x * cell, offY + y * cell), size = Size(cell, cell))
        }
    }
}
