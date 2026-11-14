package dev.pocketshare.ui.viewmodel

import android.net.TrafficStats
import android.os.Process

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

/** Thin wrapper over [TrafficStats] so the view model does not deal with sentinels. */
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
