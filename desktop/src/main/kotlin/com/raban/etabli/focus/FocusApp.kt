// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import com.raban.etabli.focus.data.AppDatabase
import com.raban.etabli.focus.data.AppPaths
import com.raban.etabli.focus.data.CategoryEntity
import com.raban.etabli.focus.data.SessionEntity
import com.raban.etabli.focus.engine.PomodoroEngine
import com.raban.etabli.focus.notifications.DesktopAlarm
import com.raban.etabli.focus.prefs.ThemeRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

// Process-wide singletons, the desktop counterpart of EtabliFocusApplication.
class FocusApp(val paths: AppPaths) : AutoCloseable {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: AppDatabase = AppDatabase.open(paths.database)

    // Completions are buffered until the window/tray is ready, so a block that
    // ran out while the app was closed is still announced once after start-up.
    private val completions = Channel<SessionEntity>(Channel.UNLIMITED)
    val completed: Flow<SessionEntity> = completions.receiveAsFlow()

    private val alarm: DesktopAlarm = DesktopAlarm(appScope) { engine.tick() }

    val engine: PomodoroEngine = PomodoroEngine(
        store = PreferenceDataStoreFactory.create(scope = storeScope) { paths.timerState.toFile() },
        saveSession = { db.sessionDao().insert(it) },
        schedule = { at, _ -> alarm.schedule(at) },
        cancelAlarm = { alarm.cancel() },
        scope = appScope,
        onFinished = { completions.trySend(it) },
    )

    val flowSavvy = com.raban.etabli.focus.flowsavvy.FlowSavvy(
        com.raban.etabli.focus.flowsavvy.DesktopKeyVault(profile = paths.root.toAbsolutePath().normalize().toString()),
        com.raban.etabli.focus.flowsavvy.FileLedger(paths.root.resolve("flowsavvy-ledger.json")),
        db.sessionDao().observeAll(), appScope,
    )

    val calendarNotifications = com.raban.etabli.focus.notifications.CalendarNotifications(appScope)
    val calendar = com.raban.etabli.focus.calendar.CalendarService(
        com.raban.etabli.focus.flowsavvy.DesktopKeyVault("caldav", profile = paths.root.toAbsolutePath().normalize().toString()), appScope,
        calendarNotifications::schedule,
            cancelReminders = calendarNotifications::cancelAll,
    )

    val themeRepo = ThemeRepo(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = storeScope,
        ) { paths.themePreference.toFile() },
    )

    /** First-launch seeding and start-up completion reconciliation. */
    suspend fun prepare() {
        if (db.categoryDao().count() == 0) {
            db.categoryDao().insertAll(CategoryEntity.defaults())
        }
        engine.restoreAlarm().join()
    }

    override fun close() {
        alarm.cancel()
        appScope.cancel()
        storeScope.cancel()
        db.close()
    }
}
