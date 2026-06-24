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
    lateinit var themeRepo: ThemeRepo
        private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(this)
        engine = PomodoroEngine.get(this, db, appScope)
        themeRepo = ThemeRepo(this)
        // First-launch seeding: insert default categories if the table is empty.
        appScope.launch {
            if (db.categoryDao().count() == 0) {
                db.categoryDao().insertAll(CategoryEntity.defaults())
            }
        }
    }
}
