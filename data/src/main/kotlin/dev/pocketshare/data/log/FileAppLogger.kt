package dev.pocketshare.data.log

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketshare.core.log.AppLogger
import dev.pocketshare.core.log.Redactor
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rolling on-device log. Every line passes through [Redactor] before it is written. Logs are never
 * uploaded anywhere.
 */
@Singleton
class FileAppLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppLogger {
    private val logDir = File(context.filesDir, "logs")
    private val logFile = File(logDir, "pocketshare.log")
    private val rotatedFile = File(logDir, "pocketshare.log.1")
    private val lock = Any()
    private val formatter =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())

    override fun debug(
        tag: String,
        message: String,
    ) = write("D", tag, message, null)

    override fun info(
        tag: String,
        message: String,
    ) = write("I", tag, message, null)

    override fun warn(
        tag: String,
        message: String,
        error: Throwable?,
    ) = write("W", tag, message, error)

    override fun error(
        tag: String,
        message: String,
        error: Throwable?,
    ) = write("E", tag, message, error)

    fun recentLines(maxLines: Int = 400): String =
        synchronized(lock) {
            val lines =
                buildList {
                    if (rotatedFile.exists()) addAll(runCatching { rotatedFile.readLines() }.getOrDefault(emptyList()))
                    if (logFile.exists()) addAll(runCatching { logFile.readLines() }.getOrDefault(emptyList()))
                }
            lines.takeLast(maxLines).joinToString("\n")
        }

    fun clear() {
        synchronized(lock) {
            runCatching { logFile.delete() }
            runCatching { rotatedFile.delete() }
        }
    }

    fun exportTo(target: Uri): Boolean =
        synchronized(lock) {
            runCatching {
                val text =
                    buildList {
                        if (rotatedFile.exists()) addAll(rotatedFile.readLines())
                        if (logFile.exists()) addAll(logFile.readLines())
                    }.joinToString("\n")
                val payload = if (text.isBlank()) "PocketShare diagnostics (empty)" else text
                context.contentResolver.openOutputStream(target, "w")?.use { stream ->
                    stream.write(payload.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } ?: return false
                true
            }.getOrDefault(false)
        }

    fun sizeBytes(): Long =
        synchronized(lock) {
            (if (logFile.exists()) logFile.length() else 0L) +
                (if (rotatedFile.exists()) rotatedFile.length() else 0L)
        }

    private fun write(
        level: String,
        tag: String,
        message: String,
        error: Throwable?,
    ) {
        val timestamp = formatter.format(Instant.now())
        val safeTag = tag.take(24)
        val raw =
            buildString {
                append(timestamp)
                append(' ')
                append(level)
                append('/')
                append(safeTag)
                append(": ")
                append(message)
                if (error != null) {
                    append(" | ")
                    append(error.javaClass.simpleName)
                    append(": ")
                    append(error.message.orEmpty())
                    val stack = error.stackTraceToString()
                    if (stack.isNotBlank()) {
                        append('\n')
                        append(stack.take(MAX_STACK_CHARS))
                    }
                }
            }
        val safe = Redactor.redact(raw)
        synchronized(lock) {
            try {
                ensureDir()
                if (logFile.length() > MAX_BYTES) {
                    if (rotatedFile.exists()) rotatedFile.delete()
                    logFile.renameTo(rotatedFile)
                }
                logFile.appendText(safe + "\n")
            } catch (_: IOException) {
                // Logging must never crash the server or UI.
            }
        }
    }

    private fun ensureDir() {
        if (!logDir.exists()) logDir.mkdirs()
        if (!logFile.exists()) logFile.createNewFile()
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024L
        const val MAX_STACK_CHARS = 4_000
    }
}
