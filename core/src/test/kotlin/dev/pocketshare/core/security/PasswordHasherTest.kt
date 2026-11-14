package dev.pocketshare.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {
    private val hasher = PasswordHasher(iterations = 2_000)

    @Test
    fun `hash verifies and stores a unique salt`() {
        val first = hasher.hash("correct horse battery staple")
        val second = hasher.hash("correct horse battery staple")

        assertNotEquals(first.saltBase64, second.saltBase64)
        assertNotEquals(first.hashBase64, second.hashBase64)
        assertTrue(hasher.verify("correct horse battery staple", first.hashBase64, first.saltBase64, first.iterations))
        assertFalse(hasher.verify("wrong", first.hashBase64, first.saltBase64, first.iterations))
    }

    @Test
    fun `unicode passwords are supported`() {
        val password = "kata sandi rahasia 🔐 — ñ"
        val hash = hasher.hash(password)
        assertTrue(hasher.verify(password, hash.hashBase64, hash.saltBase64, hash.iterations))
    }

    @Test
    fun `malformed verifier data is rejected without throwing`() {
        assertFalse(hasher.verify("password", "not-base64", "also-not", 2_000))
        assertFalse(hasher.verify("password", "", "", 2_000))
        assertFalse(hasher.verify("password", "AAAA", "AAAA", 2_000))
        assertFalse(hasher.verify("password", "AAAA", "AAAA", -1))
        assertFalse(hasher.verify("", "AAAA", "AAAA", 2_000))
    }

    @Test
    fun `password length is bounded`() {
        assertFalse(hasher.verify("x".repeat(PasswordHasher.MAX_PASSWORD_LENGTH + 1), "AAAA", "AAAA", 2_000))
    }
}
