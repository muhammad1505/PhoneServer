package dev.pocketshare.server

import dev.pocketshare.core.http.FileNames
import dev.pocketshare.core.image.EncodedImage
import dev.pocketshare.core.image.ImageTransformer
import dev.pocketshare.core.log.NoopLogger
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.core.security.PasswordHasher
import dev.pocketshare.core.security.TokenGenerator
import dev.pocketshare.core.share.SharedEntry
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ServerIntegrationTest {
    private val tokenGenerator = TokenGenerator()
    private val hasher = PasswordHasher(iterations = 2_000)
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)

    private fun deps(
        store: FakeShareStore,
        source: dev.pocketshare.core.share.ShareContentSource,
        config: ServerConfig = ServerConfig(secureCookies = false),
        imageTransformer: ImageTransformer? = null,
    ): ServerDependencies =
        ServerDependencies(
            store = store,
            content = source,
            passwordHasher = hasher,
            logger = NoopLogger,
            sessionSecret = ByteArray(32) { (it + 1).toByte() },
            config = config,
            clock = clock,
            imageTransformer = imageTransformer,
        )

    private class StubImageTransformer(
        private val supported: Boolean = true,
    ) : ImageTransformer {
        val maxDimensions = mutableListOf<Int>()

        override fun downscale(
            source: ByteArray,
            maxDimension: Int,
            quality: Int,
        ): EncodedImage? {
            maxDimensions += maxDimension
            if (!supported) return null
            return EncodedImage("<$maxDimension:$quality>".toByteArray(), "image/jpeg")
        }
    }

    private fun entry(
        id: String,
        name: String,
        relativePath: String = name,
        mime: String = "text/plain",
    ) = SharedEntry(
        id = id,
        name = name,
        relativePath = relativePath,
        sizeBytes = null,
        mimeType = mime,
    )

    private fun source(
        vararg files: Pair<String, ByteArray>,
        names: Map<String, String> = emptyMap(),
    ): FakeContentSource {
        val entries =
            files.map { (id, bytes) ->
                SharedEntry(
                    id = id,
                    name = names[id] ?: id,
                    relativePath = names[id] ?: id,
                    sizeBytes = bytes.size.toLong(),
                    mimeType = "text/plain",
                )
            }
        return FakeContentSource(entries, files.toMap())
    }

    private fun install(
        builder: ApplicationTestBuilder,
        dependencies: ServerDependencies,
    ) {
        builder.application { pocketShareModule(dependencies) }
    }

    private fun docxBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>Halo dari DOCX</w:t></w:r></w:p>
                  </w:body>
                </w:document>
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    @Test
    fun `content provider failures are answered with 500`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content = source("file" to "hello".toByteArray())
            content.unexpectedFailure = IllegalStateException("provider died")
            install(this, deps(store, content))

            val page = client.get("/s/$token")
            assertEquals(HttpStatusCode.InternalServerError, page.status)

            val download = client.get("/s/$token/download/file")
            assertEquals(HttpStatusCode.InternalServerError, download.status)
        }

    @Test
    fun `share page is rendered with security headers`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content = source("file" to "hello".toByteArray(), names = mapOf("file" to "hello.txt"))
            install(this, deps(store, content))

            val response = client.get("/s/$token") { header(HttpHeaders.AcceptLanguage, "en") }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/html"))
            val body = response.bodyAsText()
            assertTrue(body.contains("hello.txt"))
            assertTrue(body.contains("Download"))
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
            assertTrue(response.headers["X-Robots-Tag"].orEmpty().contains("noindex"))
            assertTrue(response.headers["Content-Security-Policy"].orEmpty().contains("default-src 'none'"))
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun `invalid expired revoked and exhausted shares return identical 404`() =
        testApplication {
            val missing = tokenGenerator.generate()
            val expired = tokenGenerator.generate()
            val revoked = tokenGenerator.generate()
            val exhausted = tokenGenerator.generate()
            val store =
                FakeShareStore(
                    listOf(
                        testRecord(expired, expiresAtEpochMs = NOW - 1),
                        testRecord(revoked, revokedAtEpochMs = NOW - 1),
                        testRecord(exhausted, maxDownloads = 1, downloadCount = 1),
                    ),
                )
            val content = source("file" to "hello".toByteArray())
            install(this, deps(store, content))

            val responses =
                listOf(missing, expired, revoked, exhausted).map { token ->
                    val response = client.get("/s/$token") { header(HttpHeaders.AcceptLanguage, "en") }
                    response.status to response.bodyAsText()
                }
            assertEquals(HttpStatusCode.NotFound, responses.first().first)
            assertTrue(responses.all { it.first == HttpStatusCode.NotFound })
            assertTrue(responses.all { it.second == responses.first().second })
            assertTrue(responses.first().second.contains("Link not found"))
        }

    @Test
    fun `full and single range downloads work and are counted`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content = source("file" to "0123456789".toByteArray(), names = mapOf("file" to "numbers.txt"))
            install(this, deps(store, content))

            val full = client.get("/s/$token/download/file")
            assertEquals(HttpStatusCode.OK, full.status)
            assertEquals("0123456789", full.bodyAsText())
            assertEquals("bytes", full.headers[HttpHeaders.AcceptRanges])
            assertTrue(full.headers[HttpHeaders.ContentDisposition].orEmpty().contains("numbers.txt"))

            val ranged = client.get("/s/$token/download/file") { header(HttpHeaders.Range, "bytes=2-5") }
            assertEquals(HttpStatusCode.PartialContent, ranged.status)
            assertEquals("2345", ranged.bodyAsText())
            assertEquals("bytes 2-5/10", ranged.headers[HttpHeaders.ContentRange])
            assertEquals("4", ranged.headers[HttpHeaders.ContentLength])
            assertEquals(2, store.record(token)?.downloadCount)
        }

    @Test
    fun `unsatisfiable range is rejected without consuming a download`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content = source("file" to "0123456789".toByteArray())
            install(this, deps(store, content))

            val response = client.get("/s/$token/download/file") { header(HttpHeaders.Range, "bytes=100-") }

            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
            assertEquals("bytes */10", response.headers[HttpHeaders.ContentRange])
            assertEquals(0, store.record(token)?.downloadCount)
        }

    @Test
    fun `multi range request is rejected`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content = source("file" to "0123456789".toByteArray())
            install(this, deps(store, content))

            val response = client.get("/s/$token/download/file") { header(HttpHeaders.Range, "bytes=0-1,4-5") }
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
        }

    @Test
    fun `password protected share requires unlock and issues cookie`() =
        testApplication {
            val token = tokenGenerator.generate()
            val hash = hasher.hash("correct horse")
            val store =
                FakeShareStore(
                    listOf(
                        testRecord(
                            token,
                            passwordHash = hash.hashBase64,
                            passwordSalt = hash.saltBase64,
                            passwordIterations = hash.iterations,
                        ),
                    ),
                )
            val content = source("file" to "top secret".toByteArray(), names = mapOf("file" to "secret.txt"))
            install(this, deps(store, content))

            val lockedPage = client.get("/s/$token") { header(HttpHeaders.AcceptLanguage, "en") }
            assertEquals(HttpStatusCode.OK, lockedPage.status)
            val lockedHtml = lockedPage.bodyAsText()
            assertTrue(lockedHtml.contains("Protected content"))
            assertTrue(lockedHtml.contains("id=\"passwordToggle\""))
            assertTrue(lockedHtml.contains("aria-controls=\"password\""))
            assertTrue(lockedHtml.contains("data-show-label=\"Show password\""))
            assertTrue(lockedHtml.contains("data-hide-label=\"Hide password\""))
            assertFalse(lockedHtml.contains("secret.txt"))

            val lockedDownload = client.get("/s/$token/download/file")
            assertEquals(HttpStatusCode.Unauthorized, lockedDownload.status)

            val wrong =
                client.post("/s/$token/unlock") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody("password=wrong")
                    header(HttpHeaders.AcceptLanguage, "en")
                }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
            assertTrue(wrong.bodyAsText().contains("Wrong password"))

            val correct =
                client.post("/s/$token/unlock") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody("password=correct+horse")
                }
            assertEquals(HttpStatusCode.SeeOther, correct.status)
            assertEquals("/s/$token", correct.headers[HttpHeaders.Location])
            val cookie =
                correct.headers
                    .getAll(HttpHeaders.SetCookie)
                    .orEmpty()
                    .first()
                    .substringBefore(';')
            assertTrue(cookie.startsWith("ps_session="))

            val unlockedPage = client.get("/s/$token") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, unlockedPage.status)
            assertTrue(unlockedPage.bodyAsText().contains("secret.txt"))

            val download = client.get("/s/$token/download/file") { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.OK, download.status)
            assertEquals("top secret", download.bodyAsText())
        }

    @Test
    fun `password attempts are rate limited`() =
        testApplication {
            val token = tokenGenerator.generate()
            val hash = hasher.hash("secret")
            val store =
                FakeShareStore(
                    listOf(
                        testRecord(
                            token,
                            passwordHash = hash.hashBase64,
                            passwordSalt = hash.saltBase64,
                            passwordIterations = hash.iterations,
                        ),
                    ),
                )
            val content = source("file" to "x".toByteArray())
            install(
                this,
                deps(
                    store,
                    content,
                    config = ServerConfig(secureCookies = false, passwordAttemptsPerWindow = 2),
                ),
            )

            suspend fun attempt(): io.ktor.client.statement.HttpResponse =
                client.post("/s/$token/unlock") {
                    header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                    setBody("password=nope")
                }

            assertEquals(HttpStatusCode.Unauthorized, attempt().status)
            assertEquals(HttpStatusCode.Unauthorized, attempt().status)
            val blocked = attempt()
            assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
            assertNotNull(blocked.headers[HttpHeaders.RetryAfter])
        }

    @Test
    fun `general request rate limit applies`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content = source("file" to "x".toByteArray())
            install(
                this,
                deps(
                    store,
                    content,
                    config = ServerConfig(secureCookies = false, generalRequestsPerMinute = 2),
                ),
            )

            assertEquals(HttpStatusCode.OK, client.get("/s/$token").status)
            assertEquals(HttpStatusCode.OK, client.get("/s/$token").status)
            assertEquals(HttpStatusCode.TooManyRequests, client.get("/s/$token").status)
        }

    @Test
    fun `oversized headers are rejected`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content = source("file" to "x".toByteArray())
            install(
                this,
                deps(
                    store,
                    content,
                    config = ServerConfig(secureCookies = false, maxHeaderBytes = 1_024),
                ),
            )

            val response = client.get("/s/$token") { header("X-Oversized", "a".repeat(2_000)) }
            assertEquals(HttpStatusCode.RequestHeaderFieldTooLarge, response.status)
        }

    @Test
    fun `zip download preserves contents and sanitizes traversal names`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store =
                FakeShareStore(
                    listOf(
                        testRecord(
                            token,
                            kind = ShareKind.FOLDER,
                            files =
                                listOf(
                                    dev.pocketshare.core.model
                                        .ShareFileRef("content://test/tree", "folder"),
                                ),
                        ),
                    ),
                )
            val content =
                FakeContentSource(
                    entries =
                        listOf(
                            SharedEntry("a", "a.txt", "a.txt", 5, "text/plain"),
                            SharedEntry("b", "b.txt", "../sub/b.txt", 5, "text/plain"),
                        ),
                    data = mapOf("a" to "hello".toByteArray(), "b" to "world".toByteArray()),
                )
            install(this, deps(store, content))

            val response = client.get("/s/$token/zip")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().contains("application/zip"))
            assertTrue(response.headers[HttpHeaders.ContentDisposition].orEmpty().contains(".zip"))

            val entries = unzip(response.bodyAsBytes())
            assertEquals("hello", entries.getValue("a.txt").toString(Charsets.UTF_8))
            assertEquals("world", entries.getValue("_/sub/b.txt").toString(Charsets.UTF_8))
            assertFalse(entries.keys.any { it.contains("..") })
        }

    @Test
    fun `large files above 4gb support ranges`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val size = 5L * 1024L * 1024L * 1024L + 32L
            val content = LargeSyntheticContentSource(size)
            install(this, deps(store, content))

            val start = size - 16
            val response =
                client.get("/s/$token/download/big") {
                    header(HttpHeaders.Range, "bytes=$start-${size - 1}")
                }
            assertEquals(HttpStatusCode.PartialContent, response.status)
            assertEquals("bytes $start-${size - 1}/$size", response.headers[HttpHeaders.ContentRange])
            assertEquals(16, response.bodyAsBytes().size)
        }

    @Test
    fun `path traversal attempts return not found`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content = source("file" to "safe".toByteArray())
            install(this, deps(store, content))

            val attempts =
                listOf(
                    "/s/$token/download/..%2F..%2Fetc%2Fpasswd",
                    "/s/$token/download/${FileNames.encodePathSegment("../../etc/passwd")}",
                    "/s/$token/download/does-not-exist",
                )
            attempts.forEach { path ->
                val response = client.get(path)
                assertTrue("unexpected status for $path: ${response.status}", response.status.value in 400..404)
                assertFalse(response.bodyAsText().contains("root:"))
            }
        }

    @Test
    fun `only share routes are served`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content = source("file" to "x".toByteArray())
            install(this, deps(store, content))

            listOf("/", "/admin", "/s", "/s/$token/unknown").forEach { path ->
                assertEquals("path $path", HttpStatusCode.NotFound, client.get(path).status)
            }
        }

    @Test
    fun `health endpoint answers without a share token`() =
        testApplication {
            val store = FakeShareStore(emptyList())
            val content = source()
            install(this, deps(store, content))

            val response = client.get("/healthz")

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("", response.bodyAsText())
        }

    @Test
    fun `unicode and hostile file names are escaped`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val entry =
                SharedEntry(
                    id = "file",
                    name = "日本語 <script>alert(1)</script>.txt",
                    relativePath = "日本語 <script>alert(1)</script>.txt",
                    sizeBytes = 3,
                    mimeType = "text/plain",
                )
            val content = FakeContentSource(listOf(entry), mapOf("file" to "abc".toByteArray()))
            install(this, deps(store, content))

            val page = client.get("/s/$token")
            val body = page.bodyAsText()
            assertTrue(body.contains("&lt;script&gt;"))
            assertFalse(body.contains("<script>alert"))

            val download = client.get("/s/$token/download/file")
            val disposition = download.headers[HttpHeaders.ContentDisposition].orEmpty()
            assertTrue(disposition.contains("filename*=UTF-8''"))
            assertFalse(disposition.contains("\r"))
            assertFalse(disposition.contains("\n"))
        }

    @Test
    fun `download limit is enforced atomically`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token, maxDownloads = 1)))
            val content = source("file" to "once".toByteArray())
            install(this, deps(store, content))

            assertEquals(HttpStatusCode.OK, client.get("/s/$token/download/file").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/s/$token/download/file").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/s/$token").status)
            assertEquals(1, store.record(token)?.downloadCount)
        }

    @Test
    fun `revoked folder permission yields an identical not found page`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store =
                FakeShareStore(
                    listOf(
                        testRecord(
                            token,
                            kind = ShareKind.FOLDER,
                            files =
                                listOf(
                                    dev.pocketshare.core.model
                                        .ShareFileRef("content://test/tree", "folder"),
                                ),
                        ),
                    ),
                )
            val content = source("file" to "x".toByteArray()).apply { unavailable = true }
            install(this, deps(store, content))

            val response = client.get("/s/$token") { header(HttpHeaders.AcceptLanguage, "en") }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("Link not found"))
        }

    @Test
    fun `preview streams inline images and counts the download`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("img", "photo.png", "photo.png", png.size.toLong(), "image/png")),
                    data = mapOf("img" to png),
                )
            install(this, deps(store, content))

            val response = client.get("/s/$token/preview/img")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("image/png"))
            assertFalse(response.headers[HttpHeaders.ContentDisposition].orEmpty().contains("attachment"))
            assertArrayEquals(png, response.bodyAsBytes())
            assertEquals(1, store.findByToken(token)?.downloadCount)
        }

    @Test
    fun `list thumbnails carry a signed token and do not consume downloads`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("img", "photo.png", "photo.png", png.size.toLong(), "image/png")),
                    data = mapOf("img" to png),
                )
            install(this, deps(store, content))

            val body = client.get("/s/$token").bodyAsText()
            val src = Regex("src=\"(/s/[^\"]+/preview/img\\?thumb=[^\"]+)\"").find(body)?.groupValues?.get(1)
            assertNotNull(src)
            val thumb = client.get(src!!)
            assertEquals(HttpStatusCode.OK, thumb.status)
            assertArrayEquals(png, thumb.bodyAsBytes())
            assertEquals(0, store.record(token)?.downloadCount)

            // A forged token cannot dodge the download counter.
            val forged = client.get("/s/$token/preview/img?thumb=forged")
            assertEquals(HttpStatusCode.OK, forged.status)
            assertEquals(1, store.record(token)?.downloadCount)
        }

    @Test
    fun `unsafe preview types fall back to a download`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val html = "<h1>hi</h1>".toByteArray()
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("page", "page.html", "page.html", html.size.toLong(), "text/html")),
                    data = mapOf("page" to html),
                )
            install(this, deps(store, content))

            val response = client.get("/s/$token/preview/page")
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().contains("application/octet-stream"))
            assertTrue(response.headers[HttpHeaders.ContentDisposition].orEmpty().contains("attachment"))
        }

    @Test
    fun `media preview counts one download per viewing and reuses the playback ticket`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val clip = "0123456789".toByteArray()
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("clip", "clip.mp4", "clip.mp4", clip.size.toLong(), "video/mp4")),
                    data = mapOf("clip" to clip),
                )
            install(this, deps(store, content))

            val shell = client.get("/s/$token/preview/clip")
            assertEquals(HttpStatusCode.OK, shell.status)
            assertTrue(shell.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/html"))
            val body = shell.bodyAsText()
            assertTrue(body.contains("<video"))
            assertTrue(body.contains("/s/$token/preview/clip?raw=1"))
            assertEquals(1, store.record(token)?.downloadCount)
            val ticket =
                shell.headers
                    .getAll(HttpHeaders.SetCookie)
                    .orEmpty()
                    .first { it.startsWith("ps_play=") }
                    .substringBefore(';')

            val asset = client.get("/s/$token/preview/clip?raw=1") { header(HttpHeaders.Cookie, ticket) }
            assertEquals(HttpStatusCode.OK, asset.status)
            assertTrue(asset.headers[HttpHeaders.ContentType].orEmpty().startsWith("video/mp4"))
            assertFalse(asset.headers[HttpHeaders.ContentDisposition].orEmpty().contains("attachment"))
            assertEquals("bytes", asset.headers[HttpHeaders.AcceptRanges])
            assertArrayEquals(clip, asset.bodyAsBytes())
            assertEquals(1, store.record(token)?.downloadCount)

            val ranged =
                client.get("/s/$token/preview/clip?raw=1") {
                    header(HttpHeaders.Cookie, ticket)
                    header(HttpHeaders.Range, "bytes=4-7")
                }
            assertEquals(HttpStatusCode.PartialContent, ranged.status)
            assertEquals("4567", ranged.bodyAsText())
            assertEquals("bytes 4-7/10", ranged.headers[HttpHeaders.ContentRange])
            assertEquals(1, store.record(token)?.downloadCount)

            val unsatisfiable =
                client.get("/s/$token/preview/clip?raw=1") {
                    header(HttpHeaders.Cookie, ticket)
                    header(HttpHeaders.Range, "bytes=100-")
                }
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, unsatisfiable.status)
            assertEquals("bytes */10", unsatisfiable.headers[HttpHeaders.ContentRange])
            assertEquals(1, store.record(token)?.downloadCount)

            // Without a ticket the raw asset still counts once and hands back a fresh ticket.
            val direct = client.get("/s/$token/preview/clip?raw=1")
            assertEquals(HttpStatusCode.OK, direct.status)
            assertEquals(2, store.record(token)?.downloadCount)
            assertTrue(
                direct.headers
                    .getAll(HttpHeaders.SetCookie)
                    .orEmpty()
                    .any { it.startsWith("ps_play=") },
            )
        }

    @Test
    fun `embed preview renders media without the preview chrome`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val clip = "0123456789".toByteArray()
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("clip", "clip.mp4", "clip.mp4", clip.size.toLong(), "video/mp4")),
                    data = mapOf("clip" to clip),
                )
            install(this, deps(store, content))

            val embedded =
                client.get("/s/$token/preview/clip?embed=1") { header(HttpHeaders.AcceptLanguage, "en") }
            assertEquals(HttpStatusCode.OK, embedded.status)
            val body = embedded.bodyAsText()
            assertTrue(body.contains("<video"))
            assertTrue(body.contains("/s/$token/preview/clip?raw=1"))
            assertFalse(body.contains("Back to the list"))
            assertFalse(body.contains("<h1>"))
            assertEquals(1, store.record(token)?.downloadCount)
        }

    @Test
    fun `embed preview renders documents without the preview chrome`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val text = "halo dunia".toByteArray()
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("note", "note.txt", "note.txt", text.size.toLong(), "text/plain")),
                    data = mapOf("note" to text),
                )
            install(this, deps(store, content))

            val embedded =
                client.get("/s/$token/preview/note?embed=1") { header(HttpHeaders.AcceptLanguage, "en") }
            assertEquals(HttpStatusCode.OK, embedded.status)
            val body = embedded.bodyAsText()
            assertTrue(body.contains("<pre class=\"doc-text\">halo dunia</pre>"))
            assertFalse(body.contains("Back to the list"))
            assertFalse(body.contains("<h1>"))
            assertEquals(1, store.record(token)?.downloadCount)
        }

    @Test
    fun `office documents are previewed as extracted text`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val docx = docxBytes()
            val mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("doc", "catatan.docx", "catatan.docx", docx.size.toLong(), mime)),
                    data = mapOf("doc" to docx),
                )
            install(this, deps(store, content))

            val page = client.get("/s/$token") { header(HttpHeaders.AcceptLanguage, "en") }.bodyAsText()
            assertTrue(page.contains("/s/$token/preview/doc"))
            assertTrue(page.contains("data-preview-kind=\"text\""))

            val preview =
                client.get("/s/$token/preview/doc?embed=1") { header(HttpHeaders.AcceptLanguage, "en") }
            assertEquals(HttpStatusCode.OK, preview.status)
            val body = preview.bodyAsText()
            assertTrue(body.contains("<pre class=\"doc-text\">"))
            assertTrue(body.contains("Halo dari DOCX"))
            assertEquals(1, store.record(token)?.downloadCount)
        }

    @Test
    fun `pdf previews stay embedded`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val pdf = "%PDF-1.4".toByteArray()
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("pdf", "surat.pdf", "surat.pdf", pdf.size.toLong(), "application/pdf")),
                    data = mapOf("pdf" to pdf),
                )
            install(this, deps(store, content))

            val preview =
                client.get("/s/$token/preview/pdf?embed=1") { header(HttpHeaders.AcceptLanguage, "en") }
            assertEquals(HttpStatusCode.OK, preview.status)
            val body = preview.bodyAsText()
            assertTrue(body.contains("<iframe class=\"doc-frame\" src=\"data:application/pdf;base64,"))
            assertEquals(1, store.record(token)?.downloadCount)
        }

    @Test
    fun `image thumbnails and previews are re-encoded and thumbnails stay free`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val png = ByteArray(64) { it.toByte() }
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("img", "photo.png", "photo.png", png.size.toLong(), "image/png")),
                    data = mapOf("img" to png),
                )
            val transformer = StubImageTransformer()
            install(this, deps(store, content, imageTransformer = transformer))

            val page = client.get("/s/$token").bodyAsText()
            val thumbPath =
                Regex("src=\"(/s/[^\"]*/preview/img\\?thumb=[^\"]*)\"")
                    .find(page)!!
                    .groupValues[1]
            val thumb = client.get(thumbPath)
            assertEquals(HttpStatusCode.OK, thumb.status)
            assertEquals("image/jpeg", thumb.headers[HttpHeaders.ContentType])
            assertEquals("<320:74>", thumb.bodyAsText())
            assertEquals(0, store.record(token)?.downloadCount)

            val preview = client.get("/s/$token/preview/img")
            assertEquals(HttpStatusCode.OK, preview.status)
            assertEquals("<1600:82>", preview.bodyAsText())
            assertEquals(1, store.record(token)?.downloadCount)
            assertEquals(listOf(320, 1600), transformer.maxDimensions)
        }

    @Test
    fun `animated images keep their frames while thumbnails stay optimized`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val gif = "GIF89a-frames".toByteArray()
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("anim", "loop.gif", "loop.gif", gif.size.toLong(), "image/gif")),
                    data = mapOf("anim" to gif),
                )
            install(this, deps(store, content, imageTransformer = StubImageTransformer()))

            val preview = client.get("/s/$token/preview/anim")
            assertEquals(HttpStatusCode.OK, preview.status)
            assertEquals("image/gif", preview.headers[HttpHeaders.ContentType])
            assertArrayEquals(gif, preview.bodyAsBytes())
            assertEquals(1, store.record(token)?.downloadCount)

            val page = client.get("/s/$token").bodyAsText()
            val thumbPath =
                Regex("src=\"(/s/[^\"]*/preview/anim\\?thumb=[^\"]*)\"")
                    .find(page)!!
                    .groupValues[1]
            val thumb = client.get(thumbPath)
            assertEquals(HttpStatusCode.OK, thumb.status)
            assertEquals("image/jpeg", thumb.headers[HttpHeaders.ContentType])
            assertEquals("<320:74>", thumb.bodyAsText())
            assertEquals(1, store.record(token)?.downloadCount)
        }

    @Test
    fun `animated png is served untouched while its thumbnail is optimized`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val apng = apngBytes()
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("apng", "sticker.png", "sticker.png", apng.size.toLong(), "image/png")),
                    data = mapOf("apng" to apng),
                )
            install(this, deps(store, content, imageTransformer = StubImageTransformer()))

            val preview = client.get("/s/$token/preview/apng")
            assertEquals(HttpStatusCode.OK, preview.status)
            assertEquals("image/png", preview.headers[HttpHeaders.ContentType])
            assertArrayEquals(apng, preview.bodyAsBytes())
            assertEquals(1, store.record(token)?.downloadCount)

            val page = client.get("/s/$token").bodyAsText()
            val thumbPath =
                Regex("src=\"(/s/[^\"]*/preview/apng\\?thumb=[^\"]*)\"")
                    .find(page)!!
                    .groupValues[1]
            val thumb = client.get(thumbPath)
            assertEquals(HttpStatusCode.OK, thumb.status)
            assertEquals("image/jpeg", thumb.headers[HttpHeaders.ContentType])
            assertEquals("<320:74>", thumb.bodyAsText())
            assertEquals(1, store.record(token)?.downloadCount)
        }

    private fun apngBytes(): ByteArray {
        fun chunk(
            type: String,
            data: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val length = data.size
            out.write(
                byteArrayOf(
                    (length ushr 24).toByte(),
                    (length ushr 16).toByte(),
                    (length ushr 8).toByte(),
                    length.toByte(),
                ),
            )
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(data)
            out.write(ByteArray(4))
            return out.toByteArray()
        }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(chunk("IHDR", ByteArray(13)))
        out.write(chunk("acTL", ByteArray(8)))
        out.write(chunk("IDAT", ByteArray(4)))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    @Test
    fun `image preview falls back to the original bytes when re-encode is unsupported`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val png = ByteArray(16) { (it + 1).toByte() }
            val content =
                FakeContentSource(
                    entries = listOf(SharedEntry("img", "photo.png", "photo.png", png.size.toLong(), "image/png")),
                    data = mapOf("img" to png),
                )
            install(this, deps(store, content, imageTransformer = StubImageTransformer(supported = false)))

            val preview = client.get("/s/$token/preview/img")
            assertEquals(HttpStatusCode.OK, preview.status)
            assertArrayEquals(png, preview.bodyAsBytes())
            assertEquals(1, store.record(token)?.downloadCount)
        }

    @Test
    fun `zip accepts an id selection and rejects unknown ids`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content =
                FakeContentSource(
                    entries =
                        listOf(
                            SharedEntry("a", "a.txt", "a.txt", 5, "text/plain"),
                            SharedEntry("b", "b.txt", "b.txt", 5, "text/plain"),
                        ),
                    data = mapOf("a" to "hello".toByteArray(), "b" to "world".toByteArray()),
                )
            install(this, deps(store, content))

            val selected = client.get("/s/$token/zip?ids=a")
            assertEquals(HttpStatusCode.OK, selected.status)
            val entries = unzip(selected.bodyAsBytes())
            assertEquals(setOf("a.txt"), entries.keys)

            val unknown = client.get("/s/$token/zip?ids=nope")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
        }

    @Test
    fun `share page offers thumbnails previews and selection controls`() =
        testApplication {
            val token = tokenGenerator.generate()
            val store = FakeShareStore(listOf(testRecord(token)))
            val content =
                FakeContentSource(
                    entries =
                        listOf(
                            SharedEntry("a", "a.txt", "a.txt", 5, "text/plain"),
                            SharedEntry("b", "b.png", "b.png", 8, "image/png"),
                        ),
                    data = mapOf("a" to "hello".toByteArray(), "b" to ByteArray(8)),
                )
            install(this, deps(store, content))

            val body = client.get("/s/$token").bodyAsText()
            assertTrue(body.contains("class=\"file-check\""))
            assertTrue(body.contains("id=\"downloadSelectedBtn\""))
            assertTrue(body.contains("/s/$token/preview/b"))
            assertTrue(body.contains("src=\"/s/$token/preview/b?thumb="))
            // Previews open in the in-page dialog; the plain link stays as a no-JS fallback.
            assertTrue(body.contains("id=\"previewDialog\""))
            assertTrue(body.contains("data-preview-src=\"/s/$token/preview/a\""))
            assertTrue(body.contains("data-preview-kind=\"image\""))
            assertTrue(body.contains("id=\"previewFullscreenBtn\""))
            assertTrue(body.contains("id=\"previewPrevBtn\""))
            assertTrue(body.contains("id=\"previewNextBtn\""))
        }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
