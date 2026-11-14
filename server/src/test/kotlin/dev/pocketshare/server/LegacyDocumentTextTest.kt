package dev.pocketshare.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDocumentTextTest {
    @Test
    fun `word documents are read through their piece table`() {
        val text = sample("SampleDoc.doc", "application/msword", "doc")

        assertEquals(
            """
            I am a test document
            This is page 1
            I am Calibri (Body) in font size 11

            This is page two
            It’s Arial Black in 16 point
            It’s also in blue
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `excel shared strings and rk numbers are rendered`() {
        assertEquals(
            "Sheet1\nreplaceMe",
            sample("Simple.xls", "application/vnd.ms-excel", "xls"),
        )
        assertEquals(
            "Sheet1\n1\n2\t3\n4\t5\t6\n7\t8\t9\t10\n11\t12\t13\t14\t15",
            sample("SimpleMultiCell.xls", "application/vnd.ms-excel", "xls"),
        )
    }

    @Test
    fun `powerpoint slides skip masters and notes`() {
        val text = sample("SampleShow.ppt", "application/vnd.ms-powerpoint", "ppt")

        assertTrue(text!!.contains("Title of the first slide"))
        assertTrue(text.contains("This is the second slide"))
        assertTrue(text.contains("They’re fun, aren’t they?"))
        assertFalse(text.contains("Click to edit Master"))
        assertFalse(text.contains("I am the notes"))
    }

    @Test
    fun `broken legacy files fall back instead of throwing`() {
        assertNull(DocumentText.extract("application/msword", "rusak.doc", ByteArray(64)))
        assertNull(DocumentText.extract("application/vnd.ms-excel", "rusak.xls", byteArrayOf(9, 8)))
        assertNull(DocumentText.extract("application/vnd.ms-powerpoint", "rusak.ppt", ByteArray(0)))
    }

    private fun sample(
        name: String,
        mime: String,
        extension: String,
    ): String? {
        val bytes =
            checkNotNull(javaClass.getResourceAsStream("/samples/$name")) {
                "Missing test sample $name"
            }.use { it.readBytes() }
        return DocumentText.extract(mime, "sample.$extension", bytes)
    }
}
