package dev.pocketshare.server

import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.floor

/**
 * Best-effort text extraction for Office 97-2003 binary files. The formats are old, so this reader
 * focuses on the main text stream and leaves layout, images, and macros behind.
 */
internal object LegacyDocumentText {
    private const val MAX_TEXT = 400_000
    private const val MAX_CELLS = 50_000
    private const val MAX_SHEET_COLUMNS = 200
    private const val MAX_SST_STRINGS = 100_000
    private const val MAX_SST_STRING_CHARS = 4_096
    private const val WINDOWS_1252 = "windows-1252"

    fun word(bytes: ByteArray): String? {
        val file = CompoundFile.open(bytes) ?: return null
        val document = file.stream("WordDocument") ?: return null
        if (document.size < 0x200 || u16At(document, 0) != 0xA5EC) return null
        val flags = u16At(document, 0x0A)
        if (flags and 0x0100 != 0) return null
        val table =
            file.stream(if (flags and 0x0200 != 0) "1Table" else "0Table")
                ?: file.stream("0Table")
                ?: file.stream("1Table")
                ?: return null
        val textLength = i32At(document, 0x004C)
        val clxStart = i32At(document, 0x01A2)
        val clxLength = i32At(document, 0x01A6)
        if (textLength <= 0 || clxStart < 0 || clxLength < 0) return null
        if (clxStart.toLong() + clxLength > table.size) return null
        return pieces(document, table, clxStart, clxLength, textLength)?.let(::cleanWord)
    }

    fun sheet(bytes: ByteArray): String? {
        val file = CompoundFile.open(bytes) ?: return null
        val workbook = file.stream("Workbook", "Book") ?: return null
        if (workbook.size < 4 || u16At(workbook, 0) != 0x0809) return null
        return parseWorkbook(workbook)
    }

    fun slides(bytes: ByteArray): String? {
        val file = CompoundFile.open(bytes) ?: return null
        val document = file.stream("PowerPoint Document") ?: return null
        return PptReader(document).read()
    }

    private fun pieces(
        document: ByteArray,
        table: ByteArray,
        start: Int,
        length: Int,
        textLength: Int,
    ): String? {
        var position = start
        val end = start + length
        val builder = StringBuilder()
        while (position + 1 <= end) {
            when (table[position].toInt() and 0xFF) {
                1 -> {
                    if (position + 3 > end) break
                    val skip = u16At(table, position + 1)
                    if (skip < 0) break
                    position += 3 + skip
                }

                2 -> {
                    if (position + 5 > end) break
                    val plcLength = i32At(table, position + 1)
                    if (plcLength < 16 || position.toLong() + 5 + plcLength > end) break
                    val count = (plcLength - 4) / 12
                    if (count <= 0) break
                    val cpsStart = position + 5
                    val pcdStart = cpsStart + (count + 1) * 4
                    for (index in 0 until count) {
                        val cpStart = i32At(table, cpsStart + index * 4).coerceAtLeast(0)
                        val cpEnd = i32At(table, cpsStart + (index + 1) * 4)
                        val from = maxOf(cpStart, 0)
                        val to = minOf(cpEnd, textLength)
                        if (to <= from) continue
                        val rawFc = i32At(table, pcdStart + index * 8 + 2)
                        val offset = rawFc and 0x3FFFFFFF
                        if (rawFc and 0x40000000 != 0) {
                            builder.append(
                                decode(document, offset / 2 + (from - cpStart), to - from, compressed = true),
                            )
                        } else {
                            builder.append(
                                decode(document, offset + (from - cpStart) * 2, (to - from) * 2, compressed = false),
                            )
                        }
                    }
                    return builder.toString()
                }

                else -> position++
            }
        }
        return null
    }

    private fun decode(
        data: ByteArray,
        offset: Int,
        length: Int,
        compressed: Boolean,
    ): String {
        if (offset < 0 || length <= 0) return ""
        val end = minOf(data.size.toLong(), offset.toLong() + length).toInt()
        if (end <= offset) return ""
        return if (compressed) {
            String(data, offset, end - offset, Charset.forName(WINDOWS_1252))
        } else {
            String(data, offset, (end - offset) and -2, Charsets.UTF_16LE)
        }
    }

    private fun cleanWord(text: String): String {
        val builder = StringBuilder(text.length)
        for (character in text) {
            when (character) {
                '\r', '\u000B', '\u000C' -> builder.append('\n')
                '\u0007' -> builder.append('\t')
                '\u0013', '\u0014', '\u0015', '\u0001', '\u0002', '\u0005', '\u0008', '\u001F' -> Unit
                '\u001E' -> builder.append('-')
                else -> if (character == '\n' || character == '\t' || character >= ' ') builder.append(character)
            }
        }
        return builder.toString()
    }

