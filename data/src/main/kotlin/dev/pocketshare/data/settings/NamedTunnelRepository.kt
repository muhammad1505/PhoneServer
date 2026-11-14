package dev.pocketshare.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pocketshare.core.log.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class NamedTunnelSettings(
    val hostname: String = "",
    val hasToken: Boolean = false,
) {
    val isReady: Boolean
        get() = hostname.isNotBlank() && hasToken
}

/**
 * Hostname is not secret and stored in plain preferences. The Cloudflare tunnel token is stored
 * encrypted by [SecretCipher] and can only be read back through [loadToken].
 */
@Singleton
class NamedTunnelRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val logger: AppLogger,
) {
    val settings: Flow<NamedTunnelSettings> =
        dataStore.data.map { preferences ->
            NamedTunnelSettings(
                hostname = preferences[KEY_HOSTNAME].orEmpty(),
                hasToken = !preferences[KEY_TOKEN].isNullOrBlank(),
            )
        }

    suspend fun save(
        hostname: String,
        token: String?,
    ) {
        val encrypted =
            token?.takeIf { it.isNotBlank() }?.let { plain ->
                cipher.encrypt(plain)
            }
        dataStore.edit { preferences ->
            preferences[KEY_HOSTNAME] = hostname
            if (token != null) {
                if (encrypted != null) {
                    preferences[KEY_TOKEN] = encrypted
                } else if (token.isBlank()) {
                    preferences.remove(KEY_TOKEN)
                }
            }
        }
    }

    suspend fun clearToken() {
        dataStore.edit { it.remove(KEY_TOKEN) }
        runCatching { cipher.deleteKey() }
            .onFailure { logger.warn(TAG, "Failed to delete Keystore key", it) }
    }

    suspend fun loadToken(): String? {
        val encrypted = dataStore.data.map { it[KEY_TOKEN] }.first()
        if (encrypted.isNullOrBlank()) return null
        return cipher.decrypt(encrypted).also { token ->
            if (token == null) logger.warn(TAG, "Stored tunnel token could not be decrypted")
        }
    }

    private companion object {
        const val TAG = "NamedTunnel"
        val KEY_HOSTNAME = stringPreferencesKey("named_tunnel_hostname")
        val KEY_TOKEN = stringPreferencesKey("named_tunnel_token_encrypted")
    }
}
