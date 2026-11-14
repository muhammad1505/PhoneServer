package dev.pocketshare.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketshare.R
import dev.pocketshare.core.model.ShareState
import dev.pocketshare.data.log.FileAppLogger
import dev.pocketshare.data.repository.ShareRepository
import dev.pocketshare.runtime.ShareRuntime
import dev.pocketshare.tunnel.CloudflaredRuntime
import dev.pocketshare.ui.UiMessage
import dev.pocketshare.ui.UiTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class DiagnosticsFacts(
    val appVersion: String,
    val binaryPath: String?,
    val binaryPresent: Boolean,
    val cloudflaredVersion: String,
    val dnsServers: String,
    val caBundlePresent: Boolean,
    val notificationsEnabled: Boolean,
    val batteryUnrestricted: Boolean,
    val logSizeBytes: Long,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: FileAppLogger,
    private val cloudflaredRuntime: CloudflaredRuntime,
    shareRepository: ShareRepository,
    runtime: ShareRuntime,
) : ViewModel() {
    val runtimeState = runtime.state

    private val mutableFacts = MutableStateFlow(DiagnosticsFacts("?", null, false, "-", "-", false, false, false, 0))
    val facts: StateFlow<DiagnosticsFacts> = mutableFacts.asStateFlow()

    private val mutableLog = MutableStateFlow("")
    val log: StateFlow<String> = mutableLog.asStateFlow()

    private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages = mutableMessages.asSharedFlow()

    private val mutableLive = MutableStateFlow(true)
    val live: StateFlow<Boolean> = mutableLive.asStateFlow()

    private val mutableNow = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = mutableNow.asStateFlow()

    val activeShares: StateFlow<Int> =
        combine(shareRepository.shares, mutableNow) { shares, at ->
            shares.count { it.state(at) == ShareState.ACTIVE }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        refresh()
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(LIVE_INTERVAL_MS)
                mutableNow.value = System.currentTimeMillis()
                if (!mutableLive.value) continue
                mutableLog.value = readLogText()
                ticks++
                if (ticks % FACT_REFRESH_TICKS == 0) {
                    refreshFacts()
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshFacts()
            mutableLog.value = readLogText()
        }
    }

    fun setLive(enabled: Boolean) {
        mutableLive.value = enabled
        if (enabled) refresh()
    }

    fun export(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = logger.exportTo(uri)
            mutableMessages.tryEmit(
                if (success) {
                    UiMessage(R.string.diag_exported)
                } else {
                    UiMessage(R.string.diag_export_failed, UiTone.ERROR)
                },
            )
        }
    }

    fun clearLog() {
        viewModelScope.launch(Dispatchers.IO) {
            logger.clear()
            refresh()
            mutableMessages.tryEmit(UiMessage(R.string.diag_clear_done))
        }
    }

    private suspend fun refreshFacts() {
        val facts = withContext(Dispatchers.IO) { readFacts() }
        mutableFacts.value = facts
    }

    private suspend fun readLogText(): String =
        withContext(Dispatchers.IO) {
            logger.recentLines(MAX_LOG_LINES).ifBlank { context.getString(R.string.diag_logs_empty) }
        }

    private fun readFacts(): DiagnosticsFacts {
        val caBundle = File(context.filesDir, "cloudflared/ca-bundle.pem")
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val power = context.getSystemService(PowerManager::class.java)
        val batteryUnrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        val dns = cloudflaredRuntime.dnsServers()
        return DiagnosticsFacts(
            appVersion = appVersion(),
            binaryPath = cloudflaredRuntime.binary?.absolutePath,
            binaryPresent = cloudflaredRuntime.binary != null,
            cloudflaredVersion = cloudflaredRuntime.versionLabel,
            dnsServers = if (dns.isEmpty()) "-" else dns.joinToString(", "),
            caBundlePresent = caBundle.isFile && caBundle.length() > 0,
            notificationsEnabled = notifications,
            batteryUnrestricted = batteryUnrestricted,
            logSizeBytes = logger.sizeBytes(),
        )
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String =
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
        } catch (_: Exception) {
            "?"
        }

    private companion object {
        const val MAX_LOG_LINES = 400
        const val LIVE_INTERVAL_MS = 1_000L
        const val FACT_REFRESH_TICKS = 5
    }
}
