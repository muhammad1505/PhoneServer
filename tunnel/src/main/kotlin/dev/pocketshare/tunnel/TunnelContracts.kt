package dev.pocketshare.tunnel

import dev.pocketshare.core.model.TunnelMode
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min
import kotlin.random.Random

/**
 * Everything the tunnel needs to expose the local HTTP server. [namedToken] must never be logged or
 * serialized; the tunnel implementation passes it through an environment variable.
 */
data class TunnelRequest(
    val mode: TunnelMode,
    val localPort: Int,
    val namedToken: String? = null,
    val hostname: String? = null,
    val dnsServers: List<String> = emptyList(),
) {
    init {
        require(localPort in 1..65_535) { "Local port must be between 1 and 65535" }
        if (mode == TunnelMode.NAMED) {
            require(!namedToken.isNullOrBlank()) { "Named tunnels require a token" }
            require(!hostname.isNullOrBlank()) { "Named tunnels require a public hostname" }
        }
    }

    override fun toString(): String =
        "TunnelRequest(mode=$mode, localPort=$localPort, hostname=$hostname, " +
            "namedToken=${if (namedToken == null) "null" else "[REDACTED]"}, dnsServers=$dnsServers)"
}

sealed interface TunnelStatus {
    data class Stopped(
        val reason: String? = null,
    ) : TunnelStatus

    data class Starting(
        val attempt: Int,
    ) : TunnelStatus

    data class Running(
        val publicUrl: String,
        val mode: TunnelMode,
    ) : TunnelStatus

    /**
     * The process is alive but Cloudflare has no usable connection to this phone, so the public
     * link currently answers with an edge error (1033/530). Unlike [Reconnecting], the quick
     * tunnel URL is unchanged; traffic resumes when a connection registers again.
     */
    data class Degraded(
        val publicUrl: String?,
        val reason: String? = null,
    ) : TunnelStatus

    data class Reconnecting(
        val attempt: Int,
        val delayMillis: Long,
        val reason: String?,
    ) : TunnelStatus

    /** A configuration problem that retrying cannot fix (missing binary, invalid token, ...). */
    data class Failed(
        val reason: String,
    ) : TunnelStatus
}

interface TunnelProvider {
    val status: StateFlow<TunnelStatus>

    /**
     * Starts the supervisor. Returns once the supervisor is running; observe [status] for progress.
     * Calling this again replaces any previous tunnel.
     */
    suspend fun start(request: TunnelRequest)

    /** Stops the tunnel and waits for the child process to exit. */
    suspend fun stop()
}

fun interface BackoffPolicy {
    fun delayMillis(attempt: Int): Long
}

/** Result of asking cloudflared's loopback readiness endpoint about the tunnel. */
enum class Readiness {
    READY,
    NOT_READY,

    /** The endpoint is unreachable or answered unexpectedly; keep the previous state. */
    UNKNOWN,
}

/**
 * Checks cloudflared's `/ready` endpoint, which answers 200 only while at least one edge
 * connection is active. Loopback traffic, so probing can be frequent without spending data.
 */
interface ReadinessProbe {
    suspend fun check(address: String): Readiness
}

/**
 * Exponential backoff with optional jitter. [attempt] starts at 1 after the first failure.
 */
class ExponentialBackoff(
    private val initialMillis: Long = 1_000L,
    private val maxMillis: Long = 60_000L,
    private val multiplier: Double = 2.0,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) : BackoffPolicy {
    init {
        require(initialMillis > 0)
        require(maxMillis >= initialMillis)
        require(multiplier >= 1.0)
        require(jitterRatio in 0.0..1.0)
    }

    override fun delayMillis(attempt: Int): Long {
        val exponent = (attempt - 1).coerceAtLeast(0).toDouble()
        val raw = initialMillis.toDouble() * Math.pow(multiplier, min(exponent, 30.0))
        val capped = min(raw, maxMillis.toDouble())
        if (jitterRatio == 0.0) return capped.toLong()
        val jitter = capped * jitterRatio
        val low = (capped - jitter).coerceAtLeast(1.0)
        val high = min(capped + jitter, maxMillis.toDouble())
        return low.toLong() + (random.nextDouble() * (high - low)).toLong()
    }
}
