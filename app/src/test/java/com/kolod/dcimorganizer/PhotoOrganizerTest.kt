package com.kolod.dcimorganizer

import com.kolod.dcimorganizer.PhotoOrganizer.Companion.computeTargetRelativePath
import com.kolod.dcimorganizer.PhotoOrganizer.Companion.isMediaMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Unit tests for the pure helpers on [PhotoOrganizer].
 *
 * These cover the parts of the organizer that are independent of the Android framework:
 * target-path computation (which timestamp wins, month padding, timezone handling) and the
 * MIME-type filter. The MediaStore-dependent parts of [PhotoOrganizer.organize] aren't
 * exercised here — they need either Robolectric or an instrumented test.
 */
class PhotoOrganizerTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** Build a millisecond timestamp for (year, month, day, hour, minute) in the given zone. */
    private fun instant(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0,
        zone: TimeZone = utc
    ): Long {
        val cal = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }
        return cal.timeInMillis
    }

    // --- computeTargetRelativePath: date selection ---------------------------------

    @Test
    fun `uses DATE_TAKEN when present`() {
        val taken = instant(2024, 5, 15)
        val modified = instant(2022, 1, 1)
        assertEquals(
            "DCIM/2024/05/",
            computeTargetRelativePath(taken, modified, utc)
        )
    }

    @Test
    fun `falls back to DATE_MODIFIED when DATE_TAKEN is zero`() {
        val modified = instant(2023, 8, 20)
        assertEquals(
            "DCIM/2023/08/",
            computeTargetRelativePath(dateTakenMs = 0L, dateModifiedMs = modified, timeZone = utc)
        )
    }

    @Test
    fun `falls back to DATE_MODIFIED when DATE_TAKEN is negative`() {
        // DATE_TAKEN should never be negative, but guard against garbage just in case.
        val modified = instant(2023, 8, 20)
        assertEquals(
            "DCIM/2023/08/",
            computeTargetRelativePath(dateTakenMs = -1L, dateModifiedMs = modified, timeZone = utc)
        )
    }

    // --- computeTargetRelativePath: formatting -------------------------------------

    @Test
    fun `january is padded to 01`() {
        val taken = instant(2024, 1, 3)
        assertEquals("DCIM/2024/01/", computeTargetRelativePath(taken, 0L, utc))
    }

    @Test
    fun `september is padded to 09`() {
        val taken = instant(2024, 9, 3)
        assertEquals("DCIM/2024/09/", computeTargetRelativePath(taken, 0L, utc))
    }

    @Test
    fun `december is formatted as 12`() {
        val taken = instant(2024, 12, 31)
        assertEquals("DCIM/2024/12/", computeTargetRelativePath(taken, 0L, utc))
    }

    @Test
    fun `target path always ends with a trailing slash`() {
        val taken = instant(2024, 6, 1)
        val path = computeTargetRelativePath(taken, 0L, utc)
        assertTrue("expected trailing slash on '$path'", path.endsWith("/"))
    }

    @Test
    fun `epoch zero with no date taken resolves to January 1970`() {
        // A file with neither a DATE_TAKEN nor a DATE_MODIFIED lands on the epoch.
        // We don't crash — we just file it under 1970/01. Callers can decide whether they
        // care; the organizer treats this as a best-effort placement.
        assertEquals("DCIM/1970/01/", computeTargetRelativePath(0L, 0L, utc))
    }

    // --- computeTargetRelativePath: timezone ---------------------------------------

    @Test
    fun `timezone shifts a midnight-boundary photo into the previous month`() {
        // 2024-06-01 00:30 UTC is still 2024-05-31 in a UTC-5 zone.
        val taken = instant(2024, 6, 1, hour = 0, minute = 30, zone = utc)
        val eastern = TimeZone.getTimeZone("America/New_York") // UTC-4 or -5
        assertEquals("DCIM/2024/05/", computeTargetRelativePath(taken, 0L, eastern))
    }

    @Test
    fun `same timestamp resolves differently in UTC vs Tokyo`() {
        // 2024-12-31 20:00 UTC is already 2025-01-01 in Tokyo (UTC+9).
        val taken = instant(2024, 12, 31, hour = 20, minute = 0, zone = utc)
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        assertEquals("DCIM/2024/12/", computeTargetRelativePath(taken, 0L, utc))
        assertEquals("DCIM/2025/01/", computeTargetRelativePath(taken, 0L, tokyo))
    }

    @Test
    fun `default timezone overload uses the JVM default`() {
        // Temporarily fix the JVM default so the overload is deterministic.
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(utc)
            val taken = instant(2024, 7, 4, zone = utc)
            assertEquals("DCIM/2024/07/", computeTargetRelativePath(taken, 0L))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    // --- computeTargetRelativePath: skip-if-already-organized contract -------------

    @Test
    fun `recomputing from within the target folder produces the same path`() {
        // This is the contract organize() relies on to skip already-organized files:
        // a file whose relativePath equals computeTargetRelativePath(...) is left alone.
        val taken = instant(2024, 5, 15)
        val first = computeTargetRelativePath(taken, 0L, utc)
        val second = computeTargetRelativePath(taken, 0L, utc)
        assertEquals(first, second)
        assertEquals("DCIM/2024/05/", first)
    }

    // --- isMediaMime ----------------------------------------------------------------

    @Test
    fun `image mime types are accepted`() {
        assertTrue(isMediaMime("image/jpeg"))
        assertTrue(isMediaMime("image/png"))
        assertTrue(isMediaMime("image/heic"))
        assertTrue(isMediaMime("image/webp"))
    }

    @Test
    fun `video mime types are accepted`() {
        assertTrue(isMediaMime("video/mp4"))
        assertTrue(isMediaMime("video/quicktime"))
        assertTrue(isMediaMime("video/3gpp"))
    }

    @Test
    fun `non-media mime types are rejected`() {
        assertFalse(isMediaMime("application/pdf"))
        assertFalse(isMediaMime("text/plain"))
        assertFalse(isMediaMime("audio/mpeg"))
        assertFalse(isMediaMime("application/octet-stream"))
    }

    @Test
    fun `null and blank mime types are rejected`() {
        assertFalse(isMediaMime(null))
        assertFalse(isMediaMime(""))
    }

    @Test
    fun `mime types that only contain the prefix as a substring are rejected`() {
        // Defensive: "fooimage/bar" should not be accepted just because it contains "image/".
        assertFalse(isMediaMime("fooimage/bar"))
        assertFalse(isMediaMime("xvideo/mp4"))
    }

    // --- OrganizeResult -------------------------------------------------------------

    @Test
    fun `OrganizeResult equality reflects its fields`() {
        val a = PhotoOrganizer.OrganizeResult(movedCount = 3, pendingPermissionUris = emptyList())
        val b = PhotoOrganizer.OrganizeResult(movedCount = 3, pendingPermissionUris = emptyList())
        val c = PhotoOrganizer.OrganizeResult(movedCount = 4, pendingPermissionUris = emptyList())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}
