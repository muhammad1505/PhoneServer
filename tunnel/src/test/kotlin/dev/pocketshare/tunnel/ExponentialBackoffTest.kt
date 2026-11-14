package dev.pocketshare.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExponentialBackoffTest {
    @Test
    fun `doubles without jitter and caps at maximum`() {
        val backoff =
            ExponentialBackoff(
                initialMillis = 1_000,
                maxMillis = 20_000,
                multiplier = 2.0,
                jitterRatio = 0.0,
            )
        assertEquals(1_000, backoff.delayMillis(1))
        assertEquals(2_000, backoff.delayMillis(2))
        assertEquals(4_000, backoff.delayMillis(3))
        assertEquals(8_000, backoff.delayMillis(4))
        assertEquals(16_000, backoff.delayMillis(5))
        assertEquals(20_000, backoff.delayMillis(6))
        assertEquals(20_000, backoff.delayMillis(100))
    }

    @Test
    fun `jitter stays within configured bounds`() {
        val backoff =
            ExponentialBackoff(
                initialMillis = 2_000,
                maxMillis = 60_000,
                multiplier = 2.0,
                jitterRatio = 0.25,
                random = Random(42),
            )
        repeat(10) { index ->
            val attempt = index + 1
            val value = backoff.delayMillis(attempt)
            val raw = (2_000L shl (attempt - 1).coerceAtMost(10)).coerceAtMost(60_000L)
            val high = (raw * 5 / 4).coerceAtMost(60_000L)
            assertTrue("attempt $attempt -> $value (raw $raw)", value in (raw * 3 / 4)..high)
        }
    }
}
