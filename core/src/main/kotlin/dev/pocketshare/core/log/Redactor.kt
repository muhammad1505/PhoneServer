package dev.pocketshare.core.log

/**
 * Removes share tokens, passwords, cookies and Cloudflare tunnel tokens before anything reaches a
 * log file. Keep this conservative: false positives are acceptable, leaks are not.
 */
object Redactor {
    private val PATTERNS: List<Regex> =
        listOf(
            Regex(
                "(?i)((?:token|password|passwd|secret|authorization|cookie|set-cookie|" +
                    "cf-access-client-secret|cf-access-client-id|ps_session)" +
                    "[^:=\\s]*\\s*[=:]\\s*)([^\\s&;,\"']+)",
            ),
            Regex("(/s/)([A-Za-z0-9_-]{20,})"),
            Regex("\\beyJ[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_-]+){1,2}\\b"),
            Regex("\\b[A-Za-z0-9_-]{43}\\b"),
        )

    fun redact(input: String): String {
        var result = input
        result = PATTERNS[0].replace(result) { match -> "${match.groupValues[1]}[REDACTED]" }
        result = PATTERNS[1].replace(result) { match -> "${match.groupValues[1]}[REDACTED]" }
        result = PATTERNS[2].replace(result, "[REDACTED]")
        result = PATTERNS[3].replace(result, "[REDACTED]")
        return result
    }
}
