package dev.pocketshare.server

import java.nio.charset.Charset
import java.util.Locale

/**
 * Minimal RTF reader that keeps visible text and drops formatting, pictures, metadata, and field
 * instructions. It understands group destinations, code-page escapes, and Unicode escapes, and it
 * never throws on malformed input.
 */
internal object RtfText {
    private val DEFAULT_CHARSET: Charset = Charset.forName("windows-1252")

    private val IGNORED_DESTINATIONS =
        setOf(
            "annotation",
            "atnauthor",
            "atnid",
            "bkmkend",
            "bkmkstart",
            "colorschememapping",
            "colortbl",
            "datastore",
            "filetbl",
            "fldinst",
            "fonttbl",
            "footer",
            "footerf",
            "footerl",
            "footerr",
            "footnote",
            "generator",
            "header",
            "headerf",
            "headerl",
            "headerr",
            "info",
            "latentstyles",
            "listoverridetable",
            "listtable",
            "listtext",
            "mmathPr",
            "object",
            "pict",
            "pn",
            "pnseclvl",
            "pntext",
            "revtbl",
            "rsidtbl",
            "shppict",
            "stylesheet",
            "tc",
            "themedata",
            "wgrffmtfilter",
            "xmlnstbl",
            "xe",
        )

    fun extract(bytes: ByteArray): String? {
        if (!hasHeader(bytes)) return null
        return Parser(bytes).parse()
    }

    private fun hasHeader(bytes: ByteArray): Boolean {
        var start = 0
        while (start < bytes.size && bytes[start].toInt().toChar().isWhitespace()) start++
        val header = "{\\rtf".toByteArray(Charsets.US_ASCII)
        if (start + header.size > bytes.size) return false
        return header.indices.all { bytes[start + it] == header[it] }
    }

    private class Parser(
        private val bytes: ByteArray,
    ) {
        private var index = 0
        private var charset: Charset = DEFAULT_CHARSET
        private var unicodeSkip = 1
        private var fallbackToSkip = 0
        private val output = StringBuilder()

        fun parse(): String {
            content(stopAtBrace = false)
            return output.toString()
        }

        private fun content(stopAtBrace: Boolean) {
            while (index < bytes.size) {
                when (val value = byteAt(index)) {
                    '{'.code -> {
                        index++
                        group()
                    }

                    '}'.code -> {
                        index++
                        if (stopAtBrace) return
                    }

                    '\\'.code -> {
                        index++
                        control()
                    }

                    else -> {
                        index++
                        literal(value)
                    }
                }
            }
        }

        private fun group() {
            if (starred() || destination() in IGNORED_DESTINATIONS) {
                skipGroup()
                return
            }
            content(stopAtBrace = true)
        }

        private fun starred(): Boolean = byteAt(index) == '\\'.code && byteAt(index + 1) == '*'.code

        private fun destination(): String? {
            if (byteAt(index) != '\\'.code) return null
            if (!letter(byteAt(index + 1))) return null
            val builder = StringBuilder()
            var cursor = index + 1
            while (letter(byteAt(cursor))) {
                builder.append(byteAt(cursor).toChar())
                cursor++
            }
            return builder.toString().lowercase(Locale.ROOT)
        }

        private fun control() {
            val value = byteAt(index)
            if (value < 0) return
            if (value == '\''.code) {
                index++
                hexEscape()
                return
            }
            if (!letter(value)) {
                index++
                symbol(value)
                return
            }
            val wordStart = index
            while (letter(byteAt(index))) index++
            val word = String(bytes, wordStart, index - wordStart, Charsets.US_ASCII).lowercase(Locale.ROOT)
            val number = number()
            if (byteAt(index) == ' '.code) index++
            word(word, number)
        }

        private fun number(): Int? {
            var sign = 1
            if (byteAt(index) == '-'.code) {
                sign = -1
                index++
            }
            val start = index
            while (byteAt(index) in '0'.code..'9'.code) index++
            if (index == start) return null
            return (String(bytes, start, index - start, Charsets.US_ASCII).toIntOrNull() ?: 0) * sign
        }

        private fun word(
            word: String,
            number: Int?,
        ) {
            when (word) {
                "ansicpg" -> number?.let(::applyCodePage)
                "uc" -> unicodeSkip = (number ?: 1).coerceIn(0, 4)
                "u" -> {
                    if (number != null) output.append((number and 0xFFFF).toChar())
                    fallbackToSkip = unicodeSkip
                }

                "par", "line", "sect", "page", "row" -> output.append('\n')
                "tab", "cell" -> output.append('\t')
                "emdash" -> output.append('\u2014')
                "endash" -> output.append('\u2013')
                "lquote" -> output.append('\u2018')
                "rquote" -> output.append('\u2019')
                "ldblquote" -> output.append('\u201C')
                "rdblquote" -> output.append('\u201D')
                "bullet" -> output.append('\u2022')
                "bin" -> if (number != null && number > 0) index = minOf(bytes.size, index + number)
                else -> Unit
            }
        }

        private fun hexEscape() {
            if (index + 2 > bytes.size) {
                index = bytes.size
                return
            }
            val hex = String(bytes, index, 2, Charsets.US_ASCII)
            index += 2
            if (fallbackToSkip > 0) {
                fallbackToSkip--
                return
            }
            val value = hex.toIntOrNull(16) ?: return
            output.append(String(byteArrayOf(value.toByte()), charset))
        }

        private fun symbol(value: Int) {
            if (fallbackToSkip > 0) {
                fallbackToSkip--
                return
            }
            when (value.toChar()) {
                '~' -> output.append('\u00A0')
                '_' -> output.append('\u2011')
                '\\', '{', '}' -> output.append(value.toChar())
                else -> Unit
            }
        }

        private fun literal(value: Int) {
            if (fallbackToSkip > 0) {
                fallbackToSkip--
                return
            }
            if (value == '\r'.code || value == '\n'.code) return
            if (value < 0x80) {
                output.append(value.toChar())
            } else {
                output.append(String(byteArrayOf(value.toByte()), charset))
            }
        }

        private fun applyCodePage(value: Int) {
            charset =
                if (value == 65001) {
                    Charsets.UTF_8
                } else {
                    runCatching { Charset.forName("windows-$value") }.getOrDefault(DEFAULT_CHARSET)
                }
        }

        private fun skipGroup() {
            var depth = 1
            while (depth > 0 && index < bytes.size) {
                when (byteAt(index)) {
                    '{'.code -> {
                        depth++
                        index++
                    }

                    '}'.code -> {
                        depth--
                        index++
                    }

                    '\\'.code -> {
                        index++
                        skipControl()
                    }

                    else -> index++
                }
            }
        }

        private fun skipControl() {
            val value = byteAt(index)
            if (value < 0) return
            index++
            if (value == '\''.code) {
                index = minOf(bytes.size, index + 2)
                return
            }
            if (!letter(value)) return
            val wordStart = index
            while (letter(byteAt(index))) index++
            val word = String(bytes, wordStart, index - wordStart, Charsets.US_ASCII).lowercase(Locale.ROOT)
            val number = number()
            if (byteAt(index) == ' '.code) index++
            if (word == "bin" && number != null && number > 0) index = minOf(bytes.size, index + number)
        }

        private fun byteAt(position: Int): Int = if (position in bytes.indices) bytes[position].toInt() and 0xFF else -1

        private fun letter(value: Int): Boolean = value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code
    }
}
