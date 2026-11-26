package dev.pocketshare.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pocketshare.core.log.AppLogger
import dev.pocketshare.core.model.AppLanguage
import dev.pocketshare.core.model.ShareExpiry
import dev.pocketshare.core.model.TunnelMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val tunnelMode: TunnelMode = TunnelMode.QUICK,
    val port: Int = 0,
    val wifiOnly: Boolean = false,
    val autoStopMinutes: Int = 0,
    val defaultExpiry: ShareExpiry = ShareExpiry.HOURS_24,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val allowLanAccess: Boolean = false,
) {
    val hasFixedPort: Boolean
        get() = port != 0
}

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val logger: AppLogger,
) {
    val settings: Flow<AppSettings> =
        dataStore.data
            .catch { error ->
                if (error is IOException) {
                    logger.warn(TAG, "Unable to read settings; using defaults", error)
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }.map { preferences ->
                AppSettings(
                    tunnelMode = enumValue(preferences[KEYS.tunnelMode], TunnelMode.QUICK),
                    port = (preferences[KEYS.port] ?: 0).coerceIn(0, 65_535),
                    wifiOnly = preferences[KEYS.wifiOnly] ?: false,
                    autoStopMinutes = (preferences[KEYS.autoStopMinutes] ?: 0).coerceAtLeast(0),
                    defaultExpiry = enumValue(preferences[KEYS.defaultExpiry], ShareExpiry.HOURS_24),
                    language = enumValue(preferences[KEYS.language], AppLanguage.SYSTEM),
                    allowLanAccess = preferences[KEYS.allowLanAccess] ?: false,
                )
            }

    suspend fun setTunnelMode(mode: TunnelMode) {
        dataStore.edit { it[KEYS.tunnelMode] = mode.name }
    }

    suspend fun setPort(port: Int) {
        dataStore.edit { it[KEYS.port] = port.coerceIn(0, 65_535) }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        dataStore.edit { it[KEYS.wifiOnly] = enabled }
    }

    suspend fun setAutoStopMinutes(minutes: Int) {
        dataStore.edit { it[KEYS.autoStopMinutes] = minutes.coerceIn(0, 24 * 60) }
    }

    suspend fun setDefaultExpiry(expiry: ShareExpiry) {
        dataStore.edit { it[KEYS.defaultExpiry] = expiry.name }
    }

    suspend fun setLanguage(language: AppLanguage) {
        dataStore.edit { it[KEYS.language] = language.name }
    }

    suspend fun setAllowLanAccess(enabled: Boolean) {
        dataStore.edit { it[KEYS.allowLanAccess] = enabled }
    }

    /**
     * Writes a complete settings snapshot in one transaction so an import cannot leave the phone
     * with a mix of old and new values when one write fails.
     */
    suspend fun apply(settings: AppSettings) {
        dataStore.edit { preferences ->
            preferences[KEYS.tunnelMode] = settings.tunnelMode.name
            preferences[KEYS.port] = settings.port.coerceIn(0, 65_535)
            preferences[KEYS.wifiOnly] = settings.wifiOnly
            preferences[KEYS.autoStopMinutes] = settings.autoStopMinutes.coerceIn(0, 24 * 60)
            preferences[KEYS.defaultExpiry] = settings.defaultExpiry.name
            preferences[KEYS.language] = settings.language.name
            preferences[KEYS.allowLanAccess] = settings.allowLanAccess
        }
    }

    suspend fun changelogSeenVersion(): String? = dataStore.data.first()[KEYS.changelogSeenVersion]

    suspend fun setChangelogSeenVersion(version: String) {
        dataStore.edit { it[KEYS.changelogSeenVersion] = version }
    }

    private inline fun <reified T : Enum<T>> enumValue(
        raw: String?,
        fallback: T,
    ): T = raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: fallback

    private companion object {
        const val TAG = "Settings"
    }
}

private object KEYS {
    val tunnelMode = stringPreferencesKey("tunnel_mode")
    val port = intPreferencesKey("port")
    val wifiOnly = booleanPreferencesKey("wifi_only")
    val autoStopMinutes = intPreferencesKey("auto_stop_minutes")
    val defaultExpiry = stringPreferencesKey("default_expiry")
    val language = stringPreferencesKey("language")
    val allowLanAccess = booleanPreferencesKey("allow_lan_access")
    val changelogSeenVersion = stringPreferencesKey("changelog_seen_version")
}
