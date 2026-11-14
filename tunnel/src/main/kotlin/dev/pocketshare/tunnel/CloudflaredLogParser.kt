package dev.pocketshare.tunnel

import dev.pocketshare.core.log.Redactor
import java.util.Locale

data class ParsedCloudflaredLine(
    val quickTunnelUrl: String? = null,
    val connected: Boolean = false,
    val disconnected: Boolean = false,
    val connectionIndex: Int? = null,
    val metricsAddress: String? = null,
    val error: String? = null,
    val fatal: Boolean = false,
)

/**
 * Parses cloudflared's human-readable log. Only a handful of lines matter; everything else is
 * forwarded to the redacted local log at debug level.
 */
object CloudflaredLogParser {
    private val quickUrl =
        Regex(
            "https://[-a-zA-Z0-9@:%._+~#=]{1,256}\\.trycloudflare\\.com",
            RegexOption.IGNORE_CASE,
        )
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val levelMarker = Regex("\\b(ERR|FTL|WRN)\\b")
    private val connectionIndexPattern = Regex("\\bconnIndex=(\\d{1,2})\\b")
    private val metricsAddressPattern =
        Regex("Starting metrics server on (127\\.0\\.0\\.1:\\d{2,5})/metrics")

    private val fatalFragments =
        listOf(
            "provided tunnel token is not valid",
            "invalid tunnel secret",
            "failed to parse tunnel token",
            "token is not valid",
            "invalid tunnel token",
            "couldn't read tunnel credentials",
        )

    /**
     * Lines that mean an edge connection went away. cloudflared keeps running and retries on its
     * own, so these must eventually surface as [TunnelStatus.Degraded] instead of staying "live".
     */
    private val disconnectFragments =
        listOf(
            "lost connection with the edge",
            "unregistered tunnel connection",
            "retrying connection in up to",
            "serve tunnel error",
            "failed to serve tunnel connection",
            "connection terminated",
        )

    fun parse(rawLine: String): ParsedCloudflaredLine {
        val line = sanitize(rawLine)
        if (line.isBlank()) return ParsedCloudflaredLine()

        val lower = line.lowercase(Locale.ROOT)
        val fatal = fatalFragments.any { lower.contains(it) }
        val looksLikeError =
            levelMarker.containsMatchIn(line) ||
                lower.contains("failed to request quick tunnel") ||
                lower.contains("failed to initialize dns") ||
                lower.contains("no such file or directory") ||
                lower.contains("i/o timeout")
        val disconnected = disconnectFragments.any { lower.contains(it) }
        val url =
            if (looksLikeError || fatal) {
                null
            } else {
                quickUrl
                    .find(line)
                    ?.value
                    ?.trimEnd('.', ',', ')', ']')
                    ?.takeUnless { it.equals("https://api.trycloudflare.com", ignoreCase = true) }
            }
        val connected =
            !looksLikeError &&
                !disconnected &&
                (
                    line.contains("Registered tunnel connection", ignoreCase = true) ||
                        line.contains("Connection registered", ignoreCase = true)
                )

        return ParsedCloudflaredLine(
            quickTunnelUrl = url,
            connected = connected,
            disconnected = disconnected,
            connectionIndex =
                connectionIndexPattern
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull(),
            metricsAddress =
                metricsAddressPattern
                    .find(line)
                    ?.groupValues
                    ?.get(1),
            error = if (looksLikeError || fatal) line else null,
            fatal = fatal,
        )
    }

    fun sanitize(rawLine: String): String =
        Redactor
            .redact(
                rawLine.replace(ansi, "").replace('\r', ' ').trim(),
            ).take(MAX_LINE_CHARS)

    private const val MAX_LINE_CHARS = 600
}
