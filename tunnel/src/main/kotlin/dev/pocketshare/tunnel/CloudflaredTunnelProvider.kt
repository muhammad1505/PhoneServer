package dev.pocketshare.tunnel

import dev.pocketshare.core.log.AppLogger
import dev.pocketshare.core.log.NoopLogger
import dev.pocketshare.core.model.TunnelMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Supervises one cloudflared child process at a time. Crashes are retried with exponential
 * backoff; configuration errors stop the supervisor. Tokens never appear in argv or log output.
 *
 * cloudflared normally survives network changes internally instead of exiting, so the supervisor
 * also watches the process' loopback `/ready` endpoint. That endpoint answers 200 only while at
 * least one edge connection is active, which lets the UI report [TunnelStatus.Degraded] instead
 * of claiming the link is live while Cloudflare returns 1033.
 */
class CloudflaredTunnelProvider(
    private val scope: CoroutineScope,
    private val binaryPath: File,
    private val launcher: TunnelProcessLauncher = ProcessBuilderTunnelLauncher(),
    private val baseEnvironmentProvider: () -> Map<String, String> = { emptyMap() },
    private val workingDirectory: File? = null,
    private val edgeAddressProvider: () -> List<String> = { emptyList() },
    private val backoff: BackoffPolicy = ExponentialBackoff(),
    private val logger: AppLogger = NoopLogger,
    private val connectionTimeoutMillis: Long = 150_000L,
    private val networkOnline: StateFlow<Boolean>? = null,
    private val readinessProbe: ReadinessProbe = HttpReadinessProbe(),
    private val readinessPollMillis: Long = 5_000L,
    private val degradedAfterMillis: Long = 15_000L,
) : TunnelProvider {
    private val mutableStatus = MutableStateFlow<TunnelStatus>(TunnelStatus.Stopped())
    override val status: StateFlow<TunnelStatus> = mutableStatus.asStateFlow()

    private val lifecycleMutex = Mutex()
    private var supervisor: Job? = null
    private val processRef = AtomicReference<ManagedTunnelProcess?>(null)

    override suspend fun start(request: TunnelRequest) {
        lifecycleMutex.withLock {
            stopInternal()
            if (!binaryPath.isFile) {
                mutableStatus.value = TunnelStatus.Failed("cloudflared binary not found at ${binaryPath.name}")
                return
            }
            if (!binaryPath.canExecute() && !binaryPath.setExecutable(true)) {
                mutableStatus.value = TunnelStatus.Failed("cloudflared binary is not executable")
                return
            }
            logger.info(TAG, "Starting ${request.mode} tunnel on local port ${request.localPort}")
            supervisor = scope.launch { supervise(request) }
        }
    }

    override suspend fun stop() {
        lifecycleMutex.withLock { stopInternal() }
    }

    private suspend fun stopInternal() {
        val job = supervisor
        supervisor = null
        val process = processRef.getAndSet(null)
        process?.let { runCatching { it.destroy() } }
        if (job != null) {
            job.cancel()
            withTimeoutOrNull(STOP_GRACE_MILLIS) { job.join() }
            process?.let { runCatching { it.destroyForcibly() } }
            withTimeoutOrNull(STOP_FORCE_MILLIS) { job.join() }
        }
        mutableStatus.value = TunnelStatus.Stopped()
        logger.info(TAG, "Tunnel stopped")
    }

    private suspend fun supervise(request: TunnelRequest) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            awaitNetwork(attempt)
            mutableStatus.value = TunnelStatus.Starting(attempt = attempt + 1)
            val outcome =
                try {
                    runOnce(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    RunOutcome.StartFailure(error.message.orEmpty())
                } catch (error: SecurityException) {
                    RunOutcome.StartFailure(error.message.orEmpty())
                }

            when (outcome) {
                is RunOutcome.Fatal -> {
                    logger.error(TAG, "Tunnel failed permanently: ${outcome.reason}")
                    mutableStatus.value = TunnelStatus.Failed(outcome.reason)
                    return
                }

                is RunOutcome.StartFailure -> {
                    val reason = "Unable to start cloudflared: ${outcome.reason.take(200)}"
                    logger.error(TAG, reason)
                    mutableStatus.value = TunnelStatus.Failed(reason)
                    return
                }

                is RunOutcome.Exited -> {
                    attempt += 1
                    val wait = backoff.delayMillis(attempt)
                    logger.warn(
                        TAG,
                        "cloudflared exited (code ${outcome.exitCode}); retrying in ${wait}ms" +
                            (outcome.reason?.let { ": $it" } ?: ""),
                    )
                    mutableStatus.value =
                        TunnelStatus.Reconnecting(
                            attempt = attempt,
                            delayMillis = wait,
                            reason = outcome.reason,
                        )
                    delay(wait)
                }
            }
        }
    }

    /**
     * Restarting while the phone is offline only burns attempts and (for quick tunnels) changes
     * the public URL, so wait for connectivity before launching again.
     */
    private suspend fun awaitNetwork(attempt: Int) {
        val online = networkOnline ?: return
        if (online.value) return
        mutableStatus.value =
            TunnelStatus.Reconnecting(
                attempt = attempt + 1,
                delayMillis = 0L,
                reason = "Waiting for a network connection",
            )
        logger.info(TAG, "Waiting for a network connection before starting cloudflared")
        online.first { it }
    }

    private suspend fun runOnce(request: TunnelRequest): RunOutcome {
        val edgeAddresses =
            runCatching { edgeAddressProvider() }
                .getOrDefault(emptyList())
                .filter { it.isNotBlank() }
        if (edgeAddresses.isEmpty()) {
            logger.warn(TAG, "No Cloudflare edge addresses resolved; cloudflared will use its own DNS")
        } else {
            logger.info(TAG, "Using Cloudflare edge address(es): ${edgeAddresses.joinToString(", ")}")
        }
        val spec =
            try {
                CloudflaredCommand.build(
                    request = request,
                    binaryPath = binaryPath.absolutePath,
                    baseEnvironment = baseEnvironmentProvider(),
                    workingDirectory = workingDirectory,
                    edgeAddresses = edgeAddresses,
                )
            } catch (error: IllegalArgumentException) {
                return RunOutcome.Fatal("Invalid tunnel configuration: ${error.message.orEmpty().take(160)}")
            }

        val process = launcher.launch(spec)
        processRef.set(process)
        val shared = SharedRunState()
        val health = RunHealth(mode = request.mode)
        val success = CompletableDeferred<Unit>()

        fun report(parsed: ParsedCloudflaredLine) {
            parsed.metricsAddress?.let { health.metricsAddress = it }
            parsed.connectionIndex?.let { index ->
                when {
                    parsed.connected -> health.connections.add(index)
                    parsed.disconnected -> health.connections.remove(index)
                }
            }
            if (parsed.quickTunnelUrl != null) {
                shared.error = null
                health.publicUrl = parsed.quickTunnelUrl
                success.complete(Unit)
                mutableStatus.value = TunnelStatus.Running(parsed.quickTunnelUrl, TunnelMode.QUICK)
                logger.info(TAG, "Quick tunnel ready: ${parsed.quickTunnelUrl}")
            }
            if (parsed.connected) {
                success.complete(Unit)
                health.unhealthySince = null
                val hostname =
                    request.hostname
                        .orEmpty()
                        .trim()
                        .trimEnd('/')
                if (hostname.isNotEmpty()) {
                    val url =
                        if (hostname.startsWith("http://") || hostname.startsWith("https://")) {
                            hostname
                        } else {
                            "https://$hostname"
                        }
                    health.publicUrl = url
                    mutableStatus.value = TunnelStatus.Running(url, TunnelMode.NAMED)
                    logger.info(TAG, "Named tunnel connected")
                }
            }
            if (parsed.disconnected) {
                // One failed connection out of four is not an outage; let the readiness poll
                // decide before touching the public status.
                logger.debug(TAG, "Edge connection event: ${parsed.error ?: "disconnected"}")
            }
            if (parsed.fatal) {
                shared.fatal = parsed.error ?: "cloudflared rejected the tunnel token"
            } else if (parsed.error != null) {
                shared.error = parsed.error
                logger.warn(TAG, parsed.error)
            }
        }

        val readers =
            listOf(process.stdout, process.stderr).map { stream ->
                scope.launch(Dispatchers.IO) { consume(stream, ::report) }
            }
        val healthJob =
            scope.launch {
                monitorReadiness(health, success)
            }
        val watchdog =
            scope.launch {
                var waited = 0L
                while (currentCoroutineContext().isActive &&
                    !success.isCompleted &&
                    waited < connectionTimeoutMillis
                ) {
                    delay(WATCHDOG_TICK_MILLIS)
                    // Time spent offline is not the tunnel's fault; do not kill a process that
                    // is merely waiting for the carrier to come back.
                    if (networkOnline?.value != false) waited += WATCHDOG_TICK_MILLIS
                }
                if (currentCoroutineContext().isActive && !success.isCompleted) {
                    shared.error = "Timed out waiting for Cloudflare to connect"
                    logger.warn(TAG, shared.error!!)
                    runCatching { process.destroy() }
                }
            }

        val exitCode =
            try {
                withContext(Dispatchers.IO) { process.waitFor() }
            } finally {
                withContext(NonCancellable) {
                    watchdog.cancelAndJoin()
                    healthJob.cancelAndJoin()
                    runCatching { process.destroyForcibly() }
                    withTimeoutOrNull(READER_DRAIN_MILLIS) { readers.joinAll() }
                    readers.forEach { it.cancel() }
                }
                processRef.compareAndSet(process, null)
            }

        val fatal = shared.fatal
        if (fatal != null) return RunOutcome.Fatal(fatal)
        return RunOutcome.Exited(exitCode = exitCode, reason = shared.error)
    }

    /**
     * Polls cloudflared's readiness endpoint once the initial connection succeeded. A single
     * failed connection among several must not turn the UI red, so degradation is only reported
     * after [degradedAfterMillis] of continuous failure.
     */
    private suspend fun monitorReadiness(
        health: RunHealth,
        success: CompletableDeferred<Unit>,
    ) {
        success.await()
        while (currentCoroutineContext().isActive) {
            delay(readinessPollMillis)
            val url = health.publicUrl ?: continue
            val readiness =
                when {
                    health.metricsAddress != null -> readinessProbe.check(health.metricsAddress!!)
                    health.connections.isNotEmpty() -> Readiness.READY
                    else -> Readiness.NOT_READY
                }
            when (readiness) {
                Readiness.READY -> {
                    health.unhealthySince = null
                    if (mutableStatus.value is TunnelStatus.Degraded) {
                        mutableStatus.value = TunnelStatus.Running(url, health.mode)
                        logger.info(TAG, "Tunnel reachable again")
                    }
                }

                Readiness.NOT_READY -> {
                    val now = System.currentTimeMillis()
                    val since = health.unhealthySince ?: now.also { health.unhealthySince = it }
                    if (now - since >= degradedAfterMillis) {
                        val current = mutableStatus.value
                        if (current is TunnelStatus.Running || current is TunnelStatus.Degraded) {
                            val reason = "No active connection to the Cloudflare edge"
                            if (current !is TunnelStatus.Degraded || current.reason != reason) {
                                mutableStatus.value = TunnelStatus.Degraded(url, reason)
                                logger.warn(TAG, "Tunnel is not reachable: $reason")
                            }
                        }
                    }
                }

                Readiness.UNKNOWN -> Unit
            }
        }
    }

    private suspend fun consume(
        stream: InputStream,
        report: (ParsedCloudflaredLine) -> Unit,
    ) {
        try {
            stream.bufferedReader().use { reader: BufferedReader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val sanitized = CloudflaredLogParser.sanitize(line)
                    if (sanitized.isNotEmpty()) logger.debug(TAG_LOG, sanitized)
                    report(CloudflaredLogParser.parse(line))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            logger.debug(TAG, "cloudflared output closed: ${error.javaClass.simpleName}")
        }
    }

    private class RunHealth(
        val mode: TunnelMode,
    ) {
        @Volatile
        var publicUrl: String? = null

        @Volatile
        var metricsAddress: String? = null

        @Volatile
        var unhealthySince: Long? = null

        val connections: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    }

    private class SharedRunState {
        @Volatile
        var error: String? = null

        @Volatile
        var fatal: String? = null
    }

    private sealed interface RunOutcome {
        data class Exited(
            val exitCode: Int,
            val reason: String?,
        ) : RunOutcome

        data class Fatal(
            val reason: String,
        ) : RunOutcome

        data class StartFailure(
            val reason: String,
        ) : RunOutcome
    }

    private companion object {
        const val TAG = "Tunnel"
        const val TAG_LOG = "Cloudflared"
        const val STOP_GRACE_MILLIS = 4_000L
        const val STOP_FORCE_MILLIS = 1_000L
        const val READER_DRAIN_MILLIS = 1_000L
        const val WATCHDOG_TICK_MILLIS = 1_000L
    }
}

/** Probes cloudflared's loopback metrics server; never used across the network. */
class HttpReadinessProbe(
    private val connectTimeoutMillis: Int = 2_000,
    private val readTimeoutMillis: Int = 2_000,
) : ReadinessProbe {
    override suspend fun check(address: String): Readiness =
        withContext(Dispatchers.IO) {
            if (!LOOPBACK.matches(address)) return@withContext Readiness.UNKNOWN
            val connection =
                runCatching { URL("http://$address/ready").openConnection() as HttpURLConnection }
                    .getOrNull()
                    ?: return@withContext Readiness.UNKNOWN
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                when (connection.responseCode) {
                    200 -> Readiness.READY
                    503 -> Readiness.NOT_READY
                    else -> Readiness.UNKNOWN
                }
            } catch (_: IOException) {
                Readiness.UNKNOWN
            } finally {
                runCatching { connection.disconnect() }
            }
        }

    private companion object {
        val LOOPBACK = Regex("127\\.0\\.0\\.1:\\d{2,5}")
    }
}
