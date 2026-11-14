package dev.pocketshare.server

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Formats whose text can be extracted for the public preview. */
internal enum class DocumentFormat {
    WORD,
    SHEET,
    SLIDES,
    ODF_TEXT,
    ODF_SHEET,
    RTF,
    LEGACY_WORD,
    LEGACY_SHEET,
    LEGACY_SLIDES,
}

/**
 * Extracts readable text from office documents so the public page can preview them.
 *
 * Covers ZIP/XML formats (OOXML and OpenDocument), RTF, and the Office 97-2003 binaries.
 * Every step is bounded, so a crafted archive cannot exhaust the phone.
 */
object DocumentText {
    private const val MAX_ARCHIVE_ENTRIES = 512
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    private const val MAX_CHARS = 400_000

    private const val WORD_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val SHEET_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private const val SLIDES_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private const val ODF_PREFIX = "application/vnd.oasis.opendocument"
    private const val WORD_BINARY_MIME = "application/msword"
    private const val SHEET_BINARY_MIME = "application/vnd.ms-excel"
    private const val SLIDES_BINARY_MIME = "application/vnd.ms-powerpoint"
    private val RTF_EXTENSIONS = setOf("rtf")
    private val RTF_MIMES = setOf("application/rtf", "text/rtf")
    private val WORD_BINARY_EXTENSIONS = setOf("doc")
    private val SHEET_BINARY_EXTENSIONS = setOf("xls")
    private val SLIDES_BINARY_EXTENSIONS = setOf("ppt")

    private val WORD_EXTENSIONS = setOf("docx", "docm")
    private val SHEET_EXTENSIONS = setOf("xlsx", "xlsm")
    private val SLIDES_EXTENSIONS = setOf("pptx", "pptm")
    private val ODF_EXTENSIONS = setOf("odt", "ods", "odp")
    private val EXTENSIONS =
        WORD_EXTENSIONS + SHEET_EXTENSIONS + SLIDES_EXTENSIONS + ODF_EXTENSIONS + RTF_EXTENSIONS +
            WORD_BINARY_EXTENSIONS + SHEET_BINARY_EXTENSIONS + SLIDES_BINARY_EXTENSIONS
    private val MIMES =
        setOf(
            WORD_MIME,
            SHEET_MIME,
            SLIDES_MIME,
            "$ODF_PREFIX.text",
            "$ODF_PREFIX.spreadsheet",
            "$ODF_PREFIX.presentation",
            WORD_BINARY_MIME,
            SHEET_BINARY_MIME,
            SLIDES_BINARY_MIME,
        ) + RTF_MIMES

    private val SHEET_ENTRY = Regex("xl/worksheets/sheet\\d+\\.xml")
    private val SLIDE_ENTRY = Regex("ppt/slides/slide\\d+\\.xml")
    private val ENTRY_NUMBER = Regex("(\\d+)\\.xml$")
    private val SPACES_BEFORE_NEWLINE = Regex("[ \\t]+\\n")
    private val EXCESS_NEWLINES = Regex("\\n{3,}")

    /** True when [name] or [mime] identifies a format this extractor understands. */
    fun isSupported(
        mime: String,
        name: String,
    ): Boolean = formatOf(mime, name) != null

    internal fun formatOf(
        mime: String,
        name: String,
    ): DocumentFormat? {
        val extension = extensionOf(name)
        val lowerMime = normalizedMime(mime)
        return when {
            extension in WORD_EXTENSIONS || lowerMime == WORD_MIME -> DocumentFormat.WORD
            extension in SHEET_EXTENSIONS || lowerMime == SHEET_MIME -> DocumentFormat.SHEET
            extension in SLIDES_EXTENSIONS || lowerMime == SLIDES_MIME -> DocumentFormat.SLIDES
            extension in ODF_EXTENSIONS || lowerMime.startsWith("$ODF_PREFIX.") ->
                if (extension == "ods" || lowerMime.endsWith(".spreadsheet")) {
                    DocumentFormat.ODF_SHEET
                } else {
                    DocumentFormat.ODF_TEXT
                }
            extension in WORD_BINARY_EXTENSIONS || lowerMime == WORD_BINARY_MIME -> DocumentFormat.LEGACY_WORD
            extension in SHEET_BINARY_EXTENSIONS || lowerMime == SHEET_BINARY_MIME -> DocumentFormat.LEGACY_SHEET
            extension in SLIDES_BINARY_EXTENSIONS || lowerMime == SLIDES_BINARY_MIME -> DocumentFormat.LEGACY_SLIDES
            extension in RTF_EXTENSIONS || lowerMime in RTF_MIMES -> DocumentFormat.RTF
            else -> null
        }
    }

