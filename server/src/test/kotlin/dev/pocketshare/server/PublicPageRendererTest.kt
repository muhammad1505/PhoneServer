package dev.pocketshare.server

import dev.pocketshare.core.model.ShareFileRef
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.core.model.ShareRecord
import dev.pocketshare.core.share.SharedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicPageRendererTest {
    private val renderer = PublicPageRenderer()

    private fun folderShare() =
        ShareRecord(
            id = 1L,
            token = "tokentokentokentokentokentoken12",
            kind = ShareKind.FOLDER,
            displayName = "Album",
            files = listOf(ShareFileRef(uri = "content://tree/album", name = "Album")),
            createdAtEpochMs = 0L,
            expiresAtEpochMs = null,
            passwordHash = null,
            passwordSalt = null,
            passwordIterations = 0,
            maxDownloads = null,
            downloadCount = 0,
            revokedAtEpochMs = null,
        )

    @Test
    fun `nested folder share renders a folder tree instead of one flat list`() {
        val entries =
            listOf(
                entry("b-photo", "photo.jpg", "image/jpeg", "B/photo.jpg"),
                entry("bc-notes", "notes.txt", "text/plain", "B/C/notes.txt"),
                entry("c-report", "report.pdf", "application/pdf", "C/report.pdf"),
                entry("root-readme", "readme.txt", "text/plain", "readme.txt"),
            )

        val html = renderer.renderShare(folderShare(), entries, PublicPageRenderer.Language.INDONESIAN)

        // B and C at the top, plus C nested inside B: one <details> per folder.
        assertEquals(3, Regex("<details").findAll(html).count())
        assertTrue(html.contains("class=\"folder-summary\""))
        assertTrue(html.contains(">B<"))
        assertTrue(html.contains(">C<"))
        // The folder path is no longer glued onto the file name.
        assertTrue(html.contains(">photo.jpg<"))
        assertFalse(html.contains("B/photo.jpg"))
        // A file that sits at the root of the share stays outside every folder.
        assertTrue(html.indexOf("readme.txt") > html.lastIndexOf("</details>"))
    }

    @Test
    fun `folder rows report their own file count`() {
        val entries =
            listOf(
                entry("b-photo", "photo.jpg", "image/jpeg", "B/photo.jpg"),
                entry("bc-notes", "notes.txt", "text/plain", "B/C/notes.txt"),
            )

        val html = renderer.renderShare(folderShare(), entries, PublicPageRenderer.Language.INDONESIAN)

        assertTrue(html.contains("2 file"))
        assertTrue(html.contains("1 file"))
    }

    @Test
    fun `file shares keep the flat list`() {
        val entries = listOf(entry("a", "a.txt", "text/plain", "a.txt"))

        val html =
            renderer.renderShare(
                folderShare().copy(kind = ShareKind.FILES),
                entries,
                PublicPageRenderer.Language.ENGLISH,
            )

        assertFalse(html.contains("<details"))
        assertTrue(html.contains(">a.txt<"))
    }

    @Test
    fun `folder names are escaped`() {
        val entries = listOf(entry("x", "a.txt", "text/plain", "B<b>/a.txt"))

        val html = renderer.renderShare(folderShare(), entries, PublicPageRenderer.Language.ENGLISH)

        assertTrue(html.contains("B&lt;b&gt;"))
        assertFalse(html.contains("<b>"))
    }

    private fun entry(
        id: String,
        name: String,
        mime: String,
        relativePath: String,
    ) = SharedEntry(
        id = id,
        name = name,
        relativePath = relativePath,
        sizeBytes = 1024L,
        mimeType = mime,
    )
}
