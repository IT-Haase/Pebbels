/*
 * Info-Tab — kleine Geschichte über Pebbels + Link zur Website.
 *
 * Ersetzt den früheren Debug-Tab in der Bottom-Navigation. Debug lebt
 * jetzt als Abschnitt unten in den Einstellungen.
 */
package com.g7monitor.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.g7monitor.R

@Composable
fun InfoTab() {
    val ctx = LocalContext.current
    val card  = Color(0x15FFFFFF)
    val dim   = Color(0xCCFFFFFF)
    val faint = Color(0x77FFFFFF)
    val green = Color(0xFF22C55E)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            stringResource(R.string.info_hello),
            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.info_dates),
            color = faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace
        )

        Image(
            painter = painterResource(R.drawable.pebbels),
            contentDescription = "Pebbels",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
        )

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.info_story1),
                color = dim, fontSize = 14.sp, lineHeight = 20.sp
            )
            Text(
                stringResource(R.string.info_story2),
                color = dim, fontSize = 14.sp, lineHeight = 20.sp
            )
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.info_more), color = faint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(
                stringResource(R.string.info_more_text),
                color = dim, fontSize = 13.sp
            )
            Button(
                onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://sa1.de/pebbels-website"))
                    )
                },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = green),
            ) {
                Text(stringResource(R.string.info_open_website), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Text(
                "sa1.de/pebbels-website",
                color = faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }

        Text(
            "Pebbels APP · v" + com.g7monitor.BuildConfig.VERSION_NAME +
            " · Open Source (GPL-3.0)\n" + stringResource(R.string.info_free),
            color = faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp
        )
    }
}