    /** Returns the document text, or null when the file cannot be decoded. */
    fun extract(
        mime: String,
        name: String,
        bytes: ByteArray,
    ): String? {
        val text =
            when (formatOf(mime, name)) {
                DocumentFormat.WORD -> wordText(bytes)
                DocumentFormat.SHEET -> sheetText(bytes)
                DocumentFormat.SLIDES -> slidesText(bytes)
                DocumentFormat.ODF_TEXT, DocumentFormat.ODF_SHEET -> odfText(bytes)
                DocumentFormat.LEGACY_WORD -> LegacyDocumentText.word(bytes)
                DocumentFormat.LEGACY_SHEET -> LegacyDocumentText.sheet(bytes)
                DocumentFormat.LEGACY_SLIDES -> LegacyDocumentText.slides(bytes)
                DocumentFormat.RTF -> rtfText(bytes)
                null -> null
            }
        return text?.let(::normalize)?.takeIf { it.isNotEmpty() }?.let(::limit)
    }

    private fun wordText(bytes: ByteArray): String? =
        readEntries(bytes) { it == "word/document.xml" }["word/document.xml"]
            ?.let(::parse)
            ?.let { document ->
                val builder = StringBuilder()
                appendText(document.documentElement, builder, setOf("t"), setOf("p", "h"), emptySet())
                builder.toString()
            }

    private fun slidesText(bytes: ByteArray): String? {
        val entries = readEntries(bytes) { SLIDE_ENTRY.matches(it) }
        val slides = entries.keys.sortedBy(::entryNumber)
        if (slides.isEmpty()) return null
        return slides.joinToString("\n\n") { entry ->
            val document = parse(entries.getValue(entry)) ?: return@joinToString ""
            val builder = StringBuilder()
            appendText(document.documentElement, builder, setOf("t"), setOf("p"), emptySet())
            builder.toString().trim()
        }
    }

    private fun sheetText(bytes: ByteArray): String? {
        val entries = readEntries(bytes) { it == "xl/sharedStrings.xml" || SHEET_ENTRY.matches(it) }
        val shared = entries["xl/sharedStrings.xml"]?.let(::parse)?.let(::sharedStrings).orEmpty()
        val sheets = entries.keys.filter(SHEET_ENTRY::matches).sortedBy(::entryNumber)
        if (sheets.isEmpty()) return null
        return sheets.joinToString("\n") { entry ->
            val document = parse(entries.getValue(entry)) ?: return@joinToString ""
            val builder = StringBuilder()
            appendSheet(document, shared, builder)
            builder.toString()
        }
    }

    private fun rtfText(bytes: ByteArray): String? = RtfText.extract(bytes)

    private fun odfText(bytes: ByteArray): String? =
        readEntries(bytes) { it == "content.xml" }["content.xml"]
            ?.let(::parse)
            ?.let { document ->
                val builder = StringBuilder()
                appendText(
                    document.documentElement,
                    builder,
                    textTags = emptySet(),
                    lineTags = setOf("p", "h", "table-row"),
                    tabTags = setOf("table-cell"),
                )
                builder.toString()
            }

    internal fun sharedStrings(document: Document): List<String> {
        val nodes = document.getElementsByTagNameNS("*", "si")
        return (0 until nodes.length).map { index ->
            val element = nodes.item(index) as? Element ?: return@map ""
            collectText(element)
        }
    }

    private fun collectText(element: Element): String {
        val nodes = element.getElementsByTagNameNS("*", "t")
        val builder = StringBuilder()
        for (index in 0 until nodes.length) {
            builder.append(nodes.item(index).textContent)
        }
        return builder.toString()
    }

