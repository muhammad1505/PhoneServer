package dev.pocketshare.tunnel

import dev.pocketshare.core.model.TunnelMode
import java.io.File

data class CloudflaredProcessSpec(
    val command: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File?,
)

/**
 * Builds the cloudflared invocation. The named-tunnel token travels in `TUNNEL_TOKEN` instead of
 * argv so it cannot show up in process listings or crash reports.
 */
object CloudflaredCommand {
    fun build(
        request: TunnelRequest,
        binaryPath: String,
        baseEnvironment: Map<String, String> = emptyMap(),
        workingDirectory: File? = null,
        edgeAddresses: List<String> = emptyList(),
    ): CloudflaredProcessSpec {
        require(binaryPath.isNotBlank()) { "cloudflared binary path is empty" }
        val edgeTargets =
            edgeAddresses
                .mapNotNull(::edgeAddress)
                .distinct()
                .take(MAX_EDGE_ADDRESSES)
        val command =
            when (request.mode) {
                TunnelMode.QUICK ->
                    buildList {
                        add(binaryPath)
                        add("tunnel")
                        add("--no-autoupdate")
                        // UDP NAT on mobile carriers silently drops idle QUIC flows. HTTP/2 over
                        // TCP keeps the quick tunnel (the default mode) reachable on weak signal.
                        add("--protocol")
                        add("http2")
                        add("--url")
                        add("http://127.0.0.1:${request.localPort}")
                    }

                TunnelMode.NAMED ->
                    buildList {
                        add(binaryPath)
                        add("tunnel")
                        add("--no-autoupdate")
                        // TCP/HTTP2 survives carrier UDP NAT timeouts far better than QUIC on
                        // mobile networks.
                        add("--protocol")
                        add("http2")
                        add("run")
                        request.dnsServers
                            .mapNotNull(::resolverAddress)
                            .forEach { resolver ->
                                add("--dns-resolver-addrs")
                                add(resolver)
                            }
                    }
            }

        val environment =
            buildMap {
                putAll(baseEnvironment)
                put("NO_AUTOUPDATE", "true")
                put("TUNNEL_LOGLEVEL", "info")
                put("TUNNEL_METRICS", "127.0.0.1:0")
                if (request.mode == TunnelMode.NAMED) {
                    put("TUNNEL_TOKEN", request.namedToken.orEmpty())
                }
                val resolvers = request.dnsServers.mapNotNull(::resolverAddress)
                if (resolvers.isNotEmpty()) {
                    put("TUNNEL_DNS_RESOLVER_ADDRS", resolvers.joinToString(","))
                    // Read by the patched cloudflared build. Upstream ignores unknown variables;
                    // it is kept separate from TUNNEL_DNS_RESOLVER_ADDRS because that flag only
                    // configures cloudflared's virtual DNS origin and never fixes edge discovery.
                    put("TUNNEL_DNS_SERVERS", resolvers.joinToString(","))
                }
                if (edgeTargets.isNotEmpty()) {
                    put("TUNNEL_EDGE", edgeTargets.joinToString(","))
                }
            }

        return CloudflaredProcessSpec(
            command = command,
            environment = environment,
            workingDirectory = workingDirectory,
        )
    }

    private fun resolverAddress(raw: String): String? =
        numericHost(raw)?.let { host -> if (host.contains(':')) "[$host]:53" else "$host:53" }

    private fun edgeAddress(raw: String): String? =
        numericHost(raw)?.let { host ->
            if (host.contains(':')) "[$host]:$EDGE_PORT" else "$host:$EDGE_PORT"
        }

    /** Returns a bare IPv4/IPv6 literal, or null for anything that is not a numeric address. */
    private fun numericHost(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty() || value.length > 64) return null
        if (value.contains('/') || value.contains(' ') || value.contains('=')) return null
        val host = value.removePrefix("[").substringBefore(']').substringBefore('%')
        if (host.isEmpty()) return null
        val looksLikeV4 =
            host.count { it == '.' } == 3 &&
                host.all { it.isDigit() || it == '.' } &&
                host.split('.').all { part -> part.isNotEmpty() && part.toIntOrNull() in 0..255 }
        val looksLikeV6 =
            host.contains(':') &&
                host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if (!looksLikeV4 && !looksLikeV6) return null
        return host
    }

    private const val EDGE_PORT = 7844
    private const val MAX_EDGE_ADDRESSES = 4
}
