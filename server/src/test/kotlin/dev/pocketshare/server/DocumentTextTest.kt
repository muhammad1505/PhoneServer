package dev.pocketshare.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentTextTest {
    private val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private val pptxMime = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private val odtMime = "application/vnd.oasis.opendocument.text"

    @Test
    fun `docx paragraphs keep their order and skip deleted text`() {
        val bytes =
            zip(
                "word/document.xml" to
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>Halo dunia</w:t></w:r></w:p>
                        <w:p>
                          <w:r><w:t>Baris kedua</w:t></w:r>
                          <w:del><w:r><w:delText>rahasia</w:delText></w:r></w:del>
                        </w:p>
                      </w:body>
                    </w:document>
                    """.trimIndent(),
            )

        assertEquals("Halo dunia\nBaris kedua", DocumentText.extract(docxMime, "catatan.docx", bytes))
    }

    @Test
    fun `xlsx cells use shared strings inline strings and raw values`() {
        val bytes =
            zip(
                "xl/sharedStrings.xml" to
                    """
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <si><t>Nama</t></si>
                      <si><t>Nilai</t></si>
                    </sst>
                    """.trimIndent(),
                "xl/worksheets/sheet1.xml" to
                    """
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <sheetData>
                        <row r="1">
                          <c r="A1" t="s"><v>0</v></c>
                          <c r="B1" t="s"><v>1</v></c>
                        </row>
                        <row r="2">
                          <c r="A2"><v>42</v></c>
                          <c r="B2" t="inlineStr"><is><t>teks</t></is></c>
                        </row>
                      </sheetData>
                    </worksheet>
                    """.trimIndent(),
            )

        assertEquals("Nama\tNilai\n42\tteks", DocumentText.extract(xlsxMime, "data.xlsx", bytes))
    }

    @Test
    fun `pptx slides are ordered by number`() {
        val bytes =
            zip(
                "ppt/slides/slide10.xml" to slide("Slide sepuluh"),
                "ppt/slides/slide2.xml" to slide("Slide dua"),
            )

        assertEquals("Slide dua\n\nSlide sepuluh", DocumentText.extract(pptxMime, "deck.pptx", bytes))
    }

    @Test
    fun `odt text keeps headings paragraphs and table cells`() {
        val bytes =
            zip(
                "content.xml" to
                    """
                    <office:document-content
                        xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                        xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                        xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">
                      <office:body>
                        <office:text>
                          <text:h>Judul</text:h>
                          <text:p>Isi paragraf</text:p>
                          <table:table>
                            <table:table-row>
                              <table:table-cell><text:p>A</text:p></table:table-cell>
                              <table:table-cell><text:p>B</text:p></table:table-cell>
                            </table:table-row>
                          </table:table>
                        </office:text>
                      </office:body>
                    </office:document-content>
                    """.trimIndent(),
            )

        assertEquals("Judul\nIsi paragraf\nA\tB", DocumentText.extract(odtMime, "surat.odt", bytes))
    }

    @Test
    fun `unsupported and corrupt documents are rejected`() {
        assertFalse(DocumentText.isSupported("application/x-abiword", "lama.abw"))
        assertNull(DocumentText.extract("application/x-abiword", "lama.abw", ByteArray(8)))
        assertNull(DocumentText.extract(docxMime, "rusak.docx", "bukan zip".toByteArray()))
    }

    @Test
    fun `document support is detected from either mime or extension`() {
        assertTrue(DocumentText.isSupported(docxMime, "catatan"))
        assertTrue(DocumentText.isSupported("application/octet-stream", "LAPORAN.DOCX"))
        assertTrue(DocumentText.isSupported("application/vnd.oasis.opendocument.text", "surat"))
        assertTrue(DocumentText.isSupported("application/msword", "lama"))
        assertTrue(DocumentText.isSupported("application/vnd.ms-excel", "lama"))
        assertTrue(DocumentText.isSupported("application/octet-stream", "LAMA.PPT"))
        assertFalse(DocumentText.isSupported("application/pdf", "surat.pdf"))
    }

    private fun slide(text: String): String =
        """
        <p:sld
            xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
          <p:cSld>
            <p:spTree>
              <p:sp>
                <p:txBody>
                  <a:p><a:r><a:t>$text</a:t></a:r></a:p>
                </p:txBody>
              </p:sp>
            </p:spTree>
          </p:cSld>
        </p:sld>
        """.trimIndent()

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
