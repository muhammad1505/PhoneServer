package dev.pocketshare.server

import dev.pocketshare.core.http.FileNames
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.core.model.ShareRecord
import dev.pocketshare.core.share.SharedEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders the public HTML surface. Styling and the small progressive-enhancement script are inline,
 * and no external resources are loaded, so a share page works from a single origin. The plain
 * download links stay usable when JavaScript is disabled.
 */
class PublicPageRenderer {
    enum class Language {
        INDONESIAN,
        ENGLISH,
    }

    private data class Copy(
        val htmlLang: String,
        val locale: Locale,
        val brandTagline: String,
        val files: String,
        val folder: String,
        val expires: String,
        val unlimited: String,
        val downloadsLeft: String,
        val downloadAll: String,
        val downloadSelected: String,
        val preview: String,
        val open: String,
        val selectAll: String,
        val selectNone: String,
        val selectedCount: String,
        val download: String,
        val protectedTitle: String,
        val protectedBody: String,
        val password: String,
        val showPassword: String,
        val hidePassword: String,
        val unlock: String,
        val wrongPassword: String,
        val rateTitle: String,
        val rateBody: String,
        val notFoundTitle: String,
        val notFoundBody: String,
        val serverTitle: String,
        val serverBody: String,
        val footer: String,
        val sizeUnknown: String,
        val noExpiry: String,
        val downloadsUsed: String,
        val previewDownload: String,
        val previewBack: String,
        val previewFullscreen: String,
        val previewClose: String,
        val previewPrev: String,
        val previewNext: String,
        val previewTooLarge: String,
    )

    private fun copyFor(language: Language): Copy =
        when (language) {
            Language.INDONESIAN ->
                Copy(
                    htmlLang = "id",
                    locale = Locale.forLanguageTag("id-ID"),
                    brandTagline = "Berbagi file privat",
                    files = "file",
                    folder = "folder",
                    expires = "Berlaku hingga",
                    unlimited = "Tanpa batas waktu",
                    downloadsLeft = "Sisa unduhan",
                    downloadAll = "Unduh semua (ZIP)",
                    downloadSelected = "Unduh terpilih",
                    preview = "Pratinjau",
                    open = "Buka",
                    selectAll = "Pilih semua",
                    selectNone = "Batal pilih",
                    selectedCount = "Terpilih",
                    download = "Unduh",
                    protectedTitle = "Konten terlindungi",
                    protectedBody = "Masukkan kata sandi yang diberikan pemilik tautan untuk melihat dan mengunduh file.",
                    password = "Kata sandi",
                    showPassword = "Tampilkan kata sandi",
                    hidePassword = "Sembunyikan kata sandi",
                    unlock = "Buka",
                    wrongPassword = "Kata sandi salah. Coba lagi.",
                    rateTitle = "Terlalu banyak percobaan",
                    rateBody = "Demi keamanan, coba lagi dalam beberapa saat.",
                    notFoundTitle = "Tautan tidak ditemukan",
                    notFoundBody = "Tautan ini tidak tersedia, sudah kedaluwarsa, atau telah dicabut.",
                    serverTitle = "Terjadi kesalahan",
                    serverBody = "Server tidak dapat memproses permintaan ini. Coba lagi nanti.",
                    footer =
                        "Dibagikan langsung dari ponsel pemilik tautan melalui Pocket Share. " +
                            "Pemilik tautan bertanggung jawab atas file yang dibagikan.",
                    sizeUnknown = "ukuran tidak diketahui",
                    noExpiry = "Tanpa kedaluwarsa",
                    downloadsUsed = "Terunduh",
                    previewDownload = "Unduh file ini",
                    previewBack = "Kembali ke daftar",
                    previewFullscreen = "Layar penuh",
                    previewClose = "Tutup",
                    previewPrev = "Sebelumnya",
                    previewNext = "Berikutnya",
                    previewTooLarge = "File terlalu besar untuk ditampilkan.",
                )

            Language.ENGLISH ->
                Copy(
                    htmlLang = "en",
                    locale = Locale.US,
                    brandTagline = "Private file sharing",
                    files = "file(s)",
                    folder = "folder",
                    expires = "Expires",
                    unlimited = "No time limit",
                    downloadsLeft = "Downloads left",
                    downloadAll = "Download all (ZIP)",
                    downloadSelected = "Download selected",
                    preview = "Preview",
                    open = "Open",
                    selectAll = "Select all",
                    selectNone = "Select none",
                    selectedCount = "Selected",
                    download = "Download",
                    protectedTitle = "Protected content",
                    protectedBody = "Enter the password from the link owner to view and download the files.",
                    password = "Password",
                    showPassword = "Show password",
                    hidePassword = "Hide password",
                    unlock = "Unlock",
                    wrongPassword = "Wrong password. Try again.",
                    rateTitle = "Too many attempts",
                    rateBody = "For security reasons, please try again later.",
                    notFoundTitle = "Link not found",
                    notFoundBody = "This link is unavailable, expired, or has been revoked.",
                    serverTitle = "Something went wrong",
                    serverBody = "The server could not process this request. Please try again later.",
                    footer =
                        "Shared directly from the link owner's phone with Pocket Share. " +
                            "The link owner is responsible for the shared files.",
                    sizeUnknown = "unknown size",
                    noExpiry = "No expiry",
                    downloadsUsed = "Downloaded",
                    previewDownload = "Download this file",
                    previewBack = "Back to the list",
                    previewFullscreen = "Fullscreen",
                    previewClose = "Close",
                    previewPrev = "Previous",
                    previewNext = "Next",
                    previewTooLarge = "This file is too large to display.",
                )
        }

    fun languageFor(acceptLanguage: String?): Language {
        val first =
            acceptLanguage
                ?.split(',')
                ?.firstOrNull()
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                .orEmpty()
        return if (first == "id" || first.startsWith("id-") || first == "in" || first.startsWith("in-")) {
            Language.INDONESIAN
        } else {
            Language.ENGLISH
        }
    }

