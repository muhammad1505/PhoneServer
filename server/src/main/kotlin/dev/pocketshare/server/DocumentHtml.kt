package dev.pocketshare.server

import dev.pocketshare.core.http.FileNames
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Builds the structured part of a document preview. Text is always escaped here, and the
 * caller only uses the result when it contains a heading, a table, or slide layout.
 */
internal object DocumentHtml {
    private const val MAX_HTML_CHARS = 500_000
    private const val MAX_TABLE_ROWS = 200
    private const val MAX_TABLE_COLUMNS = 30
    private const val MAX_SLIDES = 100
    private const val MAX_CELL_CHARS = 500

    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"

    private val SHEET_ENTRY = Regex("xl/worksheets/sheet\\d+\\.xml")
    private val SLIDE_ENTRY = Regex("ppt/slides/slide\\d+\\.xml")
    private val ENTRY_NUMBER = Regex("(\\d+)\\.xml$")
    private val HEADING_STYLE = Regex("(?i)^heading\\s*([1-9])$")

    fun render(
        mime: String,
        name: String,
        bytes: ByteArray,
    ): String? =
        when (DocumentText.formatOf(mime, name)) {
            DocumentFormat.WORD -> word(bytes)
            DocumentFormat.SHEET -> sheet(bytes)
            DocumentFormat.SLIDES -> slides(bytes)
            DocumentFormat.ODF_TEXT -> odfText(bytes)
            DocumentFormat.ODF_SHEET -> odfSheet(bytes)
            else -> null
        }

    private fun word(bytes: ByteArray): String? {
        val entry =
            DocumentText.readEntries(bytes) { it == "word/document.xml" }["word/document.xml"]
                ?: return null
        val document = DocumentText.parse(entry) ?: return null
        val body = descendants(document.documentElement, "body").firstOrNull() ?: return null
        val html = StringBuilder()
        var structured = false
        for (element in childrenOf(body)) {
            when (element.localName) {
                "p" -> {
                    val text = wordText(element).trim()
                    if (text.isEmpty()) continue
                    val level = headingLevel(element)
                    if (level == null) {
                        html.append("<p>").append(paragraphHtml(text)).append("</p>")
                    } else {
                        structured = true
                        html.append("<h").append(level).append('>')
                        html.append(FileNames.escapeHtml(text))
                        html.append("</h").append(level).append('>')
                    }
                }

                "tbl" -> {
                    wordTable(element)?.let {
                        html.append(it)
                        structured = true
                    }
                }
            }
        }
        return html.takeIf { structured && it.length in 1..MAX_HTML_CHARS }?.toString()
    }

    private fun headingLevel(paragraph: Element): Int? {
        val properties = child(paragraph, "pPr") ?: return null
        val style = child(properties, "pStyle")
        val styleName =
            style?.getAttributeNS(WORD_NS, "val")?.takeIf { it.isNotBlank() }
                ?: style?.getAttribute("w:val")
        val match = HEADING_STYLE.find(styleName.orEmpty())
        if (match != null) {
            val number = match.groupValues[1].toIntOrNull() ?: 1
            return (number + 2).coerceIn(3, 6)
        }
        val outline =
            child(properties, "outlineLvl")?.getAttributeNS(WORD_NS, "val")?.toIntOrNull()
                ?: child(properties, "outlineLvl")?.getAttribute("w:val")?.toIntOrNull()
        return outline?.takeIf { it in 0..5 }?.let { (it + 3).coerceIn(3, 6) }
    }

    private fun wordTable(table: Element): String? {
        val rows = descendants(table, "tr").take(MAX_TABLE_ROWS)
        if (rows.isEmpty()) return null
        val html = StringBuilder("<div class=\"doc-table-wrap\"><table><tbody>")
        rows.forEachIndexed { rowIndex, row ->
            html.append("<tr>")
            val cells = descendants(row, "tc").take(MAX_TABLE_COLUMNS)
            for (cell in cells) {
                val tag = if (rowIndex == 0) "th" else "td"
                html.append('<').append(tag).append('>')
                html.append(paragraphHtml(wordText(cell).trim().take(MAX_CELL_CHARS)))
                html.append("</").append(tag).append('>')
            }
            html.append("</tr>")
        }
        html.append("</tbody></table></div>")
        return html.toString()
    }

    private fun sheet(bytes: ByteArray): String? {
        val entries =
            DocumentText.readEntries(bytes) {
                it == "xl/sharedStrings.xml" || it == "xl/workbook.xml" || SHEET_ENTRY.matches(it)
            }
        val shared =
            entries["xl/sharedStrings.xml"]
                ?.let(DocumentText::parse)
                ?.let(DocumentText::sharedStrings)
                .orEmpty()
        val names =
            entries["xl/workbook.xml"]
                ?.let(DocumentText::parse)
                ?.let(::sheetNames)
                .orEmpty()
        val sheets = entries.keys.filter(SHEET_ENTRY::matches).sortedBy(::entryNumber)
        if (sheets.isEmpty()) return null
        val html = StringBuilder()
        sheets.forEachIndexed { index, name ->
            val document = DocumentText.parse(entries.getValue(name)) ?: return@forEachIndexed
            val table = xlsxTable(document, shared) ?: return@forEachIndexed
            html.append("<section class=\"doc-sheet\"><h3>")
            html.append(FileNames.escapeHtml(names.getOrNull(index) ?: "Sheet ${index + 1}"))
            html.append("</h3>").append(table).append("</section>")
        }
        return html.takeIf { it.length in 1..MAX_HTML_CHARS }?.toString()
    }

