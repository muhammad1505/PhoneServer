package dev.pocketshare.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostnameValidatorTest {
    @Test
    fun `normalizes pasted URLs`() {
        assertEquals("files.example.com", HostnameValidator.normalize("https://Files.Example.com/"))
        assertEquals("files.example.com", HostnameValidator.normalize("  http://files.example.com:443/path?x=1"))
        assertEquals("sub.example.co.id", HostnameValidator.normalize("sub.example.co.id"))
    }

    @Test
    fun `supports IDN`() {
        assertEquals("xn--bcher-kva.example", HostnameValidator.normalize("bücher.example"))
    }

    @Test
    fun `rejects invalid hosts`() {
        assertNull(HostnameValidator.normalize(""))
        assertNull(HostnameValidator.normalize("localhost"))
        assertNull(HostnameValidator.normalize("127.0.0.1"))
        assertNull(HostnameValidator.normalize("example"))
        assertNull(HostnameValidator.normalize("-bad.example.com"))
        assertNull(HostnameValidator.normalize("bad-.example.com"))
        assertNull(HostnameValidator.normalize("bad..example.com"))
        assertNull(HostnameValidator.normalize("ftp://files.example.com"))
        assertNull(HostnameValidator.normalize("[::1]"))
    }
}