    fun renderShare(
        share: ShareRecord,
        entries: List<SharedEntry>,
        language: Language,
        thumbnailToken: (String) -> String? = { null },
    ): String {
        val copy = copyFor(language)
        val token = FileNames.encodePathSegment(share.token)
        val totalSize =
            if (entries.all { it.sizeBytes != null }) {
                entries.sumOf { it.sizeBytes ?: 0L }
            } else {
                null
            }
        val kindLabel = if (share.kind == ShareKind.FOLDER) copy.folder else copy.files
        val meta =
            buildString {
                append(entries.size)
                append(' ')
                append(copy.files)
                append(" · ")
                append(kindLabel)
                if (totalSize != null) {
                    append(" · ")
                    append(formatBytes(totalSize, copy.locale))
                }
            }
        val expiryText = share.expiresAtEpochMs?.let { formatDate(it, copy.locale) } ?: copy.noExpiry
        val remainingDownloads = share.maxDownloads?.let { (it - share.downloadCount).coerceAtLeast(0) }
        val safeShareName = FileNames.escapeHtml(share.displayName)
        val safeMeta = FileNames.escapeHtml(meta)
        val safeExpiryText = FileNames.escapeHtml(expiryText)
        val downloadIcon = icon("download")
        val archiveIcon = icon("archive")
        val eyeIcon = icon("eye")
        val clockIcon = icon("clock")
        val chevronLeftIcon = icon("chevron-left")
        val chevronRightIcon = icon("chevron-right")
        val fullscreenIcon = icon("fullscreen")
        val closeIcon = icon("close")
        val downloadsFact =
            if (remainingDownloads != null) {
                """
                <span class="fact">$downloadIcon<span class="fact-label">${copy.downloadsLeft}</span><span class="fact-value">$remainingDownloads</span></span>
                """.trimIndent()
            } else {
                """
                <span class="fact">$downloadIcon<span class="fact-label">${copy.downloadsUsed}</span><span class="fact-value">${share.downloadCount}</span></span>
                """.trimIndent()
            }

        val zipLink =
            if (entries.size > 1 || share.kind == ShareKind.FOLDER) {
                """<a class="button secondary small compact" href="/s/$token/zip">$archiveIcon<span class="label">${copy.downloadAll}</span></a>"""
            } else {
                ""
            }

        val toolbar =
            if (entries.size > 1 || zipLink.isNotBlank()) {
                """
                <div class="toolbar" role="group">
                  <span class="toolbar-count"><span id="selectedCount">${entries.size}</span> ${copy.selectedCount}</span>
                  <button type="button" class="link-btn" id="selectAllBtn">${copy.selectAll}</button>
                  <button type="button" class="link-btn" id="selectNoneBtn">${copy.selectNone}</button>
                  <span class="spacer"></span>
                  <button type="button" class="button small primary compact" id="downloadSelectedBtn">$downloadIcon<span class="label">${copy.downloadSelected}</span></button>
                  $zipLink
                </div>
                """.trimIndent()
            } else {
                ""
            }

        val fileItems =
            entries.joinToString("\n") { entry ->
                val displayPath =
                    if (share.kind == ShareKind.FOLDER) {
                        entry.relativePath
                    } else {
                        entry.name
                    }
                val size = entry.sizeBytes?.let { formatBytes(it, copy.locale) } ?: copy.sizeUnknown
                val encodedId = FileNames.encodePathSegment(entry.id)
                val downloadHref = "/s/$token/download/$encodedId"
                val media = mediaKindOf(entry)
                val previewHref = "/s/$token/preview/$encodedId"
                val thumbToken = if (media == MediaKind.IMAGE) thumbnailToken(entry.id) else null
                val thumbSrc =
                    if (thumbToken != null) "$previewHref?thumb=${FileNames.escapeHtml(thumbToken)}" else previewHref
                val safeDisplayPath = FileNames.escapeHtml(displayPath)
                val safeSize = FileNames.escapeHtml(size)
                val fileIconSvg = icon(fileIcon(media))
                val thumb =
                    when (media) {
                        MediaKind.IMAGE ->
                            """<img class="thumb" src="$thumbSrc" loading="lazy" decoding="async" alt="" width="34" height="34">"""
                        else ->
                            """<span class="thumb icon-thumb" data-kind="${media.token}" aria-hidden="true">$fileIconSvg</span>"""
                    }
                val actions =
                    when (media) {
                        MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.PDF,
                        MediaKind.TEXT,
                        ->
                            """
                            <a class="icon-btn" href="$previewHref" data-preview-src="$previewHref" data-preview-kind="${media.token}" data-preview-title="$safeDisplayPath" data-preview-download="$downloadHref" aria-label="${copy.preview}" title="${copy.preview}">$eyeIcon</a>
                            <a class="icon-btn" href="$downloadHref" aria-label="${copy.download}" title="${copy.download}">$downloadIcon</a>
                            """.trimIndent()
                        MediaKind.OTHER ->
                            """
                            <a class="icon-btn" href="$downloadHref" aria-label="${copy.download}" title="${copy.download}">$downloadIcon</a>
                            """.trimIndent()
                    }
                val checkbox =
                    if (entries.size > 1) {
                        """
                        <input class="file-check" type="checkbox" checked value="$encodedId" aria-label="$safeDisplayPath">
                        """.trimIndent()
                    } else {
                        ""
                    }
                """
                <li class="file" data-id="$encodedId">
                  <div class="file-main">
                    $checkbox
                    $thumb
                    <span class="file-copy">
                      <span class="file-name"><span class="file-name-text">$safeDisplayPath</span></span>
                      <span class="file-size">$safeSize</span>
                    </span>
                  </div>
                  <span class="file-actions">$actions</span>
                </li>
                """.trimIndent()
            }

        // Previews open in a bounded dialog on this page; the plain links stay usable without JS.
        val previewable = entries.any { mediaKindOf(it) != MediaKind.OTHER }
        val previewDialog =
            if (previewable) {
                """
                <dialog class="preview-dialog" id="previewDialog" aria-labelledby="previewDialogTitle">
                  <div class="preview-panel">
                    <header class="preview-bar">
                      <span class="preview-name" id="previewDialogTitle"></span>
                      <span class="preview-tools">
                        <button class="icon-btn" type="button" id="previewPrevBtn" aria-label="${copy.previewPrev}" title="${copy.previewPrev}" disabled>$chevronLeftIcon</button>
                        <button class="icon-btn" type="button" id="previewNextBtn" aria-label="${copy.previewNext}" title="${copy.previewNext}" disabled>$chevronRightIcon</button>
                        <a class="button small secondary" id="previewDownloadLink" href="#">$downloadIcon<span>${copy.download}</span></a>
                        <button class="icon-btn" type="button" id="previewFullscreenBtn" aria-pressed="false" aria-label="${copy.previewFullscreen}" title="${copy.previewFullscreen}">$fullscreenIcon</button>
                        <button class="icon-btn" type="button" id="previewCloseBtn" aria-label="${copy.previewClose}" title="${copy.previewClose}">$closeIcon</button>
                      </span>
                    </header>
                    <div class="preview-stage">
                      <img class="preview-image" id="previewImage" alt="" hidden>
                      <iframe class="preview-frame" id="previewFrame" title="" hidden></iframe>
                    </div>
                  </div>
                </dialog>
                """.trimIndent()
            } else {
                ""
            }

        return page(
            language = copy,
            title = FileNames.displayName(share.displayName),
            bodyClass = "share",
            content =
                """
                <header class="hero">
                  ${brandHeader(copy)}
                  <h1>$safeShareName</h1>
                  <p class="meta">$safeMeta</p>
                  <div class="facts">
                    <span class="fact">$clockIcon<span class="fact-label">${copy.expires}</span><span class="fact-value">$safeExpiryText</span></span>
                    $downloadsFact
                  </div>
                </header>
                $toolbar
                <ul class="files">
                  $fileItems
                </ul>
                $previewDialog
                <footer class="foot">${FileNames.escapeHtml(copy.footer)}</footer>
                """.trimIndent(),
        )
    }

