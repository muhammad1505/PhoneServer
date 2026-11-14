package dev.pocketshare.core.security

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * Fixed-window rate limiter with exponential lockout backoff.
 *
 * It is deliberately small and in-memory: all state disappears when the server stops, which is
 * acceptable because the server itself is the only process serving share links.
 */
class RateLimiter(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Policy(
        val maxRequests: Int,
        val windowMillis: Long,
    ) {
        init {
            require(maxRequests > 0)
            require(windowMillis > 0)
        }
    }

    data class Decision(
        val allowed: Boolean,
        val retryAfterSeconds: Long = 0,
    )

    private data class Bucket(
        var windowStartMs: Long,
        var count: Int = 0,
        var blockedUntilMs: Long = 0,
        var strikes: Int = 0,
        var lastSeenMs: Long = 0,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(
        key: String,
        policy: Policy,
    ): Decision {
        val now = clock()
        pruneIfNeeded(now)
        synchronized(buckets) {
            val bucket = buckets.getOrPut(key) { Bucket(windowStartMs = now) }
            bucket.lastSeenMs = now

            if (now < bucket.blockedUntilMs) {
                return Decision(allowed = false, retryAfterSeconds = retryAfter(now, bucket.blockedUntilMs))
            }

            if (now - bucket.windowStartMs >= policy.windowMillis) {
                bucket.windowStartMs = now
                bucket.count = 0
            }

            if (bucket.count >= policy.maxRequests) {
                bucket.strikes = min(bucket.strikes + 1, MAX_STRIKES)
                val multiplier = 1L shl min(bucket.strikes - 1, 8)
                val blockFor = min(policy.windowMillis * multiplier, MAX_BLOCK_MILLIS)
                bucket.blockedUntilMs = now + blockFor
                return Decision(allowed = false, retryAfterSeconds = retryAfter(now, bucket.blockedUntilMs))
            }

            bucket.count += 1
            return Decision(allowed = true)
        }
    }

    fun reset(key: String) {
        buckets.remove(key)
    }

    private fun retryAfter(
        now: Long,
        until: Long,
    ): Long = ceil((until - now).coerceAtLeast(1L) / 1_000.0).toLong().coerceAtLeast(1L)

    private fun pruneIfNeeded(now: Long) {
        if (buckets.size < PRUNE_THRESHOLD) return
        buckets.entries.removeIf { now - it.value.lastSeenMs > PRUNE_AGE_MILLIS }
    }

    companion object {
        private const val MAX_STRIKES = 8
        private const val MAX_BLOCK_MILLIS = 24L * 60L * 60L * 1_000L
        private const val PRUNE_THRESHOLD = 10_000
        private const val PRUNE_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
