package io.github.typhosj.familycalendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    private val base = "https://github.com/typhosj/google-calendar-android/releases"

    @Test
    fun `reads the version from the redirect to the latest release`() {
        assertEquals("1.2.3", versionFromRedirect("$base/tag/v1.2.3"))
    }

    @Test
    fun `no release or an unknown target gives no version`() {
        assertNull(versionFromRedirect(null))
        assertNull(versionFromRedirect(""))
        assertNull(versionFromRedirect(base))
        assertNull(versionFromRedirect("$base/tag/nightly"))
        assertNull(versionFromRedirect("$base/tag/v1.2"))
        assertNull(versionFromRedirect("$base/tag/v1.2.3-beta"))
    }

    @Test
    fun `a redirect to another host is not trusted`() {
        assertNull(versionFromRedirect("https://example.test/typhosj/google-calendar-android/releases/tag/v9.9.9"))
    }

    @Test
    fun `newer compares numbers, not text`() {
        assertTrue(isNewer("0.10.0", "0.9.0"))
        assertTrue(isNewer("1.0.0", "0.99.99"))
        assertTrue(isNewer("0.1.1", "0.1.0"))
    }

    @Test
    fun `same or older is not newer`() {
        assertFalse(isNewer("0.1.0", "0.1.0"))
        assertFalse(isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun `an unreadable version is never newer`() {
        assertFalse(isNewer("abc", "0.1.0"))
        assertFalse(isNewer("0.2.0", "0.1.0-debug"))
        assertFalse(isNewer("0.2.0", null))
    }
}
