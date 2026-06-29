/*
 * Haupt-Container mit Bottom-Navigation.
 *
 * Splittet die App in drei Tabs: Werte / Statistik / Einstellungen.
 * Tabs leben alle im selben Composable-Baum und teilen sich das
 * ViewModel-State; kein Navigation-Graph nötig.
 *
 * Design-Entscheidung:
 *   - Scaffold statt Column, damit die NavigationBar immer sichtbar ist
 *     und korrekt mit WindowInsets interagiert.
 *   - Keine NavHost-Abhängigkeit — das wäre für drei Tabs Overkill und
 *     würde die Gradle-Dependency erweitern. Lokaler `selectedTab`-State
 *     reicht.
 *   - Header (Titel + Status-Lampe) zeigen wir NUR auf dem Werte-Tab,
 *     damit die anderen Tabs mehr Platz für Inhalt haben.
 */
package com.g7monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.g7monitor.vm.ConnectionState
import com.g7monitor.vm.G7ViewModel

private enum class Tab { Values, Stats, Settings, Info }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun G7Screen(vm: G7ViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val bg = Color(0xFF060A06)
    val green = Color(0xFF22C55E)
    val dim = Color(0x88FFFFFF)
    val faint = Color(0x55FFFFFF)

    // rememberSaveable: überlebt Activity-Neuerstellung (z. B. Rückkehr vom
    // Kamera-Scan) — sonst springt die App auf den ersten Tab zurück.
    var tab by rememberSaveable { mutableStateOf(Tab.Values) }

    val isActive = state.connection in setOf(
        ConnectionState.Scanning,
        ConnectionState.Connecting,
        ConnectionState.Bonded,
        ConnectionState.Authenticating,
        ConnectionState.Authenticated,
        ConnectionState.Receiving,
    )
    val isLive = state.connection == ConnectionState.Receiving ||
                 state.connection == ConnectionState.Authenticated

    Scaffold(
        containerColor = bg,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        when (tab) {
                            Tab.Values -> "Pebbels APP"
                            Tab.Stats -> stringResource(R.string.tab_stats)
                            Tab.Settings -> stringResource(R.string.tab_settings)
                            Tab.Info -> stringResource(R.string.info_title)
                        },
                        color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "CGM-Monitor",
                        color = dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            if (isLive) green
                            else if (isActive) Color(0xFFFBBF24)
                            else faint
                        )
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0D120D),
                contentColor = Color.White
            ) {
                NavigationBarItem(
                    selected = tab == Tab.Values,
                    onClick = { tab = Tab.Values },
                    icon = { Icon(Icons.Filled.ShowChart, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_values)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = green.copy(alpha = 0.35f),
                        unselectedIconColor = dim,
                        unselectedTextColor = dim,
                    )
                )
                NavigationBarItem(
                    selected = tab == Tab.Stats,
                    onClick = { tab = Tab.Stats },
                    icon = { Icon(Icons.Outlined.BarChart, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_stats)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = green.copy(alpha = 0.35f),
                        unselectedIconColor = dim,
                        unselectedTextColor = dim,
                    )
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = green.copy(alpha = 0.35f),
                        unselectedIconColor = dim,
                        unselectedTextColor = dim,
                    )
                )
                NavigationBarItem(
                    selected = tab == Tab.Info,
                    onClick = { tab = Tab.Info },
                    icon = { Icon(Icons.Filled.Pets, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_info)) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        indicatorColor = green.copy(alpha = 0.35f),
                        unselectedIconColor = dim,
                        unselectedTextColor = dim,
                    )
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(bg)
        ) {
            when (tab) {
                Tab.Values   -> ValuesTab(vm, state)
                Tab.Stats    -> StatsTab(vm, state)
                Tab.Settings -> SettingsTab(vm, state)
                Tab.Info     -> InfoTab()
            }
        }
    }
}
