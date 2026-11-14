package dev.pocketshare.data.repository

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pocketshare.core.http.FileNames
import dev.pocketshare.core.log.AppLogger
import dev.pocketshare.core.model.ShareExpiry
import dev.pocketshare.core.model.ShareFileRef
import dev.pocketshare.core.model.ShareKind
import dev.pocketshare.core.model.ShareRecord
import dev.pocketshare.core.model.ShareSummary
import dev.pocketshare.core.security.PasswordHasher
import dev.pocketshare.core.security.TokenGenerator
import dev.pocketshare.core.share.ShareStore
import dev.pocketshare.data.db.ShareDao
import dev.pocketshare.data.db.ShareEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CreateShareResult {
    data class Success(
        val share: ShareSummary,
    ) : CreateShareResult

    data class Failure(
        val reason: ImportFailure,
        val detail: String? = null,
    ) : CreateShareResult
}

enum class ImportFailure {
    NO_FILES,
    FOLDER_UNREADABLE,
    FILE_UNREADABLE,
    PERMISSION_NOT_PERSISTED,
    STORAGE_ERROR,
}

data class CreateShareOptions(
    val kind: ShareKind,
    val displayName: String,
    val expiry: ShareExpiry,
    val password: String?,
    val maxDownloads: Int?,
)

sealed interface PasswordChange {
    data object Keep : PasswordChange

    data object Clear : PasswordChange

    data class Set(
        val value: String,
    ) : PasswordChange
}

data class UpdateShareOptions(
    val displayName: String,
    /** Null keeps the current expiry untouched. */
    val expiry: ShareExpiry?,
    val passwordChange: PasswordChange = PasswordChange.Keep,
    val maxDownloads: Int?,
    /** Null keeps the current file list. Only file shares accept a new list. */
    val files: List<ShareFileRef>? = null,
)

interface ShareRepository {
    val shares: Flow<List<ShareSummary>>

    suspend fun createShare(
        uris: List<Uri>,
        options: CreateShareOptions,
    ): CreateShareResult

    suspend fun revoke(shareId: Long)

    suspend fun delete(shareId: Long)

    suspend fun updateExpiry(
        shareId: Long,
        expiry: ShareExpiry,
    )

    suspend fun updateDetails(
        shareId: Long,
        options: UpdateShareOptions,
    ): Boolean

    suspend fun findSummary(shareId: Long): ShareSummary?
}

@Serializable
private data class FileRefJson(
    val uri: String,
    val name: String,
)

