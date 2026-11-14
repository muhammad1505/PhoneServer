package dev.pocketshare.runtime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketshare.R
import dev.pocketshare.core.log.AppLogger
import dev.pocketshare.core.log.Redactor
import dev.pocketshare.core.model.TunnelMode
import dev.pocketshare.core.security.PasswordHasher
import dev.pocketshare.core.share.ShareContentSource
import dev.pocketshare.core.share.ShareStore
import dev.pocketshare.core.util.HostnameValidator
import dev.pocketshare.data.settings.AppSettings
import dev.pocketshare.data.settings.NamedTunnelRepository
import dev.pocketshare.data.settings.SettingsRepository
import dev.pocketshare.server.PocketShareServer
import dev.pocketshare.server.PortUnavailableException
import dev.pocketshare.server.ServerConfig
import dev.pocketshare.server.ServerDependencies
import dev.pocketshare.tunnel.CloudflaredTunnelProvider
import dev.pocketshare.tunnel.TunnelRequest
import dev.pocketshare.tunnel.TunnelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.BindException
import java.security.SecureRandom
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

enum class SharingPhase {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class SharingState(
    val phase: SharingPhase = SharingPhase.STOPPED,
    val mode: TunnelMode = TunnelMode.QUICK,
    val publicUrl: String? = null,
    val localPort: Int? = null,
    val tunnelStatus: TunnelStatus = TunnelStatus.Stopped(),
    val error: String? = null,
    val startedAtEpochMs: Long? = null,
    /** Result of probing the public URL; a running tunnel can still be unreachable. */
    val reachability: Reachability = Reachability.UNKNOWN,
) {
    val isSharing: Boolean
        get() = phase == SharingPhase.STARTING || phase == SharingPhase.RUNNING

    val isBusy: Boolean
        get() = phase != SharingPhase.STOPPED && phase != SharingPhase.ERROR
}

/**
 * Owns the HTTP server and cloudflared process for the whole process lifetime. The foreground
 * service starts/stops it and the UI observes [state].
 */
@Singleton
class ShareRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shareStore: ShareStore,
    private val contentSource: ShareContentSource,
    private val passwordHasher: PasswordHasher,
    private val logger: AppLogger,
    private val settingsRepository: SettingsRepository,
    private val namedTunnelRepository: NamedTunnelRepository,
    private val cloudflaredRuntime: dev.pocketshare.tunnel.CloudflaredRuntime,
    private val networkMonitor: NetworkMonitor,
    private val clock: Clock,
    private val secureRandom: SecureRandom,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lifecycleMutex = Mutex()
    private val mutableState = MutableStateFlow(SharingState())
    val state: StateFlow<SharingState> = mutableState.asStateFlow()
    val network: StateFlow<NetworkStatus> = networkMonitor.status

    private val tunnel =
        CloudflaredTunnelProvider(
            scope = scope,
            binaryPath =
                cloudflaredRuntime.binary
                    ?: File(cloudflaredRuntime.workingDirectory, "missing-libcloudflared.so"),
            baseEnvironmentProvider = cloudflaredRuntime::baseEnvironment,
            workingDirectory = cloudflaredRuntime.workingDirectory,
            edgeAddressProvider = cloudflaredRuntime::edgeAddresses,
            dnsServerProvider = cloudflaredRuntime::dnsServers,
            logger = logger,
            networkOnline = networkMonitor.online,
        )

    private val publicProbe = PublicReachabilityProbe()
    private var probeJob: Job? = null
    private var probeTarget: String? = null
    private var wifiOnlyJob: Job? = null

    private var server: PocketShareServer? = null

    @Volatile
    private var activeSettings: AppSettings? = null

    private var autoStopJob: Job? = null

