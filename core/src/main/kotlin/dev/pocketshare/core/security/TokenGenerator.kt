package dev.pocketshare.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Generates 256-bit share tokens and compares them without early exit.
 *
 * Tokens are 32 random bytes encoded as unpadded base64url, i.e. exactly 43 characters.
 */
class TokenGenerator(
    private val random: SecureRandom = SecureRandom(),
) {
    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return ENCODER.encodeToString(bytes)
    }

    companion object {
        const val TOKEN_BYTES: Int = 32
        const val TOKEN_LENGTH: Int = 43
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        private val TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{$TOKEN_LENGTH}$")
        private const val DUMMY_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        fun isWellFormed(token: String): Boolean = TOKEN_PATTERN.matches(token)

        /** Compares two tokens in constant time. [expected] must be a 43-character token. */
        fun constantTimeEquals(
            expected: String,
            actual: String,
        ): Boolean {
            val expectedBytes = expected.toByteArray(Charsets.UTF_8)
            val actualBytes = actual.toByteArray(Charsets.UTF_8)
            if (expectedBytes.size != actualBytes.size) {
                // Normalize the work; the length itself is not secret.
                MessageDigest.isEqual(sha256(expectedBytes), sha256(actualBytes))
                return false
            }
            return MessageDigest.isEqual(expectedBytes, actualBytes)
        }

        /**
         * Performs the same comparison work as a real lookup so a request with a non-existent token
         * does not return measurably faster.
         */
        fun constantTimeMiss(actual: String): Boolean {
            val dummyBytes = DUMMY_TOKEN.toByteArray(Charsets.UTF_8)
            val actualBytes = actual.toByteArray(Charsets.UTF_8)
            MessageDigest.isEqual(dummyBytes, actualBytes)
            return false
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
