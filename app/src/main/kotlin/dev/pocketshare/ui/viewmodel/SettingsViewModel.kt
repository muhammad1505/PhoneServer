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
import org.json.JSONObject
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
    fun exportSettings(): String {
        val current = settings.value
        val json =
            JSONObject()
                .put("app", EXPORT_APP)
                .put("schema", EXPORT_SCHEMA)
                .put("tunnelMode", current.tunnelMode.name)
                .put("port", current.port)
                .put("wifiOnly", current.wifiOnly)
                .put("autoStopMinutes", current.autoStopMinutes)
                .put("defaultExpiry", current.defaultExpiry.name)
                .put("language", current.language.name)
                .put("allowLanAccess", current.allowLanAccess)
        return json.toString(2)
    }

    fun importSettings(raw: String) {
        viewModelScope.launch {
            val json =
                try {
                    JSONObject(raw)
                } catch (_: Exception) {
                    null
                }
            if (json == null || json.optString("app") != EXPORT_APP) {
                emitMessage(
                    textRes = R.string.settings_import_failed,
                    tone = UiTone.ERROR,
                    bodyRes = R.string.settings_import_failed_body,
                )
                return@launch
            }

            val current = settings.first()
            val importedMode = enumOrNull<TunnelMode>(json.optString("tunnelMode"))
            // A named tunnel needs the token that stays on this device, so only switch when present.
            val namedReady = namedTunnelRepository.settings.first().hasToken
            val mode = if (importedMode == TunnelMode.NAMED && !namedReady) current.tunnelMode else importedMode
            mode?.let { settingsRepository.setTunnelMode(it) }
            settingsRepository.setPort(json.optInt("port", current.port).coerceIn(0, 65_535))
            settingsRepository.setWifiOnly(json.optBoolean("wifiOnly", current.wifiOnly))
            settingsRepository.setAutoStopMinutes(
                json.optInt("autoStopMinutes", current.autoStopMinutes).coerceIn(0, 24 * 60),
            )
            enumOrNull<ShareExpiry>(json.optString("defaultExpiry"))
                ?.let { settingsRepository.setDefaultExpiry(it) }
            enumOrNull<AppLanguage>(json.optString("language"))
                ?.let { settingsRepository.setLanguage(it) }
            settingsRepository.setAllowLanAccess(json.optBoolean("allowLanAccess", current.allowLanAccess))
            emitMessage(
                textRes = R.string.settings_imported,
                bodyRes = R.string.settings_imported_body,
            )
        }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(raw: String): T? =
        raw.takeIf { it.isNotBlank() }?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

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

    private companion object {
        const val EXPORT_APP = "pocketshare"
        const val EXPORT_SCHEMA = 1
    }
}
