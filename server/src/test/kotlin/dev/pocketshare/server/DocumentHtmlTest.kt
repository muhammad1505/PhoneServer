package dev.pocketshare.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentHtmlTest {
    private val docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    private val pptxMime = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private val odtMime = "application/vnd.oasis.opendocument.text"

    @Test
    fun `docx headings and tables become semantic html and text stays escaped`() {
        val bytes =
            zip(
                "word/document.xml" to
                    """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Judul</w:t></w:r></w:p>
                        <w:p><w:r><w:t>Isi &lt;aman&gt;</w:t></w:r></w:p>
                        <w:p><w:r><w:t>&lt;script&gt;alert(1)&lt;/script&gt;</w:t></w:r></w:p>
                        <w:tbl>
                          <w:tr>
                            <w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc>
                            <w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc>
                          </w:tr>
                        </w:tbl>
                      </w:body>
                    </w:document>
                    """.trimIndent(),
            )

        val rendered = requireNotNull(DocumentHtml.render(docxMime, "catatan.docx", bytes))
        assertTrue(rendered.contains("<h3>Judul</h3>"))
        assertTrue(rendered.contains("<p>Isi &lt;aman&gt;</p>"))
        assertTrue(rendered.contains("<th>A</th>"))
        assertTrue(rendered.contains("<th>B</th>"))
        assertFalse(rendered.contains("<script>"))
    }

    @Test
    fun `plain docx stays on the preformatted path`() {
        val bytes =
            zip(
                "word/document.xml" to
                    """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>Halo</w:t></w:r></w:p></w:body>
                    </w:document>
                    """.trimIndent(),
            )

        assertNull(DocumentHtml.render(docxMime, "polos.docx", bytes))
    }

    @Test
    fun `xlsx sheets render as tables with their names`() {
        val bytes =
            zip(
                "xl/workbook.xml" to
                    """
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                    """.trimIndent(),
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

        val rendered = requireNotNull(DocumentHtml.render(xlsxMime, "data.xlsx", bytes))
        assertTrue(rendered.contains("<h3>Data</h3>"))
        assertTrue(rendered.contains("<th>Nama</th>"))
        assertTrue(rendered.contains("<td>42</td>"))
        assertTrue(rendered.contains("<td>teks</td>"))
    }

    @Test
    fun `pptx slides keep their numeric order`() {
        val bytes =
            zip(
                "ppt/slides/slide10.xml" to slide("Sepuluh"),
                "ppt/slides/slide2.xml" to slide("Dua"),
            )

        val rendered = requireNotNull(DocumentHtml.render(pptxMime, "deck.pptx", bytes))
        assertTrue(rendered.contains("<h3>Dua</h3>"))
        assertTrue(rendered.contains("<h3>Sepuluh</h3>"))
        assertTrue(rendered.indexOf("Dua") < rendered.indexOf("Sepuluh"))
        assertTrue(rendered.contains("class=\"doc-slide-number\">1<"))
    }

    @Test
    fun `odt headings and tables become semantic html`() {
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
                          <text:h text:outline-level="1">Judul</text:h>
                          <text:p>Isi</text:p>
                          <table:table table:name="Tabel">
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

        val rendered = requireNotNull(DocumentHtml.render(odtMime, "surat.odt", bytes))
        assertTrue(rendered.contains("<h3>Judul</h3>"))
        assertTrue(rendered.contains("<p>Isi</p>"))
        assertTrue(rendered.contains("<th>A</th>"))
        assertTrue(rendered.contains("<th>B</th>"))
    }

    @Test
    fun `formats without structure are left to the plain renderer`() {
        assertNull(DocumentHtml.render("application/pdf", "surat.pdf", "%PDF".toByteArray()))
        assertNull(DocumentHtml.render("text/plain", "catatan.txt", "halo".toByteArray()))
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
                  <a:p><a:r><a:t>Isi $text</a:t></a:r></a:p>
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
