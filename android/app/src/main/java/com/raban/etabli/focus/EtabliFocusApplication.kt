// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus

import android.app.Application
import com.raban.etabli.focus.data.AppDatabase
import com.raban.etabli.focus.data.CategoryEntity
import com.raban.etabli.focus.engine.PomodoroEngine
import com.raban.etabli.focus.prefs.ThemeRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Process-wide singletons. Simple manual DI — no Hilt needed at this scale.
class EtabliFocusApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var db: AppDatabase
        private set
    lateinit var engine: PomodoroEngine
        private set
    lateinit var flowSavvy: com.raban.etabli.focus.flowsavvy.FlowSavvy
        private set
    val openCalendar = kotlinx.coroutines.flow.MutableStateFlow(false)
    lateinit var calendar: com.raban.etabli.focus.calendar.CalendarService
        private set
    lateinit var themeRepo: ThemeRepo
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(this)
        engine = PomodoroEngine.get(this, db, appScope)
        themeRepo = ThemeRepo(this)
        flowSavvy = com.raban.etabli.focus.flowsavvy.FlowSavvy(
            com.raban.etabli.focus.flowsavvy.AndroidKeyVault(this),
            com.raban.etabli.focus.flowsavvy.FileLedger(java.io.File(noBackupFilesDir, "flowsavvy-ledger.json").toPath()),
            db.sessionDao().observeAll(), appScope,
        )
        val calendarNotifications = com.raban.etabli.focus.notifications.CalendarNotifications(this)
        calendar = com.raban.etabli.focus.calendar.CalendarService(
            com.raban.etabli.focus.flowsavvy.AndroidKeyVault(this, "caldav"), appScope,
            calendarNotifications::schedule,
            cancelReminders = calendarNotifications::cancelAll,
        )
        engine.restoreAlarm()
        // First-launch seeding: insert default categories if the table is empty.
        appScope.launch {
            if (db.categoryDao().count() == 0) {
                db.categoryDao().insertAll(CategoryEntity.defaults())
            }
        }
    }
}
