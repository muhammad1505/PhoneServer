package dev.pocketshare.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflaredLogParserTest {
    @Test
    fun `extracts quick tunnel url from banner`() {
        val line = "2026-11-09T03:32:11Z INF |  https://sip-dist-fundamental-publication.trycloudflare.com  |"
        val parsed = CloudflaredLogParser.parse(line)
        assertEquals(
            "https://sip-dist-fundamental-publication.trycloudflare.com",
            parsed.quickTunnelUrl,
        )
    }

    @Test
    fun `detects registered named tunnel connection`() {
        val line = "INF Registered tunnel connection connIndex=0 connection=abc event=0 ip=198.41.200.13 protocol=quic"
        assertTrue(CloudflaredLogParser.parse(line).connected)
    }

    @Test
    fun `invalid token is fatal`() {
        val parsed = CloudflaredLogParser.parse("ERR Provided Tunnel token is not valid. error=bad")
        assertTrue(parsed.fatal)
        assertNotNull(parsed.error)
    }

    @Test
    fun `ansi sequences are stripped and tokens redacted`() {
        val token = "a".repeat(43)
        val sanitized = CloudflaredLogParser.sanitize("\u001B[31mERR token=$token\u001B[0m")
        assertFalse(sanitized.contains(token))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun `ordinary log lines are not errors`() {
        val parsed = CloudflaredLogParser.parse("INF Version 2026.10.7 (Checksum abc)")
        assertNull(parsed.error)
        assertFalse(parsed.fatal)
        assertNull(parsed.quickTunnelUrl)
    }

    @Test
    fun `failed quick tunnel request is never treated as a public url`() {
        val line =
            "ERR failed to request quick Tunnel: Post \"https://api.trycloudflare.com/tunnel\": " +
                "dial tcp: lookup api.trycloudflare.com on [::1]:53: read: connection refused"
        val parsed = CloudflaredLogParser.parse(line)
        assertNull(parsed.quickTunnelUrl)
        assertNotNull(parsed.error)
        assertFalse(parsed.fatal)
    }

    @Test
    fun `unregistered connection is a disconnect event not a registration`() {
        val parsed =
            CloudflaredLogParser.parse(
                "INF Unregistered tunnel connection connIndex=1 event=0 ip=198.41.200.13",
            )
        assertTrue(parsed.disconnected)
        assertFalse(parsed.connected)
        assertEquals(1, parsed.connectionIndex)
    }

    @Test
    fun `lost edge connection and retry lines are disconnect events`() {
        val lost = CloudflaredLogParser.parse("INF Lost connection with the edge connIndex=0")
        assertTrue(lost.disconnected)
        assertEquals(0, lost.connectionIndex)

        val retry = CloudflaredLogParser.parse("INF Retrying connection in up to 2s connIndex=3")
        assertTrue(retry.disconnected)
        assertEquals(3, retry.connectionIndex)
    }

    @Test
    fun `metrics server address is extracted for the readiness probe`() {
        val parsed =
            CloudflaredLogParser.parse(
                "INF Starting metrics server on 127.0.0.1:20241/metrics",
            )
        assertEquals("127.0.0.1:20241", parsed.metricsAddress)
        assertNull(parsed.error)
    }

    @Test
    fun `does not treat arbitrary trycloudflare links as quick tunnel urls`() {
        val parsed = CloudflaredLogParser.parse("INF see https://docs.example.com/trycloudflare.com")
        assertNull(parsed.quickTunnelUrl)
    }
}
