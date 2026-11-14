package dev.pocketshare.tunnel

import dev.pocketshare.core.log.NoopLogger
import dev.pocketshare.core.model.TunnelMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudflaredTunnelProviderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logger = NoopLogger

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `quick tunnel reports public url and stops cleanly`() =
        runBlocking {
            val process =
                FakeProcess(
                    lines = listOf("INF | https://test-quick.trycloudflare.com |"),
                )
            val binary = binary()
            val launcher = FakeLauncher(listOf(process))
            val provider =
                CloudflaredTunnelProvider(
                    scope = scope,
                    binaryPath = binary,
                    launcher = launcher,
                    backoff = BackoffPolicy { 0 },
                    logger = logger,
                )

            provider.start(TunnelRequest(TunnelMode.QUICK, localPort = 45_612))
            val running =
                withTimeout(5_000) {
                    provider.status.first { it is TunnelStatus.Running }
                } as TunnelStatus.Running

            assertEquals("https://test-quick.trycloudflare.com", running.publicUrl)
            assertEquals(TunnelMode.QUICK, running.mode)
            assertEquals(
                listOf(
                    binary.absolutePath,
                    "tunnel",
                    "--no-autoupdate",
                    "--protocol",
                    "http2",
                    "--url",
                    "http://127.0.0.1:45612",
                ),
                launcher.specs.first().command,
            )

            provider.stop()
            assertTrue(process.destroyed)
            assertEquals(TunnelStatus.Stopped(), provider.status.value)
        }

    @Test
    fun `provider resolves edge addresses for every launch`() =
        runBlocking {
            val process =
                FakeProcess(
                    lines = listOf("INF | https://test-quick.trycloudflare.com |"),
                )
            val launcher = FakeLauncher(listOf(process))
            var resolutions = 0
            val provider =
                CloudflaredTunnelProvider(
                    scope = scope,
                    binaryPath = binary(),
                    launcher = launcher,
                    backoff = BackoffPolicy { 0 },
                    logger = logger,
                    edgeAddressProvider = {
                        resolutions += 1
                        listOf("198.41.192.107")
                    },
                )

            provider.start(TunnelRequest(TunnelMode.QUICK, localPort = 45_612))
            withTimeout(5_000) {
                provider.status.first { it is TunnelStatus.Running }
            }

            assertEquals(1, resolutions)
            assertEquals("198.41.192.107:7844", launcher.specs.first().environment["TUNNEL_EDGE"])
            provider.stop()
        }

    @Test
    fun `pinned edge attempt falls back to cloudflared edge discovery`() =
        runBlocking {
            val stalled = FakeProcess(lines = emptyList())
            val second = FakeProcess(lines = listOf("INF Registered tunnel connection connIndex=0"))
            val launcher = FakeLauncher(listOf(stalled, second))
            val provider =
                CloudflaredTunnelProvider(
                    scope = scope,
                    binaryPath = binary(),
                    launcher = launcher,
                    backoff = BackoffPolicy { 0 },
                    logger = logger,
                    edgeAddressProvider = { listOf("198.41.192.107") },
                    pinnedConnectTimeoutMillis = 0,
                )

            provider.start(
                TunnelRequest(
                    mode = TunnelMode.NAMED,
                    localPort = 8_080,
                    namedToken = "token",
                    hostname = "files.example.com",
                ),
            )
            withTimeout(8_000) {
                provider.status.first { it is TunnelStatus.Running }
            }

            assertEquals(2, launcher.specs.size)
            assertEquals("198.41.192.107:7844", launcher.specs[0].environment["TUNNEL_EDGE"])
            assertNull(launcher.specs[1].environment["TUNNEL_EDGE"])
            assertTrue(stalled.destroyed)
            provider.stop()
        }

    @Test
    fun `dns servers are refreshed for every launch attempt`() =
        runBlocking {
            val first = FakeProcess(lines = emptyList(), exitCode = 1, stayAlive = false)
            val second = FakeProcess(lines = listOf("INF Registered tunnel connection connIndex=0"))
            val launcher = FakeLauncher(listOf(first, second))
            var lookups = 0
            val provider =
                CloudflaredTunnelProvider(
                    scope = scope,
                    binaryPath = binary(),
                    launcher = launcher,
                    backoff = BackoffPolicy { 0 },
                    logger = logger,
                    dnsServerProvider = {
                        lookups += 1
                        listOf("192.0.2.$lookups", "8.8.8.8")
                    },
                )

            provider.start(
                TunnelRequest(
                    mode = TunnelMode.NAMED,
                    localPort = 8_080,
                    namedToken = "token",
                    hostname = "files.example.com",
                    dnsServers = listOf("10.0.0.1"),
                ),
            )
            withTimeout(8_000) {
                provider.status.first { it is TunnelStatus.Running }
            }

            assertEquals(2, launcher.specs.size)
            assertEquals(
                "192.0.2.1:53,8.8.8.8:53",
                launcher.specs[0].environment["TUNNEL_DNS_SERVERS"],
            )
            assertEquals(
                "192.0.2.2:53,8.8.8.8:53",
                launcher.specs[1].environment["TUNNEL_DNS_SERVERS"],
            )
            provider.stop()
        }

    @Test
    fun `named tunnel waits for registration and keeps token out of argv`() =
        runBlocking {
            val process = FakeProcess(lines = listOf("INF Registered tunnel connection connIndex=0"))
            val launcher = FakeLauncher(listOf(process))
            val provider = provider(launcher)
            val token = "named-tunnel-secret-token"

            provider.start(
                TunnelRequest(
                    mode = TunnelMode.NAMED,
                    localPort = 8_080,
                    namedToken = token,
                    hostname = "files.example.com",
                ),
            )
            val running =
                withTimeout(5_000) {
                    provider.status.first { it is TunnelStatus.Running }
                } as TunnelStatus.Running

            assertEquals("https://files.example.com", running.publicUrl)
            val spec = launcher.specs.first()
            assertFalse(spec.command.any { it.contains(token) })
            assertEquals(token, spec.environment["TUNNEL_TOKEN"])

            provider.stop()
        }

    @Test
    fun `tunnel reports degraded while readiness stays unavailable`() =
        runBlocking {
            val process =
                FakeProcess(
                    lines =
                        listOf(
                            "INF Starting metrics server on 127.0.0.1:20241/metrics",
                            "INF | https://degraded-test.trycloudflare.com |",
                        ),
                )
            var ready = false
            val provider =
                CloudflaredTunnelProvider(
                    scope = scope,
                    binaryPath = binary(),
                    launcher = FakeLauncher(listOf(process)),
                    backoff = BackoffPolicy { 0 },
                    logger = logger,
                    readinessProbe =
                        object : ReadinessProbe {
                            override suspend fun check(address: String): Readiness =
                                if (address == "127.0.0.1:20241" && ready) {
                                    Readiness.READY
                                } else {
                                    Readiness.NOT_READY
                                }
                        },
                    readinessPollMillis = 25,
                    degradedAfterMillis = 0,
                )

            provider.start(TunnelRequest(TunnelMode.QUICK, localPort = 45_612))
            val degraded =
                withTimeout(5_000) {
                    provider.status.first { it is TunnelStatus.Degraded }
                } as TunnelStatus.Degraded
            assertEquals("https://degraded-test.trycloudflare.com", degraded.publicUrl)

            ready = true
            val running =
                withTimeout(5_000) {
                    provider.status.first { it is TunnelStatus.Running }
                } as TunnelStatus.Running
            assertEquals("https://degraded-test.trycloudflare.com", running.publicUrl)
            provider.stop()
        }

    @Test
    fun `process crashes are retried with backoff`() =
        runBlocking {
            val first = FakeProcess(lines = emptyList(), exitCode = 1, stayAlive = false)
            val second = FakeProcess(lines = listOf("INF https://retry.trycloudflare.com"))
            val launcher = FakeLauncher(listOf(first, second))
            val provider =
                CloudflaredTunnelProvider(
                    scope = scope,
                    binaryPath = binary(),
                    launcher = launcher,
                    backoff = BackoffPolicy { 0 },
                    logger = logger,
                )

            provider.start(TunnelRequest(TunnelMode.QUICK, localPort = 45_612))
            val running =
                withTimeout(8_000) {
                    provider.status.first { it is TunnelStatus.Running }
                } as TunnelStatus.Running

            assertEquals("https://retry.trycloudflare.com", running.publicUrl)
            assertEquals(2, launcher.specs.size)
            provider.stop()
        }

    @Test
    fun `invalid named token fails permanently`() =
        runBlocking {
            val process =
                FakeProcess(
                    lines = listOf("ERR Provided Tunnel token is not valid."),
                    exitCode = 1,
                    stayAlive = false,
                )
            val launcher = FakeLauncher(listOf(process, FakeProcess(lines = emptyList())))
            val provider = provider(launcher)

            provider.start(
                TunnelRequest(
                    mode = TunnelMode.NAMED,
                    localPort = 8_080,
                    namedToken = "bad-token",
                    hostname = "files.example.com",
                ),
            )
            val failed =
                withTimeout(5_000) {
                    provider.status.first { it is TunnelStatus.Failed }
                } as TunnelStatus.Failed

            assertTrue(failed.reason.contains("token", ignoreCase = true))
            assertEquals(1, launcher.specs.size)
        }

    @Test
    fun `missing binary fails without launching`() =
        runBlocking {
            val launcher = FakeLauncher(emptyList())
            val missing = File(temporaryFolder.root, "does-not-exist")
            val provider = CloudflaredTunnelProvider(scope, missing, launcher, logger = logger)

            provider.start(TunnelRequest(TunnelMode.QUICK, localPort = 1_234))
            val failed = provider.status.value as TunnelStatus.Failed
            assertTrue(failed.reason.contains("not found"))
            assertTrue(launcher.specs.isEmpty())
        }

    private fun provider(launcher: FakeLauncher): CloudflaredTunnelProvider =
        CloudflaredTunnelProvider(
            scope = scope,
            binaryPath = binary(),
            launcher = launcher,
            backoff = BackoffPolicy { 0 },
            logger = logger,
        )

    private fun binary(): File =
        File.createTempFile("cloudflared", ".bin").apply {
            setExecutable(true)
            deleteOnExit()
        }

    private class FakeLauncher(
        processes: List<ManagedTunnelProcess>,
    ) : TunnelProcessLauncher {
        private val queue = ArrayDeque(processes)
        val specs = CopyOnWriteArrayList<CloudflaredProcessSpec>()

        override fun launch(spec: CloudflaredProcessSpec): ManagedTunnelProcess {
            synchronized(queue) {
                specs.add(spec)
                return queue.removeFirstOrNull() ?: error("No fake process left")
            }
        }
    }

    private class FakeProcess(
        lines: List<String>,
        private val exitCode: Int = 0,
        private val stayAlive: Boolean = true,
    ) : ManagedTunnelProcess {
        private val latch = CountDownLatch(1)

        @Volatile
        var destroyed: Boolean = false
            private set

        override val stdout: InputStream =
            ByteArrayInputStream(
                lines.joinToString("\n").toByteArray(),
            )

        override val stderr: InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            if (stayAlive) latch.await(10, TimeUnit.SECONDS)
            return exitCode
        }

        override fun destroy() {
            destroyed = true
            latch.countDown()
        }

        override fun destroyForcibly() = destroy()
    }
}
