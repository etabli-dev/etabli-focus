// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.isTraySupported
import com.raban.etabli.focus.FocusApp
import com.raban.etabli.focus.prefs.ThemePreference
import com.raban.etabli.focus.ui.theme.Card
import com.raban.etabli.focus.ui.theme.Coder
import com.raban.etabli.focus.ui.theme.MonoLabel
import com.raban.etabli.focus.ui.theme.PromptHeader
import com.raban.etabli.focus.ui.theme.SegmentChip

/** Set by jpackage launchers; absent when running from Gradle. */
internal fun appVersion(): String = System.getProperty("jpackage.app-version") ?: "development build"

@Composable
fun SettingsScreen(app: FocusApp, setTheme: (ThemePreference) -> Unit) {
    val t = Coder.tokens
    val preference by remember { app.themeRepo.flow }.collectAsState(initial = ThemePreference.System)

    Column(
        modifier = Modifier.fillMaxSize().background(t.color.paper)
            .verticalScroll(rememberScrollState()).padding(t.space.xl),
        verticalArrangement = Arrangement.spacedBy(t.space.lg),
    ) {
        PromptHeader(listOf("settings", "preferences"))

        Card(title = "appearance", icon = Icons.Default.Settings) {
            MonoLabel("theme", color = t.color.faint)
            Row(horizontalArrangement = Arrangement.spacedBy(t.space.sm)) {
                ThemePreference.entries.forEach { pref ->
                    SegmentChip(
                        label = pref.label.lowercase(),
                        selected = pref == preference,
                        modifier = Modifier.weight(1f),
                        onClick = { setTheme(pref) },
                    )
                }
            }
            MonoLabel(
                when (preference) {
                    ThemePreference.System -> "follows the system setting where the desktop reports it."
                    ThemePreference.Light -> "always light."
                    ThemePreference.Dark -> "always dark."
                },
                color = t.color.faint,
            )
        }

        Card(title = "about", icon = Icons.Default.Info) {
            Column(verticalArrangement = Arrangement.spacedBy(t.space.xs)) {
                Text("Établi Focus — desktop preview", style = t.font.headline)
                MonoLabel("the workbench for focused work.", color = t.color.faint)
                Spacer(Modifier.height(t.space.sm))
                AboutRow("version", appVersion())
                AboutRow("storage", "on this computer, no account")
                AboutRow("tray", if (isTraySupported) "available" else "not offered by this desktop")
                Spacer(Modifier.height(t.space.sm))
                MonoLabel("data folder", color = t.color.faint)
                SelectionContainer { MonoLabel(app.paths.root.toAbsolutePath().toString()) }
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    val t = Coder.tokens
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        MonoLabel(label)
        MonoLabel(value, color = t.color.faint)
    }
}
