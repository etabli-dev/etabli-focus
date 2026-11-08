package com.raban.etabli.focus.flowsavvy

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopKeyVaultTest {
    @Test fun syntheticCredentialRoundTripAndRemoval() {
        val namespace = "test-${UUID.randomUUID()}"
        val vault = DesktopKeyVault(namespace)
        val value = """{"url":"https://calendar.example.test/","username":"user name","password":"quote\" and slash\\ and $ sign"}"""
        try {
            assertNull(vault.read())
            vault.write(value)
            assertEquals(value, vault.read())
            vault.write("replacement")
            assertEquals("replacement", vault.read())
        } finally { vault.write(null) }
        assertNull(vault.read())
    }
    @Test fun isolatedProfilesDoNotReadEachOthersCredentials() {
        val namespace = "test-${UUID.randomUUID()}"
        val first = DesktopKeyVault(namespace, "profile-one")
        val second = DesktopKeyVault(namespace, "profile-two")
        try {
            first.write("synthetic")
            assertEquals("synthetic", first.read())
            assertNull(second.read())
        } finally { first.write(null); second.write(null) }
    }

}
