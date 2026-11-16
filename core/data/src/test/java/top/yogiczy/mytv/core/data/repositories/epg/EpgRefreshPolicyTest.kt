package top.yogiczy.mytv.core.data.repositories.epg

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class EpgRefreshPolicyTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun time(value: String) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = zone }.parse(value)!!.time
    @Test fun previousDayCacheIsKeptBeforeThreshold() {
        assertFalse(EpgRefreshPolicy.isExpired(time("2026-11-15 08:00"), time("2026-11-16 05:59"), 6, zone))
    }
    @Test fun crossingThresholdRefreshesEvenOnSameDay() {
        assertTrue(EpgRefreshPolicy.isExpired(time("2026-11-16 05:00"), time("2026-11-16 06:00"), 6, zone))
        assertFalse(EpgRefreshPolicy.isExpired(time("2026-11-16 06:00"), time("2026-11-16 06:00"), 6, zone))
    }
    @Test fun olderCacheAndClockRollbackRefresh() {
        assertTrue(EpgRefreshPolicy.isExpired(time("2026-11-14 08:00"), time("2026-11-16 05:59"), 6, zone))
        assertTrue(EpgRefreshPolicy.isExpired(time("2026-11-17 08:00"), time("2026-11-16 05:59"), 6, zone))
    }
}