    private fun parseWorkbook(data: ByteArray): String? {
        var position = 0
        var depth = 0
        var sheet: SheetBuilder? = null
        var sheetIndex = 0
        var sharedStrings: List<String> = emptyList()
        val sheetNames = ArrayList<String>()
        val sheets = ArrayList<SheetBuilder>()
        while (position + 4 <= data.size) {
            val type = u16At(data, position)
            val length = u16At(data, position + 2)
            if (length < 0 || position.toLong() + 4 + length > data.size) break
            val body = position + 4
            when (type) {
                0x0809 -> {
                    depth++
                    if (depth == 1 && u16At(data, body + 2) == 0x0010) {
                        sheet = SheetBuilder(sheetNames.getOrNull(sheetIndex) ?: "Sheet ${sheetIndex + 1}")
                        sheetIndex++
                    }
                }

                0x000A -> {
                    if (depth == 1) {
                        sheet?.let { if (!it.isEmpty()) sheets.add(it) }
                        sheet = null
                    }
                    if (depth > 0) depth--
                }

                0x00FC -> {
                    val result = readSst(data, position)
                    sharedStrings = result.first
                    position = result.second
                    continue
                }

                0x0085 -> {
                    if (depth == 1 && sheet == null) {
                        boundsheetName(data, body, length)?.let(sheetNames::add)
                    }
                }

                else -> {
                    if (depth == 1 && sheet != null) collectCell(type, data, body, length, sheet, sharedStrings)
                }
            }
            position += 4 + length
        }
        val rendered =
            sheets
                .map(::renderSheet)
                .filter { it.isNotEmpty() }
        if (rendered.isEmpty()) return null
        return rendered.joinToString("\n\n").take(MAX_TEXT)
    }

    private fun collectCell(
        type: Int,
        data: ByteArray,
        body: Int,
        length: Int,
        sheet: SheetBuilder,
        sharedStrings: List<String>,
    ) {
        if (length < 6) return
        val row = u16At(data, body)
        val column = u16At(data, body + 2)
        if (row < 0 || column < 0) return
        when (type) {
            0x00FD -> {
                if (length < 10) return
                val index = i32At(data, body + 6)
                sharedStrings.getOrNull(index)?.let { sheet.put(row, column, it) }
            }

            0x0204 -> {
                labelText(data, body + 6, body + length)?.let { sheet.put(row, column, it) }
            }

            0x0203 -> {
                if (length < 14) return
                sheet.put(row, column, numberText(Double.fromBits(i64At(data, body + 6))))
            }

            0x027E -> {
                if (length < 10) return
                sheet.put(row, column, numberText(decodeRk(i32At(data, body + 6))))
            }

            0x00BD -> {
                if (length < 12) return
                val count = (length - 6) / 6
                for (index in 0 until count) {
                    val rk = i32At(data, body + 4 + index * 6 + 2)
                    sheet.put(row, column + index, numberText(decodeRk(rk)))
                }
            }

            0x0006 -> {
                if (length < 14) return
                val bits = i64At(data, body + 6)
                if (bits ushr 48 == 0xFFFFL) return
                sheet.put(row, column, numberText(Double.fromBits(bits)))
            }

            0x0205 -> {
                if (length < 8 || data[body + 7].toInt() != 0) return
                sheet.put(row, column, if (data[body + 6].toInt() == 0) "FALSE" else "TRUE")
            }
        }
    }

    private fun decodeRk(value: Int): Double {
        val divided = value and 1 != 0
        val number =
            if (value and 2 != 0) {
                (value shr 2).toDouble()
            } else {
                Double.fromBits((value.toLong() and 0xFFFFFFFCL) shl 32)
            }
        return if (divided) number / 100.0 else number
    }

    private fun numberText(value: Double): String =
        when {
            !value.isFinite() -> ""
            value == floor(value) && abs(value) < 1e15 -> value.toLong().toString()
            else -> value.toString()
        }

    private fun labelText(
        data: ByteArray,
        start: Int,
        end: Int,
    ): String? {
        if (start + 3 > end) return null
        val characters = u16At(data, start)
        val flags = data[start + 2].toInt() and 0xFF
        val highByte = flags and 1 != 0
        val byteCount = if (highByte) characters * 2 else characters
        if (characters < 0 || start + 3L + byteCount > end) return null
        return if (highByte) {
            String(data, start + 3, byteCount, Charsets.UTF_16LE)
        } else {
            String(data, start + 3, byteCount, Charsets.ISO_8859_1)
        }
    }

