package top.yogiczy.mytv.core.data.repositories.epg

import java.util.Calendar
import java.util.TimeZone

/** The setting is a local hour of day, not a cache duration. Missing caches always refresh. */
object EpgRefreshPolicy {
    fun isExpired(checkedAt: Long, now: Long, thresholdHour: Int, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val boundary = Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, thresholdHour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis > now) add(Calendar.DAY_OF_YEAR, -1)
        }.timeInMillis
        return checkedAt < boundary || checkedAt > now
    }
}
