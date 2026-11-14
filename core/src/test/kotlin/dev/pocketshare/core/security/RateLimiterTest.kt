package dev.pocketshare.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    private var now = 1_000L
    private val limiter = RateLimiter { now }
    private val policy = RateLimiter.Policy(maxRequests = 2, windowMillis = 10_000)

    @Test
    fun `allows up to the limit then blocks with retry after`() {
        assertTrue(limiter.tryAcquire("ip", policy).allowed)
        assertTrue(limiter.tryAcquire("ip", policy).allowed)
        val denied = limiter.tryAcquire("ip", policy)
        assertFalse(denied.allowed)
        assertTrue(denied.retryAfterSeconds in 1..10)
    }

    @Test
    fun `keys are isolated and reset works`() {
        assertTrue(limiter.tryAcquire("a", policy).allowed)
        assertTrue(limiter.tryAcquire("a", policy).allowed)
        assertFalse(limiter.tryAcquire("a", policy).allowed)
        assertTrue(limiter.tryAcquire("b", policy).allowed)
        limiter.reset("a")
        assertTrue(limiter.tryAcquire("a", policy).allowed)
    }

    @Test
    fun `window slides after expiry`() {
        assertTrue(limiter.tryAcquire("ip", policy).allowed)
        assertTrue(limiter.tryAcquire("ip", policy).allowed)
        assertFalse(limiter.tryAcquire("ip", policy).allowed)
        now += 10_001
        assertTrue(limiter.tryAcquire("ip", policy).allowed)
    }

    @Test
    fun `repeated lockouts use exponential backoff`() {
        repeat(2) { limiter.tryAcquire("ip", policy) }
        val firstBlock = limiter.tryAcquire("ip", policy)
        assertFalse(firstBlock.allowed)
        now += firstBlock.retryAfterSeconds * 1_000

        // reset window, allow two more, then a second strike must be longer
        limiter.tryAcquire("ip", policy)
        limiter.tryAcquire("ip", policy)
        val secondBlock = limiter.tryAcquire("ip", policy)
        assertFalse(secondBlock.allowed)
        assertTrue(secondBlock.retryAfterSeconds > firstBlock.retryAfterSeconds)
    }
}
