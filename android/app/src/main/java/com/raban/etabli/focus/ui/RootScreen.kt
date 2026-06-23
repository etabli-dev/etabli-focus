package com.raban.etabli.focus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.raban.etabli.focus.EtabliFocusApplication
import com.raban.etabli.focus.prefs.ThemePreference
import com.raban.etabli.focus.ui.screens.*
import com.raban.etabli.focus.ui.theme.Coder

// Bottom-nav shell — Android counterpart to iOS RootView's TabView.
// Five tabs: Today / Template / Stats / Categories / Settings.
@Composable
fun RootScreen(app: EtabliFocusApplication, setTheme: (ThemePreference) -> Unit) {
    val t = Coder.tokens
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = t.color.paper,
        bottomBar = {
            NavigationBar(containerColor = t.color.surface) {
                listOf(
                    Triple("Today",      Icons.Default.CalendarToday, 0),
                    Triple("Template",   Icons.Default.GridView,      1),
                    Triple("Stats",      Icons.Default.BarChart,      2),
                    Triple("Categories", Icons.Default.Tag,           3),
                    Triple("Settings",   Icons.Default.Settings,      4),
                ).forEach { (label, icon, idx) ->
                    NavigationBarItem(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, style = t.font.mono) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = t.color.accent,
                            selectedTextColor = t.color.accent,
                            indicatorColor = t.color.accentMuted,
                            unselectedIconColor = t.color.faint,
                            unselectedTextColor = t.color.faint,
                        ),
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(t.color.paper)) {
            when (tab) {
                0 -> TodayScreen(app)
                1 -> TemplateScreen(app)
                2 -> StatsScreen(app)
                3 -> CategoriesScreen(app)
                else -> SettingsScreen(app, setTheme)
            }
        }
    }
}
