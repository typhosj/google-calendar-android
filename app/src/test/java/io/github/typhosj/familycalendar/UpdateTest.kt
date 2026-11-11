package io.github.typhosj.familycalendar

import android.content.pm.PackageInstaller
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
    fun `the installer result asks the user only when Android wants a confirmation`() {
        assertEquals(InstallOutcome.CONFIRM, installOutcome(PackageInstaller.STATUS_PENDING_USER_ACTION))
        assertEquals(InstallOutcome.DONE, installOutcome(PackageInstaller.STATUS_SUCCESS))
    }

    @Test
    fun `every other installer result is a failure`() {
        for (status in listOf(
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE,
            -999,
            42,
        )) {
            assertEquals("status $status", InstallOutcome.FAILED, installOutcome(status))
        }
    }

    @Test
    fun `an unreadable version is never newer`() {
        assertFalse(isNewer("abc", "0.1.0"))
        assertFalse(isNewer("0.2.0", "0.1.0-debug"))
        assertFalse(isNewer("0.2.0", null))
    }
}
