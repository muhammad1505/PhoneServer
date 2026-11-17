package dev.pocketshare.data.traffic

import dev.pocketshare.data.db.TrafficSampleDao
import dev.pocketshare.data.db.TrafficSampleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Aggregated bytes for one chart bucket, measured from this app's UID. */
data class TrafficBucket(
    val bucketStartEpochMs: Long,
    val sentBytes: Long,
    val receivedBytes: Long,
)

@Singleton
class TrafficHistoryRepository @Inject constructor(
    private val dao: TrafficSampleDao,
) {
    private val writeMutex = Mutex()

    fun observeSince(fromEpochMs: Long): Flow<List<TrafficBucket>> =
        dao.observeSince(fromEpochMs).map { entities ->
            entities.map { entity ->
                TrafficBucket(
                    bucketStartEpochMs = entity.bucketStartEpochMs,
                    sentBytes = entity.sentBytes,
                    receivedBytes = entity.receivedBytes,
                )
            }
        }

    /** Adds a sample to its hour bucket so the history is not rewritten for every poll. */
    suspend fun record(
        bucketStartEpochMs: Long,
        sentBytes: Long,
        receivedBytes: Long,
    ) {
        if (sentBytes <= 0L && receivedBytes <= 0L) return
        writeMutex.withLock {
            val existing = dao.find(bucketStartEpochMs)
            dao.upsert(
                TrafficSampleEntity(
                    bucketStartEpochMs = bucketStartEpochMs,
                    sentBytes = (existing?.sentBytes ?: 0L) + sentBytes,
                    receivedBytes = (existing?.receivedBytes ?: 0L) + receivedBytes,
                ),
            )
        }
    }
}
