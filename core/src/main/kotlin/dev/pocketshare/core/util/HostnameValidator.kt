package dev.pocketshare.core.util

import java.net.IDN
import java.util.Locale

object HostnameValidator {
    /**
     * Normalizes user input such as `https://files.example.com/` to `files.example.com`.
     * Returns null for invalid public hostnames, IPs, localhost or schemes other than http(s).
     */
    fun normalize(input: String): String? {
        var value = input.trim()
        if (value.isEmpty() || value.length > 300) return null

        val lower = value.lowercase(Locale.ROOT)
        if (lower.startsWith("https://")) value = value.substring(8)
        if (lower.startsWith("http://")) value = value.substring(7)
        value = value.substringBefore('?').substringBefore('#').substringBefore('/')
        value = value.substringBefore('@')
        value = value.removeSuffix(".").trim()

        val portMatch = Regex("^(.+):(\\d{1,5})$").matchEntire(value)
        if (portMatch != null) value = portMatch.groupValues[1]
        if (value.contains(':') || value.startsWith("[") || value.isEmpty()) return null

        val ascii =
            try {
                IDN.toASCII(value, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (ascii.length > 253 || ascii.startsWith(".") || ascii.endsWith(".") || ascii.contains("..")) return null

        val labels = ascii.split('.')
        if (labels.size < 2) return null
        for (label in labels) {
            if (label.length !in 1..63) return null
            if (!label.first().isLetterOrDigit() || !label.last().isLetterOrDigit()) return null
            if (!label.all { it.isLetterOrDigit() || it == '-' }) return null
        }
        if (labels.last().length < 2 || labels.last().all { it.isDigit() }) return null
        return ascii
    }
}
