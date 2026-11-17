package dev.pocketshare.ui.components

import dev.pocketshare.data.traffic.TrafficBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

class TrafficChartDataTest {
    private val zone = ZoneOffset.UTC
    private val locale = Locale.US
    private val now = epoch("2026-11-17T12:34:56")

    @Test
    fun dayWindowSumsOnlyTheLastTwentyFourHours() {
        val buckets =
            listOf(
                bucket("2026-11-16T13:00:00", sent = 10, received = 5),
                bucket("2026-11-16T12:00:00", sent = 999, received = 999),
                bucket("2026-11-17T11:00:00", sent = 50, received = 25),
                bucket("2026-11-17T12:00:00", sent = 100, received = 200),
            )

        val series = buildTrafficSeries(buckets, TrafficRange.DAY, now, zone, locale)

        assertEquals(24, series.points.size)
        assertEquals("13:00", series.points.first().label)
        assertEquals("12:00", series.points.last().label)
        assertEquals(160L, series.sentBytes)
        assertEquals(230L, series.receivedBytes)
        assertEquals(100L, series.peakBytes)
        assertFalse(series.isEmpty)
    }

    @Test
    fun weekWindowGroupsHoursByLocalDate() {
        val buckets =
            listOf(
                bucket("2026-11-16T10:00:00", sent = 100, received = 50),
                bucket("2026-11-16T18:00:00", sent = 25, received = 10),
                bucket("2026-11-17T09:00:00", sent = 20, received = 30),
            )

        val series = buildTrafficSeries(buckets, TrafficRange.WEEK, now, zone, locale)

        assertEquals(7, series.points.size)
        assertEquals("Wed", series.points.first().label)
        assertEquals("Tue", series.points.last().label)
        assertEquals(125L, series.points[5].sentBytes)
        assertEquals(60L, series.points[5].receivedBytes)
        assertEquals(20L, series.points[6].sentBytes)
        assertEquals(145L, series.sentBytes)
        assertEquals(90L, series.receivedBytes)
    }

    @Test
    fun monthWindowCrossesTheYearBoundary() {
        val buckets =
            listOf(
                bucket("2025-12-05T08:00:00", sent = 1_000, received = 2_000),
                bucket("2026-01-02T08:00:00", sent = 300, received = 400),
                bucket("2025-01-31T08:00:00", sent = 9_999, received = 9_999),
            )

        val series = buildTrafficSeries(buckets, TrafficRange.MONTH, epoch("2026-01-15T12:00:00"), zone, locale)

        assertEquals(12, series.points.size)
        assertEquals("Feb", series.points.first().label)
        assertEquals("Jan", series.points.last().label)
        assertEquals("Dec", series.points[10].label)
        assertEquals(1_000L, series.points[10].sentBytes)
        assertEquals(300L, series.points[11].sentBytes)
        assertEquals(1_300L, series.sentBytes)
    }

    @Test
    fun yearWindowCoversFiveCalendarYears() {
        val buckets =
            listOf(
                bucket("2022-06-01T00:00:00", sent = 500, received = 250),
                bucket("2026-01-01T00:00:00", sent = 100, received = 50),
                bucket("2021-12-31T23:00:00", sent = 9_999, received = 9_999),
            )

        val series = buildTrafficSeries(buckets, TrafficRange.YEAR, now, zone, locale)

        assertEquals(5, series.points.size)
        assertEquals("2022", series.points.first().label)
        assertEquals("2026", series.points.last().label)
        assertEquals(600L, series.sentBytes)
        assertEquals(300L, series.receivedBytes)
    }

    @Test
    fun sinceCoversTheOldestPointOfEachWindow() {
        val daySince = TrafficRange.DAY.sinceEpochMs(now, zone)
        assertEquals(epoch("2026-11-16T13:00:00"), daySince)

        val weekSince = TrafficRange.WEEK.sinceEpochMs(now, zone)
        assertEquals(epoch("2026-11-11T00:00:00"), weekSince)

        val monthSince = TrafficRange.MONTH.sinceEpochMs(now, zone)
        assertEquals(epoch("2025-12-01T00:00:00"), monthSince)

        val yearSince = TrafficRange.YEAR.sinceEpochMs(now, zone)
        assertEquals(epoch("2022-01-01T00:00:00"), yearSince)
    }

    @Test
    fun emptyHistoryIsReported() {
        val series = buildTrafficSeries(emptyList(), TrafficRange.DAY, now, zone, locale)

        assertEquals(24, series.points.size)
        assertEquals(0L, series.peakBytes)
        assertTrue(series.isEmpty)
    }

    private fun bucket(
        localDateTime: String,
        sent: Long,
        received: Long,
    ): TrafficBucket =
        TrafficBucket(
            bucketStartEpochMs = epoch(localDateTime),
            sentBytes = sent,
            receivedBytes = received,
        )

    private fun epoch(localDateTime: String): Long =
        LocalDateTime
            .parse(localDateTime)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
}
