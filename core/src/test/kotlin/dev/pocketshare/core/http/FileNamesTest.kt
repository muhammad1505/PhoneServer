package dev.pocketshare.core.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test
    fun `content disposition contains ASCII fallback and RFC 5987 value`() {
        val value = FileNames.contentDisposition("laporan keuangan \"Q3\".pdf")
        assertTrue(value.startsWith("attachment; filename=\""))
        assertTrue(value.contains("filename*=UTF-8''"))
        assertTrue(value.contains("laporan%20keuangan"))
        assertFalse(value.contains("\r"))
        assertFalse(value.contains("\n"))
    }

    @Test
    fun `content disposition handles unicode`() {
        val value = FileNames.contentDisposition("日本語 ファイル.zip")
        assertTrue(value.contains("filename*=UTF-8''%E6%97%A5%E6%9C%AC%E8%AA%9E%20%E3%83%95%E3%82%A1%E3%82%A4%E3%83%AB.zip"))
        assertTrue(value.contains("filename=\"___ ____.zip\""))
    }

    @Test
    fun `header injection is removed`() {
        val value = FileNames.contentDisposition("evil\r\nSet-Cookie: x=1.txt")
        assertFalse(value.contains("\r"))
        assertFalse(value.contains("\n"))
    }

    @Test
    fun `html escaping`() {
        assertEquals(
            "&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp;",
            FileNames.escapeHtml("<script>alert('x')</script> &"),
        )
    }

    @Test
    fun `zip traversal is neutralized`() {
        val path = FileNames.zipEntryPath("../../etc/passwd")
        assertFalse(path.startsWith("/"))
        assertFalse(path.split('/').any { it == ".." })
        assertEquals("_/_/etc/passwd", path)
        assertEquals("_", FileNames.zipEntryPath(".."))
        assertEquals("_", FileNames.zipEntryPath("."))
        assertEquals("folder/_/file.txt", FileNames.zipEntryPath("folder/../file.txt"))
    }

    @Test
    fun `zip names are deduplicated`() {
        val used = mutableSetOf<String>()
        assertEquals("a.txt", FileNames.uniqueZipEntry("a.txt", used))
        assertEquals("a (2).txt", FileNames.uniqueZipEntry("a.txt", used))
        assertEquals("a (3).txt", FileNames.uniqueZipEntry("a.txt", used))
    }

    @Test
    fun `path segments are percent encoded`() {
        assertEquals("a%20b%2Fc.txt", FileNames.encodePathSegment("a b/c.txt"))
        assertEquals("normal-_.~", FileNames.encodePathSegment("normal-_.~"))
    }
}
