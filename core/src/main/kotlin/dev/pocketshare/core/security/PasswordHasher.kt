package dev.pocketshare.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PasswordHash(
    val hashBase64: String,
    val saltBase64: String,
    val iterations: Int,
)

/**
 * PBKDF2-HMAC-SHA256 password verification with a per-share random salt.
 *
 * The default work factor is intentionally high. Android's own rate limiter additionally slows
 * password guessing down.
 */
class PasswordHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val saltLengthBytes: Int = 16,
    private val keyLengthBits: Int = 256,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Invalid PBKDF2 iteration count" }
        require(saltLengthBytes in 16..64) { "Invalid salt length" }
        require(keyLengthBits in 128..512) { "Invalid key length" }
    }

    fun hash(password: String): PasswordHash {
        require(password.isNotEmpty()) { "Password must not be empty" }
        require(password.length <= MAX_PASSWORD_LENGTH) { "Password is too long" }
        val salt = ByteArray(saltLengthBytes).also(random::nextBytes)
        val derived = derive(password, salt, iterations)
        return PasswordHash(
            hashBase64 = encoder.encodeToString(derived),
            saltBase64 = encoder.encodeToString(salt),
            iterations = iterations,
        )
    }

    fun verify(
        password: String,
        hashBase64: String,
        saltBase64: String,
        iterations: Int,
    ): Boolean {
        if (password.isEmpty() || password.length > MAX_PASSWORD_LENGTH) return false
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) return false
        return try {
            val expected = decoder.decode(hashBase64)
            val salt = decoder.decode(saltBase64)
            if (expected.isEmpty() || salt.size !in 16..64) return false
            val actual = derive(password, salt, iterations)
            MessageDigest.isEqual(expected, actual)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val DEFAULT_ITERATIONS: Int = 210_000
        const val MIN_ITERATIONS: Int = 1_000
        const val MAX_ITERATIONS: Int = 2_000_000
        const val MAX_PASSWORD_LENGTH: Int = 256
        private const val ALGORITHM = "PBKDF2WithHmacSHA256"
        private val encoder: Base64.Encoder = Base64.getEncoder()
        private val decoder: Base64.Decoder = Base64.getDecoder()
    }
}
