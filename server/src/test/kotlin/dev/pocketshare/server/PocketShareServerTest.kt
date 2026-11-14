package dev.pocketshare.server

import dev.pocketshare.core.log.NoopLogger
import dev.pocketshare.core.security.PasswordHasher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Clock

class PocketShareServerTest {
    private fun dependencies(config: ServerConfig): ServerDependencies =
        ServerDependencies(
            store = FakeShareStore(emptyList()),
            content = FakeContentSource(emptyList(), emptyMap()),
            passwordHasher = PasswordHasher(iterations = 2_000),
            logger = NoopLogger,
            sessionSecret = ByteArray(32) { (it + 1).toByte() },
            config = config,
            clock = Clock.systemUTC(),
        )

    @Test
    fun `occupied fixed port reports a friendly failure instead of crashing`() =
        runBlocking {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { occupied ->
                val port = occupied.localPort
                val server =
                    PocketShareServer(
                        dependencies(ServerConfig(bindHost = "127.0.0.1", port = port)),
                    )

                try {
                    server.start()
                    fail("Expected PortUnavailableException")
                } catch (error: PortUnavailableException) {
                    assertEquals(port, error.port)
                    assertTrue(error.message.orEmpty().contains(port.toString()))
                }
            }
        }

    @Test
    fun `automatic port starts and stops cleanly`() =
        runBlocking {
            val server =
                PocketShareServer(
                    dependencies(ServerConfig(bindHost = "127.0.0.1", port = 0)),
                )
            val port = server.start()
            assertTrue(port > 0)
            assertEquals(port, server.boundPort)
            server.stop()
            assertEquals(-1, server.boundPort)
        }
}
