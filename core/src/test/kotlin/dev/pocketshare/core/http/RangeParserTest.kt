package dev.pocketshare.core.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeParserTest {
    @Test
    fun `missing header means full content`() {
        assertEquals(RangeSelection.Full, RangeParser.parse(null, 100))
        assertEquals(RangeSelection.Full, RangeParser.parse("", 100))
        assertEquals(RangeSelection.Full, RangeParser.parse("bytes=0-9", null))
    }

    @Test
    fun `open ended and bounded ranges`() {
        assertEquals(RangeSelection.Partial(0, 9), RangeParser.parse("bytes=0-9", 100))
        assertEquals(RangeSelection.Partial(10, 99), RangeParser.parse("bytes=10-", 100))
        assertEquals(RangeSelection.Partial(95, 99), RangeParser.parse("bytes=-5", 100))
        assertEquals(RangeSelection.Partial(0, 99), RangeParser.parse("bytes=-500", 100))
        assertEquals(RangeSelection.Partial(0, 0), RangeParser.parse("bytes=0-0", 100))
        assertEquals(RangeSelection.Partial(99, 99), RangeParser.parse("bytes=99-999", 100))
        assertEquals(RangeSelection.Partial(0, 99), RangeParser.parse("BYTES=0-999", 100))
    }

    @Test
    fun `unsatisfiable ranges`() {
        assertEquals(RangeSelection.Unsatisfiable, RangeParser.parse("bytes=100-", 100))
        assertEquals(RangeSelection.Unsatisfiable, RangeParser.parse("bytes=200-300", 100))
        assertEquals(RangeSelection.Unsatisfiable, RangeParser.parse("bytes=5-4", 100))
        assertEquals(RangeSelection.Unsatisfiable, RangeParser.parse("bytes=-0", 100))
        assertEquals(RangeSelection.Unsatisfiable, RangeParser.parse("bytes=0-1", 0))
    }

    @Test
    fun `malformed and multipart ranges are unsupported`() {
        assertEquals(RangeSelection.Unsupported, RangeParser.parse("items=0-1", 100))
        assertEquals(RangeSelection.Unsupported, RangeParser.parse("bytes=0-1,5-6", 100))
        assertEquals(RangeSelection.Unsupported, RangeParser.parse("bytes=-", 100))
        assertEquals(RangeSelection.Unsupported, RangeParser.parse("bytes=abc-def", 100))
        assertEquals(RangeSelection.Unsupported, RangeParser.parse("bytes=--1", 100))
        assertEquals(RangeSelection.Unsupported, RangeParser.parse("bytes=1-2-3", 100))
        assertEquals(RangeSelection.Unsupported, RangeParser.parse("bytes=999999999999999999999999-", 100))
    }

    @Test
    fun `partial length is inclusive`() {
        val range = RangeParser.parse("bytes=5-9", 100) as RangeSelection.Partial
        assertEquals(5, range.length)
        assertTrue(range.length > 0)
    }
}