    private fun sheetNames(document: Document): List<String> =
        descendants(document.documentElement, "sheet").mapNotNull { element ->
            element.getAttribute("name").takeIf { it.isNotBlank() }
        }

    private fun xlsxTable(
        document: Document,
        shared: List<String>,
    ): String? {
        val rows = descendants(document.documentElement, "row").take(MAX_TABLE_ROWS)
        if (rows.isEmpty()) return null
        val html = StringBuilder("<div class=\"doc-table-wrap\"><table><tbody>")
        rows.forEachIndexed { rowIndex, row ->
            val values = sortedMapOf<Int, String>()
            descendants(row, "c").forEachIndexed { index, cell ->
                val column = xlsxColumn(cell).takeIf { it >= 0 } ?: index
                if (column in 0 until MAX_TABLE_COLUMNS) {
                    values[column] = DocumentText.cellValue(cell, shared).take(MAX_CELL_CHARS)
                }
            }
            if (values.isNotEmpty()) {
                html.append("<tr>")
                val last = minOf(values.keys.maxOrNull() ?: 0, MAX_TABLE_COLUMNS - 1)
                for (column in 0..last) {
                    val tag = if (rowIndex == 0) "th" else "td"
                    html.append('<').append(tag).append('>')
                    html.append(FileNames.escapeHtml(values[column].orEmpty()))
                    html.append("</").append(tag).append('>')
                }
                html.append("</tr>")
            }
        }
        html.append("</tbody></table></div>")
        return html.toString()
    }

    private fun xlsxColumn(cell: Element): Int {
        val reference = cell.getAttribute("r")
        var value = 0
        for (character in reference) {
            when {
                character in 'A'..'Z' -> value = value * 26 + (character - 'A' + 1)
                character in 'a'..'z' -> value = value * 26 + (character - 'a' + 1)
                character.isDigit() -> break
            }
        }
        return value - 1
    }

    private fun slides(bytes: ByteArray): String? {
        val entries = DocumentText.readEntries(bytes) { SLIDE_ENTRY.matches(it) }
        val ordered =
            entries.keys
                .filter(SLIDE_ENTRY::matches)
                .sortedBy(::entryNumber)
                .take(MAX_SLIDES)
        if (ordered.isEmpty()) return null
        val html = StringBuilder()
        ordered.forEachIndexed { index, name ->
            val document = DocumentText.parse(entries.getValue(name)) ?: return@forEachIndexed
            val paragraphs =
                descendants(document.documentElement, "p")
                    .map { wordText(it).trim() }
                    .filter { it.isNotEmpty() }
            if (paragraphs.isEmpty()) return@forEachIndexed
            html.append("<section class=\"doc-slide\"><span class=\"doc-slide-number\">")
            html.append(index + 1).append("</span>")
            html.append("<h3>").append(FileNames.escapeHtml(paragraphs.first())).append("</h3>")
            for (paragraph in paragraphs.drop(1)) {
                html.append("<p>").append(paragraphHtml(paragraph)).append("</p>")
            }
            html.append("</section>")
        }
        return html.takeIf { it.length in 1..MAX_HTML_CHARS }?.toString()
    }

    private fun odfText(bytes: ByteArray): String? {
        val entry = DocumentText.readEntries(bytes) { it == "content.xml" }["content.xml"] ?: return null
        val document = DocumentText.parse(entry) ?: return null
        val body = descendants(document.documentElement, "text").firstOrNull() ?: return null
        val html = StringBuilder()
        var structured = false
        for (element in childrenOf(body)) {
            when (element.localName) {
                "h" -> {
                    val text = odfTextOf(element).trim()
                    if (text.isEmpty()) continue
                    val level = odfHeadingLevel(element)
                    html.append("<h").append(level).append('>')
                    html.append(FileNames.escapeHtml(text))
                    html.append("</h").append(level).append('>')
                    structured = true
                }

                "p" -> {
                    val text = odfTextOf(element).trim()
                    if (text.isNotEmpty()) html.append("<p>").append(paragraphHtml(text)).append("</p>")
                }

                "table" -> {
                    odfTable(element)?.let {
                        html.append(it)
                        structured = true
                    }
                }
            }
        }
        return html.takeIf { structured && it.length in 1..MAX_HTML_CHARS }?.toString()
    }

    private fun odfHeadingLevel(element: Element): Int {
        val level =
            element.getAttributeNS(TEXT_NS, "outline-level").toIntOrNull()
                ?: element.getAttribute("text:outline-level").toIntOrNull()
                ?: 1
        return (level + 2).coerceIn(3, 6)
    }

