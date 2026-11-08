// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.data

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Where the desktop app keeps its local data. Everything lives in one per-user
 * folder; nothing is written elsewhere.
 *
 * - Linux:   `$XDG_DATA_HOME/etabli-focus` (default `~/.local/share/etabli-focus`)
 * - macOS:   `~/Library/Application Support/com.raban.etabli.focus`
 * - Windows: `%APPDATA%\EtabliFocus` (kept apart from the per-user install
 *   folder `%LOCALAPPDATA%\EtabliFocus`, which the MSI owns)
 *
 * `ETABLI_FOCUS_DATA_DIR` (or the `etabli.focus.dataDir` system property)
 * overrides the folder, e.g. for tests or a portable install.
 */
class AppPaths(val root: Path) {
    /** Same file name as the Android Room database. */
    val database: Path get() = root.resolve("etabli_focus.db")
    /** Same names as the Android Preferences DataStore files. */
    val timerState: Path get() = root.resolve("datastore").resolve("timer_state.preferences_pb")
    val themePreference: Path get() = root.resolve("datastore").resolve("theme_pref.preferences_pb")

    /**
     * Two processes must not drive the same timer file. Returns null when
     * another instance already holds the lock.
     */
    fun acquireInstanceLock(): FileLock? {
        Files.createDirectories(root)
        val channel = FileChannel.open(
            root.resolve(".instance.lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE,
        )
        val lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) channel.close()
        return lock
    }

    companion object {
        fun resolve(
            env: (String) -> String? = System::getenv,
            property: (String) -> String? = System::getProperty,
        ): AppPaths {
            val override = property("etabli.focus.dataDir") ?: env("ETABLI_FOCUS_DATA_DIR")
            if (!override.isNullOrBlank()) return AppPaths(Paths.get(override))
            val home = Paths.get(property("user.home") ?: ".")
            val os = (property("os.name") ?: "").lowercase()
            val root = when {
                os.startsWith("windows") ->
                    (env("APPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                        ?: home.resolve("AppData").resolve("Roaming"))
                        .resolve("EtabliFocus")
                os.startsWith("mac") ->
                    home.resolve("Library").resolve("Application Support").resolve("com.raban.etabli.focus")
                else ->
                    (env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                        ?: home.resolve(".local").resolve("share"))
                        .resolve("etabli-focus")
            }
            return AppPaths(root)
        }
    }
}