@Singleton
class DefaultShareRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ShareDao,
    private val tokenGenerator: TokenGenerator,
    private val passwordHasher: PasswordHasher,
    private val clock: Clock,
    private val logger: AppLogger,
) : ShareRepository,
    ShareStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val contentResolver: ContentResolver = context.contentResolver

    override val shares: Flow<List<ShareSummary>> =
        dao.observeAll().map { entities ->
            entities.map { it.toRecord().toSummary() }
        }

    // Storage providers are third-party code and can fail with arbitrary exceptions; always surface
    // them as a user-visible import error instead of crashing the app.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun createShare(
        uris: List<Uri>,
        options: CreateShareOptions,
    ): CreateShareResult {
        if (uris.isEmpty()) return CreateShareResult.Failure(ImportFailure.NO_FILES)
        if (options.kind == ShareKind.FOLDER && uris.size != 1) {
            return CreateShareResult.Failure(ImportFailure.NO_FILES)
        }

        return try {
            withContext(Dispatchers.IO) {
                val fileRefs =
                    when (options.kind) {
                        ShareKind.FOLDER -> {
                            val uri = uris.first()
                            persistPermission(uri)
                            val folder = DocumentFile.fromTreeUri(context, uri)
                            if (folder == null || !folder.exists() || !folder.isDirectory || !folder.canRead()) {
                                return@withContext CreateShareResult.Failure(ImportFailure.FOLDER_UNREADABLE)
                            }
                            listOf(ShareFileRef(uri.toString(), FileNames.displayName(folder.name, "folder")))
                        }

                        ShareKind.FILES -> {
                            val refs =
                                uris.distinct().mapNotNull { uri ->
                                    persistPermission(uri)
                                    val document = DocumentFile.fromSingleUri(context, uri)
                                    if (document == null || !document.exists() || !document.canRead()) {
                                        logger.warn(TAG, "Skipping unreadable selected file")
                                        null
                                    } else {
                                        ShareFileRef(uri.toString(), FileNames.displayName(document.name, "file"))
                                    }
                                }
                            if (refs.isEmpty()) {
                                return@withContext CreateShareResult.Failure(ImportFailure.FILE_UNREADABLE)
                            }
                            refs
                        }
                    }

                val now = clock.millis()
                val password = options.password
                val hashedPassword =
                    if (password.isNullOrEmpty()) {
                        null
                    } else {
                        withContext(Dispatchers.Default) { passwordHasher.hash(password) }
                    }
                val fallbackName =
                    when (options.kind) {
                        ShareKind.FOLDER -> fileRefs.first().name
                        ShareKind.FILES -> if (fileRefs.size == 1) fileRefs.first().name else "${fileRefs.size} files"
                    }
                val displayName = FileNames.displayName(options.displayName, fallbackName)
                val maxDownloads = options.maxDownloads?.takeIf { it > 0 }

                var insertedId = -1L
                for (attempt in 0 until TOKEN_ATTEMPTS) {
                    val entity =
                        ShareEntity(
                            token = tokenGenerator.generate(),
                            kind = options.kind.name,
                            displayName = displayName,
                            filesJson = json.encodeToString(fileRefs.map { FileRefJson(it.uri, it.name) }),
                            createdAtEpochMs = now,
                            expiresAtEpochMs = options.expiry.expiresAt(now),
                            passwordHash = hashedPassword?.hashBase64,
                            passwordSalt = hashedPassword?.saltBase64,
                            passwordIterations = hashedPassword?.iterations ?: 0,
                            maxDownloads = maxDownloads,
                            downloadCount = 0,
                            revokedAtEpochMs = null,
                        )
                    try {
                        insertedId = dao.insert(entity)
                        break
                    } catch (error: android.database.sqlite.SQLiteConstraintException) {
                        logger.warn(TAG, "Token collision while inserting share", error)
                    }
                }
                if (insertedId <= 0L) {
                    logger.error(TAG, "Unable to insert share after retries")
                    return@withContext CreateShareResult.Failure(ImportFailure.STORAGE_ERROR)
                }

                val record =
                    dao.findById(insertedId)?.toRecord()
                        ?: return@withContext CreateShareResult.Failure(ImportFailure.STORAGE_ERROR)
                logger.info(TAG, "Created ${record.kind.name.lowercase()} share with ${record.files.size} reference(s)")
                CreateShareResult.Success(record.toSummary())
            }
        } catch (error: PermissionNotPersistedException) {
            logger.warn(TAG, "Storage permission could not be persisted", error)
            CreateShareResult.Failure(ImportFailure.PERMISSION_NOT_PERSISTED)
        } catch (error: SecurityException) {
            logger.warn(TAG, "Selected content is no longer accessible", error)
            CreateShareResult.Failure(ImportFailure.FILE_UNREADABLE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error(TAG, "Failed to create share", error)
            CreateShareResult.Failure(ImportFailure.STORAGE_ERROR)
        }
    }

    override suspend fun revoke(shareId: Long) =
        withContext(Dispatchers.IO) {
            dao.revoke(shareId, clock.millis())
            logger.info(TAG, "Share revoked")
        }

    override suspend fun delete(shareId: Long) =
        withContext(Dispatchers.IO) {
            dao.delete(shareId)
        }

    override suspend fun updateExpiry(
        shareId: Long,
        expiry: ShareExpiry,
    ) = withContext(Dispatchers.IO) {
        dao.updateExpiry(shareId, expiry.expiresAt(clock.millis()))
    }

    override suspend fun updateDetails(
        shareId: Long,
        options: UpdateShareOptions,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val entity = dao.findById(shareId) ?: return@withContext false
            if (entity.revokedAtEpochMs != null) return@withContext false

            val maxDownloads = options.maxDownloads?.takeIf { it > 0 }
            if (maxDownloads != null && maxDownloads <= entity.downloadCount) {
                return@withContext false
            }

            val existingFiles =
                runCatching {
                    json.decodeFromString<List<FileRefJson>>(entity.filesJson)
                }.getOrDefault(emptyList())

            val nextFiles =
                options.files?.let { requested ->
                    if (entity.kind != ShareKind.FILES.name) return@withContext false
                    val distinct = requested.distinctBy { it.uri }.filter { it.uri.isNotBlank() }
                    if (distinct.isEmpty()) return@withContext false
                    val existingUris = existingFiles.map { it.uri }.toSet()
                    val added = distinct.filter { it.uri !in existingUris }
                    try {
                        added.forEach { ref ->
                            val uri = Uri.parse(ref.uri)
                            persistPermission(uri)
                            val document = DocumentFile.fromSingleUri(context, uri)
                            if (document == null || !document.exists() || !document.canRead()) {
                                logger.warn(TAG, "Refusing to save an unreadable file reference")
                                return@withContext false
                            }
                        }
                    } catch (error: PermissionNotPersistedException) {
                        logger.warn(TAG, "Storage permission could not be persisted", error)
                        return@withContext false
                    } catch (error: SecurityException) {
                        logger.warn(TAG, "Selected content is no longer accessible", error)
                        return@withContext false
                    }
                    distinct.map { FileRefJson(it.uri, it.name) }
                } ?: existingFiles

            val hashedPassword =
                when (val change = options.passwordChange) {
                    is PasswordChange.Keep -> null
                    is PasswordChange.Clear -> null
                    is PasswordChange.Set ->
                        withContext(Dispatchers.Default) {
                            passwordHasher.hash(change.value)
                        }
                }
            val clearPassword = options.passwordChange is PasswordChange.Clear
            val displayName = FileNames.displayName(options.displayName, entity.displayName)

            dao.updateDetails(
                id = shareId,
                displayName = displayName,
                expiresAtEpochMs = options.expiry?.expiresAt(clock.millis()) ?: entity.expiresAtEpochMs,
                passwordHash = if (clearPassword) null else hashedPassword?.hashBase64 ?: entity.passwordHash,
                passwordSalt = if (clearPassword) null else hashedPassword?.saltBase64 ?: entity.passwordSalt,
                passwordIterations =
                    when {
                        clearPassword -> 0
                        hashedPassword != null -> hashedPassword.iterations
                        else -> entity.passwordIterations
                    },
                maxDownloads = maxDownloads,
                filesJson = json.encodeToString(nextFiles),
            )
            logger.info(TAG, "Share details updated")
            true
        }

    override suspend fun findSummary(shareId: Long): ShareSummary? =
        withContext(Dispatchers.IO) {
            dao.findById(shareId)?.toRecord()?.toSummary()
        }

    override suspend fun findByToken(token: String): ShareRecord? =
        withContext(Dispatchers.IO) {
            dao.findByToken(token)?.toRecord()
        }

    override suspend fun tryReserveDownload(
        shareId: Long,
        nowEpochMs: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dao.tryReserveDownload(shareId, nowEpochMs) == 1
        }

    private fun persistPermission(uri: Uri) {
        val alreadyPersisted =
            contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
        if (alreadyPersisted) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (error: SecurityException) {
            throw PermissionNotPersistedException(error)
        }
    }

    private fun ShareEntity.toRecord(): ShareRecord {
        val refs =
            runCatching {
                json.decodeFromString<List<FileRefJson>>(filesJson).map { ShareFileRef(it.uri, it.name) }
            }.getOrElse {
                logger.warn(TAG, "Corrupt file list in share record", it)
                emptyList()
            }
        return ShareRecord(
            id = id,
            token = token,
            kind = runCatching { ShareKind.valueOf(kind) }.getOrDefault(ShareKind.FILES),
            displayName = displayName,
            files = refs,
            createdAtEpochMs = createdAtEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            passwordHash = passwordHash,
            passwordSalt = passwordSalt,
            passwordIterations = passwordIterations,
            maxDownloads = maxDownloads,
            downloadCount = downloadCount,
            revokedAtEpochMs = revokedAtEpochMs,
        )
    }

    private fun ShareRecord.toSummary(): ShareSummary =
        ShareSummary(
            id = id,
            token = token,
            kind = kind,
            displayName = displayName,
            fileCount = files.size,
            createdAtEpochMs = createdAtEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            hasPassword = hasPassword,
            maxDownloads = maxDownloads,
            downloadCount = downloadCount,
            revokedAtEpochMs = revokedAtEpochMs,
            files = files,
        )

    private class PermissionNotPersistedException(
        cause: Throwable,
    ) : Exception(cause)

    private companion object {
        const val TAG = "Shares"
        const val TOKEN_ATTEMPTS = 3
    }
}

/** Kept private to data: IDs shown on the web are opaque hashes of SAF document URIs. */
internal fun opaqueDocumentId(uriString: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(uriString.toByteArray(Charsets.UTF_8))
    return Base64
        .getUrlEncoder()
        .withoutPadding()
        .encodeToString(digest)
        .take(22)
}
