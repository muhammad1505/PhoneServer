package dev.pocketshare.server

import dev.pocketshare.core.http.FileNames
import dev.pocketshare.core.http.RangeParser
import dev.pocketshare.core.http.RangeSelection
import dev.pocketshare.core.model.ShareRecord
import dev.pocketshare.core.security.RateLimiter
import dev.pocketshare.core.security.SessionTokenSigner
import dev.pocketshare.core.security.TokenGenerator
import dev.pocketshare.core.share.ContentUnavailableException
import dev.pocketshare.core.share.OpenedContent
import dev.pocketshare.core.share.SharedEntry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Semaphore
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.min

fun Application.pocketShareModule(dependencies: ServerDependencies) {
    val service = PublicShareService(dependencies)
    install(SecurityHeaders)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            dependencies.logger.error("Http", "Unhandled request failure", cause)
            if (!call.response.isCommitted) {
                service.respondServerError(call)
            }
        }
    }
    routing {
        get("/healthz") { service.handleHealth(call) }
        get("/favicon.svg") { service.handleFavicon(call) }
        get("/favicon.ico") { service.handleFavicon(call) }
        route("/s/{token}") {
            get { service.handlePage(call, call.parameters["token"].orEmpty()) }
            post("/unlock") { service.handleUnlock(call, call.parameters["token"].orEmpty()) }
            get("/download/{entryId}") {
                service.handleDownload(
                    call = call,
                    token = call.parameters["token"].orEmpty(),
                    entryId = call.parameters["entryId"].orEmpty(),
                )
            }
            get("/zip") { service.handleZip(call, call.parameters["token"].orEmpty()) }
            get("/preview/{entryId}") {
                service.handlePreview(
                    call = call,
                    token = call.parameters["token"].orEmpty(),
                    entryId = call.parameters["entryId"].orEmpty(),
                )
            }
        }
        route("{...}") {
            handle { service.respondNotFound(call) }
        }
    }
}

private val SecurityHeaders =
    createApplicationPlugin("PocketShareSecurityHeaders") {
        onCall { call ->
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("X-Robots-Tag", "noindex, nofollow, noarchive")
            // Previews are framed by our own share page, never by another origin.
            call.response.headers.append("X-Frame-Options", "SAMEORIGIN")
            call.response.headers.append("Cross-Origin-Resource-Policy", "same-origin")
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        }
    }