    init {
        scope.launch {
            tunnel.status.collect(::onTunnelStatus)
        }
        scope.launch {
            combine(networkMonitor.status, settingsRepository.settings) { network, settings ->
                network to settings
            }.collect { (network, settings) ->
                val violation =
                    settings.wifiOnly &&
                        mutableState.value.isSharing &&
                        network.online &&
                        !network.isWifi
                if (!violation) {
                    wifiOnlyJob?.cancel()
                    wifiOnlyJob = null
                    return@collect
                }
                // Wi-Fi handover briefly reports cellular before VALIDATED flips; give the
                // phone a moment to settle instead of killing the session on a false positive.
                if (wifiOnlyJob?.isActive == true) return@collect
                wifiOnlyJob =
                    scope.launch {
                        delay(WIFI_ONLY_GRACE_MILLIS)
                        val current = networkMonitor.status.value
                        if (current.online && !current.isWifi && mutableState.value.isSharing) {
                            failAndStop(context.getString(R.string.error_wifi_only))
                        }
                    }
            }
        }
    }

    suspend fun start() {
        lifecycleMutex.withLock { startInternal() }
    }

    suspend fun stop() {
        lifecycleMutex.withLock { stopInternal(error = null) }
    }

    fun publicLink(token: String): String? {
        val base = mutableState.value.publicUrl ?: return null
        return "${base.trimEnd('/')}/s/$token"
    }

    fun cloudflaredVersion(): String = cloudflaredRuntime.versionLabel

    fun cloudflaredBinaryPath(): String? = cloudflaredRuntime.binary?.absolutePath

    fun dnsServers(): List<String> = cloudflaredRuntime.dnsServers()

    private suspend fun startInternal() {
        if (mutableState.value.isBusy) return
        stopProbe()
        // VALIDATED can lag behind the first callback after a cold start; read it fresh so a
        // just-connected phone is not rejected as offline.
        networkMonitor.refresh()
        mutableState.value = SharingState(phase = SharingPhase.STARTING)
        val settings =
            try {
                settingsRepository.settings.first()
            } catch (error: IOException) {
                mutableState.value =
                    SharingState(
                        phase = SharingPhase.ERROR,
                        error = "Unable to read settings: ${safeMessage(error)}",
                    )
                return
            }
        activeSettings = settings
        mutableState.value = SharingState(phase = SharingPhase.STARTING, mode = settings.tunnelMode)

        val network = networkMonitor.status.value
        if (!network.online) {
            mutableState.value =
                SharingState(
                    phase = SharingPhase.ERROR,
                    mode = settings.tunnelMode,
                    error = context.getString(R.string.error_offline),
                )
            return
        }
        if (settings.wifiOnly && network.online && !network.isWifi) {
            mutableState.value =
                SharingState(
                    phase = SharingPhase.ERROR,
                    mode = settings.tunnelMode,
                    error = context.getString(R.string.error_wifi_only),
                )
            return
        }
        if (cloudflaredRuntime.binary == null) {
            mutableState.value =
                SharingState(
                    phase = SharingPhase.ERROR,
                    mode = settings.tunnelMode,
                    error = context.getString(R.string.error_binary_missing),
                )
            return
        }

        var pendingServer: PocketShareServer? = null
        val failure =
            runCatching {
                val namedSettings = namedTunnelRepository.settings.first()
                var namedToken: String? = null
                var hostname: String? = null
                if (settings.tunnelMode == TunnelMode.NAMED) {
                    if (settings.port == 0) {
                        throw ConfigurationProblem(context.getString(R.string.named_requires_port))
                    }
                    hostname =
                        HostnameValidator.normalize(namedSettings.hostname)
                            ?: throw ConfigurationProblem(context.getString(R.string.invalid_hostname))
                    namedToken =
                        namedTunnelRepository.loadToken()
                            ?: throw ConfigurationProblem(context.getString(R.string.token_required))
                }

                val bindHost = if (settings.allowLanAccess) "0.0.0.0" else "127.0.0.1"
                val serverConfig =
                    ServerConfig(
                        bindHost = bindHost,
                        port = settings.port,
                        secureCookies = true,
                    )
                val dependencies =
                    ServerDependencies(
                        store = shareStore,
                        content = contentSource,
                        passwordHasher = passwordHasher,
                        logger = logger,
                        sessionSecret = ByteArray(32).also(secureRandom::nextBytes),
                        config = serverConfig,
                        clock = clock,
                        imageTransformer = AndroidImageTransformer(),
                    )
                val instance = PocketShareServer(dependencies)
                pendingServer = instance
                val port = instance.start()
                server = instance
                mutableState.value =
                    mutableState.value.copy(
                        localPort = port,
                        mode = settings.tunnelMode,
                        error = null,
                    )

                tunnel.start(
                    TunnelRequest(
                        mode = settings.tunnelMode,
                        localPort = port,
                        namedToken = namedToken,
                        hostname = hostname,
                        dnsServers = cloudflaredRuntime.dnsServers(),
                    ),
                )
                logger.info(TAG, "Sharing started with ${settings.tunnelMode} tunnel on port $port")
            }.exceptionOrNull()

        when (failure) {
            null -> Unit
            is CancellationException -> {
                runCatching { pendingServer?.stop() }
                throw failure
            }
            is PortUnavailableException ->
                failStart(
                    settings.tunnelMode,
                    ConfigurationProblem(context.getString(R.string.error_port_in_use, failure.port)),
                    pendingServer,
                )
            is BindException ->
                failStart(
                    settings.tunnelMode,
                    if (settings.port == 0) {
                        failure
                    } else {
                        ConfigurationProblem(context.getString(R.string.error_port_in_use, settings.port))
                    },
                    pendingServer,
                )
            // Startup failures must never escape this coroutine: an uncaught exception on the
            // service scope would force-close the app. Surface every failure as ERROR instead.
            else -> failStart(settings.tunnelMode, failure, pendingServer)
        }
    }

