package com.vigyan.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class LockRulesTest {
    @Test
    fun waitGrowsAndStopsAtFifteenMinutes() {
        assertEquals(30_000L, LockRules.waitMillis(5))
        assertEquals(60_000L, LockRules.waitMillis(10))
        assertEquals(120_000L, LockRules.waitMillis(15))
        assertEquals(15 * 60_000L, LockRules.waitMillis(50))
        assertEquals(15 * 60_000L, LockRules.waitMillis(500))
    }

    @Test
    fun tagFromLatestReleaseAddress() {
        assertEquals("v1.0.50", Updater.tagFromLocation("https://github.com/vigyaninternational/vigyan-scanner/releases/tag/v1.0.50"))
        assertEquals(null, Updater.tagFromLocation("https://github.com/vigyaninternational/vigyan-scanner/releases"))
        assertEquals(null, Updater.tagFromLocation(null))
    }
}
