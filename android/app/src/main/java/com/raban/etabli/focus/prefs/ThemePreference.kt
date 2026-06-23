package com.raban.etabli.focus.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Persisted Auto / Light / Dark override — same key shape and semantics as
// the iOS ThemePreference (AppStorage key "coder.theme.preference").

enum class ThemePreference(val label: String, val systemImage: String) {
    System("Auto",  "circle.lefthalf.filled"),
    Light ("Light", "sun.max"),
    Dark  ("Dark",  "moon");

    fun darkOverride(): Boolean? = when (this) {
        System -> null
        Light  -> false
        Dark   -> true
    }
}

private val Context.themeStore by preferencesDataStore(name = "theme_pref")
private val KEY_THEME = stringPreferencesKey("coder.theme.preference")

class ThemeRepo(private val context: Context) {
    val flow: Flow<ThemePreference> = context.themeStore.data
        .map { it[KEY_THEME] }
        .map { raw ->
            when (raw?.lowercase()) {
                "light" -> ThemePreference.Light
                "dark"  -> ThemePreference.Dark
                else    -> ThemePreference.System
            }
        }

    suspend fun set(value: ThemePreference) {
        context.themeStore.edit { it[KEY_THEME] = value.name.lowercase() }
    }
}
