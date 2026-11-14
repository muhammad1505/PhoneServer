package dev.pocketshare.core.http

/**
 * Result of parsing an HTTP Range header. Only a single range is supported.
 */
sealed interface RangeSelection {
    data object Full : RangeSelection

    data class Partial(
        val start: Long,
        val endInclusive: Long,
    ) : RangeSelection {
        val length: Long
            get() = endInclusive - start + 1
    }

    data object Unsatisfiable : RangeSelection

    /** Malformed header or multiple ranges; the server answers 416. */
    data object Unsupported : RangeSelection
}

object RangeParser {
    private const val PREFIX = "bytes="

    /**
     * Parses a single byte range. When [sizeBytes] is unknown the range cannot be honoured and
     * [RangeSelection.Full] is returned.
     */
    fun parse(
        header: String?,
        sizeBytes: Long?,
    ): RangeSelection {
        if (header.isNullOrBlank()) return RangeSelection.Full
        if (sizeBytes == null) return RangeSelection.Full
        if (sizeBytes <= 0L) return RangeSelection.Unsatisfiable

        val value = header.trim()
        if (!value.startsWith(PREFIX, ignoreCase = true)) return RangeSelection.Unsupported
        val spec = value.substring(PREFIX.length).trim()
        if (spec.isEmpty() || spec.contains(',')) return RangeSelection.Unsupported

        val dash = spec.indexOf('-')
        if (dash < 0) return RangeSelection.Unsupported

        val startPart = spec.substring(0, dash).trim()
        val endPart = spec.substring(dash + 1).trim()

        if (startPart.isEmpty()) {
            if (!endPart.isDigits()) return RangeSelection.Unsupported
            val suffixLength = endPart.toLongOrNull() ?: return RangeSelection.Unsupported
            if (suffixLength <= 0L) return RangeSelection.Unsatisfiable
            val start = (sizeBytes - suffixLength).coerceAtLeast(0L)
            return RangeSelection.Partial(start, sizeBytes - 1L)
        }

        if (!startPart.isDigits()) return RangeSelection.Unsupported
        val start = startPart.toLongOrNull() ?: return RangeSelection.Unsupported
        if (endPart.isEmpty()) {
            if (start >= sizeBytes) return RangeSelection.Unsatisfiable
            return RangeSelection.Partial(start, sizeBytes - 1L)
        }

        if (!endPart.isDigits()) return RangeSelection.Unsupported
        val requestedEnd = endPart.toLongOrNull() ?: return RangeSelection.Unsupported
        if (start > requestedEnd || start >= sizeBytes) return RangeSelection.Unsatisfiable
        return RangeSelection.Partial(start, requestedEnd.coerceAtMost(sizeBytes - 1L))
    }

    private fun String.isDigits(): Boolean = isNotEmpty() && all { it in '0'..'9' }
}
