package dev.pocketshare.tunnel

import dev.pocketshare.core.model.TunnelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflaredCommandTest {
    @Test
    fun `quick tunnel command matches cloudflared syntax`() {
        val spec =
            CloudflaredCommand.build(
                request = TunnelRequest(TunnelMode.QUICK, localPort = 45_612),
                binaryPath = "/data/app/lib/arm64/libcloudflared.so",
            )

        assertEquals(
            listOf(
                "/data/app/lib/arm64/libcloudflared.so",
                "tunnel",
                "--no-autoupdate",
                "--protocol",
                "http2",
                "--url",
                "http://127.0.0.1:45612",
            ),
            spec.command,
        )
        assertNull(spec.environment["TUNNEL_TOKEN"])
    }

    @Test
    fun `named tunnel token is passed in the environment not argv`() {
        val token = "eyJhIjoiYSIsInQiOiJzZWNyZXQifQ.signature"
        val spec =
            CloudflaredCommand.build(
                request =
                    TunnelRequest(
                        mode = TunnelMode.NAMED,
                        localPort = 8_080,
                        namedToken = token,
                        hostname = "files.example.com",
                        dnsServers = listOf("1.1.1.1", "2606:4700:4700::1111"),
                    ),
                binaryPath = "/bin/cloudflared",
            )

        assertEquals(token, spec.environment["TUNNEL_TOKEN"])
        assertFalse(spec.command.any { it.contains(token) })
        assertTrue(spec.command.contains("run"))
        assertTrue(spec.command.contains("--protocol"))
        assertTrue(spec.command.contains("http2"))
        assertTrue(spec.command.contains("--dns-resolver-addrs"))
        assertTrue(spec.command.contains("1.1.1.1:53"))
        assertTrue(spec.command.contains("[2606:4700:4700::1111]:53"))
        assertEquals("1.1.1.1:53,[2606:4700:4700::1111]:53", spec.environment["TUNNEL_DNS_RESOLVER_ADDRS"])
        assertEquals("1.1.1.1:53,[2606:4700:4700::1111]:53", spec.environment["TUNNEL_DNS_SERVERS"])
    }

    @Test
    fun `edge addresses are numeric and passed in the environment`() {
        val spec =
            CloudflaredCommand.build(
                request =
                    TunnelRequest(
                        mode = TunnelMode.NAMED,
                        localPort = 8_080,
                        namedToken = "token",
                        hostname = "files.example.com",
                    ),
                binaryPath = "/bin/cloudflared",
                edgeAddresses =
                    listOf(
                        "198.41.192.107",
                        "2001:db8::1",
                        "not-an-ip",
                        "198.41.192.107",
                    ),
            )

        assertEquals(
            "198.41.192.107:7844,[2001:db8::1]:7844",
            spec.environment["TUNNEL_EDGE"],
        )
        assertFalse(spec.command.any { it.contains("198.41.192.107") })
    }

    @Test
    fun `quick tunnels also use explicit edge addresses`() {
        val spec =
            CloudflaredCommand.build(
                request = TunnelRequest(TunnelMode.QUICK, localPort = 45_612),
                binaryPath = "/bin/cloudflared",
                edgeAddresses = listOf("198.41.200.233"),
            )

        assertEquals("198.41.200.233:7844", spec.environment["TUNNEL_EDGE"])
    }

    @Test
    fun `resolver strings with shell metacharacters are rejected`() {
        val spec =
            CloudflaredCommand.build(
                request =
                    TunnelRequest(
                        mode = TunnelMode.NAMED,
                        localPort = 8_080,
                        namedToken = "token",
                        hostname = "files.example.com",
                        dnsServers = listOf("1.1.1.1; rm -rf /", "not-an-ip", "192.168.1.1"),
                    ),
                binaryPath = "/bin/cloudflared",
            )

        assertFalse(spec.command.any { it.contains("rm") })
        assertTrue(spec.command.contains("192.168.1.1:53"))
        assertEquals("192.168.1.1:53", spec.environment["TUNNEL_DNS_RESOLVER_ADDRS"])
    }

    @Test
    fun `request toString never exposes the named token`() {
        val request =
            TunnelRequest(
                mode = TunnelMode.NAMED,
                localPort = 8_080,
                namedToken = "super-secret-token",
                hostname = "files.example.com",
            )
        assertFalse(request.toString().contains("super-secret-token"))
        assertTrue(request.toString().contains("[REDACTED]"))
    }
}
