package edu.uestc.nightsign.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test fun treatsSameVersionAsNotNewer() {
        assertFalse(AppVersion.isRemoteNewer("v0.1.0", "0.1.0"))
    }

    @Test fun detectsHigherPatchAndMinor() {
        assertTrue(AppVersion.isRemoteNewer("0.1.1", "0.1.0"))
        assertTrue(AppVersion.isRemoteNewer("v0.2.0", "0.1.9"))
        assertFalse(AppVersion.isRemoteNewer("0.1.0", "0.1.1"))
    }
}
