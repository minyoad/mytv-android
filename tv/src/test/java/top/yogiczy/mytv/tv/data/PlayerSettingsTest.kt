package top.yogiczy.mytv.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerSettingsTest {
    @Test fun invalidTimeoutsAreBoundedAndSupportedValuesArePreserved() {
        assertEquals(1000L, PlayerSettings.normalizeTimeout(Long.MIN_VALUE))
        assertEquals(1000L, PlayerSettings.normalizeTimeout(0))
        assertEquals(120000L, PlayerSettings.normalizeTimeout(Long.MAX_VALUE))
        for (seconds in listOf(3, 5, 10, 15, 20, 25, 30)) {
            assertEquals(seconds * 1000L, PlayerSettings.normalizeTimeout(seconds * 1000L))
        }
    }
}
