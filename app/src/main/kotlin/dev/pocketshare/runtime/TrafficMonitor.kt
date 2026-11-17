package dev.pocketshare.runtime

import android.net.TrafficStats
import android.os.Process
import dev.pocketshare.core.log.AppLogger
import dev.pocketshare.data.traffic.TrafficHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bytes moved by this app's UID since the current sharing session started.
 *
 * The counters cover every interface, which is what the tunnel actually uses: while the phone is
 * on mobile data the numbers are plan usage, on Wi-Fi they describe local network throughput.
 */
data class TrafficUsage(
    val sentBytes: Long = 0L,
    val receivedBytes: Long = 0L,
    val available: Boolean = false,
)

/** Thin wrapper over [TrafficStats] so the monitor does not deal with sentinels. */
internal object TrafficMeter {
    /**
     * Cumulative `(sent, received)` byte counters for this process, or null when the runtime does
     * not expose per-UID accounting (a few OEM ROMs return -1).
     */
    fun sample(): Sample? {
        val uid = Process.myUid()
        val sent = TrafficStats.getUidTxBytes(uid)
        val received = TrafficStats.getUidRxBytes(uid)
        if (sent < 0L || received < 0L) return null
        return Sample(sent = sent, received = received)
    }

    data class Sample(
        val sent: Long,
        val received: Long,
    )
}

/**
 * Samples the session meter and writes hourly history buckets.
 *
 * Sampling is owned by this singleton instead of a screen view model so the history keeps
 * growing while Pocket Share runs in the background. The session meter still resets for every
 * start, while the persisted history keeps the daily, weekly, monthly, and yearly totals.
 */
@Singleton
class TrafficMonitor @Inject constructor(
    private val history: TrafficHistoryRepository,
    private val logger: AppLogger,
    private val clock: Clock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableSession = MutableStateFlow(TrafficUsage())
    val session: StateFlow<TrafficUsage> = mutableSession.asStateFlow()

    private var sampleJob: Job? = null
    private var baseline: TrafficMeter.Sample? = null
    private var previous: TrafficMeter.Sample? = null

    fun observe(state: StateFlow<SharingState>) {
        scope.launch {
            state
                .map { it.isSharing }
                .distinctUntilChanged()
                .collect { sharing ->
                    if (sharing) startSession() else endSession()
                }
        }
    }

    private fun startSession() {
        sampleJob?.cancel()
        baseline = null
        previous = null
        mutableSession.value = TrafficUsage()
        sampleJob =
            scope.launch {
                val pending = HashMap<Long, LongArray>()
                var lastFlush = clock.millis()
                try {
                    while (isActive) {
                        val sample = TrafficMeter.sample()
                        if (sample == null) {
                            if (mutableSession.value.available) {
                                mutableSession.value = TrafficUsage()
                            }
                        } else {
                            val start = baseline ?: sample.also { baseline = it }
                            previous?.let { last ->
                                val sent = (sample.sent - last.sent).coerceAtLeast(0L)
                                val received = (sample.received - last.received).coerceAtLeast(0L)
                                if (sent > 0L || received > 0L) {
                                    val totals = pending.getOrPut(bucketStart(clock.millis())) { LongArray(2) }
                                    totals[0] += sent
                                    totals[1] += received
                                }
                            }
                            previous = sample
                            mutableSession.value =
                                TrafficUsage(
                                    sentBytes = (sample.sent - start.sent).coerceAtLeast(0L),
                                    receivedBytes = (sample.received - start.received).coerceAtLeast(0L),
                                    available = true,
                                )
                            val now = clock.millis()
                            if (pending.isNotEmpty() && now - lastFlush >= FLUSH_INTERVAL_MILLIS) {
                                flush(pending)
                                lastFlush = now
                            }
                        }
                        delay(SAMPLE_INTERVAL_MILLIS)
                    }
                } finally {
                    // endSession() cancels this job; keep the last few seconds of usage.
                    withContext(NonCancellable) { flush(pending) }
                }
            }
    }

    private fun endSession() {
        sampleJob?.cancel()
        sampleJob = null
        baseline = null
        previous = null
        mutableSession.value = TrafficUsage()
    }

    private suspend fun flush(pending: MutableMap<Long, LongArray>) {
        if (pending.isEmpty()) return
        val snapshot = pending.map { (bucket, totals) -> bucket to totals.copyOf() }
        pending.clear()
        for ((bucket, totals) in snapshot) {
            runCatching {
                history.record(bucket, totals[0], totals[1])
            }.onFailure { error ->
                logger.warn(TAG, "Unable to store traffic history", error)
            }
        }
    }

    private fun bucketStart(nowEpochMs: Long): Long =
        Instant
            .ofEpochMilli(nowEpochMs)
            .atZone(ZoneId.systemDefault())
            .truncatedTo(ChronoUnit.HOURS)
            .toInstant()
            .toEpochMilli()

    private companion object {
        const val TAG = "Traffic"
        const val SAMPLE_INTERVAL_MILLIS = 3_000L
        const val FLUSH_INTERVAL_MILLIS = 30_000L
    }
}
