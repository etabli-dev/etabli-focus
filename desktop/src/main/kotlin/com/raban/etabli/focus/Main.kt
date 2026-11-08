// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.raban.etabli.focus.data.AppPaths
import com.raban.etabli.focus.engine.TimerPhase
import com.raban.etabli.focus.engine.TimerState
import com.raban.etabli.focus.engine.progress
import com.raban.etabli.focus.engine.remainingSeconds
import com.raban.etabli.focus.notifications.CompletionNotice
import com.raban.etabli.focus.notifications.LinuxNotifications
import com.raban.etabli.focus.prefs.ThemePreference
import com.raban.etabli.focus.ui.RootScreen
import com.raban.etabli.focus.ui.clockText
import com.raban.etabli.focus.ui.rememberNow
import com.raban.etabli.focus.ui.theme.CoderTheme
import com.raban.etabli.focus.ui.theme.FocusMarkPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import javax.swing.JOptionPane
import kotlin.system.exitProcess

private const val APP_NAME = "Établi Focus"

fun main() {
    val paths = AppPaths.resolve()
    val lock = paths.acquireInstanceLock()
    if (lock == null) {
        System.err.println("$APP_NAME is already running with data folder ${paths.root}.")
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, "$APP_NAME is already running.", APP_NAME, JOptionPane.INFORMATION_MESSAGE)
        }
        exitProcess(1)
    }

    val app = FocusApp(paths)
    try {
        // Seed defaults and reconcile a block that ran out while the app was closed.
        runBlocking { app.prepare() }
        application(exitProcessOnExit = false) {
            FocusDesktop(app)
        }
    } finally {
        app.close()
        lock.release()
        lock.channel().close()
    }
    exitProcess(0)
}

/** "12:34 left" while a block is active, null when idle. */
private fun statusLine(timer: TimerState, now: Long): String? = when (timer.phase) {
    TimerPhase.Idle -> null
    TimerPhase.Running -> "${clockText(timer.remainingSeconds(now))} left"
    TimerPhase.Paused -> "paused · ${clockText(timer.remainingSeconds(now))} left"
}

@Composable
private fun ApplicationScope.FocusDesktop(app: FocusApp) {
    val scope = rememberCoroutineScope()
    val theme by app.themeRepo.flow.collectAsState(initial = ThemePreference.System)
    val timer by app.engine.state.collectAsState()
    val now by rememberNow(periodMillis = 500)
    val trayState = rememberTrayState()
    var visible by remember { mutableStateOf(true) }
    val active = timer.phase != TimerPhase.Idle
    val status = statusLine(timer, now)

    LaunchedEffect(Unit) {
        app.completed.collect { session ->
            val notice = CompletionNotice.forSession(session)
            val delivered = withContext(Dispatchers.IO) { LinuxNotifications.send(notice) }
            if (!delivered && isTraySupported) {
                trayState.sendNotification(Notification(notice.title, notice.body, Notification.Type.Info))
            }
        }
    }

    if (isTraySupported) {
        // Quantised so the tray image is re-rendered a few dozen times per block, not every tick.
        val step = if (active) (timer.progress(now) * 24).toInt() / 24f else null
        Tray(
            icon = remember(step) { FocusMarkPainter(step) },
            state = trayState,
            tooltip = status?.let { "$APP_NAME — $it" } ?: APP_NAME,
            onAction = { visible = true },
            menu = {
                Item(status ?: "No block running", enabled = false, onClick = {})
                when (timer.phase) {
                    TimerPhase.Running -> Item("Pause", onClick = { app.engine.pause() })
                    TimerPhase.Paused -> Item("Resume", onClick = { app.engine.resume() })
                    TimerPhase.Idle -> Unit
                }
                Separator()
                Item("Show window", onClick = { visible = true })
                Item("Quit", onClick = ::exitApplication)
            },
        )
    }

    Window(
        // With a tray, closing the window keeps an active block running in the
        // background; otherwise closing quits (the deadline persists either way).
        onCloseRequest = { if (isTraySupported && active) visible = false else exitApplication() },
        visible = visible,
        state = rememberWindowState(
            size = DpSize(1120.dp, 780.dp),
            position = WindowPosition(Alignment.Center),
        ),
        title = status?.let { "$it · $APP_NAME" } ?: APP_NAME,
        icon = remember { FocusMarkPainter() },
    ) {
        LaunchedEffect(Unit) { window.minimumSize = Dimension(720, 560) }
        CoderTheme(darkOverride = theme.darkOverride()) {
            RootScreen(app, setTheme = { value -> scope.launch { app.themeRepo.set(value) } })
        }
    }
}
