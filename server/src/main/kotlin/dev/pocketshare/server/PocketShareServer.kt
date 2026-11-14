package dev.pocketshare.server

import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Raised when a fixed port cannot be bound, usually because another app already listens on it.
 */
class PortUnavailableException(
    val port: Int,
    cause: Throwable,
) : IOException("Port $port is already in use", cause)

/**
 * Thin lifecycle wrapper around Ktor CIO. The server binds to loopback by default because the
 * tunnel always connects from the same device.
 */
class PocketShareServer(
    private val dependencies: ServerDependencies,
) {
    private var server: EmbeddedServer<*, *>? = null

    @Volatile
    var boundPort: Int = -1
        private set

    suspend fun start(): Int {
        check(server == null) { "Server is already running" }
        ensurePortAvailable()
        // CIO binds asynchronously. Without a handler here, a failed bind (for example a port
        // conflict) crashes the whole process on Android instead of reaching the caller.
        val engineContext =
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, error ->
                    dependencies.logger.error(TAG, "HTTP engine stopped unexpectedly", error)
                }
        val rootConfig =
            serverConfig(applicationEnvironment { }) {
                parentCoroutineContext = engineContext
                module { pocketShareModule(dependencies) }
            }
        val instance =
            embeddedServer(
                factory = CIO,
                rootConfig = rootConfig,
            ) {
                connectors.add(
                    EngineConnectorBuilder().apply {
                        host = dependencies.config.bindHost
                        port = dependencies.config.port
                    },
                )
                connectionIdleTimeoutSeconds = 120
                reuseAddress = true
            }
        val connector =
            runCatching {
                instance.startSuspend(wait = false)
                instance.engine.resolvedConnectors().firstOrNull()
                    ?: error("Ktor did not expose a listening connector")
            }.onFailure {
                runCatching {
                    instance.stopSuspend(gracePeriodMillis = 200, timeoutMillis = 2_000)
                }
            }.getOrThrow()
        server = instance
        boundPort = connector.port
        dependencies.logger.info(TAG, "HTTP server listening on ${dependencies.config.bindHost}:$boundPort")
        return boundPort
    }

    suspend fun stop() {
        val instance = server ?: return
        server = null
        instance.stopSuspend(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        boundPort = -1
        dependencies.logger.info(TAG, "HTTP server stopped")
    }

    private fun ensurePortAvailable() {
        val port = dependencies.config.port
        if (port == 0) return
        try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(dependencies.config.bindHost, port))
            }
        } catch (error: IOException) {
            throw PortUnavailableException(port, error)
        }
    }

    private companion object {
        const val TAG = "HttpServer"
    }
}
