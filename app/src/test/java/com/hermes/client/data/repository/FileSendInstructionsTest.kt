package com.hermes.client.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential embedded in the run prompt must be a scoped upload token — never the master
 * API key. A leaked master key is remote code execution on the server (the agent runs terminal
 * commands), and prompts are persisted server-side and processed next to untrusted content, so
 * assume anything in a prompt can leak. These tests pin that contract.
 */
class FileSendInstructionsTest {

    @Test
    fun embedsScopedTokenWithHttpsBase() {
        val instructions = FileSendInstructions.build(
            filesBase = "https://files.example.com/hermes-files",
            token = "uat_abc123",
        )!!
        assertTrue(instructions.contains("Bearer uat_abc123"))
        assertTrue(instructions.contains("https://files.example.com/hermes-files/upload"))
        assertTrue(instructions.contains("MEDIA:url"))
    }

    @Test
    fun missingTokenDegradesToNothingInsteadOfUsingAMasterKey() {
        assertNull(FileSendInstructions.build("https://files.example.com/hermes-files", token = null))
        assertNull(FileSendInstructions.build("https://files.example.com/hermes-files", token = ""))
        assertNull(FileSendInstructions.build("https://files.example.com/hermes-files", token = "   "))
    }

    @Test
    fun blankBaseYieldsNothing() {
        assertNull(FileSendInstructions.build("", token = "uat_abc123"))
        assertNull(FileSendInstructions.build("   ", token = "uat_abc123"))
    }

    @Test
    fun neverTeachesPlaintextUrls() {
        assertNull(FileSendInstructions.build("http://files.example.com/hermes-files", token = "uat_abc123"))
    }

    @Test
    fun loopbackPlaintextOnlyWithExplicitOptIn() {
        val base = "http://127.0.0.1:8643/hermes-files"
        assertNull(FileSendInstructions.build(base, token = "uat_abc123"))
        assertNull(FileSendInstructions.build(base, token = "uat_abc123", allowLoopback = false))
        val instructions = FileSendInstructions.build(base, token = "uat_abc123", allowLoopback = true)!!
        assertTrue(instructions.contains("Bearer uat_abc123"))
    }

    @Test
    fun optInNeverUnlocksNonLoopbackPlaintext() {
        assertNull(
            FileSendInstructions.build("http://files.example.com/hermes-files", token = "uat_abc123", allowLoopback = true),
        )
    }

    @Test
    fun outputNeverLooksLikeTheLegacyMasterKeyPattern() {
        // Regression guard for the incident: the old code interpolated the 48-hex master API
        // key here. Scoped tokens are "uat_"-prefixed, so a raw hex credential must never fit.
        val instructions = FileSendInstructions.build(
            filesBase = "https://files.example.com/hermes-files",
            token = "uat_" + "a1b2c3".repeat(8),
        )!!
        assertFalse(Regex("Bearer (?![a-z]+_)[0-9a-f]{48}").containsMatchIn(instructions))
    }

    @Test
    fun credentialMarkerIsScopedPrefix() {
        assertEquals("Bearer uat_", FileSendInstructions.CREDENTIAL_MARKER)
    }
}
