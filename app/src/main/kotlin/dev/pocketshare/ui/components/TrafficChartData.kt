package dev.pocketshare.ui.components

import dev.pocketshare.data.traffic.TrafficBucket
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Time windows offered by the network usage graph. */
enum class TrafficRange {
    DAY,
    WEEK,
    MONTH,
    YEAR,
    ;

    /**
     * Earliest bucket that can contribute to this range. The history query uses it so the
     * dialog only reads the window it is about to draw.
     */
    fun sinceEpochMs(
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
        return when (this) {
            DAY ->
                now
                    .truncatedTo(ChronoUnit.HOURS)
                    .minusHours(WINDOW_HOURS - 1L)
                    .toInstant()
                    .toEpochMilli()

            WEEK ->
                now
                    .toLocalDate()
                    .minusDays(WINDOW_DAYS - 1L)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            MONTH ->
                YearMonth
                    .from(now)
                    .minusMonths(WINDOW_MONTHS - 1L)
                    .atDay(1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()

            YEAR ->
                LocalDate
                    .of(now.year - (WINDOW_YEARS - 1), 1, 1)
                    .atStartOfDay(zoneId)
                    .toInstant()
                    .toEpochMilli()
        }
    }
}

/** One bar group: the bucket label and the bytes moved in both directions. */
data class TrafficPoint(
    val label: String,
    val sentBytes: Long,
    val receivedBytes: Long,
)

data class TrafficSeries(
    val points: List<TrafficPoint>,
    /** Tallest single-direction bar, used to scale the graph. */
    val peakBytes: Long,
    val sentBytes: Long,
    val receivedBytes: Long,
) {
    val isEmpty: Boolean
        get() = sentBytes == 0L && receivedBytes == 0L
}

/**
 * Aggregates persisted hourly buckets into the points of one range. Buckets are stored at the
 * start of their local hour, so day, month, and year boundaries line up with the phone clock.
 */
fun buildTrafficSeries(
    buckets: List<TrafficBucket>,
    range: TrafficRange,
    nowEpochMs: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): TrafficSeries {
    val now = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
    val points =
        when (range) {
            TrafficRange.DAY -> hourlyPoints(buckets, now, locale)
            TrafficRange.WEEK -> dailyPoints(buckets, now, zoneId, locale)
            TrafficRange.MONTH -> monthlyPoints(buckets, now, zoneId, locale)
            TrafficRange.YEAR -> yearlyPoints(buckets, now, zoneId)
        }
    return TrafficSeries(
        points = points,
        peakBytes = points.maxOfOrNull { maxOf(it.sentBytes, it.receivedBytes) } ?: 0L,
        sentBytes = points.sumOf { it.sentBytes },
        receivedBytes = points.sumOf { it.receivedBytes },
    )
}

private fun hourlyPoints(
    buckets: List<TrafficBucket>,
    now: ZonedDateTime,
    locale: Locale,
): List<TrafficPoint> {
    val formatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    val currentHour = now.truncatedTo(ChronoUnit.HOURS)
    return (WINDOW_HOURS - 1 downTo 0).map { offset ->
        val start = currentHour.minusHours(offset.toLong())
        val end = start.plusHours(1)
        val (sent, received) = sumRange(buckets, start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
        TrafficPoint(label = start.format(formatter), sentBytes = sent, receivedBytes = received)
    }
}

private fun dailyPoints(
    buckets: List<TrafficBucket>,
    now: ZonedDateTime,
    zoneId: ZoneId,
    locale: Locale,
): List<TrafficPoint> {
    val formatter = DateTimeFormatter.ofPattern("EEE", locale)
    val today = now.toLocalDate()
    return (WINDOW_DAYS - 1 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong())
        val start = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end =
            date
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val (sent, received) = sumRange(buckets, start, end)
        TrafficPoint(label = date.format(formatter), sentBytes = sent, receivedBytes = received)
    }
}

private fun monthlyPoints(
    buckets: List<TrafficBucket>,
    now: ZonedDateTime,
    zoneId: ZoneId,
    locale: Locale,
): List<TrafficPoint> {
    val formatter = DateTimeFormatter.ofPattern("MMM", locale)
    val currentMonth = YearMonth.from(now)
    return (WINDOW_MONTHS - 1 downTo 0).map { offset ->
        val month = currentMonth.minusMonths(offset.toLong())
        val start =
            month
                .atDay(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val end =
            month
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val (sent, received) = sumRange(buckets, start, end)
        TrafficPoint(label = month.format(formatter), sentBytes = sent, receivedBytes = received)
    }
}

private fun yearlyPoints(
    buckets: List<TrafficBucket>,
    now: ZonedDateTime,
    zoneId: ZoneId,
): List<TrafficPoint> {
    val currentYear = now.year
    return (WINDOW_YEARS - 1 downTo 0).map { offset ->
        val year = currentYear - offset
        val start =
            LocalDate
                .of(year, 1, 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val end =
            LocalDate
                .of(year + 1, 1, 1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
        val (sent, received) = sumRange(buckets, start, end)
        TrafficPoint(label = year.toString(), sentBytes = sent, receivedBytes = received)
    }
}

private fun sumRange(
    buckets: List<TrafficBucket>,
    startEpochMs: Long,
    endEpochMs: Long,
): Pair<Long, Long> {
    var sent = 0L
    var received = 0L
    for (bucket in buckets) {
        if (bucket.bucketStartEpochMs >= startEpochMs && bucket.bucketStartEpochMs < endEpochMs) {
            sent += bucket.sentBytes
            received += bucket.receivedBytes
        }
    }
    return sent to received
}

private const val WINDOW_HOURS = 24
private const val WINDOW_DAYS = 7
private const val WINDOW_MONTHS = 12
private const val WINDOW_YEARS = 5
