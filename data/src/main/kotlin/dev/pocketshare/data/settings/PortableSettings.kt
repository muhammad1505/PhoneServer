package dev.pocketshare.data.settings

import dev.pocketshare.core.model.AppLanguage
import dev.pocketshare.core.model.ShareExpiry
import dev.pocketshare.core.model.TunnelMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Portable subset of the app settings, including the named-tunnel hostname and token so a new
 * phone can start the same tunnel without asking for the token again. The token is written to the
 * export file in plain text, so that file must be treated as a secret.
 */
data class PortableSettings(
    val tunnelMode: TunnelMode? = null,
    val port: Int? = null,
    val wifiOnly: Boolean? = null,
    val autoStopMinutes: Int? = null,
    val defaultExpiry: ShareExpiry? = null,
    val language: AppLanguage? = null,
    val allowLanAccess: Boolean? = null,
    val namedTunnelHostname: String? = null,
    val namedTunnelToken: String? = null,
) {
    fun merge(current: AppSettings): AppSettings =
        AppSettings(
            tunnelMode = tunnelMode ?: current.tunnelMode,
            port = (port ?: current.port).coerceIn(0, 65_535),
            wifiOnly = wifiOnly ?: current.wifiOnly,
            autoStopMinutes = (autoStopMinutes ?: current.autoStopMinutes).coerceIn(0, 24 * 60),
            defaultExpiry = defaultExpiry ?: current.defaultExpiry,
            language = language ?: current.language,
            allowLanAccess = allowLanAccess ?: current.allowLanAccess,
        )
}

/**
 * JSON format used by Settings -> Export/Import. Schema 1 files from older builds stay readable;
 * unknown keys are ignored so a newer export can still be imported without losing the known ones.
 */
object PortableSettingsCodec {
    const val APP = "pocketshare"
    const val SCHEMA = 3

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            prettyPrint = true
            encodeDefaults = true
        }

    fun encode(
        settings: AppSettings,
        namedTunnelHostname: String?,
        namedTunnelToken: String? = null,
    ): String =
        buildJsonObject {
            put("app", APP)
            put("schema", SCHEMA)
            put("tunnelMode", settings.tunnelMode.name)
            put("port", settings.port)
            put("wifiOnly", settings.wifiOnly)
            put("autoStopMinutes", settings.autoStopMinutes)
            put("defaultExpiry", settings.defaultExpiry.name)
            put("language", settings.language.name)
            put("allowLanAccess", settings.allowLanAccess)
            namedTunnelHostname
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { put("namedTunnelHostname", it) }
            namedTunnelToken
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { put("namedTunnelToken", it) }
        }.toString()

    fun decode(raw: String): PortableSettings? {
        val text = raw.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return null
        val root =
            runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrNull()
                ?: return null
        if (root.string("app") != APP) return null
        val schema = root.int("schema") ?: 1
        // Accept newer files too: unknown keys are ignored, so a downgrade still restores the
        // settings it understands instead of failing the whole import.
        if (schema < 1) return null
        return PortableSettings(
            tunnelMode = root.string("tunnelMode")?.let { enumValue<TunnelMode>(it) },
            port = root.int("port")?.coerceIn(0, 65_535),
            wifiOnly = root.boolean("wifiOnly"),
            autoStopMinutes = root.int("autoStopMinutes")?.coerceIn(0, 24 * 60),
            defaultExpiry = root.string("defaultExpiry")?.let { enumValue<ShareExpiry>(it) },
            language = root.string("language")?.let { enumValue<AppLanguage>(it) },
            allowLanAccess = root.boolean("allowLanAccess"),
            namedTunnelHostname = root.string("namedTunnelHostname")?.trim()?.takeIf { it.isNotEmpty() },
            namedTunnelToken = root.string("namedTunnelToken")?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(raw, ignoreCase = true) }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.intOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.booleanOrNull
}