    private fun boundsheetName(
        data: ByteArray,
        body: Int,
        length: Int,
    ): String? {
        if (length < 8) return null
        val characters = data[body + 6].toInt() and 0xFF
        val flags = data[body + 7].toInt() and 0xFF
        val highByte = flags and 1 != 0
        val byteCount = if (highByte) characters * 2 else characters
        if (body + 8L + byteCount > body + length) return null
        return if (highByte) {
            String(data, body + 8, byteCount, Charsets.UTF_16LE)
        } else {
            String(data, body + 8, byteCount, Charsets.ISO_8859_1)
        }
    }

    private fun readSst(
        data: ByteArray,
        record: Int,
    ): Pair<List<String>, Int> {
        val length = u16At(data, record + 2)
        if (length < 8) return emptyList<String>() to (record + 4 + length)
        val chunks = ArrayList<ByteArray>()
        chunks.add(data.copyOfRange(record + 4, record + 4 + length))
        var next = record + 4 + length
        while (next + 4 <= data.size && u16At(data, next) == 0x003C) {
            val continuation = u16At(data, next + 2)
            if (continuation < 0 || next.toLong() + 4 + continuation > data.size) break
            chunks.add(data.copyOfRange(next + 4, next + 4 + continuation))
            next += 4 + continuation
        }
        return parseSst(chunks) to next
    }

    private fun parseSst(chunks: List<ByteArray>): List<String> {
        val reader = ChunkReader(chunks)
        val total = reader.u32()
        val unique = reader.u32()
        if (total < 0 || unique < 0) return emptyList()
        val count = minOf(unique, MAX_SST_STRINGS.toLong()).toInt()
        val strings = ArrayList<String>(minOf(count, 1_024))
        repeat(count) {
            val characters = reader.u16()
            if (characters < 0) return strings
            val flags = reader.u8()
            if (flags < 0) return strings
            var highByte = flags and 1 != 0
            val rich = flags and 8 != 0
            val extended = flags and 4 != 0
            val runs = if (rich) reader.u16() else 0
            val extendedSize = if (extended) reader.u32() else 0
            if (runs < 0 || extendedSize < 0) return strings
            val builder = StringBuilder(minOf(characters, 256))
            for (index in 0 until characters) {
                if (reader.atChunkEnd()) {
                    if (!reader.nextChunk()) break
                    val continuation = reader.u8()
                    if (continuation < 0) break
                    highByte = continuation and 1 != 0
                }
                val character = reader.character(highByte) ?: break
                if (builder.length < MAX_SST_STRING_CHARS) builder.append(character)
            }
            reader.skip(runs.toLong() * 4 + extendedSize)
            strings.add(builder.toString())
        }
        return strings
    }

    private fun renderSheet(sheet: SheetBuilder): String {
        if (sheet.isEmpty()) return ""
        val builder = StringBuilder()
        builder.append(sheet.name).append('\n')
        for ((_, cells) in sheet.rows) {
            if (builder.length >= MAX_TEXT) break
            val last = cells.keys.maxOrNull() ?: continue
            val lastColumn = minOf(last, MAX_SHEET_COLUMNS)
            for (column in 0..lastColumn) {
                if (column > 0) builder.append('\t')
                builder.append(cells[column].orEmpty())
            }
            builder.append('\n')
        }
        return builder.toString().trimEnd()
    }

    private class SheetBuilder(
        val name: String,
    ) {
        val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        private var cells = 0

        fun put(
            row: Int,
            column: Int,
            value: String,
        ) {
            if (cells >= MAX_CELLS || row < 0 || column < 0 || value.isEmpty()) return
            val text = value.take(MAX_CELL_CHARACTERS)
            val line = rows.getOrPut(row) { sortedMapOf() }
            if (line.put(column, text) == null) cells++
        }

        fun isEmpty(): Boolean = cells == 0
    }

