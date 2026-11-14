package dev.pocketshare.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareStateTest {
    @Test
    fun `state transitions`() {
        assertEquals(ShareState.ACTIVE, shareState(100, null, null, null, 0))
        assertEquals(ShareState.EXPIRED, shareState(100, 100, null, null, 0))
        assertEquals(ShareState.EXPIRED, shareState(101, 100, null, null, 0))
        assertEquals(ShareState.REVOKED, shareState(1, null, 2, null, 0))
        assertEquals(ShareState.EXHAUSTED, shareState(1, null, null, 2, 2))
        assertEquals(ShareState.ACTIVE, shareState(1, null, null, 2, 1))
    }

    @Test
    fun `expiry options compute deadlines`() {
        assertEquals(3_601_000L, ShareExpiry.HOUR_1.expiresAt(1_000L))
        assertEquals(86_401_000L, ShareExpiry.HOURS_24.expiresAt(1_000L))
        assertEquals(604_801_000L, ShareExpiry.DAYS_7.expiresAt(1_000L))
        assertEquals(null, ShareExpiry.UNLIMITED.expiresAt(1_000L))
    }

    @Test
    fun `record availability includes download limit`() {
        val record =
            ShareRecord(
                id = 1,
                token = "t",
                kind = ShareKind.FILES,
                displayName = "files",
                files = emptyList(),
                createdAtEpochMs = 0,
                expiresAtEpochMs = null,
                passwordHash = null,
                passwordSalt = null,
                passwordIterations = 0,
                maxDownloads = 1,
                downloadCount = 1,
                revokedAtEpochMs = null,
            )
        assertFalse(record.isAvailable(0))
        assertEquals(ShareState.EXHAUSTED, record.state(0))
        assertFalse(record.hasPassword)
        assertTrue(record.copy(downloadCount = 0).isAvailable(0))
    }
}
