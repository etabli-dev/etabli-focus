// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.focus.notifications

import com.raban.etabli.focus.data.SessionEntity
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** Same wording as the Android receiver and the iOS local notification. */
data class CompletionNotice(val title: String, val body: String) {
    companion object {
        fun forSession(session: SessionEntity): CompletionNotice =
            if (session.kind == "focus") {
                CompletionNotice("Focus block complete", "Suggest a short break — or start the next block.")
            } else {
                CompletionNotice("Break complete", "Time to get back to it when you're ready.")
            }
    }
}

/**
 * Linux desktops show tray balloons inconsistently, so prefer the
 * freedesktop notification service through `notify-send` when it exists.
 * Returns false when that is not possible; the caller then falls back to the
 * tray notification.
 */
object LinuxNotifications {
    fun send(notice: CompletionNotice): Boolean {
        if (!System.getProperty("os.name", "").lowercase().startsWith("linux")) return false
        val command = mutableListOf("notify-send", "--app-name=Établi Focus")
        packagedIcon()?.let { command += "--icon=$it" }
        command += listOf(notice.title, notice.body)
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.close()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    /** jpackage installs the launcher icon next to the app's lib folder. */
    private fun packagedIcon(): String? {
        val launcher = System.getProperty("jpackage.app-path") ?: return null
        val bin = Paths.get(launcher).parent ?: return null
        val icon = bin.resolveSibling("lib").resolve("EtabliFocus.png")
        return icon.takeIf { Files.isRegularFile(it) }?.toString()
    }
}