    private class ChunkReader(
        private val chunks: List<ByteArray>,
    ) {
        private var chunk = 0
        private var offset = 0

        fun atChunkEnd(): Boolean {
            while (chunk < chunks.size && offset >= chunks[chunk].size) {
                chunk++
                offset = 0
            }
            return chunk >= chunks.size
        }

        fun nextChunk(): Boolean {
            chunk++
            offset = 0
            return chunk < chunks.size
        }

        fun u8(): Int {
            if (atChunkEnd()) return -1
            return chunks[chunk][offset++].toInt() and 0xFF
        }

        fun u16(): Int {
            val low = u8()
            val high = u8()
            if (low < 0 || high < 0) return -1
            return low or (high shl 8)
        }

        fun u32(): Long {
            val first = u8()
            val second = u8()
            val third = u8()
            val fourth = u8()
            if (first < 0 || second < 0 || third < 0 || fourth < 0) return -1
            return first.toLong() or
                (second.toLong() shl 8) or
                (third.toLong() shl 16) or
                (fourth.toLong() shl 24)
        }

        fun skip(count: Long) {
            var remaining = count
            while (remaining > 0) {
                if (atChunkEnd()) return
                val available = (chunks[chunk].size - offset).toLong()
                val take = minOf(available, remaining).toInt()
                offset += take
                remaining -= take
            }
        }

        fun character(highByte: Boolean): Char? {
            if (atChunkEnd()) return null
            if (!highByte) return (chunks[chunk][offset++].toInt() and 0xFF).toChar()
            if (offset + 2 > chunks[chunk].size) return null
            val value =
                (chunks[chunk][offset].toInt() and 0xFF) or
                    ((chunks[chunk][offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            return value.toChar()
        }
    }

    private class PptReader(
        private val data: ByteArray,
    ) {
        private val output = StringBuilder()
        private var slides = 0

        fun read(): String? {
            walk(0, data.size, 0)
            if (slides == 0) return null
            return output.toString().trim().take(MAX_TEXT)
        }

        private fun walk(
            start: Int,
            end: Int,
            depth: Int,
        ) {
            var position = start
            var records = 0
            while (position + 8 <= end && records++ < MAX_RECORDS) {
                val version = u16At(data, position)
                val type = u16At(data, position + 2)
                val length = i32At(data, position + 4)
                if (length < 0 || position.toLong() + 8 + length > end) break
                val body = position + 8
                val bodyEnd = body + length
                if (version and 0xF == 0xF) {
                    if (type == SLIDE) {
                        slides++
                        val text = slideText(body, bodyEnd, 0)
                        if (text.isNotBlank()) output.append(text.trim()).append("\n\n")
                    } else if (depth < MAX_DEPTH) {
                        walk(body, bodyEnd, depth + 1)
                    }
                }
                position = bodyEnd
            }
        }

        private fun slideText(
            start: Int,
            end: Int,
            depth: Int,
        ): String {
            if (depth > MAX_DEPTH) return ""
            val builder = StringBuilder()
            var position = start
            var records = 0
            while (position + 8 <= end && records++ < MAX_RECORDS) {
                val version = u16At(data, position)
                val type = u16At(data, position + 2)
                val length = i32At(data, position + 4)
                if (length < 0 || position.toLong() + 8 + length > end) break
                val body = position + 8
                val bodyEnd = body + length
                if (version and 0xF == 0xF) {
                    val nested = slideText(body, bodyEnd, depth + 1)
                    if (nested.isNotBlank()) {
                        if (builder.isNotEmpty()) builder.append('\n')
                        builder.append(nested)
                    }
                } else {
                    textAtom(type, body, length)?.let {
                        if (builder.isNotEmpty()) builder.append('\n')
                        builder.append(it)
                    }
                }
                position = bodyEnd
            }
            return builder.toString()
        }

        private fun textAtom(
            type: Int,
            body: Int,
            length: Int,
        ): String? {
            if (type != TEXT_CHARS && type != TEXT_BYTES) return null
            if (length <= 0) return null
            val text =
                if (type == TEXT_CHARS) {
                    String(data, body, length and -2, Charsets.UTF_16LE)
                } else {
                    String(data, body, length, Charset.forName(WINDOWS_1252))
                }
            val cleaned =
                text
                    .replace('\r', '\n')
                    .replace('\u000B', '\n')
                    .replace("\u0000", "")
                    .trim()
            return cleaned.takeIf { it.isNotEmpty() && it != "*" }
        }

        companion object {
            private const val SLIDE = 0x03EE
            private const val TEXT_CHARS = 0x0FA0
            private const val TEXT_BYTES = 0x0FA8
            private const val MAX_DEPTH = 12
            private const val MAX_RECORDS = 100_000
        }
    }
}

private const val MAX_CELL_CHARACTERS = 2_000

private fun u16At(
    data: ByteArray,
    offset: Int,
): Int {
    if (offset < 0 || offset + 2 > data.size) return -1
    return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}

private fun i32At(
    data: ByteArray,
    offset: Int,
): Int {
    if (offset < 0 || offset + 4 > data.size) return -1
    return (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)
}

private fun i64At(
    data: ByteArray,
    offset: Int,
): Long {
    if (offset < 0 || offset + 8 > data.size) return -1
    return (data[offset].toLong() and 0xFF) or
        ((data[offset + 1].toLong() and 0xFF) shl 8) or
        ((data[offset + 2].toLong() and 0xFF) shl 16) or
        ((data[offset + 3].toLong() and 0xFF) shl 24) or
        ((data[offset + 4].toLong() and 0xFF) shl 32) or
        ((data[offset + 5].toLong() and 0xFF) shl 40) or
        ((data[offset + 6].toLong() and 0xFF) shl 48) or
        ((data[offset + 7].toLong() and 0xFF) shl 56)
}
