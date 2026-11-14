package dev.pocketshare.core.security

import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Issues and verifies short-lived, share-bound cookies after a successful password check.
 *
 * The cookie contains a signed payload (version, share id, expiry) and no password material.
 */
class SessionTokenSigner(
    secret: ByteArray,
    private val clock: Clock = Clock.systemUTC(),
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private val secretKey = SecretKeySpec(secret.copyOf(), MAC_ALGORITHM)

    init {
        require(secret.size >= 32) { "Session secret must be at least 256 bits" }
        require(ttlMillis in 60_000..86_400_000) { "Invalid session lifetime" }
    }

    fun issue(shareId: Long): String {
        val expiresAt = clock.millis() + ttlMillis
        val payload = "$PAYLOAD_VERSION:$shareId:$expiresAt"
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        return "${encoder.encodeToString(payloadBytes)}.${encoder.encodeToString(mac(payloadBytes))}"
    }

    fun isValid(
        value: String?,
        shareId: Long,
    ): Boolean {
        if (value.isNullOrBlank()) return false
        val parts = value.split('.')
        if (parts.size != 2) return false
        return try {
            val payloadBytes = decoder.decode(parts[0])
            val suppliedMac = decoder.decode(parts[1])
            val expectedMac = mac(payloadBytes)
            if (!MessageDigest.isEqual(expectedMac, suppliedMac)) return false

            val payload = String(payloadBytes, Charsets.UTF_8)
            val fields = payload.split(':')
            if (fields.size != 3 || fields[0] != PAYLOAD_VERSION) return false
            if (fields[1].toLongOrNull() != shareId) return false
            val expiresAt = fields[2].toLongOrNull() ?: return false
            clock.millis() < expiresAt
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Issues a ticket bound to an arbitrary scope, e.g. a single shared entry during playback.
     * Scoped tickets never satisfy [isValid] and session cookies never satisfy [isValidScoped].
     */
    fun issueScoped(scope: String): String {
        val expiresAt = clock.millis() + ttlMillis
        val encodedScope = encoder.encodeToString(scope.toByteArray(Charsets.UTF_8))
        val payload = "$SCOPED_PAYLOAD_VERSION:$encodedScope:$expiresAt"
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        return "${encoder.encodeToString(payloadBytes)}.${encoder.encodeToString(mac(payloadBytes))}"
    }

    fun isValidScoped(
        value: String?,
        scope: String,
    ): Boolean {
        if (value.isNullOrBlank()) return false
        val parts = value.split('.')
        if (parts.size != 2) return false
        return try {
            val payloadBytes = decoder.decode(parts[0])
            val suppliedMac = decoder.decode(parts[1])
            val expectedMac = mac(payloadBytes)
            if (!MessageDigest.isEqual(expectedMac, suppliedMac)) return false

            val payload = String(payloadBytes, Charsets.UTF_8)
            val fields = payload.split(':')
            if (fields.size != 3 || fields[0] != SCOPED_PAYLOAD_VERSION) return false
            val suppliedScope = String(decoder.decode(fields[1]), Charsets.UTF_8)
            if (suppliedScope != scope) return false
            val expiresAt = fields[2].toLongOrNull() ?: return false
            clock.millis() < expiresAt
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun mac(payload: ByteArray): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(secretKey)
        return mac.doFinal(payload)
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 30L * 60L * 1_000L
        private const val MAC_ALGORITHM = "HmacSHA256"
        private const val PAYLOAD_VERSION = "v1"
        private const val SCOPED_PAYLOAD_VERSION = "v2"
        private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder: Base64.Decoder = Base64.getUrlDecoder()
    }
}