    fun renderUnlock(
        language: Language,
        token: String,
        wrongPassword: Boolean,
    ): String {
        val copy = copyFor(language)
        val safeToken = FileNames.encodePathSegment(token)
        return page(
            language = copy,
            title = copy.protectedTitle,
            bodyClass = "unlock",
            content =
                """
                <header class="hero center">
                  ${brandHeader(copy)}
                  <h1>${FileNames.escapeHtml(copy.protectedTitle)}</h1>
                  <p class="meta">${FileNames.escapeHtml(copy.protectedBody)}</p>
                </header>
                <form class="unlock-form" method="post" action="/s/$safeToken/unlock">
                  <div class="field">
                    <label for="password">${FileNames.escapeHtml(copy.password)}</label>
                    <div class="password-wrap">
                      <input id="password" name="password" type="password" autocomplete="current-password" maxlength="256" required autofocus>
                      <button id="passwordToggle" class="password-toggle" type="button" aria-controls="password" aria-pressed="false" aria-label="${FileNames.escapeHtml(
                    copy.showPassword,
                )}" data-show-label="${FileNames.escapeHtml(
                    copy.showPassword,
                )}" data-hide-label="${FileNames.escapeHtml(copy.hidePassword)}" hidden>
                        <span class="password-eye">${icon("eye")}</span>
                        <span class="password-eye-off" hidden>${icon("eye-off")}</span>
                      </button>
                    </div>
                  </div>
                  ${if (wrongPassword) "<p class=\"error\">${FileNames.escapeHtml(copy.wrongPassword)}</p>" else ""}
                  <button class="button" type="submit">${icon("unlock")}<span>${FileNames.escapeHtml(copy.unlock)}</span></button>
                </form>
                <footer class="foot">${FileNames.escapeHtml(copy.footer)}</footer>
                """.trimIndent(),
        )
    }

    fun renderNotFound(language: Language): String {
        val copy = copyFor(language)
        return page(
            language = copy,
            title = copy.notFoundTitle,
            bodyClass = "empty",
            content =
                """
                <header class="hero center">
                  ${brandHeader(copy)}
                  <h1>${FileNames.escapeHtml(copy.notFoundTitle)}</h1>
                  <p class="meta">${FileNames.escapeHtml(copy.notFoundBody)}</p>
                </header>
                <footer class="foot">${FileNames.escapeHtml(copy.footer)}</footer>
                """.trimIndent(),
        )
    }

    fun renderRateLimited(
        language: Language,
        retryAfterSeconds: Long,
    ): String {
        val copy = copyFor(language)
        return page(
            language = copy,
            title = copy.rateTitle,
            bodyClass = "empty",
            content =
                """
                <header class="hero center">
                  ${brandHeader(copy)}
                  <h1>${FileNames.escapeHtml(copy.rateTitle)}</h1>
                  <p class="meta">${FileNames.escapeHtml(copy.rateBody)}</p>
                  <p class="meta">Retry-After: ${retryAfterSeconds}s</p>
                </header>
                <footer class="foot">${FileNames.escapeHtml(copy.footer)}</footer>
                """.trimIndent(),
        )
    }

    fun renderServerError(language: Language): String {
        val copy = copyFor(language)
        return page(
            language = copy,
            title = copy.serverTitle,
            bodyClass = "empty",
            content =
                """
                <header class="hero center">
                  ${brandHeader(copy)}
                  <h1>${FileNames.escapeHtml(copy.serverTitle)}</h1>
                  <p class="meta">${FileNames.escapeHtml(copy.serverBody)}</p>
                </header>
                <footer class="foot">${FileNames.escapeHtml(copy.footer)}</footer>
                """.trimIndent(),
        )
    }

    fun renderPreview(
        language: Language,
        title: String,
        bodyClass: String,
        bodyHtml: String,
        downloadHref: String,
        backHref: String,
        embed: Boolean = false,
    ): String {
        val copy = copyFor(language)
        val safeTitle = FileNames.escapeHtml(FileNames.displayName(title))
        val safeBackHref = FileNames.escapeHtml(backHref)
        val safeDownloadHref = FileNames.escapeHtml(downloadHref)
        val backIcon = icon("back")
        val downloadIcon = icon("download")
        if (embed) {
            return page(
                language = copy,
                title = FileNames.displayName(title),
                bodyClass = "embed $bodyClass",
                content = """<div class="preview-body">$bodyHtml</div>""",
            )
        }
        return page(
            language = copy,
            title = FileNames.displayName(title),
            bodyClass = "preview $bodyClass",
            content =
                """
                <header class="hero">
                  ${brandHeader(copy)}
                  <h1>$safeTitle</h1>
                  <div class="actions">
                    <a class="button secondary small" href="$safeBackHref">$backIcon<span>${copy.previewBack}</span></a>
                    <a class="button small" href="$safeDownloadHref">$downloadIcon<span>${copy.previewDownload}</span></a>
                  </div>
                </header>
                <div class="preview-body">$bodyHtml</div>
                <footer class="foot">${FileNames.escapeHtml(copy.footer)}</footer>
                """.trimIndent(),
        )
    }

