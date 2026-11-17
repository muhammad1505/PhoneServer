package dev.pocketshare.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One hour of traffic moved by this app's UID, kept so the history graph survives restarts. */
@Entity(tableName = "traffic_samples")
data class TrafficSampleEntity(
    @PrimaryKey val bucketStartEpochMs: Long,
    val sentBytes: Long,
    val receivedBytes: Long,
)

@Dao
interface TrafficSampleDao {
    @Query(
        "SELECT * FROM traffic_samples " +
            "WHERE bucketStartEpochMs >= :fromEpochMs " +
            "ORDER BY bucketStartEpochMs ASC",
    )
    fun observeSince(fromEpochMs: Long): Flow<List<TrafficSampleEntity>>

    @Query("SELECT * FROM traffic_samples WHERE bucketStartEpochMs = :bucketStartEpochMs LIMIT 1")
    suspend fun find(bucketStartEpochMs: Long): TrafficSampleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrafficSampleEntity)
}
