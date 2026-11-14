package dev.pocketshare.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenGeneratorTest {
    private val generator = TokenGenerator()

    @Test
    fun `token is 32 bytes base64url without padding`() {
        val token = generator.generate()
        assertEquals(TokenGenerator.TOKEN_LENGTH, token.length)
        assertTrue(TokenGenerator.isWellFormed(token))
        assertTrue(token.none { it == '=' || it == '+' || it == '/' })
    }

    @Test
    fun `tokens are unique`() {
        val tokens = (1..2_000).map { generator.generate() }.toSet()
        assertEquals(2_000, tokens.size)
    }

    @Test
    fun `well formed validation rejects malformed values`() {
        assertFalse(TokenGenerator.isWellFormed(""))
        assertFalse(TokenGenerator.isWellFormed("a".repeat(42)))
        assertFalse(TokenGenerator.isWellFormed("a".repeat(44)))
        assertFalse(TokenGenerator.isWellFormed("a".repeat(42) + "="))
        assertFalse(TokenGenerator.isWellFormed("a".repeat(42) + "/"))
    }

    @Test
    fun `constant time comparison accepts equal tokens only`() {
        val token = generator.generate()
        val copy = String(token.toCharArray())
        assertTrue(TokenGenerator.constantTimeEquals(token, copy))
        assertFalse(TokenGenerator.constantTimeEquals(token, generator.generate()))
        assertFalse(TokenGenerator.constantTimeEquals(token, token.dropLast(1)))
        assertFalse(TokenGenerator.constantTimeMiss(token))
    }
}
