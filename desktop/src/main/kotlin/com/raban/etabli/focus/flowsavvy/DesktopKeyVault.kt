// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0
package com.raban.etabli.focus.flowsavvy

import java.util.concurrent.TimeUnit

/** macOS uses Keychain; other desktops keep the key only for this app session. */
class DesktopKeyVault(private val namespace: String = "flowsavvy", profile: String? = null) : KeyVault {
    private val mac = System.getProperty("os.name").lowercase().startsWith("mac")
    private var memory: String? = null
    override val description: String get() {
        val subject = if (namespace == "flowsavvy") "API key" else "Calendar credentials"
        return subject + (if (mac) " saved in macOS Keychain." else " kept in memory until the app closes.") +
            (if (namespace == "flowsavvy") " Requires FlowSavvy Pro." else "")
    }
    override fun read(): String? {
        if (!mac) return memory
        val process = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", SERVICE, "-a", "api-key", "-w").start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Keychain did not respond. Please retry.") }
        return if (process.exitValue() == 0) process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() } else null
    }
    override fun write(key: String?) {
        if (!mac) { memory = key; return }
        if (key == null) {
            val process = ProcessBuilder("/usr/bin/security", "delete-generic-password", "-s", SERVICE, "-a", "api-key").start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Keychain did not respond.") }
            check(read() == null) { "Could not disconnect from Keychain." }
            return
        }
        require(key.none { it == '\n' || it == '\r' })
        // Interactive stdin avoids putting the secret in the process argument list.
        val escaped = key.replace("\\", "\\\\").replace("\"", "\\\"")
        val process = ProcessBuilder("/usr/bin/security", "-i").redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.bufferedWriter().use { it.write("add-generic-password -U -s $SERVICE -a api-key -w \"$escaped\"\nquit\n") }
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Keychain did not respond.") }
        check(read() == key) { "Could not save credentials in Keychain." }
    }
    private val SERVICE = "com.raban.etabli.focus.$namespace" +
        (profile?.let { ".${FlowSavvy.fingerprint(it)}" } ?: "")
}
