package dev.pocketshare.core.log

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    @Test
    fun `redacts tokens in share urls`() {
        val token = "a".repeat(43)
        val redacted = Redactor.redact("GET https://x.trycloudflare.com/s/$token")
        assertFalse(redacted.contains(token))
        assertTrue(redacted.contains("/s/[REDACTED]"))
    }

    @Test
    fun `redacts password and token fields`() {
        val redacted = Redactor.redact("password=hunter2 token:abc123 cookie=session")
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("session"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun `redacts jwt like tunnel tokens and cookies`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJhIjoiYiJ9.signaturepart"
        val cookie = "ps_session=abcdef123456"
        val redacted = Redactor.redact("token $jwt; $cookie")
        assertFalse(redacted.contains(jwt))
        assertFalse(redacted.contains("abcdef123456"))
    }

    @Test
    fun `leaves harmless text alone`() {
        val text = "Server started on port 45612 for share file.zip"
        assertTrue(Redactor.redact(text).contains("45612"))
        assertTrue(Redactor.redact(text).contains("file.zip"))
    }
}
