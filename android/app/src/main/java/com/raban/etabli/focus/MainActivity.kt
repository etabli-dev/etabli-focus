// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import com.raban.etabli.focus.prefs.ThemePreference
import com.raban.etabli.focus.ui.RootScreen
import com.raban.etabli.focus.ui.theme.CoderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as EtabliFocusApplication

    private val askNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user choice — we just don't post if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationsIfNeeded()
        setContent {
            val preference by app.themeRepo.flow.collectAsState(initial = ThemePreference.System)
            CoderTheme(darkOverride = preference.darkOverride()) {
                RootScreen(
                    app = app,
                    setTheme = { value ->
                        app.appScope.launch { app.themeRepo.set(value) }
                    }
                )
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