    private fun odfTable(table: Element): String? {
        val rows = descendants(table, "table-row").take(MAX_TABLE_ROWS)
        if (rows.isEmpty()) return null
        val html = StringBuilder("<div class=\"doc-table-wrap\"><table><tbody>")
        rows.forEachIndexed { rowIndex, row ->
            html.append("<tr>")
            var column = 0
            for (cell in descendants(row, "table-cell")) {
                if (column >= MAX_TABLE_COLUMNS) break
                val repeat =
                    (
                        cell.getAttributeNS(TABLE_NS, "number-columns-repeated").toIntOrNull()
                            ?: cell.getAttribute("table:number-columns-repeated").toIntOrNull()
                            ?: 1
                    ).coerceIn(1, MAX_TABLE_COLUMNS - column)
                val text = odfTextOf(cell).trim().take(MAX_CELL_CHARS)
                val tag = if (rowIndex == 0) "th" else "td"
                repeat(repeat) {
                    html
                        .append('<')
                        .append(tag)
                        .append('>')
                        .append(paragraphHtml(text))
                    html.append("</").append(tag).append('>')
                    column++
                }
            }
            html.append("</tr>")
        }
        html.append("</tbody></table></div>")
        return html.toString()
    }

    private fun odfSheet(bytes: ByteArray): String? {
        val entry = DocumentText.readEntries(bytes) { it == "content.xml" }["content.xml"] ?: return null
        val document = DocumentText.parse(entry) ?: return null
        val tables = descendants(document.documentElement, "table")
        if (tables.isEmpty()) return null
        val html = StringBuilder()
        tables.forEach { table ->
            val name =
                table.getAttributeNS(TABLE_NS, "name").takeIf { it.isNotBlank() }
                    ?: table.getAttribute("table:name")
            odfTable(table)?.let {
                html.append("<section class=\"doc-sheet\"><h3>")
                html.append(FileNames.escapeHtml(name.ifBlank { "Sheet" }))
                html.append("</h3>").append(it).append("</section>")
            }
        }
        return html.takeIf { it.length in 1..MAX_HTML_CHARS }?.toString()
    }

    private fun wordText(element: Element): String {
        val builder = StringBuilder()

        fun visit(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE -> builder.append(node.nodeValue)
                Node.ELEMENT_NODE -> {
                    val current = node as Element
                    when (current.localName) {
                        "delText" -> return
                        "tab" -> {
                            builder.append('\t')
                            return
                        }

                        "br", "cr" -> {
                            builder.append('\n')
                            return
                        }

                        "t" -> {
                            builder.append(current.textContent)
                            return
                        }
                    }
                    var child = current.firstChild
                    while (child != null) {
                        visit(child)
                        child = child.nextSibling
                    }
                    if (current.localName == "p") builder.append('\n')
                }
            }
        }
        visit(element)
        return builder.toString()
    }

    private fun odfTextOf(element: Element): String {
        val builder = StringBuilder()

        fun visit(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE -> builder.append(node.nodeValue)
                Node.ELEMENT_NODE -> {
                    val current = node as Element
                    when (current.localName) {
                        "s" -> {
                            val count =
                                (
                                    current.getAttributeNS(TEXT_NS, "c").toIntOrNull()
                                        ?: current.getAttribute("text:c").toIntOrNull()
                                        ?: 1
                                ).coerceIn(1, 100)
                            repeat(count) { builder.append(' ') }
                            return
                        }

                        "tab" -> {
                            builder.append('\t')
                            return
                        }

                        "line-break" -> {
                            builder.append('\n')
                            return
                        }
                    }
                    var child = current.firstChild
                    while (child != null) {
                        visit(child)
                        child = child.nextSibling
                    }
                    if (current.localName == "p" || current.localName == "h") builder.append('\n')
                }
            }
        }
        visit(element)
        return builder.toString()
    }

    private fun paragraphHtml(text: String): String = FileNames.escapeHtml(text).replace("\n", "<br>")

    private fun child(
        element: Element,
        name: String,
    ): Element? {
        var node = element.firstChild
        while (node != null) {
            if (node is Element && node.localName == name) return node
            node = node.nextSibling
        }
        return null
    }

    private fun childrenOf(element: Element): List<Element> {
        val nodes = element.childNodes
        val result = ArrayList<Element>(nodes.length)
        for (index in 0 until nodes.length) {
            (nodes.item(index) as? Element)?.let(result::add)
        }
        return result
    }

    private fun descendants(
        element: Element,
        name: String,
    ): List<Element> {
        val nodes = element.getElementsByTagNameNS("*", name)
        val result = ArrayList<Element>(minOf(nodes.length, 256))
        for (index in 0 until nodes.length) {
            (nodes.item(index) as? Element)?.let(result::add)
        }
        return result
    }

    private fun entryNumber(entry: String): Int =
        ENTRY_NUMBER
            .find(entry)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: 0
}