    fun renderMediaViewer(
        language: Language,
        title: String,
        kind: String,
        srcHref: String,
        downloadHref: String,
        backHref: String,
        embed: Boolean = false,
    ): String {
        val copy = copyFor(language)
        val safeTitle = FileNames.escapeHtml(FileNames.displayName(title))
        val safeBackHref = FileNames.escapeHtml(backHref)
        val safeDownloadHref = FileNames.escapeHtml(downloadHref)
        val backIcon = icon("back")
        val downloadIcon = icon("download")
        val safeKind =
            when (kind) {
                "image", "video", "audio" -> kind
                else -> "file"
            }
        val mediaHtml = mediaElement(safeKind, srcHref, copy)
        if (embed) {
            return page(
                language = copy,
                title = FileNames.displayName(title),
                bodyClass = "embed media-$safeKind",
                content = """<div class="preview-body">$mediaHtml</div>""",
            )
        }
        return page(
            language = copy,
            title = FileNames.displayName(title),
            bodyClass = "preview media-$safeKind",
            content =
                """
                <header class="hero">
                  ${brandHeader(copy)}
                  <h1>$safeTitle</h1>
                  <div class="actions">
                    <a class="button secondary small" href="$safeBackHref">$backIcon<span>${copy.previewBack}</span></a>
                    <a class="button small" href="$safeDownloadHref">$downloadIcon<span>${copy.previewDownload}</span></a>
                  </div>
                </header>
                <div class="preview-body">$mediaHtml</div>
                <footer class="foot">${FileNames.escapeHtml(copy.footer)}</footer>
                """.trimIndent(),
        )
    }

    private fun mediaElement(
        kind: String,
        srcHref: String,
        copy: Copy,
    ): String {
        val src = FileNames.escapeHtml(srcHref)
        return when (kind) {
            "image" -> "<img class=\"media-image\" src=\"$src\" alt=\"\">"
            "video" -> "<video class=\"media-video\" controls preload=\"metadata\" src=\"$src\"></video>"
            "audio" -> "<audio class=\"media-audio\" controls preload=\"metadata\" src=\"$src\"></audio>"
            else -> "<p class=\"meta\"><a class=\"button\" href=\"$src\">${copy.open}</a></p>"
        }
    }

    private fun icon(name: String): String {
        val body =
            when (name) {
                "download" -> """<path d="M12 4v10"/><path d="M8 10.5 12 14.5l4-4"/><path d="M5 19h14"/>"""
                "eye" ->
                    """<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z"/><circle cx="12" cy="12" r="3.2"/>"""
                "eye-off" ->
                    """<path d="m3 3 18 18"/><path d="M10.6 6.1A10.9 10.9 0 0 1 12 6c6 0 9.5 6 9.5 6a17 17 0 0 1-3.2 3.8"/><path d="M6.2 6.2A16.8 16.8 0 0 0 2.5 12S6 18 12 18a10.8 10.8 0 0 0 5.8-1.8"/><path d="M9.9 9.9a3.2 3.2 0 0 0 4.2 4.2"/>"""
                "archive" ->
                    """<path d="M4 8.5h16"/><path d="M5.5 8.5v9.5A1.5 1.5 0 0 0 7 19.5h10a1.5 1.5 0 0 0 1.5-1.5V8.5"/>""" +
                        """<path d="M4.5 4.5h15v4h-15z"/><path d="M10 12.5h4"/>"""
                "chevron-left" -> """<path d="M14.5 6 8.5 12l6 6"/>"""
                "chevron-right" -> """<path d="M9.5 6l6 6-6 6"/>"""
                "fullscreen" ->
                    """<path d="M9 4.5H4.5V9"/><path d="M15 4.5h4.5V9"/><path d="M15 19.5h4.5V15"/><path d="M9 19.5H4.5V15"/>"""
                "close" -> """<path d="M6.5 6.5l11 11"/><path d="M17.5 6.5l-11 11"/>"""
                "clock" -> """<circle cx="12" cy="12" r="7.5"/><path d="M12 8.2v4.3l2.6 1.6"/>"""
                "back" -> """<path d="M10 6l-6 6 6 6"/><path d="M4.5 12H20"/>"""
                "unlock" ->
                    """<rect x="5" y="10.5" width="14" height="9.5" rx="2.5"/><path d="M8.5 10.5V8a3.5 3.5 0 0 1 6.8-1.2"/>"""
                "video" -> """<rect x="3" y="6" width="12.5" height="12" rx="2.5"/><path d="M15.5 10.6 21 8v8l-5.5-2.6"/>"""
                "audio" -> """<path d="M9 16.5V7l8-2v9.5"/><circle cx="6.5" cy="17" r="2.5"/><circle cx="14.5" cy="15" r="2.5"/>"""
                "pdf" -> """<path d="M6.5 3.5h7l4 4v13h-11z"/><path d="M13.5 3.5v4h4"/><path d="M9 12.5h6"/><path d="M9 16h4"/>"""
                "text" -> """<path d="M6.5 3.5h7l4 4v13h-11z"/><path d="M13.5 3.5v4h4"/><path d="M9 12h6"/><path d="M9 15.5h6"/>"""
                else -> """<path d="M6.5 3.5h7l4 4v13h-11z"/><path d="M13.5 3.5v4h4"/>"""
            }
        return """<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" """ +
            """stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">$body</svg>"""
    }

    private fun fileIcon(media: MediaKind): String =
        when (media) {
            MediaKind.VIDEO -> "video"
            MediaKind.AUDIO -> "audio"
            MediaKind.PDF -> "pdf"
            MediaKind.TEXT -> "text"
            else -> "file"
        }

    private enum class MediaKind(
        val token: String,
    ) {
        IMAGE("image"),
        VIDEO("video"),
        AUDIO("audio"),
        PDF("pdf"),
        TEXT("text"),
        OTHER("file"),
    }

    private fun mediaKindOf(entry: SharedEntry): MediaKind {
        val mime = entry.mimeType.lowercase(Locale.ROOT).trim()
        return when {
            mime.startsWith("image/") -> MediaKind.IMAGE
            mime.startsWith("video/") -> MediaKind.VIDEO
            mime.startsWith("audio/") -> MediaKind.AUDIO
            mime == "application/pdf" -> MediaKind.PDF
            mime.startsWith("text/") ||
                mime in
                setOf(
                    "application/json",
                    "application/xml",
                    "application/javascript",
                    "application/x-yaml",
                    "application/yaml",
                ) -> MediaKind.TEXT
            DocumentText.isSupported(mime, entry.name) -> MediaKind.TEXT
            else -> MediaKind.OTHER
        }
    }

    private fun brandHeader(copy: Copy): String =
        """
        <div class="brand-row">
          <span class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path d="M12 3.6v9.2" stroke="currentColor" stroke-width="2.1" stroke-linecap="round"/>
              <path d="M8 7.6 12 3.6l4 4" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M6.4 12.1v4c0 3 2.5 5.4 5.6 5.4s5.6-2.4 5.6-5.4v-4" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </span>
          <span class="brand-text">
            <span class="brand-name">Pocket Share</span>
            <span class="brand-tagline">${FileNames.escapeHtml(copy.brandTagline)}</span>
          </span>
        </div>
        """.trimIndent()

