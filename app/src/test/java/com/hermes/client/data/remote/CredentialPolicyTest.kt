package com.hermes.client.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialPolicyTest {

    private fun allows(url: String, optIn: Boolean) = CredentialPolicy.allows(url.toHttpUrl(), optIn)

    @Test
    fun httpsAlwaysAllowed() {
        assertTrue(allows("https://example.com/v1", optIn = false))
        assertTrue(allows("https://example.com/v1", optIn = true))
    }

    @Test
    fun loopbackHttpRequiresExplicitOptIn() {
        listOf("http://127.0.0.1:8642/v1", "http://localhost:8642/v1", "http://[::1]:8642/v1").forEach {
            assertFalse(it, allows(it, optIn = false))
            assertTrue(it, allows(it, optIn = true))
        }
    }

    @Test
    fun otherPlaintextHostsNeverAllowedEvenWithOptIn() {
        assertFalse(allows("http://192.168.1.10:8642/v1", optIn = true))
        assertFalse(allows("http://example.com/v1", optIn = true))
        assertFalse(allows("http://127.0.0.1.evil.com/v1", optIn = true))
        assertFalse(allows("http://localhost.evil.com/v1", optIn = true))
    }

    @Test
    fun isUnderComparesWholePathSegments() {
        val base = "https://files.example.com/hermes-files".toHttpUrl()
        assertTrue(CredentialPolicy.isUnder("https://files.example.com/hermes-files".toHttpUrl(), base))
        assertTrue(CredentialPolicy.isUnder("https://files.example.com/hermes-files/a/b.png".toHttpUrl(), base))
        assertFalse(CredentialPolicy.isUnder("https://files.example.com/hermes-files-evil/a.png".toHttpUrl(), base))
        assertFalse(CredentialPolicy.isUnder("https://files.example.com/other".toHttpUrl(), base))
        assertFalse(CredentialPolicy.isUnder("https://files.example.com.evil.net/hermes-files/a".toHttpUrl(), base))
        assertFalse(CredentialPolicy.isUnder("https://files.example.com:8443/hermes-files/a".toHttpUrl(), base))
        assertFalse(CredentialPolicy.isUnder("http://files.example.com/hermes-files/a".toHttpUrl(), base))
    }
}