    private suspend fun failStart(
        mode: TunnelMode,
        error: Throwable,
        pendingServer: PocketShareServer?,
    ) {
        logger.error(TAG, "Unable to start sharing", error)
        runCatching { pendingServer?.stop() }
        server = null
        mutableState.value =
            SharingState(
                phase = SharingPhase.ERROR,
                mode = mode,
                error = safeMessage(error),
            )
    }

    private suspend fun stopInternal(error: String?) {
        if (mutableState.value.phase == SharingPhase.STOPPED && server == null) {
            mutableState.value = mutableState.value.copy(phase = SharingPhase.STOPPED)
            return
        }
        mutableState.value = mutableState.value.copy(phase = SharingPhase.STOPPING, error = error)
        autoStopJob?.cancel()
        autoStopJob = null
        wifiOnlyJob?.cancel()
        wifiOnlyJob = null
        stopProbe()
        runCatching { tunnel.stop() }
            .onFailure { logger.warn(TAG, "Tunnel did not stop cleanly", it) }
        val runningServer = server
        server = null
        runCatching { runningServer?.stop() }
            .onFailure { logger.warn(TAG, "Server did not stop cleanly", it) }
        activeSettings = null
        mutableState.value =
            SharingState(
                phase = if (error == null) SharingPhase.STOPPED else SharingPhase.ERROR,
                mode = mutableState.value.mode,
                error = error,
            )
    }

    private suspend fun failAndStop(reason: String) {
        lifecycleMutex.withLock { stopInternal(error = reason) }
    }

    private suspend fun onTunnelStatus(status: TunnelStatus) {
        when (status) {
            is TunnelStatus.Running -> {
                if (!mutableState.value.isSharing) return
                mutableState.value =
                    mutableState.value.copy(
                        phase = SharingPhase.RUNNING,
                        publicUrl = status.publicUrl,
                        tunnelStatus = status,
                        error = null,
                        startedAtEpochMs = mutableState.value.startedAtEpochMs ?: clock.millis(),
                    )
                scheduleAutoStop()
                restartProbe()
            }

            is TunnelStatus.Degraded -> {
                if (!mutableState.value.isSharing) return
                // The process and URL are still valid; only the edge connection is missing.
                mutableState.value =
                    mutableState.value.copy(
                        phase = SharingPhase.RUNNING,
                        publicUrl = status.publicUrl ?: retainedPublicUrl(mutableState.value),
                        tunnelStatus = status,
                        error = null,
                        startedAtEpochMs = mutableState.value.startedAtEpochMs ?: clock.millis(),
                    )
                scheduleAutoStop()
                restartProbe()
            }

            is TunnelStatus.Starting -> {
                if (!mutableState.value.isSharing) return
                stopProbe()
                mutableState.value =
                    mutableState.value.copy(
                        phase = SharingPhase.STARTING,
                        publicUrl = retainedPublicUrl(mutableState.value),
                        tunnelStatus = status,
                    )
            }

            is TunnelStatus.Reconnecting -> {
                if (!mutableState.value.isSharing) return
                stopProbe()
                mutableState.value =
                    mutableState.value.copy(
                        phase = SharingPhase.STARTING,
                        publicUrl = retainedPublicUrl(mutableState.value),
                        tunnelStatus = status,
                        error = null,
                        reachability = Reachability.UNKNOWN,
                    )
            }

            is TunnelStatus.Failed -> {
                if (mutableState.value.isSharing) {
                    logger.error(TAG, "Tunnel failed: ${status.reason}")
                    stopProbe()
                    failAndStop(status.reason)
                }
            }

            is TunnelStatus.Stopped -> {
                if (mutableState.value.isSharing) {
                    mutableState.value = mutableState.value.copy(tunnelStatus = status)
                }
            }
        }
    }

