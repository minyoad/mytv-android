package top.yogiczy.mytv.core.data.entities.epg

import kotlinx.serialization.Serializable
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.utils.Logger
import java.util.Calendar

/**
 * 频道节目单
 */
@Serializable
data class Epg(
    /**
     * 频道名称
     */
    val channel: String = "",

    /**
     * 节目列表
     */
    val programmeList: List<EpgProgramme> = EpgProgrammeList(),
) {
    companion object {
        private val log = Logger.create(javaClass.simpleName)

        fun Epg.recentProgramme(): EpgProgrammeRecent {
            val currentTime = System.currentTimeMillis()

            // 确保 programmeList 按 startAt 排序
            if (programmeList.isEmpty()) {
                log.d("No programmes available for channel=${channel}")
                return EpgProgrammeRecent()
            }

            // 确保 programmeList 按 startAt 排序
            val sortedProgrammeList = programmeList.sortedBy { it.startAt }

            val liveProgramIndex = sortedProgrammeList.binarySearch {
                when {
                    currentTime < it.startAt -> 1
                    currentTime > it.endAt -> -1
                    else -> 0
                }
            }

//            log.d("channel=${channel},liveProgramIndex=$liveProgramIndex")

            return if (liveProgramIndex >= 0) {
                EpgProgrammeRecent(
                    now = sortedProgrammeList[liveProgramIndex],
                    next = sortedProgrammeList.getOrNull(liveProgramIndex + 1)
                )
            } else {
                EpgProgrammeRecent()
            }
        }

        fun example(channel: Channel): Epg {
            return Epg(
                channel = channel.epgName,
                programmeList = EpgProgrammeList(
                    List(100) { index ->
                        val startAt =
                            System.currentTimeMillis() - 3500 * 1000 * 24 * 2 + index * 3600 * 1000
                        EpgProgramme(
                            title = "${channel.epgName}节目${index + 1}",
                            startAt = startAt,
                            endAt = startAt + 3600 * 1000
                        )
                    }
                )
            )
        }

        fun empty(channel: Channel): Epg {
            val dayStart = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val dayEnd = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }

            return Epg(
                channel = channel.epgName,
                programmeList = EpgProgrammeList(
                    listOf(
                        EpgProgramme(
                            title = "暂无节目",
                            startAt = dayStart.timeInMillis,
                            endAt = dayEnd.timeInMillis,
                        )
                    )
                )
            )
        }
    }
}