// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.FocusApp
import com.raban.etabli.focus.prefs.ThemePreference
import com.raban.etabli.focus.ui.screens.CategoriesScreen
import com.raban.etabli.focus.ui.screens.SettingsScreen
import com.raban.etabli.focus.ui.screens.StatsScreen
import com.raban.etabli.focus.ui.screens.TemplateScreen
import com.raban.etabli.focus.ui.screens.TodayScreen
import com.raban.etabli.focus.ui.theme.Coder
import com.raban.etabli.focus.ui.theme.FocusIcons

// Same five destinations as the Android bottom bar and the iOS TabView, as a
// navigation rail on the side, which suits wide desktop windows.
@Composable
fun RootScreen(app: FocusApp, setTheme: (ThemePreference) -> Unit) {
    val t = Coder.tokens
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val destinations = listOf(
        "Today" to Icons.Default.DateRange,
        "Template" to Icons.AutoMirrored.Filled.List,
        "Stats" to FocusIcons.BarChart,
        "Categories" to FocusIcons.Label,
        "Settings" to Icons.Default.Settings,
    )

    Row(Modifier.fillMaxSize().background(t.color.paper)) {
        NavigationRail(
            containerColor = t.color.surface,
            modifier = Modifier.fillMaxHeight().width(96.dp),
        ) {
            destinations.forEachIndexed { idx, (label, icon) ->
                NavigationRailItem(
                    selected = tab == idx,
                    onClick = { tab = idx },
                    icon = { Icon(icon, contentDescription = label) },
                    label = { Text(label, style = t.font.caption) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = t.color.accent,
                        selectedTextColor = t.color.accent,
                        indicatorColor = t.color.accentMuted,
                        unselectedIconColor = t.color.faint,
                        unselectedTextColor = t.color.faint,
                    ),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(t.color.paper)) {
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
