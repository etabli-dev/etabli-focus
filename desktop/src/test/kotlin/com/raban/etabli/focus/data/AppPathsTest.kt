// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.data

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AppPathsTest {
    private fun resolve(os: String, env: Map<String, String> = emptyMap(), props: Map<String, String> = emptyMap()) =
        AppPaths.resolve(env = { env[it] }, property = { (props + mapOf("os.name" to os, "user.home" to "/home/u"))[it] }).root

    @Test fun eachPlatformUsesItsConventionalPerUserFolder() {
        assertEquals(Paths.get("/home/u/.local/share/etabli-focus"), resolve("Linux"))
        assertEquals(Paths.get("/xdg/etabli-focus"), resolve("Linux", env = mapOf("XDG_DATA_HOME" to "/xdg")))
        assertEquals(Paths.get("/home/u/Library/Application Support/com.raban.etabli.focus"), resolve("Mac OS X"))
        assertEquals(Paths.get("/roaming/EtabliFocus"), resolve("Windows 11", env = mapOf("APPDATA" to "/roaming")))
        assertEquals(Paths.get("/home/u/AppData/Roaming/EtabliFocus"), resolve("Windows 11"))
    }

    @Test fun anExplicitDataFolderWins() {
        assertEquals(Paths.get("/tmp/a"), resolve("Linux", env = mapOf("ETABLI_FOCUS_DATA_DIR" to "/tmp/a")))
        assertEquals(Paths.get("/tmp/b"), resolve("Linux", env = mapOf("ETABLI_FOCUS_DATA_DIR" to "/tmp/a"),
            props = mapOf("etabli.focus.dataDir" to "/tmp/b")))
    }

    @Test fun onlyOneInstanceHoldsTheDataFolder() {
        val directory = Files.createTempDirectory("focus-lock")
        try {
            val first = assertNotNull(AppPaths(directory).acquireInstanceLock())
            // A second lock on the same file from this JVM fails like a second process would.
            assertNull(AppPaths(directory).acquireInstanceLock())
            first.release()
            first.channel().close()
            assertNotNull(AppPaths(directory).acquireInstanceLock()).let { it.release(); it.channel().close() }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
