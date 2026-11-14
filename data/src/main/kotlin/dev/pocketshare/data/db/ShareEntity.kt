package dev.pocketshare.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "shares",
    indices = [
        Index(value = ["token"], unique = true),
        Index(value = ["createdAtEpochMs"]),
    ],
)
data class ShareEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val token: String,
    val kind: String,
    val displayName: String,
    val filesJson: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val passwordHash: String?,
    val passwordSalt: String?,
    val passwordIterations: Int,
    val maxDownloads: Int?,
    val downloadCount: Int,
    val revokedAtEpochMs: Long?,
)

@Dao
interface ShareDao {
    @Query("SELECT * FROM shares ORDER BY createdAtEpochMs DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ShareEntity>>

    @Query("SELECT * FROM shares WHERE token = :token LIMIT 1")
    suspend fun findByToken(token: String): ShareEntity?

    @Query("SELECT * FROM shares WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ShareEntity?

    @Insert
    suspend fun insert(entity: ShareEntity): Long

    @Query("UPDATE shares SET expiresAtEpochMs = :expiresAtEpochMs WHERE id = :id")
    suspend fun updateExpiry(
        id: Long,
        expiresAtEpochMs: Long?,
    )

    @Query(
        "UPDATE shares SET displayName = :displayName, " +
            "expiresAtEpochMs = :expiresAtEpochMs, " +
            "passwordHash = :passwordHash, " +
            "passwordSalt = :passwordSalt, " +
            "passwordIterations = :passwordIterations, " +
            "maxDownloads = :maxDownloads, " +
            "filesJson = :filesJson " +
            "WHERE id = :id",
    )
    suspend fun updateDetails(
        id: Long,
        displayName: String,
        expiresAtEpochMs: Long?,
        passwordHash: String?,
        passwordSalt: String?,
        passwordIterations: Int,
        maxDownloads: Int?,
        filesJson: String,
    )

    @Query("UPDATE shares SET revokedAtEpochMs = :nowEpochMs WHERE id = :id")
    suspend fun revoke(
        id: Long,
        nowEpochMs: Long,
    )

    @Query("DELETE FROM shares WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "UPDATE shares SET downloadCount = downloadCount + 1 " +
            "WHERE id = :id " +
            "AND revokedAtEpochMs IS NULL " +
            "AND (expiresAtEpochMs IS NULL OR expiresAtEpochMs > :nowEpochMs) " +
            "AND (maxDownloads IS NULL OR downloadCount < maxDownloads)",
    )
    suspend fun tryReserveDownload(
        id: Long,
        nowEpochMs: Long,
    ): Int
}