    private fun retainedPublicUrl(state: SharingState): String? = if (state.mode == TunnelMode.NAMED) state.publicUrl else null

    /**
     * Requests `/healthz` through the public URL, which is the only way to see an edge error
     * such as 1033 while cloudflared still reports a live process. Runs only while a session is
     * live and the URL is known; stops as soon as the session leaves RUNNING.
     */
    private fun restartProbe() {
        val state = mutableState.value
        val target = state.publicUrl
        if (state.phase != SharingPhase.RUNNING || target == null) {
            stopProbe()
            return
        }
        if (probeJob?.isActive == true && probeTarget == target) return
        stopProbe()
        probeTarget = target
        probeJob =
            scope.launch {
                var failures = 0
                while (currentCoroutineContext().isActive) {
                    val result = publicProbe.probe(target)
                    val current = mutableState.value
                    if (current.phase != SharingPhase.RUNNING || current.publicUrl != target) return@launch
                    when (result) {
                        is PublicProbeResult.Http ->
                            if (result.statusCode in 500..599) {
                                failures += 1
                            } else {
                                failures = 0
                                setReachability(Reachability.OK)
                            }

                        // DNS on the phone can fail for reasons unrelated to the tunnel.
                        PublicProbeResult.HostUnresolved -> Unit

                        PublicProbeResult.Failed -> failures += 1
                    }
                    if (failures >= PROBE_FAILURES_BEFORE_UNREACHABLE) {
                        setReachability(Reachability.UNREACHABLE)
                    }
                    delay(if (networkMonitor.status.value.metered) METERED_PROBE_INTERVAL_MILLIS else PROBE_INTERVAL_MILLIS)
                }
            }
    }

    private fun stopProbe() {
        probeJob?.cancel()
        probeJob = null
        probeTarget = null
    }

    private fun setReachability(value: Reachability) {
        mutableState.update { state ->
            if (state.reachability == value) state else state.copy(reachability = value)
        }
    }

    private fun scheduleAutoStop() {
        if (autoStopJob?.isActive == true) return
        val minutes = activeSettings?.autoStopMinutes ?: 0
        if (minutes <= 0) return
        autoStopJob =
            scope.launch {
                delay(minutes.coerceAtMost(24 * 60).toLong() * 60_000L)
                logger.info(TAG, "Auto-stop timer reached")
                stop()
            }
    }

    private fun safeMessage(error: Throwable): String {
        val message =
            when (error) {
                is ConfigurationProblem -> error.message.orEmpty()
                else ->
                    error.message?.takeIf { it.isNotBlank() }
                        ?: error.javaClass.simpleName
            }
        return Redactor.redact(message).take(240)
    }

    private class ConfigurationProblem(
        message: String,
    ) : Exception(message)

    private companion object {
        const val TAG = "Runtime"
        const val WIFI_ONLY_GRACE_MILLIS = 5_000L
        const val PROBE_INTERVAL_MILLIS = 45_000L
        const val METERED_PROBE_INTERVAL_MILLIS = 120_000L
        const val PROBE_FAILURES_BEFORE_UNREACHABLE = 2
    }
}
