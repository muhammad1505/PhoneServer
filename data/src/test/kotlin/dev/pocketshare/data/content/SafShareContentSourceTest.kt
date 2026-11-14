package dev.pocketshare.data.content

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import dev.pocketshare.core.log.NoopLogger
import dev.pocketshare.core.model.ShareFileRef
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.core.model.ShareRecord
import dev.pocketshare.core.share.ContentUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Exercises the SAF listing paths that decide which provider failures are treated as "gone". */
@RunWith(RobolectricTestRunner::class)
// API 36 sandboxes require JDK 21; CI runs the tests on JDK 17.
@Config(sdk = [35])
class SafShareContentSourceTest {
    private lateinit var provider: FakeDocumentsProvider
    private lateinit var source: SafShareContentSource

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, AUTHORITY)
        provider.reset()
        provider.addDocument(DocumentEntry(id = ROOT_ID, name = "shared", mimeType = Document.MIME_TYPE_DIR))
        source = SafShareContentSource(RuntimeEnvironment.getApplication(), NoopLogger)
    }

    @Test
    fun `folder listing exposes readable files`() =
        runBlocking {
            provider.addDocument(DocumentEntry("a", "a.txt", "text/plain", sizeBytes = 3L, content = "abc"))
            provider.addDocument(DocumentEntry("b", "b.txt", "text/plain", sizeBytes = 1L, content = "b"))
            provider.setChildren(ROOT_ID, "a", "b")

            val entries = source.list(folderShare())

            assertEquals(listOf("a.txt", "b.txt"), entries.map { it.name })
            assertEquals(listOf(3L, 1L), entries.map { it.sizeBytes })
            assertEquals("text/plain", entries.first().mimeType)
        }

    @Test
    fun `folder listing descends into nested folders`() =
        runBlocking {
            provider.addDocument(DocumentEntry("a", "a.txt", "text/plain", sizeBytes = 3L, content = "abc"))
            provider.addDocument(DocumentEntry("sub", "sub", Document.MIME_TYPE_DIR))
            provider.addDocument(DocumentEntry("c", "c.txt", "text/plain", sizeBytes = 2L, content = "hi"))
            provider.setChildren(ROOT_ID, "a", "sub")
            provider.setChildren("sub", "c")

            val entries = source.list(folderShare())

            assertEquals(listOf("a.txt", "sub/c.txt"), entries.map { it.relativePath })
        }

    @Test
    fun `revoked folder permission surfaces as content unavailable`() {
        provider.listingFailure = SecurityException("permission revoked")

        assertThrows(ContentUnavailableException::class.java) {
            runBlocking { source.list(folderShare()) }
        }
    }

    @Test
    fun `unreadable folder surfaces as content unavailable`() {
        provider.listingFailure = IOException("provider died")

        assertThrows(ContentUnavailableException::class.java) {
            runBlocking { source.list(folderShare()) }
        }
    }

    @Test
    fun `unexpected listing failure is not swallowed`() {
        provider.listingFailure = IllegalStateException("programming fault")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { source.list(folderShare()) }
        }
    }

    @Test
    fun `open returns null when read permission was revoked`() =
        runBlocking {
            provider.addDocument(DocumentEntry("a", "a.txt", "text/plain", sizeBytes = 3L, content = "abc"))
            provider.setChildren(ROOT_ID, "a")
            val entry = source.list(folderShare()).single()
            provider.readFailure = SecurityException("permission revoked")

            assertNull(source.open(folderShare(), entry.id))
        }

    @Test
    fun `open returns null for an unknown entry id`() =
        runBlocking {
            assertNull(source.open(folderShare(), "not-a-real-entry"))
        }

    private fun folderShare(): ShareRecord = share(ShareKind.FOLDER, DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID))

    private fun share(
        kind: ShareKind,
        uri: Uri,
    ): ShareRecord =
        ShareRecord(
            id = 1L,
            token = "token",
            kind = kind,
            displayName = "shared",
            files = listOf(ShareFileRef(uri.toString(), "shared")),
            createdAtEpochMs = 0L,
            expiresAtEpochMs = null,
            passwordHash = null,
            passwordSalt = null,
            passwordIterations = 0,
            maxDownloads = null,
            downloadCount = 0,
            revokedAtEpochMs = null,
        )

    private companion object {
        const val AUTHORITY = "dev.pocketshare.test.documents"
        const val ROOT_ID = "root"
    }
}

/** In-memory DocumentsProvider so tests never touch real storage. */
private data class DocumentEntry(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    val content: String? = null,
)

private class FakeDocumentsProvider : ContentProvider() {
    private val documents = mutableMapOf<String, DocumentEntry>()
    private val children = mutableMapOf<String, List<String>>()

    /** Thrown while listing folder contents, i.e. exactly where SAF raises permission errors. */
    var listingFailure: Exception? = null

    /** Thrown while opening a document stream. */
    var readFailure: Exception? = null

    fun reset() {
        documents.clear()
        children.clear()
        listingFailure = null
        readFailure = null
    }

    fun addDocument(document: DocumentEntry) {
        documents[document.id] = document
    }

    fun setChildren(
        parentId: String,
        vararg childIds: String,
    ) {
        children[parentId] = childIds.toList()
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID)
        val documentId = DocumentsContract.getDocumentId(uri)
        val cursor = MatrixCursor(columns)
        if (uri.pathSegments.contains(CHILD_SEGMENT)) {
            listingFailure?.let { throw it }
            children[documentId].orEmpty().forEach { childId ->
                cursor.addRow(columns.map { column -> columnValue(childId, column) })
            }
        } else {
            if (documents[documentId] != null) cursor.addRow(columns.map { column -> columnValue(documentId, column) })
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = documents[DocumentsContract.getDocumentId(uri)]?.mimeType

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        readFailure?.let { throw it }
        val document =
            documents[DocumentsContract.getDocumentId(uri)]
                ?: throw FileNotFoundException("Unknown document: $uri")
        val file = File.createTempFile("pocketshare-test", ".bin")
        file.writeText(document.content.orEmpty())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun columnValue(
        documentId: String,
        column: String,
    ): Any? {
        val document = documents[documentId] ?: return null
        return when (column) {
            Document.COLUMN_DOCUMENT_ID -> documentId
            Document.COLUMN_DISPLAY_NAME -> document.name
            Document.COLUMN_MIME_TYPE -> document.mimeType
            Document.COLUMN_SIZE, OpenableColumns.SIZE -> document.sizeBytes
            Document.COLUMN_FLAGS -> 0
            Document.COLUMN_LAST_MODIFIED -> 0L
            else -> null
        }
    }

    private companion object {
        const val CHILD_SEGMENT = "children"
    }
}
