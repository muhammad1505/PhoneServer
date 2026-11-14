package dev.pocketshare.data.content

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketshare.core.http.FileNames
import dev.pocketshare.core.log.AppLogger
import dev.pocketshare.core.model.ShareFileRef
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.core.model.ShareRecord
import dev.pocketshare.core.share.ContentUnavailableException
import dev.pocketshare.core.share.OpenedContent
import dev.pocketshare.core.share.ShareContentSource
import dev.pocketshare.core.share.SharedEntry
import dev.pocketshare.data.repository.opaqueDocumentId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SAF-backed content source. Clients never provide paths: the server only knows opaque ids that
 * this class generated from documents discovered inside the selected tree.
 */
@Singleton
class SafShareContentSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger,
) : ShareContentSource {
    private val resolver: ContentResolver = context.contentResolver

    private data class ResolvedEntry(
        val entry: SharedEntry,
        val uri: Uri,
    )

    private data class CachedListing(
        val entries: List<ResolvedEntry>,
        val createdAtMs: Long,
    )

    private val cache =
        object : LinkedHashMap<Long, CachedListing>(MAX_CACHED_SHARES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedListing>?): Boolean = size > MAX_CACHED_SHARES
        }

    override suspend fun list(share: ShareRecord): List<SharedEntry> =
        withContext(Dispatchers.IO) {
            loadListing(share, force = false).map { it.entry }
        }

    override suspend fun open(
        share: ShareRecord,
        entryId: String,
    ): OpenedContent? =
        withContext(Dispatchers.IO) {
            var resolved =
                synchronized(cache) {
                    cache[share.id]?.entries?.firstOrNull { it.entry.id == entryId }
                }
            if (resolved == null) {
                resolved =
                    runCatching { loadListing(share, force = true) }
                        .getOrElse {
                            logger.warn(TAG, "Unable to enumerate content for download", it)
                            return@withContext null
                        }.firstOrNull { it.entry.id == entryId }
            }
            if (resolved == null) return@withContext null

            openResolved(resolved) ?: run {
                // The provider may have renamed/moved the document after our listing was cached.
                val refreshed =
                    runCatching { loadListing(share, force = true) }
                        .getOrElse {
                            logger.warn(TAG, "Unable to refresh content after open failure", it)
                            return@withContext null
                        }.firstOrNull { it.entry.id == entryId }
                if (refreshed == null) null else openResolved(refreshed)
            }
        }

    private fun openResolved(resolved: ResolvedEntry): OpenedContent? =
        try {
            val stream = resolver.openInputStream(resolved.uri)
            if (stream == null) {
                null
            } else {
                OpenedContent(
                    stream = stream,
                    sizeBytes = resolved.entry.sizeBytes,
                    mimeType = resolved.entry.mimeType,
                    name = resolved.entry.name,
                )
            }
        } catch (error: SecurityException) {
            logger.warn(TAG, "Read permission no longer granted", error)
            null
        } catch (error: FileNotFoundException) {
            logger.warn(TAG, "Shared document disappeared", error)
            null
        } catch (error: IOException) {
            logger.warn(TAG, "Unable to open shared document", error)
            null
        }

    private fun loadListing(
        share: ShareRecord,
        force: Boolean,
    ): List<ResolvedEntry> {
        val now = System.currentTimeMillis()
        if (!force) {
            val cached =
                synchronized(cache) {
                    cache[share.id]?.takeIf { now - it.createdAtMs <= CACHE_TTL_MS }
                }
            if (cached != null) return cached.entries
        }

        val entries =
            when (share.kind) {
                ShareKind.FILES -> listSelectedFiles(share.files)
                ShareKind.FOLDER -> listFolder(share)
            }
        if (entries.isEmpty()) {
            throw ContentUnavailableException("No readable files remain in this share")
        }
        synchronized(cache) {
            cache[share.id] = CachedListing(entries, now)
        }
        return entries
    }

    private fun listSelectedFiles(files: List<ShareFileRef>): List<ResolvedEntry> {
        val result = mutableListOf<ResolvedEntry>()
        val seen = mutableSetOf<String>()
        files.forEach { ref ->
            if (!seen.add(ref.uri)) return@forEach
            val uri = Uri.parse(ref.uri)
            val document =
                try {
                    DocumentFile.fromSingleUri(context, uri)
                } catch (error: SecurityException) {
                    logger.warn(TAG, "Cannot read selected document", error)
                    null
                }
            if (document == null || !document.exists() || !document.canRead()) {
                logger.warn(TAG, "Selected document is missing or unreadable")
                return@forEach
            }
            result +=
                ResolvedEntry(
                    entry = buildEntry(uri, document, FileNames.displayName(document.name, ref.name), ref.name),
                    uri = uri,
                )
        }
        return result.sortedBy { it.entry.relativePath.lowercase() }
    }

    private fun listFolder(share: ShareRecord): List<ResolvedEntry> {
        val rootUri =
            share.files
                .firstOrNull()
                ?.uri
                ?.let(Uri::parse)
                ?: throw ContentUnavailableException("Folder share has no URI")
        val root =
            try {
                DocumentFile.fromTreeUri(context, rootUri)
            } catch (error: SecurityException) {
                logger.warn(TAG, "Cannot access shared folder", error)
                null
            }
        if (root == null || !root.exists() || !root.canRead() || !root.isDirectory) {
            throw ContentUnavailableException("Shared folder is missing or permission was revoked")
        }

        val treeDocumentId =
            try {
                DocumentsContract.getTreeDocumentId(rootUri)
            } catch (error: IllegalArgumentException) {
                throw ContentUnavailableException("Shared folder URI is not a document tree", error)
            }
        val output = mutableListOf<ResolvedEntry>()
        val visited = mutableSetOf<String>()
        walkDirectory(rootUri, treeDocumentId, prefix = "", depth = 0, output = output, visited = visited)
        return output.sortedBy { it.entry.relativePath.lowercase() }
    }

    // Children are queried directly: androidx's TreeDocumentFile.listFiles() swallows every
    // exception and returns an empty list, which would turn a dead provider into a share that
    // silently looks empty. Providers can also throw arbitrary runtime exceptions (buggy
    // third-party apps), so a fault at the root surfaces while nested folders degrade.
    @Suppress("TooGenericExceptionCaught")
    private fun walkDirectory(
        treeUri: Uri,
        documentId: String,
        prefix: String,
        depth: Int,
        output: MutableList<ResolvedEntry>,
        visited: MutableSet<String>,
    ) {
        if (depth > MAX_DEPTH || output.size >= MAX_ENTRIES) return
        val children =
            try {
                listChildren(treeUri, documentId)
            } catch (error: SecurityException) {
                logger.warn(TAG, "Cannot list a shared folder", error)
                if (depth == 0) throw ContentUnavailableException("Shared folder permission was revoked", error)
                return
            } catch (error: IOException) {
                logger.warn(TAG, "Provider failed while listing a folder", error)
                if (depth == 0) throw ContentUnavailableException("Shared folder cannot be read", error)
                return
            } catch (error: RuntimeException) {
                logger.warn(TAG, "Provider failed while listing a folder", error)
                if (depth == 0) throw error
                return
            }

        children.sortedBy { it.name.lowercase() }.forEach { child ->
            if (output.size >= MAX_ENTRIES) return@forEach
            if (!visited.add(child.uri.toString())) return@forEach
            val name = FileNames.displayName(child.name, "file")
            if (child.isDirectory) {
                walkDirectory(treeUri, child.documentId, "$prefix$name/", depth + 1, output, visited)
            } else {
                output +=
                    ResolvedEntry(
                        entry =
                            SharedEntry(
                                id = opaqueDocumentId(child.uri.toString()),
                                name = name,
                                relativePath = FileNames.displayName("$prefix$name", name),
                                sizeBytes = child.sizeBytes,
                                mimeType = child.mimeType?.takeIf { it.isNotBlank() } ?: DEFAULT_MIME_TYPE,
                            ),
                        uri = child.uri,
                    )
            }
        }
    }

    private data class DocumentRow(
        val documentId: String,
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long?,
        val mimeType: String?,
    )

    private fun listChildren(
        treeUri: Uri,
        parentDocumentId: String,
    ): List<DocumentRow> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
            )
        val cursor =
            resolver.query(childrenUri, projection, null, null, null)
                ?: throw FileNotFoundException("Provider returned no listing")
        return cursor.use { listing ->
            buildList {
                while (listing.moveToNext()) {
                    val id = listing.getString(DOCUMENT_ID_COLUMN) ?: continue
                    val mimeType = listing.getString(MIME_TYPE_COLUMN)
                    add(
                        DocumentRow(
                            documentId = id,
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            name = listing.getString(NAME_COLUMN).orEmpty(),
                            isDirectory = mimeType == Document.MIME_TYPE_DIR,
                            sizeBytes = if (listing.isNull(SIZE_COLUMN)) null else listing.getLong(SIZE_COLUMN),
                            mimeType = mimeType,
                        ),
                    )
                }
            }
        }
    }

    private fun buildEntry(
        uri: Uri,
        document: DocumentFile,
        name: String,
        relativeName: String,
    ): SharedEntry =
        SharedEntry(
            id = opaqueDocumentId(uri.toString()),
            name = name,
            relativePath = FileNames.displayName(relativeName, name),
            sizeBytes = querySize(uri),
            mimeType = mimeType(uri, document),
        )

    private fun querySize(uri: Uri): Long? {
        try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
            }
        } catch (_: Exception) {
            // Fall through to OpenableColumns.
        }
        try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
            }
        } catch (_: Exception) {
            // Size is unknown for this provider.
        }
        return null
    }

    private fun mimeType(
        uri: Uri,
        document: DocumentFile,
    ): String {
        val documentType =
            try {
                document.type
            } catch (_: IllegalStateException) {
                null
            }
        return documentType?.takeIf { it.isNotBlank() }
            ?: resolver.getType(uri)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MIME_TYPE
    }

    private companion object {
        const val TAG = "SafContent"
        const val CACHE_TTL_MS = 15_000L
        const val MAX_CACHED_SHARES = 8
        const val MAX_DEPTH = 20
        const val MAX_ENTRIES = 5_000
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
        const val DOCUMENT_ID_COLUMN = 0
        const val NAME_COLUMN = 1
        const val MIME_TYPE_COLUMN = 2
        const val SIZE_COLUMN = 3
    }
}
