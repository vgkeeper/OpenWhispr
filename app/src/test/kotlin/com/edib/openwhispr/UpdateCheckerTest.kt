package com.edib.openwhispr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `fork SemVer release compares numerically`() {
        assertTrue(UpdateChecker.isNewer("v3.11.0", "3.10.0"))
        assertFalse(UpdateChecker.isNewer("v3.10.0", "3.11.0"))
        assertFalse(UpdateChecker.isNewer("3.11.0", "3.11.0"))
    }
}
