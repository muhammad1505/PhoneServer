package dev.pocketshare.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtfTextTest {
    private val rtfMime = "application/rtf"

    @Test
    fun `paragraphs tabs and table cells keep readable layout`() {
        val rtf = """{\rtf1\ansi Halo dunia\par Baris kedua\tab selesai}"""
        assertEquals("Halo dunia\nBaris kedua\tselesai", DocumentText.extract(rtfMime, "catatan.rtf", rtf.toByteArray()))

        val table = """{\rtf1 Nama\cell Nilai\row A\cell 42\row}"""
        assertEquals("Nama\tNilai\nA\t42", DocumentText.extract(rtfMime, "tabel.rtf", table.toByteArray()))
    }

    @Test
    fun `font tables metadata pictures and field instructions are dropped`() {
        val rtf =
            """{\rtf1{\fonttbl{\f0 Arial;}}{\info{\title Rahasia}}""" +
                """{\*\generator Tes}Teks terlihat{\pict\pngblip 89504e47}""" +
                """{\field{\*\fldinst HYPERLINK "https://example.com"}{\fldrslt Klik di sini}}}"""

        assertEquals("Teks terlihatKlik di sini", DocumentText.extract(rtfMime, "info.rtf", rtf.toByteArray()))
    }

    @Test
    fun `code page and unicode escapes are decoded`() {
        val rtf = """{\rtf1\ansi\ansicpg1252 Caf\'e9 \u233? sudah}"""
        assertEquals("Café é sudah", DocumentText.extract(rtfMime, "aksen.rtf", rtf.toByteArray()))
    }

    @Test
    fun `uc count skips the right number of fallback characters`() {
        val rtf = """{\rtf1\uc2\u233?? setelah}"""
        assertEquals("é setelah", DocumentText.extract(rtfMime, "unicode.rtf", rtf.toByteArray()))
    }

    @Test
    fun `non rtf data is rejected and truncated rtf stays usable`() {
        assertNull(DocumentText.extract(rtfMime, "rusak.rtf", "bukan rtf".toByteArray()))
        assertNull(DocumentText.extract(rtfMime, "kosong.rtf", ByteArray(0)))
        assertEquals("teks", RtfText.extract("{\\rtf1 teks".toByteArray()))
    }

    @Test
    fun `rtf support is detected from either mime or extension`() {
        assertTrue(DocumentText.isSupported("application/rtf", "surat"))
        assertTrue(DocumentText.isSupported("text/rtf", "surat"))
        assertTrue(DocumentText.isSupported("application/octet-stream", "SURAT.RTF"))
        assertFalse(DocumentText.isSupported("text/plain", "surat.txt"))
    }
}
