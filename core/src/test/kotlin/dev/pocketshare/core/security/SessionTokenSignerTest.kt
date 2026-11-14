package dev.pocketshare.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SessionTokenSignerTest {
    private var now = 1_700_000_000_000L
    private val clock =
        object : Clock() {
            override fun getZone(): ZoneOffset = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId): Clock = this

            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
    private val signer =
        SessionTokenSigner(
            secret = ByteArray(32) { (it + 1).toByte() },
            clock = clock,
            ttlMillis = 60_000,
        )

    @Test
    fun `issued token is valid for the bound share`() {
        val token = signer.issue(42)
        assertTrue(signer.isValid(token, 42))
        assertFalse(signer.isValid(token, 43))
        assertFalse(signer.isValid(null, 42))
    }

    @Test
    fun `token expires`() {
        val token = signer.issue(42)
        now += 60_001
        assertFalse(signer.isValid(token, 42))
    }

    @Test
    fun `tampered token is rejected`() {
        val token = signer.issue(42)
        val tampered = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'
        assertFalse(signer.isValid(tampered, 42))
        assertFalse(signer.isValid("garbage", 42))
        assertFalse(signer.isValid("a.b.c", 42))
    }

    @Test
    fun `scoped ticket is valid for its own scope only`() {
        val ticket = signer.issueScoped("play:42:clip")
        assertTrue(signer.isValidScoped(ticket, "play:42:clip"))
        assertFalse(signer.isValidScoped(ticket, "play:42:other"))
        assertFalse(signer.isValidScoped(null, "play:42:clip"))
        assertFalse(signer.isValidScoped("garbage", "play:42:clip"))
    }

    @Test
    fun `scoped ticket expires and cannot be used as a session`() {
        val ticket = signer.issueScoped("play:42:clip")
        val session = signer.issue(42)
        assertFalse(signer.isValid(ticket, 42))
        assertFalse(signer.isValidScoped(session, "play:42:clip"))
        assertTrue(signer.isValidScoped(ticket, "play:42:clip"))
        now += 60_001
        assertFalse(signer.isValidScoped(ticket, "play:42:clip"))
    }

    @Test
    fun `different secrets do not validate each other`() {
        val other = SessionTokenSigner(ByteArray(32) { 9 }, clock, 60_000)
        assertFalse(other.isValid(signer.issue(1), 1))
    }
}
