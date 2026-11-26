package dev.pocketshare.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketshare.R
import dev.pocketshare.core.model.AppLanguage
import dev.pocketshare.core.model.ShareExpiry
import dev.pocketshare.core.model.TunnelMode
import dev.pocketshare.core.util.HostnameValidator
import dev.pocketshare.data.settings.AppSettings
import dev.pocketshare.data.settings.NamedTunnelRepository
import dev.pocketshare.data.settings.NamedTunnelSettings
import dev.pocketshare.data.settings.PortableSettings
import dev.pocketshare.data.settings.PortableSettingsCodec
import dev.pocketshare.data.settings.SettingsRepository
import dev.pocketshare.ui.UiMessage
import dev.pocketshare.ui.UiTone
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val namedTunnelRepository: NamedTunnelRepository,
) : ViewModel() {
    private val mutableMessages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages = mutableMessages.asSharedFlow()

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AppSettings(),
        )

    val namedTunnel: StateFlow<NamedTunnelSettings> =
        namedTunnelRepository.settings.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            NamedTunnelSettings(),
        )

    fun saveTunnelDetails(
        mode: TunnelMode,
        portText: String,
        hostnameInput: String,
        tokenInput: String,
    ) {
        viewModelScope.launch {
            val port = parsePort(portText)
            if (port == null) {
                emitMessage(R.string.invalid_port, UiTone.ERROR)
                return@launch
            }
            if (mode == TunnelMode.NAMED) {
                if (port == 0) {
                    emitMessage(R.string.named_requires_port, UiTone.ERROR)
                    return@launch
                }
                val hostname = HostnameValidator.normalize(hostnameInput)
                if (hostname == null) {
                    emitMessage(R.string.invalid_hostname, UiTone.ERROR)
                    return@launch
                }
                val existing = namedTunnelRepository.settings.first()
                if (tokenInput.isBlank() && !existing.hasToken) {
                    emitMessage(R.string.token_required, UiTone.ERROR)
                    return@launch
                }
                namedTunnelRepository.save(hostname, tokenInput.takeIf { it.isNotBlank() })
            }
            settingsRepository.setTunnelMode(mode)
            settingsRepository.setPort(port)
            emitMessage(
                textRes = R.string.settings_saved,
                bodyRes = R.string.settings_saved_body,
            )
        }
    }

    fun clearNamedToken() {
        viewModelScope.launch {
            namedTunnelRepository.clearToken()
            emitMessage(
                textRes = R.string.settings_token_cleared,
                tone = UiTone.INFO,
                bodyRes = R.string.settings_token_cleared_body,
            )
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWifiOnly(enabled) }
    }

    fun setLanAccess(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAllowLanAccess(enabled) }
    }

    fun setAutoStopMinutes(minutes: Int) {
        viewModelScope.launch { settingsRepository.setAutoStopMinutes(minutes) }
    }

    fun setDefaultExpiry(expiry: ShareExpiry) {
        viewModelScope.launch { settingsRepository.setDefaultExpiry(expiry) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.setLanguage(language) }
    }

    /** Portable settings snapshot; deliberately excludes tunnel secrets. */
    suspend fun exportSettings(): String {
        // Read the repository directly instead of relying on a possibly-not-yet-collected state.
        val current = settingsRepository.settings.first()
        val named = namedTunnelRepository.settings.first()
        return PortableSettingsCodec.encode(current, named.hostname)
    }

    fun importSettings(raw: String) {
        viewModelScope.launch {
            val imported = PortableSettingsCodec.decode(raw)
            if (imported == null || imported == PortableSettings()) {
                emitMessage(
                    textRes = R.string.settings_import_failed,
                    tone = UiTone.ERROR,
                    bodyRes = R.string.settings_import_failed_body,
                )
                return@launch
            }

            // Validate everything before writing so a malformed file cannot apply half a snapshot.
            val importedHostname = imported.namedTunnelHostname
            val hostname = importedHostname?.let(HostnameValidator::normalize)
            if (importedHostname != null && hostname == null) {
                emitMessage(
                    textRes = R.string.settings_import_failed,
                    tone = UiTone.ERROR,
                    bodyRes = R.string.invalid_hostname,
                )
                return@launch
            }

            try {
                val current = settings.first()
                // The hostname is portable; the token is not. Keep any token already stored here.
                hostname?.let { namedTunnelRepository.save(it, token = null) }
                val merged = imported.merge(current)
                settingsRepository.apply(merged)

                val named = namedTunnelRepository.settings.first()
                val namedNeedsToken = merged.tunnelMode == TunnelMode.NAMED && !named.hasToken
                emitMessage(
                    textRes = R.string.settings_imported,
                    bodyRes =
                        if (namedNeedsToken) {
                            R.string.settings_imported_named_body
                        } else {
                            R.string.settings_imported_body
                        },
                )
            } catch (_: Exception) {
                emitMessage(
                    textRes = R.string.settings_import_failed,
                    tone = UiTone.ERROR,
                    bodyRes = R.string.settings_import_failed_body,
                )
            }
        }
    }

    private fun parsePort(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        val value = text.toIntOrNull() ?: return null
        return value.takeIf { it in 0..65_535 }
    }

    private fun emitMessage(
        textRes: Int,
        tone: UiTone = UiTone.SUCCESS,
        bodyRes: Int? = null,
    ) {
        mutableMessages.tryEmit(UiMessage(textRes = textRes, tone = tone, bodyRes = bodyRes))
    }
}