    private fun page(
        language: Copy,
        title: String,
        bodyClass: String,
        content: String,
    ): String =
        """
        <!doctype html>
        <html lang="${language.htmlLang}">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="robots" content="noindex, nofollow, noarchive">
          <meta name="color-scheme" content="light dark">
          <meta name="theme-color" content="#0B5C4B">
          <link rel="icon" type="image/svg+xml" href="/favicon.svg">
          <link rel="shortcut icon" href="/favicon.svg">
          <title>${FileNames.escapeHtml(title)} · Pocket Share</title>
          <style>
            :root {
              color-scheme: light dark;
              --bg: #f5f8f6;
              --bg-2: #edf3f0;
              --card: #ffffff;
              --text: #0d1a15;
              --muted: #64766d;
              --line: #e3eae6;
              --soft: #f2f7f4;
              --soft-line: #d8e6df;
              --accent: #0b6b53;
              --accent-strong: #0a5843;
              --on-accent: #ffffff;
              --gold: #d9a94f;
              --error: #b3261e;
              --error-soft: #fdeceb;
              --glow-a: rgba(94, 205, 173, .16);
              --glow-b: rgba(233, 190, 116, .12);
              --shadow: 0 1px 2px rgba(9, 40, 32, .05), 0 14px 34px rgba(9, 40, 32, .07);
              --focus: #0b6b53;
              --control: 34px;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #060d0a;
                --bg-2: #0a1511;
                --card: #0d1713;
                --text: #e9f4ee;
                --muted: #99b0a5;
                --line: #1f2f29;
                --soft: #131f1a;
                --soft-line: #263a32;
                --accent: #7fe3cb;
                --accent-strong: #a5f2dd;
                --on-accent: #062019;
                --gold: #edc479;
                --error: #ffb4ab;
                --error-soft: #37130f;
                --glow-a: rgba(52, 173, 144, .16);
                --glow-b: rgba(237, 196, 121, .07);
                --shadow: 0 1px 2px rgba(0, 0, 0, .4), 0 16px 40px rgba(0, 0, 0, .45);
                --focus: #a5f2dd;
              }
            }
            * { box-sizing: border-box; }
            html { -webkit-text-size-adjust: 100%; }
            html, body { margin: 0; min-height: 100%; }
            body {
              min-height: 100vh;
              min-height: 100dvh;
              padding: clamp(12px, 2.6vw, 28px);
              display: flex;
              justify-content: center;
              align-items: flex-start;
              background:
                radial-gradient(760px 480px at 4% -6%, var(--glow-a), transparent 62%),
                radial-gradient(640px 420px at 100% 0%, var(--glow-b), transparent 58%),
                linear-gradient(180deg, var(--bg), var(--bg-2));
              background-attachment: fixed;
              color: var(--text);
              font: 15px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
              -webkit-font-smoothing: antialiased;
              text-rendering: optimizeLegibility;
            }
            main {
              position: relative;
              width: 100%;
              max-width: 640px;
              overflow: hidden;
              background: var(--card);
              border: 1px solid var(--line);
              border-radius: 18px;
              padding: clamp(16px, 2.8vw, 24px);
              box-shadow: var(--shadow);
            }
            main::before {
              content: "";
              position: absolute;
              inset: 0 0 auto 0;
              height: 3px;
              background: linear-gradient(90deg, var(--accent) 0%, #26a383 60%, var(--gold) 100%);
            }
            h1 {
              margin: 0 0 5px;
              font-size: clamp(19px, 3.6vw, 24px);
              line-height: 1.25;
              letter-spacing: -.022em;
              font-weight: 700;
              overflow-wrap: anywhere;
            }
            .icon { display: block; width: 15px; height: 15px; flex: none; }
            .brand-row { display: flex; align-items: center; gap: 9px; margin: 0 0 16px; }
            .brand-mark {
              width: 28px;
              height: 28px;
              flex: none;
              display: inline-flex;
              align-items: center;
              justify-content: center;
              border-radius: 8px;
              background: linear-gradient(145deg, #137a64, #06463a);
              color: #f1fffa;
            }
            .brand-mark svg { width: 16px; height: 16px; display: block; }
            .brand-text { display: flex; flex-direction: column; gap: 1px; line-height: 1.15; }
            .brand-name { font-size: 12.5px; font-weight: 700; letter-spacing: -.008em; }
            .brand-tagline { font-size: 10px; font-weight: 550; letter-spacing: .05em; text-transform: uppercase; color: var(--muted); }
            .center { text-align: center; }
            .center .brand-row { justify-content: center; }
            .center .brand-text { text-align: left; }
            .center .meta { max-width: 440px; margin-left: auto; margin-right: auto; }
            .meta { color: var(--muted); margin: 0; overflow-wrap: anywhere; font-size: 12.5px; }
            .facts { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 14px; margin-top: 9px; font-size: 11.5px; }
            .fact { display: inline-flex; align-items: center; gap: 5px; color: var(--muted); }
            .fact .icon { width: 12.5px; height: 12.5px; opacity: .8; }
            .fact-label { font-size: 10.5px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; }
            .fact-value { font-size: 11.5px; font-weight: 600; color: var(--text); overflow-wrap: anywhere; }
            .actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 16px 0 2px; }
            .toolbar {
              display: flex;
              align-items: center;
              flex-wrap: wrap;
              gap: 4px 6px;
              margin: 14px 0 2px;
              padding-bottom: 8px;
              border-bottom: 1px solid var(--line);
            }
            .toolbar-count { font-size: 11.5px; font-weight: 600; color: var(--muted); letter-spacing: .01em; margin-right: 4px; }
            .toolbar .spacer { flex: 1 1 auto; }
            .link-btn {
              border: 0;
              background: transparent;
              color: var(--accent);
              font: inherit;
              font-size: 11.5px;
              font-weight: 600;
              padding: 5px 7px;
              border-radius: 7px;
              cursor: pointer;
            }
            .link-btn:hover { background: var(--soft); }
            .files { list-style: none; padding: 0; margin: 0; }
            .file { display: flex; align-items: center; gap: 10px; padding: 7px 6px; border-bottom: 1px solid var(--line); }
            .file:last-child { border-bottom: 0; padding-bottom: 3px; }
            .file-main { min-width: 0; display: flex; align-items: center; gap: 10px; flex: 1 1 auto; }
            .file-copy { min-width: 0; display: flex; flex-direction: column; gap: 0; }
            .file-name { font-weight: 600; font-size: 13px; line-height: 1.35; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .file-name-text { display: inline-block; white-space: nowrap; }
            .file-name.marquee .file-name-text { animation: file-marquee var(--marquee-duration, 9s) ease-in-out infinite alternate; }
            @keyframes file-marquee {
              from { transform: translateX(0); }
              to { transform: translateX(calc(-1 * var(--marquee-shift, 0px))); }
            }
            .file-size { color: var(--muted); font-size: 11px; line-height: 1.3; }
            .file-actions { display: inline-flex; gap: 5px; flex: none; }
            .thumb {
              width: 34px;
              height: 34px;
              flex: none;
              object-fit: cover;
              border-radius: 8px;
              border: 1px solid var(--line);
              background: var(--soft);
            }
            .icon-thumb { display: inline-flex; align-items: center; justify-content: center; color: var(--accent); }
            .icon-thumb .icon { width: 17px; height: 17px; }
            .file-check { width: 16px; height: 16px; flex: none; accent-color: var(--accent); cursor: pointer; }
            .button {
              display: inline-flex;
              align-items: center;
              justify-content: center;
              gap: 6px;
              min-height: var(--control);
              padding: 0 13px;
              border: 1px solid transparent;
              border-radius: 9px;
              background: var(--accent);
              color: var(--on-accent);
              font: inherit;
              font-size: 12.5px;
              font-weight: 600;
              line-height: 1;
              text-decoration: none;
              cursor: pointer;
              -webkit-tap-highlight-color: transparent;
              transition: background-color .15s ease, border-color .15s ease, color .15s ease, opacity .15s ease;
            }
            .button:hover { background: var(--accent-strong); }
            .button.secondary { background: transparent; color: var(--text); border-color: var(--line); }
            .button.secondary:hover { background: var(--soft); border-color: var(--soft-line); }
            .button.small { min-height: 30px; padding: 0 10px; border-radius: 8px; font-size: 12px; }
            .button.small .icon { width: 14px; height: 14px; }
            .button:disabled { opacity: .45; cursor: default; }
            .icon-btn {
              display: inline-flex;
              align-items: center;
              justify-content: center;
              width: 30px;
              height: 30px;
              flex: none;
              padding: 0;
              border: 1px solid var(--line);
              border-radius: 8px;
              background: transparent;
              color: var(--muted);
              cursor: pointer;
              text-decoration: none;
              -webkit-tap-highlight-color: transparent;
              transition: background-color .15s ease, border-color .15s ease, color .15s ease;
            }
            .icon-btn:hover { background: var(--soft); border-color: var(--soft-line); color: var(--text); }
            .icon-btn:disabled { opacity: .4; cursor: default; background: transparent; }
            .button:focus-visible, .icon-btn:focus-visible, .link-btn:focus-visible, .password-toggle:focus-visible, input:focus-visible {
              outline: 2.5px solid var(--focus);
              outline-offset: 2px;
            }
            .unlock-form {
              display: flex;
              flex-direction: column;
              gap: 11px;
              max-width: 340px;
              margin: 18px auto 0;
              padding: 14px;
              border: 1px solid var(--soft-line);
              border-radius: 14px;
              background: var(--soft);
              text-align: left;
            }
            .field { display: flex; flex-direction: column; gap: 5px; }
            .password-wrap { position: relative; }
            .password-wrap input { padding-right: 44px; }
            .password-toggle {
              position: absolute;
              top: 50%;
              right: 4px;
              display: inline-flex;
              align-items: center;
              justify-content: center;
              width: 32px;
              height: 32px;
              padding: 0;
              border: 0;
              border-radius: 7px;
              background: transparent;
              color: var(--muted);
              cursor: pointer;
              transform: translateY(-50%);
              -webkit-tap-highlight-color: transparent;
            }
            .password-toggle:hover { background: var(--soft-line); color: var(--text); }
            .password-toggle .icon { width: 16px; height: 16px; }
            .password-toggle[hidden], .password-toggle [hidden] { display: none; }
            label { font-size: 11.5px; font-weight: 600; color: var(--muted); letter-spacing: .02em; }
            input {
              width: 100%;
              min-height: 38px;
              padding: 8px 11px;
              border: 1px solid var(--line);
              border-radius: 9px;
              background: var(--card);
              color: var(--text);
              font: inherit;
              font-size: 13px;
            }
            .error {
              margin: 0;
              padding: 8px 10px;
              border-radius: 8px;
              background: var(--error-soft);
              color: var(--error);
              font-size: 12px;
              font-weight: 600;
            }
            .unlock-form .button { width: 100%; margin-top: 2px; }
            .foot {
              margin-top: 18px;
              padding-top: 11px;
              border-top: 1px solid var(--line);
              color: var(--muted);
              font-size: 11px;
              line-height: 1.6;
            }
            main.preview { max-width: 780px; }
            .preview-body { margin-top: 14px; }
            .preview-body img,
            .preview-body video {
              display: block;
              width: 100%;
              height: auto;
              max-height: min(72vh, 620px);
              border-radius: 12px;
              border: 1px solid var(--line);
              background: var(--soft);
            }
            .preview-body audio { display: block; width: 100%; margin: 6px 0; }
            .doc-frame {
              display: block;
              width: 100%;
              height: min(72vh, 620px);
              border: 1px solid var(--line);
              border-radius: 12px;
              background: var(--soft);
            }
            .doc-text {
              margin: 0;
              padding: 14px;
              max-height: min(72vh, 620px);
              overflow: auto;
              white-space: pre-wrap;
              overflow-wrap: anywhere;
              border: 1px solid var(--line);
              border-radius: 12px;
              background: var(--soft);
              font: 12.5px/1.6 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
            }
            .doc-html {
              margin: 0;
              padding: 16px;
              max-height: min(72vh, 620px);
              overflow: auto;
              border: 1px solid var(--line);
              border-radius: 12px;
              background: var(--soft);
              font-size: 13px;
              line-height: 1.6;
              overflow-wrap: anywhere;
            }
            .doc-html h3, .doc-html h4, .doc-html h5, .doc-html h6 {
              margin: 18px 0 8px;
              line-height: 1.3;
              letter-spacing: -.01em;
            }
            .doc-html h3:first-child, .doc-sheet h3, .doc-slide h3 { margin-top: 0; }
            .doc-html h3 { font-size: 16px; }
            .doc-html h4 { font-size: 14.5px; }
            .doc-html h5, .doc-html h6 { font-size: 13px; }
            .doc-html p { margin: 0 0 10px; white-space: pre-wrap; }
            .doc-html p:last-child { margin-bottom: 0; }
            .doc-table-wrap { margin: 0 0 14px; overflow-x: auto; }
            .doc-table-wrap:last-child { margin-bottom: 0; }
            .doc-html table { width: 100%; border-collapse: collapse; font-size: 12px; }
            .doc-html th, .doc-html td {
              padding: 6px 8px;
              border: 1px solid var(--line);
              text-align: left;
              vertical-align: top;
              white-space: pre-wrap;
              overflow-wrap: anywhere;
            }
            .doc-html th { background: var(--bg-2); font-weight: 700; }
            .doc-sheet + .doc-sheet, .doc-slide + .doc-slide {
              margin-top: 18px;
              padding-top: 16px;
              border-top: 1px solid var(--line);
            }
            .doc-slide-number {
              display: inline-block;
              margin-bottom: 4px;
              color: var(--muted);
              font-size: 10px;
              font-weight: 700;
              letter-spacing: .08em;
            }
            .preview-dialog {
              width: min(720px, calc(100vw - 96px));
              height: min(520px, calc(100dvh - 160px));
              max-width: calc(100vw - 96px);
              max-height: calc(100dvh - 160px);
              margin: auto;
              padding: 0;
              border: 1px solid var(--line);
              border-radius: 14px;
              background: var(--card);
              color: var(--text);
              box-shadow: 0 24px 64px rgba(4, 20, 15, .35);
              overflow: hidden;
            }
            .preview-dialog[open] { display: flex; }
            .preview-dialog::backdrop { background: rgba(4, 18, 14, .38); }
            .preview-panel { display: flex; flex-direction: column; width: 100%; height: 100%; min-height: 0; }
            .preview-bar {
              display: flex;
              align-items: center;
              gap: 8px;
              padding: 8px 10px;
              border-bottom: 1px solid var(--line);
              background: var(--card);
            }
            .preview-name {
              flex: 1 1 auto;
              min-width: 0;
              font-size: 12px;
              font-weight: 600;
              letter-spacing: .01em;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }
            .preview-tools { display: inline-flex; align-items: center; gap: 5px; flex: none; }
            .preview-tools .button.small .icon { width: 13px; height: 13px; }
            .preview-stage { position: relative; flex: 1 1 auto; min-height: 0; background: var(--soft); }
            .preview-frame { display: block; width: 100%; height: 100%; border: 0; background: var(--soft); }
            .preview-image { display: block; width: 100%; height: 100%; object-fit: contain; background: var(--soft); }
            .preview-image[hidden], .preview-frame[hidden] { display: none; }
            .preview-dialog:fullscreen {
              width: 100vw;
              height: 100dvh;
              max-width: 100vw;
              max-height: 100dvh;
              margin: 0;
              border: 0;
              border-radius: 0;
            }
            body.embed { padding: 0; display: block; background: transparent; }
            main.embed {
              width: 100%;
              max-width: none;
              min-height: 100dvh;
              margin: 0;
              padding: 0;
              border: 0;
              border-radius: 0;
              box-shadow: none;
              background: transparent;
              overflow: hidden;
            }
            main.embed::before { display: none; }
            .embed .preview-body { margin: 0; height: 100dvh; display: flex; align-items: center; justify-content: center; }
            .embed .preview-body img,
            .embed .preview-body video {
              width: 100%;
              height: 100%;
              max-height: 100dvh;
              object-fit: contain;
              border: 0;
              border-radius: 0;
              background: transparent;
            }
            .embed .preview-body video { background: #000; }
            .embed .preview-body audio { width: min(640px, 100%); margin: 0; padding: 0 16px; }
            .embed .doc-frame { height: 100dvh; border: 0; border-radius: 0; }
            .embed .doc-text { height: 100dvh; max-height: none; border: 0; border-radius: 0; }
            .embed .doc-html { height: 100dvh; max-height: none; border: 0; border-radius: 0; padding: 18px; }
            main.empty { max-width: 460px; }
            .empty .hero { padding: 6px 0 2px; }
            @media (max-width: 520px) {
              body { align-items: stretch; padding: 8px; }
              main { border-radius: 14px; padding: 15px 13px 13px; }
              .brand-row { margin-bottom: 13px; }
              .file { gap: 8px; padding: 7px 4px; }
              .thumb { width: 32px; height: 32px; }
              .toolbar { flex-wrap: nowrap; gap: 2px 4px; }
              .toolbar-count { margin-right: 0; white-space: nowrap; }
              .link-btn { padding: 5px 5px; }
              .button.small.compact { padding: 0 7px; min-width: 30px; }
              .button.small.compact .label { display: none; }
              .unlock-form { padding: 12px; border-radius: 12px; }
              .preview-dialog {
                width: calc(100vw - 48px);
                height: min(58dvh, 420px);
                max-width: calc(100vw - 48px);
                max-height: min(58dvh, 420px);
                border-radius: 12px;
              }
              .preview-bar { flex-wrap: wrap; }
              .preview-name { width: 100%; }
              .preview-tools { width: 100%; justify-content: flex-end; }
            }
            @media (prefers-reduced-motion: reduce) {
              .button, .icon-btn { transition: none; }
              .file-name.marquee .file-name-text { animation: none; }
            }
          </style>
        </head>
        <body>
          <main class="$bodyClass">
            $content
          </main>
          <script>
            (function () {
              // Long names scroll instead of being cut off; short names stay still.
              function setupFileMarquee() {
                Array.prototype.forEach.call(document.querySelectorAll('.file-name-text'), function (text) {
                  var box = text.parentElement;
                  if (!box) return;
                  var overflow = text.scrollWidth - box.clientWidth;
                  if (overflow <= 6) return;
                  box.classList.add('marquee');
                  box.style.setProperty('--marquee-shift', (overflow + 8) + 'px');
                  box.style.setProperty('--marquee-duration', Math.max(7, Math.round(overflow / 26)) + 's');
                });
              }
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', setupFileMarquee);
              } else {
                setupFileMarquee();
              }
            })();

            (function () {
              var toggle = document.getElementById('passwordToggle');
              var input = document.getElementById('password');
              if (!toggle || !input) return;
              var eye = toggle.querySelector('.password-eye');
              var eyeOff = toggle.querySelector('.password-eye-off');
              toggle.hidden = false;
              toggle.addEventListener('click', function () {
                var show = input.type === 'password';
                input.type = show ? 'text' : 'password';
                toggle.setAttribute('aria-pressed', show ? 'true' : 'false');
                var label = toggle.getAttribute(show ? 'data-hide-label' : 'data-show-label');
                if (label) toggle.setAttribute('aria-label', label);
                if (eye) eye.hidden = show;
                if (eyeOff) eyeOff.hidden = !show;
                input.focus();
              });
            })();

            (function () {
              var dialog = document.getElementById('previewDialog');
              if (!dialog || typeof dialog.showModal !== 'function') return;
              var panel = dialog.querySelector('.preview-panel');
              var stageImage = document.getElementById('previewImage');
              var frame = document.getElementById('previewFrame');
              var titleEl = document.getElementById('previewDialogTitle');
              var downloadLink = document.getElementById('previewDownloadLink');
              var fullscreenBtn = document.getElementById('previewFullscreenBtn');
              var closeBtn = document.getElementById('previewCloseBtn');
              var prevBtn = document.getElementById('previewPrevBtn');
              var nextBtn = document.getElementById('previewNextBtn');
              var triggers = Array.prototype.slice.call(document.querySelectorAll('[data-preview-src]'));
              var currentIndex = -1;
              var lastTrigger = null;

              function updateNav() {
                if (prevBtn) prevBtn.disabled = currentIndex <= 0;
                if (nextBtn) nextBtn.disabled = currentIndex < 0 || currentIndex >= triggers.length - 1;
              }

              function openAt(index) {
                if (index < 0 || index >= triggers.length) return;
                openPreview(triggers[index]);
              }

              function openPreview(trigger) {
                var href = trigger.getAttribute('data-preview-src');
                if (!href) return;
                lastTrigger = trigger;
                currentIndex = triggers.indexOf(trigger);
                updateNav();
                var label = trigger.getAttribute('data-preview-title') || '';
                if (titleEl) titleEl.textContent = label;
                if (downloadLink) downloadLink.href = trigger.getAttribute('data-preview-download') || href;
                if (trigger.getAttribute('data-preview-kind') === 'image' && stageImage) {
                  if (frame) {
                    frame.hidden = true;
                    frame.removeAttribute('src');
                  }
                  stageImage.src = href;
                  stageImage.alt = label;
                  stageImage.hidden = false;
                } else if (frame) {
                  if (stageImage) {
                    stageImage.hidden = true;
                    stageImage.removeAttribute('src');
                  }
                  frame.title = label;
                  frame.src = href + (href.indexOf('?') < 0 ? '?' : '&') + 'embed=1';
                  frame.hidden = false;
                }
                if (!dialog.open) dialog.showModal();
              }

              function closePreview() {
                if (dialog.open) dialog.close();
              }

              function toggleFullscreen() {
                if (document.fullscreenElement) {
                  if (document.exitFullscreen) document.exitFullscreen().catch(function () {});
                  return;
                }
                var target = panel || dialog;
                if (target.requestFullscreen) target.requestFullscreen().catch(function () {});
              }

              Array.prototype.forEach.call(document.querySelectorAll('[data-preview-src]'), function (trigger) {
                trigger.addEventListener('click', function (event) {
                  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                  event.preventDefault();
                  openAt(triggers.indexOf(trigger));
                });
              });
              if (closeBtn) closeBtn.addEventListener('click', closePreview);
              if (prevBtn) {
                prevBtn.addEventListener('click', function () { openAt(currentIndex - 1); });
              }
              if (nextBtn) {
                nextBtn.addEventListener('click', function () { openAt(currentIndex + 1); });
              }
              dialog.addEventListener('keydown', function (event) {
                if (event.key === 'ArrowLeft' && currentIndex > 0) {
                  event.preventDefault();
                  openAt(currentIndex - 1);
                } else if (event.key === 'ArrowRight' && currentIndex >= 0 && currentIndex < triggers.length - 1) {
                  event.preventDefault();
                  openAt(currentIndex + 1);
                }
              });
              if (fullscreenBtn) fullscreenBtn.addEventListener('click', toggleFullscreen);
              dialog.addEventListener('cancel', function (event) {
                event.preventDefault();
                closePreview();
              });
              dialog.addEventListener('click', function (event) {
                if (event.target === dialog) closePreview();
              });
              dialog.addEventListener('close', function () {
                if (frame) {
                  frame.hidden = true;
                  frame.src = 'about:blank';
                }
                if (stageImage) {
                  stageImage.hidden = true;
                  stageImage.removeAttribute('src');
                }
                if (document.fullscreenElement && document.exitFullscreen) {
                  document.exitFullscreen().catch(function () {});
                }
                if (lastTrigger && lastTrigger.focus) lastTrigger.focus();
                lastTrigger = null;
                currentIndex = -1;
                updateNav();
              });
              document.addEventListener('fullscreenchange', function () {
                if (fullscreenBtn) {
                  fullscreenBtn.setAttribute('aria-pressed', document.fullscreenElement ? 'true' : 'false');
                }
              });
            })();

            document.addEventListener('DOMContentLoaded', function () {
              var checks = Array.prototype.slice.call(document.querySelectorAll('.file-check'));
              if (checks.length === 0) return;
              var countEl = document.getElementById('selectedCount');
              var selectAllBtn = document.getElementById('selectAllBtn');
              var selectNoneBtn = document.getElementById('selectNoneBtn');
              var downloadSelectedBtn = document.getElementById('downloadSelectedBtn');
              var tokenPath = window.location.pathname.replace(/\/$/, '');
              function update() {
                var selected = checks.filter(function (c) { return c.checked; });
                if (countEl) countEl.textContent = String(selected.length);
                if (downloadSelectedBtn) downloadSelectedBtn.disabled = selected.length === 0;
              }
              checks.forEach(function (c) { c.addEventListener('change', update); });
              if (selectAllBtn) {
                selectAllBtn.addEventListener('click', function () {
                  checks.forEach(function (c) { c.checked = true; });
                  update();
                });
              }
              if (selectNoneBtn) {
                selectNoneBtn.addEventListener('click', function () {
                  checks.forEach(function (c) { c.checked = false; });
                  update();
                });
              }
              if (downloadSelectedBtn) {
                downloadSelectedBtn.addEventListener('click', function () {
                  var ids = checks.filter(function (c) { return c.checked; }).map(function (c) { return c.value; });
                  if (ids.length === 0) return;
                  var url = tokenPath + '/zip?ids=' + encodeURIComponent(ids.join(','));
                  window.location.assign(url);
                });
              }
              update();
            });
          </script>
        </body>
        </html>
        """.trimIndent()

    private fun formatDate(
        epochMs: Long,
        locale: Locale,
    ): String {
        val formatter =
            DateTimeFormatter
                .ofPattern("d MMM yyyy, HH:mm", locale)
                .withZone(ZoneId.systemDefault())
        return formatter.format(Instant.ofEpochMilli(epochMs))
    }

    private fun formatBytes(
        bytes: Long,
        locale: Locale,
    ): String {
        if (bytes < 0) return "-"
        if (bytes < 1_024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1_024 && unitIndex < units.lastIndex) {
            value /= 1_024.0
            unitIndex += 1
        }
        val pattern =
            if (value >= 100) {
                "%.0f %s"
            } else if (value >= 10) {
                "%.1f %s"
            } else {
                "%.2f %s"
            }
        return String.format(locale, pattern, value, units[unitIndex])
    }
}
