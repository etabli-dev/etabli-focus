// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.raban.etabli.focus.EtabliFocusApplication
import com.raban.etabli.focus.prefs.ThemePreference
import com.raban.etabli.focus.ui.theme.*

@Composable
fun SettingsScreen(
    app: EtabliFocusApplication,
    setTheme: (ThemePreference) -> Unit,
) {
    val t = Coder.tokens
    val preference by app.themeRepo.flow.collectAsState(initial = ThemePreference.System)

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.lg),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("settings", "preferences"))

        Card(title = "appearance", icon = Icons.Default.Palette) {
            MonoLabel("theme", color = t.color.faint)
            Spacer(Modifier.height(t.space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                ThemePreference.values().forEach { pref ->
                    ThemeChip(
                        label = pref.shortLabel(),
                        selected = pref == preference,
                        modifier = Modifier.weight(1f),
                        onClick = { setTheme(pref) },
                    )
                }
            }
            Spacer(Modifier.height(t.space.sm))
            MonoLabel(
                when (preference) {
                    ThemePreference.System -> "follows the system setting."
                    ThemePreference.Light  -> "always light."
                    ThemePreference.Dark   -> "always dark."
                },
                color = t.color.faint,
            )
        }

        Card(title = "about", icon = Icons.Default.Info) {
            Column(verticalArrangement = Arrangement.spacedBy(t.space.xs)) {
                Text("Établi Focus — Android", style = t.font.headline)
                MonoLabel("the workbench for focused work.", color = t.color.faint)
                Spacer(Modifier.height(t.space.sm))
                Row(horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("version"); MonoLabel("1.0", color = t.color.faint)
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("min SDK"); MonoLabel("26", color = t.color.faint)
                }
                Row(horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    MonoLabel("storage"); MonoLabel("on-device", color = t.color.faint)
                }
            }
        }
    }
}

private fun ThemePreference.shortLabel() = when (this) {
    ThemePreference.System -> "auto"
    ThemePreference.Light  -> "light"
    ThemePreference.Dark   -> "dark"
}

@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = Coder.tokens
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(t.radius.sm))
            .background(if (selected) t.color.accent.copy(alpha = 0.12f) else t.color.surface)
            .border(
                1.dp,
                if (selected) t.color.accent else t.color.hairline,
                RoundedCornerShape(t.radius.sm),
            )
            .clickable(onClick = onClick)
            .padding(vertical = t.space.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = t.font.caption.copy(
            color = if (selected) t.color.accent else t.color.faint,
        ))
    }
}