    private fun appendSheet(
        document: Document,
        shared: List<String>,
        builder: StringBuilder,
    ) {
        val rows = document.getElementsByTagNameNS("*", "row")
        for (rowIndex in 0 until rows.length) {
            val row = rows.item(rowIndex) as? Element ?: continue
            val cells = row.getElementsByTagNameNS("*", "c")
            val line = StringBuilder()
            for (cellIndex in 0 until cells.length) {
                val cell = cells.item(cellIndex) as? Element ?: continue
                if (line.isNotEmpty()) line.append('\t')
                line.append(cellValue(cell, shared))
            }
            builder.append(line.toString().trimEnd()).append('\n')
        }
    }

    internal fun cellValue(
        cell: Element,
        shared: List<String>,
    ): String {
        if (cell.getAttribute("t") == "inlineStr") return collectText(cell)
        val value = firstText(cell, "v").orEmpty()
        return if (cell.getAttribute("t") == "s") {
            value.toIntOrNull()?.let(shared::getOrNull).orEmpty()
        } else {
            value
        }
    }

    private fun firstText(
        element: Element,
        name: String,
    ): String? {
        val nodes = element.getElementsByTagNameNS("*", name)
        return (nodes.item(0) as? Element)?.textContent
    }

    private fun appendText(
        node: Node,
        builder: StringBuilder,
        textTags: Set<String>,
        lineTags: Set<String>,
        tabTags: Set<String>,
    ) {
        when (node.nodeType) {
            Node.TEXT_NODE -> {
                val value = node.nodeValue.orEmpty()
                val xmlFormatting = value.isBlank() && value.contains('\n')
                if (textTags.isEmpty() && !xmlFormatting) builder.append(value)
            }
            Node.ELEMENT_NODE -> appendElement(node as Element, builder, textTags, lineTags, tabTags)
        }
    }

    private fun appendElement(
        element: Element,
        builder: StringBuilder,
        textTags: Set<String>,
        lineTags: Set<String>,
        tabTags: Set<String>,
    ) {
        val name = element.localName ?: element.tagName
        when (name) {
            in textTags -> builder.append(element.textContent)
            "tab" -> builder.append('\t')
            "br", "cr" -> builder.append('\n')
            else -> {
                var child = element.firstChild
                while (child != null) {
                    appendText(child, builder, textTags, lineTags, tabTags)
                    child = child.nextSibling
                }
                val insideCell = name == "p" && (element.parentNode as? Element)?.localName == "table-cell"
                if (name in lineTags && !insideCell) builder.append('\n')
                if (name in tabTags) builder.append('\t')
            }
        }
    }

    internal fun readEntries(
        bytes: ByteArray,
        wanted: (String) -> Boolean,
    ): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var count = 0
                var total = 0
                while (count < MAX_ARCHIVE_ENTRIES && total < MAX_TOTAL_BYTES) {
                    val entry = zip.nextEntry ?: break
                    count += 1
                    if (!entry.isDirectory && wanted(entry.name)) {
                        val remaining = MAX_TOTAL_BYTES - total
                        readEntry(zip, minOf(MAX_ENTRY_BYTES, remaining))?.let {
                            entries[entry.name] = it
                            total += it.size
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        return entries
    }

    private fun readEntry(
        input: InputStream,
        limit: Int,
    ): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    internal fun parse(bytes: ByteArray): Document? =
        runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            // Android's parser does not implement XInclude and throws when the flag is set; the
            // whole parse must not fail because of an optional hardening switch.
            runCatching { factory.isExpandEntityReferences = false }
            runCatching { factory.isXIncludeAware = false }
            runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        }.getOrNull()

    private fun normalize(text: String): String =
        text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(SPACES_BEFORE_NEWLINE, "\n")
            .replace(EXCESS_NEWLINES, "\n\n")
            .trim()

    private fun limit(text: String): String = if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "\n\n…"

    private fun entryNumber(entry: String): Int =
        ENTRY_NUMBER
            .find(entry)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: 0

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun normalizedMime(mime: String): String = mime.lowercase(Locale.ROOT).substringBefore(';').trim()
}
