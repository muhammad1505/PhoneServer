package dev.pocketshare.server

import java.time.Clock

data class ServerConfig(
    val bindHost: String = "127.0.0.1",
    val port: Int = 0,
    val secureCookies: Boolean = true,
    val sessionTtlMillis: Long = 30L * 60L * 1_000L,
    val maxConcurrentDownloads: Int = 6,
    val generalRequestsPerMinute: Int = 300,
    val passwordAttemptsPerWindow: Int = 5,
    val passwordWindowMillis: Long = 15L * 60L * 1_000L,
    val maxHeaderBytes: Int = 16 * 1024,
    val maxFormBytes: Int = 4 * 1024,
    val maxPreviewDocumentBytes: Long = 8L * 1024L * 1024L,
    val maxPreviewImageBytes: Long = 24L * 1024L * 1024L,
) {
    init {
        require(port in 0..65_535)
        require(maxConcurrentDownloads > 0)
        require(generalRequestsPerMinute > 0)
        require(passwordAttemptsPerWindow > 0)
        require(passwordWindowMillis > 0)
        require(maxHeaderBytes >= 1_024)
        require(maxFormBytes >= 256)
        require(maxPreviewImageBytes > 0)
    }
}

data class ServerDependencies(
    val store: dev.pocketshare.core.share.ShareStore,
    val content: dev.pocketshare.core.share.ShareContentSource,
    val passwordHasher: dev.pocketshare.core.security.PasswordHasher,
    val logger: dev.pocketshare.core.log.AppLogger,
    val sessionSecret: ByteArray,
    val config: ServerConfig = ServerConfig(),
    val clock: Clock = Clock.systemUTC(),
    val imageTransformer: dev.pocketshare.core.image.ImageTransformer? = null,
) {
    init {
        require(sessionSecret.size >= 32) { "Session secret must have at least 256 bits" }
    }
}
