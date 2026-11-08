// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Persisted Auto / Light / Dark override, same key and values as Android's
// ThemeRepo and the iOS AppStorage key "coder.theme.preference".

enum class ThemePreference(val label: String) {
    System("Auto"),
    Light("Light"),
    Dark("Dark");

    fun darkOverride(): Boolean? = when (this) {
        System -> null
        Light -> false
        Dark -> true
    }
}

private val KEY_THEME = stringPreferencesKey("coder.theme.preference")

class ThemeRepo(private val store: DataStore<Preferences>) {
    val flow: Flow<ThemePreference> = store.data
        .map { it[KEY_THEME] }
        .map { raw ->
            when (raw?.lowercase()) {
                "light" -> ThemePreference.Light
                "dark" -> ThemePreference.Dark
                else -> ThemePreference.System
            }
        }

    suspend fun set(value: ThemePreference) {
        store.edit { it[KEY_THEME] = value.name.lowercase() }
    }
}
