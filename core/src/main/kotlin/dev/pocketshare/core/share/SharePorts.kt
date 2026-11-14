package dev.pocketshare.core.share

import dev.pocketshare.core.model.ShareRecord
import java.io.InputStream

/** Metadata for one downloadable entry. The id is opaque and never a filesystem path. */
data class SharedEntry(
    val id: String,
    val name: String,
    val relativePath: String,
    val sizeBytes: Long?,
    val mimeType: String,
)

class OpenedContent(
    val stream: InputStream,
    val sizeBytes: Long?,
    val mimeType: String,
    val name: String,
)

/** Thrown when the selected folder/file cannot be read any more (deleted or permission revoked). */
class ContentUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Content port implemented by the data layer using SAF. The server never accepts filesystem paths
 * from clients; it can only resolve opaque ids that were produced by [list].
 */
interface ShareContentSource {
    suspend fun list(share: ShareRecord): List<SharedEntry>

    suspend fun open(
        share: ShareRecord,
        entryId: String,
    ): OpenedContent?
}

/** Share persistence port used by the HTTP server. */
interface ShareStore {
    suspend fun findByToken(token: String): ShareRecord?

    /**
     * Atomically reserves one download slot. Returns false when the share is expired, revoked or has
     * reached its download limit. A reservation is consumed even if the client disconnects later.
     */
    suspend fun tryReserveDownload(
        shareId: Long,
        nowEpochMs: Long,
    ): Boolean
}
