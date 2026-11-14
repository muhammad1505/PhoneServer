package dev.pocketshare.core.model

/** How the shared content is stored. */
enum class ShareKind {
    FILES,
    FOLDER,
}

/** A persisted reference to a SAF document. Only content URIs are stored, never raw paths. */
data class ShareFileRef(
    val uri: String,
    val name: String,
)

/**
 * Full share record. Contains password verifier material and is therefore only used inside the
 * server/data boundary, never rendered directly by the UI.
 */
data class ShareRecord(
    val id: Long,
    val token: String,
    val kind: ShareKind,
    val displayName: String,
    val files: List<ShareFileRef>,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val passwordHash: String?,
    val passwordSalt: String?,
    val passwordIterations: Int,
    val maxDownloads: Int?,
    val downloadCount: Int,
    val revokedAtEpochMs: Long?,
) {
    val hasPassword: Boolean
        get() = !passwordHash.isNullOrBlank() && !passwordSalt.isNullOrBlank()

    fun state(nowEpochMs: Long): ShareState =
        shareState(
            nowEpochMs = nowEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            revokedAtEpochMs = revokedAtEpochMs,
            maxDownloads = maxDownloads,
            downloadCount = downloadCount,
        )

    fun isAvailable(nowEpochMs: Long): Boolean = state(nowEpochMs) == ShareState.ACTIVE
}

/** UI-safe projection of a share. It never contains the password hash or salt. */
data class ShareSummary(
    val id: Long,
    val token: String,
    val kind: ShareKind,
    val displayName: String,
    val fileCount: Int,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val hasPassword: Boolean,
    val maxDownloads: Int?,
    val downloadCount: Int,
    val revokedAtEpochMs: Long?,
    /** Display names and SAF URIs for file shares. Folder shares expose the single tree URI. */
    val files: List<ShareFileRef> = emptyList(),
) {
    fun state(nowEpochMs: Long): ShareState =
        shareState(
            nowEpochMs = nowEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            revokedAtEpochMs = revokedAtEpochMs,
            maxDownloads = maxDownloads,
            downloadCount = downloadCount,
        )
}

enum class ShareState {
    ACTIVE,
    EXPIRED,
    REVOKED,
    EXHAUSTED,
}

fun shareState(
    nowEpochMs: Long,
    expiresAtEpochMs: Long?,
    revokedAtEpochMs: Long?,
    maxDownloads: Int?,
    downloadCount: Int,
): ShareState =
    when {
        revokedAtEpochMs != null -> ShareState.REVOKED
        expiresAtEpochMs != null && nowEpochMs >= expiresAtEpochMs -> ShareState.EXPIRED
        maxDownloads != null && downloadCount >= maxDownloads -> ShareState.EXHAUSTED
        else -> ShareState.ACTIVE
    }

/** Expiry choices exposed by the app. */
enum class ShareExpiry(
    val durationMillis: Long?,
) {
    HOUR_1(60L * 60L * 1_000L),
    HOURS_24(24L * 60L * 60L * 1_000L),
    DAYS_7(7L * 24L * 60L * 60L * 1_000L),
    UNLIMITED(null),
    ;

    fun expiresAt(nowEpochMs: Long): Long? = durationMillis?.let { nowEpochMs + it }
}

enum class TunnelMode {
    QUICK,
    NAMED,
}

enum class AppLanguage(
    val languageTag: String?,
) {
    SYSTEM(null),
    INDONESIAN("in"),
    ENGLISH("en"),
}
