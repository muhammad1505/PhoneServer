package dev.pocketshare.core.http

/**
 * Safe names for HTTP headers, HTML and ZIP entries. A filename coming from a content provider is
 * untrusted input.
 */
object FileNames {
    private const val MAX_NAME_CHARS = 200

    fun escapeHtml(value: String): String =
        buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(ch)
                }
            }
        }

    fun displayName(
        raw: String?,
        fallback: String = "download",
    ): String {
        val cleaned =
            raw
                ?.filterNot { it.isControl() }
                ?.trim()
                ?.take(MAX_NAME_CHARS)
                ?: ""
        return cleaned.ifBlank { fallback }
    }

    /**
     * Builds an RFC 6266 / RFC 5987 Content-Disposition value with an ASCII fallback.
     */
    fun contentDisposition(fileName: String): String {
        val safe = displayName(fileName)
        val fallback =
            safe
                .map { ch ->
                    when {
                        ch.code in 0x20..0x7e && ch != '"' && ch != '\\' -> ch
                        else -> '_'
                    }
                }.joinToString("")
                .ifBlank { "download" }
        val encoded = rfc5987Encode(safe)
        return "attachment; filename=\"$fallback\"; filename*=UTF-8''$encoded"
    }

    /**
     * Sanitizes a relative path for use inside a ZIP. Separators are normalized and traversal
     * segments (`.` / `..`) are replaced. The result is always relative and never starts with `/`.
     */
    fun zipEntryPath(
        relativePath: String,
        fallbackName: String = "file",
    ): String {
        val normalized = relativePath.replace('\\', '/')
        val rawSegments =
            normalized
                .split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val safeSegments =
            rawSegments
                .map { segment ->
                    sanitizeSegment(segment)
                }.filter { it.isNotEmpty() }
        val result = safeSegments.joinToString("/")
        return result.ifBlank { sanitizeSegment(displayName(fallbackName)).ifBlank { "file" } }
    }

    fun uniqueZipEntry(
        relativePath: String,
        used: MutableSet<String>,
    ): String {
        val base = zipEntryPath(relativePath)
        if (used.add(base)) return base

        val slash = base.lastIndexOf('/')
        val dir = if (slash >= 0) base.substring(0, slash + 1) else ""
        val leaf = if (slash >= 0) base.substring(slash + 1) else base
        val dot = leaf.lastIndexOf('.').takeIf { it > 0 } ?: leaf.length
        val stem = leaf.substring(0, dot)
        val extension = leaf.substring(dot)

        var counter = 2
        while (true) {
            val candidate = "$dir$stem ($counter)$extension"
            if (used.add(candidate)) return candidate
            counter += 1
            if (counter > 10_000) return "$dir${stem}_$counter$extension"
        }
    }

    /** Percent-encodes a single path segment (not a whole path). */
    fun encodePathSegment(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return buildString(bytes.size) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                val isAllowed =
                    unsigned in 'a'.code..'z'.code ||
                        unsigned in 'A'.code..'Z'.code ||
                        unsigned in '0'.code..'9'.code ||
                        unsigned == '-'.code ||
                        unsigned == '.'.code ||
                        unsigned == '_'.code ||
                        unsigned == '~'.code
                if (isAllowed) append(unsigned.toChar()) else append('%').append(hex(unsigned))
            }
        }
    }

    private fun sanitizeSegment(input: String): String {
        val filtered =
            input
                .filterNot { it.isControl() || it in BIDI_CONTROLS }
                .replace('\\', '_')
                .replace('/', '_')
                .trim()
                .trim('.')
                .take(MAX_NAME_CHARS)
        return if (filtered.isBlank() || filtered == "." || filtered == "..") "_" else filtered
    }

    private fun rfc5987Encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return buildString(bytes.size) {
            bytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                val isAttrChar =
                    unsigned in 'a'.code..'z'.code ||
                        unsigned in 'A'.code..'Z'.code ||
                        unsigned in '0'.code..'9'.code ||
                        unsigned.toChar() in ATTR_CHARS
                if (isAttrChar) append(unsigned.toChar()) else append('%').append(hex(unsigned))
            }
        }
    }

    private fun hex(value: Int): String {
        val digits = "0123456789ABCDEF"
        return buildString(2) {
            append(digits[value shr 4])
            append(digits[value and 0x0f])
        }
    }

    private fun Char.isControl(): Boolean = code < 0x20 || code == 0x7f

    private val ATTR_CHARS = setOf('!', '#', '$', '&', '+', '-', '.', '^', '_', '`', '|', '~')
    private val BIDI_CONTROLS = '\u202A'..'\u202E'
}