private class PublicShareService(
    private val dependencies: ServerDependencies,
) {
    private val config = dependencies.config
    private val store = dependencies.store
    private val content = dependencies.content
    private val logger = dependencies.logger
    private val hasher = dependencies.passwordHasher
    private val clock = dependencies.clock
    private val imageTransformer = dependencies.imageTransformer
    private val renderer = PublicPageRenderer()
    private val signer =
        SessionTokenSigner(
            secret = dependencies.sessionSecret,
            clock = dependencies.clock,
            ttlMillis = config.sessionTtlMillis,
        )

    // Browsing a photo-heavy folder asks for the same list thumbnails again and again. Re-encoding
    // every one on each page load is what made nested folders feel slow, so keep a small cache.
    private val thumbnailCache =
        object : LinkedHashMap<String, CachedThumbnail>(THUMBNAIL_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedThumbnail>?): Boolean {
                return size > THUMBNAIL_CACHE_ENTRIES
            }
        }
    private val generalLimiter = RateLimiter { clock.millis() }
    private val passwordLimiter = RateLimiter { clock.millis() }
    private val generalPolicy = RateLimiter.Policy(config.generalRequestsPerMinute, 60_000L)
    private val passwordPolicy = RateLimiter.Policy(config.passwordAttemptsPerWindow, config.passwordWindowMillis)
    private val downloadGate = Semaphore(config.maxConcurrentDownloads)

    suspend fun handlePage(
        call: ApplicationCall,
        token: String,
    ) {
        if (!call.enforceGeneralLimits()) return
        val share = resolveAvailable(token) ?: return respondNotFound(call)
        if (share.hasPassword && !hasValidSession(call, share)) {
            respondHtml(call, renderer.renderUnlock(language(call), share.token, wrongPassword = false))
            return
        }
        renderSharePage(call, share)
    }

    suspend fun handleUnlock(
        call: ApplicationCall,
        token: String,
    ) {
        if (!call.enforceGeneralLimits()) return
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > config.maxFormBytes) {
            respondPlain(call, HttpStatusCode.PayloadTooLarge, "Payload Too Large")
            return
        }
        if (!call.request.headers[HttpHeaders.ContentType]
                .orEmpty()
                .startsWith("application/x-www-form-urlencoded")
        ) {
            respondPlain(call, HttpStatusCode.UnsupportedMediaType, "Unsupported Media Type")
            return
        }

        val share = resolveAvailable(token) ?: return respondNotFound(call)
        if (!share.hasPassword) {
            call.respondRedirectToShare(share.token)
            return
        }

        val ip = clientIp(call)
        val passwordKey = "pw:${share.id}:$ip"
        val decision = passwordLimiter.tryAcquire(passwordKey, passwordPolicy)
        if (!decision.allowed) {
            respondRateLimited(call, decision.retryAfterSeconds)
            return
        }

        val body =
            try {
                call.receiveText()
            } catch (error: BadRequestException) {
                logger.warn("Http", "Unable to read unlock form", error)
                respondPlain(call, HttpStatusCode.BadRequest, "Bad Request")
                return
            } catch (error: IOException) {
                logger.warn("Http", "Unable to read unlock form", error)
                respondPlain(call, HttpStatusCode.BadRequest, "Bad Request")
                return
            }
        if (body.length > config.maxFormBytes) {
            respondPlain(call, HttpStatusCode.PayloadTooLarge, "Payload Too Large")
            return
        }
        val password = parseFormValue(body, "password")
        val verified =
            password != null &&
                withContext(Dispatchers.Default) {
                    hasher.verify(
                        password = password,
                        hashBase64 = share.passwordHash.orEmpty(),
                        saltBase64 = share.passwordSalt.orEmpty(),
                        iterations = share.passwordIterations,
                    )
                }
        if (!verified) {
            respondHtml(
                call,
                renderer.renderUnlock(language(call), share.token, wrongPassword = true),
                status = HttpStatusCode.Unauthorized,
            )
            return
        }

        passwordLimiter.reset(passwordKey)
        setSessionCookie(call, share)
        call.respondRedirectToShare(share.token)
    }

    suspend fun handleDownload(
        call: ApplicationCall,
        token: String,
        entryId: String,
    ) {
        if (!call.enforceGeneralLimits()) return
        val share = resolveAvailable(token) ?: return respondNotFound(call)
        if (!authorizeDownload(call, share)) return
        if (entryId.isBlank() || entryId.length > 128) return respondNotFound(call)
        if (!downloadGate.tryAcquire()) {
            respondBusy(call)
            return
        }

        var opened: OpenedContent? = null
        try {
            opened = content.open(share, entryId)
            if (opened == null) {
                respondNotFound(call)
                return
            }
            // A provider that reports 0 bytes usually means "size unknown"; the stream is then sent
            // chunked instead of being announced as an empty file.
            val size = opened.sizeBytes?.takeIf { it > 0L }
            val selection = RangeParser.parse(call.request.headers[HttpHeaders.Range], size)
            if (selection is RangeSelection.Unsatisfiable || selection is RangeSelection.Unsupported) {
                closeQuietly(opened.stream)
                opened = null
                if (size != null) {
                    call.response.headers.append(HttpHeaders.ContentRange, "bytes */$size")
                }
                respondPlain(call, HttpStatusCode.RequestedRangeNotSatisfiable, "Range Not Satisfiable")
                return
            }

            if (!store.tryReserveDownload(share.id, clock.millis())) {
                closeQuietly(opened.stream)
                opened = null
                respondNotFound(call)
                return
            }

            val contentType = safeContentType(opened.mimeType)
            val disposition = attachmentDisposition(opened.name)
            call.response.headers.append(HttpHeaders.ContentDisposition, disposition)
            call.response.headers.append(HttpHeaders.AcceptRanges, if (size != null) "bytes" else "none")

            val status: HttpStatusCode
            val contentLength: Long?
            val skip: Long
            val length: Long?
            when (selection) {
                is RangeSelection.Partial -> {
                    status = HttpStatusCode.PartialContent
                    contentLength = selection.length
                    skip = selection.start
                    length = selection.length
                    call.response.headers.append(
                        HttpHeaders.ContentRange,
                        "bytes ${selection.start}-${selection.endInclusive}/$size",
                    )
                }

                RangeSelection.Full, RangeSelection.Unsatisfiable, RangeSelection.Unsupported -> {
                    status = HttpStatusCode.OK
                    contentLength = size
                    skip = 0L
                    length = size
                }
            }

            val stream = opened.stream
            try {
                call.respondOutputStream(contentType = contentType, status = status, contentLength = contentLength) {
                    withContext(Dispatchers.IO) {
                        copyBytes(
                            context = coroutineContext,
                            input = stream,
                            output = this@respondOutputStream,
                            skipBytes = skip,
                            lengthBytes = length,
                        )
                    }
                }
            } finally {
                closeQuietly(stream)
                opened = null
            }
        } catch (error: ContentUnavailableException) {
            logger.warn("Http", "Content unavailable during download", error)
            respondNotFound(call)
        } catch (error: IOException) {
            respondStreamFailure(call, "Download failed", error)
        } catch (error: IllegalStateException) {
            respondStreamFailure(call, "Download failed", error)
        } catch (error: SecurityException) {
            respondStreamFailure(call, "Download failed", error)
        } finally {
            opened?.let { closeQuietly(it.stream) }
            downloadGate.release()
        }
    }

    suspend fun handleZip(
        call: ApplicationCall,
        token: String,
    ) {
        if (!call.enforceGeneralLimits()) return
        val share = resolveAvailable(token) ?: return respondNotFound(call)
        if (!authorizeDownload(call, share)) return
        if (!downloadGate.tryAcquire()) {
            respondBusy(call)
            return
        }
        try {
            val allEntries =
                try {
                    content.list(share)
                } catch (error: ContentUnavailableException) {
                    logger.warn("Http", "ZIP content unavailable", error)
                    respondNotFound(call)
                    return
                }
            if (allEntries.isEmpty()) {
                respondNotFound(call)
                return
            }
            val requestedIds = parseIdList(call.request.queryParameters["ids"])
            val entries =
                if (requestedIds == null) {
                    allEntries
                } else {
                    val selected = allEntries.filter { it.id in requestedIds }
                    if (selected.isEmpty()) {
                        respondNotFound(call)
                        return
                    }
                    selected
                }
            if (!store.tryReserveDownload(share.id, clock.millis())) {
                respondNotFound(call)
                return
            }

            val baseName = FileNames.displayName("${share.displayName}.zip")
            val zipName =
                if (requestedIds != null && entries.size < allEntries.size) {
                    FileNames.displayName("${share.displayName}-selection.zip")
                } else {
                    baseName
                }
            call.response.headers.append(HttpHeaders.ContentDisposition, attachmentDisposition(zipName))
            call.response.headers.append(HttpHeaders.AcceptRanges, "none")
            call.respondOutputStream(
                contentType = ContentType.Application.Zip,
                status = HttpStatusCode.OK,
                contentLength = null,
            ) {
                withContext(Dispatchers.IO) {
                    writeZip(coroutineContext, share, entries, this@respondOutputStream)
                }
            }
        } catch (error: IOException) {
            respondStreamFailure(call, "ZIP stream failed", error)
        } catch (error: IllegalStateException) {
            respondStreamFailure(call, "ZIP stream failed", error)
        } catch (error: SecurityException) {
            respondStreamFailure(call, "ZIP stream failed", error)
        } finally {
            downloadGate.release()
        }
    }

    private fun parseIdList(raw: String?): Set<String>? {
        if (raw.isNullOrBlank()) return null
        return raw
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 128 }
            .toSet()
            .takeIf { it.isNotEmpty() }
    }

    suspend fun handlePreview(
        call: ApplicationCall,
        token: String,
        entryId: String,
    ) {
        if (!call.enforceGeneralLimits()) return
        val share = resolveAvailable(token) ?: return respondNotFound(call)
        if (!authorizeDownload(call, share)) return
        if (entryId.isBlank() || entryId.length > 128) return respondNotFound(call)

        val entry = findEntry(call, share, entryId) ?: return
        // Embed mode drops the page chrome so the share page can show the preview in its dialog.
        val embed = call.request.queryParameters["embed"] == "1"

        // Documents are wrapped so the browser never executes shared markup directly.
        if (isDocument(entry.mimeType, entry.name)) {
            val bytes = readAllForPreview(call, share, entryId) ?: return
            val safeToken = FileNames.encodePathSegment(share.token)
            val encodedId = FileNames.encodePathSegment(entry.id)
            respondHtml(
                call,
                renderer.renderPreview(
                    language = language(call),
                    title = entry.name,
                    bodyClass = documentBodyClass(entry.mimeType, entry.name),
                    bodyHtml = documentPreviewHtml(entry.mimeType, entry.name, bytes),
                    downloadHref = "/s/$safeToken/download/$encodedId",
                    backHref = "/s/$safeToken",
                    embed = embed,
                ),
            )
            return
        }

        val mediaKind = mediaKindOf(entry.mimeType, entry.name)
        if (mediaKind != MediaKind.OTHER) {
            val safeToken = FileNames.encodePathSegment(share.token)
            val encodedId = FileNames.encodePathSegment(entry.id)
            val directHref = "/s/$safeToken/preview/$encodedId"
            // Images render straight from this URL so thumbnails and shares keep working without
            // JavaScript. Players get a small shell page whose media element asks for ?raw=1,
            // because a browser cannot send custom headers from <video>/<audio>.
            if (mediaKind != MediaKind.IMAGE && call.request.queryParameters["raw"] != "1") {
                // Opening the player is the viewing act: it consumes one download and hands the
                // media element a ticket so the follow-up range requests stay free.
                if (!store.tryReserveDownload(share.id, clock.millis())) {
                    respondNotFound(call)
                    return
                }
                setPlaybackTicket(call, share, entryId)
                respondHtml(
                    call,
                    renderer.renderMediaViewer(
                        language = language(call),
                        title = entry.name,
                        kind = mediaKind.htmlKind(),
                        srcHref = "$directHref?raw=1",
                        downloadHref = "/s/$safeToken/download/$encodedId",
                        backHref = "/s/$safeToken",
                        embed = embed,
                    ),
                )
                return
            }
            // Animated images must keep every frame: re-encoding would freeze them. Their list
            // thumbnail may still be optimized, since a thumbnail is expected to be static.
            val animatedImage = mayAnimateImage(entry.mimeType, entry.name)
            val thumbnailRequest = hasValidThumbnailToken(call, share, entryId)
            if (mediaKind == MediaKind.IMAGE && imageTransformer != null && (!animatedImage || thumbnailRequest)) {
                serveOptimizedImage(call, share, entryId)
                return
            }
            streamPreviewAsset(
                call = call,
                share = share,
                entryId = entryId,
                inline = true,
                playback = mediaKind != MediaKind.IMAGE,
                thumbnail = mediaKind == MediaKind.IMAGE,
            )
            return
        }

        // Everything else is served inline-safe: unknown or markup types turn into an attachment.
        streamPreviewAsset(call, share, entryId, inline = false, playback = false)
    }

    /**
     * Re-encodes images before they travel over the tunnel: list thumbnails become small previews
     * and the full view is capped, so a 6 MB camera photo no longer has to load untouched.
     */
    private suspend fun serveOptimizedImage(
        call: ApplicationCall,
        share: ShareRecord,
        entryId: String,
    ) {
        val transformer = imageTransformer ?: return
        val thumbnail = hasValidThumbnailToken(call, share, entryId)
        if (thumbnail) {
            val cached = cachedThumbnail(share, entryId)
            if (cached != null) {
                call.response.headers.append(HttpHeaders.ContentDisposition, inlineDisposition(cached.name))
                call.respondBytes(cached.bytes, ContentType.parse(cached.mimeType), HttpStatusCode.OK)
                return
            }
        }
        if (!downloadGate.tryAcquire()) {
            respondBusy(call)
            return
        }
        var source: ByteArray? = null
        var mimeType = "application/octet-stream"
        var name = entryId
        var oversized = false
        try {
            val opened = content.open(share, entryId)
            if (opened == null) {
                respondNotFound(call)
                return
            }
            try {
                mimeType = opened.mimeType
                name = opened.name
                val limit = config.maxPreviewImageBytes
                val declared = opened.sizeBytes
                oversized = declared != null && declared > limit
                if (!oversized) {
                    source = readAtMost(opened.stream, limit)
                    oversized = source == null
                }
            } finally {
                closeQuietly(opened.stream)
            }
        } catch (error: ContentUnavailableException) {
            logger.warn("Http", "Content unavailable during preview", error)
            respondNotFound(call)
            return
        } catch (error: IOException) {
            respondStreamFailure(call, "Preview failed", error)
            return
        } catch (error: IllegalStateException) {
            respondStreamFailure(call, "Preview failed", error)
            return
        } finally {
            downloadGate.release()
        }

        val original = source
        if (oversized || original == null) {
            // Too large to decode safely: stream the untouched file instead.
            streamPreviewAsset(call, share, entryId, inline = true, playback = false, thumbnail = true)
            return
        }

        if (!thumbnail && !store.tryReserveDownload(share.id, clock.millis())) {
            respondNotFound(call)
            return
        }

        if (!thumbnail && isAnimatedPng(original)) {
            // APNG can be named .png; re-encoding would keep only the first frame.
            call.response.headers.append(HttpHeaders.ContentDisposition, inlineDisposition(name))
            call.respondBytes(original, ContentType.Image.PNG, HttpStatusCode.OK)
            return
        }

        val encoded =
            try {
                withContext(Dispatchers.IO) {
                    transformer.downscale(
                        source = original,
                        maxDimension = if (thumbnail) THUMBNAIL_MAX_DIMENSION else PREVIEW_MAX_DIMENSION,
                        quality = if (thumbnail) THUMBNAIL_QUALITY else PREVIEW_QUALITY,
                    )
                }
            } catch (error: IllegalArgumentException) {
                logger.warn("Http", "Image re-encode failed", error)
                null
            } catch (error: IllegalStateException) {
                logger.warn("Http", "Image re-encode failed", error)
                null
            } catch (_: OutOfMemoryError) {
                logger.warn("Http", "Image too large to re-encode")
                null
            }

        val contentType =
            encoded?.let { runCatching { ContentType.parse(it.mimeType) }.getOrNull() }
                ?: inlineContentType(safeContentType(mimeType), name)
        if (thumbnail && encoded != null) {
            rememberThumbnail(share, entryId, encoded.bytes, encoded.mimeType, name)
        }
        call.response.headers.append(HttpHeaders.ContentDisposition, inlineDisposition(name))
        call.respondBytes(encoded?.bytes ?: original, contentType, HttpStatusCode.OK)
    }

    private fun readAtMost(
        input: InputStream,
        limit: Long,
    ): ByteArray? {
        val buffer = ByteArrayOutputStream(COPY_BUFFER)
        val chunk = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > limit) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private suspend fun streamPreviewAsset(
        call: ApplicationCall,
        share: ShareRecord,
        entryId: String,
        inline: Boolean,
        playback: Boolean,
        thumbnail: Boolean = false,
    ) {
        if (!downloadGate.tryAcquire()) {
            respondBusy(call)
            return
        }

        var opened: OpenedContent? = null
        try {
            opened = content.open(share, entryId)
            if (opened == null) {
                respondNotFound(call)
                return
            }
            val size = opened.sizeBytes?.takeIf { it > 0L }
            val selection = RangeParser.parse(call.request.headers[HttpHeaders.Range], size)
            if (selection is RangeSelection.Unsatisfiable) {
                closeQuietly(opened.stream)
                opened = null
                if (size != null) {
                    call.response.headers.append(HttpHeaders.ContentRange, "bytes */$size")
                }
                respondPlain(call, HttpStatusCode.RequestedRangeNotSatisfiable, "Range Not Satisfiable")
                return
            }

            // A playback ticket means this request continues a viewing that was already counted.
            val ticketed = playback && hasValidPlaybackTicket(call, share, entryId)
            // List thumbnails carry a signed token: they decorate the page, they are not downloads.
            val thumbnailed = thumbnail && hasValidThumbnailToken(call, share, entryId)
            if (!ticketed && !thumbnailed && !store.tryReserveDownload(share.id, clock.millis())) {
                closeQuietly(opened.stream)
                opened = null
                respondNotFound(call)
                return
            }
            if (playback && !ticketed) {
                setPlaybackTicket(call, share, entryId)
            }

            val safeType = safeContentType(opened.mimeType)
            val contentType = if (inline) inlineContentType(safeType, opened.name) else safeType
            val disposition = if (inline) inlineDisposition(opened.name) else attachmentDisposition(opened.name)
            call.response.headers.append(HttpHeaders.ContentDisposition, disposition)
            call.response.headers.append(HttpHeaders.AcceptRanges, if (size != null) "bytes" else "none")

            val status: HttpStatusCode
            val contentLength: Long?
            val skip: Long
            val length: Long?
            when (selection) {
                is RangeSelection.Partial -> {
                    status = HttpStatusCode.PartialContent
                    contentLength = selection.length
                    skip = selection.start
                    length = selection.length
                    call.response.headers.append(
                        HttpHeaders.ContentRange,
                        "bytes ${selection.start}-${selection.endInclusive}/$size",
                    )
                }

                RangeSelection.Full, RangeSelection.Unsatisfiable, RangeSelection.Unsupported -> {
                    status = HttpStatusCode.OK
                    contentLength = size
                    skip = 0L
                    length = size
                }
            }

            val stream = opened.stream
            try {
                call.respondOutputStream(contentType = contentType, status = status, contentLength = contentLength) {
                    withContext(Dispatchers.IO) {
                        copyBytes(
                            context = coroutineContext,
                            input = stream,
                            output = this@respondOutputStream,
                            skipBytes = skip,
                            lengthBytes = length,
                        )
                    }
                }
            } finally {
                closeQuietly(stream)
                opened = null
            }
        } catch (error: ContentUnavailableException) {
            logger.warn("Http", "Content unavailable during preview", error)
            respondNotFound(call)
        } catch (error: IOException) {
            respondStreamFailure(call, "Preview stream failed", error)
        } catch (error: IllegalStateException) {
            respondStreamFailure(call, "Preview stream failed", error)
        } catch (error: SecurityException) {
            respondStreamFailure(call, "Preview stream failed", error)
        } finally {
            opened?.let { closeQuietly(it.stream) }
            downloadGate.release()
        }
    }

    /**
     * Resolves the requested entry without listing the whole share again. Null means the response
     * was already written (not found, gone, or provider failure).
     */
    private suspend fun findEntry(
        call: ApplicationCall,
        share: ShareRecord,
        entryId: String,
    ): SharedEntry? =
        try {
            content.find(share, entryId) ?: run {
                respondNotFound(call)
                null
            }
        } catch (error: ContentUnavailableException) {
            logger.warn("Http", "Shared content is no longer available", error)
            respondNotFound(call)
            null
        } catch (error: IOException) {
            logger.error("Http", "Unable to resolve shared content", error)
            respondServerError(call)
            null
        } catch (error: IllegalStateException) {
            logger.error("Http", "Unable to resolve shared content", error)
            respondServerError(call)
            null
        }

    private suspend fun readAllForPreview(
        call: ApplicationCall,
        share: ShareRecord,
        entryId: String,
    ): ByteArray? {
        if (!downloadGate.tryAcquire()) {
            respondBusy(call)
            return null
        }
        try {
            val opened =
                try {
                    content.open(share, entryId)
                } catch (error: ContentUnavailableException) {
                    logger.warn("Http", "Content unavailable during preview", error)
                    respondNotFound(call)
                    return null
                }
            if (opened == null) {
                respondNotFound(call)
                return null
            }
            opened.stream.use { input ->
                val limit = config.maxPreviewDocumentBytes
                val buffer = ByteArrayOutputStream(limit.coerceAtMost(4L * 1024L * 1024L).toInt() + 1)
                val chunk = ByteArray(COPY_BUFFER)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > limit) {
                        logger.warn("Http", "Preview document exceeds $limit bytes")
                        respondPlain(call, HttpStatusCode.PayloadTooLarge, "Document too large to preview")
                        return null
                    }
                    buffer.write(chunk, 0, read)
                }
                if (!store.tryReserveDownload(share.id, clock.millis())) {
                    respondNotFound(call)
                    return null
                }
                return buffer.toByteArray()
            }
        } catch (error: IOException) {
            respondStreamFailure(call, "Preview failed", error)
            return null
        } finally {
            downloadGate.release()
        }
    }

    private fun isDocument(
        mime: String,
        name: String,
    ): Boolean {
        val lower = mime.lowercase(Locale.ROOT)
        val fileName = name.lowercase(Locale.ROOT)

        // Markup is never wrapped as a document: it could execute inside the preview page.
        if (isUnsafePreviewType(lower, fileName)) return false

        return lower == "application/pdf" ||
            lower.startsWith("text/") ||
            fileName.endsWith(".txt") ||
            fileName.endsWith(".md") ||
            fileName.endsWith(".log") ||
            fileName.endsWith(".pdf") ||
            DocumentText.isSupported(lower, fileName)
    }

    private fun documentBodyClass(
        mime: String,
        name: String,
    ): String {
        val lower = mime.lowercase(Locale.ROOT)
        val fileName = name.lowercase(Locale.ROOT)
        return if (lower == "application/pdf" || fileName.endsWith(".pdf")) "document-pdf" else "document-text"
    }

    private fun documentPreviewHtml(
        mime: String,
        name: String,
        bytes: ByteArray,
    ): String {
        val lower = mime.lowercase(Locale.ROOT)
        val fileName = name.lowercase(Locale.ROOT)
        if (lower == "application/pdf" || fileName.endsWith(".pdf")) {
            val encoded = Base64.getEncoder().encodeToString(bytes)
            return "<iframe class=\"doc-frame\" src=\"data:application/pdf;base64,$encoded\"></iframe>"
        }
        if (DocumentText.isSupported(lower, fileName)) {
            DocumentHtml.render(lower, fileName, bytes)?.let { structured ->
                return "<div class=\"doc-html\">$structured</div>"
            }
            val extracted = DocumentText.extract(lower, fileName, bytes)
            return if (extracted == null) {
                "<p class=\"meta\">Preview unavailable. Download the file to open it.</p>"
            } else {
                "<pre class=\"doc-text\">${FileNames.escapeHtml(extracted)}</pre>"
            }
        }
        val text = String(bytes, Charsets.UTF_8)
        return "<pre class=\"doc-text\">${FileNames.escapeHtml(text)}</pre>"
    }

    private fun attachmentDisposition(fileName: String): String =
        FileNames
            .contentDisposition(fileName)
            .removePrefix("attachment; ")
            .let { "attachment; $it" }

    private fun inlineDisposition(fileName: String): String =
        FileNames
            .contentDisposition(fileName)
            .removePrefix("attachment; ")
            .let { "inline; $it" }

    private enum class MediaKind {
        IMAGE,
        VIDEO,
        AUDIO,
        OTHER,
    }

    private fun MediaKind.htmlKind(): String =
        when (this) {
            MediaKind.IMAGE -> "image"
            MediaKind.VIDEO -> "video"
            MediaKind.AUDIO -> "audio"
            MediaKind.OTHER -> "file"
        }

    /** Formats that can carry multiple frames; re-encoding would keep only the first one. */
    private fun mayAnimateImage(
        mime: String,
        name: String,
    ): Boolean {
        val lower = mime.lowercase(Locale.ROOT).trim()
        val fileName = name.lowercase(Locale.ROOT)
        return lower == "image/gif" ||
            lower == "image/webp" ||
            lower == "image/apng" ||
            fileName.endsWith(".gif") ||
            fileName.endsWith(".webp") ||
            fileName.endsWith(".apng")
    }

    /**
     * APNG files usually keep a .png name, so the name alone cannot reveal them. Walk the PNG
     * chunks and look for the animation control chunk (acTL) before the first image chunk.
     */
    private fun isAnimatedPng(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        for (index in PNG_SIGNATURE.indices) {
            if (bytes[index] != PNG_SIGNATURE[index]) return false
        }
        var offset = PNG_SIGNATURE.size
        while (offset + 8 <= bytes.size) {
            val length = readBigEndianInt(bytes, offset)
            if (length < 0 || offset + 12 + length > bytes.size) return false
            when (String(bytes, offset + 4, 4, Charsets.US_ASCII)) {
                "acTL" -> return true
                "IDAT", "IEND" -> return false
            }
            offset += 12 + length
        }
        return false
    }

    private fun readBigEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** Scriptable formats must always end up as an attachment download, never inline. */
    private fun isUnsafePreviewType(
        mimeLower: String,
        nameLower: String,
    ): Boolean =
        mimeLower.startsWith("text/html") ||
            mimeLower.startsWith("application/xhtml") ||
            mimeLower.startsWith("image/svg") ||
            nameLower.endsWith(".html") ||
            nameLower.endsWith(".htm") ||
            nameLower.endsWith(".xhtml") ||
            nameLower.endsWith(".svg")

    private fun mediaKindOf(
        mime: String,
        name: String,
    ): MediaKind {
        val lower = mime.lowercase(Locale.ROOT)
        val fileName = name.lowercase(Locale.ROOT)
        if (isUnsafePreviewType(lower, fileName)) return MediaKind.OTHER
        return when {
            lower.startsWith("image/") -> MediaKind.IMAGE
            lower.startsWith("video/") -> MediaKind.VIDEO
            lower.startsWith("audio/") -> MediaKind.AUDIO
            fileName.endsWith(".jpg") ||
                fileName.endsWith(".jpeg") ||
                fileName.endsWith(".png") ||
                fileName.endsWith(".gif") ||
                fileName.endsWith(".webp") ||
                fileName.endsWith(".avif") ||
                fileName.endsWith(".bmp") -> MediaKind.IMAGE
            fileName.endsWith(".mp4") ||
                fileName.endsWith(".mov") ||
                fileName.endsWith(".mkv") ||
                fileName.endsWith(".webm") ||
                fileName.endsWith(".avi") -> MediaKind.VIDEO
            fileName.endsWith(".mp3") ||
                fileName.endsWith(".wav") ||
                fileName.endsWith(".ogg") ||
                fileName.endsWith(".m4a") ||
                fileName.endsWith(".flac") ||
                fileName.endsWith(".aac") -> MediaKind.AUDIO
            else -> MediaKind.OTHER
        }
    }

    private suspend fun renderSharePage(
        call: ApplicationCall,
        share: ShareRecord,
    ) {
        val entries =
            try {
                content.list(share)
            } catch (error: ContentUnavailableException) {
                logger.warn("Http", "Shared content is no longer available", error)
                respondNotFound(call)
                return
            } catch (error: IOException) {
                logger.error("Http", "Unable to list shared content", error)
                respondServerError(call)
                return
            } catch (error: IllegalStateException) {
                logger.error("Http", "Unable to list shared content", error)
                respondServerError(call)
                return
            }
        if (entries.isEmpty()) {
            respondNotFound(call)
            return
        }
        respondHtml(
            call,
            renderer.renderShare(
                share = share,
                entries = entries,
                language = language(call),
                thumbnailToken = { entryId -> signer.issueScoped(thumbnailScope(share, entryId)) },
            ),
        )
    }

    private suspend fun authorizeDownload(
        call: ApplicationCall,
        share: ShareRecord,
    ): Boolean {
        if (!share.hasPassword) return true
        if (hasValidSession(call, share)) return true
        respondHtml(
            call,
            renderer.renderUnlock(language(call), share.token, wrongPassword = false),
            status = HttpStatusCode.Unauthorized,
        )
        return false
    }

    private suspend fun resolveAvailable(token: String): ShareRecord? {
        if (!TokenGenerator.isWellFormed(token)) {
            TokenGenerator.constantTimeMiss(token)
            return null
        }
        val share = store.findByToken(token)
        val matches =
            if (share == null) {
                TokenGenerator.constantTimeMiss(token)
                false
            } else {
                TokenGenerator.constantTimeEquals(share.token, token)
            }
        if (share == null || !matches) return null
        if (!share.isAvailable(clock.millis())) return null
        return share
    }

    private fun hasValidSession(
        call: ApplicationCall,
        share: ShareRecord,
    ): Boolean {
        val value = cookieValue(call, SESSION_COOKIE) ?: return false
        return signer.isValid(value, share.id)
    }

    private fun playbackScope(
        share: ShareRecord,
        entryId: String,
    ): String = "play:${share.id}:$entryId"

    private fun thumbnailScope(
        share: ShareRecord,
        entryId: String,
    ): String = "thumb:${share.id}:$entryId"

    private fun cachedThumbnail(
        share: ShareRecord,
        entryId: String,
    ): CachedThumbnail? =
        synchronized(thumbnailCache) {
            thumbnailCache[thumbnailKey(share, entryId)]
                ?.takeIf { clock.millis() - it.createdAtMs <= THUMBNAIL_CACHE_TTL_MS }
        }

    private fun rememberThumbnail(
        share: ShareRecord,
        entryId: String,
        bytes: ByteArray,
        mimeType: String,
        name: String,
    ) {
        if (bytes.size > THUMBNAIL_CACHE_MAX_ENTRY_BYTES) return
        synchronized(thumbnailCache) {
            thumbnailCache[thumbnailKey(share, entryId)] =
                CachedThumbnail(bytes = bytes, mimeType = mimeType, name = name, createdAtMs = clock.millis())
        }
    }

    private fun thumbnailKey(
        share: ShareRecord,
        entryId: String,
    ): String = "${share.id}:$entryId"

    private class CachedThumbnail(
        val bytes: ByteArray,
        val mimeType: String,
        val name: String,
        val createdAtMs: Long,
    )

    private fun hasValidThumbnailToken(
        call: ApplicationCall,
        share: ShareRecord,
        entryId: String,
    ): Boolean {
        val value = call.request.queryParameters["thumb"] ?: return false
        return signer.isValidScoped(value, thumbnailScope(share, entryId))
    }

    private fun hasValidPlaybackTicket(
        call: ApplicationCall,
        share: ShareRecord,
        entryId: String,
    ): Boolean {
        val value = cookieValue(call, PLAYBACK_COOKIE) ?: return false
        return signer.isValidScoped(value, playbackScope(share, entryId))
    }

    private fun setPlaybackTicket(
        call: ApplicationCall,
        share: ShareRecord,
        entryId: String,
    ) {
        val value = signer.issueScoped(playbackScope(share, entryId))
        val secure = if (config.secureCookies) "; Secure" else ""
        val cookie =
            "$PLAYBACK_COOKIE=$value; Path=/s/${share.token}; " +
                "Max-Age=${config.sessionTtlMillis / 1_000}; HttpOnly; SameSite=Lax$secure"
        call.response.headers.append(HttpHeaders.SetCookie, cookie)
    }

    private fun setSessionCookie(
        call: ApplicationCall,
        share: ShareRecord,
    ) {
        val value = signer.issue(share.id)
        val secure = if (config.secureCookies) "; Secure" else ""
        val cookie =
            "$SESSION_COOKIE=$value; Path=/s/${share.token}; " +
                "Max-Age=${config.sessionTtlMillis / 1_000}; HttpOnly; SameSite=Lax$secure"
        call.response.headers.append(HttpHeaders.SetCookie, cookie)
    }

    private suspend fun ApplicationCall.respondRedirectToShare(token: String) {
        response.headers.append(HttpHeaders.Location, "/s/$token")
        respondText("", status = HttpStatusCode.SeeOther)
    }

    private suspend fun ApplicationCall.enforceGeneralLimits(): Boolean {
        if (requestHeaderBytes() > config.maxHeaderBytes) {
            respondPlain(this, HttpStatusCode.RequestHeaderFieldTooLarge, "Request Header Fields Too Large")
            return false
        }
        val decision = generalLimiter.tryAcquire("http:${clientIp(this)}", generalPolicy)
        if (!decision.allowed) {
            respondRateLimited(this, decision.retryAfterSeconds)
            return false
        }
        return true
    }

    private fun ApplicationCall.requestHeaderBytes(): Int =
        request.headers.entries().sumOf { entry ->
            entry.key.length + 4 + entry.value.sumOf { it.length + 1 }
        }

    private fun clientIp(call: ApplicationCall): String {
        val remote = call.request.origin.remoteHost
        if (!isLoopback(remote)) return remote.take(64)
        val forwarded = call.request.headers["CF-Connecting-IP"]?.trim()
        return if (isIpLiteral(forwarded)) forwarded.orEmpty().take(64) else remote.take(64)
    }

    private fun isLoopback(host: String): Boolean =
        when (host) {
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost" -> true
            else -> false
        }

    private fun isIpLiteral(value: String?): Boolean {
        if (value.isNullOrBlank() || value.length > 45) return false
        if (value.count { it == ':' } >= 2) {
            return value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        }
        val parts = value.split('.')
        return parts.size == 4 &&
            parts.all { part ->
                val number = part.toIntOrNull()
                part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) && number != null && number in 0..255
            }
    }

    private fun language(call: ApplicationCall): PublicPageRenderer.Language =
        renderer.languageFor(call.request.headers[HttpHeaders.AcceptLanguage])

    suspend fun respondNotFound(call: ApplicationCall) {
        respondHtml(
            call,
            renderer.renderNotFound(language(call)),
            status = HttpStatusCode.NotFound,
        )
    }

    suspend fun respondServerError(call: ApplicationCall) {
        respondHtml(
            call,
            renderer.renderServerError(language(call)),
            status = HttpStatusCode.InternalServerError,
        )
    }

    /**
     * Unauthenticated liveness probe used by the app to test the full public path
     * (browser → Cloudflare → tunnel → local server). It deliberately carries no data and no
     * share token, so a leaked request tells an attacker nothing.
     */
    suspend fun handleHealth(call: ApplicationCall) {
        call.respond(HttpStatusCode.NoContent)
    }

    private suspend fun respondRateLimited(
        call: ApplicationCall,
        retryAfterSeconds: Long,
    ) {
        call.response.headers.append(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
        respondHtml(
            call,
            renderer.renderRateLimited(language(call), retryAfterSeconds),
            status = HttpStatusCode.TooManyRequests,
        )
    }

    private suspend fun respondBusy(call: ApplicationCall) {
        call.response.headers.append(HttpHeaders.RetryAfter, "5")
        respondPlain(call, HttpStatusCode.ServiceUnavailable, "Too many concurrent downloads")
    }

    suspend fun handleFavicon(call: ApplicationCall) {
        call.respondText(
            text = FAVICON_SVG,
            contentType = ContentType.parse("image/svg+xml; charset=utf-8"),
            status = HttpStatusCode.OK,
        )
    }

    private suspend fun respondHtml(
        call: ApplicationCall,
        html: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        call.response.headers.append("Content-Security-Policy", CSP)
        call.respondText(
            text = html,
            contentType = ContentType.Text.Html.withCharset(Charsets.UTF_8),
            status = status,
        )
    }

    private suspend fun respondPlain(
        call: ApplicationCall,
        status: HttpStatusCode,
        body: String,
    ) {
        call.respondText(
            text = body,
            contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
            status = status,
        )
    }

    private fun cookieValue(
        call: ApplicationCall,
        name: String,
    ): String? {
        val cookies =
            call.request.headers
                .getAll(HttpHeaders.Cookie)
                .orEmpty()
        return cookies
            .asSequence()
            .flatMap { it.split(';').asSequence() }
            .map { it.trim() }
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseFormValue(
        body: String,
        name: String,
    ): String? {
        if (body.isEmpty()) return null
        return body
            .split('&')
            .asSequence()
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) return@mapNotNull null
                try {
                    val key = URLDecoder.decode(pair.substring(0, index), Charsets.UTF_8.name())
                    val value = URLDecoder.decode(pair.substring(index + 1), Charsets.UTF_8.name())
                    if (key == name) value else null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }.firstOrNull()
    }

    private fun safeContentType(mime: String?): ContentType {
        val normalized = mime?.trim()?.lowercase()
        if (normalized.isNullOrEmpty() ||
            normalized.startsWith("text/html") ||
            normalized.startsWith("application/xhtml") ||
            normalized.startsWith("image/svg")
        ) {
            return ContentType.Application.OctetStream
        }
        return runCatching { ContentType.parse(normalized) }
            .getOrDefault(ContentType.Application.OctetStream)
    }

    private fun inlineContentType(
        type: ContentType,
        name: String,
    ): ContentType {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            type.contentType.equals("image", ignoreCase = true) ||
                type.contentType.equals("video", ignoreCase = true) ||
                type.contentType.equals("audio", ignoreCase = true) -> type

            type == ContentType.Application.Pdf -> type
            type.contentType.equals("text", ignoreCase = true) -> type
            type == ContentType.Application.Json || type == ContentType.Application.Xml -> type
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log") -> ContentType.Text.Plain
            else -> ContentType.Application.OctetStream
        }
    }

    private suspend fun copyBytes(
        context: kotlin.coroutines.CoroutineContext,
        input: InputStream,
        output: OutputStream,
        skipBytes: Long,
        lengthBytes: Long?,
    ) {
        input.use {
            // Prefer skip() so a seekable provider jumps straight to the requested range instead
            // of reading every byte before it. Pipes may report 0; then discard until they advance.
            var skipped = 0L
            val discard = ByteArray(DISCARD_BUFFER)
            while (skipped < skipBytes) {
                context.ensureActive()
                val step = input.skip(skipBytes - skipped)
                if (step > 0) {
                    skipped += step
                    continue
                }
                val wanted = min(discard.size.toLong(), skipBytes - skipped).toInt()
                val read = input.read(discard, 0, wanted)
                if (read < 0) return
                skipped += read
            }

            val buffer = ByteArray(COPY_BUFFER)
            var remaining = lengthBytes
            while (remaining == null || remaining > 0L) {
                context.ensureActive()
                val wanted = if (remaining == null) buffer.size else min(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, wanted)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                if (remaining != null) remaining -= read
            }
            output.flush()
        }
    }

    private suspend fun writeZip(
        context: kotlin.coroutines.CoroutineContext,
        share: ShareRecord,
        entries: List<SharedEntry>,
        output: OutputStream,
    ) {
        ZipOutputStream(NonClosingOutputStream(output), Charsets.UTF_8).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            val usedNames = mutableSetOf<String>()
            entries.forEach { entry ->
                context.ensureActive()
                val opened =
                    try {
                        content.open(share, entry.id)
                    } catch (error: ContentUnavailableException) {
                        unreadableEntry(error)
                    } catch (error: IOException) {
                        unreadableEntry(error)
                    } catch (error: IllegalStateException) {
                        unreadableEntry(error)
                    } catch (error: SecurityException) {
                        unreadableEntry(error)
                    } ?: return@forEach
                opened.stream.use { input ->
                    val zipEntry = ZipEntry(FileNames.uniqueZipEntry(entry.relativePath, usedNames))
                    zip.putNextEntry(zipEntry)
                    copyEntry(context, input, zip)
                    zip.closeEntry()
                }
            }
            zip.finish()
        }
    }

    private suspend fun copyEntry(
        context: kotlin.coroutines.CoroutineContext,
        input: InputStream,
        output: OutputStream,
    ) {
        val buffer = ByteArray(COPY_BUFFER)
        while (true) {
            context.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) output.write(buffer, 0, read)
        }
    }

    private suspend fun respondStreamFailure(
        call: ApplicationCall,
        message: String,
        error: Throwable,
    ) {
        // A browser that closes the tab, or an edge that drops an idle stream, is not a server
        // fault. Logging a stack trace for it buries the failures that actually need attention.
        if (isClientDisconnect(error)) {
            logger.debug("Http", "$message: client closed the connection")
        } else {
            logger.warn("Http", message, error)
        }
        if (!call.response.isCommitted) respondServerError(call)
    }

    private fun isClientDisconnect(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            when (current::class.java.simpleName) {
                "ClosedByteChannelException", "ChannelWriteException", "AbortedException" -> return true
            }
            val text = current.message.orEmpty().lowercase(Locale.ROOT)
            if (text.contains("connection reset") ||
                text.contains("broken pipe") ||
                text.contains("stream closed") ||
                text.contains("client disconnected")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun unreadableEntry(error: Throwable): OpenedContent? {
        logger.warn("Http", "Skipping unreadable ZIP entry", error)
        return null
    }

    private fun closeQuietly(stream: InputStream) {
        runCatching { stream.close() }
    }

    /** Prevents ZipOutputStream.close() from closing Ktor's response stream. */
    private class NonClosingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        override fun write(b: Int) = delegate.write(b)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = delegate.write(b, off, len)

        override fun flush() = delegate.flush()

        override fun close() {
            delegate.flush()
        }
    }

    private companion object {
        const val SESSION_COOKIE = "ps_session"
        const val PLAYBACK_COOKIE = "ps_play"
        const val THUMBNAIL_MAX_DIMENSION = 320
        const val THUMBNAIL_QUALITY = 74
        const val THUMBNAIL_CACHE_ENTRIES = 96
        const val THUMBNAIL_CACHE_MAX_ENTRY_BYTES = 256 * 1024
        const val THUMBNAIL_CACHE_TTL_MS = 10L * 60L * 1_000L
        const val PREVIEW_MAX_DIMENSION = 1600
        const val PREVIEW_QUALITY = 82
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        const val COPY_BUFFER = 64 * 1024
        const val DISCARD_BUFFER = 16 * 1024
        const val CSP =
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self'; " +
                "media-src 'self'; frame-src 'self' data:; connect-src 'none'; form-action 'self'; " +
                "base-uri 'none'; frame-ancestors 'self'"
        val FAVICON_SVG =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
              <defs>
                <linearGradient id="g" x1="0" y1="0" x2="64" y2="64" gradientUnits="userSpaceOnUse">
                  <stop stop-color="#127A65"/>
                  <stop offset=".55" stop-color="#0B5C4B"/>
                  <stop offset="1" stop-color="#073B31"/>
                </linearGradient>
              </defs>
              <rect width="64" height="64" rx="15" fill="url(#g)"/>
              <g fill="none" stroke="#FFFFFF" stroke-width="5.4" stroke-linecap="round" stroke-linejoin="round">
                <path d="M32 14.5v23.4"/>
                <path d="M22.4 24.1 32 14.5l9.6 9.6"/>
                <path d="M17.8 32.6v7.2c0 7.5 6.4 13.6 14.2 13.6s14.2-6.1 14.2-13.6v-7.2"/>
              </g>
            </svg>
            """.trimIndent()
    }
}
