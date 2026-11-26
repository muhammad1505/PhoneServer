package dev.pocketshare.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

enum class Reachability {
    UNKNOWN,
    OK,
    UNREACHABLE,
}

sealed interface PublicProbeResult {
    data class Http(
        val statusCode: Int,
    ) : PublicProbeResult

    /** Local resolver failure; says nothing about the state of the tunnel. */
    data object HostUnresolved : PublicProbeResult

    /** Connect or read failure after the host resolved. */
    data object Failed : PublicProbeResult
}

/**
 * Requests `/healthz` through the public URL so the probe covers the same path a visitor takes:
 * browser → Cloudflare edge → tunnel → local server. A live cloudflared process alone does not
 * prove that path works (Cloudflare answers 1033/530 when no edge connection is registered).
 *
 * Returns the HTTP status, or null when the request failed locally. Only used while a session is
 * running, so the traffic cost is a few kilobytes per hour.
 */
class PublicReachabilityProbe(
    private val connectTimeoutMillis: Int = 8_000,
    private val readTimeoutMillis: Int = 8_000,
) {
    suspend fun probe(baseUrl: String): PublicProbeResult =
        withContext(Dispatchers.IO) {
            val target = "${baseUrl.trimEnd('/')}/healthz"
            val connection =
                runCatching { URL(target).openConnection() as HttpURLConnection }.getOrNull()
                    ?: return@withContext PublicProbeResult.Failed
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-cache")
                // Do not reuse a pooled socket that may still belong to the previous network.
                connection.setRequestProperty("Connection", "close")
                connection.setRequestProperty("User-Agent", "PocketShare-health/1.0")
                PublicProbeResult.Http(connection.responseCode)
            } catch (_: UnknownHostException) {
                PublicProbeResult.HostUnresolved
            } catch (_: IOException) {
                PublicProbeResult.Failed
            } finally {
                runCatching { connection.disconnect() }
            }
        }
}
