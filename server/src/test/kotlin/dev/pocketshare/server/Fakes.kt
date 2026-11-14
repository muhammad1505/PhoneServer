package dev.pocketshare.server

import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.core.model.ShareRecord
import dev.pocketshare.core.share.ContentUnavailableException
import dev.pocketshare.core.share.OpenedContent
import dev.pocketshare.core.share.ShareContentSource
import dev.pocketshare.core.share.ShareStore
import dev.pocketshare.core.share.SharedEntry
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.math.min

class FakeShareStore(
    records: List<ShareRecord>,
) : ShareStore {
    private val byToken = records.associateBy { it.token }.toMutableMap()

    override suspend fun findByToken(token: String): ShareRecord? =
        synchronized(this) {
            byToken[token]
        }

    override suspend fun tryReserveDownload(
        shareId: Long,
        nowEpochMs: Long,
    ): Boolean =
        synchronized(this) {
            val record = byToken.values.firstOrNull { it.id == shareId } ?: return false
            if (!record.isAvailable(nowEpochMs)) return false
            byToken[record.token] = record.copy(downloadCount = record.downloadCount + 1)
            true
        }

    fun record(token: String): ShareRecord? = synchronized(this) { byToken[token] }
}

class FakeContentSource(
    var entries: List<SharedEntry>,
    private val data: Map<String, ByteArray>,
) : ShareContentSource {
    var unavailable: Boolean = false
    var openCalls: Int = 0
    var unexpectedFailure: RuntimeException? = null

    override suspend fun list(share: ShareRecord): List<SharedEntry> {
        if (unavailable) throw ContentUnavailableException("gone")
        unexpectedFailure?.let { throw it }
        return entries
    }

    override suspend fun open(
        share: ShareRecord,
        entryId: String,
    ): OpenedContent? {
        openCalls += 1
        if (unavailable) throw ContentUnavailableException("gone")
        unexpectedFailure?.let { throw it }
        val entry = entries.firstOrNull { it.id == entryId } ?: return null
        val bytes = data[entryId] ?: return null
        return OpenedContent(
            stream = ByteArrayInputStream(bytes),
            sizeBytes = entry.sizeBytes,
            mimeType = entry.mimeType,
            name = entry.name,
        )
    }
}

class LargeSyntheticContentSource(
    private val size: Long,
) : ShareContentSource {
    private val entry =
        SharedEntry(
            id = "big",
            name = "big.bin",
            relativePath = "big.bin",
            sizeBytes = size,
            mimeType = "application/octet-stream",
        )

    override suspend fun list(share: ShareRecord): List<SharedEntry> = listOf(entry)

    override suspend fun open(
        share: ShareRecord,
        entryId: String,
    ): OpenedContent? {
        if (entryId != entry.id) return null
        return OpenedContent(
            stream = SyntheticStream(size),
            sizeBytes = size,
            mimeType = entry.mimeType,
            name = entry.name,
        )
    }

    private class SyntheticStream(
        private val total: Long,
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int {
            if (position >= total) return -1
            val value = (position % 251L).toInt()
            position += 1
            return value
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (position >= total) return -1
            val count = min(len.toLong(), total - position).toInt()
            for (index in 0 until count) {
                b[off + index] = ((position + index) % 251L).toByte()
            }
            position += count
            return count
        }

        override fun skip(n: Long): Long {
            val count = min(n, total - position).coerceAtLeast(0L)
            position += count
            return count
        }
    }
}

fun testRecord(
    token: String,
    id: Long = 1,
    kind: ShareKind = ShareKind.FILES,
    displayName: String = "Test share",
    files: List<dev.pocketshare.core.model.ShareFileRef> =
        listOf(
            dev.pocketshare.core.model
                .ShareFileRef("content://test/file", "hello.txt"),
        ),
    expiresAtEpochMs: Long? = null,
    passwordHash: String? = null,
    passwordSalt: String? = null,
    passwordIterations: Int = 0,
    maxDownloads: Int? = null,
    downloadCount: Int = 0,
    revokedAtEpochMs: Long? = null,
): ShareRecord =
    ShareRecord(
        id = id,
        token = token,
        kind = kind,
        displayName = displayName,
        files = files,
        createdAtEpochMs = 0,
        expiresAtEpochMs = expiresAtEpochMs,
        passwordHash = passwordHash,
        passwordSalt = passwordSalt,
        passwordIterations = passwordIterations,
        maxDownloads = maxDownloads,
        downloadCount = downloadCount,
        revokedAtEpochMs = revokedAtEpochMs,
    )
